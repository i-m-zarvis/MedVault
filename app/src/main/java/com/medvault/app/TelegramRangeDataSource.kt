package com.medvault.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded-range Telegram source for Media3. Seeks request the required byte range. */
class TelegramRangeDataSource(
    private val auth: TelegramAuthManager,
    private val fileId: Int,
    initialFileSize: Long
) : BaseDataSource(false) {
    class Factory(private val auth: TelegramAuthManager, private val fileId: Int, private val size: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramRangeDataSource(auth, fileId, size)
    }

    companion object {
        private const val ALIGN = 1024L
        private const val WINDOW = 2L * 1024L * 1024L
        private val locks = java.util.concurrent.ConcurrentHashMap<Int, Any>()
    }

    private val fileLock = locks.computeIfAbsent(fileId) { Any() }
    private var uri: Uri? = null
    private var fileSize = initialFileSize.coerceAtLeast(0L)
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var file: RandomAccessFile? = null
    private var path: String? = null
    private var bytes = ByteArray(0)
    private var bytesOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileSize > 0L -> (fileSize - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        if (position < 0L || (fileSize > 0L && position > fileSize)) throw IOException("Invalid Telegram seek position.")
        bytes = ByteArray(0)
        bytesOffset = 0
        opened = true
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (bytesOffset >= bytes.size) {
            fill()
            if (bytesOffset >= bytes.size) return C.RESULT_END_OF_INPUT
        }
        val count = minOf(length, bytes.size - bytesOffset)
        System.arraycopy(bytes, bytesOffset, target, offset, count)
        bytesOffset += count
        position += count
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) { opened = false; transferEnded() }
        uri = null
        bytes = ByteArray(0)
        bytesOffset = 0
        try { file?.close() } catch (_: IOException) { }
        file = null
        path = null
    }

    private fun fill() {
        val start = position - position % ALIGN
        var limit = if (remaining in 1..WINDOW) remaining else WINDOW
        limit += position - start
        limit = ((limit + ALIGN - 1L) / ALIGN) * ALIGN
        if (fileSize > 0L) {
            val available = fileSize - start
            if (available <= 0L) { remaining = 0L; return }
            limit = minOf(limit, available)
        }
        val result = synchronized(fileLock) { downloadRange(start, limit) }
        if (result.size > 0L) {
            fileSize = result.size
            if (remaining != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, (fileSize - position).coerceAtLeast(0L))
            if (remaining == 0L) return
        }
        val localPath = result.local?.path?.takeIf { it.isNotBlank() }
            ?: throw IOException("Telegram did not return a local media path.")
        val diskFile = File(localPath)
        if (!diskFile.isFile || diskFile.length() <= start) throw IOException("Telegram media range is not available.")
        if (path != localPath || file == null) {
            try { file?.close() } catch (_: IOException) { }
            file = RandomAccessFile(diskFile, "r")
            path = localPath
        }
        val readLength = minOf(limit, diskFile.length() - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (readLength <= 0) throw IOException("Empty Telegram media range.")
        val chunk = ByteArray(readLength)
        val handle = file ?: throw IOException("Unable to open Telegram media cache.")
        handle.seek(start)
        var count = 0
        while (count < chunk.size) {
            val n = handle.read(chunk, count, chunk.size - count)
            if (n < 0) break
            count += n
        }
        val skip = (position - start).toInt()
        if (count <= skip) throw IOException("TDLib returned an incomplete media range.")
        bytes = if (count == chunk.size) chunk else chunk.copyOf(count)
        bytesOffset = skip
    }

    private fun downloadRange(offset: Long, limit: Long): TdApi.File {
        val latch = CountDownLatch(1)
        val response = AtomicReference<TdApi.Object?>()
        auth.sendRequest(TdApi.DownloadFile(fileId, 32, offset, limit, true)) {
            response.set(it)
            latch.countDown()
        }
        if (!latch.await(120, TimeUnit.SECONDS)) throw IOException("Timed out waiting for Telegram video data.")
        return when (val result = response.get()) {
            is TdApi.File -> result
            is TdApi.Error -> throw IOException("Telegram download error: ${result.message}")
            else -> throw IOException("Unexpected Telegram media response.")
        }
    }
}
