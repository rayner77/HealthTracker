package com.inf2007.healthtracker.Screens

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.inf2007.healthtracker.ui.theme.Primary
import com.inf2007.healthtracker.ui.theme.Tertiary
import com.inf2007.healthtracker.ui.theme.Unfocused

@Composable
fun SignUpScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var nameError by rememberSaveable { mutableStateOf("") }
    var emailError by rememberSaveable { mutableStateOf("") }
    var passwordError by rememberSaveable { mutableStateOf("") }
    var generalError by rememberSaveable { mutableStateOf("") }
    var phoneError by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current

    val nameFocusRequester = remember { FocusRequester() }
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val phoneFocusRequester = remember { FocusRequester() }

    // Email validation pattern
    val emailPattern = "[a-zA-Z0-9._-]+@[a-z]+\\.+[a-z]+"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 4.dp
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // App Logo
                Icon(
                    imageVector = Icons.Outlined.FitnessCenter,
                    contentDescription = "Health Tracker Logo",
                    tint = Primary,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(8.dp)
                )

                // App Title
                Text(
                    text = "HEALTH TRACKER",
                    style = MaterialTheme.typography.titleMedium,
                    color = Primary,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Signup Header
                Text(
                    text = "Create Account",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Register to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Name TextField
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = ""  // Clear error when typing
                        generalError = ""
                    },
                    label = { Text("Name") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Person Icon",
                            tint = if (name.isNotEmpty()) Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Unfocused,
                        focusedLabelColor = Primary,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    ),
                    singleLine = true,
                    isError = nameError.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                        .onKeyEvent {
                            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_TAB) {
                                emailFocusRequester.requestFocus()
                                true
                            } else false
                        }
                )

                // Name Error Message
                AnimatedVisibility(
                    visible = nameError.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = nameError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 2.dp)
                    )
                }

                // Email TextField
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailError = ""  // Clear error when typing
                        generalError = ""
                    },
                    label = { Text("Email") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Email Icon",
                            tint = if (email.isNotEmpty()) Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Unfocused,
                        focusedLabelColor = Primary,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    ),
                    singleLine = true,
                    isError = emailError.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(emailFocusRequester)
                        .onKeyEvent {
                            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_TAB) {
                                passwordFocusRequester.requestFocus()
                                true
                            } else false
                        }
                )

                // Email Error Message
                AnimatedVisibility(
                    visible = emailError.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = emailError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 2.dp)
                    )
                }

                // Phone TextField
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        phoneError = ""
                        generalError = ""
                    },
                    label = { Text("Phone Number") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Phone Icon",
                            tint = if (phone.isNotEmpty()) Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Unfocused,
                        focusedLabelColor = Primary,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    ),
                    singleLine = true,
                    isError = phoneError.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(phoneFocusRequester)
                )
                if (phoneError.isNotEmpty()) {
                    Text(
                        text = phoneError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 2.dp)
                    )
                }

                // Password TextField
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordError = ""  // Clear error when typing
                        generalError = ""
                    },
                    label = { Text("Password") },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Password Icon",
                            tint = if (password.isNotEmpty()) Primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Unfocused,
                        focusedLabelColor = Primary,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    ),
                    singleLine = true,
                    isError = passwordError.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester)
                )

                // Password Error Message
                AnimatedVisibility(
                    visible = passwordError.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = passwordError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 2.dp)
                    )
                }

                // General Error
                AnimatedVisibility(
                    visible = generalError.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = generalError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sign Up Button
                Button(
                    onClick = {
                        // Validate fields before submitting
                        var isValid = true

                        // Name validation
                        if (name.isEmpty()) {
                            nameError = "Name cannot be empty"
                            isValid = false
                        }

                        // Email validation
                        if (email.isEmpty()) {
                            emailError = "Email cannot be empty"
                            isValid = false
                        } else if (!email.matches(emailPattern.toRegex())) {
                            emailError = "Please enter a valid email address"
                            isValid = false
                        }

                        // Phone validation
                        if (phone.isEmpty()) {
                            phoneError = "Phone number cannot be empty"
                            isValid = false
                        } else if (!phone.matches(Regex("^[0-9+()\\s-]{8,15}$"))) {
                            phoneError = "Please enter a valid phone number"
                            isValid = false
                        }

                        // Password validation
                        if (password.isEmpty()) {
                            passwordError = "Password cannot be empty"
                            isValid = false
                        } else if (password.length < 6) {
                            passwordError = "Password must be at least 6 characters"
                            isValid = false
                        }

                        if (isValid) {
                            isLoading = true
                            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                                .addOnCompleteListener { task ->
                                    isLoading = false
                                    if (task.isSuccessful) {
                                        val user = task.result?.user
                                        val userData = hashMapOf(
                                            "name" to name,
                                            "uid" to user?.uid,
                                            "email" to email,
                                            "phone" to phone,
                                            "friendIds" to emptyList<String>()
                                            // Don't store password in Firestore for security
                                        )
                                        FirebaseFirestore.getInstance().collection("users")
                                            .document(user?.uid ?: "")
                                            .set(userData, SetOptions.merge())
                                            .addOnSuccessListener {
                                                Toast.makeText(context, "Account created successfully!", Toast.LENGTH_SHORT).show()
                                                navController.navigate("login_screen") {
                                                    popUpTo("signup_screen") { inclusive = true }
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                generalError = "Error saving user data: ${e.message}"
                                            }
                                    } else {
                                        // Handle different types of errors
                                        when (val exception = task.exception) {
                                            is FirebaseAuthWeakPasswordException -> {
                                                // Password is too weak
                                                passwordError = "Password is too weak. Please use at least 6 characters"
                                            }
                                            is FirebaseAuthInvalidCredentialsException -> {
                                                // Malformed email
                                                val errorMsg = exception.message ?: ""
                                                if (errorMsg.contains("email", ignoreCase = true) ||
                                                    errorMsg.contains("credential", ignoreCase = true)) {
                                                    emailError = "Invalid email format. Please check and try again"
                                                } else {
                                                    generalError = "Invalid credentials: ${exception.message}"
                                                }
                                            }
                                            is FirebaseAuthUserCollisionException -> {
                                                // Email already in use
                                                emailError = "This email is already registered"
                                            }
                                            else -> {
                                                val errorMsg = task.exception?.message ?: "Sign up failed"
                                                generalError = errorMsg
                                            }
                                        }
                                    }
                                }
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = Primary.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "SIGN UP",
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Login Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Have an account? ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Login",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { navController.navigate("login_screen") {
                                popUpTo("signup_screen") { inclusive = true }
                            }}
                            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}