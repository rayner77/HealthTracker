package com.inf2007.healthtracker.Screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.inf2007.healthtracker.ui.theme.Primary
import com.inf2007.healthtracker.utilities.BottomNavigationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Chats", "Add Friend", "Requests")
    val currentUser = FirebaseAuth.getInstance().currentUser
    val firestore = FirebaseFirestore.getInstance()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Social") })
        },
        bottomBar = { BottomNavigationBar(navController) }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> FriendsListSection(navController, currentUser?.uid ?: "")
                1 -> AddFriendSection(currentUser?.uid ?: "", currentUser?.displayName ?: "", currentUser?.email ?: "")
                2 -> FriendRequestsSection(currentUser?.uid ?: "")
            }
        }
    }
}

@Composable
fun FriendsListSection(navController: NavController, currentUid: String) {
    var friends by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUid) {
        FirebaseFirestore.getInstance().collection("users")
            .document(currentUid)
            .addSnapshotListener { snapshot, _ ->
                val friendIds = snapshot?.get("friendIds") as? List<String> ?: emptyList()
                if (friendIds.isNotEmpty()) {
                    // Firestore 'in' queries are limited to 10 items.
                    // For a production app, you would chunk this or structure data differently.
                    FirebaseFirestore.getInstance().collection("users")
                        .whereIn("uid", friendIds.take(10))
                        .get()
                        .addOnSuccessListener { result ->
                            friends = result.documents.mapNotNull { it.toObject(SocialUser::class.java) }
                            isLoading = false
                        }
                } else {
                    friends = emptyList()
                    isLoading = false
                }
            }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (friends.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No friends yet. Go to 'Add Friend' to find people!")
        }
    } else {
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            items(friends) { friend ->
                FriendItem(friend) {
                    // Navigate to Chat Screen
                    navController.navigate("chat_screen/${friend.uid}/${friend.name}")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun FriendItem(user: SocialUser, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    user.name.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(user.name, fontWeight = FontWeight.Bold)
                Text(user.email, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Chat", tint = Primary)
        }
    }
}

@Composable
fun AddFriendSection(currentUid: String, currentName: String, currentEmail: String) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<SocialUser?>(null) }
    var searchStatus by remember { mutableStateOf("") }
    val context = LocalContext.current
    val firestore = FirebaseFirestore.getInstance()

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search by Email") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = {
                    if (searchQuery.isNotEmpty()) {
                        searchStatus = "Searching..."
                        searchResult = null
                        firestore.collection("users")
                            .whereEqualTo("email", searchQuery)
                            .get()
                            .addOnSuccessListener { documents ->
                                if (!documents.isEmpty) {
                                    val user = documents.documents[0].toObject(SocialUser::class.java)
                                    if (user?.uid == currentUid) {
                                        searchStatus = "You cannot add yourself."
                                    } else {
                                        searchResult = user
                                        searchStatus = ""
                                    }
                                } else {
                                    searchStatus = "User not found."
                                }
                            }
                    }
                }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (searchStatus.isNotEmpty()) {
            Text(searchStatus, color = MaterialTheme.colorScheme.secondary)
        }

        searchResult?.let { user ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(user.name, fontWeight = FontWeight.Bold)
                        Text(user.email)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val request = FriendRequest(
                            fromUid = currentUid,
                            fromName = currentName,
                            fromEmail = currentEmail
                        )
                        // Add to friend_requests collection of the TARGET user
                        firestore.collection("users").document(user.uid)
                            .collection("friend_requests")
                            .add(request)
                            .addOnSuccessListener {
                                Toast.makeText(context, "Request sent!", Toast.LENGTH_SHORT).show()
                                searchQuery = ""
                                searchResult = null
                            }
                    }) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

@Composable
fun FriendRequestsSection(currentUid: String) {
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }

    LaunchedEffect(currentUid) {
        FirebaseFirestore.getInstance().collection("users").document(currentUid)
            .collection("friend_requests")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    requests = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(FriendRequest::class.java)?.copy(id = doc.id)
                    }
                }
            }
    }

    if (requests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pending requests.")
        }
    } else {
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            items(requests) { request ->
                RequestItem(currentUid, request)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun RequestItem(currentUid: String, request: FriendRequest) {
    val firestore = FirebaseFirestore.getInstance()
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Request from ${request.fromName}", fontWeight = FontWeight.Bold)
            Text(request.fromEmail, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        // 1. Update request status to accepted
                        firestore.collection("users").document(currentUid)
                            .collection("friend_requests").document(request.id)
                            .update("status", "accepted")

                        // 2. Add sender to current user's friendIds
                        firestore.collection("users").document(currentUid)
                            .update("friendIds", com.google.firebase.firestore.FieldValue.arrayUnion(request.fromUid))

                        // 3. Add current user to sender's friendIds
                        firestore.collection("users").document(request.fromUid)
                            .update("friendIds", com.google.firebase.firestore.FieldValue.arrayUnion(currentUid))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Accept")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        firestore.collection("users").document(currentUid)
                            .collection("friend_requests").document(request.id)
                            .delete()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decline")
                }
            }
        }
    }
}