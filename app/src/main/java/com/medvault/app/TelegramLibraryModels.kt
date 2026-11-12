package com.medvault.app

import org.drinkless.tdlib.TdApi

/** A Telegram supergroup that MedVault has verified as a forum. */
data class TelegramSourceCandidate(
    val chatId: Long,
    val title: String,
    val username: String? = null,
    val isForum: Boolean = false
)

data class TelegramForumTopic(
    val chatId: Long,
    val topicId: Int,
    val name: String,
    val isGeneral: Boolean,
    val isClosed: Boolean,
    val lastMessageId: Long = 0L,
    val lastMessageDate: Int = 0
)

data class TelegramLecture(
    val stableId: String,
    val chatId: Long,
    val topicId: Int,
    val messageId: Long,
    val title: String,
    val fileName: String,
    val caption: String,
    val fileId: Int,
    val fileSizeBytes: Long,
    val durationSeconds: Int,
    val mimeType: String,
    val thumbnailFileId: Int?,
    val messageDate: Int
) {
    companion object {
        fun fromMessage(chatId: Long, topicId: Int, message: TdApi.Message): TelegramLecture? {
            val media = when (val content = message.content) {
                is TdApi.MessageVideo -> {
                    val video = content.video
                    Media(video.video, video.fileName, video.mimeType, video.duration,
                        content.caption.text, video.thumbnail?.file?.id)
                }
                is TdApi.MessageDocument -> {
                    val document = content.document.document
                    val mime = content.document.mimeType.orEmpty()
                    val name = content.document.fileName.orEmpty()
                    if (!mime.startsWith("video/", ignoreCase = true) && !name.hasVideoExtension()) return null
                    Media(document, name, mime, 0, content.caption.text, null)
                }
                is TdApi.MessageAnimation -> {
                    val animation = content.animation
                    Media(animation.animation, animation.fileName, animation.mimeType, animation.duration,
                        content.caption.text, animation.thumbnail?.file?.id)
                }
                is TdApi.MessageVideoNote -> {
                    val video = content.videoNote.video
                    Media(video, "video_note_${message.id}.mp4", "video/mp4",
                        content.videoNote.duration, "", content.videoNote.thumbnail?.file?.id)
                }
                else -> return null
            }
            val file = media.file
            val fallbackName = media.fileName.ifBlank { "lecture_${message.id}.mp4" }
            val captionTitle = media.caption.lineSequence().firstOrNull()?.trim().orEmpty()
            return TelegramLecture(
                stableId = "$chatId:$topicId:${message.id}",
                chatId = chatId,
                topicId = topicId,
                messageId = message.id,
                title = captionTitle.ifBlank { fallbackName.substringAfterLast('/') },
                fileName = fallbackName,
                caption = media.caption,
                fileId = file.id,
                fileSizeBytes = file.size.coerceAtLeast(0L),
                durationSeconds = media.duration.coerceAtLeast(0),
                mimeType = media.mimeType.ifBlank { "video/*" },
                thumbnailFileId = media.thumbnailFileId,
                messageDate = message.date
            )
        }

        private fun String.hasVideoExtension(): Boolean =
            substringBefore('?').substringAfterLast('.', "").lowercase() in
                setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "mpeg", "mpg", "3gp")

        private data class Media(
            val file: TdApi.File,
            val fileName: String,
            val mimeType: String,
            val duration: Int,
            val caption: String,
            val thumbnailFileId: Int?
        )
    }
}

/** Each source role is configured independently in Settings. */
enum class MedVaultEdition { M, P, U, QBANK_M, QBANK_P, QBANK_U, NOTES_M, NOTES_P, NOTES_U, QBANK, TESTS }
