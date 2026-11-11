package com.medvault.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WatchHistorySync(context: Context, private val accountId: String = "default") {

    private val fileName = if (accountId == "default") {
        "medvault_watch_history.json"
    } else {
        "medvault_watch_history_${DownloadRepository.accountKey(accountId)}.json"
    }
    private val historyFile = File(context.filesDir, fileName)

    companion object {
        private const val LEGACY_fileName = "medvault_watch_history.json"
        private const val APPDATA_URL =
            "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files"
    }

    fun loadLocal(): MutableMap<String, WatchRecord> {
        if (!historyFile.exists()) return mutableMapOf()

        val json = JSONObject(historyFile.readText())
        val records = json.optJSONArray("records") ?: JSONArray()
        return parseRecords(records)
    }

    fun saveLocal(records: Map<String, WatchRecord>) {
        val root = JSONObject()
        val array = JSONArray()

        records.values
            .distinctBy { it.fileId }
            .forEach { record -> array.put(record.toJson()) }

        root.put("records", array)
        val temporaryFile = File(historyFile.parentFile, "${historyFile.name}.tmp")
        temporaryFile.writeText(root.toString())

        try {
            java.nio.file.Files.move(
                temporaryFile.toPath(),
                historyFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporaryFile.toPath(),
                historyFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun parseRecords(array: JSONArray): MutableMap<String, WatchRecord> {
        val result = mutableMapOf<String, WatchRecord>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val fileId = obj.optString("fileId").takeIf { it.isNotBlank() } ?: continue
            val record = WatchRecord(
                fileId = fileId,
                name = obj.optString("name", "Untitled lecture"),
                positionMs = obj.optLong("positionMs").coerceAtLeast(0L),
                durationMs = obj.optLong("durationMs").coerceAtLeast(0L),
                updatedAt = obj.optLong("updatedAt").coerceAtLeast(0L),
                completed = obj.optBoolean("completed"),
                accountId = obj.optString("accountId", accountId)
            )

            val existing = result[fileId]
            if (existing == null || record.updatedAt > existing.updatedAt) {
                result[fileId] = record
            }
        }

        return result
    }

    fun sync(token: String, local: Map<String, WatchRecord>): Map<String, WatchRecord> {
        val remote = downloadRemote(token)
        val merged = mutableMapOf<String, WatchRecord>()

        (local.keys + remote.keys).forEach { id ->
            val localRecord = local[id]
            val remoteRecord = remote[id]

            val newest = when {
                localRecord == null -> remoteRecord
                remoteRecord == null -> localRecord
                localRecord.updatedAt >= remoteRecord.updatedAt -> localRecord
                else -> remoteRecord
            }

            if (newest != null) merged[id] = newest
        }

        saveLocal(merged)
        uploadRemote(token, merged)

        return merged
    }

    private fun downloadRemote(token: String): MutableMap<String, WatchRecord> {
        val query = "name='$fileName' and trashed=false"

        val url = URL(
            "$APPDATA_URL?spaces=appDataFolder&q=" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&fields=files(id,name)"
        )

        val response = request(url, "GET", token)
        val files = JSONObject(response).optJSONArray("files")
            ?: JSONArray()

        if (files.length() == 0) return mutableMapOf()

        val fileId = files.getJSONObject(0).getString("id")

        val contentUrl = URL(
            "$APPDATA_URL/$fileId?alt=media"
        )

        val content = request(contentUrl, "GET", token)
        val array = JSONObject(content).optJSONArray("records")
            ?: JSONArray()

        return parseRecords(array)
    }

    private fun uploadRemote(
        token: String,
        records: Map<String, WatchRecord>
    ) {
        val query = "name='$fileName' and trashed=false"

        val listUrl = URL(
            "$APPDATA_URL?spaces=appDataFolder&q=" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&fields=files(id)"
        )

        val response = request(listUrl, "GET", token)
        val files = JSONObject(response).optJSONArray("files")
            ?: JSONArray()

        val root = JSONObject()
        val array = JSONArray()
        records.values.forEach { array.put(it.toJson()) }
        root.put("records", array)

        val fileId: String

        if (files.length() > 0) {
            fileId = files.getJSONObject(0).getString("id")
        } else {
            val metadata = JSONObject()
                .put("name", fileName)
                .put("mimeType", "application/json")
                .put("parents", JSONArray().put("appDataFolder"))

            val createUrl = URL("$APPDATA_URL?fields=id")
            val created = request(
                createUrl,
                "POST",
                token,
                metadata.toString(),
                "application/json"
            )

            fileId = JSONObject(created).getString("id")
        }

        val uploadUrl = URL(
            "$UPLOAD_URL/$fileId?uploadType=media"
        )

        request(
            uploadUrl,
            "PATCH",
            token,
            root.toString(),
            "application/json"
        )
    }

    private fun request(
        url: URL,
        method: String,
        token: String,
        body: String? = null,
        contentType: String = "application/json"
    ): String {
        val connection = url.openConnection() as HttpURLConnection

        try {
            if (method == "PATCH") {
    connection.requestMethod = "POST"
    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
} else {
    connection.requestMethod = method
            }
            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )
            connection.connectTimeout = 20000
            connection.readTimeout = 30000

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    contentType
                )
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (code !in 200..299) {
                throw IllegalStateException(
                    "Watch history sync failed ($code): $response"
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun WatchRecord.toJson(): JSONObject {
        return JSONObject()
            .put("fileId", fileId)
            .put("name", name)
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("updatedAt", updatedAt)
            .put("completed", completed)
            .put("accountId", accountId)
    }
}
