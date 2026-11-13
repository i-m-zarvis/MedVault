package com.medvault.app

import android.Manifest
import android.accounts.Account
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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import org.drinkless.tdlib.TdApi
import androidx.work.WorkInfo
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executors

private val AppBg = Color(0xFF080B12)
private val AppPanel = Color(0xFF171D30)
private val AppAccent = Color(0xFF78AFFF)
private val AppMuted = Color(0xFF9299AA)

private const val DRIVE_SCOPE =
    "https://www.googleapis.com/auth/drive.readonly"

private const val DRIVE_APPDATA_SCOPE =
    "https://www.googleapis.com/auth/drive.appdata"
private const val DRIVE_EMAIL_SCOPE =
    "https://www.googleapis.com/auth/userinfo.email"

private const val AUTH_REQUEST_CODE = 7001
private const val FOLDER_MIME =
    "application/vnd.google-apps.folder"

private const val DOWNLOAD_TAG_PREFIX = "medvault_download_"

class MainActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var historySync: WatchHistorySync
    private lateinit var downloadRepository: DownloadRepository

    private var accessToken by mutableStateOf<String?>(null)
    // Activity context is unavailable during property initialization.
    // Load account state in onCreate after Android attaches the Activity.
    private var activeAccountId by mutableStateOf("default")
    private var activeAccountEmail by mutableStateOf("Google Drive")
    private var currentSharedDriveId by mutableStateOf<String?>(null)
    private var connected by mutableStateOf(false)
    private var authBusy by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var status by mutableStateOf("Checking Google Drive access…")

    private val driveItems = mutableStateListOf<DriveItem>()
    private val folderStack = mutableStateListOf<FolderLocation>()
    private val watchRecords = mutableStateMapOf<String, WatchRecord>()

    private var folderId by mutableStateOf("root")
    private var folderName by mutableStateOf("My Drive")
    private var driveSearchActive by mutableStateOf(false)
    private var folderIdBeforeSearch = "root"
    private var folderNameBeforeSearch = "My Drive"
    private var selectedVideo by mutableStateOf<DriveItem?>(null)
    private var selectedTelegramLecture by mutableStateOf<TelegramLecture?>(null)
    private var selectedPdfFile by mutableStateOf<File?>(null)
    private var selectedPdfTitle by mutableStateOf("Document")
    private var pdfReturnScreen by mutableStateOf("qbank")
    private var screen by mutableStateOf("home")
    private var playerReturnScreen by mutableStateOf("videos")
    private var telegramProgressRevision by mutableIntStateOf(0)
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

    private fun applyImmersiveMode() {
        if (isInPictureInPictureMode) return

        // Keep immersive content below the camera punch-hole/notch.
        // System bars remain hidden; only the display cutout is respected.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // Do not reveal system bars from ordinary side/back-edge swipes.
            // Android's notification shade remains available from the top edge.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()

        activeAccountId = DriveTokenVault.getActiveAccountId(this) ?: "default"
        activeAccountEmail = DriveTokenVault.accounts(this)
            .firstOrNull { it.id == activeAccountId }?.email ?: "Google Drive"

        historySync = WatchHistorySync(this)
        downloadRepository = DownloadRepository(this)
        ContentFeedScheduler.schedule(this)

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
            val editionPrefs = remember { getSharedPreferences("medvault_app_settings", MODE_PRIVATE) }
            var activeEdition by remember {
                mutableStateOf(
                    when (editionPrefs.getString("active_edition", "M")) {
                        "P" -> MedVaultEdition.P
                        "U" -> MedVaultEdition.U
                        else -> MedVaultEdition.M
                    }
                )
            }

            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(3200)
                showBrandSplash = false
            }

            LaunchedEffect(downloadWorkItems) {
                downloadedLectures =
                    DownloadStore.getAll(this@MainActivity)

                val expiredDownload = downloadWorkItems.firstOrNull { work ->
                    work.state == WorkInfo.State.FAILED &&
                        work.outputData.getString("error")
                            ?.contains("Google access expired", ignoreCase = true) == true
                }

                if (expiredDownload != null) {
                    val expiredAccountId = accountIdForWork(expiredDownload)
                    try {
                        DriveTokenVault.clear(applicationContext, expiredAccountId)
                    } catch (_: Exception) {
                    }
                    if (expiredAccountId == activeAccountId) {
                        accessToken = null
                        connected = false
                        status = "Google access expired. Reconnect this Google account before retrying downloads."
                    }
                }
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B))))
                        // Keep backgrounds edge-to-edge, but keep controls and text
                        // outside physical display cutouts on every orientation.
                        .windowInsetsPadding(WindowInsets.displayCutout)
                ) {
                MedVaultRoot(
                    showBrandSplash = showBrandSplash,
                    connected = connected,
                    authBusy = authBusy,
                    loading = loading,
                    status = status,
                    items = driveItems.toList(),
                    folderName = folderName,
                    canGoBack = folderStack.isNotEmpty(),
                    selectedVideo = selectedVideo,
                    screen = screen,
                    telegramProgressRevision = telegramProgressRevision,
                    activeEdition = activeEdition,
                    onEditionChange = { edition ->
                        activeEdition = edition
                        editionPrefs.edit().putString("active_edition", edition.name).apply()
                    },
                    activeAccountEmail = activeAccountEmail,
                    onOpenQBank = { screen = "qbank" },
                    onOpenTests = { screen = "tests" },
                    onOpenOffline = { screen = "offline" },
                    onOpenStudySources = { screen = "study_sources" },
                    onOpenDailyDose = { screen = "daily_dose" },
                    onOpenUpdates = { screen = "updates" },
                    onOpenPractice = { screen = "practice" },
                    selectedPdfFile = selectedPdfFile,
                    selectedPdfTitle = selectedPdfTitle,
                    onOpenPdf = { file, title ->
                        try {
                            val uri = FileProvider.getUriForFile(this@MainActivity, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(viewIntent, "Open PDF with"))
                        } catch (error: Exception) {
                            Toast.makeText(this@MainActivity, "No compatible PDF viewer found: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                        }
                    },
                    onClosePdf = { selectedPdfFile = null; screen = pdfReturnScreen },
                    onOpenTelegram = { screen = "telegram" },
                    onOpenTelegramLibrary = { screen = "videos" },
                    onPlayTelegramLecture = { startTelegramVideo(it) },
                    onDownloadTelegramLecture = { enqueueTelegramDownload(it) },
                    onSettings = { screen = "settings" },
                    onGoogleSettings = { screen = "google_settings" },
                    onTelegramSettings = { screen = "telegram_settings" },
                    onManageTelegramGroups = { screen = "study_sources" },
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
                    onConnect = {
                        saveCurrentProgress()
                        accessToken?.let { syncHistory(it) }
                        authorizeDrive(true)
                    },
                    onDisconnect = { disconnectDrive() },

                    onBrowse = {
                        folderId = "root"
                        folderName = "My Drive"
                        currentSharedDriveId = null
                        folderStack.clear()
                        driveSearchActive = false
                        screen = "videos"
                        browseDrive()
                    },
                    onRawDrive = {
                        folderStack.clear()
                        folderId = "root"
                        folderName = "My Drive"
                        currentSharedDriveId = null
                        driveSearchActive = false
                        screen = "drive"
                        loadFolder("root")
                    },

                    onHome = {
                        saveCurrentProgress()
                        leavePlayer()
                        screen = "home"
                    },
                    onPlayerBack = {
                        saveCurrentProgress()
                        leavePlayer()
                        screen = playerReturnScreen
                    },

                    onFolderBack = {
                        if (driveSearchActive) {
                            folderId = folderIdBeforeSearch
                            folderName = folderNameBeforeSearch
                            driveSearchActive = false
                            loadFolder(folderId)
                        } else goBackFolder()
                    },
                    onRefresh = {
                        if (driveSearchActive) {
                            folderName = folderNameBeforeSearch
                            loadFolder(folderIdBeforeSearch)
                        } else loadFolder(folderId)
                    },
                    onGlobalSearch = {
                        screen = "drive"
                        searchDrive(it)
                    },
                    onFolder = {
                        screen = "drive"
                        openFolder(it)
                    },
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
                                "video/mp4",
                                accountId = record.accountId
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
                            downloadRepository.pause(work.id, fileId, accountIdForWork(work))
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
                            downloadRepository.cancel(work.id, fileId, accountIdForWork(work))
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

        // Google Drive integration is dormant; no Google setup is launched.
    }

    // ---------------------------------------------------------
    // GOOGLE DRIVE AUTHORIZATION
    // ---------------------------------------------------------

    private fun authorizeDrive(forceAccountPicker: Boolean = false) {
        if (authBusy) return

        authBusy = true
        status = "Checking Google Drive access…"

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(DRIVE_SCOPE),
                    Scope(DRIVE_APPDATA_SCOPE),
                    Scope(DRIVE_EMAIL_SCOPE)
                )
            )
            .apply {
                if (forceAccountPicker) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                else if (activeAccountId != "default" && activeAccountId.contains("@")) {
                    setAccount(Account(activeAccountId, "com.google"))
                }
            }
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

        status = "Identifying Google account…"
        io.execute {
            try {
                val connection = URL("https://www.googleapis.com/oauth2/v2/userinfo")
                    .openConnection() as HttpURLConnection
                val email: String
                val displayName: String
                try {
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("Could not identify the selected Google account.")
                    }
                    val profile = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    email = profile.optString("email").trim()
                    displayName = profile.optString("name", email).ifBlank { email }
                } finally {
                    connection.disconnect()
                }
                if (email.isBlank()) throw IllegalStateException("Google did not return an account email.")

                val isFirstSavedAccount = DriveTokenVault.accounts(applicationContext).isEmpty()
                DriveTokenVault.save(applicationContext, email, token)
                DriveTokenVault.saveAccount(applicationContext, DriveAccount(email, email, displayName))
                DriveTokenVault.setActiveAccountId(applicationContext, email)

                runOnUiThread {
                    activeAccountId = email
                    activeAccountEmail = email
                    accessToken = token
                    connected = true
                    status = "Connected: $email"
                    historySync = WatchHistorySync(this@MainActivity, email, migrateLegacy = isFirstSavedAccount)
                    io.execute {
                        val savedHistory = try { historySync.loadLocal() } catch (_: Exception) { mutableMapOf() }
                        runOnUiThread {
                            watchRecords.clear()
                            watchRecords.putAll(savedHistory)
                            syncHistory(token)
                        }
                    }
                    if (screen == "drive" || screen == "videos") {
                        folderStack.clear()
                        currentSharedDriveId = null
                        folderId = "root"
                        folderName = "My Drive"
                        loadFolder("root")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connected = false
                    status = "Google account setup failed: ${e.message}"
                }
            }
        }
    }

    private fun disconnectDrive() {
        saveCurrentProgress()
        accessToken = null
        try { DriveTokenVault.clear(applicationContext, activeAccountId) } catch (_: Exception) { }
        connected = false
        driveItems.clear()
        folderStack.clear()
        folderId = "root"
        folderName = "My Drive"

        leavePlayer()
        screen = "videos"

        status =
            "Google Drive disconnected. Saved accounts, local downloads and watch history are retained."
    }

    // ---------------------------------------------------------
    // DOWNLOAD MANAGER
    // ---------------------------------------------------------

    private fun enqueueTelegramDownload(lecture: TelegramLecture) {
        val existing = DownloadStore.find(this, lecture.stableId, "telegram")
        if (existing != null && File(existing.localPath).isFile) {
            Toast.makeText(this, "Already available offline.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            downloadRepository.enqueueTelegram(lecture)
            Toast.makeText(this, "Added to offline downloads.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not queue download: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

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
        val existing = DownloadStore.find(this, file.id, file.accountId)

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

    private fun accountIdForWork(work: WorkInfo): String {
        val key = work.tags.firstOrNull { it.startsWith("medvault_account_") }
            ?.removePrefix("medvault_account_")
        return DriveTokenVault.accounts(this)
            .firstOrNull { DownloadRepository.accountKey(it.id) == key }
            ?.id ?: activeAccountId
    }

    private fun retryDownload(work: WorkInfo) {
        if ("medvault_source_telegram" in work.tags) {
            Toast.makeText(
                this,
                "Retry this Telegram download from its lecture listing.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

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

        val accountId = accountIdForWork(work)
        val file = DriveItem(
            id = fileId,
            name = name,
            mimeType = "video/mp4",
            accountId = accountId
        )

        if (DriveTokenVault.get(this, accountId).isNullOrBlank()) {
            activeAccountId = accountId
            activeAccountEmail = DriveTokenVault.accounts(this).firstOrNull { it.id == accountId }?.email ?: accountId
            DriveTokenVault.setActiveAccountId(this, accountId)
            Toast.makeText(
                this,
                "Reconnect the Google account that owns this download.",
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

        DownloadStore.delete(this, lecture.fileId, lecture.accountId)

        Toast.makeText(
            this,
            "Offline lecture deleted.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startOfflineVideo(
        lecture: DownloadedLecture
    ) {
        if (lecture.source == "telegram") {
            val telegramLecture = TelegramLecture(
                stableId = lecture.fileId,
                chatId = lecture.chatId,
                topicId = lecture.topicId,
                messageId = lecture.messageId,
                title = lecture.name,
                fileName = File(lecture.localPath).name,
                caption = "",
                fileId = lecture.telegramFileId,
                fileSizeBytes = File(lecture.localPath).length(),
                durationSeconds = lecture.durationSeconds,
                mimeType = lecture.mimeType,
                thumbnailFileId = null,
                messageDate = 0
            )
            startTelegramVideo(telegramLecture)
            return
        }
        if (activeAccountId != lecture.accountId) {
            activeAccountId = lecture.accountId
            activeAccountEmail = DriveTokenVault.accounts(this)
                .firstOrNull { it.id == lecture.accountId }?.email ?: lecture.accountId
            DriveTokenVault.setActiveAccountId(this, lecture.accountId)
            historySync = WatchHistorySync(this, lecture.accountId)
            try {
                watchRecords.clear()
                watchRecords.putAll(historySync.loadLocal())
            } catch (_: Exception) {
                watchRecords.clear()
            }
            accessToken = DriveTokenVault.get(this, lecture.accountId)
            connected = !accessToken.isNullOrBlank()
        }
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
            mimeType = "video/mp4",
            accountId = lecture.accountId
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
        val syncStore = historySync

        io.execute {
            try {
                val merged = syncStore.sync(token, snapshot)

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
        selectedTelegramLecture?.let { lecture ->
            val completed = position >= (duration * 0.95f).toLong()
            getSharedPreferences("telegram_playback_local", MODE_PRIVATE).edit()
                .putLong(lecture.stableId, if (completed) duration else position.coerceAtLeast(0L))
                .apply()
            telegramProgressRevision++
            return
        }

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
            completed = completed,
            accountId = file.accountId
        )

        watchRecords[file.id] = record

        val snapshot = watchRecords.toMap()
        val syncStore = historySync

        io.execute {
            try {
                syncStore.saveLocal(snapshot)
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

    // Natural, human-readable ordering: folders first, then names with
    // numeric runs compared by value (1, 2, 10 rather than 1, 10, 2).
    private fun sortedDriveItems(items: List<DriveItem>): List<DriveItem> {
        fun compareNatural(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val aDigit = a[i].isDigit()
                val bDigit = b[j].isDigit()
                if (aDigit && bDigit) {
                    var aEnd = i
                    var bEnd = j
                    while (aEnd < a.length && a[aEnd].isDigit()) aEnd++
                    while (bEnd < b.length && b[bEnd].isDigit()) bEnd++
                    val aRaw = a.substring(i, aEnd)
                    val bRaw = b.substring(j, bEnd)
                    val aNumber = aRaw.trimStart('0').ifEmpty { "0" }
                    val bNumber = bRaw.trimStart('0').ifEmpty { "0" }
                    val numberCompare = when {
                        aNumber.length != bNumber.length -> aNumber.length.compareTo(bNumber.length)
                        else -> aNumber.compareTo(bNumber)
                    }
                    if (numberCompare != 0) return numberCompare
                    // Stable tie-break for names such as "01" and "1".
                    val rawCompare = aRaw.length.compareTo(bRaw.length)
                    if (rawCompare != 0) return rawCompare
                    i = aEnd
                    j = bEnd
                } else {
                    val charCompare = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                    if (charCompare != 0) return charCompare
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j)
        }

        return items.sortedWith { left, right ->
            val leftIsFolder = left.mimeType == FOLDER_MIME || left.isSharedDriveRoot
            val rightIsFolder = right.mimeType == FOLDER_MIME || right.isSharedDriveRoot
            when {
                leftIsFolder && !rightIsFolder -> -1
                !leftIsFolder && rightIsFolder -> 1
                else -> compareNatural(left.name, right.name)
            }
        }
    }

    private fun loadFolder(targetFolderId: String) {
        driveSearchActive = false
        val token = accessToken

        if (token.isNullOrBlank()) {
            status = "Connect Google Drive from account settings."
            authorizeDrive()
            return
        }

        val serial = ++requestSerial
        val accountId = activeAccountId
        val targetSharedDriveId = currentSharedDriveId

        loading = true
        status = "Loading $folderName…"
        driveItems.clear()

        io.execute {
            try {
                val found = mutableListOf<DriveItem>()
                var pageToken: String? = null

                do {
                    val parentId = if (targetFolderId == "root" && targetSharedDriveId != null) targetSharedDriveId else targetFolderId
                    val query =
                        "'${parentId.replace("'", "\\'")}' in parents and trashed=false"

                    val builder = Uri.parse(
                        "https://www.googleapis.com/drive/v3/files"
                    ).buildUpon()
                        .appendQueryParameter("q", query)
                        .appendQueryParameter(
                            "fields",
                            "nextPageToken,files(id,name,mimeType,driveId,size)"
                        )
                        .appendQueryParameter("pageSize", "100")
                        .appendQueryParameter("orderBy", "folder,name")
                        .appendQueryParameter("supportsAllDrives", "true")
                        .appendQueryParameter("includeItemsFromAllDrives", "true")
                        .appendQueryParameter("corpora", if (targetSharedDriveId != null) "drive" else "user")
                        .also { if (targetSharedDriveId != null) it.appendQueryParameter("driveId", targetSharedDriveId) }

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
                                file.getString("mimeType"),
                                accountId = accountId,
                                sharedDriveId = file.optString("driveId").takeIf { it.isNotBlank() }
                            )
                        }
                    }

                    pageToken = json.optString("nextPageToken")
                        .takeIf { it.isNotBlank() }

                } while (pageToken != null)

                if (targetFolderId == "root" && targetSharedDriveId == null) {
                    found.addAll(listSharedDrives(token, accountId))
                }

                runOnUiThread {
                    if (
                        serial == requestSerial &&
                        !isFinishing
                    ) {
                        driveItems.clear()
                        driveItems.addAll(sortedDriveItems(found))
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

    private fun listSharedDrives(token: String, accountId: String): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val builder = Uri.parse("https://www.googleapis.com/drive/v3/drives")
                .buildUpon()
                .appendQueryParameter("pageSize", "100")
                .appendQueryParameter("fields", "nextPageToken,drives(id,name)")
                .appendQueryParameter("useDomainAdminAccess", "false")
            pageToken?.let { builder.appendQueryParameter("pageToken", it) }
            val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
            val response: String
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    throw IllegalStateException("Shared Drive list failed ($code): $detail")
                }
                response = connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val json = JSONObject(response)
            val drives = json.optJSONArray("drives") ?: org.json.JSONArray()
            for (i in 0 until drives.length()) {
                val drive = drives.getJSONObject(i)
                val id = drive.getString("id")
                result += DriveItem(
                    id = id,
                    name = drive.getString("name"),
                    mimeType = FOLDER_MIME,
                    accountId = accountId,
                    sharedDriveId = id,
                    isSharedDriveRoot = true
                )
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return result
    }

    private fun searchDrive(rawQuery: String) {
        val queryText = rawQuery.trim()
        if (queryText.isBlank()) {
            driveSearchActive = false
            loadFolder(folderId)
            return
        }

        val token = accessToken
        if (token.isNullOrBlank()) {
            status = "Connect Google Drive before searching."
            authorizeDrive()
            return
        }

        val serial = ++requestSerial
        val accountId = activeAccountId
        if (!driveSearchActive) {
            folderIdBeforeSearch = folderId
            folderNameBeforeSearch = folderName
        }
        driveSearchActive = true
        loading = true
        folderName = "Drive search"
        status = "Searching all of Google Drive…"
        driveItems.clear()

        io.execute {
            try {
                val found = mutableListOf<DriveItem>()
                var pageToken: String? = null
                val escaped = queryText.replace("\\", "\\\\").replace("'", "\\'")
                val driveQuery = "name contains '$escaped' and trashed=false"

                do {
                    val builder = Uri.parse(
                        "https://www.googleapis.com/drive/v3/files"
                    ).buildUpon()
                        .appendQueryParameter("q", driveQuery)
                        .appendQueryParameter("fields", "nextPageToken,files(id,name,mimeType,driveId,size)")
                        .appendQueryParameter("pageSize", "100")
                        .appendQueryParameter("orderBy", "folder,name")
                        .appendQueryParameter("supportsAllDrives", "true")
                        .appendQueryParameter("includeItemsFromAllDrives", "true")
                        .appendQueryParameter("corpora", "user")

                    pageToken?.let { builder.appendQueryParameter("pageToken", it) }
                    val connection = URL(builder.build().toString())
                        .openConnection() as HttpURLConnection

                    val response: String
                    try {
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        connection.connectTimeout = 15000
                        connection.readTimeout = 20000
                        val code = connection.responseCode
                        if (code !in 200..299) {
                            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                                ?: "HTTP $code"
                            if (code == 401) {
                                throw SecurityException("Google access expired. Reconnect from account settings.")
                            }
                            throw IllegalStateException("Drive search failed ($code): $detail")
                        }
                        response = connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }

                    val json = JSONObject(response)
                    val files = json.optJSONArray("files") ?: org.json.JSONArray()
                    for (index in 0 until files.length()) {
                        val file = files.getJSONObject(index)
                        found += DriveItem(
                            file.getString("id"),
                            file.getString("name"),
                            file.getString("mimeType"),
                            accountId = accountId,
                            sharedDriveId = file.optString("driveId").takeIf { it.isNotBlank() }
                        )
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                } while (pageToken != null)

                runOnUiThread {
                    if (serial == requestSerial && !isFinishing) {
                        driveItems.clear()
                        driveItems.addAll(sortedDriveItems(found))
                        loading = false
                        status = "${found.size} results across Google Drive"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (serial == requestSerial && !isFinishing) {
                        loading = false
                        status = error.message ?: "Could not search Google Drive."
                        if (error is SecurityException) {
                            connected = false
                            accessToken = null
                            try { DriveTokenVault.clear(applicationContext) } catch (_: Exception) { }
                        }
                    }
                }
            }
        }
    }

    private fun openFolder(item: DriveItem) {
        if (loading || item.mimeType != FOLDER_MIME) return
        if (driveSearchActive) {
            folderStack.add(FolderLocation(folderIdBeforeSearch, folderNameBeforeSearch, currentSharedDriveId))
            driveSearchActive = false
        } else {
            folderStack.add(FolderLocation(folderId, folderName, currentSharedDriveId))
        }

        if (item.isSharedDriveRoot) {
            currentSharedDriveId = item.sharedDriveId
            folderId = "root"
        } else {
            currentSharedDriveId = item.sharedDriveId
            folderId = item.id
        }
        folderName = item.name
        loadFolder(folderId)
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
        currentSharedDriveId = previous.sharedDriveId

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
        selectedTelegramLecture = null
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
        playerReturnScreen = screen
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
                    var cause: Throwable? = error
                    var authorizationExpired = false
                    while (cause != null) {
                        if (
                            cause is HttpDataSource.InvalidResponseCodeException &&
                            cause.responseCode == 401
                        ) {
                            authorizationExpired = true
                            break
                        }
                        cause = cause.cause
                    }

                    runOnUiThread {
                        if (authorizationExpired) {
                            accessToken = null
                            connected = false
                            status = "Google access expired. Reconnect Google Drive to continue streaming."
                            try {
                                DriveTokenVault.clear(applicationContext)
                            } catch (_: Exception) {
                            }
                        } else {
                            status = "Playback error: ${error.message}"
                        }

                        Toast.makeText(
                            this@MainActivity,
                            if (authorizationExpired) {
                                "Google access expired. Reconnect Drive."
                            } else {
                                "Playback failed: ${error.errorCodeName}"
                            },
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
                    "${Uri.encode(item.id)}?alt=media&supportsAllDrives=true"
            )
        }

        newPlayer.setMediaItem(
            MediaItem.fromUri(mediaUri)
        )

        newPlayer.prepare()
        newPlayer.playWhenReady = true
    }

    private fun startTelegramVideo(lecture: TelegramLecture) {
        saveCurrentProgress()
        player?.release()

        val resumePosition = getSharedPreferences("telegram_playback_local", MODE_PRIVATE)
            .getLong(lecture.stableId, 0L)
        val auth = TelegramAuthManager.getInstance(this) { }
        val offlineCopy = DownloadStore.find(this, lecture.stableId, "telegram")
            ?.localPath?.let(::File)?.takeIf { it.isFile }
        val sourceFactory = TelegramRangeDataSource.Factory(auth, lecture.fileId, lecture.fileSizeBytes)
        val newPlayer = if (offlineCopy != null) {
            ExoPlayer.Builder(this).build()
        } else {
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
                .build()
        }

        player = newPlayer
        selectedTelegramLecture = lecture
        selectedVideo = DriveItem(
            id = lecture.stableId,
            name = lecture.title,
            mimeType = lecture.mimeType,
            accountId = "telegram-local"
        )
        playerReturnScreen = screen
        screen = "player"

        var resumeApplied = false
        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !resumeApplied && resumePosition > 0L) {
                    newPlayer.seekTo(resumePosition)
                    resumeApplied = true
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                runOnUiThread {
                    status = "Video playback error: ${error.message ?: error.errorCodeName}"
                    Toast.makeText(this@MainActivity, "Video playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            }
        })

        val mediaItem = MediaItem.Builder()
            .setMediaId(lecture.stableId)
            .setUri(offlineCopy?.let { Uri.fromFile(it) } ?: Uri.parse("telegram://file/${lecture.fileId}"))
            .setMimeType(lecture.mimeType.takeIf { it.startsWith("video/") })
            .build()
        newPlayer.setMediaItem(mediaItem)
        newPlayer.prepare()
        newPlayer.playWhenReady = true
    }

    private fun leavePlayer() {
        saveCurrentProgress()

        selectedVideo = null
        selectedTelegramLecture = null

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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MedVaultRoot(
    showBrandSplash: Boolean,
    connected: Boolean, authBusy: Boolean, loading: Boolean, status: String, telegramProgressRevision: Int,
    items: List<DriveItem>, folderName: String, canGoBack: Boolean,
    selectedVideo: DriveItem?, screen: String, activeEdition: MedVaultEdition, onEditionChange: (MedVaultEdition) -> Unit, activeAccountEmail: String,
    onOpenTelegram: () -> Unit, onOpenTelegramLibrary: () -> Unit,
    onPlayTelegramLecture: (TelegramLecture) -> Unit, onDownloadTelegramLecture: (TelegramLecture) -> Unit, onPlayerBack: () -> Unit,
    onSettings: () -> Unit, onGoogleSettings: () -> Unit,
    onTelegramSettings: () -> Unit, onManageTelegramGroups: () -> Unit,
    onOpenQBank: () -> Unit, onOpenTests: () -> Unit, onOpenOffline: () -> Unit,
    onOpenStudySources: () -> Unit, onOpenDailyDose: () -> Unit, onOpenUpdates: () -> Unit, onOpenPractice: () -> Unit,
    selectedPdfFile: File?, selectedPdfTitle: String,
    onOpenPdf: (File, String) -> Unit, onClosePdf: () -> Unit,
    accountMenu: Boolean, pipMode: Boolean, fullscreen: Boolean, player: ExoPlayer?,
    history: List<WatchRecord>, downloadWorkItems: List<WorkInfo>,
    downloadedLectures: List<DownloadedLecture>, pausedDownloadIds: Set<String>,
    onAccountMenu: (Boolean) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit,
    onBrowse: () -> Unit, onRawDrive: () -> Unit, onHome: () -> Unit,
    onFolderBack: () -> Unit, onRefresh: () -> Unit, onGlobalSearch: (String) -> Unit,
    onFolder: (DriveItem) -> Unit, onFile: (DriveItem) -> Unit, onDownload: (DriveItem) -> Unit,
    onHistory: () -> Unit, onContinue: () -> Unit, onDownloads: () -> Unit,
    onHistoryFile: (WatchRecord) -> Unit, onFullscreen: () -> Unit, onPip: () -> Unit,
    onProgress: (Long, Long) -> Unit, onPauseDownload: (WorkInfo) -> Unit,
    onResumeDownload: (WorkInfo) -> Unit, onCancelDownload: (WorkInfo) -> Unit,
    onRetryDownload: (WorkInfo) -> Unit, onPlayOffline: (DownloadedLecture) -> Unit,
    onDeleteOffline: (DownloadedLecture) -> Unit
) {
    val telegramLibraryState = remember { TelegramLibraryScreenState() }
    val qbankLibraryState = remember { TelegramDocumentLibraryState() }
    val notesLibraryState = remember { TelegramDocumentLibraryState() }
    val mainTabs = setOf("home", "videos", "qbank", "offline", "settings")
    BackHandler(enabled = screen !in setOf("home", "videos") && !pipMode) {
        when (screen) {
            "player" -> onPlayerBack()
            "settings", "study_sources", "telegram_settings", "telegram" -> onHome()
            "drive" -> onFolderBack()
            else -> onHome()
        }
    }
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B))))) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (initialState == "player" || targetState == "player") EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(animationSpec = LiquidMotion.FadeIn) +
                    slideInHorizontally(animationSpec = LiquidMotion.NavigationOffset) { it / 5 } +
                    scaleIn(initialScale = 0.985f, animationSpec = LiquidMotion.Navigation)) togetherWith
                    (fadeOut(animationSpec = LiquidMotion.FadeOut) +
                    slideOutHorizontally(animationSpec = LiquidMotion.NavigationOffset) { -it / 9 } +
                    androidx.compose.animation.scaleOut(targetScale = 0.992f, animationSpec = LiquidMotion.Navigation))
            },
            label = "medvault_navigation"
        ) { targetScreen ->
            Surface(Modifier.fillMaxSize(), color = AppBg) {
                when (targetScreen) {
                    "home" -> MedVaultHomeScreen(onVideos = onOpenTelegramLibrary, onQBank = onOpenQBank,
                        onTests = onOpenTests, onDailyDose = onOpenDailyDose, onUpdates = onOpenOffline, onSettings = onSettings,
                        onHistory = onHistory, history = history, onResumeLecture = onHistoryFile,
                        activeEdition = activeEdition, onEditionChange = onEditionChange)
                    "videos", "telegram_library" -> TelegramLibraryScreen(onBack = onHome, onConnect = onOpenTelegram,
                        onPlayLecture = onPlayTelegramLecture, onDownloadLecture = onDownloadTelegramLecture,
                        downloadedLectures = downloadedLectures, downloadWorkItems = downloadWorkItems,
                        setupMode = false, onManageSources = onOpenStudySources,
                        activeEdition = activeEdition, state = telegramLibraryState, progressRevision = telegramProgressRevision,
                        onDeleteOffline = onDeleteOffline)
                    "telegram_setup" -> TelegramStudySourcesSettingsScreen(onBack = onHome, onConnectAccount = onOpenTelegram)
                    "qbank" -> TelegramDocumentLibraryScreen(MedVaultEdition.QBANK, onHome, onOpenPdf, onOpenStudySources, onOpenTelegram, activeEdition = activeEdition, onOpenPractice = onOpenPractice, state = qbankLibraryState)
                    "tests" -> TelegramDocumentLibraryScreen(MedVaultEdition.TESTS, onHome, onOpenPdf, onOpenStudySources, onOpenTelegram, activeEdition = activeEdition, state = notesLibraryState)
                    "offline" -> MedVaultOfflineScreen(downloadedLectures, onOpenPdf, onPlayOffline, onHome, onDeleteOffline)
                    "study_sources" -> TelegramStudySourcesSettingsScreen(onBack = onSettings, onConnectAccount = onOpenTelegram)
                    "settings" -> MedVaultSettingsScreen(onBack = onHome, onStudySources = onOpenStudySources, onConnectAccount = onOpenTelegram)
                    "daily_dose" -> MedVaultQuestionScreen(onBack = onHome, dailyMode = true)
                    "practice" -> MedVaultQuestionScreen(onBack = onHome, dailyMode = false)
                    "updates" -> MedicalUpdatesScreen(onBack = onHome)
                    "player" -> {
                        if (selectedVideo != null && player != null) VideoScreen(selectedVideo, player, fullscreen, pipMode,
                            onPlayerBack, onFullscreen, onPip, onProgress)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No video selected.", color = Color.White) }
                    }
                    "telegram_settings" -> TelegramSettingsScreen(onBack = onSettings, onConnect = onOpenTelegram, onManageGroups = onManageTelegramGroups)
                    "telegram" -> TelegramLoginScreen(onBack = onSettings)
                    "history", "continue" -> HistoryScreen(
                        title = if (targetScreen == "history") "Watch history" else "Continue watching",
                        records = if (targetScreen == "continue") history.filter { !it.completed && it.positionMs > 0L } else history,
                        onBack = onHome, onOpen = onHistoryFile)
                    "downloads" -> DownloadManagerScreen(workItems = downloadWorkItems, downloadedLectures = downloadedLectures,
                        pausedIds = pausedDownloadIds, onBack = onHome, onPause = onPauseDownload, onResume = onResumeDownload,
                        onCancel = onCancelDownload, onRetry = onRetryDownload, onPlayOffline = onPlayOffline, onDeleteOffline = onDeleteOffline)
                    "drive" -> DriveBrowser(items = items, folderName = folderName, canGoBack = canGoBack, loading = loading,
                        status = status, onBack = onFolderBack, onRefresh = onRefresh, onGlobalSearch = onGlobalSearch,
                        onFolder = onFolder, onFile = onFile, onDownload = onDownload, onHome = onHome)
                    else -> MedVaultHomeScreen(onVideos = onOpenTelegramLibrary, onQBank = onOpenQBank,
                        onTests = onOpenTests, onDailyDose = onOpenDailyDose, onUpdates = onOpenOffline, onSettings = onSettings,
                        onHistory = onHistory, history = history, onResumeLecture = onHistoryFile,
                        activeEdition = activeEdition, onEditionChange = onEditionChange)
                }
            }
        }
        // The floating dock must leave the entire IME area to the keyboard.
        // WindowInsets.isImeVisible is shared here so every searchable screen gets
        // the same behaviour without per-field keyboard workarounds.
        if (screen in mainTabs && !pipMode && !showBrandSplash && !WindowInsets.isImeVisible) {
            MedVaultBottomBarHost(selected = if (screen == "settings") "profile" else screen, onHome = onHome, onVideos = onOpenTelegramLibrary,
                onQBank = onOpenQBank, onOffline = onOpenOffline, onProfile = onSettings)
        }
    }
}

@Composable
private fun MedVaultPlaceholderScreen(title: String, subtitle: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFFF6F8FB)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF252A3A))
        }
        Text(subtitle, color = Color(0xFF81889A), fontSize = 14.sp)
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onTelegramSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onTelegramSettings), colors = CardDefaults.cardColors(containerColor = AppPanel), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Send, null, tint = AppAccent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Content account", fontWeight = FontWeight.SemiBold)
                    Text("Account, source groups and lecture library settings", color = AppMuted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = AppMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("MedVault  •  Version 1.0", color = AppMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("BY AFTAB", color = AppMuted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun GoogleSettingsScreen(
    connected: Boolean,
    activeAccountEmail: String,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBrowseDrive: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Google Drive", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(if (connected) "Connected · $activeAccountEmail" else "Google Drive is not connected", color = AppMuted)
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text(if (connected) "Add / switch Google account" else "Connect Google account") }
        if (connected) TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect Google account") }
        OutlinedButton(onClick = onBrowseDrive, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Browse raw Drive") }
    }
}

@Composable
private fun TelegramSettingsScreen(onBack: () -> Unit, onConnect: () -> Unit, onManageGroups: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { TelegramLibraryRepository(context, TelegramAuthManager.getInstance(context) {}) }
    val marrow = repo.selectedTitle(MedVaultEdition.M)
    val prep = repo.selectedTitle(MedVaultEdition.P)
    val universal = repo.selectedTitle(MedVaultEdition.U)
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Content account", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text("Account and configured content sources", color = AppMuted)
        Text("Edition M", fontWeight = FontWeight.SemiBold)
        Text(marrow ?: "Not configured", color = AppMuted)
        Divider(color = AppMuted.copy(alpha = 0.25f))
        Text("Edition P", fontWeight = FontWeight.SemiBold)
        Text(prep ?: "Not configured", color = AppMuted)
        Divider(color = AppMuted.copy(alpha = 0.25f))
        Text("Edition U", fontWeight = FontWeight.SemiBold)
        Text(universal ?: "Not configured", color = AppMuted)
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect / manage account") }
        OutlinedButton(onClick = onManageGroups, modifier = Modifier.fillMaxWidth()) { Text("Change source groups") }
    }
}

@Composable
private fun TelegramLoginScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var authState by remember { mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting) }
    var phone by remember { mutableStateOf("+91") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var manager by remember { mutableStateOf<TelegramAuthManager?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val auth = TelegramAuthManager.getInstance(context) { state ->
            authState = state
            busy = false
        }
        manager = auth
        authState = auth.currentState
        auth.start()
    }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Connect account", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        Text("Sign in to your content account. Authentication is handled locally on this device.", color = AppMuted, fontSize = 13.sp)
        when (val state = authState) {
            TelegramAuthManager.State.Starting -> {
                CircularProgressIndicator()
                Text("Starting secure content service…", color = AppMuted)
            }
            TelegramAuthManager.State.WaitingForPhone -> {
                OutlinedTextField(value = phone, onValueChange = { phone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Phone number with country code") }, placeholder = { Text("+919876543210") }, singleLine = true)
                Button(onClick = { busy = true; manager?.submitPhoneNumber(phone) }, enabled = !busy && phone.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Sending…" else "Send login code") }
            }
            TelegramAuthManager.State.WaitingForCode -> {
                OutlinedTextField(value = code, onValueChange = { code = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Login code") }, singleLine = true)
                Button(onClick = { busy = true; manager?.submitCode(code) }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Verifying…" else "Verify code") }
            }
            TelegramAuthManager.State.WaitingForPassword -> {
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Two-step verification password") }, singleLine = true)
                Button(onClick = { busy = true; manager?.submitPassword(password) }, enabled = !busy && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Verifying…" else "Verify password") }
            }
            is TelegramAuthManager.State.Ready -> {
                Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(36.dp))
                        Text("Account connected", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Account verified", color = AppMuted)
                        Text("Your session is stored locally on this device.", color = AppMuted)
                    }
                }
            }
            is TelegramAuthManager.State.Failed -> {
                Text(state.message, color = Color(0xFFFF8A80))
                Button(onClick = { authState = TelegramAuthManager.State.Starting; manager?.start() }) { Text("Retry") }
            }
            TelegramAuthManager.State.Closed -> {
                Text("Content service closed.", color = AppMuted)
                Button(onClick = { manager?.start() }) { Text("Reconnect") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to MedVault") }
    }
}


private class TelegramLibraryScreenState {
    val authState = mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting)
    val repo = mutableStateOf<TelegramLibraryRepository?>(null)
    val groups = mutableStateOf<List<TelegramSourceCandidate>>(emptyList())
    val topics = mutableStateOf<List<TelegramForumTopic>>(emptyList())
    val messages = mutableStateOf<List<org.drinkless.tdlib.TdApi.Message>>(emptyList())
    val hasMoreMessages = mutableStateOf(false)
    val topic = mutableStateOf<TelegramForumTopic?>(null)
    val titleM = mutableStateOf<String?>(null)
    val titleP = mutableStateOf<String?>(null)
    val titleU = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
    val status = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)
    val topicSearch = mutableStateOf("")
    val topicMessagesCache = mutableStateOf<Map<String, List<org.drinkless.tdlib.TdApi.Message>>>(emptyMap())
    val editionSearchResults = mutableStateOf<Map<MedVaultEdition, List<Pair<String, TelegramLecture>>>>(emptyMap())
    val editionSearchLoading = mutableStateOf(false)
    val editionSearchLoaded = mutableStateOf<MedVaultEdition?>(null)

    private val topicRequestGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    fun beginTopicRequest(): Long = topicRequestGeneration.incrementAndGet()

    fun isCurrentTopicRequest(requestId: Long): Boolean =
        requestId == topicRequestGeneration.get()

    fun cancelTopicRequest() {
        topicRequestGeneration.incrementAndGet()
    }

    fun cachedTopicMessages(key: String): List<org.drinkless.tdlib.TdApi.Message>? {
        val cached = topicMessagesCache.value[key] ?: return null
        val refreshed = LinkedHashMap(topicMessagesCache.value)
        refreshed.remove(key)
        refreshed[key] = cached
        topicMessagesCache.value = refreshed
        return cached
    }

    fun cacheTopicMessages(key: String, messages: List<org.drinkless.tdlib.TdApi.Message>) {
        // Do not retain unusually large histories. The active screen can still
        // display them; reopening simply performs a fresh progressive load.
        if (messages.size > MAX_CACHED_MESSAGES_PER_TOPIC) {
            if (key in topicMessagesCache.value) {
                topicMessagesCache.value = LinkedHashMap(topicMessagesCache.value).apply { remove(key) }
            }
            return
        }

        val updated = LinkedHashMap(topicMessagesCache.value)
        updated.remove(key)
        updated[key] = messages
        while (
            updated.size > MAX_CACHED_TOPICS ||
            updated.values.sumOf { it.size } > MAX_CACHED_MESSAGES_TOTAL
        ) {
            val eldestKey = updated.keys.firstOrNull() ?: break
            updated.remove(eldestKey)
        }
        topicMessagesCache.value = updated
    }

    private companion object {
        const val MAX_CACHED_TOPICS = 5
        const val MAX_CACHED_MESSAGES_PER_TOPIC = 800
        const val MAX_CACHED_MESSAGES_TOTAL = 4000
    }
}

@Composable
private fun TelegramLibraryScreen(onBack: () -> Unit, onConnect: () -> Unit, onPlayLecture: (TelegramLecture) -> Unit, onDownloadLecture: (TelegramLecture) -> Unit, downloadedLectures: List<DownloadedLecture>, downloadWorkItems: List<WorkInfo>, setupMode: Boolean, onManageSources: () -> Unit = {}, activeEdition: MedVaultEdition, state: TelegramLibraryScreenState, progressRevision: Int, onDeleteOffline: (DownloadedLecture) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(state) {
        onDispose { state.cancelTopicRequest() }
    }
    var authState by state.authState
    var repo by state.repo
    var groups by state.groups
    var topics by state.topics
    var messages by state.messages
    var hasMoreMessages by state.hasMoreMessages
    var topic by state.topic
    var titleM by state.titleM
    var titleP by state.titleP
    var titleU by state.titleU
    var busy by state.busy
    var status by state.status
    var error by state.error
    var topicSearch by state.topicSearch
    var editionSearchResults by state.editionSearchResults
    var editionSearchLoading by state.editionSearchLoading
    var editionSearchLoaded by state.editionSearchLoaded
    var pendingDeleteLecture by remember { mutableStateOf<DownloadedLecture?>(null) }
    // Keep a small LRU of completed subject histories. A generation token owns
    // each request so callbacks from a screen we have left cannot mutate it.
    val openTopic: (TelegramForumTopic) -> Unit = { selected ->
        val requestId = state.beginTopicRequest()
        topic = selected
        topicSearch = ""
        error = null
        val cacheKey = "${selected.chatId}:${selected.topicId}"
        val cachedMessages = state.cachedTopicMessages(cacheKey)
        if (cachedMessages != null) {
            messages = cachedMessages
            busy = false
            hasMoreMessages = false
        } else {
            messages = emptyList()
            busy = true
            hasMoreMessages = false
            val sourceRepo = repo
            if (sourceRepo == null) {
                busy = false
                error = "Video library is not ready yet. Try again."
            } else {
                sourceRepo.loadTopicMessagesProgressively(
                    selected,
                    shouldContinue = { state.isCurrentTopicRequest(requestId) },
                    onPage = { page ->
                        if (state.isCurrentTopicRequest(requestId)) messages = page
                    },
                    onComplete = { result ->
                        if (state.isCurrentTopicRequest(requestId)) {
                            busy = false
                            result.onSuccess {
                                messages = it
                                state.cacheTopicMessages(cacheKey, it)
                            }.onFailure { error = it.message }
                        }
                    }
                )
            }
        }
    }

    val visibleTopics = remember(topics, topicSearch) {
        topics.filter { it.name.contains(topicSearch.trim(), ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        val auth = TelegramAuthManager.getInstance(context) { authState = it }
        authState = auth.currentState
        auth.start()
        repo = TelegramLibraryRepository(context, auth)
    }
    LaunchedEffect(repo) {
        repo?.let {
            titleM = it.selectedTitle(MedVaultEdition.M)
            titleP = it.selectedTitle(MedVaultEdition.P)
            titleU = it.selectedTitle(MedVaultEdition.U)
        }
    }
    LaunchedEffect(topicSearch, topic, repo, activeEdition, authState, topics) {
        if (topic != null || topicSearch.isBlank() || authState !is TelegramAuthManager.State.Ready ||
            repo == null || repo?.selectedChatId(activeEdition) == null ||
            editionSearchLoaded == activeEdition || editionSearchLoading || topics.isEmpty()
        ) return@LaunchedEffect
        editionSearchLoading = true
        val sourceRepo = repo ?: return@LaunchedEffect
        val collected = mutableListOf<Pair<String, TelegramLecture>>()
        fun loadTopicAt(index: Int) {
            if (index >= topics.size) {
                editionSearchResults = editionSearchResults + (activeEdition to collected.toList())
                editionSearchLoaded = activeEdition
                editionSearchLoading = false
                return
            }
            val currentTopic = topics[index]
            sourceRepo.loadAllTopicMessages(currentTopic) { result ->
                result.onSuccess { page ->
                    collected += sourceRepo.extractLectures(currentTopic, page).map { currentTopic.name to it }
                }
                loadTopicAt(index + 1)
            }
        }
        loadTopicAt(0)
    }
    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { event -> backProgress.snapTo(event.progress) }
            if (topic != null) {
                state.cancelTopicRequest()
                topic = null
                messages = emptyList()
                busy = false
                topicSearch = ""
            } else {
                state.cancelTopicRequest()
                onBack()
            }
            backProgress.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 520f))
        } catch (cancelled: CancellationException) {
            backProgress.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 520f))
            throw cancelled
        }
    }
    val activeTitle = when (activeEdition) {
        MedVaultEdition.M -> titleM
        MedVaultEdition.P -> titleP
        else -> titleU
    }
    var editionLoadGeneration by remember { mutableStateOf(0) }
    LaunchedEffect(repo, activeEdition, authState) {
        val generation = ++editionLoadGeneration
        state.cancelTopicRequest()
        topic = null
        messages = emptyList()
        hasMoreMessages = false
        topicSearch = ""
        editionSearchLoading = false
        editionSearchLoaded = null
        topics = emptyList()
        error = null
        val sourceRepo = repo
        if (sourceRepo == null || authState !is TelegramAuthManager.State.Ready) {
            busy = false
            return@LaunchedEffect
        }
        if (sourceRepo.selectedChatId(activeEdition) == null) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        val editionToLoad = activeEdition
        sourceRepo.loadTopics(editionToLoad) { result ->
            if (editionLoadGeneration == generation) {
                busy = false
                result.onSuccess { topics = it }.onFailure { error = it.message }
            }
        }
    }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val progress = backProgress.value
                translationX = size.width * 0.20f * progress
                scaleX = 1f - 0.035f * progress
                scaleY = 1f - 0.035f * progress
                alpha = 1f - 0.08f * progress
            }
            .background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B))))
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                state.cancelTopicRequest()
                if (topic != null) {
                    topic = null
                    messages = emptyList()
                    busy = false
                    topicSearch = ""
                } else onBack()
            }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(topic?.name ?: "Video Library", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (topic == null) "HIGH-YIELD MEDIA" else "SUBJECT COLLECTION",
                    color = Color(0xFFB18CFF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
            if (topic == null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF78AFFF).copy(alpha = 0.13f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF78AFFF).copy(alpha = 0.30f))
                ) {
                    Text("EDITION ${activeEdition.name}", modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        color = Color(0xFF78AFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
            }
            if (busy) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF78AFFF))
            }
        }
        if (authState is TelegramAuthManager.State.Ready) {
            OutlinedTextField(
                value = topicSearch,
                onValueChange = { topicSearch = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text("Search", color = AppMuted)
                },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = AppMuted) },
                trailingIcon = {
                    if (topicSearch.isNotEmpty()) {
                        IconButton(onClick = { topicSearch = "" }) {
                            Icon(Icons.Default.Close, "Clear search", tint = AppMuted)
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF78AFFF).copy(alpha = 0.75f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                    focusedContainerColor = Color(0xAA172238),
                    unfocusedContainerColor = Color(0x88131D30),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF78AFFF)
                )
            )
        }
        AnimatedContent(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            targetState = topic,
            transitionSpec = {
                val enteringFromRight = targetState != null
                val motion = spring<Float>(dampingRatio = 0.88f, stiffness = 430f)
                val offsetMotion = spring<IntOffset>(dampingRatio = 0.90f, stiffness = 430f)
                (fadeIn(animationSpec = tween(170)) +
                    slideInHorizontally(animationSpec = offsetMotion) { width -> if (enteringFromRight) width / 7 else -width / 7 } +
                    scaleIn(initialScale = 0.975f, animationSpec = motion)) togetherWith
                    (fadeOut(animationSpec = tween(105)) +
                    slideOutHorizontally(animationSpec = offsetMotion) { width -> if (enteringFromRight) -width / 10 else width / 10 })
            },
            label = "subject_collection_transition"
        ) { targetTopic ->
            Column(Modifier.fillMaxSize()) {
        if (authState !is TelegramAuthManager.State.Ready) {
            Text("Content account is not connected.", color = AppMuted)
            Button(onClick = onConnect) { Text("Connect account") }
        } else if (targetTopic == null && setupMode) {
            Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Edition " + activeEdition.name, fontWeight = FontWeight.Bold)
                    Text(activeTitle ?: "No forum group assigned", color = AppMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            busy = true; error = null; status = null
                            repo?.discoverForumSources { result ->
                                busy = false
                                result.onSuccess { groups = it; status = "Found " + it.size + " forum group(s)." }.onFailure { error = it.message }
                            }
                        }) { Text("Find groups") }
                        OutlinedButton(enabled = !busy && activeTitle != null, onClick = {
                            busy = true; error = null
                            repo?.loadTopics(activeEdition) { result ->
                                busy = false
                                result.onSuccess { topics = it; status = "Loaded " + it.size + " subjects." }.onFailure { error = it.message }
                            }
                        }) { Text("Open subjects") }
                    }
                }
            }
            status?.let { Text(it, color = AppMuted, fontSize = 12.sp) }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            if (groups.isNotEmpty()) {
                Text("FORUM GROUPS", color = AppAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)) {
                    items(groups, key = { it.chatId }) { group ->
                        Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(group.title, Modifier.weight(1f))
                                TextButton(onClick = {
                                    runCatching { repo?.assignSource(activeEdition, group) }
                                        .onSuccess {
                                            when (activeEdition) {
                                                MedVaultEdition.M -> titleM = group.title
                                                MedVaultEdition.P -> titleP = group.title
                                                else -> titleU = group.title
                                            }
                                            status = "Assigned " + group.title
                                            error = null
                                        }.onFailure { error = it.message }
                                }) { Text("Assign") }
                            }
                        }
                    }
                }
            } else if (topics.isNotEmpty()) {
                Text("SUBJECTS", color = AppAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)) {
                    items(visibleTopics, key = { it.topicId }) { item ->
                        Card(Modifier.clickable {
                            openTopic(item)
                        }, colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Icon(Icons.Default.ChevronRight, null, tint = AppMuted)
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                Text("Configure your two video editions in Profile & Settings, then browse their subject topics.", color = AppMuted, fontSize = 13.sp)
            }
        } else if (targetTopic == null) {
            if (topicSearch.isNotBlank()) {
                val query = topicSearch.trim()
                val results = remember(editionSearchResults, activeEdition, query) {
                    editionSearchResults[activeEdition].orEmpty().filter { (_, lecture) ->
                        lecture.title.contains(query, ignoreCase = true) ||
                            lecture.fileName.contains(query, ignoreCase = true) ||
                            lecture.caption.contains(query, ignoreCase = true)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("VIDEOS", color = Color(0xFF9299AA), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (editionSearchLoading) "Searching…" else "${results.size} results", color = Color(0xFF9299AA), fontSize = 11.sp)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)) {
                    items(results, key = { it.second.stableId }) { (subjectName, lecture) ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onPlayLecture(lecture) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xB51A263B)),
                            shape = RoundedCornerShape(17.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color(0xFF78AFFF), modifier = Modifier.size(27.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(lecture.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(subjectName, color = Color(0xFF9299AA), fontSize = 10.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onDownloadLecture(lecture) }) {
                                    Icon(Icons.Default.Download, "Download", tint = Color(0xFF9299AA), modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                    if (results.isEmpty() && !editionSearchLoading) item {
                        Text("No videos match this search.", color = Color(0xFF9299AA), fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))
                    }
                    if (editionSearchLoading && results.isEmpty()) item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF78AFFF))
                            Spacer(Modifier.width(9.dp))
                            Text("Searching this edition…", color = Color(0xFF9299AA), fontSize = 12.sp)
                        }
                    }
                }
            } else {
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            if (repo?.selectedChatId(activeEdition) == null) {
                Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("No source configured for the selected edition.", fontWeight = FontWeight.SemiBold)
                        Text("Choose its group in Profile & Settings.", color = AppMuted, fontSize = 12.sp)
                        Button(onClick = onManageSources) { Text("Configure source") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SUBJECTS", color = Color(0xFF9299AA), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text("${visibleTopics.size} available", color = Color(0xFF9299AA), fontSize = 11.sp)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)) {
                items(visibleTopics, key = { it.topicId }) { item ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val cardScale by animateFloatAsState(
                        targetValue = if (pressed) 0.985f else 1f,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 700f),
                        label = "subject_card_press"
                    )
                    Card(
                        Modifier.fillMaxWidth()
                            .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
                            .clickable(interactionSource = interactionSource, indication = null) {
                                openTopic(item)
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xB51A263B)),
                        shape = RoundedCornerShape(21.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.085f))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)
                        ) {
                            Box(
                                Modifier.size(43.dp).background(Color(0xFF78AFFF).copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                    .border(1.dp, Color(0xFF78AFFF).copy(alpha = 0.20f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MenuBook, null, tint = Color(0xFF78AFFF), modifier = Modifier.size(21.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("OPEN SUBJECT", color = Color(0xFF9299AA), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9299AA), modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (visibleTopics.isEmpty() && topicSearch.isNotBlank()) {
                    item {
                        Text("No subjects match this search.", color = Color(0xFF9299AA), fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))
                    }
                }
            }
            }
        } else {
            val lectures = remember(targetTopic, messages) {
                targetTopic?.let { selected -> messages.mapNotNull { TelegramLecture.fromMessage(selected.chatId, selected.topicId, it) } }
                    .orEmpty().distinctBy { it.stableId }.sortedBy { it.messageId }
            }
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
            val filteredLectures = remember(lectures, topicSearch) {
                val query = topicSearch.trim()
                if (query.isEmpty()) lectures
                else lectures.filter { it.title.contains(query, ignoreCase = true) }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LECTURES", color = Color(0xFF9299AA), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text("${filteredLectures.size} loaded", color = Color(0xFF9299AA), fontSize = 11.sp)
            }
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)
            ) {
                items(filteredLectures, key = { it.stableId }, contentType = { "lecture" }) { lecture ->
                    val playedPositionMs = remember(lecture.stableId, progressRevision) {
                        context.getSharedPreferences("telegram_playback_local", android.content.Context.MODE_PRIVATE).getLong(lecture.stableId, 0L)
                    }
                    val progressFraction = if (lecture.durationSeconds > 0) (playedPositionMs.toFloat() / (lecture.durationSeconds * 1000f)).coerceIn(0f, 1f) else 0f
                    val displayTitle = remember(lecture.title) { lecture.title.replace(Regex("\\.(mp4|mkv|webm|mov)$", RegexOption.IGNORE_CASE), "") }
                    var thumbnailPath by remember(lecture.thumbnailFileId) { mutableStateOf<String?>(null) }
                    LaunchedEffect(lecture.thumbnailFileId) {
                        val thumbnailId = lecture.thumbnailFileId ?: return@LaunchedEffect
                        val auth = TelegramAuthManager.getInstance(context) { }
                        auth.sendRequest(TdApi.DownloadFile(thumbnailId, 16, 0L, 0L, true)) { result ->
                            val localPath = (result as? TdApi.File)?.local?.path
                            if (!localPath.isNullOrBlank() && File(localPath).isFile) thumbnailPath = localPath
                        }
                    }
                    val offlineCopy = remember(downloadedLectures, lecture.stableId) {
                        downloadedLectures.firstOrNull { it.source == "telegram" && it.fileId == lecture.stableId }
                            ?.takeIf { File(it.localPath).isFile }
                    }
                    val downloadWork = remember(downloadWorkItems, lecture.stableId) {
                        downloadWorkItems.firstOrNull {
                            "medvault_telegram_id_" + lecture.stableId in it.tags
                        }
                    }
                    val downloadProgress = downloadWork?.progress?.getInt("progress", 0)?.coerceIn(0, 100) ?: 0
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Column(
                        Modifier.weight(1f).clickable { onPlayLecture(lecture) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                Modifier.width(108.dp).aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Color(0xFF17243A))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(11.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumbnailPath != null) {
                                    AsyncImage(
                                        model = File(thumbnailPath!!),
                                        contentDescription = displayTitle,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp))
                                    )
                                } else {
                                    Icon(Icons.Default.MenuBook, null, tint = Color(0xFF78AFFF), modifier = Modifier.size(27.dp))
                                }
                                Box(
                                    Modifier.align(Alignment.Center).size(29.dp)
                                        .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PlayArrow, "Play lecture", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                if (progressFraction > 0f) {
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.20f))) {
                                        Box(Modifier.fillMaxWidth(progressFraction).fillMaxHeight().background(Color(0xFF78AFFF)))
                                    }
                                }
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Text(
                                    displayTitle,
                                    color = Color(0xFFF5F6FA),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (lecture.durationSeconds > 0) {
                                    val hours = lecture.durationSeconds / 3600
                                    val minutes = (lecture.durationSeconds % 3600) / 60
                                    val seconds = lecture.durationSeconds % 60
                                    val durationLabel = if (hours > 0) "${hours}h ${minutes}m" else if (minutes > 0) "${minutes}m ${seconds.toString().padStart(2, '0')}s" else "${seconds}s"
                                    Text(
                                        durationLabel,
                                        color = Color(0xFF9299AA),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text("Video lecture", color = Color(0xFF9299AA), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (offlineCopy != null) {
                        IconButton(
                            onClick = { pendingDeleteLecture = offlineCopy },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, "Delete offline copy", tint = Color(0xFFFF8A80))
                        }
                    }
                    IconButton(
                        onClick = { if (offlineCopy == null && downloadWork?.state != WorkInfo.State.RUNNING && downloadWork?.state != WorkInfo.State.ENQUEUED && downloadWork?.state != WorkInfo.State.BLOCKED) onDownloadLecture(lecture) },
                        enabled = offlineCopy == null && downloadWork?.state != WorkInfo.State.RUNNING && downloadWork?.state != WorkInfo.State.ENQUEUED && downloadWork?.state != WorkInfo.State.BLOCKED,
                        modifier = Modifier.size(42.dp)
                            .background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(50))
                            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(50))
                    ) {
                        when {
                            offlineCopy != null -> Icon(Icons.Default.CheckCircle, "Available offline", tint = Color(0xFF63D6B0))
                            downloadWork?.state == WorkInfo.State.RUNNING || downloadWork?.state == WorkInfo.State.ENQUEUED -> {
                                androidx.compose.material3.CircularProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier.size(21.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF78AFFF),
                                    trackColor = Color.White.copy(alpha = 0.10f)
                                )
                            }
                            downloadWork?.state == WorkInfo.State.FAILED -> Icon(Icons.Default.Refresh, "Retry download", tint = Color(0xFFFFA66B))
                            else -> Icon(Icons.Default.Download, "Download for offline", tint = Color.White.copy(alpha = 0.72f))
                        }
                    }
                    }
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(start = 134.dp),
                        color = Color.White.copy(alpha = 0.075f),
                        thickness = 0.7.dp
                    )
                }
                if (filteredLectures.isEmpty() && !busy) {
                    item {
                        Text(
                            if (topicSearch.isNotBlank()) "No lectures match this search."
                            else "No video lectures are available in this subject yet.",
                            color = Color(0xFF9299AA),
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }
                }
                if (filteredLectures.isEmpty() && busy) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFFB18CFF))
                            Spacer(Modifier.width(10.dp))
                            Text("Loading lectures…", color = Color(0xFF9299AA), fontSize = 13.sp)
                        }
                    }
                }
            }

        }
            }
        }
    }
    pendingDeleteLecture?.let { lecture ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLecture = null },
            title = { Text("Delete offline copy?") },
            text = { Text(lecture.name) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteOffline(lecture)
                    pendingDeleteLecture = null
                }) { Text("Delete", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLecture = null }) { Text("Cancel") }
            }
        )
    }
}

