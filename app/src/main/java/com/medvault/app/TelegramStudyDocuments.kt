package com.medvault.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private val StudyIo = Executors.newSingleThreadExecutor()
private val StudyMain = Handler(Looper.getMainLooper())
private val StudyBg = Color(0xFF080B12)
private val StudyPanel = Color(0xFF171D30)
private val StudyAccent = Color(0xFF78AFFF)
private val StudyMuted = Color(0xFF9299AA)

data class TelegramPdfDocument(
    val stableId: String,
    val chatId: Long,
    val topicId: Int,
    val messageId: Long,
    val title: String,
    val fileName: String,
    val caption: String,
    val fileId: Int,
    val fileSizeBytes: Long,
    val messageDate: Int
) {
    companion object {
        fun fromMessage(chatId: Long, topicId: Int, message: TdApi.Message): TelegramPdfDocument? {
            val content = message.content as? TdApi.MessageDocument ?: return null
            val document = content.document
            val fileName = document.fileName.orEmpty().ifBlank { "document_${message.id}.pdf" }
            val mime = document.mimeType.orEmpty()
            if (!mime.equals("application/pdf", true) &&
                !fileName.substringBefore('?').endsWith(".pdf", true)) return null
            val caption = content.caption.text
            val title = caption.lineSequence().firstOrNull()?.trim().orEmpty()
                .ifBlank { fileName.substringAfterLast('/') }
            return TelegramPdfDocument(
                stableId = "$chatId:$topicId:${message.id}",
                chatId = chatId,
                topicId = topicId,
                messageId = message.id,
                title = title,
                fileName = fileName,
                caption = caption,
                fileId = document.document.id,
                fileSizeBytes = document.document.size.coerceAtLeast(0L),
                messageDate = message.date
            )
        }
    }
}

object TelegramPdfStore {
    private fun directory(context: Context, edition: MedVaultEdition, topicId: Int): File =
        File(context.filesDir, "study-pdfs/${edition.name.lowercase()}/$topicId").apply { mkdirs() }

    fun localFile(context: Context, edition: MedVaultEdition, document: TelegramPdfDocument): File =
        File(directory(context, edition, document.topicId), "${document.messageId}.pdf")

    fun downloaded(context: Context): List<File> =
        File(context.filesDir, "study-pdfs").walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .sortedByDescending { it.lastModified() }.toList()

    fun download(
        context: Context,
        auth: TelegramAuthManager,
        edition: MedVaultEdition,
        document: TelegramPdfDocument,
        callback: (Result<File>) -> Unit
    ) {
        val target = localFile(context, edition, document)
        if (target.isFile && target.length() > 0L) {
            StudyMain.post { callback(Result.success(target)) }
            return
        }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var updateListener: (TdApi.Object) -> Unit
        fun finish(result: Result<File>) {
            if (!completed.compareAndSet(false, true)) return
            auth.removeUpdateListener(updateListener)
            StudyMain.post { callback(result) }
        }
        fun copyCompletedFile(file: TdApi.File) {
            if (!file.local.isDownloadingCompleted) return
            StudyIo.execute {
                try {
                    val source = File(file.local.path)
                    if (!source.isFile || source.length() == 0L) throw IllegalStateException("Telegram marked the PDF complete, but its local file is missing.")
                    target.parentFile?.mkdirs()
                    source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                    if (target.length() == 0L) throw IllegalStateException("Downloaded PDF is empty.")
                    finish(Result.success(target))
                } catch (error: Exception) {
                    target.delete()
                    finish(Result.failure(error))
                }
            }
        }
        updateListener = listener@{ update ->
            val fileUpdate = update as? TdApi.UpdateFile ?: return@listener
            if (fileUpdate.file.id == document.fileId && fileUpdate.file.local.isDownloadingCompleted) copyCompletedFile(fileUpdate.file)
        }
        auth.addUpdateListener(updateListener)
        auth.sendRequest(TdApi.DownloadFile(document.fileId, 32, 0L, 0L, true)) { result ->
            when (result) {
                is TdApi.File -> copyCompletedFile(result)
                is TdApi.Error -> finish(Result.failure(IllegalStateException(result.message)))
                else -> finish(Result.failure(IllegalStateException("Telegram returned an unexpected download response.")))
            }
        }
    }
}

