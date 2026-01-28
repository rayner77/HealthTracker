package com.inf2007.healthtracker.Screens

import android.view.KeyEvent
import android.util.Log
import com.google.firebase.FirebaseApp
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FitnessCenter
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
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.inf2007.healthtracker.ui.theme.Primary
import com.inf2007.healthtracker.ui.theme.Tertiary
import com.inf2007.healthtracker.ui.theme.Unfocused
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuthException

@Composable
fun LoginScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var emailError by rememberSaveable { mutableStateOf("") }
    var passwordError by rememberSaveable { mutableStateOf("") }
    var generalError by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
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

                // Login Header
                Text(
                    text = "Welcome Back",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Sign in to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Email TextField
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailError = "" // Clear error when typing
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

                // Email Error
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
                            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    )
                }

                // Password TextField
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordError = "" // Clear error when typing
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

                // Password Error
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
                            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                    )
                }

                // General Error Message
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

                Spacer(modifier = Modifier.height(8.dp))

                // Login Button
                Button(
                    onClick = {
                        // Validate inputs before attempting login
                        var isValid = true

                        // Email validation
                        if (email.isEmpty()) {
                            emailError = "Email cannot be empty"
                            isValid = false
                        } else if (!email.matches(emailPattern.toRegex())) {
                            emailError = "Please enter a valid email address"
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
                            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                                .addOnCompleteListener { task ->
                                    isLoading = false
                                    if (task.isSuccessful) {
                                        Toast.makeText(context, "Login successful!", Toast.LENGTH_SHORT).show()
                                        navController.navigate("dashboard_screen") {
                                            popUpTo("login_screen") { inclusive = true }
                                        }
                                    } else {
                                        // Handle different types of errors
                                        when (val exception = task.exception) {
                                            is FirebaseAuthInvalidUserException -> {
                                                // User doesn't exist
                                                emailError = "No account found with this email"
                                            }
                                            is FirebaseAuthInvalidCredentialsException -> {
                                                val errorMessage = exception.message ?: ""
                                                when {
                                                    errorMessage.contains("password", ignoreCase = true) -> {
                                                        passwordError = "Incorrect password"
                                                    }
                                                    else -> {
                                                        generalError = "Invalid email or password"
                                                    }
                                                }
                                            }
                                            else -> {
                                                generalError = task.exception?.message ?: "Login failed"
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
                            text = "LOGIN",
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Login with Singpass
                Button(
                    onClick = {
                        if (isLoading) return@Button
                        isLoading = true
                        generalError = ""

                        val auth = FirebaseAuth.getInstance()
                        val db = FirebaseFirestore.getInstance()

                        // ✅ Normalize email (prevents hidden whitespace issues)
                        val singpassName = "Bob Tan"
                        val singpassEmail = "testing123@gmail.com".trim().lowercase()

                        // ✅ Must be constant to keep the same UID every time
                        val singpassPassword = "SingpassTest123!"  // pick one and never change it

                        // Debug: confirms which Firebase project the app is actually using
                        val projectId = try { FirebaseApp.getInstance().options.projectId } catch (e: Exception) { "unknown" }
                        Log.d("SingpassLogin", "Firebase projectId=$projectId, email=$singpassEmail")

                        fun upsertUserDoc(uid: String) {
                            val userData = hashMapOf(
                                "uid" to uid,
                                "name" to singpassName,
                                "email" to singpassEmail,
                                "friendIds" to emptyList<String>()
                            )

                            db.collection("users")
                                .document(uid)
                                .set(userData, SetOptions.merge())
                                .addOnSuccessListener {
                                    isLoading = false
                                    Toast.makeText(context, "Logged in with Singpass!", Toast.LENGTH_SHORT).show()
                                    navController.navigate("dashboard_screen") {
                                        popUpTo("login_screen") { inclusive = true }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    isLoading = false
                                    generalError = "Failed to save Singpass user: ${e.message}"
                                    auth.signOut()
                                }
                        }

                        fun createThenProceed() {
                            auth.createUserWithEmailAndPassword(singpassEmail, singpassPassword)
                                .addOnSuccessListener { created ->
                                    val uid = created.user?.uid
                                    if (uid.isNullOrBlank()) {
                                        isLoading = false
                                        generalError = "Singpass signup failed: missing uid"
                                        return@addOnSuccessListener
                                    }
                                    upsertUserDoc(uid)
                                }
                                .addOnFailureListener { ce ->
                                    val code = (ce as? FirebaseAuthException)?.errorCode ?: ""
                                    Log.e("SingpassLogin", "createUser failed code=$code", ce)

                                    if (code == "ERROR_EMAIL_ALREADY_IN_USE") {
                                        // This is the only case that truly indicates “account exists but password differs”
                                        isLoading = false
                                        generalError =
                                            "Singpass account already exists with another password. " +
                                                    "Reset password for $singpassEmail in Firebase Auth, or delete it and try again."
                                    } else {
                                        isLoading = false
                                        generalError = ce.message ?: "Singpass signup failed"
                                    }
                                }
                        }

                        // 1) Try sign-in
                        auth.signInWithEmailAndPassword(singpassEmail, singpassPassword)
                            .addOnSuccessListener { result ->
                                val uid = result.user?.uid
                                if (uid.isNullOrBlank()) {
                                    isLoading = false
                                    generalError = "Singpass login failed: missing uid"
                                    return@addOnSuccessListener
                                }
                                upsertUserDoc(uid)
                            }
                            .addOnFailureListener { e ->
                                val code = (e as? FirebaseAuthException)?.errorCode ?: ""
                                Log.e("SingpassLogin", "signIn failed code=$code", e)

                                // 2) If sign-in fails for ANY reason, try creating the user.
                                // If it truly exists, createUser will return EMAIL_ALREADY_IN_USE.
                                createThenProceed()
                            }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        contentColor = Color.White,
                        disabledContainerColor = Color.Red.copy(alpha = 0.5f),
                        disabledContentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Log In with Singpass",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sign Up Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Don't have an account? ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Sign up",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { navController.navigate("signup_screen") }
                            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}