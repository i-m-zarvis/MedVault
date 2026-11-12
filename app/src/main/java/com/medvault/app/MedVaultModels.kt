package com.medvault.app

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val thumbnailLink: String? = null,
    val size: Long? = null,
    val accountId: String = "default",
    val sharedDriveId: String? = null,
    val isSharedDriveRoot: Boolean = false
)

data class FolderLocation(
    val id: String,
    val name: String,
    val sharedDriveId: String? = null
)

data class DriveAccount(
    val id: String,
    val email: String,
    val displayName: String = email
)

data class DownloadedLecture(
    val fileId: String,
    val name: String,
    val localPath: String,
    val downloadId: Long,
    val downloadedAt: Long,
    val accountId: String = "default"
)

data class WatchRecord(
    val fileId: String,
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
    val accountId: String = "default"
)
