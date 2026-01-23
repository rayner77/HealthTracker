
package com.inf2007.healthtracker.Screens

import com.google.firebase.Timestamp
import java.util.Date

data class SocialUser(
    val uid: String = "",
    val name: String = "",
    val email: String = ""
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