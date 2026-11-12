package com.medvault.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private const val CONTENT_BASE =
    "https://raw.githubusercontent.com/i-m-zarvis/medvault-content/main/"

@Entity(tableName = "content_items", primaryKeys = ["category", "itemId"])
data class ContentItemEntity(
    val category: String,
    val itemId: String,
    val title: String,
    val description: String?,
    val url: String,
    val sourceName: String,
    val tier: String,
    val publishedAt: String?,
    val collectedAt: String
)

@Dao
interface ContentFeedDao {
    @Query("SELECT * FROM content_items ORDER BY COALESCE(publishedAt, collectedAt) DESC")
    fun observeAll(): Flow<List<ContentItemEntity>>

    @Query("DELETE FROM content_items WHERE category = :category")
    suspend fun clearCategory(category: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ContentItemEntity>)

    @Query("SELECT COUNT(*) FROM content_items")
    suspend fun count(): Int
}

@Database(entities = [ContentItemEntity::class], version = 1, exportSchema = false)
abstract class ContentFeedDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentFeedDao

    companion object {
        @Volatile private var INSTANCE: ContentFeedDatabase? = null
        fun get(context: Context): ContentFeedDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContentFeedDatabase::class.java,
                    "medvault-content.db"
                ).build().also { INSTANCE = it }
            }
    }
}

object ContentFeedScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ContentFeedSyncWorker>(
            6, TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "medvault-content-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<ContentFeedSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}

class ContentFeedSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val dao = ContentFeedDatabase.get(applicationContext).contentDao()
            val manifest = JSONObject(download("feeds/manifest.json"))
            val feeds = manifest.optJSONArray("feeds") ?: return Result.failure()
            for (index in 0 until feeds.length()) {
                val feed = feeds.getJSONObject(index)
                val category = feed.getString("id")
                val path = feed.getString("path")
                val payload = JSONObject(download(path))
                val items = payload.optJSONArray("items") ?: continue
                val rows = ArrayList<ContentItemEntity>(items.length())
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val url = item.optString("url")
                    if (!url.startsWith("https://")) continue
                    rows += ContentItemEntity(
                        category = category,
                        itemId = item.getString("id"),
                        title = item.getString("title"),
                        description = item.optString("description").takeIf { it.isNotBlank() && it != "null" },
                        url = url,
                        sourceName = item.optString("sourceName", "Source"),
                        tier = item.optString("tier", "journalism"),
                        publishedAt = item.optString("publishedAt").takeIf { it.isNotBlank() && it != "null" },
                        collectedAt = item.optString("collectedAt", "")
                    )
                }
                dao.clearCategory(category)
                if (rows.isNotEmpty()) dao.insertAll(rows)
            }
            Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun download(path: String): String {
        require(!path.startsWith("/") && !path.contains("..")) { "Invalid content path" }
        val connection = URL(CONTENT_BASE + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "MedVault-Android/1.0")
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
