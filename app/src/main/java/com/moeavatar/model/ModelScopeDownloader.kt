package com.moeavatar.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * 精简版 ModelScope 下载器：为「能力中心」按需拉取 [NekoModel.requiredFiles]。
 *
 * v0.6.2 · 下载优化：
 *  - 文件级并发 [CONCURRENCY]（6）：小文件各占一条连接；大文件进一步拆成 Range 分片。
 *  - 大于 [SEGMENT_THRESHOLD] 的文件拆成 [SEGMENTS] 个分片并发下载，完成后顺序合并。
 *  - 首帧立刻上报：`download()` 一进就 emit 一次；每进入一个新文件也立刻 emit —— UI 不再"卡 0MB"。
 *  - 重试也上报：catch 分支通过 [ProgressReporter.reportRetry] 送 "文件 · 重试 x/y" 信号。
 *  - buffer 64KB → 256KB。
 *
 * 稳定性沿用：
 *  - Range: bytes=N-  续传；服务端返回 200 而非 206 时自动回退到从头写。
 *  - 单文件网络异常退避重试 [MAX_RETRY] 次；超过后返回 Failed，其他并发文件通过 firstFail 短路收工。
 *
 * ModelScope API（匿名可访问）:
 *   文件清单  GET /api/v1/models/{repo}/repo/files?Recursive=1 → Data.Files[{Path,Size,Type}]
 *   文件下载  GET /api/v1/models/{repo}/repo?FilePath={path}
 */
object ModelScopeDownloader {

    private const val TAG = "MoeAvatar.MsDL"
    private const val API = "https://modelscope.cn/api/v1/models"
    private const val MAX_RETRY = 3
    private const val BUF = 256 * 1024
    private const val CONCURRENCY = 6
    // 语音模型的 te.mnn/vocoder.mnn 只有 18/50MB；阈值过高时它们仍是单连接，
    // 用户会误以为“分片没有生效”。8MB 以上统一走 Range，覆盖所有模型的大文件。
    private const val SEGMENT_THRESHOLD = 8L * 1024 * 1024
    private const val SEGMENTS = 4
    private const val TICK_INTERVAL_NS = 200_000_000L  // 200ms

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 进度回调：已下字节 / 总字节 / 瞬时速度(B/s) / 当前文件名（并发下为最新写入者；重试时携带 " · 重试 x/y"）。 */
    fun interface Progress {
        fun onProgress(downloaded: Long, total: Long, speedBps: Long, currentFile: String)
    }

    sealed class Result {
        object Success : Result()
        object Stopped : Result()               // 被 shouldStop 打断，.part 已保留
        data class Failed(val message: String) : Result()
    }

    /**
     * 下载 [model] 的所有 requiredFiles 到私有目录。已完整存在的文件跳过。
     * @param shouldStop 每个读循环 tick 检查；返回 true 立即停下（保留 .part）。
     */
    suspend fun download(
        ctx: Context,
        model: NekoModel,
        shouldStop: () -> Boolean,
        onProgress: Progress,
    ): Result = withContext(Dispatchers.IO) {
        val dir = ModelManager.dirOf(ctx, model).apply { mkdirs() }
        makeModelPathAccessible(dir)

        val sizes = fetchSizes(model)
        val total = model.requiredFiles.sumOf { sizes[it] ?: 0L }.takeIf { it > 0 } ?: model.sizeBytes

        // 完整文件计入基线，剩下的进队列。
        var baseDone = 0L
        val toDownload = ArrayList<String>(model.requiredFiles.size)
        for (f in model.requiredFiles) {
            val fin = File(dir, f)
            val expect = sizes[f] ?: -1L
            if (fin.isFile && (expect < 0 || fin.length() == expect)) baseDone += fin.length()
            else toDownload.add(f)
        }
        if (toDownload.isEmpty()) {
            onProgress.onProgress(baseDone, total, 0, "")
            return@withContext Result.Success
        }

        val globalDone = AtomicLong(baseDone)
        val reporter = ProgressReporter(globalDone, total, onProgress)
        // 首帧：进入 download 就先 emit，UI 立刻从"准备中"过渡到"下载中 · <首个文件>"。
        reporter.forceEmit(toDownload.first(), speedBps = 0)

        // 这是连接级并发上限，而不是文件数上限。大文件的多个 Range 分片
        // 和小文件共享这一个信号量，避免“3 个文件 × 4 分片”失控地开 12 条连接。
        val sem = Semaphore(CONCURRENCY)
        val firstFail = AtomicReference<Result.Failed?>(null)

        coroutineScope {
            for (f in toDownload) {
                launch {
                    if (firstFail.get() != null || shouldStop()) return@launch
                    val r = downloadFile(
                        model = model,
                        file = f,
                        dir = dir,
                        expect = sizes[f] ?: -1L,
                        globalDone = globalDone,
                        reporter = reporter,
                        shouldStop = shouldStop,
                        connectionSem = sem,
                    )
                    if (r is Result.Failed) firstFail.compareAndSet(null, r)
                }
            }
        }

        firstFail.get()?.let { return@withContext it }
        if (shouldStop()) return@withContext Result.Stopped
        // 收尾刷一发 100%，UI 好切"已就绪"。
        reporter.forceEmit("", speedBps = 0)
        Result.Success
    }

