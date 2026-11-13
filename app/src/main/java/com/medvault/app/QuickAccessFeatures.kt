package com.medvault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private val QuickBg = Color(0xFF080B12)
private val QuickInk = Color(0xFFF5F6FA)
private val QuickMuted = Color(0xFF9299AA)
private val QuickBlue = Color(0xFF78AFFF)
private val BookmarkIo = Executors.newSingleThreadExecutor()

private data class BookmarkEntry(
    val id: String,
    val title: String,
    val type: String,
    val edition: MedVaultEdition,
    val chatId: Long,
    val topicId: Int,
    val messageId: Long
) {
    companion object {
        fun fromPreference(id: String, raw: String): BookmarkEntry? = runCatching {
            val json = JSONObject(raw)
            val parts = id.split(":")
            BookmarkEntry(
                id = id,
                title = json.optString("title").ifBlank { id },
                type = json.optString("type").ifBlank { parts.firstOrNull()?.removeSuffix(":") ?: "video" },
                edition = runCatching { MedVaultEdition.valueOf(json.optString("edition", "M")) }.getOrDefault(MedVaultEdition.M),
                chatId = json.optLong("chatId", parts.getOrNull(1)?.toLongOrNull() ?: 0L),
                topicId = json.optInt("topicId", parts.getOrNull(2)?.toIntOrNull() ?: 0),
                messageId = json.optLong("messageId", parts.getOrNull(3)?.toLongOrNull() ?: 0L)
            )
        }.getOrElse {
            val parts = raw.split("|", limit = 2)
            val idParts = id.split(":")
            BookmarkEntry(
                id = id,
                title = parts.getOrNull(1).orEmpty().ifBlank { id },
                type = parts.firstOrNull().orEmpty().ifBlank { "video" },
                edition = MedVaultEdition.M,
                chatId = idParts.getOrNull(1)?.toLongOrNull() ?: 0L,
                topicId = idParts.getOrNull(2)?.toIntOrNull() ?: 0,
                messageId = idParts.getOrNull(3)?.toLongOrNull() ?: 0L
            )
        }.takeIf { it.chatId != 0L && it.messageId != 0L }
    }
}

@Composable
fun MedVaultBookmarksScreen(
    onBack: () -> Unit,
    onPlayLecture: (TelegramLecture) -> Unit,
    onOpenPdf: (File, String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("medvault_bookmarks", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var authState by remember { mutableStateOf<TelegramAuthManager.State>(TelegramAuthManager.State.Starting) }
    var auth by remember { mutableStateOf<TelegramAuthManager?>(null) }
    val entries = remember(revision) {
        prefs.all.entries.mapNotNull { (id, value) ->
            BookmarkEntry.fromPreference(id, value as? String ?: return@mapNotNull null)
        }.sortedBy { it.title.lowercase() }
    }
    LaunchedEffect(Unit) {
        val manager = TelegramAuthManager.getInstance(context) { authState = it }
        auth = manager
        authState = manager.currentState
        manager.start()
    }
    BackHandler { onBack() }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0B1428), QuickBg, Color(0xFF100B1B))))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = QuickInk) }
            Column(Modifier.weight(1f)) {
                Text("YOUR LIBRARY", color = QuickBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Bookmarks", color = QuickInk, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            }
            Text(entries.size.toString(), color = QuickMuted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Default.Bookmark, null, tint = QuickBlue, modifier = Modifier.size(38.dp))
                Text("Nothing bookmarked yet", color = QuickInk, fontWeight = FontWeight.SemiBold)
                Text("Save a lecture or PDF and it will appear here.", color = QuickMuted, fontSize = 13.sp)
            }
        } else if (authState !is TelegramAuthManager.State.Ready) {
            Text("Connect Telegram to open your saved content.", color = QuickMuted, fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                items(entries, key = { it.id }) { entry ->
                    BookmarkContentCard(
                        entry = entry,
                        auth = auth,
                        onPlayLecture = onPlayLecture,
                        onOpenPdf = onOpenPdf,
                        onRemove = { prefs.edit().remove(entry.id).apply(); revision++ }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkContentCard(
    entry: BookmarkEntry,
    auth: TelegramAuthManager?,
    onPlayLecture: (TelegramLecture) -> Unit,
    onOpenPdf: (File, String) -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var lecture by remember(entry.id) { mutableStateOf<TelegramLecture?>(null) }
    var document by remember(entry.id) { mutableStateOf<TelegramPdfDocument?>(null) }
    var loading by remember(entry.id) { mutableStateOf(true) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var thumbnailPath by remember(entry.id) { mutableStateOf<String?>(null) }
    var pdfFile by remember(entry.id) { mutableStateOf<File?>(null) }
    var downloading by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(entry.id, auth) {
        val manager = auth ?: return@LaunchedEffect
        manager.sendRequest(TdApi.GetMessage(entry.chatId, entry.messageId)) { result ->
            when (result) {
                is TdApi.Message -> {
                    if (entry.type.equals("pdf", true)) {
                        document = TelegramPdfDocument.fromMessage(entry.chatId, entry.topicId, result)
                        if (document == null) error = "This saved message is no longer a PDF."
                    } else {
                        lecture = TelegramLecture.fromMessage(entry.chatId, entry.topicId, result)
                        if (lecture == null) error = "This saved message is no longer a playable video."
                    }
                    loading = false
                }
                is TdApi.Error -> { error = result.message; loading = false }
                else -> { error = "Could not load this saved item."; loading = false }
            }
        }
    }
    LaunchedEffect(lecture?.thumbnailFileId) {
        val fileId = lecture?.thumbnailFileId ?: return@LaunchedEffect
        val manager = auth ?: return@LaunchedEffect
        manager.sendRequest(TdApi.DownloadFile(fileId, 16, 0L, 0L, true)) { result ->
            val path = (result as? TdApi.File)?.local?.path
            if (!path.isNullOrBlank() && File(path).isFile) thumbnailPath = path
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xB51A263B)),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.085f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(width = 112.dp, height = 72.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (entry.type.equals("pdf", true)) Color(0xFFFF7777).copy(alpha = 0.12f) else Color(0xFF17243A))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (entry.type.equals("pdf", true)) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFFF8585), modifier = Modifier.size(29.dp))
                    } else if (thumbnailPath != null) {
                        AsyncImage(model = File(thumbnailPath!!), contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(28.dp))
                    } else {
                        Icon(Icons.Default.PlayCircle, null, tint = QuickBlue, modifier = Modifier.size(29.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(lecture?.title ?: document?.title ?: entry.title, color = QuickInk, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (entry.type.equals("pdf", true)) "PDF DOCUMENT" else "VIDEO LECTURE",
                        color = QuickMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Remove bookmark", tint = QuickMuted) }
            }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            if (loading || downloading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = QuickBlue)
                    Text(if (loading) "Loading saved item…" else "Downloading PDF…", color = QuickMuted, fontSize = 12.sp)
                }
            }
            if (entry.type.equals("pdf", true) && document != null) {
                val local = remember(document, entry.edition) { document?.let { TelegramPdfStore.localFile(context, entry.edition, it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        enabled = local?.isFile == true && !downloading,
                        onClick = { local?.let { onOpenPdf(it, document!!.title) } },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickBlue)
                    ) { Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Open") }
                    Button(
                        enabled = !downloading,
                        onClick = {
                            val manager = auth ?: return@Button
                            downloading = true
                            TelegramPdfStore.download(context, manager, entry.edition, document!!) { result ->
                                downloading = false
                                result.onSuccess { file -> pdfFile = file; onOpenPdf(file, document!!.title) }
                                    .onFailure { error = it.message }
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QuickBlue.copy(alpha = 0.17f), contentColor = QuickBlue)
                    ) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(if (local?.isFile == true) "Downloaded" else "Download") }
                }
            } else if (!entry.type.equals("pdf", true) && lecture != null) {
                Button(
                    onClick = { lecture?.let(onPlayLecture) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickBlue.copy(alpha = 0.17f), contentColor = QuickBlue)
                ) { Icon(Icons.Default.PlayCircle, null); Spacer(Modifier.width(7.dp)); Text("Play lecture") }
            }
        }
    }
}

@Composable
private fun PdfBookmarkPreview(file: File, modifier: Modifier = Modifier) {
    var bitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        BookmarkIo.execute {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        if (renderer.pageCount > 0) renderer.openPage(0).use { page ->
                            val width = 300
                            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        } else null
                    }
                }
            }.getOrNull()
        }.also { result -> bitmap = result.getOrNull() }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "PDF first page preview", modifier = modifier, contentScale = ContentScale.Crop)
}
private data class PlannerTask(val id: Long, val title: String, val subject: String, val due: String, val done: Boolean)

