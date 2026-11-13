package com.medvault.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DownloadStore {
    private const val PREFS = "medvault_downloads"
    private const val KEY_DOWNLOADS = "downloaded_lectures"

    @Synchronized
    fun getAll(context: Context): List<DownloadedLecture> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOADS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<DownloadedLecture>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val lecture = DownloadedLecture(
                    fileId = item.getString("fileId"),
                    name = item.getString("name"),
                    localPath = item.getString("localPath"),
                    downloadId = item.optLong("downloadId", 0L),
                    downloadedAt = item.getLong("downloadedAt"),
                    accountId = item.optString("accountId", "default"),
                    source = item.optString("source", "drive"),
                    chatId = item.optLong("chatId", 0L),
                    topicId = item.optInt("topicId", 0),
                    messageId = item.optLong("messageId", 0L),
                    telegramFileId = item.optInt("telegramFileId", 0),
                    mimeType = item.optString("mimeType", "video/mp4"),
                    durationSeconds = item.optInt("durationSeconds", 0)
                )
                if (File(lecture.localPath).isFile) result.add(lecture)
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun save(context: Context, lecture: DownloadedLecture) {
        val current = getAll(context)
            .filterNot { it.fileId == lecture.fileId && it.accountId == lecture.accountId }
            .toMutableList()
        current.add(lecture)
        write(context, current)
    }

    @Synchronized
    fun delete(context: Context, fileId: String, accountId: String? = null) {
        val current = getAll(context).filterNot {
            it.fileId == fileId && (accountId == null || it.accountId == accountId)
        }
        write(context, current)
    }

    @Synchronized
    fun find(context: Context, fileId: String, accountId: String = "default"): DownloadedLecture? =
        getAll(context).firstOrNull { it.fileId == fileId && it.accountId == accountId }

    @Synchronized
    private fun write(context: Context, lectures: List<DownloadedLecture>) {
        val array = JSONArray()
        lectures.forEach { lecture ->
            array.put(JSONObject().apply {
                put("fileId", lecture.fileId)
                put("name", lecture.name)
                put("localPath", lecture.localPath)
                put("downloadId", lecture.downloadId)
                put("downloadedAt", lecture.downloadedAt)
                put("accountId", lecture.accountId)
                put("source", lecture.source)
                put("chatId", lecture.chatId)
                put("topicId", lecture.topicId)
                put("messageId", lecture.messageId)
                put("telegramFileId", lecture.telegramFileId)
                put("mimeType", lecture.mimeType)
                put("durationSeconds", lecture.durationSeconds)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOWNLOADS, array.toString()).apply()
    }
}
