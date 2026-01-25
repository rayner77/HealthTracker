package com.inf2007.healthtracker.Screens

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.android.gms.location.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRecordingScreen(
    navController: NavController,
    activityType: String
) {
    val context = LocalContext.current
    val firestore = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()

    var isRecording by remember { mutableStateOf(true) }
    var startTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var elapsedTime by remember { mutableStateOf(0L) }

    // GPS
    val locationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var totalDistance by remember { mutableStateOf(0f) }

    val locationRequest = remember {
        LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L
        ).build()
    }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocation?.let {
                    totalDistance += it.distanceTo(location)
                }
                lastLocation = location
            }
        }
    }

    // Timer
    LaunchedEffect(isRecording) {
        while (isRecording) {
            elapsedTime = System.currentTimeMillis() - startTime
            kotlinx.coroutines.delay(1000)
        }
    }

    // Start GPS immediately
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(activityType) }
            )
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.labelLarge
                )

                Text(
                    text = "${elapsedTime / 60000} min",
                    style = MaterialTheme.typography.displaySmall
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Distance",
                    style = MaterialTheme.typography.labelLarge
                )

                Text(
                    text = "${(totalDistance / 1000).format(2)} km",
                    style = MaterialTheme.typography.displaySmall
                )
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                onClick = {
                    isRecording = false
                    locationClient.removeLocationUpdates(locationCallback)

                    val endTime = System.currentTimeMillis()
                    val durationMinutes =
                        (endTime - startTime) / 60000

                    val activityData = hashMapOf(
                        "userId" to auth.currentUser?.uid,
                        "activityType" to activityType,
                        "startTime" to Timestamp(Date(startTime)),
                        "endTime" to Timestamp(Date(endTime)),
                        "durationMinutes" to durationMinutes,
                        "distanceKm" to totalDistance / 1000,
                        "createdAt" to Timestamp.now()
                    )

                    firestore.collection("activities")
                        .add(activityData)
                        .addOnSuccessListener {
                            Toast.makeText(context, "Activity saved!", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                context,
                                "Save failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            Log.e("RECORDING", "Firestore error", e)
                        }

                }
            ) {
                Text("Stop & Save")
            }
        }
    }
}

fun Float.format(digits: Int): String =
    "%.${digits}f".format(this)
