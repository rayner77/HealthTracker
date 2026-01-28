@file:OptIn(ExperimentalMaterial3Api::class)

package com.inf2007.healthtracker.Screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Scale
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import com.inf2007.healthtracker.utilities.BottomNavigationBar
import com.inf2007.healthtracker.ui.theme.Primary
import com.inf2007.healthtracker.ui.theme.Secondary
import com.inf2007.healthtracker.ui.theme.Tertiary
import com.inf2007.healthtracker.ui.theme.Unfocused
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    // Current values
    var userName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    var userPhone by remember { mutableStateOf("") }
    var userGender by remember { mutableStateOf("") }
    var userAge by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var activityLevel by remember { mutableStateOf("") }
    var dietaryPreference by remember { mutableStateOf("") }
    var calorieIntake by remember { mutableStateOf("") }
    var stepsGoal by remember { mutableStateOf("") }
    var hydrationGoal by remember { mutableStateOf("") }

    // Original values (to compare changes)
    var originalUserName by remember { mutableStateOf("") }
    var originalUserEmail by remember { mutableStateOf("") }
    var originalUserAge by remember { mutableStateOf("") }
    var originalUserPhone by remember { mutableStateOf("") }
    var originalWeight by remember { mutableStateOf("") }
    var originalHeight by remember { mutableStateOf("") }
    var originalActivityLevel by remember { mutableStateOf("") }
    var originalDietaryPreference by remember { mutableStateOf("") }
    var originalCalorieIntake by remember { mutableStateOf("") }
    var originalStepsGoal by remember { mutableStateOf("") }
    var originalHydrationGoal by remember { mutableStateOf("") }
    var originalUserGender by remember { mutableStateOf("") }

    var isEditing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val user = FirebaseAuth.getInstance().currentUser
    var steps by remember { mutableStateOf(0) }

    val roundedShape = MaterialTheme.shapes.small

    // Derived state: check if any field has changed
    val isDataChanged by derivedStateOf {
        (userName != originalUserName) ||
        (userEmail != originalUserEmail) ||
        (userAge != originalUserAge) || (userPhone != originalUserPhone) ||
        (weight != originalWeight) ||
        (height != originalHeight) ||
        (activityLevel != originalActivityLevel) ||
        (dietaryPreference != originalDietaryPreference) ||
        (calorieIntake != originalCalorieIntake) ||
        (stepsGoal != originalStepsGoal) ||
        (hydrationGoal != originalHydrationGoal) ||
        (userGender != originalUserGender)
    }
    // Fetch user data from Firebase
    LaunchedEffect(Unit) {
        user?.let {
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val todayString = dateFormat.format(Date())
            FirebaseFirestore.getInstance().collection("users").document(it.uid)
                .get()
                .addOnSuccessListener { document ->
                    userName = document.getString("name") ?: "Unknown User"
                    userEmail = document.getString("email") ?: "Unknown Email"
                    userPhone = document.getString("phone") ?: "Not Set"
                    userGender = document.getString("gender") ?: "Not Set"
                    userAge = document.getLong("age")?.toString() ?: "0"  // Ensure age is treated as a number
                    weight = document.getLong("weight")?.toString() ?: "0"  // Ensure weight is treated as a number
                    height = document.getLong("height")?.toString() ?: "0"  // Ensure height is treated as a number
                    activityLevel = document.getString("activity_level") ?: "Not Set"
                    dietaryPreference = document.getString("dietary_preference") ?: "None"
                    calorieIntake = document.getLong("calorie_intake")?.toString() ?: "0"  // Ensure calorieIntake is treated as a number
                    stepsGoal = document.getLong("steps_goal")?.toString() ?: "0"  // Ensure stepsGoal is treated as a number
                    hydrationGoal = document.getLong("hydration_goal")?.toString() ?: "0"  // Ensure hydrationGoal is treated as a number

                    // Initialize original values when data is fetched.
                    originalUserName = userName
                    originalUserEmail = userEmail
                    originalUserAge = userAge
                    originalUserPhone = userPhone
                    originalWeight = weight
                    originalHeight = height
                    originalActivityLevel = activityLevel
                    originalDietaryPreference = dietaryPreference
                    originalCalorieIntake = calorieIntake
                    originalStepsGoal = stepsGoal
                    originalHydrationGoal = hydrationGoal
                    originalUserGender = userGender

                    isLoading = false
                }
                .addOnFailureListener { exception ->
                    errorMessage = exception.message ?: "Failed to retrieve profile data"
                    isLoading = false
                }

            FirebaseFirestore.getInstance().collection("steps")
                .whereEqualTo("userId", user.uid) // ensure your steps documents include a "userId" field
                .whereEqualTo("dateString", todayString)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && !snapshot.isEmpty) {
                        steps = snapshot.documents.sumOf { doc ->
                            doc.getLong("steps")?.toInt() ?: 0
                        }
                    } else {
                        steps = 0
                    }
                }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Profile") }, modifier = Modifier.padding(horizontal = 24.dp)) },
        bottomBar = { BottomNavigationBar(navController) }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMessage.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            }
        } else {
            // Main content: Use LazyColumn for efficient vertical scrolling
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                // Check if the user is in view mode or edit mode
                    if (!isEditing) {
                        // ===============================
                        // VIEW MODE: Display profile details
                        // ===============================
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Left side: Display user's name and email
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = userName,
                                    style = MaterialTheme.typography.headlineLarge
                                )
                                Text(
                                    text = userEmail,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal)
                                )
                                Text(
                                    text = if (userPhone.isBlank()) "Phone: Not set" else "Phone: $userPhone",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal)
                                )
                            }

                            // Right side: Edit icon to switch to edit mode
                            IconButton(
                                onClick = { isEditing = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Profile",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Card showing detailed user information (Gender, Weight, Height)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = roundedShape,
                            colors = CardDefaults.cardColors(containerColor = Secondary)
                        ) {
                            // Row with evenly spaced details
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Display Gender
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(
                                            style = MaterialTheme.typography.titleSmall.toSpanStyle()
                                                .copy(color = MaterialTheme.colorScheme.secondaryContainer)
                                        ) {
                                            append("Gender\n")
                                        }
                                        withStyle(
                                            style = MaterialTheme.typography.bodyLarge.toSpanStyle()
                                                .copy(color = Color.White)
                                        ) {
                                            append(userGender)
                                        }
                                    },
                                    textAlign = TextAlign.Center
                                )

                                // Divider between details
                                VerticalDivider(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .width(1.dp),
                                    color = Color.White
                                )

                                // Display Weight with fallback if not set
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(
                                            style = MaterialTheme.typography.titleSmall.toSpanStyle()
                                                .copy(color = MaterialTheme.colorScheme.secondaryContainer)
                                        ) {
                                            append("Weight\n")
                                        }
                                        withStyle(
                                            style = MaterialTheme.typography.bodyLarge.toSpanStyle()
                                                .copy(color = Color.White)
                                        ) {
                                            append(if (weight == "0") "Not Set" else "$weight kg")
                                        }
                                    },
                                    textAlign = TextAlign.Center
                                )

                                // Divider between details
                                VerticalDivider(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .width(1.dp),
                                    color = Color.White
                                )

                                // Display Height with fallback if not set
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(
                                            style = MaterialTheme.typography.titleSmall.toSpanStyle()
                                                .copy(color = MaterialTheme.colorScheme.secondaryContainer)
                                        ) {
                                            append("Height\n")
                                        }
                                        withStyle(
                                            style = MaterialTheme.typography.bodyLarge.toSpanStyle()
                                                .copy(color = Color.White)
                                        ) {
                                            append(if (height == "0") "Not Set" else "$height cm")
                                        }
                                    },
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Additional profile details like activity level, dietary preference, etc.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // ---------------------------
                            // Activity Level Row
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FitnessCenter,
                                    contentDescription = "Activity Level Icon",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Activity Level",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.weight(1f))  // Pushes the value to the right

                                Text(
                                    text = activityLevel,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // ---------------------------
                            // Dietary Preference Row
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Restaurant,
                                    contentDescription = "Dietary Preference",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Dietary Preference",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.weight(1f))  // Pushes the value to the right

                                Text(
                                    text = dietaryPreference,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // ---------------------------
                            // Calorie Intake Row
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = "Calorie Intake Icon",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Calorie Intake",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.weight(1f))  // Pushes the value to the right

                                // Display Calorie Intake with fallback to "Not Set"
                                Text(
                                    text = if (calorieIntake.toIntOrNull() == 0 || calorieIntake.isEmpty()) "Not Set" else "$calorieIntake kcal",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // ---------------------------
                            // Calories Burned Calculation and Display
                            // ---------------------------

                            // Calculate calories burned using a simple estimation formula
                            val weightNumber = weight.toDoubleOrNull() ?: 0.0
                            val caloriesBurned = (steps * weightNumber * 0.0005).toInt()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocalFireDepartment,
                                    contentDescription = "Calorie Burned Icon",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Calorie Burned",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.weight(1f))  // Pushes the value to the right

                                Text(
                                    text = "$caloriesBurned kcal",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // ---------------------------
                            // Steps Goal Row
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                    contentDescription = "Steps Goal Icon",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Steps Goal",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.weight(1f))  // Pushes the value to the right

                                // Display Steps Goal with fallback to "Not Set"
                                Text(
                                    text = if (stepsGoal.toIntOrNull() == 0 || stepsGoal.isEmpty()) "Not Set" else "$stepsGoal steps",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // ---------------------------
                            // Hydration Goal Row
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalDrink,
                                    contentDescription = "Hydration Icon",
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Hydration Goal",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )

                                Spacer(modifier = Modifier.weight(1f))  // Pushes the value to the right

                                // Display Hydration Goal with fallback to "Not Set"
                                Text(
                                    text = if (hydrationGoal.toIntOrNull() == 0 || hydrationGoal.isEmpty()) "Not Set" else "$hydrationGoal ml",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }

                            // ---------------------------
                            // Logout Row
                            // ---------------------------
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        FirebaseAuth.getInstance().signOut()
                                        navController.navigate("login_screen") {
                                            popUpTo("main_screen") { inclusive = true }
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = "Logout",
                                    modifier = Modifier.size(24.dp),
                                    tint = Color.Red
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Logout",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.Red
                                )
                            }
                        }
                    } else {
                        // ===============================
                        // EDIT MODE: Display editable fields for user profile
                        // ===============================
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // ---------------------------
                            // Name Text Field
                            // ---------------------------
                            OutlinedTextField(
                                value = userName,
                                onValueChange = { userName = it },
                                label = { Text("Name") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Name Icon"
                                    )
                                },
                                shape = roundedShape,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Unfocused
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // ---------------------------
                            // Email Text Field
                            // ---------------------------
                            OutlinedTextField(
                                value = userEmail,
                                onValueChange = { userEmail = it },
                                label = { Text("Email") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = "Email Icon"
                                    )
                                },
                                shape = roundedShape,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Unfocused
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // ---------------------------
                            // Age Text Field: Only digits allowed
                            // ---------------------------
                            OutlinedTextField(
                                value = userAge,
                                onValueChange = { userAge = it.filter { char -> char.isDigit() } },
                                label = { Text("Age") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Event,
                                        contentDescription = "Age Icon"
                                    )
                                },
                                shape = roundedShape,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Unfocused
                                ),
                                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                keyboardActions = KeyboardActions(
                                    onDone = { /* Handle done action */ }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // ---------------------------
                            // Row for Phone fields
                            // ---------------------------
                            val canEditPhone = originalUserPhone.isBlank() || originalUserPhone == "Not Set"

                            if (canEditPhone) {
                                OutlinedTextField(
                                    value = userPhone,
                                    onValueChange = { userPhone = it },
                                    label = { Text("Phone Number") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = "Phone: $userPhone",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }

                            // ---------------------------
                            // Row for Dietary Preference and Calorie Intake fields
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f) // Makes this column take up half of the row's width
                                ) {
                                    // Dietary Preference Text Field
                                    OutlinedTextField(
                                        value = dietaryPreference,
                                        onValueChange = { dietaryPreference = it },
                                        label = { Text("Dietary Preference") }, // Label without the guide text
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Restaurant,
                                                contentDescription = "Dietary Preference Icon"
                                            )
                                        },
                                        shape = roundedShape,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Primary,
                                            unfocusedBorderColor = Unfocused
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    // Guide Text
                                    Text(
                                        text = "Vegan, Keto, etc.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Black,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Calorie Intake Text Field
                                    OutlinedTextField(
                                        value = calorieIntake,
                                        onValueChange = { calorieIntake = it.filter { char -> char.isDigit() } },
                                        label = { Text("Calorie Intake (kcal)") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                                contentDescription = "Calorie Intake Icon"
                                            )
                                        },
                                        shape = roundedShape,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Primary,
                                            unfocusedBorderColor = Unfocused
                                        ),
                                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                        keyboardActions = KeyboardActions(
                                            onDone = { /* Handle done action */ }
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // ---------------------------
                            // Row for Steps and Hydration fields
                            // ---------------------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f) // Makes this column take up half of the row's width
                                ) {
                                    OutlinedTextField(
                                        value = stepsGoal,
                                        onValueChange = { stepsGoal = it.filter { char -> char.isDigit() } },
                                        label = { Text("Steps") }, // Label without the guide text
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                                contentDescription = "Steps Goal Icon"
                                            )
                                        },
                                        shape = roundedShape,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Primary,
                                            unfocusedBorderColor = Unfocused
                                        ),
                                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                        keyboardActions = KeyboardActions(
                                            onDone = { /* Handle done action */ }
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = hydrationGoal,
                                        onValueChange = { hydrationGoal = it.filter { char -> char.isDigit() } },
                                        label = { Text("Hydration") }, // Label without the guide text
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.LocalDrink,
                                                contentDescription = "Hydration Icon"
                                            )
                                        },
                                        shape = roundedShape,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Primary,
                                            unfocusedBorderColor = Unfocused
                                        ),
                                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                        keyboardActions = KeyboardActions(
                                            onDone = { /* Handle done action */ }
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // ---------------------------
                            // Row for Weight and Height fields
                            // ---------------------------
                            Row (
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ){
                                // Weight Text Field
                                OutlinedTextField(
                                    value = weight,
                                    onValueChange = { weight = it.filter { char -> char.isDigit() } },
                                    label = { Text("Weight (kg)") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Scale,
                                            contentDescription = "Age Icon"
                                        )
                                    },
                                    shape = roundedShape,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Primary,
                                        unfocusedBorderColor = Unfocused
                                    ),
                                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                    keyboardActions = KeyboardActions(
                                        onDone = { /* Handle done action */ }
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                // Height Text Field
                                OutlinedTextField(
                                    value = height,
                                    onValueChange = { height = it.filter { char -> char.isDigit() } },
                                    label = { Text("Height (cm)") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Height,
                                            contentDescription = "Age Icon"
                                        )
                                    },
                                    shape = roundedShape,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Primary,
                                        unfocusedBorderColor = Unfocused
                                    ),
                                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                    keyboardActions = KeyboardActions(
                                        onDone = { /* Handle done action */ }
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // ---------------------------
                            // Gender selection
                            // ---------------------------
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Gender", style = MaterialTheme.typography.bodyLarge)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    RadioButton(
                                        selected = userGender == "Male",
                                        onClick = { userGender = "Male" }
                                    )
                                    Text("Male")

                                    Spacer(modifier = Modifier.width(8.dp))

                                    RadioButton(
                                        selected = userGender == "Female",
                                        onClick = { userGender = "Female" }
                                    )
                                    Text("Female")
                                }
                            }

                            // ---------------------------
                            // Activity Level selection
                            // ---------------------------
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Activity Level", style = MaterialTheme.typography.bodyLarge)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val activityOptions = listOf("Active", "Moderate", "Sedentary")
                                    activityOptions.forEach { option ->
                                        FilterChip(
                                            selected = (activityLevel == option),
                                            onClick = { activityLevel = option },
                                            label = { Text(option) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                                containerColor = MaterialTheme.colorScheme.background,
                                                labelColor = Unfocused
                                            )
                                        )
                                    }
                                }
                            }

                            // ---------------------------
                            // Calculate recommended calorie intake using BMR
                            // ---------------------------
                            Button(
                                onClick = {
                                    val weightValue = weight.toDoubleOrNull() ?: 0.0
                                    val heightValue = height.toDoubleOrNull() ?: 0.0
                                    val ageValue = userAge.toIntOrNull() ?: 0
                                    val bmr =
                                        calculateBMR(weightValue, heightValue, ageValue, userGender)
                                    calorieIntake = bmr.toInt().toString()
                                    Toast.makeText(
                                        context,
                                        "Recommended Calorie Intake: $calorieIntake kcal",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                shape = roundedShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Tertiary),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text("Calculate Recommended Intake")
                            }

                            // ---------------------------
                            // Row with Cancel and Save buttons
                            // ---------------------------
                            Row (
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ){
                                // Cancel Changes Button: Revert any changes made
                                Button(
                                    onClick = {
                                        // Revert current fields back to original values
                                        userName = originalUserName
                                        userEmail = originalUserEmail
                                        userAge = originalUserAge
                                        userPhone = originalUserPhone
                                        weight = originalWeight
                                        height = originalHeight
                                        activityLevel = originalActivityLevel
                                        dietaryPreference = originalDietaryPreference
                                        calorieIntake = originalCalorieIntake
                                        stepsGoal = originalStepsGoal
                                        hydrationGoal = originalHydrationGoal
                                        userGender = originalUserGender

                                        isEditing = false
                                    },
                                    shape = roundedShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.background, contentColor = Primary, ),
                                    border = BorderStroke(1.dp, Primary),
                                    modifier = Modifier.height(56.dp).weight(1f)
                                ) {
                                    Text("Cancel")
                                }

                                // Save Changes Button: Commit changes and update data in Firestore
                                Button(
                                    onClick = {
                                        // Perform save operation, e.g., update Firestore.
                                        isLoading = true
                                        val updates: MutableMap<String, Any> = mutableMapOf(
                                            "name" to userName,
                                            "age" to userAge,
                                            "gender" to userGender,
                                            "phone" to userPhone,
                                            "email" to userEmail,
                                            "activity_level" to activityLevel,
                                            "dietary_preference" to dietaryPreference,
                                            "steps_goal" to stepsGoal,
                                            "hydration_goal" to hydrationGoal
                                        )
                                        // Convert number fields if possible
                                        userAge.toIntOrNull()?.let { updates["age"] = it }
                                        weight.toIntOrNull()?.let { updates["weight"] = it }
                                        height.toIntOrNull()?.let { updates["height"] = it }
                                        calorieIntake.toIntOrNull()?.let { updates["calorie_intake"] = it }
                                        stepsGoal.toIntOrNull()?.let { updates["steps_goal"] = it }
                                        hydrationGoal.toIntOrNull()?.let { updates["hydration_goal"] = it }

                                        FirebaseFirestore.getInstance().collection("users")
                                            .document(user!!.uid)
                                            .update(updates)
                                            .addOnSuccessListener {
                                                // Update original values to current values after a successful save.
                                                originalUserName = userName
                                                originalUserEmail = userEmail
                                                originalUserAge = userAge
                                                originalUserPhone = userPhone
                                                originalWeight = weight
                                                originalHeight = height
                                                originalActivityLevel = activityLevel
                                                originalDietaryPreference = dietaryPreference
                                                originalCalorieIntake = calorieIntake
                                                originalStepsGoal = stepsGoal
                                                originalHydrationGoal = hydrationGoal
                                                originalUserGender = userGender

                                                isLoading = false
                                                Toast.makeText(
                                                    context,
                                                    "Profile updated!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                isEditing = false
                                            }
                                            .addOnFailureListener { exception ->
                                                isLoading = false
                                                errorMessage = exception.message ?: "Update failed"
                                            }
                                    },
                                    shape = roundedShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                    modifier = Modifier.height(56.dp).weight(1f),
                                    enabled = isDataChanged // Save enabled only when data has changed.
                                ) {
                                    Text("Save")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

// Function to calculate BMR
fun calculateBMR(weight: Double, height: Double, age: Int, gender: String): Double {
    return if (gender == "Male") {
        13.397 * weight + 4.799 * height - 5.677 * age + 88.362
    } else {
        9.247 * weight + 3.098 * height - 4.330 * age + 447.593
    }
}