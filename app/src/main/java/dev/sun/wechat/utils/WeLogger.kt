package dev.sun.wechat.utils

import android.util.Log
import dev.sun.wechat.BuildConfig
import dev.sun.wechat.utils.fs.KnownPaths
import dev.sun.wechat.utils.fs.createDirsSafe
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.math.min

object WeLogger {

    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    private const val MAX_CHUNKS = 200
    private const val QUEUE_CAPACITY = 2048
    private const val RESERVED_IMPORTANT_CAPACITY = 128
    private const val BATCH_SIZE = 64
    private const val FLUSH_TIMEOUT_MILLIS = 3000L
    private const val MAX_LOG_AGE_DAYS = 3L

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private sealed interface WriteTask {
        data class Record(
            val level: String,
            val tag: String?,
            val msg: String,
            val throwable: Throwable?,
            val timestamp: LocalDateTime,
        ) : WriteTask

        class Flush(val completed: CountDownLatch) : WriteTask
    }

    /**
     * A bounded queue keeps logging fire-and-forget even if storage is temporarily slow. A
     * dropped-record counter is emitted by the writer once it catches up, so loss is visible.
     */
    private val writeQueue = ArrayBlockingQueue<WriteTask>(QUEUE_CAPACITY, true)
    private val droppedRecords = AtomicLong()
    private val writerThread = Thread(::runWriter, "WeKit-Logger").apply {
        isDaemon = true
        start()
    }

    private var writer: FileWriter? = null
    private var currentLogDate: LocalDate? = null

    // ========== File Logging Internals ==========

    private fun getOrRotateWriter(logDate: LocalDate): FileWriter? {
        if (writer != null && currentLogDate == logDate) return writer

        writer?.runCatching { close() }
        writer = null
        currentLogDate = null

        val logsDir = runCatching {
            (KnownPaths.moduleRoot / "logs").createDirsSafe()
        }.getOrNull() ?: return null

        // Clean up logs older than 3 days during rotation/initialization
        deleteOldLogs(logsDir)

        val logPath = logsDir / "wekit-${dateFmt.format(logDate)}.log"

        return runCatching {
            FileWriter(logPath.toFile(), true).also {
                writer = it
                currentLogDate = logDate
            }
        }.getOrNull()
    }