// =============================================================
// VIDEOS SUBJECT DASHBOARD
// =============================================================

@Composable
private fun VideosDashboard(
    subjectFolders: List<DriveItem>,
    loading: Boolean,
    status: String,
    history: List<WatchRecord>,
    downloadedCount: Int,
    connected: Boolean,
    activeAccountEmail: String,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenSubject: (DriveItem) -> Unit,
    onRawDrive: () -> Unit,
    onDownloads: () -> Unit,
    onContinue: () -> Unit,
    onHistory: () -> Unit,
    onTelegram: () -> Unit,
    onSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val subjects = remember(subjectFolders) {
        subjectFolders.sortedBy { it.name.lowercase() }
    }
    val lightBg = Color(0xFFF4F5F5)
    val cyan = Color(0xFF62C5D5)
    val ink = Color(0xFF62686A)
    val muted = Color(0xFF858B8D)

    Column(
        Modifier
            .fillMaxSize()
            .background(lightBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cyan)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(27.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "MedVault Videos",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    "Version 1.0 by Aftab",
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
            }
            IconButton(onClick = { searchExpanded = !searchExpanded }) {
                Icon(Icons.Default.Search, "Search", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        if (searchExpanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(14.dp)
                )
                IconButton(
                    onClick = { if (query.isNotBlank()) onSearch(query.trim()) },
                    enabled = query.isNotBlank()
                ) {
                    Icon(Icons.Default.ArrowForward, "Search")
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDownloads,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ink)
                ) {
                    Icon(Icons.Default.CloudDownload, null, tint = cyan)
                    Spacer(Modifier.width(7.dp))
                    Text("Downloaded ($downloadedCount)")
                }
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ink)
                ) {
                    Icon(Icons.Default.VideoLibrary, null, tint = cyan)
                    Spacer(Modifier.width(7.dp))
                    Text("Continue")
                }
            }

            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SUBJECTS", color = muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
             }

            when {
                loading && subjects.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cyan)
                    }
                }
                subjects.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudOff, null, tint = muted, modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(status, color = ink)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        gridItems(subjects, key = { it.id }) { subject ->
                            val icon = when {
                                subject.isSharedDriveRoot -> Icons.Default.Cloud
                                subject.name.contains("anatom", true) -> Icons.Default.AccessibilityNew
                                subject.name.contains("biochem", true) -> Icons.Default.Science
                                subject.name.contains("physio", true) -> Icons.Default.Favorite
                                subject.name.contains("pharma", true) -> Icons.Default.Medication
                                subject.name.contains("micro", true) -> Icons.Default.Biotech
                                subject.name.contains("path", true) -> Icons.Default.BubbleChart
                                subject.name.contains("medicine", true) -> Icons.Default.MedicalServices
                                subject.name.contains("surgery", true) -> Icons.Default.MedicalInformation
                                subject.name.contains("ophthalm", true) -> Icons.Default.VideoLibrary
                                subject.name.contains("ent", true) -> Icons.Default.VideoLibrary
                                subject.name.contains("psychi", true) -> Icons.Default.Favorite
                                subject.name.contains("radiol", true) -> Icons.Default.Favorite
                                else -> Icons.Default.VideoLibrary
                            }
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + scaleIn(initialScale = 0.94f)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize()
                                        .clickable { onOpenSubject(subject) },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                                ) {
                                    Column {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(112.dp)
                                            .background(Color(0xFFE5E7E7)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            Modifier
                                                .size(76.dp)
                                                .background(Color(0xFFEFFAFF), RoundedCornerShape(50)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(icon, null, tint = cyan, modifier = Modifier.size(38.dp))
                                        }
                                    }
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            subject.name,
                                            color = ink,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp,
                                            maxLines = 2,
                                            minLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(5.dp))
                                        Text(if (subject.isSharedDriveRoot) "Open Shared Drive" else "Open subject", color = muted, fontSize = 12.sp)
                                        Spacer(Modifier.height(10.dp))
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .background(Color(0xFFEAF5F7), RoundedCornerShape(5.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        NavigationBar(
            containerColor = Color.White,
            contentColor = cyan,
            tonalElevation = 8.dp
        ) {
            NavigationBarItem(
                selected = true,
                onClick = { },
                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                label = { Text("Videos") },
                alwaysShowLabel = true
            )
            NavigationBarItem(
                selected = false,
                onClick = onTelegram,
                icon = { Icon(Icons.Default.Send, contentDescription = null) },
                label = { Text("Content account") },
                alwaysShowLabel = true
            )
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
    onSettings: () -> Unit,
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

            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = AppAccent, modifier = Modifier.size(30.dp))
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
                        else -> "Connect a library to browse lectures."
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
    onGlobalSearch: (String) -> Unit,
    onFolder: (DriveItem) -> Unit,
    onFile: (DriveItem) -> Unit,
    onDownload: (DriveItem) -> Unit,
    onHome: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val lightBg = Color(0xFFF4F5F5)
    val cyan = Color(0xFF62C5D5)
    val ink = Color(0xFF62686A)
    val muted = Color(0xFF858B8D)

    Column(
        Modifier
            .fillMaxSize()
            .background(lightBg)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onHome) {
                Icon(Icons.Default.Home, "Videos home", tint = cyan)
            }
            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Parent folder", tint = cyan)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    folderName,
                    color = ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Version 1.0 by Aftab",
                    color = muted,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Default.Refresh, "Refresh", tint = cyan)
            }
        }

        if (loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = cyan
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
            placeholder = { Text("Search") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = muted)
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            onRefresh()
                        }) {
                            Icon(Icons.Default.Close, "Clear search", tint = muted)
                        }
                    }
                    IconButton(
                        onClick = { if (searchQuery.isNotBlank()) onGlobalSearch(searchQuery.trim()) },
                        enabled = searchQuery.isNotBlank() && !loading
                    ) {
                        Icon(Icons.Default.Search, "Search all Google Drive", tint = cyan)
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ink,
                unfocusedTextColor = ink,
                focusedBorderColor = cyan,
                unfocusedBorderColor = Color(0xFFB8BEC0),
                focusedPlaceholderColor = muted,
                unfocusedPlaceholderColor = muted
            )
        )

        Text(
            if (folderName == "Drive search") "${items.size} results across Drive"
            else "${items.size} items in this folder",
            color = muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp)
        )

        if (!loading && items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOff, null, tint = muted, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (folderName == "Drive search") "No matching files found across Google Drive." else "No items found.",
                        color = ink
                    )
                    Text(status, color = muted, fontSize = 12.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                gridItems(items, key = { it.id }) { item ->
                    val folder = item.mimeType == FOLDER_MIME
                    val isVideo = !folder && (
                        item.mimeType.startsWith("video/") ||
                            listOf(".mp4", ".mkv", ".webm", ".mov").any {
                                item.name.endsWith(it, true)
                            }
                        )
                    val tileIcon = when {
                        folder -> Icons.Default.Folder
                        isVideo -> Icons.Default.VideoLibrary
                        item.mimeType.contains("pdf", true) -> Icons.Default.PictureAsPdf
                        item.mimeType.contains("audio", true) -> Icons.Default.AudioFile
                        else -> Icons.Default.InsertDriveFile
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .clickable(enabled = !loading) {
                                if (folder) onFolder(item) else onFile(item)
                            },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(112.dp)
                                    .background(Color(0xFFE5E7E7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .size(76.dp)
                                        .background(Color(0xFFEFFAFF), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(tileIcon, null, tint = cyan, modifier = Modifier.size(40.dp))
                                }
                            }
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    item.name,
                                    color = ink,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 2,
                                    minLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    when {
                                        folder -> "Open folder"
                                        item.size != null -> "${if (isVideo) "Video" else "File"} · ${formatDriveSize(item.size)}"
                                        isVideo -> "Video lecture"
                                        else -> "Open file"
                                    },
                                    color = muted,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(5.dp)
                                            .background(Color(0xFFEAF5F7), RoundedCornerShape(5.dp))
                                    )
                                    if (isVideo) {
                                        IconButton(
                                            onClick = { onDownload(item) },
                                            enabled = !loading,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = "Download ${item.name}",
                                                tint = cyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDriveSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
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
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var playbackError by remember(player) { mutableStateOf<androidx.media3.common.PlaybackException?>(null) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(player) { mutableLongStateOf(player.duration.takeIf { it > 0L } ?: 0L) }
    var isSeeking by remember(player) { mutableStateOf(false) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.takeIf { it > 0L } ?: 0L
                if (state == Player.STATE_READY) playbackError = null
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = error
            }
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                speed = playbackParameters.speed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        var lastSaved = 0L
        while (true) {
            if (!isSeeking) position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            if (!isSeeking && duration > 0L && position > 0L &&
                System.currentTimeMillis() - lastSaved >= 5000L) {
                onProgress(position, duration)
                lastSaved = System.currentTimeMillis()
            }
            kotlinx.coroutines.delay(350)
        }
    }

    LaunchedEffect(controlsVisible, playing, isSeeking, settingsExpanded, fullscreen) {
        if (controlsVisible && playing && !isSeeking && !settingsExpanded) {
            kotlinx.coroutines.delay(3200)
            controlsVisible = false
        }
    }

    if (pipMode) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize().background(Color.Black)
        )
        return
    }

    val glassShape = RoundedCornerShape(50)
    val glassBorder = Brush.verticalGradient(
        listOf(Color(0x88FFFFFF), Color(0x35FFFFFF), Color(0x18FFFFFF))
    )

    Column(
        Modifier.fillMaxSize().background(if (fullscreen) Color.Black else AppBg)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.player = player
                    view.useController = false
                },
                modifier = Modifier.fillMaxSize()
            )

            // The video remains edge-to-edge. Only the controls respect the
            // physical display cutout and are positioned inside the safe area.
            Box(
                Modifier.fillMaxSize().pointerInput(duration) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            settingsExpanded = false
                        },
                        onDoubleTap = { offset ->
                            val target = if (offset.x < size.width / 2f) {
                                (player.currentPosition - 10_000L).coerceAtLeast(0L)
                            } else {
                                val next = player.currentPosition + 10_000L
                                if (duration > 0L) next.coerceAtMost(duration) else next
                            }
                            player.seekTo(target)
                            controlsVisible = true
                        }
                    )
                }
            )

            if (playbackError != null) {
                Card(
                    Modifier.align(Alignment.Center).padding(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xF01A2233))
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Playback interrupted", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Check the connection and retry from your saved position.",
                            color = Color(0xFFB8C0D0), fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            player.seekTo(position.coerceAtLeast(0L))
                            player.prepare()
                            player.playWhenReady = true
                            playbackError = null
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
            }

            PlayerControlsVisibility(visible = controlsVisible) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().then(
                        if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                        else Modifier
                    )
                ) {
                    val screenWidth = maxWidth
                    val screenHeight = maxHeight

                    if (fullscreen) {
                        // iOS-inspired floating controls: independent glass elements,
                        // measured in dp and inset from the physical display edges.
                        val safeInset = 24.dp
                        val compact = screenWidth < 600.dp
                        val clusterGap = 20.dp
                        val centerButton = 64.dp
                        val skipButton = 44.dp
                        val centerClusterWidth = centerButton + skipButton * 2 + clusterGap * 2

                        // Top-left: dismiss plus a compact functional PiP capsule.
                        Row(
                            modifier = Modifier.align(Alignment.TopStart)
                                .padding(start = safeInset, top = safeInset),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PlayerGlassCircleButton(
                                icon = Icons.Default.Close,
                                description = "Close player",
                                diameter = 40.dp,
                                iconSize = 21.dp,
                                onClick = onClose
                            )
                            PlayerGlassCapsule(
                                modifier = Modifier.width(48.dp).height(40.dp)
                            ) {
                                GlassPlayerButton(Icons.Default.PictureInPictureAlt, "Picture in picture", onPip)
                            }
                        }

                        // Three independent circles. No oversized shared transport capsule.
                        Row(
                            modifier = Modifier.align(Alignment.Center).width(centerClusterWidth),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(clusterGap)
                        ) {
                            PlayerGlassCircleButton(
                                icon = Icons.Default.Replay10,
                                description = "Back 10 seconds",
                                diameter = skipButton,
                                iconSize = 25.dp
                            ) {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                controlsVisible = true
                            }
                            PlayerGlassCircleButton(
                                icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                description = if (playing) "Pause" else "Play",
                                diameter = centerButton,
                                iconSize = 31.dp,
                                stronger = true
                            ) {
                                if (player.isPlaying) player.pause() else player.play()
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            PlayerGlassCircleButton(
                                icon = Icons.Default.Forward10,
                                description = "Forward 10 seconds",
                                diameter = skipButton,
                                iconSize = 25.dp
                            ) {
                                val target = player.currentPosition + 10_000L
                                player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                controlsVisible = true
                            }
                        }

                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(154.dp)
                                .background(Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0x66000000), Color(0xD9000000))
                                ))
                        )

                        // Lower-left title and custom 4 dp timeline.
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart)
                                .padding(start = safeInset, end = safeInset, bottom = safeInset)
                                .fillMaxWidth()
                        ) {
                            Text(
                                "MEDVAULT  •  NOW PLAYING",
                                color = Color.White.copy(alpha = 0.76f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.15.sp,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                file.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(position), color = Color.White, fontSize = 12.sp)
                                PlayerTimeline(
                                    position = position,
                                    duration = duration,
                                    onSeekChange = {
                                        isSeeking = true
                                        position = it
                                    },
                                    onSeekFinished = {
                                        player.seekTo(it.coerceIn(0L, duration.coerceAtLeast(0L)))
                                        position = it
                                        isSeeking = false
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    },
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                                )
                                Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                            }
                        }

                        // Bottom-right control cluster: speed, PiP and fullscreen.
                        PlayerGlassCapsule(
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = safeInset, bottom = screenHeight * 0.205f)
                                .height(40.dp)
                        ) {
                            Box {
                                PlayerGlassAction(label = "${speed}×", onClick = {
                                    settingsExpanded = !settingsExpanded
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                })
                            }
                            GlassPlayerButton(Icons.Default.FullscreenExit, "Exit fullscreen", onFullscreen)
                        }

                        // Render the anchored speed menu only while open. Avoid the
                        // ColumnScope AnimatedVisibility overload, which is incompatible
                        // with this BoxWithConstraints receiver in the release compiler.
                        if (settingsExpanded) {
                            Column(
                                Modifier.align(Alignment.BottomEnd)
                                    .padding(end = safeInset, bottom = screenHeight * 0.205f + 48.dp)
                                    .width(136.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xE83A4050), Color(0xED202635), Color(0xF5151A25))
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x22FFFFFF))),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(5.dp)
                            ) {
                                listOf(0.5f, 1f, 1.5f, 2f).forEach { rate ->
                                    val selected = speed == rate
                                    Row(
                                        Modifier.fillMaxWidth().height(34.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) Color(0x22FFFFFF) else Color.Transparent)
                                            .clickable {
                                                speed = rate
                                                player.setPlaybackSpeed(rate)
                                                settingsExpanded = false
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            when (rate) {
                                                0.5f -> "0.5×"
                                                1f -> "1.0×"
                                                1.5f -> "1.5×"
                                                else -> "2.0×"
                                            },
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color(0xFFB49AFF),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                        }
                    } else {
                        // Portrait title is presented as a floating glass pill;
                        // the old text-heavy app bar and its gear/fullscreen buttons are removed.
                        PlayerGlassCapsule(
                            modifier = Modifier.align(Alignment.TopStart)
                                .padding(start = 12.dp, top = 10.dp)
                                .widthIn(max = screenWidth * 0.82f)
                                .height(44.dp)
                        ) {
                            GlassPlayerButton(Icons.Default.ArrowBack, "Back", onClose)
                            Text(
                                file.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 14.dp)
                            )
                        }

                        // Compact central transport controls.
                        PlayerGlassCapsule(
                            modifier = Modifier.align(Alignment.Center)
                                .width(174.dp).height(50.dp),
                            stronger = true
                        ) {
                            IconButton(
                                onClick = {
                                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                    controlsVisible = true
                                },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Default.Replay10, "Back 10 seconds",
                                    tint = Color.White, modifier = Modifier.size(21.dp))
                            }
                            IconButton(
                                onClick = { if (player.isPlaying) player.pause() else player.play() },
                                modifier = Modifier.size(46.dp)
                            ) {
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(31.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val target = player.currentPosition + 10_000L
                                    player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                                    controlsVisible = true
                                },
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Default.Forward10, "Forward 10 seconds",
                                    tint = Color.White, modifier = Modifier.size(21.dp))
                            }
                        }
                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .background(Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xB9000000), Color(0xE6000000))
                                ))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                file.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(position), color = Color.White, fontSize = 10.sp)
                                PlayerTimeline(
                                    position = position,
                                    duration = duration,
                                    onSeekChange = {
                                        isSeeking = true
                                        position = it
                                    },
                                    onSeekFinished = {
                                        player.seekTo(it.coerceIn(0L, duration.coerceAtLeast(0L)))
                                        position = it
                                        isSeeking = false
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    },
                                    modifier = Modifier.weight(1f).padding(horizontal = 5.dp)
                                )
                                Text(formatTime(duration), color = Color.White, fontSize = 10.sp)
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                PlayerGlassAction("${speed}×") {
                                    settingsExpanded = !settingsExpanded
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                PlayerGlassIconAction(Icons.Default.PictureInPictureAlt, "Picture in picture", onPip)
                                PlayerGlassIconAction(Icons.Default.Fullscreen, "Enter fullscreen", onFullscreen)
                            }
                        }

                        // Same compact glass speed selector in portrait and landscape.
                        if (settingsExpanded) {
                            Column(
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = 12.dp, bottom = 52.dp)
                                    .width(136.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.verticalGradient(
                                        listOf(Color(0xE83A4050), Color(0xED202635), Color(0xF5151A25))
                                    ))
                                    .border(
                                        1.dp,
                                        Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x22FFFFFF))),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .padding(5.dp)
                            ) {
                                listOf(0.5f, 1f, 1.5f, 2f).forEach { rate ->
                                    val selected = speed == rate
                                    Row(
                                        Modifier.fillMaxWidth().height(34.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) Color(0x22FFFFFF) else Color.Transparent)
                                            .clickable {
                                                speed = rate
                                                player.setPlaybackSpeed(rate)
                                                settingsExpanded = false
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            when (rate) {
                                                0.5f -> "0.5×"
                                                1f -> "1.0×"
                                                1.5f -> "1.5×"
                                                else -> "2.0×"
                                            },
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selected) Icon(
                                            Icons.Default.Check, "Selected",
                                            tint = Color(0xFFB49AFF), modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!fullscreen) {
            Text(
                "Playback position saves automatically",
                color = AppMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun PlayerTimeline(
    position: Long,
    duration: Long,
    onSeekChange: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(position) }
    val currentDuration = duration.coerceAtLeast(1L)

    Canvas(
        modifier = modifier.height(28.dp)
            .pointerInput(currentDuration) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    val target = (fraction * currentDuration).toLong()
                    onSeekChange(target)
                    onSeekFinished(target)
                }
            }
            .pointerInput(currentDuration) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        dragPosition = (fraction * currentDuration).toLong()
                        onSeekChange(dragPosition)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeekFinished(dragPosition)
                    },
                    onDragCancel = {
                        dragging = false
                        onSeekFinished(dragPosition)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragPosition = (fraction * currentDuration).toLong()
                        onSeekChange(dragPosition)
                    }
                )
            }
    ) {
        val fraction = (if (dragging) dragPosition else position).toFloat()
            .div(currentDuration.toFloat()).coerceIn(0f, 1f)
        val trackHeight = if (dragging) 8.dp.toPx() else 4.dp.toPx()
        val top = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val progressWidth = size.width * fraction

        // Dark glass rail remains visible over white lecture slides; a subtle
        // light rim keeps it defined over dark footage.
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.48f),
            topLeft = Offset(0f, top - 1.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight + 2.dp.toPx()),
            cornerRadius = CornerRadius((trackHeight + 2.dp.toPx()) / 2f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.34f),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = radius
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
        if (progressWidth > 0f) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.22f),
                topLeft = Offset(0f, top - 1.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(progressWidth, trackHeight + 2.dp.toPx()),
                cornerRadius = CornerRadius((trackHeight + 2.dp.toPx()) / 2f)
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(progressWidth, trackHeight),
                cornerRadius = radius
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.28f),
                radius = (if (dragging) 5.5.dp else 3.dp).toPx(),
                center = Offset(progressWidth.coerceIn(0f, size.width), size.height / 2f)
            )
            drawCircle(
                color = Color.White,
                radius = if (dragging) 5.dp.toPx() else 2.5.dp.toPx(),
                center = Offset(progressWidth.coerceIn(0f, size.width), size.height / 2f)
            )
        }
    }
}

