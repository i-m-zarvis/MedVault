package com.medvault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
            callback(Result.success(target))
            return
        }
        auth.sendRequest(TdApi.DownloadFile(document.fileId, 32, 0L, 0L, true)) { result ->
            StudyIo.execute {
                try {
                    val tdFile = result as? TdApi.File
                        ?: throw IllegalStateException((result as? TdApi.Error)?.message ?: "Telegram download failed.")
                    val sourcePath = tdFile.local.path
                    val source = File(sourcePath)
                    if (!source.isFile || !tdFile.local.isDownloadingCompleted) {
                        throw IllegalStateException("PDF download has not completed. Retry when the download finishes.")
                    }
                    target.parentFile?.mkdirs()
                    source.inputStream().use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    if (target.length() == 0L) {
                        target.delete()
                        throw IllegalStateException("Downloaded PDF is empty.")
                    }
                    callback(Result.success(target))
                } catch (error: Exception) {
                    callback(Result.failure(error))
                }
            }
        }
    }
}

@Composable
fun TelegramStudySourcesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var authState by remember { mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting) }
    var repo by remember { mutableStateOf<TelegramLibraryRepository?>(null) }
    var groups by remember { mutableStateOf<List<TelegramSourceCandidate>>(emptyList()) }
    var target by remember { mutableStateOf(MedVaultEdition.QBANK) }
    var selectedQbank by remember { mutableStateOf<String?>(null) }
    var selectedTests by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val auth = TelegramAuthManager.getInstance(context) { authState = it }
        authState = auth.currentState
        auth.start()
        repo = TelegramLibraryRepository(context, auth)
        selectedQbank = repo?.selectedTitle(MedVaultEdition.QBANK)
        selectedTests = repo?.selectedTitle(MedVaultEdition.TESTS)
    }

    Column(Modifier.fillMaxSize().background(AppBg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Study library sources", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Choose separate groups for QBank and Tests", color = AppMuted, fontSize = 12.sp)
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        if (authState !is TelegramAuthManager.State.Ready) {
            Text("Connect your account in account settings before selecting source groups.", color = AppMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(target == MedVaultEdition.QBANK, { target = MedVaultEdition.QBANK }, label = { Text("QBank") })
                FilterChip(target == MedVaultEdition.TESTS, { target = MedVaultEdition.TESTS }, label = { Text("Tests") })
            }
            Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (target == MedVaultEdition.QBANK) "QBank source" else "Tests source", fontWeight = FontWeight.Bold)
                    Text(
                        (if (target == MedVaultEdition.QBANK) selectedQbank else selectedTests) ?: "No group selected",
                        color = AppMuted
                    )
                    Button(enabled = !busy, onClick = {
                        busy = true
                        error = null
                        status = null
                        repo?.discoverForumSources { result ->
                            busy = false
                            result.onSuccess { groups = it; status = "Found ${it.size} eligible groups." }
                                .onFailure { error = it.message }
                        }
                    }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Find available groups") }
                    OutlinedButton(
                        enabled = (if (target == MedVaultEdition.QBANK) selectedQbank else selectedTests) != null,
                        onClick = { repo?.clearSource(target); if (target == MedVaultEdition.QBANK) selectedQbank = null else selectedTests = null }
                    ) { Text("Clear selection") }
                }
            }
            status?.let { Text(it, color = AppMuted, fontSize = 12.sp) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (groups.isNotEmpty()) {
                Text("AVAILABLE GROUPS", color = AppAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = { it.chatId }) { group ->
                        Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(group.title, fontWeight = FontWeight.SemiBold)
                                    Text("Private content source", color = AppMuted, fontSize = 11.sp)
                                }
                                TextButton(onClick = {
                                    runCatching { repo?.assignSource(target, group) }
                                        .onSuccess {
                                            if (target == MedVaultEdition.QBANK) selectedQbank = group.title else selectedTests = group.title
                                            status = "Source saved."
                                            error = null
                                        }.onFailure { error = it.message }
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
    onManageSources: () -> Unit
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

    LaunchedEffect(Unit) {
        val manager = TelegramAuthManager.getInstance(context) { authState = it }
        auth = manager
        authState = manager.currentState
        manager.start()
        repo = TelegramLibraryRepository(context, manager)
        selectedGroup = repo?.selectedTitle(edition)
        if (repo?.selectedChatId(edition) != null) {
            busy = true
            repo?.loadTopics(edition) { result ->
                busy = false
                result.onSuccess { topics = it }.onFailure { error = it.message }
            }
        }
    }

    BackHandler(enabled = selectedTopic != null) { selectedTopic = null; documents = emptyList() }

    Column(Modifier.fillMaxSize().background(AppBg).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selectedTopic != null) { selectedTopic = null; documents = emptyList() } else onBack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(if (edition == MedVaultEdition.QBANK) "QBank" else "Tests", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(selectedTopic?.name ?: selectedGroup ?: "PDF study library", color = AppMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }

        if (authState !is TelegramAuthManager.State.Ready) {
            Text("Connect your account to access this library.", color = AppMuted)
        } else if (repo?.selectedChatId(edition) == null) {
            Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No source group configured", fontWeight = FontWeight.SemiBold)
                    Text("Choose a source group in Settings to populate this library.", color = AppMuted, fontSize = 13.sp)
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
                                busy = false
                                result.onSuccess { messages ->
                                    documents = messages.mapNotNull { TelegramPdfDocument.fromMessage(topic.chatId, topic.topicId, it) }
                                        .distinctBy { it.stableId }
                                }.onFailure { error = it.message }
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = AppPanel)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null, tint = AppAccent)
                            Spacer(Modifier.width(12.dp))
                            Text(topic.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (topics.isEmpty() && !busy) item { Text("No topics found in this source group.", color = AppMuted) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(documents, key = { it.stableId }) { document ->
                    val local = TelegramPdfStore.localFile(context, edition, document)
                    Card(colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFFF7777), modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(document.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (document.fileSizeBytes > 0) "${document.fileSizeBytes / (1024 * 1024)} MB · PDF" else "PDF document", color = AppMuted, fontSize = 11.sp)
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
                                        TelegramPdfStore.download(context, manager, edition, document) { result ->
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
                if (documents.isEmpty()) item { Text("No PDF documents found in this topic's latest messages.", color = AppMuted) }
            }
        }
        status?.let { Text(it, color = AppAccent, fontSize = 12.sp) }
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

    Column(Modifier.fillMaxSize().background(AppBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (pageCount > 0) "${page + 1}/$pageCount" else "PDF", color = AppMuted, fontSize = 12.sp)
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
            Text("Page ${if (pageCount == 0) 0 else page + 1} of $pageCount", color = AppMuted, fontSize = 12.sp)
            Button(enabled = pageCount > 0 && page < pageCount - 1, onClick = { page++ }) { Text("Next") }
        }
    }
}
