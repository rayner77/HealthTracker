package com.inf2007.healthtracker.Screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.inf2007.healthtracker.utilities.BottomNavigationBar
import com.inf2007.healthtracker.utilities.StepCounter
import com.inf2007.healthtracker.utilities.syncStepsToFirestore
import kotlinx.coroutines.launch
import kotlin.math.min
import java.text.SimpleDateFormat
import java.util.*
import com.inf2007.healthtracker.ui.theme.Primary
import com.inf2007.healthtracker.ui.theme.Secondary
import com.inf2007.healthtracker.ui.theme.SecondaryContainer
import com.inf2007.healthtracker.ui.theme.Tertiary
import com.inf2007.healthtracker.ui.theme.Unfocused
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.rememberDismissState
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.inf2007.healthtracker.BuildConfig
import com.inf2007.healthtracker.utilities.GeminiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    var steps by remember { mutableStateOf(0) }
    var calorieIntakeToday by remember { mutableStateOf(0) }
    var desiredCalorieIntake by remember { mutableStateOf(0) }
    var hydration by remember { mutableStateOf(0) }
    var weight by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var weeklySteps by remember { mutableStateOf(listOf(1000, 1000, 1000, 1000, 1000, 1000, 1000)) }
    var desiredSteps by remember { mutableStateOf(0) }
    var desiredHydration by remember { mutableStateOf(0) }
    var foodEntries by remember { mutableStateOf<List<FoodEntry>>(emptyList()) }
    var weeklyDates by remember { mutableStateOf(emptyList<String>()) }
    var weeklyCalories by remember { mutableStateOf(listOf(0, 0, 0, 0, 0, 0, 0)) }
    var showDialog by remember { mutableStateOf(false) } // To show confirmation dialog
    var entryToDelete by remember { mutableStateOf<FoodEntry?>(null) } // To track which entry is being deleted

    // ---------------------------
    // New states for date selection:
    var selectedDate by remember { mutableStateOf(Date()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatForFood = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val selectedDateString = dateFormatForFood.format(selectedDate)
    // ---------------------------

    val dailyStepGoal = if (desiredSteps != 0) desiredSteps else 10000
    val dailyCalorieGoal = if (desiredCalorieIntake != 0) desiredCalorieIntake else 2000
    val dailyHydrationGoal = if (desiredHydration != 0) desiredHydration else 3200

    val coroutineScope = rememberCoroutineScope()
    val currentUser = FirebaseAuth.getInstance().currentUser
    var user by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    var stepCount by remember { mutableStateOf(0) }
    val firestore = FirebaseFirestore.getInstance()
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val formattedDate = dateFormat.format(Date())
    val stepsRef = firestore.collection("steps").document("${user?.uid}_${formattedDate}")

    val geminiService = remember { GeminiService(BuildConfig.geminiApiKey) }

    // Function to delete the food entry
    fun deleteFoodEntry(entry: FoodEntry) {
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("foodEntries")
            .document(entry.id)
            .delete()
            .addOnSuccessListener {
                // Optionally, remove it from the local foodEntries state
                foodEntries = foodEntries.filter { it.id != entry.id }
            }
            .addOnFailureListener { exception ->
                Log.e("DashboardScreen", "Error deleting food entry: ${exception.message}")
            }
    }

    // Show confirmation dialog for deletion
    if (showDialog && entryToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Delete Food Entry") },
            text = { Text("Are you sure you want to delete this food entry?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        entryToDelete?.let { deleteFoodEntry(it) }
                        showDialog = false // Close the dialog after deletion
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Fetch user data from firestore (for general settings)
    LaunchedEffect(Unit) {
        currentUser?.let { user ->
            val calendar = Calendar.getInstance() // Uses TimeZone.getDefault()
            calendar.time = Date()

            // Set to the start of today (UTC+8):
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDay = com.google.firebase.Timestamp(calendar.time)

            // Set to the start of tomorrow (UTC+8):
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            val endOfDay = com.google.firebase.Timestamp(calendar.time)

            android.util.Log.d("CalendarDebug", "Start of Day: ${calendar.time} | End of Day: $endOfDay")
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val todayString = dateFormat.format(Date())
            FirebaseFirestore.getInstance().collection("users")
                .document(user.uid)
                .get()
                .addOnSuccessListener { document ->
                    hydration = document.getLong("hydration")?.toInt() ?: 0
                    weight = document.getLong("weight")?.toInt() ?: 0
                    desiredCalorieIntake = document.getLong("calorie_intake")?.toInt() ?: 0
                    desiredSteps = document.getLong("steps_goal")?.toInt() ?: 0
                    desiredHydration = document.getLong("hydration_goal")?.toInt() ?: 0
                }
            //Steps
            //Code for steps data only for the current day
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

            // Prepare a list of dateString values for the past 7 days
            val dateFormatterNew = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val calendarNew = Calendar.getInstance().apply {
                time = Date()
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Build the past 7 dates list without reusing the same instance
            val past7Dates = (0 until 7).map {
                val clonedCal = calendarNew.clone() as Calendar
                clonedCal.add(Calendar.DAY_OF_MONTH, -it)
                dateFormatterNew.format(clonedCal.time)
            }
            // Reverse to have chronological order (oldest first)
            val queryDates = past7Dates.reversed()
            weeklyDates = queryDates  // update the state

            FirebaseFirestore.getInstance().collection("steps")
                .whereEqualTo("userId", user.uid)
                .whereIn("dateString", queryDates)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("WeeklySteps", "Error fetching weekly steps: ${error.message}")
                        return@addSnapshotListener
                    }
                    // Initialize a map with all queryDates set to 0 steps
                    val stepsMap = queryDates.associateWith { 0 }.toMutableMap()
                    snapshot?.documents?.forEach { doc ->
                        val docDate = doc.getString("dateString")
                        val stepsValue = doc.getLong("steps")?.toInt() ?: 0
                        if (docDate != null) {
                            stepsMap[docDate] = (stepsMap[docDate] ?: 0) + stepsValue
                        }
                    }
                    // Create a list of steps ordered by queryDates
                    weeklySteps = queryDates.map { date -> stepsMap[date] ?: 0 }
                    Log.d("WeeklySteps", "Weekly steps: $weeklySteps")
                }
        }
    }

    // ---------------------------
    // Query for food entries list based on selected date:
    LaunchedEffect(selectedDate, currentUser) {
        currentUser?.let { user ->
            FirebaseFirestore.getInstance().collection("foodEntries")
                .whereEqualTo("userId", user.uid)
                .whereEqualTo("dateString", selectedDateString)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && !snapshot.isEmpty) {
                        val items = snapshot.documents.mapNotNull { doc ->
                            doc.toObject(FoodEntry::class.java)?.copy(id = doc.id)
                        }
                        foodEntries = items
                    } else {
                        foodEntries = emptyList()
                    }
                }
        }
    }

    // ---------------------------
    // Query for calorie intake - always use current day:
    LaunchedEffect(currentUser) {
        currentUser?.let { user ->
            val todayString = dateFormatForFood.format(Date())  // Today’s date
            FirebaseFirestore.getInstance().collection("foodEntries")
                .whereEqualTo("userId", user.uid)
                .whereEqualTo("dateString", todayString)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    calorieIntakeToday = if (snapshot != null && !snapshot.isEmpty) {
                        snapshot.documents.sumOf { doc ->
                            doc.getLong("caloricValue")?.toInt() ?: 0
                        }
                    } else {
                        0
                    }
                }
        }
    }

    // Query for calorie intake for the past 7 days
    LaunchedEffect(weeklyDates, currentUser) {
        currentUser?.let { user ->
            // Initialize a map for calorie intake by date
            val caloriesMap = mutableMapOf<String, Int>()

            // Query for food entries within the past 7 days
            FirebaseFirestore.getInstance().collection("foodEntries")
                .whereEqualTo("userId", user.uid)
                .whereIn("dateString", weeklyDates) // Ensure your query uses the correct weeklyDates
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("WeeklyCalories", "Error fetching calorie data: ${error.message}")
                        return@addSnapshotListener
                    }

                    // Initialize all the days to 0 calories
                    weeklyCalories = weeklyDates.map { 0 }

                    snapshot?.documents?.forEach { doc ->
                        val dateString = doc.getString("dateString")
                        val caloricValue = doc.getLong("caloricValue")?.toInt() ?: 0

                        // Sum the calories for each date in the snapshot
                        if (dateString != null && weeklyDates.contains(dateString)) {
                            // Update the caloriesMap
                            caloriesMap[dateString] = (caloriesMap[dateString] ?: 0) + caloricValue
                        }
                    }

                    // Update weeklyCalories with the summed calorie intake for each date
                    weeklyCalories = weeklyDates.map { date ->
                        caloriesMap[date] ?: 0
                    }

                    Log.d("WeeklyCalories", "Weekly Calorie Intakes: $weeklyCalories")
                }
        }
    }




    // DatePickerDialog display:
    if (showDatePicker) {
        val context = LocalContext.current
        val datePickerDialog = android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val calendar = Calendar.getInstance()
                calendar.set(year, month, dayOfMonth)
                selectedDate = calendar.time
                showDatePicker = false
            },
            Calendar.getInstance().get(Calendar.YEAR),
            Calendar.getInstance().get(Calendar.MONTH),
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        )

        datePickerDialog.setOnCancelListener {
            showDatePicker = false
        }

        datePickerDialog.show()
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard", modifier = Modifier.fillMaxWidth()) },
                modifier = Modifier.padding(horizontal = 24.dp),
                actions = {
                    IconButton(onClick = { navController.navigate("history_screen") }) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = "History"
                        )
                    }
                }
            )
        },
        bottomBar = { BottomNavigationBar(navController) }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                "Daily Summary",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = Primary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Step Counter
                    StepCounter(user!!) { newStepCount ->
                        stepCount = newStepCount
                    }
                    // Calorie Intake card uses today's calorie intake
                    HealthStatCard(title = "Calorie Intake", value = "$calorieIntakeToday kcal", onClick = { navController.navigate("capture_food_screen") })
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Water Intake
                    HealthStatCard("Water Intake", "$hydration ml")
                    // Current Weight
                    HealthStatCard(title = "Current Weight", value = "$weight kg", onClick = { navController.navigate("profile_screen") })
                }
            }

            val caloriesBurned = (steps * weight * 0.0005).toInt()

            Box(
                modifier = Modifier.wrapContentWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CaloriesBurnedCard(
                    caloriesBurned = caloriesBurned,
                )
            }

            DailyGoalProgress("Steps", steps, dailyStepGoal, "steps")
            DailyGoalProgress("Calories", calorieIntakeToday, dailyCalorieGoal, "kcal")
            DailyGoalProgress("Hydration", hydration, dailyHydrationGoal, "ml")

            // Log Extra Water
            QuickWaterLogging(
                onLogWater = { amount ->
                    coroutineScope.launch {
                        currentUser?.let { user ->
                            hydration += amount
                            FirebaseFirestore.getInstance().collection("users")
                                .document(user.uid)
                                .update("hydration", hydration)
                        }
                    }
                },
                onResetWater = {
                    coroutineScope.launch {
                        currentUser?.let { user ->
                            hydration = 0
                            FirebaseFirestore.getInstance().collection("users")
                                .document(user.uid)
                                .update("hydration", hydration)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Sync Now Button
            SyncNowBtn(user!!, stepCount, stepsRef)

            Spacer(modifier = Modifier.height(16.dp))

            // Weekly Steps
            Text(
                text = "Weekly Steps",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            WeeklyStepsLineGraphWithAxes(weeklySteps, weeklyDates)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Weekly Calorie Intake",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            // Show the bar chart
            WeeklyCalorieBarChart(
                weeklyCalories = weeklyCalories,
                dateLabels = weeklyDates
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Food Entries Section with Date Selection (for list)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Food Entries for: $selectedDateString",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Select Date"
                    )
                }
            }

            Text("Food Eaten", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (foodEntries.isEmpty()) {
                Text("No entries yet!", style = MaterialTheme.typography.bodyLarge)
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    foodEntries.forEach { entry ->
                        // Wrap the FoodEntryCard in SwipeToDismiss
                        val dismissState = rememberDismissState(
                            confirmStateChange = { dismissValue ->
                                if (dismissValue == DismissValue.DismissedToStart) {
                                    // Set the entry to be deleted and show confirmation dialog
                                    entryToDelete = entry
                                    showDialog = true
                                }
                                false // Prevent auto-dismiss after action
                            }
                        )

                        SwipeToDismiss(
                            state = dismissState,
                            directions = setOf(DismissDirection.EndToStart),
                            background = {
                                val color = if (dismissState.targetValue == DismissValue.Default)
                                    MaterialTheme.colorScheme.surface
                                else
                                    MaterialTheme.colorScheme.error
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(color)
                                        .padding(8.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            },
                            dismissContent = {
                                FoodEntryCard(entry)
                            }
                        )
                    }
                }
            }

            CaptureFoodBtn(navController = navController)

            Spacer(modifier = Modifier.height(16.dp))

        }
    }
}

/**
 * Data class for a food entry document in Firestore.
 */
data class FoodEntry(
    val id: String = "",
    val foodName: String = "",
    val caloricValue: Int = 0,
    val timestamp: Timestamp? = null,
    val userId: String = ""
)

/**
 * Composable to display a single FoodEntry.
 */
@Composable
fun FoodEntryCard(entry: FoodEntry) {
    val sdf = SimpleDateFormat("MMM d, yyyy hh:mm a", Locale.getDefault())
    val dateString = entry.timestamp?.toDate()?.let { sdf.format(it) } ?: "No date"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Secondary, contentColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = entry.foodName, style = MaterialTheme.typography.titleMedium)
            Text(text = "Calories: ${entry.caloricValue}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Date: $dateString", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Shows daily goal progress for a stat (e.g., steps, calories, or hydration).
 */
@Composable
fun DailyGoalProgress(statLabel: String, currentValue: Int, goalValue: Int, unit: String) {
    val progressFraction = if (goalValue != 0) {
        min(currentValue.toFloat() / goalValue, 1f)
    } else {
        0f // Avoid division by zero, set progress to 0
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "$statLabel: $currentValue / $goalValue $unit",
            style = MaterialTheme.typography.bodyLarge,  // Using bodyLarge for Normal font weight
            modifier = Modifier.fillMaxWidth()
        )
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            color = Secondary,  // The actual progress line
            trackColor = SecondaryContainer
        )
    }
}

