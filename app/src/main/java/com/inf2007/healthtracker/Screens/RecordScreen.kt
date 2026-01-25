package com.inf2007.healthtracker.Screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.inf2007.healthtracker.utilities.BottomNavigationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    navController: NavController
) {
    var selectedActivity by remember { mutableStateOf("Running") }
    val activityOptions = listOf("Running", "Cycling", "Walking", "Gym", "Swimming")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Record Activity") })
        },
        bottomBar = {
            BottomNavigationBar(navController)
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Column {
                Text(
                    text = "Choose Activity",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        activityOptions.forEach { activity ->
                            FilterChip(
                                selected = selectedActivity == activity,
                                onClick = { selectedActivity = activity },
                                label = { Text(activity) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = {
                    navController.navigate(
                        "activity_recording_screen/$selectedActivity"
                    )
                }
            ) {
                Text("Start Activity")
            }
        }
    }
}
