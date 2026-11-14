package com.medvault.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
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
            val rawTitle = (caption.lineSequence().firstOrNull()?.trim().orEmpty()
                .ifBlank { fileName.substringAfterLast('/') })
                .replace(Regex("\\\\.pdf$", RegexOption.IGNORE_CASE), "").trim()
            val title = rawTitle.takeUnless {
                it.matches(Regex("(document[_ -]?)?\\d+", RegexOption.IGNORE_CASE))
            } ?: "PDF document"
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
    val roles = listOf(MedVaultEdition.M, MedVaultEdition.P, MedVaultEdition.U, MedVaultEdition.QBANK_M, MedVaultEdition.QBANK_P, MedVaultEdition.QBANK_U, MedVaultEdition.REVISION, MedVaultEdition.NOTES)
    fun label(role: MedVaultEdition) = when (role) {
        MedVaultEdition.M -> "Video · Edition M"
        MedVaultEdition.P -> "Video · Edition P"
        MedVaultEdition.U -> "Video · Edition U"
        MedVaultEdition.QBANK_M -> "QBank · Edition M"
        MedVaultEdition.QBANK_P -> "QBank · Edition P"
        MedVaultEdition.QBANK_U -> "QBank · Edition U"
        MedVaultEdition.REVISION -> "Rapid Revision · Shared source"
        else -> "Notes · Shared source"
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
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(18.dp),
                        border = null,
                        modifier = Modifier.fillMaxWidth().liquidPanel(if (target == role) StudyAccent else StudyMuted, 18.dp)
                            .medVaultClickable { target = role }
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label(role), fontWeight = FontWeight.SemiBold)
                            Text(repo?.selectedTitle(role) ?: "No group selected", color = StudyMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(24.dp),
                        border = null,
                        modifier = Modifier.fillMaxWidth().liquidPanel(StudyAccent, 24.dp)
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
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            shape = RoundedCornerShape(18.dp),
                            border = null,
                            modifier = Modifier.fillMaxWidth().liquidPanel(StudyAccent, 18.dp)
                        ) {
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

class TelegramDocumentLibraryState {
    val authState = mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting)
    val auth = mutableStateOf<TelegramAuthManager?>(null)
    val repo = mutableStateOf<TelegramLibraryRepository?>(null)
    val topics = mutableStateOf<List<TelegramForumTopic>>(emptyList())
    val selectedTopic = mutableStateOf<TelegramForumTopic?>(null)
    val documents = mutableStateOf<List<TelegramPdfDocument>>(emptyList())
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val status = mutableStateOf<String?>(null)
    val downloadingId = mutableStateOf<String?>(null)
    val selectedGroup = mutableStateOf<String?>(null)
    val documentSearch = mutableStateOf("")
    val allDocuments = mutableStateOf<List<Pair<String, TelegramPdfDocument>>>(emptyList())
    val allDocumentsLoadedFor = mutableStateOf<MedVaultEdition?>(null)
    val allDocumentsLoading = mutableStateOf(false)
    var loadedSourceEdition: MedVaultEdition? = null
}

