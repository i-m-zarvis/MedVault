package com.medvault.app

import android.content.Context
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Telegram is used only as a media source. This repository exposes only the
 * two explicitly selected forum groups (Edition M and Edition P) to MedVault.
 */
class TelegramLibraryRepository(context: Context, private val auth: TelegramAuthManager) {
    private val prefs = context.applicationContext.getSharedPreferences("telegram_sources", Context.MODE_PRIVATE)
    private val observers = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()
    private val updateListener: (TdApi.Object) -> Unit = { update -> observers.forEach { it(update) } }

    init { auth.addUpdateListener(updateListener) }
    fun close() { auth.removeUpdateListener(updateListener) }

    fun observeUpdates(listener: (TdApi.Object) -> Unit) { observers.add(listener) }
    fun removeUpdateObserver(listener: (TdApi.Object) -> Unit) { observers.remove(listener) }

    fun selectedChatId(edition: MedVaultEdition): Long? =
        if (prefs.contains(key(edition))) prefs.getLong(key(edition), 0L).takeIf { it != 0L } else null

    fun selectedTitle(edition: MedVaultEdition): String? = prefs.getString(titleKey(edition), null)

    fun assignSource(edition: MedVaultEdition, candidate: TelegramSourceCandidate) {
        val other = if (edition == MedVaultEdition.M) MedVaultEdition.P else MedVaultEdition.M
        require(selectedChatId(other) != candidate.chatId) { "The same Telegram group cannot be assigned to both editions." }
        prefs.edit().putLong(key(edition), candidate.chatId).putString(titleKey(edition), candidate.title).apply()
    }

    fun clearSource(edition: MedVaultEdition) {
        prefs.edit().remove(key(edition)).remove(titleKey(edition)).apply()
    }

    /** Load main-list chats, then verify each supergroup's forum flag with TDLib. */
    fun discoverForumSources(callback: (Result<List<TelegramSourceCandidate>>) -> Unit) {
        auth.sendRequest(TdApi.GetChats(TdApi.ChatListMain(), 200)) { result ->
            if (result !is TdApi.Chats) {
                callback(Result.failure(IllegalStateException("TDLib did not return the chat list.")))
                return@sendRequest
            }
            val chats = result.chatList.filter { it.type is TdApi.ChatTypeSupergroup && !(it.type as TdApi.ChatTypeSupergroup).isChannel }
            if (chats.isEmpty()) {
                callback(Result.success(emptyList()))
                return@sendRequest
            }
            val candidates = mutableListOf<TelegramSourceCandidate>()
            var remaining = chats.size
            chats.forEach { chat ->
                val type = chat.type as TdApi.ChatTypeSupergroup
                auth.sendRequest(TdApi.GetSupergroup(type.supergroupId)) { supergroupResult ->
                    if (supergroupResult is TdApi.Supergroup && supergroupResult.isForum) {
                        candidates.add(TelegramSourceCandidate(chat.id, chat.title, null))
                    }
                    remaining--
                    if (remaining == 0) callback(Result.success(candidates.sortedBy { it.title.lowercase() }))
                }
            }
        }
    }

    fun loadTopics(edition: MedVaultEdition, callback: (Result<List<TelegramForumTopic>>) -> Unit) {
        val chatId = selectedChatId(edition)
        if (chatId == null) {
            callback(Result.failure(IllegalStateException("No Telegram group is assigned to Edition ${edition.name}.")))
            return
        }
        auth.sendRequest(TdApi.GetForumTopics(chatId, "", 0, 0L, 0, 100)) { result ->
            if (result !is TdApi.ForumTopics) {
                callback(Result.failure(IllegalStateException("TDLib did not return forum topics.")))
                return@sendRequest
            }
            callback(Result.success(result.topics.map { topic ->
                TelegramForumTopic(
                    chatId = chatId,
                    topicId = topic.info.forumTopicId,
                    name = topic.info.name,
                    isGeneral = topic.info.isGeneral,
                    isClosed = topic.info.isClosed,
                    lastMessageId = topic.lastMessage?.id ?: 0L
                )
            }))
        }
    }

    fun loadTopicMessages(topic: TelegramForumTopic, fromMessageId: Long = 0L, limit: Int = 100,
                          callback: (Result<List<TdApi.Message>>) -> Unit) {
        auth.sendRequest(TdApi.GetForumTopicHistory(topic.chatId, topic.topicId, fromMessageId, 0, limit)) { result ->
            if (result is TdApi.Messages) callback(Result.success(result.messages.toList()))
            else callback(Result.failure(IllegalStateException("TDLib did not return topic messages.")))
        }
    }

    private fun key(edition: MedVaultEdition) = "source_${edition.name}"
    private fun titleKey(edition: MedVaultEdition) = "source_title_${edition.name}"
}