@Composable
fun MedVaultStudyPlannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("medvault_study_planner", Context.MODE_PRIVATE) }
    fun loadTasks(): List<PlannerTask> = runCatching {
        val array = JSONArray(prefs.getString("tasks", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PlannerTask(item.optLong("id"), item.optString("title"), item.optString("subject"), item.optString("due"), item.optBoolean("done"))
        }
    }.getOrDefault(emptyList())
    var tasks by remember { mutableStateOf(loadTasks()) }
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    fun persist(updated: List<PlannerTask>) {
        tasks = updated
        val array = JSONArray()
        updated.forEach { task ->
            array.put(JSONObject().put("id", task.id).put("title", task.title).put("subject", task.subject).put("due", task.due).put("done", task.done))
        }
        prefs.edit().putString("tasks", array.toString()).apply()
    }
    val completed = tasks.count { it.done }
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B1428), QuickBg, Color(0xFF100B1B)))).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = QuickInk) }
            Column(Modifier.weight(1f)) {
                Text("STAY ON TRACK", color = QuickBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Study Planner", color = QuickInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xD91B2940)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("$completed of ${tasks.size} tasks completed", color = QuickInk, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else completed.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth(), color = QuickBlue, trackColor = Color.White.copy(alpha = 0.10f))
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xB51A263B)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Add a study task", color = QuickInk, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Topic or task") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Subject") })
                    OutlinedTextField(value = due, onValueChange = { due = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Due date") })
                }
                Button(onClick = {
                    val clean = title.trim()
                    if (clean.isNotEmpty()) {
                        persist(tasks + PlannerTask(System.currentTimeMillis(), clean, subject.trim(), due.trim(), false))
                        title = ""; subject = ""; due = ""
                    }
                }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add task")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (tasks.isEmpty()) {
            Text("Your study tasks will appear here.", color = QuickMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(tasks, key = { it.id }) { task ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xD91B2940)), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.done, onCheckedChange = { checked -> persist(tasks.map { if (it.id == task.id) it.copy(done = checked) else it }) })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(task.title, color = if (task.done) QuickMuted else QuickInk, fontWeight = FontWeight.SemiBold)
                                Text(listOf(task.subject, task.due).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "No subject or due date" }, color = QuickMuted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { persist(tasks.filterNot { it.id == task.id }) }) { Icon(Icons.Default.DeleteOutline, "Delete task", tint = QuickMuted) }
                        }
                    }
                }
            }
        }
    }
}