@Composable
fun TelegramDocumentLibraryScreen(
    edition: MedVaultEdition,
    onBack: () -> Unit,
    onOpenPdf: (File, String) -> Unit,
    onManageSources: () -> Unit,
    onConnectAccount: () -> Unit,
    activeEdition: MedVaultEdition,
    onOpenPractice: () -> Unit = {},
    state: TelegramDocumentLibraryState
) {
    val context = LocalContext.current
    var authState by state.authState
    var auth by state.auth
    var repo by state.repo
    var topics by state.topics
    var selectedTopic by state.selectedTopic
    var documents by state.documents
    var busy by state.busy
    var error by state.error
    var status by state.status
    var downloadingId by state.downloadingId
    var selectedGroup by state.selectedGroup
    var documentSearch by state.documentSearch
    var allDocuments by state.allDocuments
    var allDocumentsLoadedFor by state.allDocumentsLoadedFor
    var allDocumentsLoading by state.allDocumentsLoading
    var pendingDeletePdf by remember { mutableStateOf<File?>(null) }
    var localFileRevision by remember { mutableStateOf(0) }
    val sourceEdition = when (edition) {
        MedVaultEdition.QBANK -> when (activeEdition) {
            MedVaultEdition.M -> MedVaultEdition.QBANK_M
            MedVaultEdition.P -> MedVaultEdition.QBANK_P
            else -> MedVaultEdition.QBANK_U
        }
        MedVaultEdition.NOTES -> MedVaultEdition.NOTES
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
        val library = repo ?: return@LaunchedEffect
        if (state.loadedSourceEdition != sourceEdition) {
            selectedTopic = null
            documents = emptyList()
            topics = emptyList()
            documentSearch = ""
            allDocuments = emptyList()
            allDocumentsLoadedFor = null
            allDocumentsLoading = false
            error = null
            status = null
            state.loadedSourceEdition = sourceEdition
        }
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

    LaunchedEffect(documentSearch, selectedTopic, topics, repo, sourceEdition) {
        val library = repo ?: return@LaunchedEffect
        if (selectedTopic != null || documentSearch.isBlank() || topics.isEmpty() ||
            allDocumentsLoadedFor == sourceEdition || allDocumentsLoading ||
            authState !is TelegramAuthManager.State.Ready
        ) return@LaunchedEffect
        allDocumentsLoading = true
        val collected = mutableListOf<Pair<String, TelegramPdfDocument>>()
        fun loadTopicAt(index: Int) {
            if (index >= topics.size) {
                allDocuments = collected.toList()
                allDocumentsLoadedFor = sourceEdition
                allDocumentsLoading = false
                return
            }
            val currentTopic = topics[index]
            library.loadAllTopicMessages(currentTopic) { result ->
                result.onSuccess { page ->
                    collected += page.mapNotNull { TelegramPdfDocument.fromMessage(currentTopic.chatId, currentTopic.topicId, it) }
                        .distinctBy { it.stableId }.map { currentTopic.name to it }
                }
                loadTopicAt(index + 1)
            }
        }
        loadTopicAt(0)
    }

    BackHandler(enabled = selectedTopic != null) { selectedTopic = null; documents = emptyList(); documentSearch = "" }

    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), Color(0xFF080B12), Color(0xFF100B1B)))).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selectedTopic != null) { selectedTopic = null; documents = emptyList(); documentSearch = "" } else onBack() }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (edition == MedVaultEdition.QBANK) "QBank Vault" else "Notes Vault",
                    fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(selectedTopic?.name ?: selectedGroup ?: "STUDY DOCUMENTS",
                    color = StudyAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.35.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (edition == MedVaultEdition.QBANK) {
                TextButton(onClick = onOpenPractice) { Text("Practice", color = StudyAccent, fontWeight = FontWeight.SemiBold) }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = StudyAccent)
        }

        if (authState is TelegramAuthManager.State.Ready && repo?.selectedChatId(sourceEdition) != null) {
            OutlinedTextField(
                value = documentSearch,
                onValueChange = { documentSearch = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search", color = StudyMuted) },
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
        } else if (selectedTopic == null && documentSearch.isBlank()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(topics, key = { it.topicId }) { topic ->
                    Card(
                        Modifier.fillMaxWidth().medVaultClickable {
                            selectedTopic = topic
                            documentSearch = ""
                            documents = emptyList()
                            busy = true
                            error = null
                            repo?.loadAllTopicMessages(topic) { result ->
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
            val visibleDocuments = if (selectedTopic == null) {
                allDocuments.filter { (_, doc) ->
                    val query = documentSearch.trim()
                    doc.title.contains(query, ignoreCase = true) || doc.fileName.contains(query, ignoreCase = true)
                }.map { it.second }
            } else {
                documents.filter {
                    it.title.contains(documentSearch.trim(), ignoreCase = true) ||
                        it.fileName.contains(documentSearch.trim(), ignoreCase = true)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selectedTopic == null) "SEARCH RESULTS" else "DOCUMENTS", color = StudyMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text(if (allDocumentsLoading) "Searching…" else "${visibleDocuments.size} files", color = StudyMuted, fontSize = 11.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 112.dp)) {
                items(visibleDocuments, key = { it.stableId }) { document ->
                    val local = TelegramPdfStore.localFile(context, sourceEdition, document)
                    val isDownloaded = localFileRevision >= 0 && local.isFile
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xB51A263B)),
                        shape = RoundedCornerShape(21.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.085f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(48.dp).background(Color(0xFFFF7777).copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                        .border(1.dp, Color(0xFFFF7777).copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFFF8585), modifier = Modifier.size(25.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(document.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                        lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (document.fileSizeBytes > 0) "${document.fileSizeBytes / (1024 * 1024)} MB  ·  PDF" else "PDF DOCUMENT",
                                        color = StudyMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.55.sp
                                    )
                                }
                                LiquidBookmarkButton(contentId = "pdf:${document.stableId}", title = document.title, contentType = "pdf", sourceEdition = sourceEdition)
                                if (isDownloaded) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF62D7C8), modifier = Modifier.size(19.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    enabled = isDownloaded && downloadingId != document.stableId,
                                    onClick = { onOpenPdf(local, document.title) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudyAccent)
                                ) {
                                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open", maxLines = 1)
                                }
                                if (isDownloaded) {
                                    IconButton(onClick = { pendingDeletePdf = local }) {
                                        Icon(Icons.Default.Delete, "Delete offline PDF", tint = Color(0xFFFF8A80))
                                    }
                                }
                                Button(
                                    enabled = downloadingId != document.stableId,
                                    onClick = {
                                        val manager = auth ?: return@Button
                                        downloadingId = document.stableId
                                        error = null
                                        TelegramPdfStore.download(context, manager, sourceEdition, document) { result ->
                                            downloadingId = null
                                            result.onSuccess { file -> status = "Saved for offline use."; onOpenPdf(file, document.title) }
                                                .onFailure { error = it.message }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = StudyAccent.copy(alpha = 0.16f), contentColor = StudyAccent)
                                ) {
                                    if (downloadingId == document.stableId) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StudyAccent)
                                    } else {
                                        Icon(if (local.isFile) Icons.Default.CheckCircle else Icons.Default.Download, null, modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (downloadingId == document.stableId) "Saving…" else if (local.isFile) "Downloaded" else "Download", maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (visibleDocuments.isEmpty() && !busy && !allDocumentsLoading) item {
                    Text(if (selectedTopic == null) "No PDFs match this search." else "No PDF documents found in this topic.",
                        color = StudyMuted, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))
                }
                if (visibleDocuments.isEmpty() && allDocumentsLoading) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = StudyAccent)
                        Spacer(Modifier.width(9.dp))
                        Text("Searching this library…", color = StudyMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        status?.let { Text(it, color = StudyAccent, fontSize = 12.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
    pendingDeletePdf?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDeletePdf = null },
            title = { Text("Delete offline PDF?") },
            text = { Text(file.nameWithoutExtension) },
            confirmButton = {
                TextButton(onClick = {
                    if (file.delete()) {
                        localFileRevision++
                        pendingDeletePdf = null
                    } else {
                        error = "Could not delete this PDF."
                        pendingDeletePdf = null
                    }
                }) { Text("Delete", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePdf = null }) { Text("Cancel") }
            }
        )
    }
}

