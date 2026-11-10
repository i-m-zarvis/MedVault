package com.medvault.app

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val thumbnailLink: String? = null,
    val size: Long? = null
)

data class FolderLocation(
    val id: String,
    val name: String
)

data class DownloadedLecture(
    val fileId: String,
    val name: String,
    val localPath: String,
    val downloadId: Long,
    val downloadedAt: Long
)

data class WatchRecord(
    val fileId: String,
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean
)
