package com.inf2007.healthtracker.Screens

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.android.gms.location.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import java.util.*
import kotlin.math.roundToInt

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

    // Location
    val locationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var totalDistanceMeters by remember { mutableStateOf(0f) }

    var currentSpeedKmh by remember { mutableStateOf(0f) }
    var averageSpeedKmh by remember { mutableStateOf(0f) }

    // Location request
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

                // Distance
                lastLocation?.let {
                    totalDistanceMeters += it.distanceTo(location)
                }
                lastLocation = location

                // Speed (m/s → km/h)
                currentSpeedKmh = (location.speed * 3.6f).coerceAtLeast(0f)

                // Average speed
                val hours = elapsedTime / 3_600_000f
                if (hours > 0) {
                    averageSpeedKmh = (totalDistanceMeters / 1000f) / hours
                }
            }
        }
    }

    // Timer
    LaunchedEffect(isRecording) {
        while (isRecording) {
            elapsedTime = System.currentTimeMillis() - startTime
            delay(1000)
        }
    }

    // Start GPS
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

    // Calories calculation (simple MET-based)
    val caloriesBurned by remember {
        derivedStateOf {
            val minutes = elapsedTime / 60000f
            val met = when {
                averageSpeedKmh < 6 -> 3.5f       // walking
                averageSpeedKmh < 10 -> 7.0f      // jogging
                else -> 10.0f                     // running
            }
            val weightKg = 70f // default assumption
            ((met * weightKg * minutes) / 60f).roundToInt()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(activityType) })
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                AssistChip(
                    onClick = {},
                    label = { Text("Recording") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.error
                    )
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = formatElapsedTime(elapsedTime),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(24.dp))

                // Stats
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatCard(
                            title = "Distance",
                            value = "${(totalDistanceMeters / 1000).format(2)} km",
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Calories",
                            value = "$caloriesBurned kcal",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatCard(
                            title = "Speed",
                            value = "${currentSpeedKmh.format(1)} km/h",
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Avg Speed",
                            value = "${averageSpeedKmh.format(1)} km/h",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Button(
                onClick = {
                    isRecording = false
                    locationClient.removeLocationUpdates(locationCallback)

                    val endTime = System.currentTimeMillis()
                    val durationMinutes = (endTime - startTime) / 60000

                    val activityData = hashMapOf(
                        "userId" to auth.currentUser?.uid,
                        "activityType" to activityType,
                        "startTime" to Timestamp(Date(startTime)),
                        "endTime" to Timestamp(Date(endTime)),
                        "durationMinutes" to durationMinutes,
                        "distanceKm" to totalDistanceMeters / 1000,
                        "averageSpeedKmh" to averageSpeedKmh,
                        "caloriesBurned" to caloriesBurned,
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
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("STOP & SAVE", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/* ---------- UI COMPONENT ---------- */

@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(110.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/* ---------- HELPERS ---------- */

fun formatElapsedTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

fun Float.format(digits: Int): String =
    "%.${digits}f".format(this)
