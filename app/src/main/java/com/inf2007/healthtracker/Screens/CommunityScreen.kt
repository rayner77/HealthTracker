package com.inf2007.healthtracker.Screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.inf2007.healthtracker.utilities.BottomNavigationBar

private data class GroupUi(
    val id: String,
    val name: String,
    val description: String,
    val createdBy: String,
    val memberCount: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(navController: NavController) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val db = remember { FirebaseFirestore.getInstance() }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Your Groups, 1 = Join Groups

    var allGroups by remember { mutableStateOf<List<GroupUi>>(emptyList()) }
    var joinedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Create group dialog
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var createErr by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    // Listen to all groups
    LaunchedEffect(Unit) {
        db.collection("communityGroups")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { d ->
                    GroupUi(
                        id = d.id,
                        name = d.getString("name") ?: "Unnamed",
                        description = d.getString("description") ?: "",
                        createdBy = d.getString("createdBy") ?: "",
                        memberCount = d.getLong("memberCount") ?: 0L
                    )
                } ?: emptyList()
                allGroups = list
            }
    }

    // Listen to memberships (collectionGroup) – requires members docs contain "uid"
    LaunchedEffect(uid) {
        if (uid.isBlank()) return@LaunchedEffect

        db.collectionGroup("members")
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snap, _ ->
                val ids = snap?.documents
                    ?.mapNotNull { it.reference.parent.parent?.id }
                    ?.toSet()
                    ?: emptySet()
                joinedGroupIds = ids
            }
    }

    fun joinGroup(groupId: String, onDone: (Boolean) -> Unit) {
        if (uid.isBlank()) {
            onDone(false); return
        }

        val groupRef = db.collection("communityGroups").document(groupId)
        val memberRef = groupRef.collection("members").document(uid)

        db.runTransaction { tx ->
            val memberSnap = tx.get(memberRef)
            if (!memberSnap.exists()) {
                tx.set(memberRef, mapOf("uid" to uid, "joinedAt" to FieldValue.serverTimestamp()))
                tx.update(groupRef, "memberCount", FieldValue.increment(1))
            }
        }.addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun createGroup() {
        val name = newName.trim()
        if (name.isEmpty()) {
            createErr = "Group name cannot be empty"
            return
        }
        if (uid.isBlank()) {
            createErr = "Not logged in"
            return
        }

        creating = true
        createErr = null

        val groupRef = db.collection("communityGroups").document()
        val memberRef = groupRef.collection("members").document(uid)

        val groupData = mapOf(
            "name" to name,
            "description" to newDesc.trim(),
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "memberCount" to 1L
        )

        db.runBatch { b ->
            b.set(groupRef, groupData)
            b.set(memberRef, mapOf("uid" to uid, "joinedAt" to FieldValue.serverTimestamp()))
        }.addOnSuccessListener {
            creating = false
            showCreateDialog = false
            newName = ""; newDesc = ""
        }.addOnFailureListener { e ->
            creating = false
            createErr = e.message ?: "Failed to create group"
        }
    }

    val yourGroups = remember(allGroups, joinedGroupIds) {
        allGroups.filter { joinedGroupIds.contains(it.id) }
    }
    val joinGroups = remember(allGroups, joinedGroupIds, uid) {
        allGroups.filter { !joinedGroupIds.contains(it.id) && it.createdBy != uid }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!creating) showCreateDialog = false },
            title = { Text("Create Community Group") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; createErr = null },
                        label = { Text("Group name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it; createErr = null },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (createErr != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(createErr!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { createGroup() }, enabled = !creating) {
                    Text(if (creating) "Creating..." else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!creating) showCreateDialog = false }, enabled = !creating) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community", fontWeight = FontWeight.Bold) },
                actions = {
                    // Only show + on "Your Groups"
                    if (selectedTab == 0) {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Create group")
                        }
                    }
                }
            )
        },
        bottomBar = { BottomNavigationBar(navController) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Your Groups") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Join Groups") }
                )
            }

            val list = if (selectedTab == 0) yourGroups else joinGroups

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list) { g ->
                    val isJoined = joinedGroupIds.contains(g.id)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (g.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(g.description)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Members: ${g.memberCount}", style = MaterialTheme.typography.bodySmall)

                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (isJoined) {
                                        navController.navigate("community_group/${g.id}")
                                    } else {
                                        joinGroup(g.id) { ok ->
                                            if (ok) navController.navigate("community_group/${g.id}")
                                        }
                                    }
                                }) {
                                    Text(if (isJoined) "Open" else "Join")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}