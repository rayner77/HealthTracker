
package com.inf2007.healthtracker.Screens

import com.google.firebase.Timestamp
import java.util.Date

data class SocialUser(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val username: String = "",
    val phone: String = "",
    val role: String = "user",          // user or admin
    val expertise: String = "Community Member"
)

data class FriendRequest(
    val id: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val fromEmail: String = "",
    val status: String = "pending", // pending, accepted
    val timestamp: Timestamp = Timestamp.now()
)

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val text: String = "",
    val timestamp: Timestamp = Timestamp.now()
)

data class SupportTicket(
    val id: String = "",
    val userUid: String = "",
    val userName: String = "",
    val userEmail: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "open",
    val adminReply: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val respondedAt: Timestamp? = null
)