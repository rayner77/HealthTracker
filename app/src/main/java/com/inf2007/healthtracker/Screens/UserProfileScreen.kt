package com.inf2007.healthtracker.Screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    navController: NavController,
    userId: String
) {
    var user by remember { mutableStateOf<SocialUser?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(userId) {
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .get()
            .addOnSuccessListener { doc ->
                user = doc.toObject(SocialUser::class.java)
                isLoading = false
            }
            .addOnFailureListener {
                error = it.message ?: "Failed to load profile"
                isLoading = false
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Profile") }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            error.isNotBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }

            user != null -> {
                val profile = user!!

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text("@${profile.username}")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Email: ${profile.email}")
                            Text("Phone: ${profile.phone}")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Role: ${profile.role}")
                            Text("Expertise: ${profile.expertise}")
                        }
                    }

                    Button(
                        onClick = {
                            navController.navigate(
                                "chat_screen/${profile.uid}/${Uri.encode(profile.name)}"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Message")
                    }
                }
            }
        }
    }
}