/**
 * A basic placeholder 'chart' for weekly steps.
 */
@Composable
fun WeeklyStepsLineGraphWithAxes(
    weeklySteps: List<Int>,
    dateLabels: List<String>
) {
    // Check if all step values are zero
    val isStepsDataEmpty = weeklySteps.all { it == 0 }
    // State to control whether the axes should be visible
    var showAxes by remember { mutableStateOf(false) }
    // After 3 seconds, set showAxes to true
    LaunchedEffect(Unit) {
        if (!isStepsDataEmpty) {
            delay(2000)  // Only delay if there's data
            showAxes = true
        }
    }

    // Compute min/max from the data
    val maxSteps = (weeklySteps.maxOrNull() ?: 0).coerceAtLeast(1)
    val minSteps = weeklySteps.minOrNull() ?: 0
    val range = (maxSteps - minSteps).coerceAtLeast(1)

    // Axis spacing
    val leftPaddingPx = 60f    // space for y-axis labels
    val bottomPaddingPx = 40f  // space for x-axis labels
    val primaryColor = MaterialTheme.colorScheme.primary


    // Only set labelCount if there is meaningful data
    val labelCount = if (isStepsDataEmpty) 0 else 5
    // Calculate the step increment for each label
    val stepIncrement = (range / (labelCount - 1)).coerceAtLeast(1)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        // The usable chart area is reduced by our left/bottom padding
        val chartWidth = size.width - leftPaddingPx
        val chartHeight = size.height - bottomPaddingPx

        if (showAxes) { // Only draw y-axis labels when data is present
            for (i in 0 until labelCount) {
                val labelValue = minSteps + i * stepIncrement
                val fraction = (labelValue - minSteps) / range.toFloat()
                val yCoord = chartHeight * (1 - fraction)

                drawLine(
                    color = Color.Black,
                    start = Offset(x = leftPaddingPx - 5f, y = yCoord),
                    end = Offset(x = leftPaddingPx, y = yCoord),
                    strokeWidth = 2f
                )

                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        labelValue.toString(),
                        leftPaddingPx - 10f,
                        yCoord + 8f,
                        android.graphics.Paint().apply {
                            textSize = 32f
                            textAlign = android.graphics.Paint.Align.RIGHT
                            color = android.graphics.Color.BLACK
                        }
                    )
                }
            }
        }

        // Conditionally draw the axes lines and y-axis labels
        if (showAxes) {
            // Draw the Y-axis line
            drawLine(
                color = Color.Black,
                start = Offset(x = leftPaddingPx, y = 0f),
                end = Offset(x = leftPaddingPx, y = chartHeight),
                strokeWidth = 2f
            )

            // Draw the X-axis line
            drawLine(
                color = Color.Black,
                start = Offset(x = leftPaddingPx, y = chartHeight),
                end = Offset(x = leftPaddingPx + chartWidth, y = chartHeight),
                strokeWidth = 2f
            )

            // Draw Y-axis labels and ticks
            for (i in 0 until labelCount) {
                val labelValue = minSteps + i * stepIncrement
                val fraction = (labelValue - minSteps) / range.toFloat()
                // Y coordinate (invert fraction because (0,0) is top-left)
                val yCoord = chartHeight * (1 - fraction)

                // Draw a small horizontal tick on the y-axis
                drawLine(
                    color = Color.Black,
                    start = Offset(x = leftPaddingPx - 5f, y = yCoord),
                    end = Offset(x = leftPaddingPx, y = yCoord),
                    strokeWidth = 2f
                )

                // Draw the label text to the left of the y-axis
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        labelValue.toString(),
                        leftPaddingPx - 10f, // a bit to the left of the tick
                        yCoord + 8f,         // shift down for better centering
                        android.graphics.Paint().apply {
                            textSize = 32f
                            textAlign = android.graphics.Paint.Align.RIGHT
                            color = android.graphics.Color.BLACK
                        }
                    )
                }
            }
        }

        // Calculate each data point's (x, y) within the chart area
        val spacing = if (weeklySteps.size > 1) {
            chartWidth / (weeklySteps.size - 1)
        } else {
            chartWidth
        }
        val points = weeklySteps.mapIndexed { index, stepsValue ->
            val fraction = (stepsValue - minSteps) / range.toFloat()
            val x = leftPaddingPx + index * spacing
            val y = chartHeight * (1 - fraction)
            Offset(x, y)
        }

        // Draw the data line between consecutive points
        for (i in 0 until points.size - 1) {
            drawLine(
                color = primaryColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 4f
            )
        }

        // Draw circles at each data point
        points.forEach { point ->
            drawCircle(
                color = primaryColor,
                radius = 6f,
                center = point
            )
        }

        // Conditionally draw X-axis labels (day only) under each point
        if (showAxes) {
            points.forEachIndexed { index, point ->
                val dateLabel = dateLabels.getOrNull(index) ?: ""
                // Extract only the day (e.g., "28") from "Feb 28, 2025"
                val day = try {
                    val parsedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).parse(dateLabel)
                    SimpleDateFormat("d", Locale.getDefault()).format(parsedDate)
                } catch (e: Exception) {
                    dateLabel // fallback if parsing fails
                }

                // Place the text slightly below the x-axis
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        day,
                        point.x,
                        chartHeight + 30f, // below the x-axis line
                        android.graphics.Paint().apply {
                            textSize = 32f
                            color = android.graphics.Color.BLACK
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }
}

