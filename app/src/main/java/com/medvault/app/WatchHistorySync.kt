package com.medvault.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WatchHistorySync(context: Context) {

    private val historyFile = File(
        context.filesDir,
        "medvault_watch_history.json"
    )

    companion object {
        private const val FILE_NAME = "medvault_watch_history.json"
        private const val APPDATA_URL =
            "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files"
    }

    fun loadLocal(): MutableMap<String, WatchRecord> {
        val result = mutableMapOf<String, WatchRecord>()

        if (!historyFile.exists()) return result

        val json = JSONObject(historyFile.readText())
        val records = json.optJSONArray("records") ?: JSONArray()

        for (i in 0 until records.length()) {
            val obj = records.getJSONObject(i)
            val record = WatchRecord(
                fileId = obj.getString("fileId"),
                name = obj.getString("name"),
                positionMs = obj.optLong("positionMs"),
                durationMs = obj.optLong("durationMs"),
                updatedAt = obj.optLong("updatedAt"),
                completed = obj.optBoolean("completed")
            )
            result[record.fileId] = record
        }

        return result
    }

    fun saveLocal(records: Map<String, WatchRecord>) {
        val root = JSONObject()
        val array = JSONArray()

        records.values.forEach { record ->
            array.put(record.toJson())
        }

        root.put("records", array)
        historyFile.writeText(root.toString())
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
        val query = "name='$FILE_NAME' and trashed=false"

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

        val result = mutableMapOf<String, WatchRecord>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val record = WatchRecord(
                fileId = obj.getString("fileId"),
                name = obj.getString("name"),
                positionMs = obj.optLong("positionMs"),
                durationMs = obj.optLong("durationMs"),
                updatedAt = obj.optLong("updatedAt"),
                completed = obj.optBoolean("completed")
            )
            result[record.fileId] = record
        }

        return result
    }

    private fun uploadRemote(
        token: String,
        records: Map<String, WatchRecord>
    ) {
        val query = "name='$FILE_NAME' and trashed=false"

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
                .put("name", FILE_NAME)
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
    }
}
