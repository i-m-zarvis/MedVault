package com.medvault.app

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.work.WorkInfo
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

private val AppBg = Color(0xFF0B1020)
private val AppPanel = Color(0xFF151E30)
private val AppAccent = Color(0xFF78AFFF)
private val AppMuted = Color(0xFF9EABC0)

private const val DRIVE_SCOPE =
    "https://www.googleapis.com/auth/drive.readonly"

private const val DRIVE_APPDATA_SCOPE =
    "https://www.googleapis.com/auth/drive.appdata"

private const val AUTH_REQUEST_CODE = 7001
private const val FOLDER_MIME =
    "application/vnd.google-apps.folder"

private const val DOWNLOAD_TAG_PREFIX = "medvault_download_"

class MainActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var historySync: WatchHistorySync
    private lateinit var downloadRepository: DownloadRepository

    private var accessToken by mutableStateOf<String?>(null)
    private var connected by mutableStateOf(false)
    private var authBusy by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var status by mutableStateOf("Checking Google Drive access…")

    private val driveItems = mutableStateListOf<DriveItem>()
    private val folderStack = mutableStateListOf<FolderLocation>()
    private val watchRecords = mutableStateMapOf<String, WatchRecord>()

    private var folderId by mutableStateOf("root")
    private var folderName by mutableStateOf("My Drive")
    private var selectedVideo by mutableStateOf<DriveItem?>(null)
    private var screen by mutableStateOf("home")
    private var accountMenu by mutableStateOf(false)
    private var pipMode by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)

    private var player: ExoPlayer? = null
    private var requestSerial = 0
    private var pendingDownload by mutableStateOf<DriveItem?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val file = pendingDownload
            pendingDownload = null

            if (file != null) {
                // Android may deny notification permission.
                // WorkManager can still run its foreground worker.
                enqueueDownload(file)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        historySync = WatchHistorySync(this)
        downloadRepository = DownloadRepository(this)

        io.execute {
            try {
                val saved = historySync.loadLocal()

                runOnUiThread {
                    watchRecords.clear()
                    watchRecords.putAll(saved)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    status = "Could not read local watch history."
                }
            }
        }

        setContent {
            val downloadWorkItems by
                downloadRepository.observeAll()
                    .observeAsState(initial = emptyList())

            var downloadedLectures by remember {
                mutableStateOf(DownloadStore.getAll(this@MainActivity))
            }
            var pausedDownloadIds by remember {
                mutableStateOf(DownloadRepository.pausedIds(this@MainActivity))
            }
            var showBrandSplash by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1900)
                showBrandSplash = false
            }

            LaunchedEffect(downloadWorkItems) {
                downloadedLectures =
                    DownloadStore.getAll(this@MainActivity)
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = AppBg,
                    surface = AppPanel,
                    primary = AppAccent,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Box(Modifier.fillMaxSize()) {
                MedVaultRoot(
                    connected = connected,
                    authBusy = authBusy,
                    loading = loading,
                    status = status,
                    items = driveItems.toList(),
                    folderName = folderName,
                    canGoBack = folderStack.isNotEmpty(),
                    selectedVideo = selectedVideo,
                    screen = screen,
                    accountMenu = accountMenu,
                    pipMode = pipMode,
                    fullscreen = fullscreen,
                    player = player,
                    history = watchRecords.values.sortedByDescending {
                        it.updatedAt
                    },
                    downloadWorkItems = downloadWorkItems,
                    downloadedLectures = downloadedLectures,
                    pausedDownloadIds = pausedDownloadIds,

                    onAccountMenu = { accountMenu = it },
                    onConnect = { authorizeDrive() },
                    onDisconnect = { disconnectDrive() },

                    onBrowse = {
                        screen = "drive"
                        browseDrive()
                    },

                    onHome = {
                        saveCurrentProgress()
                        leavePlayer()
                        screen = "home"
                    },

                    onFolderBack = { goBackFolder() },
                    onRefresh = { loadFolder(folderId) },
                    onFolder = { openFolder(it) },
                    onFile = { openFile(it) },
                    onDownload = { requestDownload(it) },

                    onHistory = { screen = "history" },
                    onContinue = { screen = "continue" },
                    onDownloads = { screen = "downloads" },

                    onHistoryFile = { record ->
                        openFile(
                            DriveItem(
                                record.fileId,
                                record.name,
                                "video/mp4"
                            )
                        )
                    },

                    onFullscreen = { toggleFullscreen() },
                    onPip = { enterPip() },

                    onProgress = { position, duration ->
                        saveProgress(position, duration)
                    },

                    onPauseDownload = { work ->
                        val fileId = work.tags.firstOrNull { it.startsWith(DOWNLOAD_TAG_PREFIX) }
                            ?.removePrefix(DOWNLOAD_TAG_PREFIX)
                        if (!fileId.isNullOrBlank()) {
                            downloadRepository.pause(work.id, fileId)
                            pausedDownloadIds = DownloadRepository.pausedIds(this@MainActivity)
                        }
                    },

                    onResumeDownload = { work ->
                        retryDownload(work)
                        pausedDownloadIds = DownloadRepository.pausedIds(this@MainActivity)
                    },

                    onCancelDownload = { work ->
                        val fileId = work.tags.firstOrNull { it.startsWith(DOWNLOAD_TAG_PREFIX) }
                            ?.removePrefix(DOWNLOAD_TAG_PREFIX)
                        if (!fileId.isNullOrBlank()) {
                            downloadRepository.cancel(work.id, fileId)
                            pausedDownloadIds = DownloadRepository.pausedIds(this@MainActivity)
                        }
                    },

                    onRetryDownload = { work ->
                        retryDownload(work)
                        pausedDownloadIds = DownloadRepository.pausedIds(this@MainActivity)
                    },

                    onPlayOffline = { lecture ->
                        startOfflineVideo(lecture)
                    },

                    onDeleteOffline = { lecture ->
                        deleteOfflineDownload(lecture)
                        downloadedLectures =
                            DownloadStore.getAll(this@MainActivity)
                    }
                )
                AnimatedVisibility(
                    visible = showBrandSplash,
                    enter = fadeIn() + scaleIn(initialScale = 0.96f),
                    exit = fadeOut()
                ) {
                    MedVaultBrandSplash()
                }
                }
            }
        }

        authorizeDrive()
    }

    // ---------------------------------------------------------
    // GOOGLE DRIVE AUTHORIZATION
    // ---------------------------------------------------------

    private fun authorizeDrive() {
        if (authBusy) return

        authBusy = true
        status = "Checking Google Drive access…"

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(DRIVE_SCOPE),
                    Scope(DRIVE_APPDATA_SCOPE)
                )
            )
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                authBusy = false

                if (result.hasResolution()) {
                    try {
                        val pending = result.pendingIntent
                            ?: throw IllegalStateException(
                                "Google authorization prompt unavailable"
                            )

                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(
                            pending.intentSender,
                            AUTH_REQUEST_CODE,
                            null,
                            0,
                            0,
                            0
                        )
                    } catch (e: Exception) {
                        connected = false
                        status = "Authorization error: ${e.message}"
                    }
                } else {
                    acceptAccessToken(result.accessToken)
                }
            }
            .addOnFailureListener {
                authBusy = false
                connected = false
                status =
                    "Google Drive authorization failed. Reconnect from account settings."
            }
    }

    @Deprecated("Google authorization resolution callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != AUTH_REQUEST_CODE) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                val result =
                    Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(data)

                acceptAccessToken(result.accessToken)
            } catch (e: Exception) {
                connected = false
                status = "Google authorization failed: ${e.message}"
            }
        } else {
            connected = false
            status = "Google authorization was cancelled."
        }
    }

    private fun acceptAccessToken(token: String?) {
        if (token.isNullOrBlank()) {
            connected = false
            status = "Google did not return an access token."
            return
        }

        accessToken = token
        connected = true

        try {
            DriveTokenVault.save(applicationContext, token)
            status = "Google Drive connected."
        } catch (e: Exception) {
            status =
                "Google Drive connected, but secure token storage failed: ${e.message}"
        }

        syncHistory(token)

        if (screen == "drive") {
            loadFolder(folderId)
        }
    }

    private fun disconnectDrive() {
        saveCurrentProgress()

        accessToken = null

        try {
            DriveTokenVault.clear(applicationContext)
        } catch (_: Exception) {
        }

        connected = false
        driveItems.clear()
        folderStack.clear()
        folderId = "root"
        folderName = "My Drive"

        leavePlayer()
        screen = "home"

        status =
            "Google Drive disconnected. Local downloads and watch history are retained."
    }

    // ---------------------------------------------------------
    // DOWNLOAD MANAGER
    // ---------------------------------------------------------

    private fun requestDownload(file: DriveItem) {
        if (!connected || accessToken.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Connect Google Drive before downloading.",
                Toast.LENGTH_LONG
            ).show()

            authorizeDrive()
            return
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = file

            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            enqueueDownload(file)
        }
    }

    private fun enqueueDownload(file: DriveItem) {
        val existing = DownloadStore.find(this, file.id)

        if (
            existing != null &&
            File(existing.localPath).isFile
        ) {
            Toast.makeText(
                this,
                "Already available offline.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            downloadRepository.enqueue(file)

            Toast.makeText(
                this,
                "Added to download queue: ${file.name}",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not start download: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun retryDownload(work: WorkInfo) {
        val fileId = work.tags
            .firstOrNull {
                it.startsWith(DOWNLOAD_TAG_PREFIX)
            }
            ?.removePrefix(DOWNLOAD_TAG_PREFIX)

        if (fileId.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Could not identify this download.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val name =
            work.progress.getString(
                DriveDownloadWorker.KEY_FILE_NAME
            )
                ?: work.outputData.getString("file_name")
                ?: "lecture_$fileId.mp4"

        val file = DriveItem(
            id = fileId,
            name = name,
            mimeType = "video/mp4"
        )

        if (accessToken.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Reconnect Google Drive before retrying.",
                Toast.LENGTH_LONG
            ).show()

            authorizeDrive()
            return
        }

        try {
            downloadRepository.resume(file)

            Toast.makeText(
                this,
                "Download queued again.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Retry failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deleteOfflineDownload(
        lecture: DownloadedLecture
    ) {
        val file = File(lecture.localPath)

        if (file.exists() && !file.delete()) {
            Toast.makeText(
                this,
                "Could not delete downloaded file.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        DownloadStore.delete(this, lecture.fileId)

        Toast.makeText(
            this,
            "Offline lecture deleted.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startOfflineVideo(
        lecture: DownloadedLecture
    ) {
        val file = File(lecture.localPath)

        if (!file.isFile) {
            DownloadStore.delete(this, lecture.fileId)

            Toast.makeText(
                this,
                "Downloaded file is missing.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val item = DriveItem(
            id = lecture.fileId,
            name = lecture.name,
            mimeType = "video/mp4"
        )

        startVideo(
            item = item,
            token = null,
            localPath = file.absolutePath
        )
    }

    // ---------------------------------------------------------
    // WATCH HISTORY
    // ---------------------------------------------------------

    private fun syncHistory(token: String) {
        val snapshot = watchRecords.toMap()

        io.execute {
            try {
                val merged = historySync.sync(token, snapshot)

                runOnUiThread {
                    watchRecords.clear()
                    watchRecords.putAll(merged)
                    status = "Watch history synced."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status = "History sync failed: ${e.message}"
                }
            }
        }
    }

    private fun saveProgress(
        position: Long,
        duration: Long
    ) {
        val file = selectedVideo ?: return
        if (duration <= 0L) return

        val completed =
            position >= (duration * 0.95f).toLong()

        val record = WatchRecord(
            fileId = file.id,
            name = file.name,
            positionMs = if (completed) {
                duration
            } else {
                position.coerceAtLeast(0L)
            },
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            completed = completed
        )

        watchRecords[file.id] = record

        val snapshot = watchRecords.toMap()

        io.execute {
            try {
                historySync.saveLocal(snapshot)
            } catch (_: Exception) {
            }
        }
    }

    private fun saveCurrentProgress() {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration

        if (duration > 0L) {
            saveProgress(
                currentPlayer.currentPosition,
                duration
            )
        }
    }

    // ---------------------------------------------------------
    // DRIVE BROWSER
    // ---------------------------------------------------------

    private fun browseDrive() {
        if (accessToken.isNullOrBlank()) {
            authorizeDrive()
        } else {
            loadFolder(folderId)
        }
    }

    private fun loadFolder(targetFolderId: String) {
        val token = accessToken

        if (token.isNullOrBlank()) {
            status = "Connect Google Drive from account settings."
            authorizeDrive()
            return
        }

        val serial = ++requestSerial

        loading = true
        status = "Loading $folderName…"
        driveItems.clear()

        io.execute {
            try {
                val found = mutableListOf<DriveItem>()
                var pageToken: String? = null

                do {
                    val query =
                        "'${targetFolderId.replace("'", "\\'")}' in parents and trashed=false"

                    val builder = Uri.parse(
                        "https://www.googleapis.com/drive/v3/files"
                    ).buildUpon()
                        .appendQueryParameter("q", query)
                        .appendQueryParameter(
                            "fields",
                            "nextPageToken,files(id,name,mimeType)"
                        )
                        .appendQueryParameter("pageSize", "100")
                        .appendQueryParameter("orderBy", "folder,name")

                    pageToken?.let {
                        builder.appendQueryParameter("pageToken", it)
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
                        connection.readTimeout = 20000

                        val code = connection.responseCode

                        if (code !in 200..299) {
                            val detail = connection.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: "HTTP $code"

                            if (code == 401) {
                                throw SecurityException(
                                    "Google access expired. Reconnect from account settings."
                                )
                            }

                            throw IllegalStateException(
                                "Drive request failed ($code): $detail"
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
                        for (index in 0 until files.length()) {
                            val file = files.getJSONObject(index)

                            found += DriveItem(
                                file.getString("id"),
                                file.getString("name"),
                                file.getString("mimeType")
                            )
                        }
                    }

                    pageToken = json.optString("nextPageToken")
                        .takeIf { it.isNotBlank() }

                } while (pageToken != null)

                runOnUiThread {
                    if (
                        serial == requestSerial &&
                        !isFinishing
                    ) {
                        driveItems.clear()
                        driveItems.addAll(found)
                        loading = false
                        status = "${found.size} items"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (
                        serial == requestSerial &&
                        !isFinishing
                    ) {
                        loading = false
                        status =
                            error.message ?: "Could not load Drive."

                        if (error is SecurityException) {
                            connected = false
                            accessToken = null

                            try {
                                DriveTokenVault.clear(applicationContext)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openFolder(item: DriveItem) {
        if (loading || item.mimeType != FOLDER_MIME) return

        folderStack.add(
            FolderLocation(folderId, folderName)
        )

        folderId = item.id
        folderName = item.name

        loadFolder(item.id)
    }

    private fun goBackFolder() {
        if (folderStack.isEmpty()) {
            screen = "home"
            return
        }

        val previous =
            folderStack.removeAt(folderStack.lastIndex)

        folderId = previous.id
        folderName = previous.name

        loadFolder(previous.id)
    }

    private fun openFile(item: DriveItem) {
        val supported =
            item.mimeType.startsWith("video/") ||
                listOf(".mp4", ".mkv", ".webm", ".mov")
                    .any { item.name.endsWith(it, true) }

        if (!supported) {
            Toast.makeText(
                this,
                "This file type is not supported yet.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val token = accessToken

        if (token.isNullOrBlank()) {
            authorizeDrive()

            Toast.makeText(
                this,
                "Reconnect Google Drive, then try again.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        startVideo(item, token)
    }

    // ---------------------------------------------------------
    // VIDEO PLAYER: ONLINE AND OFFLINE
    // ---------------------------------------------------------

    private fun startVideo(
        item: DriveItem,
        token: String?,
        localPath: String? = null
    ) {
        saveCurrentProgress()
        player?.release()

        val resumePosition =
            watchRecords[item.id]
                ?.takeIf { !it.completed }
                ?.positionMs ?: 0L

        val newPlayer = if (localPath != null) {
            // Default player data source supports local file URIs.
            ExoPlayer.Builder(this).build()
        } else {
            val validToken = token
                ?: throw IllegalStateException(
                    "Google Drive token is unavailable."
                )

            val httpFactory =
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(
                        mapOf(
                            "Authorization" to "Bearer $validToken"
                        )
                    )
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(30000)
                    .setAllowCrossProtocolRedirects(false)

            val dataSourceFactory =
                DefaultDataSource.Factory(
                    this,
                    httpFactory
                )

            ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(dataSourceFactory)
                )
                .build()
        }

        player = newPlayer
        selectedVideo = item
        screen = "player"

        var resumeApplied = false

        newPlayer.addListener(
            object : Player.Listener {

                override fun onPlaybackStateChanged(state: Int) {
                    if (
                        state == Player.STATE_READY &&
                        !resumeApplied &&
                        resumePosition > 0L
                    ) {
                        newPlayer.seekTo(resumePosition)
                        resumeApplied = true
                    }
                }

                override fun onPlayerError(
                    error: androidx.media3.common.PlaybackException
                ) {
                    runOnUiThread {
                        status = "Playback error: ${error.message}"

                        Toast.makeText(
                            this@MainActivity,
                            "Playback failed: ${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )

        val mediaUri = if (localPath != null) {
            Uri.fromFile(File(localPath))
        } else {
            Uri.parse(
                "https://www.googleapis.com/drive/v3/files/" +
                    "${Uri.encode(item.id)}?alt=media"
            )
        }

        newPlayer.setMediaItem(
            MediaItem.fromUri(mediaUri)
        )

        newPlayer.prepare()
        newPlayer.playWhenReady = true
    }

    private fun leavePlayer() {
        saveCurrentProgress()

        selectedVideo = null

        player?.release()
        player = null

        if (fullscreen) {
            fullscreen = false
            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        accessToken?.let {
            syncHistory(it)
        }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen

        requestedOrientation =
            if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
    }

    private fun enterPip() {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            selectedVideo == null
        ) return

        try {
            val params =
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()

            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Picture-in-Picture is unavailable.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (
            selectedVideo != null &&
            player?.isPlaying == true
        ) {
            enterPip()
        }
    }

    override fun onPause() {
        saveCurrentProgress()
        super.onPause()
    }

    override fun onStop() {
        saveCurrentProgress()
        accessToken?.let { syncHistory(it) }
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(
            isInPictureInPictureMode,
            newConfig
        )

        pipMode = isInPictureInPictureMode
    }

    override fun onDestroy() {
        saveCurrentProgress()

        requestSerial++

        player?.release()
        player = null

        io.shutdown()

        super.onDestroy()
    }
}

// =============================================================
// ROOT NAVIGATION
// =============================================================

@Composable
private fun MedVaultRoot(
    connected: Boolean,
    authBusy: Boolean,
    loading: Boolean,
    status: String,
    items: List<DriveItem>,
    folderName: String,
    canGoBack: Boolean,
    selectedVideo: DriveItem?,
    screen: String,
    accountMenu: Boolean,
    pipMode: Boolean,
    fullscreen: Boolean,
    player: ExoPlayer?,
    history: List<WatchRecord>,
    downloadWorkItems: List<WorkInfo>,
    downloadedLectures: List<DownloadedLecture>,
    pausedDownloadIds: Set<String>,
    onAccountMenu: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBrowse: () -> Unit,
    onHome: () -> Unit,
    onFolderBack: () -> Unit,
    onRefresh: () -> Unit,
    onFolder: (DriveItem) -> Unit,
    onFile: (DriveItem) -> Unit,
    onDownload: (DriveItem) -> Unit,
    onHistory: () -> Unit,
    onContinue: () -> Unit,
    onDownloads: () -> Unit,
    onHistoryFile: (WatchRecord) -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onProgress: (Long, Long) -> Unit,
    onPauseDownload: (WorkInfo) -> Unit,
    onResumeDownload: (WorkInfo) -> Unit,
    onCancelDownload: (WorkInfo) -> Unit,
    onRetryDownload: (WorkInfo) -> Unit,
    onPlayOffline: (DownloadedLecture) -> Unit,
    onDeleteOffline: (DownloadedLecture) -> Unit
) {
    BackHandler(
        enabled = screen != "home" && !pipMode
    ) {
        when (screen) {
            "player" -> onHome()
            "drive" -> onFolderBack()
            "history", "continue", "downloads" -> onHome()
        }
    }

    Surface(
        Modifier.fillMaxSize(),
        color = AppBg
    ) {
        when {
            screen == "player" &&
                selectedVideo != null &&
                player != null -> {

                VideoScreen(
                    selectedVideo,
                    player,
                    fullscreen,
                    pipMode,
                    onHome,
                    onFullscreen,
                    onPip,
                    onProgress
                )
            }

            screen == "drive" -> {
                DriveBrowser(
                    items = items,
                    folderName = folderName,
                    canGoBack = canGoBack,
                    loading = loading,
                    status = status,
                    onBack = onFolderBack,
                    onRefresh = onRefresh,
                    onFolder = onFolder,
                    onFile = onFile,
                    onDownload = onDownload,
                    onHome = onHome
                )
            }

            screen == "history" || screen == "continue" -> {
                HistoryScreen(
                    title = if (screen == "history") {
                        "Watch history"
                    } else {
                        "Continue watching"
                    },
                    records = if (screen == "continue") {
                        history.filter {
                            !it.completed && it.positionMs > 0L
                        }
                    } else {
                        history
                    },
                    onBack = onHome,
                    onOpen = onHistoryFile
                )
            }

            screen == "downloads" -> {
                DownloadManagerScreen(
                    workItems = downloadWorkItems,
                    downloadedLectures = downloadedLectures,
                    pausedIds = pausedDownloadIds,
                    onBack = onHome,
                    onPause = onPauseDownload,
                    onResume = onResumeDownload,
                    onCancel = onCancelDownload,
                    onRetry = onRetryDownload,
                    onPlayOffline = onPlayOffline,
                    onDeleteOffline = onDeleteOffline
                )
            }

            else -> {
                Dashboard(
                    connected = connected,
                    authBusy = authBusy,
                    status = status,
                    menu = accountMenu,
                    onMenu = onAccountMenu,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onBrowse = onBrowse,
                    onHistory = onHistory,
                    onContinue = onContinue,
                    onDownloads = onDownloads,
                    downloadCount = downloadedLectures.size,
                    activeDownloadCount = downloadWorkItems.count {
                        it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                )
            }
        }
    }
}

// =============================================================
// DASHBOARD
// =============================================================

@Composable
private fun Dashboard(
    connected: Boolean,
    authBusy: Boolean,
    status: String,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBrowse: () -> Unit,
    onHistory: () -> Unit,
    onContinue: () -> Unit,
    onDownloads: () -> Unit,
    downloadCount: Int,
    activeDownloadCount: Int
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "MEDVAULT",
                    color = AppAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Text(
                    "Your study library",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "MedVault by Aftab",
                    color = AppMuted,
                    fontSize = 12.sp
                )
            }

            Box {
                IconButton(
                    onClick = { onMenu(true) }
                ) {
                    Icon(
                        Icons.Default.AccountCircle,
                        "Account settings",
                        tint = AppAccent,
                        modifier = Modifier.size(30.dp)
                    )
                }

                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { onMenu(false) }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (connected) {
                                    "Google Drive connected"
                                } else {
                                    "Google Drive not connected"
                                }
                            )
                        },
                        onClick = { onMenu(false) },
                        leadingIcon = {
                            Icon(Icons.Default.Cloud, null)
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                if (connected) {
                                    "Reconnect Google Drive"
                                } else {
                                    "Connect Google Drive"
                                }
                            )
                        },
                        onClick = {
                            onMenu(false)
                            onConnect()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Sync, null)
                        }
                    )

                    if (connected) {
                        DropdownMenuItem(
                            text = {
                                Text("Disconnect Google account")
                            },
                            onClick = {
                                onMenu(false)
                                onDisconnect()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Logout, null)
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = AppPanel
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "YOUR NEET PG LIBRARY",
                    color = AppAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Your lectures, in one place",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    when {
                        connected -> status
                        authBusy -> "Checking Google account access…"
                        else -> "Connect Google Drive to browse lectures."
                    },
                    color = AppMuted,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = onBrowse,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Browse subjects")
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            "LIBRARY",
            color = AppMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )

        Spacer(Modifier.height(10.dp))

        DashboardTile(
            Icons.Default.Folder,
            "Browse subjects",
            "Explore folders and lectures",
            onBrowse
        )

        DashboardTile(
            Icons.Default.PlayCircle,
            "Continue watching",
            "Resume unfinished lectures",
            onContinue
        )

        DashboardTile(
            Icons.Default.History,
            "Watch history",
            "Recently played lectures",
            onHistory
        )

        DashboardTile(
            Icons.Default.CloudDownload,
            "Download Manager",
            "$activeDownloadCount active · $downloadCount offline",
            onDownloads
        )
    }
}

@Composable
private fun DashboardTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = AppPanel
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                tint = AppAccent,
                modifier = Modifier.size(25.dp)
            )

            Column(
                Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    subtitle,
                    color = AppMuted,
                    fontSize = 12.sp
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = AppMuted
            )
        }
    }
}

// =============================================================
// HISTORY SCREEN
// =============================================================

@Composable
private fun HistoryScreen(
    title: String,
    records: List<WatchRecord>,
    onBack: () -> Unit,
    onOpen: (WatchRecord) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    "Back",
                    tint = AppAccent
                )
            }

            Text(
                title,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (records.isEmpty()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No lectures here yet.",
                    color = AppMuted
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(
                    records,
                    key = { it.fileId }
                ) { record ->

                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(record) },
                        colors = CardDefaults.cardColors(
                            containerColor = AppPanel
                        )
                    ) {
                        Column(Modifier.padding(15.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (record.completed) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.PlayCircle
                                    },
                                    null,
                                    tint = AppAccent
                                )

                                Text(
                                    record.name,
                                    Modifier
                                        .padding(start = 10.dp)
                                        .weight(1f),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            val progress =
                                if (record.durationMs > 0L) {
                                    (
                                        record.positionMs.toFloat() /
                                            record.durationMs.toFloat()
                                        ).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = AppAccent
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                if (record.completed) {
                                    "Completed"
                                } else {
                                    "${formatTime(record.positionMs)} / ${formatTime(record.durationMs)}"
                                },
                                color = AppMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================
// DRIVE BROWSER
// =============================================================

@Composable
private fun DriveBrowser(
    items: List<DriveItem>,
    folderName: String,
    canGoBack: Boolean,
    loading: Boolean,
    status: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFolder: (DriveItem) -> Unit,
    onFile: (DriveItem) -> Unit,
    onDownload: (DriveItem) -> Unit,
    onHome: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                horizontal = 16.dp,
                vertical = 12.dp
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onHome) {
                Icon(
                    Icons.Default.Home,
                    "Dashboard",
                    tint = AppAccent
                )
            }

            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        "Parent folder",
                        tint = AppAccent
                    )
                }
            }

            Text(
                folderName,
                Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            IconButton(
                onClick = onRefresh,
                enabled = !loading
            ) {
                Icon(
                    Icons.Default.Refresh,
                    "Refresh",
                    tint = AppAccent
                )
            }
        }

        Text(
            status,
            color = AppMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(
                start = 8.dp,
                bottom = 8.dp
            )
        )

        if (loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = AppAccent
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items,
                key = { it.id }
            ) { item ->

                val folder = item.mimeType == FOLDER_MIME

                val supportedVideo =
                    !folder && (
                        item.mimeType.startsWith("video/") ||
                            listOf(
                                ".mp4",
                                ".mkv",
                                ".webm",
                                ".mov"
                            ).any {
                                item.name.endsWith(it, true)
                            }
                        )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppPanel
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 15.dp,
                                end = 6.dp,
                                top = 12.dp,
                                bottom = 12.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clickable(enabled = !loading) {
                                    if (folder) {
                                        onFolder(item)
                                    } else {
                                        onFile(item)
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (folder) {
                                    Icons.Default.Folder
                                } else {
                                    Icons.Default.VideoFile
                                },
                                null,
                                tint = AppAccent
                            )

                            Text(
                                item.name,
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = AppMuted
                            )
                        }

                        if (supportedVideo) {
                            IconButton(
                                onClick = {
                                    onDownload(item)
                                },
                                enabled = !loading
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "Download lecture",
                                    tint = AppAccent
                                )
                            }
                        }
                    }
                }
            }

            if (!loading && items.isEmpty()) {
                item {
                    Text(
                        "No items found. $status",
                        color = AppMuted,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

// =============================================================
// VIDEO PLAYER
// =============================================================

@Composable
private fun VideoScreen(
    file: DriveItem,
    player: ExoPlayer,
    fullscreen: Boolean,
    pipMode: Boolean,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onProgress: (Long, Long) -> Unit
) {
    var playing by remember {
        mutableStateOf(player.isPlaying)
    }

    var position by remember {
        mutableLongStateOf(0L)
    }

    var duration by remember {
        mutableLongStateOf(0L)
    }

    var speed by remember {
        mutableFloatStateOf(1f)
    }

    var controlsVisible by remember {
        mutableStateOf(true)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration =
                    player.duration.takeIf { it > 0 } ?: 0L
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player) {
        var lastSaved = 0L

        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)

            duration =
                player.duration.takeIf { it > 0 } ?: 0L

            if (
                duration > 0L &&
                position > 0L &&
                System.currentTimeMillis() - lastSaved >= 5000L
            ) {
                onProgress(position, duration)
                lastSaved = System.currentTimeMillis()
            }

            kotlinx.coroutines.delay(500)
        }
    }

    if (pipMode) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(
                        PlayerView.SHOW_BUFFERING_ALWAYS
                    )
                }
            },
            update = {
                it.player = player
            },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    if (fullscreen) Color.Black else AppBg
                )
        ) {
            if (!fullscreen) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.ArrowBack,
                            "Back",
                            tint = AppAccent
                        )
                    }

                    Text(
                        file.name,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(onClick = onPip) {
                        Icon(
                            Icons.Default.PictureInPictureAlt,
                            "Picture in picture",
                            tint = AppAccent
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier.aspectRatio(16f / 9f)
                        }
                    )
                    .background(Color.Black)
                    .clickable {
                        controlsVisible = !controlsVisible
                    }
            ) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = player
                            useController = false
                            setShowBuffering(
                                PlayerView.SHOW_BUFFERING_ALWAYS
                            )
                        }
                    },
                    update = {
                        it.player = player
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (controlsVisible) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                formatTime(position),
                                color = Color.White,
                                fontSize = 11.sp
                            )

                            Slider(
                                value = if (duration > 0) {
                                    position.toFloat()
                                        .coerceIn(0f, duration.toFloat())
                                } else {
                                    0f
                                },
                                onValueChange = {
                                    position = it.toLong()
                                },
                                onValueChangeFinished = {
                                    player.seekTo(position)
                                },
                                valueRange = 0f..duration
                                    .coerceAtLeast(1L)
                                    .toFloat(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = AppAccent,
                                    activeTrackColor = AppAccent,
                                    inactiveTrackColor = Color.Gray
                                )
                            )

                            Text(
                                formatTime(duration),
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    player.seekTo(
                                        (
                                            player.currentPosition -
                                                10000L
                                            ).coerceAtLeast(0L)
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Replay10,
                                    "Back 10 seconds",
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (player.isPlaying) {
                                        player.pause()
                                    } else {
                                        player.play()
                                    }
                                }
                            ) {
                                Icon(
                                    if (playing) {
                                        Icons.Default.PauseCircleFilled
                                    } else {
                                        Icons.Default.PlayCircleFilled
                                    },
                                    if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    player.seekTo(
                                        player.currentPosition + 10000L
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Forward10,
                                    "Forward 10 seconds",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = onPip) {
                                Icon(
                                    Icons.Default.PictureInPictureAlt,
                                    "Picture in picture",
                                    tint = Color.White
                                )
                            }

                            IconButton(onClick = onFullscreen) {
                                Icon(
                                    if (fullscreen) {
                                        Icons.Default.FullscreenExit
                                    } else {
                                        Icons.Default.Fullscreen
                                    },
                                    "Toggle fullscreen",
                                    tint = Color.White
                                )
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SPEED",
                                color = Color.LightGray,
                                fontSize = 10.sp
                            )

                            Spacer(Modifier.width(8.dp))

                            listOf(
                                0.5f,
                                0.75f,
                                1f,
                                1.25f,
                                1.5f,
                                2f
                            ).forEach { rate ->

                                TextButton(
                                    onClick = {
                                        speed = rate
                                        player.setPlaybackSpeed(rate)
                                    },
                                    contentPadding = PaddingValues(
                                        horizontal = 7.dp,
                                        vertical = 0.dp
                                    )
                                ) {
                                    Text(
                                        "${rate}x",
                                        color = if (speed == rate) {
                                            AppAccent
                                        } else {
                                            Color.White
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (fullscreen) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "Close player",
                            tint = Color.White
                        )
                    }
                }
            }

            if (!fullscreen) {
                Spacer(Modifier.height(14.dp))

                Text(
                    "NOW PLAYING",
                    color = AppAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Text(
                    file.name,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 5.dp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    "Streaming from Google Drive or local storage",
                    color = AppMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

// =============================================================
// HELPERS
// =============================================================

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L

    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(
            hours,
            minutes,
            remainder
        )
    } else {
        "%02d:%02d".format(
            minutes,
            remainder
        )
    }
}


@Composable
private fun MedVaultBrandSplash() {
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse = !pulse
            kotlinx.coroutines.delay(650)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.size(if (pulse) 92.dp else 84.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF172B49),
                shadowElevation = 18.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "MEDVAULT",
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "by Aftab",
                color = AppMuted,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp
            )
        }
    }
}
