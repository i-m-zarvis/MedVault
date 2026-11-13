package com.medvault.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import java.io.File

private val DownloadAccent = Color(0xFF78AFFF)
private val DownloadMuted = Color(0xFF9EABC0)
private val DownloadPanel = Color(0xFF1A2033)

@Composable
fun DownloadManagerScreen(
    workItems: List<WorkInfo>,
    downloadedLectures: List<DownloadedLecture>,
    pausedIds: Set<String> = emptySet(),
    onBack: () -> Unit,
    onPause: (WorkInfo) -> Unit,
    onResume: (WorkInfo) -> Unit,
    onCancel: (WorkInfo) -> Unit,
    onRetry: (WorkInfo) -> Unit,
    onPlayOffline: (DownloadedLecture) -> Unit,
    onDeleteOffline: (DownloadedLecture) -> Unit
) {
    val context = LocalContext.current
    val downloadDirectory = File(context.filesDir, "downloads")
    val storageUsed by produceState(initialValue = 0L, downloadDirectory, downloadedLectures, workItems) {
        value = withContext(Dispatchers.IO) {
            downloadDirectory.listFiles()?.asSequence()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
    }
    val deviceStorage by produceState(
        initialValue = android.os.StatFs(context.filesDir.path).let { it.totalBytes to it.availableBytes },
        context.filesDir, downloadedLectures, storageUsed
    ) {
        value = withContext(Dispatchers.IO) {
            val stat = android.os.StatFs(context.filesDir.path)
            stat.totalBytes to stat.availableBytes
        }
    }
    val storageTotal = deviceStorage.first.coerceAtLeast(1L)
    val storageFree = deviceStorage.second.coerceAtLeast(0L)
    val storageUsedOnDevice = (storageTotal - storageFree).coerceAtLeast(0L)
    val storageFraction = storageUsedOnDevice.toFloat() / storageTotal.toFloat()
    var libraryQuery by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<DownloadedLecture?>(null) }
    fun isPaused(work: WorkInfo): Boolean {
        val fileId = work.tags.firstOrNull { it.startsWith("medvault_download_") }
            ?.removePrefix("medvault_download_") ?: return false
        val accountKey = work.tags.firstOrNull { it.startsWith("medvault_account_") }
            ?.removePrefix("medvault_account_") ?: DownloadRepository.accountKey("default")
        return "$accountKey:$fileId" in pausedIds
    }

    val filteredLectures = remember(downloadedLectures, libraryQuery) {
        val query = libraryQuery.trim()
        downloadedLectures
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .sortedByDescending { it.downloadedAt }
    }

    val activeItems = remember(workItems) {
        workItems.filter {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
    }

    val finishedItems = remember(workItems) {
        workItems.filter {
            it.state == WorkInfo.State.SUCCEEDED ||
                it.state == WorkInfo.State.FAILED ||
                it.state == WorkInfo.State.CANCELLED
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B10))
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = DownloadAccent
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "Downloads",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "OFFLINE VAULT  ·  YOUR SAVED MATERIAL",
                    color = DownloadMuted,
                    fontSize = 12.sp
                )
            }

            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                tint = DownloadAccent,
                modifier = Modifier.size(28.dp)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = DownloadPanel),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OFFLINE STORAGE", color = DownloadAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${formatDownloadSize(storageFree)} free", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(
                    progress = { storageFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = DownloadAccent
                )
                Spacer(Modifier.size(5.dp))
                Text("${formatDownloadSize(storageUsedOnDevice)} used of ${formatDownloadSize(storageTotal)} device storage", color = DownloadMuted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.size(18.dp))

        Text(
            "ACTIVE DOWNLOADS (${activeItems.size})",
            color = DownloadAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.size(8.dp))

        if (activeItems.isEmpty()) {
            EmptyDownloadMessage("No active downloads.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    activeItems,
                    key = { it.id }
                ) { work ->
                    DownloadWorkCard(
                        work = work,
                        onPause = { onPause(work) },
                        onResume = { onResume(work) },
                        onCancel = { onCancel(work) },
                        onRetry = { onRetry(work) },
                        isPaused = isPaused(work)
                    )
                }
            }
        }

        Spacer(Modifier.size(18.dp))

        Text(
            "OFFLINE LIBRARY (${downloadedLectures.size})",
            color = DownloadAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.size(8.dp))

        if (downloadedLectures.isNotEmpty()) {
            OutlinedTextField(
                value = libraryQuery,
                onValueChange = { libraryQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search") },
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.size(6.dp))
        }

        if (downloadedLectures.isEmpty()) {
            EmptyDownloadMessage(
                "Downloaded lectures will appear here."
            )
        } else if (filteredLectures.isEmpty()) {
            EmptyDownloadMessage("No offline lectures match your search.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    filteredLectures,
                    key = { it.accountId + ":" + it.fileId }
                ) { lecture ->
                    OfflineLectureCard(
                        lecture = lecture,
                        onPlay = { onPlayOffline(lecture) },
                        onDelete = { pendingDelete = lecture }
                    )
                }
            }
        }

        if (finishedItems.isNotEmpty()) {
            Spacer(Modifier.size(18.dp))

            Text(
                "RECENT DOWNLOAD TASKS",
                color = DownloadAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.size(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    finishedItems,
                    key = { it.id }
                ) { work ->
                    DownloadWorkCard(
                        work = work,
                        onPause = { onPause(work) },
                        onResume = { onResume(work) },
                        onCancel = { onCancel(work) },
                        onRetry = { onRetry(work) },
                        isPaused = work.tags.firstOrNull { it.startsWith("medvault_download_") }
                            ?.removePrefix("medvault_download_") in pausedIds
                    )
                }
            }
        }
    }

    pendingDelete?.let { lecture ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color(0xFF1A2033),
            title = { Text("Delete offline copy?") },
            text = {
                Text(
                    "“${lecture.name}” will be removed from this device. The original source file or message will not be affected.",
                    color = DownloadMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteOffline(lecture)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF8A80))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep download", color = DownloadAccent)
                }
            }
        )
    }
}