/**
 * Buttons for quick water intake logging: +250 ml, +500 ml, +1000 ml, and Reset.
 */
@Composable
fun QuickWaterLogging(
    onLogWater: (Int) -> Unit,
    onResetWater: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Log Extra Water",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = onResetWater,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.background,
                    contentColor = Tertiary
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = "Reset",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val interactionSource = remember { MutableInteractionSource() }

            OutlinedButton(
                onClick = { onLogWater(250) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colorScheme.background,
                    contentColor = Unfocused
                ),
                border = BorderStroke(1.dp, Unfocused),
                interactionSource = interactionSource,
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
                val isPressed = interactionSource.collectIsPressedAsState()
                Text(
                    "+250 ml",
                    color = if (isPressed.value) Primary else Unfocused
                )
            }

            OutlinedButton(
                onClick = { onLogWater(500) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colorScheme.background,
                    contentColor = Unfocused
                ),
                border = BorderStroke(1.dp, Unfocused),
                interactionSource = interactionSource,
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
                val isPressed = interactionSource.collectIsPressedAsState()
                Text(
                    "+500 ml",
                    color = if (isPressed.value) Primary else Unfocused
                )
            }

            OutlinedButton(
                onClick = { onLogWater(1000) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colorScheme.background,
                    contentColor = Unfocused
                ),
                border = BorderStroke(1.dp, Unfocused),
                interactionSource = interactionSource,
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
                val isPressed = interactionSource.collectIsPressedAsState()
                Text(
                    "+1000 ml",
                    color = if (isPressed.value) Primary else Unfocused
                )
            }
        }
    }
}

