package com.medvault.app

import android.content.Context
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Telegram is a private content source only. Source labels are never shown
 * in the learner-facing navigation or content cards.
 */
class TelegramLibraryRepository(context: Context, private val auth: TelegramAuthManager) {
    private val prefs = context.applicationContext.getSharedPreferences("telegram_sources", Context.MODE_PRIVATE)

    fun selectedChatId(edition: MedVaultEdition): Long? =
        if (prefs.contains(key(edition))) prefs.getLong(key(edition), 0L).takeIf { it != 0L } else null

    fun selectedTitle(edition: MedVaultEdition): String? = prefs.getString(titleKey(edition), null)

    fun assignSource(edition: MedVaultEdition, candidate: TelegramSourceCandidate) {
        val alreadyUsed = MedVaultEdition.values()
            .filterNot { it == edition }
            .any { selectedChatId(it) == candidate.chatId }
        require(!alreadyUsed) { "This group is already assigned to another MedVault library." }
        prefs.edit().putLong(key(edition), candidate.chatId)
            .putString(titleKey(edition), candidate.title).apply()
    }

    fun clearSource(edition: MedVaultEdition) {
        prefs.edit().remove(key(edition)).remove(titleKey(edition)).apply()
    }

    fun discoverForumSources(callback: (Result<List<TelegramSourceCandidate>>) -> Unit) {
        auth.sendRequest(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
            if (result is TdApi.Error) {
                callback(Result.failure(IllegalStateException(result.message)))
                return@sendRequest
            }
            if (result !is TdApi.Chats) {
                callback(Result.failure(IllegalStateException("TDLib did not return the chat list.")))
                return@sendRequest
            }
            val chatIds = result.chatIds.distinct()
            if (chatIds.isEmpty()) {
                callback(Result.success(emptyList()))
                return@sendRequest
            }
            val candidates = Collections.synchronizedList(mutableListOf<TelegramSourceCandidate>())
            val remaining = AtomicInteger(chatIds.size)
            val completed = AtomicBoolean(false)
            fun finishOne() {
                if (remaining.decrementAndGet() == 0 && completed.compareAndSet(false, true)) {
                    callback(Result.success(candidates.toList().sortedBy { it.title.lowercase() }))
                }
            }
            chatIds.forEach { chatId ->
                auth.sendRequest(TdApi.GetChat(chatId)) { chatResult ->
                    val type = (chatResult as? TdApi.Chat)?.type as? TdApi.ChatTypeSupergroup
                    if (chatResult is TdApi.Chat && type != null && !type.isChannel) {
                        auth.sendRequest(TdApi.GetSupergroup(type.supergroupId)) { supergroup ->
                            if (supergroup is TdApi.Supergroup && supergroup.isForum) {
                                candidates.add(TelegramSourceCandidate(chatResult.id, chatResult.title))
                            }
                            finishOne()
                        }
                    } else finishOne()
                }
            }
        }
    }

    fun loadTopics(edition: MedVaultEdition, callback: (Result<List<TelegramForumTopic>>) -> Unit) {
        val chatId = selectedChatId(edition)
        if (chatId == null) {
            callback(Result.failure(IllegalStateException("No source group is assigned to this library.")))
            return
        }
        val collected = linkedMapOf<Int, TelegramForumTopic>()
        fun page(offsetDate: Int, offsetMessageId: Long, offsetThreadId: Int) {
            auth.sendRequest(TdApi.GetForumTopics(chatId, "", offsetDate, offsetMessageId, offsetThreadId, 100)) { result ->
                if (result is TdApi.Error) {
                    callback(Result.failure(IllegalStateException(result.message)))
                    return@sendRequest
                }
                if (result !is TdApi.ForumTopics) {
                    callback(Result.failure(IllegalStateException("TDLib did not return forum topics.")))
                    return@sendRequest
                }
                val topicPage = result.topics
                topicPage.forEach { item ->
                    val info = item.info
                    collected[info.forumTopicId] = TelegramForumTopic(
                        chatId, info.forumTopicId, info.name, info.isGeneral, info.isClosed,
                        item.lastMessage?.id ?: 0L, item.lastMessage?.date ?: 0
                    )
                }
                if (topicPage.size < 100) callback(Result.success(collected.values.toList()))
                else {
                    val last = topicPage.last()
                    val lastMessage = last.lastMessage
                    if (lastMessage == null) callback(Result.success(collected.values.toList()))
                    else page(lastMessage.date, lastMessage.id, last.info.forumTopicId)
                }
            }
        }
        page(0, 0L, 0)
    }

    fun loadTopicMessages(
        topic: TelegramForumTopic,
        fromMessageId: Long = 0L,
        limit: Int = 100,
        callback: (Result<List<TdApi.Message>>) -> Unit
    ) {
        auth.sendRequest(TdApi.GetForumTopicHistory(topic.chatId, topic.topicId, fromMessageId, 0, limit.coerceIn(1, 100))) { result ->
            when (result) {
                is TdApi.Messages -> callback(Result.success(result.messages.sortedByDescending { it.id }))
                is TdApi.Error -> callback(Result.failure(IllegalStateException(result.message)))
                else -> callback(Result.failure(IllegalStateException("TDLib did not return topic messages.")))
            }
        }
    }

    fun extractLectures(topic: TelegramForumTopic, messages: List<TdApi.Message>): List<TelegramLecture> =
        messages.mapNotNull { TelegramLecture.fromMessage(topic.chatId, topic.topicId, it) }
            .distinctBy { it.stableId }.sortedByDescending { it.messageId }

    private fun key(edition: MedVaultEdition) = "source_${edition.name}"
    private fun titleKey(edition: MedVaultEdition) = "source_title_${edition.name}"
}
