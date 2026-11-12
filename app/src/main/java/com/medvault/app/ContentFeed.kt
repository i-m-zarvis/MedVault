package com.medvault.app

import android.content.Context
import androidx.room.*
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val CONTENT_BASE = "https://raw.githubusercontent.com/i-m-zarvis/medvault-content/main/"
private const val REFRESH_WORK = "medvault-content-manual-refresh"
private const val PREFS = "medvault_content_sync"

@Entity(tableName = "content_items", primaryKeys = ["category", "itemId"])
data class ContentItemEntity(
    val category: String, val itemId: String, val title: String, val description: String?,
    val url: String, val sourceName: String, val tier: String, val publishedAt: String?,
    val collectedAt: String
)

@Dao
abstract class ContentFeedDao {
    @Query("SELECT * FROM content_items ORDER BY COALESCE(publishedAt, collectedAt) DESC")
    abstract fun observeAll(): Flow<List<ContentItemEntity>>

    @Query("DELETE FROM content_items WHERE category = :category")
    abstract suspend fun clearCategory(category: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<ContentItemEntity>)

    @Transaction
    open suspend fun replaceCategory(category: String, items: List<ContentItemEntity>) {
        clearCategory(category)
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Database(entities = [ContentItemEntity::class], version = 1, exportSchema = false)
abstract class ContentFeedDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentFeedDao()
    companion object {
        @Volatile private var INSTANCE: ContentFeedDatabase? = null
        fun get(context: Context): ContentFeedDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ContentFeedDatabase::class.java, "medvault-content.db")
                .build().also { INSTANCE = it }
        }
    }
}

object ContentFeedScheduler {
    fun refresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<ContentFeedSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<ContentFeedSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "medvault-content-refresh", ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        refresh(context)
    }

    fun manualWorkName() = REFRESH_WORK
}

private data class HttpPayload(val code: Int, val body: String?, val etag: String?)

class ContentFeedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dao = ContentFeedDatabase.get(applicationContext).contentDao()
        return try {
            // The manifest is deliberately checked on every run; it is small and defines the current feed set.
            val manifestResponse = request("feeds/manifest.json")
            if (manifestResponse.code !in 200..299 || manifestResponse.body.isNullOrBlank()) return retryOrFailure()
            val manifest = JSONObject(manifestResponse.body)
            require(manifest.optInt("schemaVersion") == 1) { "Unsupported manifest schema" }
            val feeds = manifest.optJSONArray("feeds") ?: error("Manifest has no feeds")
            val now = System.currentTimeMillis()
            var updated = 0
            var failed = 0

            for (index in 0 until feeds.length()) {
                val feed = feeds.getJSONObject(index)
                val category = feed.getString("id")
                val path = feed.getString("path")
                require(category.matches(Regex("[a-z0-9-]{2,48}"))) { "Invalid category" }
                require(path.startsWith("feeds/") && !path.contains("..") && !path.startsWith("/")) { "Invalid feed path" }
                val freshness = freshnessMillis(category)
                val lastSuccess = prefs.getLong("last_success_$category", 0L)
                val feedSignature = sha256(category + "|" + path + "|" + feed.optInt("count", -1) + "|" + feed.optString("updatedAt"))
                val previousSignature = prefs.getString("signature_$category", null)
                val metadataChanged = previousSignature != feedSignature
                if (!metadataChanged && now - lastSuccess < freshness) continue

                try {
                    val etag = prefs.getString("etag_$category", null)
                    val response = request(path, etag)
                    if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        prefs.edit().putLong("last_success_$category", now).apply()
                        updated++
                        continue
                    }
                    if (response.code !in 200..299 || response.body.isNullOrBlank()) error("Feed request failed: ${response.code}")
                    val payload = JSONObject(response.body)
                    require(payload.optInt("schemaVersion", 1) == 1) { "Unsupported feed schema" }
                    require(payload.optString("category") == category) { "Feed category mismatch" }
                    val items = payload.optJSONArray("items") ?: error("Feed has no items array")
                    val rows = ArrayList<ContentItemEntity>(items.length())
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val url = item.optString("url")
                        if (!url.startsWith("https://")) continue
                        val id = item.optString("id")
                        val title = item.optString("title")
                        if (id.isBlank() || title.isBlank()) continue
                        rows += ContentItemEntity(
                            category, id, title,
                            item.optString("description").takeIf { it.isNotBlank() && it != "null" },
                            url, item.optString("sourceName", "Source"),
                            item.optString("tier", "journalism"),
                            item.optString("publishedAt").takeIf { it.isNotBlank() && it != "null" },
                            item.optString("collectedAt", "")
                        )
                    }
                    // A valid empty feed is allowed to clear a category; a failed/invalid feed never does.
                    dao.replaceCategory(category, rows)
                    prefs.edit()
                        .putLong("last_success_$category", now)
                        .putString("etag_$category", response.etag)
                        .putString("signature_$category", feedSignature)
                        .apply()
                    updated++
                } catch (_: Exception) {
                    failed++ // Keep the previous Room rows for this category.
                }
            }
            Result.success(workDataOf("updated_feeds" to updated, "failed_feeds" to failed, "manifest_checked" to true))
        } catch (_: Exception) {
            retryOrFailure()
        }
    }

    private fun freshnessMillis(category: String): Long {
        val minutes = when (category) {
            "exam-radar" -> 15L
            "neet-pg", "mbbs-updates", "medical-news" -> 30L
            "trending-medicine" -> 120L
            else -> 24L * 60L
        }
        return TimeUnit.MINUTES.toMillis(minutes)
    }

    private fun retryOrFailure(): Result = if (runAttemptCount < 3) Result.retry() else Result.failure()

    private fun request(path: String, etag: String? = null): HttpPayload {
        val connection = (URL(CONTENT_BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("User-Agent", "MedVault-Android/1.0")
            if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
        }
        return try {
            val code = connection.responseCode
            val body = if (code == HttpURLConnection.HTTP_NOT_MODIFIED) null else {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }
            HttpPayload(code, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
