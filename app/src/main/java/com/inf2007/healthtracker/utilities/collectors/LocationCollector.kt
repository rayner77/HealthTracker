package com.inf2007.healthtracker.utilities.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationCollector(private val context: Context, private val onLocationReady: (Location) -> Unit) : DataCollector {
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
            onLocationReady(currentLocation)
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
}