    private fun deleteOldLogs(logsDir: java.nio.file.Path) {
        runCatching {
            val thresholdDate = LocalDate.now().minusDays(MAX_LOG_AGE_DAYS)
            val logFileRegex = Regex("""wekit-(\d{4}-\d{2}-\d{2})\.log""")

            logsDir.toFile().listFiles()?.forEach { file ->
                val match = logFileRegex.matchEntire(file.name)
                if (match != null) {
                    val dateStr = match.groupValues[1]
                    val fileDate = runCatching { LocalDate.parse(dateStr, dateFmt) }.getOrNull()

                    // If the log file date is older than 3 days ago, delete it
                    if (fileDate != null && fileDate.isBefore(thresholdDate)) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun runWriter() {
        val batch = ArrayList<WriteTask>(BATCH_SIZE)

        while (!Thread.currentThread().isInterrupted) {
            val first = try {
                writeQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            batch += first
            writeQueue.drainTo(batch, BATCH_SIZE - 1)

            var hasWrites = false
            batch.forEach { task ->
                when (task) {
                    is WriteTask.Record -> {
                        writeDroppedNotice(task.timestamp)
                        val written = writeRecord(task)
                        hasWrites = written || hasWrites
                        if (written && (task.level == "E" || task.level == "W" || task.level == "A")) {
                            flushWriter()
                            hasWrites = false
                        }
                    }

                    is WriteTask.Flush -> {
                        try {
                            writeDroppedNotice(LocalDateTime.now())
                            flushWriter()
                        } finally {
                            task.completed.countDown()
                        }
                        hasWrites = false
                    }
                }
            }

            if (hasWrites) flushWriter()
            batch.clear()
        }
    }

    private fun writeRecord(record: WriteTask.Record): Boolean {
        val w = getOrRotateWriter(record.timestamp.toLocalDate()) ?: return false
        return runCatching {
            w.write(buildString {
                append(timestampFmt.format(record.timestamp))
                append(' ')
                append(record.level)
                append('/')
                append(TAG)
                append(' ')
                append(record.tag)
                append(": ")
                append(record.msg)
                if (record.throwable != null) {
                    append('\n')
                    append(Log.getStackTraceString(record.throwable))
                }
            })
            w.write('\n'.code)
            true
        }.getOrElse {
            Log.e(TAG, "failed to write log file", it)
            false
        }
    }

    private fun writeDroppedNotice(timestamp: LocalDateTime) {
        val count = droppedRecords.getAndSet(0)
        if (count == 0L) return

        writeRecord(
            WriteTask.Record(
                level = "W",
                tag = "WeLogger",
                msg = "dropped $count log record(s) because the async queue was full or reserved for important logs",
                throwable = null,
                timestamp = timestamp,
            )
        )
    }

    private fun flushWriter() {
        writer?.runCatching { flush() }
    }

    private fun enqueue(record: WriteTask.Record) {
        val isImportant = record.level == "E" || record.level == "W" || record.level == "A"
        val hasRoom = isImportant || writeQueue.remainingCapacity() > RESERVED_IMPORTANT_CAPACITY
        if (!hasRoom || !writeQueue.offer(record)) {
            droppedRecords.incrementAndGet()
        }
    }

    /**
     * Wait for all records currently queued, then flush the active writer. This is intentionally
     * blocking because it is used at explicit synchronization points such as crash handling and
     * before the log viewer reads the current file; normal log calls never wait for the writer.
     */
    fun flush() {
        if (Thread.currentThread() === writerThread) {
            writeDroppedNotice(LocalDateTime.now())
            flushWriter()
            return
        }

        val completed = CountDownLatch(1)
        val barrier = WriteTask.Flush(completed)
        val enqueued = try {
            writeQueue.offer(barrier, FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing log flush barrier")
            return
        }

        val finished = try {
            completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            Log.w(TAG, "timed out while flushing log queue")
        }
    }

    // ========== File Logging: public accessors (for the log viewer UI) ==========

    /** The directory run logs are written to (`filesDir/wekit/logs`), created on first access. */
    val logsDir: java.nio.file.Path?
        get() = runCatching { (KnownPaths.moduleRoot / "logs").createDirsSafe() }.getOrNull()

    /**
     * All run-log files (`wekit-yyyy-MM-dd.log`), newest first. Flushes the active writer first so
     * the current day's file reflects the latest entries before the UI reads it.
     */
    val allLogFiles: List<java.nio.file.Path>
        get() {
            flush()
            val dir = logsDir ?: return emptyList()
            deleteOldLogs(dir)
            val regex = Regex("""wekit-\d{4}-\d{2}-\d{2}\.log""")
            return runCatching {
                dir.toFile().listFiles()
                    ?.filter { it.isFile && regex.matches(it.name) }
                    ?.sortedByDescending { it.name }
                    ?.map { it.toPath() }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

    // ========== Diagnostics: compact digest for the in-app self-check log card ==========

    /** 单条问题记录（W/E/A 级日志 + 其后续行，如堆栈）。 */
    data class LogProblem(val level: String, val header: String, val detail: List<String>)

    /** 运行日志的精简摘要，供诊断页展示与一键复制。 */
    data class LogDigest(
        val fileName: String?,
        val fileSizeText: String,
        val totalRecords: Int,
        val errorCount: Int,
        val warnCount: Int,
        val droppedCount: Long,
        val problems: List<LogProblem>,
    ) {
        /** 结构化、可分享的精简文本（问题优先，控制每条堆栈行数）。 */
        fun toShareText(): String = buildString {
            appendLine("【WeKit 运行日志摘要】")
            appendLine("文件：${fileName ?: "无"}（$fileSizeText）")
            appendLine("记录：$totalRecords 条 ｜ 错误 $errorCount ｜ 警告 $warnCount" +
                if (droppedCount > 0L) " ｜ 队列丢弃 $droppedCount" else "")
            if (problems.isEmpty()) {
                append("问题：无（未见错误或警告）")
            } else {
                appendLine("问题（最近 ${problems.size} 条，新→旧）：")
                problems.forEach { p ->
                    appendLine("· ${p.header}")
                    p.detail.take(8).forEach { detail -> appendLine("    $detail") }
                }
            }
        }
    }

    private const val TAIL_READ_BYTES = 256 * 1024L
    private val recordHeadRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [VDIWEA]/""")
    private val recordLevelRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} ([VDIWEA])/""")

    /**
     * 收集最近日志的精简摘要（问题列表 + 计数）。
     *
     * 只读取文件尾部固定字节数，避免把整份日志（可能数 MB）读进内存；随后按行首时间戳
     * 切分成记录，仅保留 W/E/A 级，供诊断页展示与「复制问题摘要」使用。
     */
    fun digest(maxProblems: Int = 40): LogDigest {
        flush()
        val file = allLogFiles.firstOrNull()
            ?: return LogDigest(null, "0 B", 0, 0, 0, droppedRecords.get(), emptyList())
        val bytes = runCatching { java.nio.file.Files.size(file) }.getOrDefault(0L)
        val records = splitRecords(readTail(file, TAIL_READ_BYTES))
        var errors = 0
        var warns = 0
        val problems = ArrayList<LogProblem>()
        records.forEach { rec ->
            when (recordLevelRegex.find(rec.first())?.groupValues?.get(1)) {
                "E", "A" -> {
                    errors++
                    problems += LogProblem("E", rec.first(), rec.drop(1))
                }
                "W" -> {
                    warns++
                    problems += LogProblem("W", rec.first(), rec.drop(1))
                }
            }
        }
        return LogDigest(
            fileName = file.fileName.toString(),
            fileSizeText = formatBytes(bytes),
            totalRecords = records.size,
            errorCount = errors,
            warnCount = warns,
            droppedCount = droppedRecords.get(),
            problems = problems.takeLast(maxProblems).asReversed(),
        )
    }

    /** 最近 [maxLines] 行原始日志（当前日志文件，按时间正序）。 */
    fun readRecentLines(maxLines: Int = 300): kotlin.Pair<String?, List<String>> {
        flush()
        val file = allLogFiles.firstOrNull() ?: return null to emptyList()
        return file.fileName.toString() to readTail(file, TAIL_READ_BYTES).split('\n').takeLast(maxLines)
    }

    private fun readTail(file: java.nio.file.Path, maxBytes: Long): String = runCatching {
        val size = java.nio.file.Files.size(file)
        val start = maxOf(0L, size - maxBytes)
        val text = java.nio.file.Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ).use { channel ->
            channel.position(start)
            val buf = java.nio.ByteBuffer.allocate((size - start).toInt())
            while (buf.hasRemaining() && channel.read(buf) > 0) Unit
            String(buf.array(), 0, buf.position(), java.nio.charset.StandardCharsets.UTF_8)
        }
        // 从文件中部截断时，丢掉首个不完整的行
        if (start > 0L) text.substringAfter('\n', "") else text
    }.getOrDefault("")

    private fun splitRecords(text: String): List<List<String>> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in text.split('\n')) {
            val isHead = recordHeadRegex.containsMatchIn(line)
            if (isHead) {
                if (current.isNotEmpty()) out += current
                current = ArrayList()
            }
            if (isHead || current.isNotEmpty()) current += line
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes >= 1024L) "${bytes / 1024L} KB" else "$bytes B"

    // ========== Tag + String ==========

    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("E", tag, msg, null, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("W", tag, msg, null, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("I", tag, msg, null, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("D", tag, msg, null, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("V", tag, msg, null, LocalDateTime.now()))
    }

    // ========== Tag + String + Throwable ==========

    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("E", tag, msg, e, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("W", tag, msg, e, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("I", tag, msg, e, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("D", tag, msg, e, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("V", tag, msg, e, LocalDateTime.now()))
    }

    // ========== Stack Trace ==========

    val currentStackTrace: String
        get() {
            return Thread.currentThread().stackTrace
                .drop(2) // drop getStackTrace + this function
                .joinToString(separator = "\n") { element ->
                    "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
        }

    // ========== Chunked ==========

    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, TAG, "$tag: $msg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, msg, null, LocalDateTime.now()))
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            val headMsg = "[chunked] too long ($len chars, $chunkCount chunks). head:\n$head"
            val truncMsg = "[chunked] truncated. consider writing to file for full dump."
            Log.println(priority, TAG, "$tag: $headMsg")
            Log.println(priority, TAG, "$tag: $truncMsg")
            val timestamp = LocalDateTime.now()
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, headMsg, null, timestamp))
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, truncMsg, null, timestamp))
            return
        }

        var i = 0
        var part = 1
        val timestamp = LocalDateTime.now()
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            val partMsg = "[part $part/$chunkCount] $chunk"
            Log.println(priority, TAG, "$tag: $partMsg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, partMsg, null, timestamp))
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) = logChunked(Log.INFO, tag, msg)
    fun logChunkedD(tag: String, msg: String) = logChunked(Log.DEBUG, tag, msg)

    // ========== Helpers ==========

    private fun Int.toPriorityChar(): String = when (this) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }
}
