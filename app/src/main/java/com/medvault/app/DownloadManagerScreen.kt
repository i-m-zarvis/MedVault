package com.medvault.app

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
private val DownloadPanel = Color(0xFF151E30)

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
    val storageUsed = downloadDirectory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    val storageFree = context.filesDir.usableSpace
    val storageTotal = storageUsed + storageFree
    val storageFraction = if (storageTotal > 0L) storageUsed.toFloat() / storageTotal.toFloat() else 0f

    val activeItems = workItems.filter {
        it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
    }

    val finishedItems = workItems.filter {
        it.state == WorkInfo.State.SUCCEEDED ||
            it.state == WorkInfo.State.FAILED ||
            it.state == WorkInfo.State.CANCELLED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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
                    "Download Manager",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Manage your offline lectures",
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
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OFFLINE STORAGE", color = DownloadAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(formatDownloadSize(storageUsed), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(
                    progress = { storageFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = DownloadAccent
                )
                Spacer(Modifier.size(5.dp))
                Text("${formatDownloadSize(storageFree)} available on device · app-private storage", color = DownloadMuted, fontSize = 11.sp)
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
                        isPaused = work.tags.firstOrNull { it.startsWith("medvault_download_") }
                            ?.removePrefix("medvault_download_") in pausedIds
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

        if (downloadedLectures.isEmpty()) {
            EmptyDownloadMessage(
                "Downloaded lectures will appear here."
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    downloadedLectures.sortedByDescending { it.downloadedAt },
                    key = { it.fileId }
                ) { lecture ->
                    OfflineLectureCard(
                        lecture = lecture,
                        onPlay = { onPlayOffline(lecture) },
                        onDelete = { onDeleteOffline(lecture) }
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
