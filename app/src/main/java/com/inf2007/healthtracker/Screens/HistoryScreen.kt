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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Coffee
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.inf2007.healthtracker.utilities.BottomNavigationBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.DisposableEffect

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    var foodEntriesHistory by remember { mutableStateOf<List<FoodEntry2>>(emptyList()) }
    var stepsHistory by remember { mutableStateOf<List<StepsEntry>>(emptyList()) }
    var activitiesHistory by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }

    var filteredFoodEntries by remember { mutableStateOf<List<FoodEntry2>>(emptyList()) }
    var filteredStepsHistory by remember { mutableStateOf<List<StepsEntry>>(emptyList()) }
    var filteredActivitiesHistory by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isDateRangeSearch by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<Any?>(null) }
    var screenError by remember { mutableStateOf("") }

    var isFoodLoaded by remember { mutableStateOf(false) }
    var isStepsLoaded by remember { mutableStateOf(false) }
    var isActivitiesLoaded by remember { mutableStateOf(false) }

    val currentUser = FirebaseAuth.getInstance().currentUser

    var expandedDates by remember { mutableStateOf(setOf<String>()) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val startDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = null,
        initialDisplayMode = DisplayMode.Picker
    )
    val endDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = null,
        initialDisplayMode = DisplayMode.Picker
    )

    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    val startDate = startDatePickerState.selectedDateMillis?.let {
        Calendar.getInstance().apply {
            timeInMillis = it
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    val endDate = endDatePickerState.selectedDateMillis?.let {
        Calendar.getInstance().apply {
            timeInMillis = it
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.time
    }

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
        startDatePickerState.selectedDateMillis = null
        endDatePickerState.selectedDateMillis = null
        isDateRangeSearch = false
        searchQuery = ""
        filterHistoryEntries()
    }

    LaunchedEffect(
        foodEntriesHistory,
        stepsHistory,
        activitiesHistory,
        searchQuery,
        isDateRangeSearch,
        startDatePickerState.selectedDateMillis,
        endDatePickerState.selectedDateMillis
    ) {
        filterHistoryEntries()
    }

    DisposableEffect(currentUser?.uid) {
        if (currentUser == null) {
            isFoodLoaded = true
            isStepsLoaded = true
            isActivitiesLoaded = true
            screenError = "You must be logged in to view history."
            onDispose { }
        } else {
            val db = FirebaseFirestore.getInstance()
            val registrations = mutableListOf<ListenerRegistration>()

            screenError = ""

            val foodRegistration = db.collection("foodEntries")
                .whereEqualTo("userId", currentUser.uid)
                .orderBy("dateString", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    isFoodLoaded = true

                    if (error != null) {
                        Log.e("HistoryScreen", "Error fetching food entries", error)
                        screenError = "Failed to load some history data."
                        return@addSnapshotListener
                    }

                    foodEntriesHistory = snapshot?.documents?.mapNotNull { doc ->
                        try {
                            doc.toObject(FoodEntry2::class.java)?.copy(id = doc.id)
                        } catch (e: Exception) {
                            Log.e("HistoryScreen", "Error parsing food entry", e)
                            null
                        }
                    }.orEmpty()
                }

            registrations.add(foodRegistration)

            val stepsRegistration = db.collection("steps")
                .whereEqualTo("userId", currentUser.uid)
                .orderBy("dateString", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    isStepsLoaded = true

                    if (error != null) {
                        Log.e("HistoryScreen", "Error fetching steps entries", error)
                        screenError = "Failed to load some history data."
                        return@addSnapshotListener
                    }

                    stepsHistory = snapshot?.documents?.mapNotNull { doc ->
                        try {
                            doc.toObject(StepsEntry::class.java)?.copy(id = doc.id)
                        } catch (e: Exception) {
                            Log.e("HistoryScreen", "Error parsing steps entry", e)
                            null
                        }
                    }.orEmpty().sortedByDescending { it.timestamp?.toDate() }
                }

            registrations.add(stepsRegistration)

            val activitiesRegistration = db.collection("activities")
                .whereEqualTo("userId", currentUser.uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    isActivitiesLoaded = true

                    if (error != null) {
                        Log.e("HistoryScreen", "Error fetching activities", error)
                        screenError = "Failed to load some history data."
                        return@addSnapshotListener
                    }

                    activitiesHistory = snapshot?.documents?.mapNotNull { doc ->
                        try {
                            doc.toObject(ActivityEntry::class.java)?.copy(id = doc.id)
                        } catch (e: Exception) {
                            Log.e("HistoryScreen", "Activity parse error", e)
                            null
                        }
                    }.orEmpty()
                }

            registrations.add(activitiesRegistration)

            onDispose {
                registrations.forEach { it.remove() }
            }
        }
    }

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

    val isInitialLoading = !isFoodLoaded || !isStepsLoaded || !isActivitiesLoaded

    val allHistoryEmpty =
        foodEntriesHistory.isEmpty() &&
                stepsHistory.isEmpty() &&
                activitiesHistory.isEmpty()

    val allFilteredEmpty =
        filteredFoodEntries.isEmpty() &&
                filteredStepsHistory.isEmpty() &&
                filteredActivitiesHistory.isEmpty()

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
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isSearchActive) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedTextField(
                                value = startDate?.let { dateFormatter.format(it) } ?: "",
                                onValueChange = {},
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

                            OutlinedTextField(
                                value = endDate?.let { dateFormatter.format(it) } ?: "",
                                onValueChange = {},
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { filterHistoryEntries() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Apply Filter")
                            }

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

            if (screenError.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = screenError,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            when {
                isInitialLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                allHistoryEmpty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.FilterList,
                                contentDescription = "No history",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No history available yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                allFilteredEmpty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
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
                }

                else -> {
                    val totalCaloriesFiltered = filteredFoodEntries.sumOf { it.caloricValue }
                    val totalStepsFiltered = filteredStepsHistory.sumOf { it.steps }

                    val groupedFoodEntries = filteredFoodEntries.groupBy { entry ->
                        entry.timestamp?.toDate()?.let { dateFormatter.format(it) } ?: "No date"
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            TotalCard(totalCaloriesFiltered, totalStepsFiltered)
                        }

                        if (isDateRangeSearch && startDate != null && endDate != null) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
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

                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = fadeIn(animationSpec = tween(200)) + expandVertically(),
                                        exit = fadeOut(animationSpec = tween(200)) + shrinkVertically()
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(
                                                start = 8.dp,
                                                end = 8.dp,
                                                top = 8.dp,
                                                bottom = 16.dp
                                            )
                                        ) {
                                            entries.forEach { entry ->
                                                val dismissState = rememberDismissState(
                                                    confirmStateChange = { dismissValue ->
                                                        if (dismissValue == DismissValue.DismissedToStart) {
                                                            pendingDeleteItem = entry
                                                        }
                                                        false
                                                    }
                                                )

                                                SwipeToDismiss(
                                                    state = dismissState,
                                                    directions = setOf(DismissDirection.EndToStart),
                                                    background = {
                                                        val color =
                                                            if (dismissState.targetValue == DismissValue.Default) {
                                                                MaterialTheme.colorScheme.surface
                                                            } else {
                                                                MaterialTheme.colorScheme.error
                                                            }

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
                                        false
                                    }
                                )

                                SwipeToDismiss(
                                    state = dismissState,
                                    directions = setOf(DismissDirection.EndToStart),
                                    background = {
                                        val color =
                                            if (dismissState.targetValue == DismissValue.Default) {
                                                MaterialTheme.colorScheme.surface
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            }

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

            pendingDeleteItem?.let { item ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteItem = null },
                    title = { androidx.compose.material.Text("Delete History Entry") },
                    text = { androidx.compose.material.Text("Are you sure you want to delete this entry?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val db = FirebaseFirestore.getInstance()

                                when (item) {
                                    is FoodEntry2 -> {
                                        db.collection("foodEntries")
                                            .document(item.id)
                                            .delete()
                                            .addOnSuccessListener {
                                                foodEntriesHistory =
                                                    foodEntriesHistory.filter { it.id != item.id }
                                                filteredFoodEntries =
                                                    filteredFoodEntries.filter { it.id != item.id }
                                            }
                                    }

                                    is StepsEntry -> {
                                        db.collection("steps")
                                            .document(item.id)
                                            .delete()
                                            .addOnSuccessListener {
                                                stepsHistory =
                                                    stepsHistory.filter { it.id != item.id }
                                                filteredStepsHistory =
                                                    filteredStepsHistory.filter { it.id != item.id }
                                            }
                                    }

                                    is ActivityEntry -> {
                                        db.collection("activities")
                                            .document(item.id)
                                            .delete()
                                            .addOnSuccessListener {
                                                activitiesHistory =
                                                    activitiesHistory.filter { it.id != item.id }
                                                filteredActivitiesHistory =
                                                    filteredActivitiesHistory.filter { it.id != item.id }
                                            }
                                    }
                                }

                                pendingDeleteItem = null
                            }
                        ) {
                            androidx.compose.material.Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteItem = null }) {
                            androidx.compose.material.Text("Cancel")
                        }
                    }
                )
            }
        }
    }
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
    val mealIcon = getMealIcon(entry)

    val caloricColor = when {
        entry.caloricValue > 800 -> Color(0xFFE57373)
        entry.caloricValue > 500 -> Color(0xFFFFB74D)
        else -> Color(0xFF81C784)
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

                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

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

fun getMealIcon(entry: FoodEntry2): ImageVector {
    val hour = entry.timestamp?.toDate()?.let {
        Calendar.getInstance().apply { time = it }.get(Calendar.HOUR_OF_DAY)
    } ?: 12

    return when {
        hour in 5..10 -> Icons.Filled.Coffee
        hour in 11..14 -> Icons.Filled.Restaurant
        hour in 15..21 -> Icons.Filled.Fastfood
        else -> Icons.Filled.RestaurantMenu
    }
}

@Composable
fun StepsHistoryCard(entry: StepsEntry, dateFormatter: SimpleDateFormat) {
    val dateString = entry.timestamp?.toDate()?.let { dateFormatter.format(it) } ?: "No date"

    val stepsColor = when {
        entry.steps > 10000 -> Color(0xFF43A047)
        entry.steps > 7500 -> Color(0xFF7CB342)
        entry.steps > 5000 -> Color(0xFFFBC02D)
        entry.steps > 2500 -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

@IgnoreExtraProperties
data class ActivityEntry(
    val id: String = "",
    val activityType: String = "",
    val startTime: Timestamp? = null,
    val endTime: Timestamp? = null,
    val durationMinutes: Long = 0,
    val distanceKm: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val caloriesBurned: Int = 0,
    val userId: String = "",
    val createdAt: Timestamp? = null
)