@Composable
private fun PlayerVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val fraction = value.coerceIn(0f, 1f)
        val trackHeight = 4.dp.toPx()
        val top = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.36f),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = radius
        )
        if (fraction > 0f) {
            val progressWidth = size.width * fraction
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(progressWidth, trackHeight),
                cornerRadius = radius
            )
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(progressWidth.coerceIn(0f, size.width), size.height / 2f)
            )
        }
    }
}

@Composable
private fun PlayerGlassCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    diameter: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    stronger: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 720f),
        label = "glassButtonScale"
    )
    val haptics = LocalHapticFeedback.current
    val fill = if (stronger) {
        Brush.verticalGradient(listOf(Color(0x7AFFFFFF), Color(0x50313B4B), Color(0xA51A2233)))
    } else {
        Brush.verticalGradient(listOf(Color(0x60FFFFFF), Color(0x3D252D3B), Color(0xA51A2233)))
    }

    Box(
        Modifier.size(diameter)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(1.dp, Brush.verticalGradient(
                listOf(Color(0xAFFFFFFF), Color(0x44FFFFFF), Color(0x22FFFFFF))
            ), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun PlayerControlsVisibility(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)) +
            scaleIn(initialScale = 0.985f, animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f)),
        exit = fadeOut(animationSpec = tween(240)),
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