/**
 * Card to display a title and value.
 */
@Composable
fun HealthStatCard(title: String, value: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), // Apply clickable only if onClick is not null
        colors = CardDefaults.cardColors(containerColor = Secondary),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, color = SecondaryContainer)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = Color.White)
        }
    }
}

@Composable
fun CaloriesBurnedCard(caloriesBurned: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Secondary, contentColor = Color.White),
        shape = MaterialTheme.shapes.small
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth().fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Calories Burned",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    color = SecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$caloriesBurned kcal",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
            }
        }
    }
}

// New Sync Button (Styled like other buttons)
@Composable
fun SyncNowBtn(user: FirebaseUser, stepCount: Int, stepsRef: DocumentReference) {
    Button(
        onClick = { syncStepsToFirestore(user, stepCount.toLong(), stepsRef) },
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = colorScheme.onPrimary
        ),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(56.dp)
    ) {
        Text("Sync Now")
    }
}

@Composable
fun CaptureFoodBtn(navController: NavController) {
    Button(
        onClick = {
            navController.navigate(
                "capture_food_screen"
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = colorScheme.onPrimary
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(56.dp)
    ) {
        Text("Add Food Data")
    }
}

@Composable
fun AIHealthTipsCard(healthTips: String, isLoading: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "AI Health Tips",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, shape = MaterialTheme.shapes.small,),
            colors = CardDefaults.cardColors(containerColor = Secondary, contentColor = Color.White),
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White)
                }  else {
                    this@Card.AnimatedVisibility(visible = !isLoading, enter = fadeIn()) {
                        Text(
                            text = healthTips,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}


@Composable
fun WeeklyCalorieBarChart(
    weeklyCalories: List<Int>,
    dateLabels: List<String>
) {
    val isDataEmpty = weeklyCalories.all { it == 0 }
    // Optional: a state to show/hide axes after some delay, just like you did for the line graph
    var showAxes by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        //delay(50)
        showAxes = true
    }

    // Calculate min/max to scale bars properly:
    val maxCalories = (weeklyCalories.maxOrNull() ?: 0).coerceAtLeast(1)
    val minCalories = weeklyCalories.minOrNull() ?: 0
    val range = (maxCalories - minCalories).coerceAtLeast(1)

    // Axis spacing on the left and bottom to accommodate text
    val leftPaddingPx = 60f
    val bottomPaddingPx = 40f

    // Number of horizontal "ticks" for the y-axis
    //val labelCount = 5


    // Primary color for bars
    val barColor = MaterialTheme.colorScheme.primary



    // Number of horizontal "ticks" for the y-axis
    val labelCount = if (isDataEmpty) 0 else 5 // Hide labels if all data is 0
    // Calculate the step increment between each tick label on the y-axis
    val stepIncrement = (range / (labelCount - 1)).coerceAtLeast(1)




    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        // The usable chart area is reduced by our left/bottom padding
        val chartWidth = size.width - leftPaddingPx
        val chartHeight = size.height - bottomPaddingPx

        if (!isDataEmpty) { // Only draw axis labels when data is present
            for (i in 0 until labelCount) {
                val labelValue = minCalories + i * stepIncrement
                val fraction = (labelValue - minCalories) / range.toFloat()
                val yCoord = chartHeight * (1 - fraction)

                // Tick mark on the y-axis
                drawLine(
                    color = Color.Black,
                    start = Offset(x = leftPaddingPx - 5f, y = yCoord),
                    end = Offset(x = leftPaddingPx, y = yCoord),
                    strokeWidth = 2f
                )

                // Draw label text
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        labelValue.toString(),
                        leftPaddingPx - 10f,
                        yCoord + 8f,
                        android.graphics.Paint().apply {
                            textSize = 32f
                            textAlign = android.graphics.Paint.Align.RIGHT
                            color = android.graphics.Color.BLACK
                        }
                    )
                }
            }
        }

        // 1. Draw axes (optional)
        if (showAxes) {
            // Y-axis
            drawLine(
                color = Color.Black,
                start = Offset(x = leftPaddingPx, y = 0f),
                end = Offset(x = leftPaddingPx, y = chartHeight),
                strokeWidth = 2f
            )

            // X-axis
            drawLine(
                color = Color.Black,
                start = Offset(x = leftPaddingPx, y = chartHeight),
                end = Offset(x = leftPaddingPx + chartWidth, y = chartHeight),
                strokeWidth = 2f
            )

            // 2. Draw Y-axis labels and ticks
            for (i in 0 until labelCount) {
                val labelValue = minCalories + i * stepIncrement
                val fraction = (labelValue - minCalories) / range.toFloat()
                // Y coordinate (invert fraction because (0,0) is top-left)
                val yCoord = chartHeight * (1 - fraction)

                // Tick mark on the y-axis
                drawLine(
                    color = Color.Black,
                    start = Offset(x = leftPaddingPx - 5f, y = yCoord),
                    end = Offset(x = leftPaddingPx, y = yCoord),
                    strokeWidth = 2f
                )

                // Draw label text
                drawContext.canvas.nativeCanvas.apply {
                    drawText(
                        labelValue.toString(),
                        leftPaddingPx - 10f, // a bit to the left of the tick
                        yCoord + 8f,         // shift down for better centering
                        android.graphics.Paint().apply {
                            textSize = 32f
                            textAlign = android.graphics.Paint.Align.RIGHT
                            color = android.graphics.Color.BLACK
                        }
                    )
                }
            }
        }

        // 3. Calculate each bar’s position and size
        val barCount = weeklyCalories.size
        if (barCount > 0) {
            // Each "slot" on the X-axis for one bar
            val slotWidth = chartWidth / barCount

            // A fraction of the slot width to use for actual bar width
            // (so there is some space between bars)
            val barWidth = slotWidth * 0.6f

            weeklyCalories.forEachIndexed { index, calories ->
                val fraction = (calories - minCalories) / range.toFloat()
                val barHeight = chartHeight * fraction

                // Calculate the left/right coordinate for the bar
                val left = leftPaddingPx + (index * slotWidth) + (slotWidth - barWidth) / 2
                val top = chartHeight - barHeight
                val right = left + barWidth
                val bottom = chartHeight

                // Draw the bar
                drawRect(
                    color = barColor,
                    topLeft = Offset(left, top),
                    size = Size(width = barWidth, height = barHeight)
                )

                // 4. Optionally label each bar along the X-axis
                if (showAxes) {
                    // For dateLabels, we can either show the entire date or just the day
                    val dateLabel = dateLabels.getOrNull(index) ?: ""
                    val dayString = try {
                        val parsedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).parse(dateLabel)
                        // Show day number only
                        SimpleDateFormat("d", Locale.getDefault()).format(parsedDate)
                    } catch (e: Exception) {
                        dateLabel // fallback if parsing fails
                    }

                    // Place the text slightly below the x-axis
                    drawContext.canvas.nativeCanvas.apply {
                        drawText(
                            dayString,
                            left + barWidth / 2,       // center the text under the bar
                            chartHeight + 30f,         // below the x-axis
                            android.graphics.Paint().apply {
                                textSize = 32f
                                color = android.graphics.Color.BLACK
                                textAlign = android.graphics.Paint.Align.CENTER
                                isFakeBoldText = true
                            }
                        )
                    }
                }
            }
        }
    }
}