    /** 下单个文件，带 Range 续传 + 退避重试。返回 Success/Stopped/Failed。 */
    private suspend fun downloadFile(
        model: NekoModel,
        file: String,
        dir: File,
        expect: Long,
        globalDone: AtomicLong,
        reporter: ProgressReporter,
        shouldStop: () -> Boolean,
        connectionSem: Semaphore,
    ): Result {
        val part = File(dir, "$file.part")
        val fin = File(dir, file)
        // Voice files use a nested relative path such as voices/catgirl_style.json.
        fin.parentFile?.mkdirs()

        // 进入这一文件先打一发（速度 0），UI 立刻切成新文件名，不用等首字节。
        reporter.forceEmit(file, speedBps = 0)

        if (expect >= SEGMENT_THRESHOLD) {
            // 旧版本的单文件断点也迁移成分片，避免升级后仍被一条连接拖慢。
            // 迁移只发生在本地磁盘，保留原有字节，不会重新下载。
            val migrated = !part.exists() || migrateLegacyPart(part, dir, file, expect)
            if (migrated) {
                return downloadSegmentedFile(
                    model, file, dir, expect, globalDone, reporter, shouldStop, connectionSem,
                )
            }
        }

        return connectionSem.withPermit {
            downloadSingleFile(model, file, dir, expect, globalDone, reporter, shouldStop)
        }
    }