@Composable
private fun PlayerGlassCapsule(
    modifier: Modifier = Modifier,
    stronger: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(50)
    val surfaceBrush = if (stronger) {
        Brush.verticalGradient(listOf(Color(0x72FFFFFF), Color(0x66232B3A), Color(0xA51A2233)))
    } else {
        Brush.verticalGradient(listOf(Color(0x58FFFFFF), Color(0x49232B3A), Color(0x98151D2B)))
    }
    Row(
        modifier
            .graphicsLayer {
                shadowElevation = 16.dp.toPx()
                this.shape = shape
                clip = true
            }
            .background(surfaceBrush, shape)
            .border(1.dp, Brush.verticalGradient(
                listOf(Color(0x99FFFFFF), Color(0x42FFFFFF), Color(0x20FFFFFF))
            ), shape)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun PlayerGlassAction(
    label: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 720f),
        label = "glassPillScale"
    )
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x421B2433), Color(0x88151D2B))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x88FFFFFF), Color(0x33FFFFFF))), shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .height(34.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlayerGlassIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(42.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x421B2433), Color(0x88151D2B))),
                shape
            )
            .border(1.dp, Color(0x55FFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GlassPlayerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 720f),
        label = "glassIconScale"
    )
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        interactionSource = interaction,
        modifier = Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(19.dp))
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
    val context = LocalContext.current
    val mark = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.medvault_emblem_freeform).asImageBitmap()
    }
    val iconReveal = remember { Animatable(0f) }
    val contentReveal = remember { Animatable(0f) }
    val ambient = rememberInfiniteTransition(label = "medvault-premium-ambient")
    val ambientPulse by ambient.animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient-pulse"
    )
    val logo = iconReveal.value
    val content = contentReveal.value

    LaunchedEffect(Unit) {
        iconReveal.animateTo(
            1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.86f,
                stiffness = 115f
            )
        )
        kotlinx.coroutines.delay(180)
        contentReveal.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = 760,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFF020611),
                        0.34f to Color(0xFF07172D),
                        0.68f to Color(0xFF061329),
                        1f to Color(0xFF02050D)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // A single restrained halo ties the emblem into the background.
        Canvas(Modifier.fillMaxSize()) {
            val glowCenter = Offset(size.width * 0.5f, size.height * 0.405f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1C69B5).copy(alpha = 0.16f * ambientPulse),
                        Color(0xFF0A2A50).copy(alpha = 0.07f * ambientPulse),
                        Color.Transparent
                    ),
                    center = glowCenter,
                    radius = size.width * 0.68f
                ),
                radius = size.width * 0.68f,
                center = glowCenter
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
        ) {
            // The emblem is presented directly on the background, not inside another app-icon tile.
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .graphicsLayer {
                        alpha = logo
                        val scale = 0.94f + 0.06f * logo
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - logo) * 14.dp.toPx()
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(172.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0x443D9FFF).copy(alpha = ambientPulse),
                                    Color(0x18236BC0).copy(alpha = ambientPulse),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )
                Image(
                    bitmap = mark,
                    contentDescription = "MedVault emblem",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 166.dp, height = 182.dp)
                )
            }

            Spacer(Modifier.height(30.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = content
                    translationY = (1f - content) * 12.dp.toPx()
                }
            ) {
                Text(
                    "Med",
                    color = Color(0xFFF5F8FF),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-2.0).sp
                )
                Text(
                    "Vault",
                    color = Color(0xFF69BEFF),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-2.0).sp
                )
            }

            Spacer(Modifier.height(9.dp))
            Text(
                "YOUR MEDICAL LEARNING LIBRARY",
                color = Color(0xFFC7D9EE).copy(alpha = content * 0.88f),
                fontSize = 9.sp,
                letterSpacing = 2.45.sp
            )

            Spacer(Modifier.height(48.dp))
            Box(
                Modifier
                    .width(116.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF17385B))
                    .graphicsLayer { alpha = content }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(content)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF238FFF),
                                    Color(0xFFDDF6FF),
                                    Color(0xFF55B8FF)
                                )
                            )
                        )
                )
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "Made with ❤️ by Aftab",
                color = Color(0xFFE7F1FF).copy(alpha = content),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.15.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = content
                    translationY = (1f - content) * 7.dp.toPx()
                }
            )
        }
    }
}
