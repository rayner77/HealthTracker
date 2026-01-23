package com.inf2007.healthtracker.Screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.inf2007.healthtracker.ui.theme.Primary
import com.inf2007.healthtracker.ui.theme.Secondary
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, friendUid: String, friendName: String) {
    val currentUser = FirebaseAuth.getInstance().currentUser ?: return
    val currentUid = currentUser.uid
    val firestore = FirebaseFirestore.getInstance()

    // Create a unique chat ID based on user IDs (alphabetically sorted to ensure consistency)
    val chatId = if (currentUid < friendUid) "${currentUid}_${friendUid}" else "${friendUid}_${currentUid}"

    var messageText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val listState = rememberLazyListState()

    // Listen for real-time messages
    LaunchedEffect(chatId) {
        firestore.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    messages = snapshot.documents.mapNotNull { it.toObject(ChatMessage::class.java) }
                }
            }
    }

    // Auto-scroll to bottom when new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(friendName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Primary, titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
        ) {
            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages) { msg ->
                    MessageBubble(msg, isCurrentUser = msg.senderId == currentUid)
                }
            }

            // Input Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            val msg = ChatMessage(
                                senderId = currentUid,
                                text = messageText,
                                timestamp = Timestamp.now()
                            )
                            firestore.collection("chats").document(chatId)
                                .collection("messages")
                                .add(msg)
                            messageText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Primary, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, isCurrentUser: Boolean) {
    val dateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        Card(
            shape = if (isCurrentUser)
                RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
            else
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isCurrentUser) Primary else Color.White
            ),
            elevation = CardDefaults.cardElevation(1.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    color = if (isCurrentUser) Color.White else Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.timestamp.toDate().let { dateFormat.format(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrentUser) Color.White.copy(alpha = 0.7f) else Color.Gray,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}