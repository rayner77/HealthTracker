package com.inf2007.healthtracker.Screens

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.firestore.Query
import com.inf2007.healthtracker.utilities.BottomNavigationBar
import com.inf2007.healthtracker.utilities.ImageUtils
import java.io.ByteArrayOutputStream
import coil.compose.AsyncImage
import java.util.Date
import java.util.UUID

private data class PostUi(
    val id: String,
    val uid: String,
    val displayName: String,
    val text: String,
    val imageUrl: String?,
    val imagePath: String?,
    val createdAt: Date
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityGroupScreen(navController: NavController, groupId: String) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val db = remember { FirebaseFirestore.getInstance() }
    val storage = remember { FirebaseStorage.getInstance() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val imageUtils = remember { ImageUtils(context) }

    var groupName by remember { mutableStateOf("Community Group") }
    var isMember by remember { mutableStateOf(false) }

    var posts by remember { mutableStateOf<List<PostUi>>(emptyList()) }

    var message by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var posting by remember { mutableStateOf(false) }

    // For edit dialog
    var editPost by remember { mutableStateOf<PostUi?>(null) }
    var editText by remember { mutableStateOf("") }


    // Load group name
    LaunchedEffect(groupId) {
        db.collection("communityGroups").document(groupId)
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    groupName = snap.getString("name") ?: "Community Group"
                }
            }
    }

    // Membership status
    LaunchedEffect(uid, groupId) {
        if (uid.isBlank()) return@LaunchedEffect
        db.collection("communityGroups").document(groupId)
            .collection("members").document(uid)
            .addSnapshotListener { snap, _ ->
                isMember = snap != null && snap.exists()
            }
    }

    // Posts listener
    LaunchedEffect(groupId) {
        db.collection("communityGroups").document(groupId)
            .collection("posts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { d ->
                    PostUi(
                        id = d.id,
                        uid = d.getString("uid") ?: "",
                        displayName = d.getString("displayName") ?: "User",
                        text = d.getString("text") ?: "",
                        imageUrl = d.getString("imageUrl"),
                        createdAt = d.getTimestamp("createdAt")?.toDate() ?: Date(),
                        imagePath = d.getString("imagePath"),
                    )
                } ?: emptyList()
                posts = list
            }
    }

    // Camera launcher (same style as CaptureFoodScreen)
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            selectedBitmap = bitmap
        }

    // Gallery launcher (same style as CaptureFoodScreen)
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                try {
                    selectedBitmap = imageUtils.uriToBitmap(it)
                } catch (e: Exception) {
                    Log.e("CommunityGroup", "Error loading image from gallery", e)
                }
            }
        }

    fun joinGroup() {
        if (uid.isBlank()) return
        val groupRef = db.collection("communityGroups").document(groupId)
        val memberRef = groupRef.collection("members").document(uid)

        db.runTransaction { tx ->
            val memberSnap = tx.get(memberRef)
            if (!memberSnap.exists()) {
                tx.set(memberRef, mapOf("uid" to uid, "joinedAt" to FieldValue.serverTimestamp()))
                tx.update(groupRef, "memberCount", FieldValue.increment(1))
            }
        }
    }

    fun uploadBitmap(
        bitmap: Bitmap,
        groupId: String,
        onDone: (imageUrl: String?, imagePath: String?) -> Unit
    ) {
        val bytes = ByteArrayOutputStream().use { baos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            baos.toByteArray()
        }

        val imagePath = "communityPosts/$groupId/${UUID.randomUUID()}.jpg"
        val ref = storage.reference.child(imagePath)

        ref.putBytes(bytes)
            .addOnSuccessListener {
                ref.downloadUrl
                    .addOnSuccessListener { uri ->
                        onDone(uri.toString(), imagePath)
                    }
                    .addOnFailureListener {
                        onDone(null, null)
                    }
            }
            .addOnFailureListener {
                onDone(null, null)
            }
    }

    fun createPost() {
        if (!isMember || uid.isBlank()) return

        val text = message.trim()
        val bmp = selectedBitmap

        // require either text or image
        if (text.isEmpty() && bmp == null) return

        posting = true

        fun writePost(imageUrl: String?, imagePath: String?) {
            val displayName = FirebaseAuth.getInstance().currentUser?.email?.substringBefore("@") ?: "User"

            val data = mutableMapOf<String, Any>(
                "uid" to uid,
                "displayName" to displayName,
                "text" to text,
                "createdAt" to FieldValue.serverTimestamp()
            )

            if (!imageUrl.isNullOrBlank() && !imagePath.isNullOrBlank()) {
                data["imageUrl"] = imageUrl
                data["imagePath"] = imagePath
            }

            db.collection("communityGroups").document(groupId)
                .collection("posts")
                .add(data)
                .addOnSuccessListener {
                    posting = false
                    message = ""
                    selectedBitmap = null
                }
                .addOnFailureListener { e ->
                    posting = false
                    Log.e("CommunityGroup", "Failed to create post", e)
                }
        }

        // If there is an image, upload first then write post
        if (bmp != null) {
            uploadBitmap(bmp, groupId) { url, path ->
                // If upload fails, still allow a text-only post (or you can block it)
                if (url == null || path == null) {
                    writePost(null, null)
                } else {
                    writePost(url, path)
                }
            }
        } else {
            writePost(null, null)
        }
    }

    fun deletePost(p: PostUi) {
        if (p.uid != uid) return

        val postRef = db.collection("communityGroups").document(groupId)
            .collection("posts").document(p.id)

        val path = p.imagePath

        if (!path.isNullOrBlank()) {
            storage.reference.child(path)
                .delete()
                .addOnCompleteListener {
                    // Regardless of image delete success, delete the post doc
                    postRef.delete()
                }
        } else {
            postRef.delete()
        }
    }

    fun saveEdit() {
        val p = editPost ?: return
        if (p.uid != uid) return
        val newText = editText.trim()
        db.collection("communityGroups").document(groupId)
            .collection("posts").document(p.id)
            .update(
                mapOf(
                    "text" to newText,
                    "editedAt" to FieldValue.serverTimestamp()
                )
            )
        editPost = null
        editText = ""
    }

    if (editPost != null) {
        AlertDialog(
            onDismissRequest = { editPost = null },
            title = { Text("Edit post") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { saveEdit() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editPost = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(groupName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp)
        ) {
            if (!isMember) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Join this group to post.", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { joinGroup() }) { Text("Join Group") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Write a post...") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !posting
                )

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { cameraLauncher.launch(null) },
                        enabled = !posting
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Camera")
                    }

                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        enabled = !posting
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gallery")
                    }

                    Button(
                        onClick = { createPost() },
                        enabled = !posting && (message.trim().isNotEmpty() || selectedBitmap != null)
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (posting) "Posting..." else "Post")
                    }
                }

                if (selectedBitmap != null) {
                    Spacer(Modifier.height(10.dp))
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("Attached photo", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Image(
                                bitmap = selectedBitmap!!.asImageBitmap(),
                                contentDescription = "Attached",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { selectedBitmap = null }) {
                                Text("Remove photo")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            Text("Posts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(posts) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(p.displayName, fontWeight = FontWeight.SemiBold)
                                if (p.uid == uid) {
                                    var menuOpen by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menuOpen = true }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                                        }
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Edit") },
                                                onClick = {
                                                    menuOpen = false
                                                    editPost = p
                                                    editText = p.text
                                                },
                                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete") },
                                                onClick = {
                                                    menuOpen = false
                                                    deletePost(p)
                                                },
                                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            if (p.text.isNotBlank()) Text(p.text)

                            // Image display (simple: show URL text for now if you don’t have Coil here)
                            if (!p.imageUrl.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                AsyncImage(
                                    model = p.imageUrl,
                                    contentDescription = "Post image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(p.createdAt.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
