package com.inf2007.healthtracker.Screens

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
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
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.inf2007.healthtracker.utilities.BottomNavigationBar
import com.inf2007.healthtracker.utilities.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class PostUi(
    val id: String,
    val uid: String,
    val displayName: String,
    val text: String,
    val imageUrl: String?,
    val imagePath: String?,
    val locationLat: Double?,
    val locationLng: Double?,
    val locationAcc: Double?,
    val locationName: String?,
    val createdAt: Date
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun CommunityGroupScreen(navController: NavController, groupId: String) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val db = remember { FirebaseFirestore.getInstance() }
    val storage = remember { FirebaseStorage.getInstance() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val imageUtils = remember { ImageUtils(context) }
    val scope = rememberCoroutineScope()

    // Location
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    var attachedLocation by remember { mutableStateOf<Location?>(null) }
    var attachedPlaceName by remember { mutableStateOf<String?>(null) }
    var pendingLocationRequest by remember { mutableStateOf(false) }
    var resolvingPlace by remember { mutableStateOf(false) }

    var groupName by remember { mutableStateOf("Community Group") }
    var isMember by remember { mutableStateOf(false) }

    var posts by remember { mutableStateOf<List<PostUi>>(emptyList()) }

    var message by remember { mutableStateOf("") }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var posting by remember { mutableStateOf(false) }

    // For edit dialog
    var editPost by remember { mutableStateOf<PostUi?>(null) }
    var editText by remember { mutableStateOf("") }

    fun openMaps(lat: Double, lng: Double, label: String?) {
        val q = if (!label.isNullOrBlank()) Uri.encode(label) else "$lat,$lng"
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($q)")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            // Prefer Google Maps if present; safe even if not installed
            setPackage("com.google.android.apps.maps")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // fallback to any map app / browser
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
            context.startActivity(fallback)
        }
    }

    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val results = geocoder.getFromLocation(lat, lng, 1)
            val a = results?.firstOrNull() ?: return@withContext null

            // Build a clean, short label
            val parts = listOfNotNull(
                a.featureName,
                a.thoroughfare,
                a.subLocality,
                a.locality,
                a.adminArea,
                a.countryName
            ).distinct()

            parts.joinToString(", ").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("CommunityGroup", "Reverse geocode failed", e)
            null
        }
    }

    fun fetchCurrentLocation(onDone: (Location?) -> Unit) {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { last ->
                    if (last != null) {
                        onDone(last)
                    } else {
                        val cts = CancellationTokenSource()
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            cts.token
                        )
                            .addOnSuccessListener { cur -> onDone(cur) }
                            .addOnFailureListener { onDone(null) }
                    }
                }
                .addOnFailureListener { onDone(null) }
        } catch (se: SecurityException) {
            onDone(null)
        }
    }

    fun attachLocation() {
        if (locationPermission.status.isGranted) {
            fetchCurrentLocation { loc ->
                attachedLocation = loc
                attachedPlaceName = null
                if (loc == null) {
                    Log.e("CommunityGroup", "Location is null (GPS off / emulator / no fix)")
                    return@fetchCurrentLocation
                }
                // Resolve human-readable name
                resolvingPlace = true
                scope.launch {
                    attachedPlaceName = reverseGeocode(loc.latitude, loc.longitude)
                    resolvingPlace = false
                }
            }
        } else {
            pendingLocationRequest = true
            locationPermission.launchPermissionRequest()
        }
    }

    // After permission is granted, run the pending location fetch
    LaunchedEffect(locationPermission.status.isGranted, pendingLocationRequest) {
        if (pendingLocationRequest && locationPermission.status.isGranted) {
            pendingLocationRequest = false
            attachLocation()
        }
    }

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
                        imagePath = d.getString("imagePath"),
                        locationLat = d.getDouble("locationLat"),
                        locationLng = d.getDouble("locationLng"),
                        locationAcc = d.getDouble("locationAcc"),
                        locationName = d.getString("locationName"),
                        createdAt = d.getTimestamp("createdAt")?.toDate() ?: Date()
                    )
                } ?: emptyList()
                posts = list
            }
    }

    // Camera launcher
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            selectedBitmap = bitmap
        }

    // Gallery launcher
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
        val loc = attachedLocation
        val locName = attachedPlaceName

        // require either text or image or location
        if (text.isEmpty() && bmp == null && loc == null) return

        posting = true

        fun writePost(imageUrl: String?, imagePath: String?) {
            val displayName =
                FirebaseAuth.getInstance().currentUser?.email?.substringBefore("@") ?: "User"

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

            if (loc != null) {
                data["locationLat"] = loc.latitude
                data["locationLng"] = loc.longitude
                if (loc.accuracy > 0f) data["locationAcc"] = loc.accuracy.toDouble()
                if (!locName.isNullOrBlank()) data["locationName"] = locName
            }

            db.collection("communityGroups").document(groupId)
                .collection("posts")
                .add(data)
                .addOnSuccessListener {
                    posting = false
                    message = ""
                    selectedBitmap = null
                    attachedLocation = null
                    attachedPlaceName = null
                }
                .addOnFailureListener { e ->
                    posting = false
                    Log.e("CommunityGroup", "Failed to create post", e)
                }
        }

        // If there is an image, upload first then write post
        if (bmp != null) {
            uploadBitmap(bmp, groupId) { url, path ->
                if (url == null || path == null) {
                    // still allow a text/location-only post
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
            confirmButton = { Button(onClick = { saveEdit() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editPost = null }) { Text("Cancel") } }
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

                // Row 1: Camera + Gallery
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                }

                Spacer(Modifier.height(8.dp))

                // Row 2 (below): Location + Post
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { attachLocation() },
                        enabled = !posting
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (resolvingPlace) "Locating..." else "Location")
                    }

                    Button(
                        onClick = { createPost() },
                        enabled = !posting && (
                                message.trim().isNotEmpty() ||
                                        selectedBitmap != null ||
                                        attachedLocation != null
                                )
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
                            TextButton(onClick = { selectedBitmap = null }) { Text("Remove photo") }
                        }
                    }
                }

                if (attachedLocation != null) {
                    Spacer(Modifier.height(10.dp))
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("Attached location", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))

                            val loc = attachedLocation!!
                            val name = attachedPlaceName
                            val acc = if (loc.accuracy > 0f) " (±${loc.accuracy.toInt()}m)" else ""

                            Text(
                                text = if (!name.isNullOrBlank()) "📍 $name$acc"
                                else "📍 ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}$acc"
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { openMaps(loc.latitude, loc.longitude, name) }) {
                                    Icon(Icons.Filled.Map, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("View on Maps")
                                }
                                TextButton(onClick = {
                                    attachedLocation = null
                                    attachedPlaceName = null
                                }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            Text(
                "Posts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
                                        DropdownMenu(
                                            expanded = menuOpen,
                                            onDismissRequest = { menuOpen = false }
                                        ) {
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

                            if (p.locationLat != null && p.locationLng != null) {
                                Spacer(Modifier.height(6.dp))

                                val label = p.locationName
                                val acc = p.locationAcc?.toInt()
                                val accText = if (acc != null) " (±${acc}m)" else ""

                                Text(
                                    text = if (!label.isNullOrBlank()) "📍 $label$accText"
                                    else "📍 ${"%.5f".format(p.locationLat)}, ${"%.5f".format(p.locationLng)}$accText",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    onClick = { openMaps(p.locationLat, p.locationLng, label) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Filled.Map, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("View on Maps")
                                }
                            }

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
