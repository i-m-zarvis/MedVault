
package com.medvault.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val Background = Color(0xFF0B1020)
private val Panel = Color(0xFF151E30)
private val Accent = Color(0xFF78AFFF)
private val Muted = Color(0xFF9EABC0)

private const val DRIVE_SCOPE =
    "https://www.googleapis.com/auth/drive.readonly"

private const val AUTH_REQUEST = 7001

private const val FOLDER_MIME =
    "application/vnd.google-apps.folder"

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String
)

data class FolderLocation(
    val id: String,
    val name: String
)

class MainActivity : ComponentActivity() {

    private val executor =
        Executors.newSingleThreadExecutor()

    private var accessToken by mutableStateOf<String?>(null)

    private var connected by mutableStateOf(false)
    private var loading by mutableStateOf(false)

    private var status by mutableStateOf(
        "Connect Google Drive to begin."
    )

    private val driveItems =
        mutableStateListOf<DriveItem>()

    private val folderStack =
        mutableStateListOf<FolderLocation>()

    private var currentFolderId by mutableStateOf("root")
    private var currentFolderName by mutableStateOf("My Drive")

    private var selectedVideo by mutableStateOf<DriveItem?>(null)

    private var requestVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Background,
                    surface = Panel,
                    primary = Accent
                )
            ) {
                MedVaultHome(
                    connected = connected,
                    loading = loading,
                    status = status,
                    items = driveItems.toList(),
                    folderName = currentFolderName,
                    canGoBack = folderStack.isNotEmpty(),
                    selectedVideo = selectedVideo,
                    accessToken = accessToken.orEmpty(),
                    onConnect = { connectGoogleDrive() },
                    onBrowse = { browseDrive() },
                    onBack = { goBack() },
                    onFolderClick = { openFolder(it) },
                    onFileClick = { openFile(it) },
                    onCloseVideo = {
                        selectedVideo = null
                    }
                )
            }
        }
    }

    private fun connectGoogleDrive() {
        if (loading) return

        status = "Requesting Google authorization..."

        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(
                    listOf(Scope(DRIVE_SCOPE))
                )
                .build()

            Identity.getAuthorizationClient(this)
                .authorize(request)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        try {
                            val pendingIntent =
                                result.pendingIntent
                                    ?: throw IllegalStateException(
                                        "Authorization resolution unavailable"
                                    )

                            @Suppress("DEPRECATION")
                            startIntentSenderForResult(
                                pendingIntent.intentSender,
                                AUTH_REQUEST,
                                null,
                                0,
                                0,
                                0
                            )
                        } catch (e: Exception) {
                            status =
                                "Authorization error: ${e.message}"
                        }
                    } else {
                        handleAuthorization(result.accessToken)
                    }
                }
                .addOnFailureListener { error ->
                    status =
                        "Google authorization failed: ${error.message}"
                }
        } catch (e: Exception) {
            status = "Authorization error: ${e.message}"
        }
    }

    @Deprecated("Google authorization resolution")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != AUTH_REQUEST) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                val result =
                    Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(data)

                handleAuthorization(result.accessToken)
            } catch (e: Exception) {
                status = "Authorization failed: ${e.message}"
            }
        } else {
            status = "Google authorization cancelled."
        }
    }

    private fun handleAuthorization(token: String?) {
        if (token.isNullOrBlank()) {
            status = "Google did not return an access token."
            return
        }

        requestVersion++
        accessToken = token
        connected = true
        selectedVideo = null

        currentFolderId = "root"
        currentFolderName = "My Drive"

        folderStack.clear()
        driveItems.clear()

        status = "Google Drive connected."

        loadDriveFolder("root")
    }

    private fun browseDrive() {
        if (accessToken.isNullOrBlank()) {
            connectGoogleDrive()
        } else {
            loadDriveFolder(currentFolderId)
        }
    }

    private fun loadDriveFolder(folderId: String) {
        val token = accessToken ?: run {
            status = "Reconnect Google Drive."
            return
        }

        val version = ++requestVersion

        loading = true
        status = "Loading $currentFolderName..."
        driveItems.clear()

        executor.execute {
            try {
                val allFiles = mutableListOf<DriveItem>()
                var pageToken: String? = null

                do {
                    val query =
                        "'${folderId.replace("'", "\\'")}' in parents and trashed=false"

                    val builder = Uri.parse(
                        "https://www.googleapis.com/drive/v3/files"
                    ).buildUpon()
                        .appendQueryParameter("q", query)
                        .appendQueryParameter(
                            "fields",
                            "nextPageToken,files(id,name,mimeType,size)"
                        )
                        .appendQueryParameter("pageSize", "100")
                        .appendQueryParameter("orderBy", "folder,name")

                    if (pageToken != null) {
                        builder.appendQueryParameter(
                            "pageToken",
                            pageToken
                        )
                    }

                    val connection =
                        URL(builder.build().toString())
                            .openConnection() as HttpURLConnection

                    val response: String

                    try {
                        connection.requestMethod = "GET"
                        connection.setRequestProperty(
                            "Authorization",
                            "Bearer $token"
                        )
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000

                        val code = connection.responseCode

                        if (code !in 200..299) {
                            val error = connection.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: "HTTP $code"

                            if (code == 401) {
                                throw SecurityException(
                                    "Google session expired. Reconnect Drive."
                                )
                            }

                            throw IllegalStateException(
                                "Drive request failed ($code): $error"
                            )
                        }

                        response = connection.inputStream
                            .bufferedReader()
                            .use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }

                    val json = JSONObject(response)
                    val files = json.optJSONArray("files")

                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val item = files.getJSONObject(i)

                            allFiles.add(
                                DriveItem(
                                    id = item.getString("id"),
                                    name = item.getString("name"),
                                    mimeType = item.getString("mimeType")
                                )
                            )
                        }
                    }

                    pageToken = json.optString(
                        "nextPageToken"
                    ).takeIf { it.isNotBlank() }

                } while (pageToken != null)

                runOnUiThread {
                    if (version == requestVersion && !isFinishing) {
                        driveItems.clear()
                        driveItems.addAll(allFiles)
                        loading = false
                        status = "${allFiles.size} items found."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (version == requestVersion && !isFinishing) {
                        loading = false
                        status = when (e) {
                            is SecurityException -> e.message.orEmpty()
                            else -> "Drive error: ${e.message ?: "Unknown error"}"
                        }
                    }
                }
            }
        }
    }

    private fun openFolder(folder: DriveItem) {
        if (loading || folder.mimeType != FOLDER_MIME) return

        folderStack.add(
            FolderLocation(
                currentFolderId,
                currentFolderName
            )
        )

        currentFolderId = folder.id
        currentFolderName = folder.name

        loadDriveFolder(folder.id)
    }

    private fun goBack() {
        if (folderStack.isEmpty()) return

        val previous = folderStack.removeAt(
            folderStack.lastIndex
        )

        currentFolderId = previous.id
        currentFolderName = previous.name

        loadDriveFolder(previous.id)
    }

    private fun openFile(file: DriveItem) {
        if (loading) return

        val isVideo =
            file.mimeType.startsWith("video/") ||
            file.name.endsWith(".mp4", ignoreCase = true) ||
            file.name.endsWith(".mkv", ignoreCase = true) ||
            file.name.endsWith(".webm", ignoreCase = true)

        if (isVideo) {
            if (accessToken.isNullOrBlank()) {
                Toast.makeText(
                    this,
                    "Reconnect Google Drive first.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            selectedVideo = file
        } else {
            Toast.makeText(
                this,
                "This file type is not supported yet.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        requestVersion++
        executor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
fun MedVaultHome(
    connected: Boolean,
    loading: Boolean,
    status: String,
    items: List<DriveItem>,
    folderName: String,
    canGoBack: Boolean,
    selectedVideo: DriveItem?,
    accessToken: String,
    onConnect: () -> Unit,
    onBrowse: () -> Unit,
    onBack: () -> Unit,
    onFolderClick: (DriveItem) -> Unit,
    onFileClick: (DriveItem) -> Unit,
    onCloseVideo: () -> Unit
) {
    BackHandler(
        enabled = selectedVideo != null ||
            (connected && canGoBack)
    ) {
        if (selectedVideo != null) {
            onCloseVideo()
        } else {
            onBack()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        if (selectedVideo != null) {
            VideoPlayerScreen(
                file = selectedVideo,
                accessToken = accessToken,
                onClose = onCloseVideo
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "MEDVAULT",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "Your study library",
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = "Cloud",
                        tint = Accent
                    )
                }

                Spacer(Modifier.height(20.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "YOUR NEET PG LIBRARY",
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            if (connected)
                                "Google Drive connected."
                            else
                                "Pick up where you left off.",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            status,
                            color = Muted,
                            fontSize = 13.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = onConnect,
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (connected)
                                    "Reconnect Google Drive"
                                else
                                    "Connect Google Drive"
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (connected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (canGoBack) {
                            IconButton(
                                onClick = onBack,
                                enabled = !loading
                            ) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Accent
                                )
                            }
                        }

                        Text(
                            folderName,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        IconButton(
                            onClick = onBrowse,
                            enabled = !loading
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Accent
                            )
                        }
                    }

                    if (loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            val isFolder =
                                item.mimeType == FOLDER_MIME

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !loading) {
                                        if (isFolder) {
                                            onFolderClick(item)
                                        } else {
                                            onFileClick(item)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = Panel
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isFolder)
                                            Icons.Default.Folder
                                        else
                                            Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = Accent
                                    )

                                    Text(
                                        item.name,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 14.dp),
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Icon(
                                        if (isFolder)
                                            Icons.Default.ChevronRight
                                        else
                                            Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.Gray
                                    )
                                }
                            }
                        }

                        if (!loading && items.isEmpty()) {
                            item {
                                Text(
                                    if (status.startsWith("Drive error") ||
                                        status.contains("expired"))
                                        status
                                    else
                                        "This folder is empty.",
                                    color = Muted,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "YOUR LIBRARY",
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Feature(
                        Icons.Default.Folder,
                        "Browse subjects",
                        "Explore your Drive folders",
                        onBrowse
                    )

                    Spacer(Modifier.height(10.dp))

                    Feature(
                        Icons.Default.PlayCircle,
                        "Continue watching",
                        "Connect Drive to access lectures",
                        onBrowse
                    )

                    Spacer(Modifier.height(10.dp))

                    Feature(
                        Icons.Default.Search,
                        "Search lectures",
                        "Search will be added later",
                        onBrowse
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(
    file: DriveItem,
    accessToken: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var playerError by remember(file.id) {
        mutableStateOf<String?>(null)
    }

    var buffering by remember(file.id) {
        mutableStateOf(true)
    }

    val exoPlayer = remember(file.id, accessToken) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Authorization" to "Bearer $accessToken"
                )
            )
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(false)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(httpFactory)
            )
            .build()
            .apply {
                val encodedId = Uri.encode(file.id)
                val videoUrl =
                    "https://www.googleapis.com/drive/v3/files/$encodedId?alt=media"

                setMediaItem(MediaItem.fromUri(videoUrl))
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    buffering = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playerError = when {
                    error.cause?.message?.contains("401") == true ->
                        "Google authorization expired. Reconnect Drive."
                    error.cause?.message?.contains("403") == true ->
                        "Google Drive denied access to this video."
                    else ->
                        "Playback failed: ${error.message}"
                }
            }
        }

        exoPlayer.addListener(listener)

        try {
            exoPlayer.prepare()
        } catch (e: Exception) {
            buffering = false
            playerError = "Unable to prepare video: ${e.message}"
        }

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Accent
                )
            }

            Text(
                file.name,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(20.dp))

        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(
                        PlayerView.SHOW_BUFFERING_ALWAYS
                    )
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        )

        if (buffering && playerError == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Accent
            )
        }

        Spacer(Modifier.height(16.dp))

        if (playerError != null) {
            Text(
                playerError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    playerError = null
                    buffering = true
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            ) {
                Text("Retry playback")
            }
        }

        Text(
            "NOW PLAYING",
            color = Accent,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(6.dp))

        Text(
            file.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "Streaming from Google Drive",
            color = Muted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(20.dp))

        var selectedSpeed by remember {
            mutableFloatStateOf(1f)
        }

        Text(
            "Playback speed",
            color = Muted,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                FilterChip(
                    selected = selectedSpeed == speed,
                    onClick = {
                        selectedSpeed = speed
                        exoPlayer.setPlaybackSpeed(speed)
                    },
                    label = {
                        Text(
                            "${speed}x",
                            fontSize = 11.sp
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun Feature(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Panel
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Accent
            )

            Column(
                modifier = Modifier.padding(start = 14.dp)
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}
