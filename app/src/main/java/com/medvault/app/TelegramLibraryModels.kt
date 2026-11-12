package com.medvault.app

/** A Telegram supergroup that MedVault has verified as a forum. */
data class TelegramSourceCandidate(
    val chatId: Long,
    val title: String,
    val username: String? = null
)

data class TelegramForumTopic(
    val chatId: Long,
    val topicId: Int,
    val name: String,
    val isGeneral: Boolean,
    val isClosed: Boolean,
    val lastMessageId: Long = 0L
)

enum class MedVaultEdition { M, P }