@Composable
fun TelegramStudySourcesSettingsScreen(onBack: () -> Unit, onConnectAccount: () -> Unit) {
    val context = LocalContext.current
    var authState by remember { mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting) }
    var repo by remember { mutableStateOf<TelegramLibraryRepository?>(null) }
    var groups by remember { mutableStateOf<List<TelegramSourceCandidate>>(emptyList()) }
    var target by remember { mutableStateOf(MedVaultEdition.M) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val roles = listOf(MedVaultEdition.M, MedVaultEdition.P, MedVaultEdition.U, MedVaultEdition.QBANK_M, MedVaultEdition.QBANK_P, MedVaultEdition.QBANK_U, MedVaultEdition.NOTES_M, MedVaultEdition.NOTES_P, MedVaultEdition.NOTES_U)
    fun label(role: MedVaultEdition) = when (role) {
        MedVaultEdition.M -> "Video · Edition M"
        MedVaultEdition.P -> "Video · Edition P"
        MedVaultEdition.U -> "Video · Edition U"
        MedVaultEdition.QBANK_M -> "QBank · Edition M"
        MedVaultEdition.QBANK_P -> "QBank · Edition P"
        MedVaultEdition.QBANK_U -> "QBank · Edition U"
        MedVaultEdition.NOTES_M -> "Notes · Edition M"
        MedVaultEdition.NOTES_P -> "Notes · Edition P"
        else -> "Notes · Edition U"
    }
    LaunchedEffect(Unit) {
        val auth = TelegramAuthManager.getInstance(context) { authState = it }
        authState = auth.currentState
        auth.start()
        repo = TelegramLibraryRepository(context, auth)
    }
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Study library sources", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Independent Telegram groups for Video, QBank and Notes", color = StudyMuted, fontSize = 12.sp)
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        if (authState !is TelegramAuthManager.State.Ready) {
            Text("Connect your content account before selecting source groups.", color = StudyMuted)
            Button(onClick = onConnectAccount) { Text("Connect account") }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(roles, key = { it.name }) { role ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (target == role) StudyPanel else StudyBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (target == role) StudyAccent else StudyMuted.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth().clickable { target = role }
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label(role), fontWeight = FontWeight.SemiBold)
                            Text(repo?.selectedTitle(role) ?: "No group selected", color = StudyMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xE61C2940)),
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                    ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Configure " + label(target), fontWeight = FontWeight.Bold)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        repo?.discoverForumSources { result ->
                            busy = false
                            result.onSuccess { groups = it; status = "Found " + it.size + " eligible groups."; error = null }
                                .onFailure { error = it.message }
                        }
                    }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Find available groups") }
                    OutlinedButton(enabled = repo?.selectedChatId(target) != null, onClick = {
                        repo?.clearSource(target); status = "Source cleared."
                    }) { Text("Clear selection") }
                }
            }
            status?.let { Text(it, color = StudyMuted, fontSize = 12.sp) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (groups.isNotEmpty()) {
                Text("AVAILABLE GROUPS", color = StudyAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = { it.chatId }) { group ->
                        Card(colors = CardDefaults.cardColors(containerColor = StudyPanel)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(group.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = {
                                    repo?.assignSource(target, group)
                                    status = "Saved " + group.title + " for " + label(target) + "."
                                    error = null
                                }) { Text("Select") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelegramDocumentLibraryScreen(
    edition: MedVaultEdition,
    onBack: () -> Unit,
    onOpenPdf: (File, String) -> Unit,
    onManageSources: () -> Unit,
    onConnectAccount: () -> Unit,
    activeEdition: MedVaultEdition,
    onOpenPractice: () -> Unit = {}
) {
    val context = LocalContext.current
    var authState by remember { mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting) }
    var auth by remember { mutableStateOf<TelegramAuthManager?>(null) }
    var repo by remember { mutableStateOf<TelegramLibraryRepository?>(null) }
    var topics by remember { mutableStateOf<List<TelegramForumTopic>>(emptyList()) }
    var selectedTopic by remember { mutableStateOf<TelegramForumTopic?>(null) }
    var documents by remember { mutableStateOf<List<TelegramPdfDocument>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var documentSearch by remember { mutableStateOf("") }
    val sourceEdition = when (edition) {
        MedVaultEdition.QBANK -> when (activeEdition) {
            MedVaultEdition.M -> MedVaultEdition.QBANK_M
            MedVaultEdition.P -> MedVaultEdition.QBANK_P
            else -> MedVaultEdition.QBANK_U
        }
        MedVaultEdition.TESTS -> when (activeEdition) {
            MedVaultEdition.M -> MedVaultEdition.NOTES_M
            MedVaultEdition.P -> MedVaultEdition.NOTES_P
            else -> MedVaultEdition.NOTES_U
        }
        else -> activeEdition
    }

    LaunchedEffect(Unit) {
        val manager = TelegramAuthManager.getInstance(context) { authState = it }
        auth = manager
        authState = manager.currentState
        manager.start()
        repo = TelegramLibraryRepository(context, manager)
    }

    LaunchedEffect(repo, sourceEdition) {
        selectedTopic = null
        documents = emptyList()
        topics = emptyList()
        error = null
        status = null
        val library = repo ?: return@LaunchedEffect
        selectedGroup = library.selectedTitle(sourceEdition)
        if (library.selectedChatId(sourceEdition) != null) {
            busy = true
            library.loadTopics(sourceEdition) { result ->
                StudyMain.post {
                    busy = false
                    result.onSuccess { topics = it }.onFailure { error = it.message }
                }
            }
        } else {
            busy = false
        }
    }

    BackHandler(enabled = selectedTopic != null) { selectedTopic = null; documents = emptyList() }

    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selectedTopic != null) { selectedTopic = null; documents = emptyList() } else onBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(if (edition == MedVaultEdition.QBANK) "QBank Vault" else "Notes Library", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(selectedTopic?.name ?: selectedGroup ?: "PRACTICE MODULES  ·  PDF LIBRARY", color = StudyAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (edition == MedVaultEdition.QBANK) {
                TextButton(onClick = onOpenPractice) { Text("Practice MCQs") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }

        if (selectedTopic != null && documents.isNotEmpty()) {
            OutlinedTextField(
                value = documentSearch,
                onValueChange = { documentSearch = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search PDFs…", color = StudyMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = StudyMuted) },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudyAccent,
                    unfocusedBorderColor = StudyPanel,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        if (authState !is TelegramAuthManager.State.Ready) {
            Text("Connect your content account to access this library.", color = StudyMuted)
            Button(onClick = onConnectAccount) { Text("Connect account") }
        } else if (repo?.selectedChatId(sourceEdition) == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xE61C2940)),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("No source group configured for ${when (sourceEdition) {
                        MedVaultEdition.M, MedVaultEdition.QBANK_M, MedVaultEdition.NOTES_M -> "Edition M"
                        MedVaultEdition.P, MedVaultEdition.QBANK_P, MedVaultEdition.NOTES_P -> "Edition P"
                        else -> "Edition U"
                    }}.", fontWeight = FontWeight.SemiBold)
                    Text("Choose a source group in Settings to populate this library.", color = StudyMuted, fontSize = 13.sp)
                    Button(onClick = onManageSources) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("Configure library") }
                }
            }
        } else if (selectedTopic == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(topics, key = { it.topicId }) { topic ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            selectedTopic = topic
                            documents = emptyList()
                            busy = true
                            error = null
                            repo?.loadTopicMessages(topic) { result ->
                                StudyMain.post {
                                    busy = false
                                    result.onSuccess { messages ->
                                        documents = messages.mapNotNull { TelegramPdfDocument.fromMessage(topic.chatId, topic.topicId, it) }
                                            .distinctBy { it.stableId }
                                    }.onFailure { error = it.message }
                                }
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = Color(0xD91B2940)),
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null, tint = StudyAccent, modifier = Modifier.size(23.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(topic.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (topics.isEmpty() && !busy) item { Text("No topics found in this source group.", color = StudyMuted) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(documents.filter { it.title.contains(documentSearch.trim(), ignoreCase = true) }, key = { it.stableId }) { document ->
                    val local = TelegramPdfStore.localFile(context, sourceEdition, document)
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2038)), shape = RoundedCornerShape(26.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFFF7777), modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(document.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (document.fileSizeBytes > 0) "${document.fileSizeBytes / (1024 * 1024)} MB · PDF" else "PDF document", color = StudyMuted, fontSize = 11.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = downloadingId != document.stableId && local.isFile,
                                    onClick = { onOpenPdf(local, document.title) },
                                    modifier = Modifier.weight(1f)
                                ) { Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(5.dp)); Text("Open offline") }
                                OutlinedButton(
                                    enabled = downloadingId != document.stableId,
                                    onClick = {
                                        val manager = auth ?: return@OutlinedButton
                                        downloadingId = document.stableId
                                        error = null
                                        TelegramPdfStore.download(context, manager, sourceEdition, document) { result ->
                                            downloadingId = null
                                            result.onSuccess { file -> status = "Saved for offline use."; onOpenPdf(file, document.title) }
                                                .onFailure { error = it.message }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (downloadingId == document.stableId) "Downloading…" else if (local.isFile) "Downloaded" else "Download")
                                }
                            }
                        }
                    }
                }
                if (documents.isEmpty() && !busy) item { Text("No PDF documents found in this topic's latest messages.", color = StudyMuted) }
            }
        }
        status?.let { Text(it, color = StudyAccent, fontSize = 12.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

