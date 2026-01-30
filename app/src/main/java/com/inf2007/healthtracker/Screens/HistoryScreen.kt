package com.inf2007.healthtracker.Screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.rememberDismissState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.inf2007.healthtracker.utilities.BottomNavigationBar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.Calendar
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    var foodEntriesHistory by remember { mutableStateOf<List<FoodEntry2>>(emptyList()) }
    var stepsHistory by remember { mutableStateOf<List<StepsEntry>>(emptyList()) }
    var filteredFoodEntries by remember { mutableStateOf<List<FoodEntry2>>(emptyList()) }
    var filteredStepsHistory by remember { mutableStateOf<List<StepsEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isDateRangeSearch by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<Any?>(null) }
    val currentUser = FirebaseAuth.getInstance().currentUser

    // Expanded states for date sections
    var expandedDates by remember { mutableStateOf(setOf<String>()) }

    // Date range picker states
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val startDatePickerState = rememberDatePickerState(initialSelectedDateMillis = null, initialDisplayMode = DisplayMode.Picker)
    val endDatePickerState = rememberDatePickerState(initialSelectedDateMillis = null, initialDisplayMode = DisplayMode.Picker)

    // Initialize with today and a week ago
    val calendar = Calendar.getInstance()
    val today = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_YEAR, -7)
    val weekAgo = calendar.timeInMillis

    // Activities state variables
    var activitiesHistory by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var filteredActivitiesHistory by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }

    // Convert date picker states to actual dates
    val startDate = startDatePickerState.selectedDateMillis?.let {
        // Set start date to beginning of day (00:00:00)
        val cal = Calendar.getInstance()
        cal.timeInMillis = it
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        Date(cal.timeInMillis)
    }
    val endDate = endDatePickerState.selectedDateMillis?.let {
        // Set end date to end of day (23:59:59)
        val cal = Calendar.getInstance()
        cal.timeInMillis = it
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        Date(cal.timeInMillis)
    }

    // Date formatter for display
    val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val inputDateFormats = listOf(
        SimpleDateFormat("MMM d yyyy", Locale.getDefault()),
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()),
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()),
        SimpleDateFormat("d MMM, yyyy", Locale.getDefault()),
        SimpleDateFormat("d MMM", Locale.getDefault()),
        SimpleDateFormat("MMMM d yyyy", Locale.getDefault()),
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()),
        SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()),
        SimpleDateFormat("d MMMM, yyyy", Locale.getDefault())
    )

    // Function to filter the history entries based on search mode
    fun filterHistoryEntries() {

        if (isDateRangeSearch && startDate != null && endDate != null) {

            filteredFoodEntries = foodEntriesHistory.filter {
                it.timestamp?.toDate()?.let { d -> d in startDate..endDate } == true
            }

            filteredStepsHistory = stepsHistory.filter {
                it.timestamp?.toDate()?.let { d -> d in startDate..endDate } == true
            }

            filteredActivitiesHistory = activitiesHistory.filter {
                it.startTime?.toDate()?.let { d -> d in startDate..endDate } == true
            }

        } else if (searchQuery.isNotBlank()) {

            val query = searchQuery.lowercase()

            filteredFoodEntries = foodEntriesHistory.filter {
                it.timestamp?.toDate()?.let { d ->
                    dateFormatter.format(d).lowercase().contains(query)
                } == true
            }

            filteredStepsHistory = stepsHistory.filter {
                it.timestamp?.toDate()?.let { d ->
                    dateFormatter.format(d).lowercase().contains(query)
                } == true
            }

            filteredActivitiesHistory = activitiesHistory.filter {
                it.startTime?.toDate()?.let { d ->
                    dateFormatter.format(d).lowercase().contains(query)
                } == true
            }

        } else {
            filteredFoodEntries = foodEntriesHistory
            filteredStepsHistory = stepsHistory
            filteredActivitiesHistory = activitiesHistory
        }
    }

    fun clearDateRangeFilter() {
        // Reset the DatePicker states to empty (null)
        startDatePickerState.selectedDateMillis = null
        endDatePickerState.selectedDateMillis = null

        // Reset search states and criteria
        isDateRangeSearch = false
        searchQuery = ""

        // Reset filtered lists to show all entries
        filteredFoodEntries = foodEntriesHistory
        filteredStepsHistory = stepsHistory
        filteredActivitiesHistory = activitiesHistory
    }

    LaunchedEffect(Unit) {
        currentUser?.let {
            // Fetch Food Entries from Firestore
            FirebaseFirestore.getInstance().collection("foodEntries")
                .whereEqualTo("userId", it.uid)
                .orderBy("dateString", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("HistoryScreen", "Error fetching food entries: ${error.message}")
                        return@addSnapshotListener
                    }
                    snapshot?.let { snap ->
                        foodEntriesHistory = snap.documents.mapNotNull { doc ->
                            try {
                                doc.toObject(FoodEntry2::class.java)?.copy(id = doc.id)
                            } catch (e: Exception) {
                                Log.e("HistoryScreen", "Error parsing food entry: ${e.message}")
                                null
                            }
                        }
                        filterHistoryEntries()
                    }
                }

            // Fetch Steps History from Firestore
            FirebaseFirestore.getInstance().collection("steps")
                .whereEqualTo("userId", it.uid)
                .orderBy("dateString", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("HistoryScreen", "Error fetching steps entries: ${error.message}")
                        return@addSnapshotListener
                    }
                    snapshot?.let { snap ->
                        stepsHistory = snap.documents.mapNotNull { doc ->
                            try {
                                doc.toObject(StepsEntry::class.java)?.copy(id = doc.id)
                            } catch (e: Exception) {
                                Log.e("HistoryScreen", "Error parsing steps entry: ${e.message}")
                                null
                            }
                        }
                        // Sort stepsHistory locally (just in case it's not sorted properly)
                        stepsHistory = stepsHistory.sortedByDescending { it.timestamp?.toDate() }
                        filterHistoryEntries()
                    }
                }

            FirebaseFirestore.getInstance().collection("activities")
                .whereEqualTo("userId", it.uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("HistoryScreen", "Error fetching activities: ${error.message}")
                        return@addSnapshotListener
                    }

                    snapshot?.let { snap ->
                        activitiesHistory = snap.documents.mapNotNull { doc ->
                            try {
                                doc.toObject(ActivityEntry::class.java)?.copy(id = doc.id)
                            } catch (e: Exception) {
                                Log.e("HistoryScreen", "Activity parse error", e)
                                null
                            }
                        }
                        filteredActivitiesHistory = activitiesHistory
                    }
                }

        }
    }

    // Date picker dialog for start date
    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showStartDatePicker = false
                    filterHistoryEntries()
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = startDatePickerState)
        }
    }

    // Date picker dialog for end date
    if (showEndDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showEndDatePicker = false
                    filterHistoryEntries()
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = endDatePickerState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History", fontWeight = FontWeight.Bold) },
                modifier = Modifier.padding(horizontal = 24.dp),
                actions = {
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(
                            if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (isSearchActive) "Close Search" else "Search"
                        )
                    }
                }
            )
        },
        bottomBar = { BottomNavigationBar(navController) },
        containerColor = MaterialTheme.colorScheme.background,
        content = { paddingValues ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Show search options when search is active
                if (isSearchActive) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Search type toggle
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !isDateRangeSearch,
                                onClick = {
                                    isDateRangeSearch = false
                                    filterHistoryEntries()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) {
                                Text("Single Date")
                            }
                            SegmentedButton(
                                selected = isDateRangeSearch,
                                onClick = {
                                    isDateRangeSearch = true
                                    filterHistoryEntries()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) {
                                Text("Date Range")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isDateRangeSearch) {
                            // Date range picker UI
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Start date field
                                OutlinedTextField(
                                    value = startDate?.let { dateFormatter.format(it) } ?: "",
                                    onValueChange = { },
                                    readOnly = true,
                                    label = { Text("Start Date") },
                                    modifier = Modifier.weight(1f),
                                    trailingIcon = {
                                        IconButton(onClick = { showStartDatePicker = true }) {
                                            Icon(Icons.Filled.CalendarMonth, "Select start date")
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                // End date field
                                OutlinedTextField(
                                    value = endDate?.let { dateFormatter.format(it) } ?: "",
                                    onValueChange = { },
                                    readOnly = true,
                                    label = { Text("End Date") },
                                    modifier = Modifier.weight(1f),
                                    trailingIcon = {
                                        IconButton(onClick = { showEndDatePicker = true }) {
                                            Icon(Icons.Filled.CalendarMonth, "Select end date")
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Row for Apply and Clear buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Apply filter button
                                Button(
                                    onClick = { filterHistoryEntries() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Apply Filter")
                                }

                                // Clear filter button
                                Button(
                                    onClick = { clearDateRangeFilter() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Text("Clear Filter")
                                }
                            }
                        } else {
                            // Single date search
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { query ->
                                    searchQuery = query
                                    filterHistoryEntries()
                                },
                                label = { Text("Search by Date") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            searchQuery = ""
                                            filterHistoryEntries()
                                        }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear"
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                if (foodEntriesHistory.isEmpty() && stepsHistory.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filteredFoodEntries.isEmpty() && filteredStepsHistory.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.FilterList,
                                contentDescription = "No results",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No history found for the selected date range.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Calculate the totals from the filtered lists
                    val totalCaloriesfiltered = filteredFoodEntries.sumOf { it.caloricValue }
                    val totalStepsfiltered = filteredStepsHistory.sumOf { it.steps }

                    // Group food entries by date
                    val groupedFoodEntries = filteredFoodEntries.groupBy { entry ->
                        entry.timestamp?.toDate()?.let { dateFormatter.format(it) } ?: "No date"
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Show the totals at the top of the list
                        item {
                            TotalCard(totalCaloriesfiltered, totalStepsfiltered)
                        }

                        // Date range info when filter is active
                        if (isDateRangeSearch && startDate != null && endDate != null) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.CalendarToday,
                                            contentDescription = "Date Range",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            "Date Range: ${dateFormatter.format(startDate)} - ${dateFormatter.format(endDate)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Food Entries History",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                            )
                        }

                        if (filteredFoodEntries.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No food entries found.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            groupedFoodEntries.forEach { (date, entries) ->
                                val isExpanded = expandedDates.contains(date)
                                val totalCaloriesForDate = entries.sumOf { it.caloricValue }

                                item {
                                    // Date header with toggle functionality
                                    DateHeader(
                                        date = date,
                                        totalCalories = totalCaloriesForDate,
                                        isExpanded = isExpanded,
                                        onToggle = {
                                            expandedDates = if (isExpanded) {
                                                expandedDates - date
                                            } else {
                                                expandedDates + date
                                            }
                                        }
                                    )

                                    // Animated visibility for entries under this date
                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = fadeIn(animationSpec = tween(200)) + expandVertically(),
                                        exit = fadeOut(animationSpec = tween(200)) + shrinkVertically()
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)
                                        ) {
                                            entries.forEach { entry ->
                                                val dismissState = rememberDismissState(
                                                    confirmStateChange = { dismissValue ->
                                                        if (dismissValue == DismissValue.DismissedToStart) {
                                                            pendingDeleteItem = entry
                                                        }
                                                        false // Don't auto-dismiss
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
                                                                tint = MaterialTheme.colorScheme.onError,
                                                                modifier = Modifier.padding(end = 16.dp)
                                                            )
                                                        }
                                                    },
                                                    dismissContent = {
                                                        EnhancedFoodEntryCard(entry = entry)
                                                    }
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }

                                // When collapsed, show a summary line
                                if (!isExpanded) {
                                    item {
                                        Text(
                                            "${entries.size} food entries (tap to expand)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "Steps History",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        if (filteredStepsHistory.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No steps data found.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            items(filteredStepsHistory) { entry ->
                                val dismissState = rememberDismissState(
                                    confirmStateChange = { dismissValue ->
                                        if (dismissValue == DismissValue.DismissedToStart) {
                                            pendingDeleteItem = entry
                                        }
                                        false // Don't auto-dismiss
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
                                                tint = MaterialTheme.colorScheme.onError,
                                                modifier = Modifier.padding(end = 16.dp)
                                            )
                                        }
                                    },
                                    dismissContent = {
                                        StepsHistoryCard(entry = entry, dateFormatter = dateFormatter)
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        /* ---------------- ACTIVITIES HISTORY ---------------- */

                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                "Activities History",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        if (filteredActivitiesHistory.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No activities recorded.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            items(filteredActivitiesHistory) { entry ->
                                ActivityHistoryCard(entry)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                    }
                }
            }

            // Confirmation dialog for deletion
            pendingDeleteItem?.let { item ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteItem = null },
                    title = { Text("Delete History Entry") },
                    text = { Text("Are you sure you want to delete this entry?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (item is FoodEntry2) {
                                    FirebaseFirestore.getInstance().collection("foodEntries")
                                        .document(item.id)
                                        .delete()
                                        .addOnSuccessListener {
                                            foodEntriesHistory = foodEntriesHistory.filter { it.id != item.id }
                                            filteredFoodEntries = filteredFoodEntries.filter { it.id != item.id }
                                        }
                                } else if (item is StepsEntry) {
                                    FirebaseFirestore.getInstance().collection("steps")
                                        .document(item.id)
                                        .delete()
                                        .addOnSuccessListener {
                                            stepsHistory = stepsHistory.filter { it.id != item.id }
                                            filteredStepsHistory = filteredStepsHistory.filter { it.id != item.id }
                                        }
                                }
                                pendingDeleteItem = null
                            }
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteItem = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    )
}

@Composable
fun DateHeader(
    date: String,
    totalCalories: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isExpanded) 0.dp else 8.dp),
        onClick = onToggle,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Date with icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CalendarToday,
                        contentDescription = "Date",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    date,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            // Total calories for this date
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocalDining,
                    contentDescription = "Calories",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "$totalCalories cal",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
fun ActivityHistoryCard(entry: ActivityEntry) {

    val formatter = remember {
        SimpleDateFormat("MMM d, yyyy • hh:mm a", Locale.getDefault())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {

            Text(entry.activityType, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(4.dp))

            Text(
                entry.startTime?.toDate()?.let { formatter.format(it) } ?: "",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${entry.distanceKm.format(2)} km")
                Text("${entry.durationMinutes} min")
                Text("${entry.caloriesBurned} kcal")
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Avg speed ${entry.averageSpeedKmh.format(1)} km/h",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
fun EnhancedFoodEntryCard(entry: FoodEntry2) {
    val dateTimeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val timeString = entry.timestamp?.toDate()?.let { dateTimeFormatter.format(it) } ?: "--:--"

    // Determine the meal type icon based on time or name (this is just an example)
    val mealIcon = getMealIcon(entry)

    // Determine color based on caloric value
    val caloricColor = when {
        entry.caloricValue > 800 -> Color(0xFFE57373) // High calories - reddish
        entry.caloricValue > 500 -> Color(0xFFFFB74D) // Medium calories - orange
        else -> Color(0xFF81C784) // Low calories - greenish
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = caloricColor.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Meal icon circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                caloricColor.copy(alpha = 0.7f),
                                caloricColor.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = mealIcon,
                    contentDescription = "Meal Type",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Food details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.foodName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Time of entry
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Calorie counter with background
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(caloricColor.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("${entry.caloricValue}")
                        }
                        append(" cal")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = caloricColor
                )
            }
        }
    }
}

// Helper function to determine meal icon based on entry
fun getMealIcon(entry: FoodEntry2): ImageVector {
    // This is just an example logic - you might want to enhance this
    // based on actual meal type data if available
    val hour = entry.timestamp?.toDate()?.let {
        Calendar.getInstance().apply { time = it }.get(Calendar.HOUR_OF_DAY)
    } ?: 12

    return when {
        hour in 5..10 -> Icons.Filled.Coffee  // Breakfast
        hour in 11..14 -> Icons.Filled.Restaurant  // Lunch
        hour in 15..21 -> Icons.Filled.Fastfood  // Dinner
        else -> Icons.Filled.RestaurantMenu
    }
}

@Composable
fun StepsHistoryCard(entry: StepsEntry, dateFormatter: SimpleDateFormat) {
    val dateString = entry.timestamp?.toDate()?.let { dateFormatter.format(it) } ?: "No date"

    // Determine color based on steps count
    val stepsColor = when {
        entry.steps > 10000 -> Color(0xFF43A047) // Exceeds daily goal - green
        entry.steps > 7500 -> Color(0xFF7CB342) // Almost at goal - light green
        entry.steps > 5000 -> Color(0xFFFBC02D) // Halfway - yellow
        entry.steps > 2500 -> Color(0xFFFB8C00) // Some progress - orange
        else -> Color(0xFFE53935) // Little progress - red
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = stepsColor.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Steps icon circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                stepsColor.copy(alpha = 0.7f),
                                stepsColor.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = "Steps",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Date and steps info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                Text(
                    text = "${entry.steps} steps",
                    style = MaterialTheme.typography.bodyLarge,
                    color = stepsColor
                )
            }

            // Steps value in a badge (replaces the progress indicator)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(stepsColor.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${entry.steps}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = stepsColor
                )
            }
        }
    }
}

@Composable
fun TotalCard(totalCalories: Int, totalSteps: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                "Summary",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Row for Total Calories
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon in circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalDining,
                        contentDescription = "Calories Icon",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Total Calories",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Text(
                        text = "$totalCalories calories",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
            )

            // Row for Total Steps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon in circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = "Steps Icon",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Total Steps",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Text(
                        text = "$totalSteps steps",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp)
    )
}

fun Double.format(digits: Int) = "%.${digits}f".format(this)

// Data classes for Firestore documents
data class FoodEntry2(
    val id: String = "",
    val foodName: String = "",
    val caloricValue: Int = 0,
    val timestamp: Timestamp? = null,
    val userId: String = ""
)

data class StepsEntry(
    val id: String = "",
    val steps: Int = 0,
    val timestamp: Timestamp? = null,
    val userId: String = ""
)

data class ActivityEntry(
    val id: String = "",
    val activityType: String = "",
    val startTime: Timestamp? = null,
    val endTime: Timestamp? = null,
    val durationMinutes: Long = 0,
    val distanceKm: Double = 0.0,
    val averageSpeedKmh: Float = 0f,
    val caloriesBurned: Int = 0,
    val userId: String = ""
)
