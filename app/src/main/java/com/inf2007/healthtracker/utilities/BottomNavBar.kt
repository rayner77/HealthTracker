package com.inf2007.healthtracker.utilities

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.inf2007.healthtracker.ui.theme.Primary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FiberManualRecord

@Composable
fun BottomNavigationBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current

    // Hide bottom bar during live recording
    if (currentRoute?.startsWith("activity_recording_screen") == true) {
        return
    }

    BottomNavigation(
        modifier = Modifier
            .navigationBarsPadding(),
        backgroundColor = Primary
    ) {
        BottomNavigationItem(
            icon = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Dashboard",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            label = {Text("Home", style = MaterialTheme.typography.bodyMedium, color = Color.White) },
            selected = currentRoute == "dashboard_screen",

            onClick = {
                if (currentRoute != "dashboard_screen") {
                    navController.navigate("dashboard_screen") {
                        popUpTo("dashboard_screen") { inclusive = true }
                    }
                }
            }
        )
        BottomNavigationItem(
            selected = currentRoute == "meal_recommendation_screen",
            onClick = {
                if (currentRoute != "meal_recommendation_screen") {
                    val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    navController.navigate("meal_recommendation_screen/$userId") {
                        popUpTo("dashboard_screen") { inclusive = false }
                    }
                }
            },
            icon = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Restaurant,
                        contentDescription = "Meal Recommendation",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            label = {Text("Meals", style = MaterialTheme.typography.bodyMedium, color = Color.White) },
            alwaysShowLabel = true
        )

        // New record screen
        BottomNavigationItem(
            icon = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = "Record",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            label = {Text("Record", style = MaterialTheme.typography.bodySmall, color = Color.White) },
            selected = currentRoute == "record_screen",

            onClick = {
                if (currentRoute != "record_screen") {
                    navController.navigate("record_screen") {
                        popUpTo("dashboard_screen") { inclusive = true }
                    }
                }
            }
        )

        // 3. NEW SOCIAL ITEM ADDED HERE
        BottomNavigationItem(
            selected = currentRoute == "social_screen",
            onClick = {
                if (currentRoute != "social_screen") {
                    navController.navigate("social_screen") {
                        popUpTo("dashboard_screen") { inclusive = false }
                    }
                }
            },
            icon = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Chat, "Social", tint = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            label = { Text("Social", style = MaterialTheme.typography.bodyMedium, color = Color.White) },
            alwaysShowLabel = true
        )

        BottomNavigationItem(
            selected = currentRoute == "profile_screen",
            onClick = {
                if (currentRoute != "profile_screen") {
                    navController.navigate("profile_screen") {
                        popUpTo("dashboard_screen") { inclusive = false }
                    }
                }
            },
            icon = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "Profile",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            label = {Text("Profile", style = MaterialTheme.typography.bodyMedium, color = Color.White) },
            alwaysShowLabel = true
        )
    }
}