@Composable
private fun DownloadWorkCard(
    work: WorkInfo,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    isPaused: Boolean = false
) {
    val name =
        work.progress.getString(DriveDownloadWorker.KEY_FILE_NAME)
            ?: work.outputData.getString(DriveDownloadWorker.KEY_FILE_NAME)
            ?: "Lecture download"

    val progress = work.progress.getInt(
        "progress",
        0
    ).coerceIn(0, 100)

    val stateText = if (isPaused) "Paused · partial file retained" else when (work.state) {
        WorkInfo.State.ENQUEUED -> "Queued"
        WorkInfo.State.RUNNING -> "Downloading"
        WorkInfo.State.SUCCEEDED -> "Downloaded"
        WorkInfo.State.FAILED -> "Failed"
        WorkInfo.State.BLOCKED -> "Waiting for another task"
        WorkInfo.State.CANCELLED -> "Cancelled"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = DownloadPanel
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp).animateContentSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (work.state) {
                        WorkInfo.State.SUCCEEDED ->
                            Icons.Default.CheckCircle

                        WorkInfo.State.FAILED ->
                            Icons.Default.Error

                        WorkInfo.State.CANCELLED ->
                            Icons.Default.Cancel

                        else ->
                            Icons.Default.Download
                    },
                    contentDescription = null,
                    tint = DownloadAccent
                )

                Text(
                    name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.size(9.dp))

            Text(
                stateText,
                color = DownloadMuted,
                fontSize = 12.sp
            )

            work.outputData.getString("error")?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.size(5.dp))
                Text(message, color = Color(0xFFFF9B91), fontSize = 11.sp)
            }

            if (
                work.state == WorkInfo.State.RUNNING ||
                work.state == WorkInfo.State.ENQUEUED
            ) {
                Spacer(Modifier.size(8.dp))

                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = DownloadAccent
                )

                Text(
                    if (progress > 0) {
                        "$progress%"
                    } else {
                        "Preparing download…"
                    },
                    color = DownloadMuted,
                    fontSize = 11.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (work.state) {
                    WorkInfo.State.RUNNING -> {
                        IconButton(onClick = onPause) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = "Pause",
                                tint = DownloadAccent
                            )
                        }

                        IconButton(onClick = onCancel) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = DownloadMuted
                            )
                        }
                    }

                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> {
                        IconButton(onClick = onCancel) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = DownloadMuted
                            )
                        }
                    }

                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> {
                        IconButton(onClick = if (isPaused) onResume else onRetry) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = if (isPaused) "Resume" else "Retry",
                                tint = DownloadAccent
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> Unit
                }
            }
        }
    }
}

@Composable
private fun OfflineLectureCard(
    lecture: DownloadedLecture,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val file = File(lecture.localPath)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = DownloadPanel
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = file.isFile) {
                    onPlay()
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = DownloadAccent
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    lecture.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    if (file.isFile) {
                        formatDownloadSize(file.length())
                    } else {
                        "File missing"
                    },
                    color = DownloadMuted,
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = onPlay,
                enabled = file.isFile
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play offline",
                    tint = DownloadAccent
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete download",
                    tint = DownloadMuted
                )
            }
        }
    }
}

@Composable
private fun EmptyDownloadMessage(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = DownloadPanel
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = DownloadMuted,
            fontSize = 13.sp
        )
    }
}

private fun formatDownloadSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"

    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return "%.1f KB".format(kb)
    }

    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return "%.1f MB".format(mb)
    }

    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
