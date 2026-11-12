package com.medvault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private val StudyIo = Executors.newSingleThreadExecutor()
private val StudyMain = Handler(Looper.getMainLooper())
private val StudyBg = Color(0xFF0B1020)
private val StudyPanel = Color(0xFF151E30)
private val StudyAccent = Color(0xFF78AFFF)
private val StudyMuted = Color(0xFF9EABC0)

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
    val roles = listOf(MedVaultEdition.M, MedVaultEdition.P, MedVaultEdition.QBANK_M, MedVaultEdition.QBANK_P, MedVaultEdition.NOTES_M, MedVaultEdition.NOTES_P)
    fun label(role: MedVaultEdition) = when (role) {
        MedVaultEdition.M -> "Video · Edition M"
        MedVaultEdition.P -> "Video · Edition P"
        MedVaultEdition.QBANK_M -> "QBank · Edition M"
        MedVaultEdition.QBANK_P -> "QBank · Edition P"
        MedVaultEdition.NOTES_M -> "Notes · Edition M"
        else -> "Notes · Edition P"
    }
    LaunchedEffect(Unit) {
        val auth = TelegramAuthManager.getInstance(context) { authState = it }
        authState = auth.currentState
        auth.start()
        repo = TelegramLibraryRepository(context, auth)
    }
    Column(Modifier.fillMaxSize().background(StudyBg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Card(colors = CardDefaults.cardColors(containerColor = StudyPanel)) {
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
    var qbankEdition by remember { mutableStateOf(MedVaultEdition.M) }
    var notesEdition by remember { mutableStateOf(MedVaultEdition.M) }
    val sourceEdition = when (edition) {
        MedVaultEdition.QBANK -> if (qbankEdition == MedVaultEdition.M) MedVaultEdition.QBANK_M else MedVaultEdition.QBANK_P
        MedVaultEdition.TESTS -> if (notesEdition == MedVaultEdition.M) MedVaultEdition.NOTES_M else MedVaultEdition.NOTES_P
        else -> edition
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

    Column(Modifier.fillMaxSize().background(StudyBg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selectedTopic != null) { selectedTopic = null; documents = emptyList() } else onBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(if (edition == MedVaultEdition.QBANK) "QBank" else "Notes", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(selectedTopic?.name ?: selectedGroup ?: "PDF study library", color = StudyMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (edition == MedVaultEdition.QBANK) {
                TextButton(onClick = onOpenPractice) { Text("Practice MCQs") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }

        if (edition == MedVaultEdition.QBANK || edition == MedVaultEdition.TESTS) {
            val currentEdition = if (edition == MedVaultEdition.QBANK) qbankEdition else notesEdition
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MedVaultEdition.M, MedVaultEdition.P).forEach { option ->
                    FilterChip(
                        selected = currentEdition == option,
                        onClick = { if (edition == MedVaultEdition.QBANK) qbankEdition = option else notesEdition = option },
                        label = { Text(if (option == MedVaultEdition.M) "Edition M" else "Edition P") }
                    )
                }
            }
        }

        if (authState !is TelegramAuthManager.State.Ready) {
            Text("Connect your content account to access this library.", color = StudyMuted)
            Button(onClick = onConnectAccount) { Text("Connect account") }
        } else if (repo?.selectedChatId(sourceEdition) == null) {
            Card(colors = CardDefaults.cardColors(containerColor = StudyPanel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No source group configured for ${if (sourceEdition.name.endsWith("_M") || sourceEdition == MedVaultEdition.M) "Edition M" else "Edition P"}.", fontWeight = FontWeight.SemiBold)
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
                        colors = CardDefaults.cardColors(containerColor = StudyPanel)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null, tint = StudyAccent)
                            Spacer(Modifier.width(12.dp))
                            Text(topic.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (topics.isEmpty() && !busy) item { Text("No topics found in this source group.", color = StudyMuted) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(documents, key = { it.stableId }) { document ->
                    val local = TelegramPdfStore.localFile(context, sourceEdition, document)
                    Card(colors = CardDefaults.cardColors(containerColor = StudyPanel)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFFF7777), modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(document.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

@Composable
fun MedVaultPdfReaderScreen(file: File, title: String, onBack: () -> Unit) {
    var renderer by remember(file.absolutePath) { mutableStateOf<PdfRenderer?>(null) }
    var descriptor by remember(file.absolutePath) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var page by remember(file.absolutePath) { mutableIntStateOf(0) }
    var pageCount by remember(file.absolutePath) { mutableIntStateOf(0) }
    var bitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file.absolutePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdf = PdfRenderer(fd)
                descriptor = fd
                renderer = pdf
                pageCount = pdf.pageCount
            } catch (e: Exception) {
                error = e.message ?: "Could not open this PDF."
            }
        }
    }

    LaunchedEffect(renderer, page) {
        val pdf = renderer ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            try {
                pdf.openPage(page).use { pdfPage ->
                    val width = 1400
                    val height = (width.toFloat() * pdfPage.height / pdfPage.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        pdfPage.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not render this page."
                null
            }
        }
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            try { renderer?.close() } catch (_: Exception) { }
            try { descriptor?.close() } catch (_: Exception) { }
            bitmap?.recycle()
        }
    }

    Column(Modifier.fillMaxSize().background(StudyBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (pageCount > 0) "${page + 1}/$pageCount" else "PDF", color = StudyMuted, fontSize = 12.sp)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "PDF page ${page + 1}",
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                contentScale = ContentScale.FillWidth
            )
        } else if (error == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
            Text("Page ${if (pageCount == 0) 0 else page + 1} of $pageCount", color = StudyMuted, fontSize = 12.sp)
            Button(enabled = pageCount > 0 && page < pageCount - 1, onClick = { page++ }) { Text("Next") }
        }
    }
}