    private fun migrateLegacyPart(part: File, dir: File, file: String, expect: Long): Boolean {
        val count = minOf(SEGMENTS, maxOf(1, ((expect + SEGMENT_THRESHOLD - 1) / SEGMENT_THRESHOLD).toInt()))
        val chunk = (expect + count - 1) / count
        val existingSegments = (0 until count).map { File(dir, "$file.part.$it") }
        if (existingSegments.any { it.exists() }) return false
        return try {
            RandomAccessFile(part, "r").use { input ->
                for (i in 0 until count) {
                    val start = i * chunk
                    val wanted = minOf(expect, start + chunk) - start
                    val available = (input.length() - start).coerceIn(0L, wanted)
                    if (available == 0L) continue
                    input.seek(start)
                    FileOutputStream(existingSegments[i]).use { output ->
                        val buf = ByteArray(BUF)
                        var left = available
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            left -= n
                        }
                        if (left != 0L) throw IOException("legacy part copy incomplete")
                    }
                }
            }
            part.delete()
        } catch (e: Exception) {
            existingSegments.forEach { it.delete() }
            Log.w(TAG, "migrate legacy part failed for $file: ${e.message}")
            false
        }
    }

    /** 大文件 Range 分片下载。每个 .part.N 都可独立断点续传。 */
    private suspend fun downloadSegmentedFile(
        model: NekoModel,
        file: String,
        dir: File,
        expect: Long,
        globalDone: AtomicLong,
        reporter: ProgressReporter,
        shouldStop: () -> Boolean,
        connectionSem: Semaphore,
    ): Result {
        val url = "$API/${model.msRepo}/repo?FilePath=${enc(file)}"
        val count = minOf(SEGMENTS, maxOf(1, ((expect + SEGMENT_THRESHOLD - 1) / SEGMENT_THRESHOLD).toInt()))
        val chunk = (expect + count - 1) / count
        val partFiles = (0 until count).map { File(dir, "$file.part.$it") }
        val partSizes = partFiles.mapIndexed { i, p ->
            val start = i * chunk
            val end = minOf(expect, start + chunk)
            (p to (end - start))
        }

        var partialDone = 0L
        for ((p, wanted) in partSizes) {
            if (p.length() > wanted) RandomAccessFile(p, "rw").use { it.setLength(wanted) }
            partialDone += p.length()
        }
        if (partialDone > 0) globalDone.addAndGet(partialDone)

        val firstFail = AtomicReference<Result.Failed?>(null)
        coroutineScope {
            for ((index, item) in partSizes.withIndex()) {
                launch {
                    if (item.first.length() == item.second || shouldStop()) return@launch
                    val r = connectionSem.withPermit {
                        downloadSegment(
                            url, file, index, item.first, item.second, chunk,
                            globalDone, reporter, shouldStop,
                        )
                    }
                    if (r is Result.Failed) firstFail.compareAndSet(null, r)
                }
            }
        }
        firstFail.get()?.let { return it }
        if (shouldStop()) return Result.Stopped
        if (partSizes.any { it.first.length() != it.second }) {
            return Result.Failed("下载失败：$file（分片大小不完整）")
        }

        val fin = File(dir, file)
        try {
            FileOutputStream(fin).use { out ->
                for ((p, _) in partSizes) FileInputStream(p).use { it.copyTo(out, BUF) }
            }
            if (fin.length() != expect) throw IOException("merged size ${fin.length()} != expect $expect")
            partFiles.forEach { it.delete() }
            makeModelPathAccessible(fin)
            reporter.forceEmit(file, speedBps = 0)
            return Result.Success
        } catch (e: Exception) {
            fin.delete()
            return Result.Failed("下载失败：$file（合并分片失败：${e.message}）")
        }
    }

    private fun downloadSegment(
        url: String,
        file: String,
        index: Int,
        part: File,
        wanted: Long,
        chunk: Long,
        globalDone: AtomicLong,
        reporter: ProgressReporter,
        shouldStop: () -> Boolean,
    ): Result {
        var onDisk = part.length()
        var attempt = 0
        while (true) {
            if (shouldStop()) return Result.Stopped
            val from = onDisk
            val start = index * chunk + from
            val end = index * chunk + wanted - 1
            try {
                val req = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 206) throw IOException("HTTP ${resp.code}, Range unsupported for $file")
                    val contentRange = resp.header("Content-Range")
                        ?: throw IOException("missing Content-Range for $file segment ${index + 1}")
                    val rangeBody = contentRange.removePrefix("bytes ").substringBefore('/')
                    val dash = rangeBody.indexOf('-')
                    if (dash <= 0) throw IOException("invalid Content-Range '$contentRange'")
                    val actualStart = rangeBody.substring(0, dash).toLongOrNull()
                    val actualEnd = rangeBody.substring(dash + 1).toLongOrNull()
                    if (actualStart != start || actualEnd != end) {
                        throw IOException("wrong Content-Range '$contentRange', expected bytes=$start-$end")
                    }
                    val body = resp.body ?: throw IOException("empty body for $file")
                    java.io.FileOutputStream(part, true).use { out ->
                        body.byteStream().use { ins ->
                            val buf = ByteArray(BUF)
                            while (true) {
                                if (shouldStop()) return Result.Stopped
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                onDisk += n
                                globalDone.addAndGet(n.toLong())
                                reporter.tick("$file · 分片 ${index + 1}")
                            }
                        }
                    }
                }
                if (onDisk != wanted) throw IOException("segment ${onDisk}/$wanted")
                return Result.Success
            } catch (e: Exception) {
                if (shouldStop()) return Result.Stopped
                attempt++
                Log.w(TAG, "download $file segment ${index + 1} failed (attempt $attempt/$MAX_RETRY): ${e.message}")
                if (attempt >= MAX_RETRY) return Result.Failed("下载失败：$file（分片 ${index + 1}：${e.message}）")
                reporter.reportRetry("$file · 分片 ${index + 1}", attempt, MAX_RETRY)
                try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) {}
            }
        }
    }

    /** 单连接文件下载，保留原有 .part + Range 续传逻辑。 */
    private fun downloadSingleFile(
        model: NekoModel,
        file: String,
        dir: File,
        expect: Long,
        globalDone: AtomicLong,
        reporter: ProgressReporter,
        shouldStop: () -> Boolean,
    ): Result {
        val part = File(dir, "$file.part")
        val fin = File(dir, file)
        val url = "$API/${model.msRepo}/repo?FilePath=${enc(file)}"

        // `.part` 已有的字节要计入 globalDone（download() 里的 baseDone 只算完整文件）。
        var onDisk = if (part.exists()) part.length() else 0L
        if (onDisk > 0) globalDone.addAndGet(onDisk)

        var attempt = 0
        while (true) {
            if (shouldStop()) return Result.Stopped
            val from = onDisk
            try {
                val req = Request.Builder().url(url).apply {
                    if (from > 0) header("Range", "bytes=$from-")
                }.build()
                client.newCall(req).execute().use { resp ->
                    // 服务器不支持续传（返回 200 而非 206）时，从头写。
                    val restart = from > 0 && resp.code == 200
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $file")
                    val body = resp.body ?: throw IOException("empty body for $file")

                    RandomAccessFile(part, "rw").use { raf ->
                        if (restart) {
                            raf.setLength(0)
                            globalDone.addAndGet(-onDisk)
                            onDisk = 0L
                        } else {
                            raf.seek(from)
                        }
                        val buf = ByteArray(BUF)
                        body.byteStream().use { ins ->
                            while (true) {
                                if (shouldStop()) return Result.Stopped
                                val n = ins.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                onDisk += n
                                globalDone.addAndGet(n.toLong())
                                reporter.tick(file)
                            }
                        }
                    }
                }
                // 校验大小后 rename
                if (expect >= 0 && part.length() != expect) {
                    Log.w(TAG, "$file size ${part.length()} != expect $expect, retrying")
                    throw IOException("size mismatch $file")
                }
                if (fin.exists()) fin.delete()
                if (!part.renameTo(fin)) throw IOException("rename failed $file")
                makeModelPathAccessible(fin)
                reporter.forceEmit(file, speedBps = 0)  // 文件完成，也刷一发
                return Result.Success
            } catch (e: Exception) {
                if (shouldStop()) return Result.Stopped
                attempt++
                Log.w(TAG, "download $file failed (attempt $attempt/$MAX_RETRY): ${e.message}")
                if (attempt >= MAX_RETRY) return Result.Failed("下载失败：$file（${e.message}）")
                reporter.reportRetry(file, attempt, MAX_RETRY)
                try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) {}
            }
        }
    }

    /** 拉文件清单，返回 path -> size。失败返回空表（调用方回退到 registry 估算）。 */
    private fun fetchSizes(model: NekoModel): Map<String, Long> {
        val url = "$API/${model.msRepo}/repo/files?Recursive=1"
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val json = JSONObject(resp.body?.string() ?: return emptyMap())
                val files = json.optJSONObject("Data")?.optJSONArray("Files") ?: return emptyMap()
                val map = HashMap<String, Long>()
                for (i in 0 until files.length()) {
                    val o = files.getJSONObject(i)
                    if (o.optString("Type") == "blob") map[o.optString("Path")] = o.optLong("Size")
                }
                map
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchSizes failed: ${e.message}")
            emptyMap()
        }
    }

    private fun enc(path: String): String = java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")

    /** Android 外部 app-specific 目录在部分系统/FUSE 实现下需要显式的读/遍历位。 */
    private fun makeModelPathAccessible(file: File) {
        file.setReadable(true, false)
        var p = if (file.isDirectory) file else file.parentFile
        while (p != null && p.absolutePath.startsWith("/storage/emulated/0/Android/data/")) {
            p.setReadable(true, false)
            p.setExecutable(true, false)
            p = p.parentFile
        }
    }

    /**
     * 200ms 节流的进度上报器。并发多个文件共享一个实例，靠 @Synchronized 收敛。
     * bps = 200ms 窗口内 globalDone 增量的瞬时速率（不做 EMA；UI 侧显示已足够平滑）。
     */
    private class ProgressReporter(
        private val globalDone: AtomicLong,
        private val total: Long,
        private val cb: Progress,
    ) {
        @Volatile private var lastEmitNs = System.nanoTime()
        @Volatile private var lastEmitDone = globalDone.get()

        @Synchronized
        fun tick(currentFile: String) {
            val now = System.nanoTime()
            val elapsed = now - lastEmitNs
            if (elapsed < TICK_INTERVAL_NS) return
            val done = globalDone.get()
            val bps = (done - lastEmitDone) * 1_000_000_000L / max(1L, elapsed)
            lastEmitNs = now
            lastEmitDone = done
            cb.onProgress(done, total, bps, currentFile)
        }

        @Synchronized
        fun forceEmit(currentFile: String, speedBps: Long) {
            val done = globalDone.get()
            lastEmitNs = System.nanoTime()
            lastEmitDone = done
            cb.onProgress(done, total, speedBps, currentFile)
        }

        @Synchronized
        fun reportRetry(file: String, attempt: Int, max: Int) {
            val done = globalDone.get()
            cb.onProgress(done, total, 0, "$file · 重试 $attempt/$max")
        }
    }
}
