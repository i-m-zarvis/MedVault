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
        // Each library role has an independent source assignment. The same
        // Telegram group may intentionally feed Video, QBank, or Notes.
        prefs.edit().putLong(key(edition), candidate.chatId)
            .putString(titleKey(edition), candidate.title)
            .putBoolean(forumKey(edition), candidate.isForum).apply()
    }

    fun clearSource(edition: MedVaultEdition) {
        prefs.edit().remove(key(edition)).remove(titleKey(edition)).remove(forumKey(edition)).apply()
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
                    val chat = chatResult as? TdApi.Chat
                    when (val type = chat?.type) {
                        is TdApi.ChatTypeSupergroup -> {
                            if (!type.isChannel) {
                                auth.sendRequest(TdApi.GetSupergroup(type.supergroupId)) { supergroup ->
                                    if (supergroup is TdApi.Supergroup) {
                                        candidates.add(TelegramSourceCandidate(chat!!.id, chat.title, isForum = supergroup.isForum))
                                    }
                                    finishOne()
                                }
                            } else finishOne()
                        }
                        is TdApi.ChatTypeBasicGroup -> {
                            auth.sendRequest(TdApi.GetBasicGroup(type.basicGroupId)) { basicGroup ->
                                if (basicGroup is TdApi.BasicGroup) {
                                    candidates.add(TelegramSourceCandidate(chat!!.id, chat.title, isForum = false))
                                }
                                finishOne()
                            }
                        }
                        else -> finishOne()
                    }
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
        if (!prefs.getBoolean(forumKey(edition), false)) {
            callback(Result.success(listOf(TelegramForumTopic(chatId, 0, "General", true, false))))
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
        val request: TdApi.Function<*> = if (topic.topicId == 0) {
            TdApi.GetChatHistory(topic.chatId, fromMessageId, 0, limit.coerceIn(1, 100), false)
        } else {
            TdApi.GetForumTopicHistory(topic.chatId, topic.topicId, fromMessageId, 0, limit.coerceIn(1, 100))
        }
        auth.sendRequest(request) { result ->
            when (result) {
                is TdApi.Messages -> callback(Result.success(result.messages.sortedByDescending { it.id }))
                is TdApi.Error -> callback(Result.failure(IllegalStateException(result.message)))
                else -> callback(Result.failure(IllegalStateException("TDLib did not return topic messages.")))
            }
        }
    }

    /**
     * Loads the complete topic history in sequential TDLib pages.
     * Telegram caps each history response at 100 messages; the UI should not
     * require the learner to manually request older pages to see the sequence.
     */
    fun loadAllTopicMessages(
        topic: TelegramForumTopic,
        callback: (Result<List<TdApi.Message>>) -> Unit
    ) {
        val collected = linkedMapOf<Long, TdApi.Message>()
        fun loadPage(fromMessageId: Long) {
            loadTopicMessages(topic, fromMessageId = fromMessageId, limit = 100) { result ->
                result.onFailure { callback(Result.failure(it)) }
                result.onSuccess { page ->
                    page.forEach { collected[it.id] = it }
                    val oldestId = page.minOfOrNull { it.id }
                    if (page.size < 100 || oldestId == null || oldestId == fromMessageId) {
                        callback(Result.success(collected.values.sortedByDescending { it.id }))
                    } else {
                        loadPage(oldestId)
                    }
                }
            }
        }
        loadPage(0L)
    }

    /**
     * Streams topic history into the UI page-by-page while continuing to fetch older
     * messages. The first page is visible immediately; completion only controls the
     * loading indicator and the final empty-state decision.
     */
    fun loadTopicMessagesProgressively(
        topic: TelegramForumTopic,
        shouldContinue: () -> Boolean = { true },
        onPage: (List<TdApi.Message>) -> Unit,
        onComplete: (Result<List<TdApi.Message>>) -> Unit
    ) {
        val collected = linkedMapOf<Long, TdApi.Message>()
        var firstPageDelivered = false
        fun loadPage(fromMessageId: Long) {
            if (!shouldContinue()) return
            loadTopicMessages(topic, fromMessageId = fromMessageId, limit = 100) { result ->
                if (!shouldContinue()) return@loadTopicMessages
                result.onFailure {
                    if (!firstPageDelivered) onComplete(Result.failure(it))
                    else onComplete(Result.success(collected.values.sortedByDescending { msg -> msg.id }))
                }
                result.onSuccess { page ->
                    if (!shouldContinue()) return@onSuccess
                    page.forEach { collected[it.id] = it }
                    val ordered = collected.values.sortedByDescending { it.id }
                    firstPageDelivered = true
                    onPage(ordered)
                    val oldestId = page.minOfOrNull { it.id }
                    if (page.size < 100 || oldestId == null || oldestId == fromMessageId) {
                        onComplete(Result.success(ordered))
                    } else {
                        loadPage(oldestId)
                    }
                }
            }
        }
        loadPage(0L)
    }

    fun extractLectures(topic: TelegramForumTopic, messages: List<TdApi.Message>): List<TelegramLecture> =
        messages.mapNotNull { TelegramLecture.fromMessage(topic.chatId, topic.topicId, it) }
            .distinctBy { it.stableId }.sortedByDescending { it.messageId }

    private fun key(edition: MedVaultEdition) = "source_${edition.name}"
    private fun titleKey(edition: MedVaultEdition) = "source_title_${edition.name}"
    private fun forumKey(edition: MedVaultEdition) = "source_forum_${edition.name}"
}
