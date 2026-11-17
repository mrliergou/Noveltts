package app.noveltts.engine

import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 日志级别，数值越大越严重，便于筛选“警告及以上”。 */
enum class Level { DEBUG, INFO, WARN, ERROR }

/** 日志来源分类，对应应用里几个关键子系统。 */
enum class Category { ENGINE, PACK, PIPELINE, SPEECH, UI, STORAGE }

/**
 * 一条诊断日志。
 *
 * 隐私约束：[message] 与 [detail] 只允许写“长度 N”“前缀哈希”或异常摘要，
 * 绝不允许写入朗读原文、完整音色包路径（含用户名）或许可证/令牌。写日志前请先用
 * [DiagnosticsLog.textHint]、[DiagnosticsLog.pathHint] 做脱敏。
 */
data class LogEntry(
    val timeMillis: Long,
    val level: Level,
    val category: Category,
    val message: String,
    val detail: String? = null,
)

/**
 * 进程内环形诊断日志，最多保留 [MAX_ENTRIES] 条；可选地把同样的记录异步追加到磁盘。
 *
 * 线程安全：合成线程、WebSocket 回调与主线程都会写日志，所有写操作在同一把锁内完成，
 * 读侧通过不可变快照 [entriesFlow] 观察，UI 收集时不需要额外加锁。
 *
 * 持久化（[attachStore]）：WARN/ERROR 一律落盘，INFO/DEBUG 按每秒配额抽样，写入发生在
 * 独立的后台线程，调用线程只做一次非阻塞入队。磁盘结构与隐私约束见 [attachStore]。
 */
object DiagnosticsLog {
    /** 环形缓冲上限。超出后丢弃最旧的记录。 */
    const val MAX_ENTRIES: Int = 500

    private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_HISTORY_FILES = 3
    private const val DEFAULT_QUEUE_CAPACITY = 2000
    private const val DEFAULT_INFO_RATE_PER_SECOND = 20
    private const val MIN_WRITABLE_FILE_BYTES = 64L
    private const val STORE_SUBDIR = "diagnostics"
    private const val FILE_PREFIX = "diagnostics-"
    private const val FILE_SUFFIX = ".jsonl"
    private const val WRITER_THREAD_NAME = "noveltts-diagnostics-writer"

    /** detail 落盘前的字符上限，防止异常堆栈把一条记录撑到几 KB。 */
    private const val MAX_DETAIL_CHARS = 300

    /** 至少两段的绝对路径；每段限文件名字符，避免把「1/2」「a/b」这类正常文本误判成路径。 */
    private val PATH_PATTERN = Regex("""(/[\w.\-]+){2,}/?""")
    private val WINDOWS_PATH_PATTERN = Regex("""(?i)\b[A-Z]:\\(?:[^\\\r\n<>:"|?*]+\\)+[^\\\r\n<>:"|?*]*""")
    private val URL_PATTERN = Regex("""(?i)\b(?:https?|wss?)://[^\s"'<>\]\)]+""")
    private val CREDENTIAL_PATTERN = Regex("""(?i)(\b(?:[a-z_]*token|authorization|[a-z_]*license|api[-_]?key|secret|password|X-Token)\b["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|Bearer\s+[^\s,;]+|[^\s,;]+)""")
    private val sensitiveValues = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 单个日志文件字节上限，超过后轮转出 -1 / -2 / -3 历史文件。
     *
     * 名字带 ForTest 是因为只有单测会把它调小来验证轮转；正式代码保持默认值。
     */
    @Volatile
    internal var maxFileBytesForTest: Long = DEFAULT_MAX_FILE_BYTES

    /** INFO/DEBUG 每秒最多落盘条数（固定 1 秒窗口配额），避免刷屏写满磁盘。 */
    @Volatile
    internal var infoRatePerSecondForTest: Int = DEFAULT_INFO_RATE_PER_SECOND

    /** 待写队列容量，满了就丢弃并计数（计数在下一次写入时以一条 WARN 体现）。 */
    internal var queueCapacityForTest: Int = DEFAULT_QUEUE_CAPACITY

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** 供 Compose / 协程订阅的日志快照流；每次写入产生一个新的不可变列表。 */
    val entriesFlow: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    // ---- 持久化状态（只在 storeLock 内改写；写线程独占 FileSink）----
    private val storeLock = Any()
    private val rateLock = Any()

    @Volatile
    private var storeDir: File? = null

    @Volatile
    private var storeQueue: LinkedBlockingQueue<Any>? = null

    @Volatile
    private var storeExecutor: ExecutorService? = null

    @Volatile
    private var storeAttached: Boolean = false

    private val droppedLines = AtomicInteger(0)
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    /** 只被写线程使用，因此不存在 SimpleDateFormat 的线程安全问题。 */
    private val dateStampFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    fun log(level: Level, category: Category, message: String, detail: String? = null) {
        val entry = LogEntry(
            timeMillis = System.currentTimeMillis(),
            level = level,
            category = category,
            message = sanitize(message).take(500),
            detail = sanitizeDetail(detail),
        )
        appendToMemory(entry)
        persist(entry)
    }

    fun debug(category: Category, message: String, detail: String? = null) =
        log(Level.DEBUG, category, message, detail)

    fun info(category: Category, message: String, detail: String? = null) =
        log(Level.INFO, category, message, detail)

    fun warn(category: Category, message: String, detail: String? = null) =
        log(Level.WARN, category, message, detail)

    fun error(category: Category, message: String, detail: String? = null) =
        log(Level.ERROR, category, message, detail)

    /** 当前日志快照，按时间从旧到新排列。 */
    fun entries(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    fun registerSensitiveValue(value: String?) {
        if (!value.isNullOrBlank() && value.length >= 4 && sensitiveValues.size < 64) sensitiveValues.add(value)
    }

    internal fun awaitWritesForTest(timeoutMs: Long = 4000): Boolean {
        val queue = storeQueue ?: return true
        val command = FlushCommand()
        return queue.offer(command) && command.done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 清空内存日志；若已挂载持久化，同时清掉磁盘上的全部日志文件。
     *
     * 删文件不在调用线程做：清空请求投递给写线程，由它关闭句柄后再删除。
     */
    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
        droppedLines.set(0)
        val queue = storeQueue
        if (!storeAttached || queue == null) return
        // 待写的行本来就要删，先丢掉；再排一个“删文件”指令给写线程。
        queue.clear()
        queue.offer(ClearCommand)
    }

    /**
     * 挂载磁盘持久化：日志异步追加到 `dir/diagnostics/diagnostics-yyyyMMdd.jsonl`。
     *
     * - 每行一个 JSON 对象：`{"t":毫秒,"l":"WARN","c":"SPEECH","m":"...","d":"..."}`，UTF-8，
     *   字段只来自 [LogEntry]，不会额外引入原文/路径/许可证；
     * - 单文件超过 [maxFileBytesForTest] 就轮转为 `-1.jsonl`/`-2.jsonl`/`-3.jsonl`，
     *   最多保留 3 个历史文件，再轮转时删最旧的（合计约 4 × 512 KB）；
     * - 幂等：同一目录重复调用直接返回；换目录会先 [detachStore]；
     * - 所有文件 IO 都在独立后台线程，调用线程（通常是主线程）不会被阻塞，
     *   目录不可写等异常全部在后台吞掉，最多退化为纯内存日志。
     */
    fun attachStore(dir: File) {
        val target = File(dir, STORE_SUBDIR)
        synchronized(storeLock) {
            if (storeAttached && storeDir == target) return
        }
        detachStore()

        val capacity = queueCapacityForTest.coerceIn(8, 100_000)
        val queue = LinkedBlockingQueue<Any>(capacity)
        val executor = try {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, WRITER_THREAD_NAME).apply { isDaemon = true }
            }
        } catch (_: Exception) {
            return
        }
        synchronized(storeLock) {
            storeDir = target
            storeQueue = queue
            storeExecutor = executor
            storeAttached = true
        }
        try {
            executor.execute { writerLoop(target, queue) }
        } catch (_: RejectedExecutionException) {
            synchronized(storeLock) {
                storeAttached = false
                storeDir = null
                storeQueue = null
                storeExecutor = null
            }
            runCatching { executor.shutdownNow() }
        }
    }

    /** 解绑持久化：丢弃待写队列并停止写线程，之后 [log] 只写内存。 */
    fun detachStore() {
        val executor: ExecutorService?
        synchronized(storeLock) {
            storeAttached = false
            storeQueue?.clear()
            storeQueue = null
            storeDir = null
            executor = storeExecutor
            storeExecutor = null
        }
        if (executor != null) runCatching { executor.shutdownNow() }
    }

    /** 是否已挂载磁盘持久化；界面用它决定是否显示“已保存到文件”。 */
    fun isStoreAttached(): Boolean = storeAttached

    /**
     * 把最近一个 jsonl 文件的末 [limit] 行读回内存环形缓冲，时间戳/级别/分类按记录还原。
     *
     * 读盘同样在写线程进行（启动时调用不会卡主线程）；文件不存在、某行 JSON 非法、
     * 级别或分类名未知时都跳过，读失败静默。未挂载持久化时该调用无副作用。
     */
    fun restoreRecent(limit: Int = 200) {
        if (limit <= 0 || !storeAttached) return
        val queue = storeQueue ?: return
        // 队列满说明写线程已严重落后，这次恢复直接放弃，不阻塞调用方。
        queue.offer(RestoreCommand(limit))
    }

    /**
     * 把待合成文本压缩成可安全落盘的诊断信息：只保留长度与前 12 个字符的 SHA-256 前 8 位。
     * 同一段文本得到同一哈希，可用于判断“是否重复段”，但无法还原原文。
     */
    fun textHint(text: String): String {
        val length = text.length
        if (length == 0) return "长度 0"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.take(12).toByteArray(Charsets.UTF_8))
        val hash = digest.take(8).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        return "长度 $length · 前缀哈希 $hash"
    }

    /**
     * 去掉路径里的用户目录前缀，只保留末尾两段（例如 …/packs/abc123）。
     * 用于音色包、缓存等文件路径，避免把用户名或沙盒绝对路径写进日志。
     */
    fun pathHint(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val parts = path.replace('\\', '/').trimEnd('/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return path
        return "…/" + parts.takeLast(2).joinToString("/")
    }

    /**
     * 收口 detail 里的绝对路径：全部压成「…/末两段」并截断到 [MAX_DETAIL_CHARS]。
     *
     * 现有埋点大量把 `error.message` 直接当 detail，而 IOException 的 message 常带
     * `/data/user/0/<包名>/...` 这样的绝对路径。日志页对外承诺「不含音色包绝对路径」，
     * 与其改 38 处埋点，不如在唯一入口 [log] 统一收口，谁也漏不掉。
     */
    private fun sanitizeDetail(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        return sanitize(detail).take(MAX_DETAIL_CHARS)
    }

    private fun sanitize(value: String): String {
        var safe = value.take(16_384)
        sensitiveValues.sortedByDescending { it.length }.forEach { secret -> safe = safe.replace(secret, "[已隐藏]") }
        safe = CREDENTIAL_PATTERN.replace(safe) { it.groupValues[1] + "[已隐藏]" }
        safe = URL_PATTERN.replace(safe) { match ->
            runCatching {
                val uri = java.net.URI(match.value)
                "${uri.scheme}://${uri.host ?: "endpoint"}" + (if (uri.port >= 0) ":${uri.port}" else "") + "/…"
            }.getOrDefault("[地址已隐藏]")
        }
        safe = WINDOWS_PATH_PATTERN.replace(safe) { pathHint(it.value).orEmpty() }
        return PATH_PATTERN.replace(safe) { pathHint(it.value).orEmpty() }
    }

    /** 重置单例状态：解绑持久化、清空缓冲并恢复默认配置。仅供单测使用。 */
    internal fun resetForTest() {
        detachStore()
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
        droppedLines.set(0)
        synchronized(rateLock) {
            rateWindowStart = 0L
            rateWindowCount = 0
        }
        maxFileBytesForTest = DEFAULT_MAX_FILE_BYTES
        infoRatePerSecondForTest = DEFAULT_INFO_RATE_PER_SECOND
        queueCapacityForTest = DEFAULT_QUEUE_CAPACITY
        sensitiveValues.clear()
    }

    // ---- 内存缓冲 ----

    private fun appendToMemory(entry: LogEntry) {
        val snapshot = synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            buffer.toList()
        }
        _entries.value = snapshot
    }

    // ---- 落盘调度（调用线程侧，全部非阻塞、不抛异常）----

    private fun persist(entry: LogEntry) {
        if (!storeAttached) return
        if (entry.level == Level.INFO || entry.level == Level.DEBUG) {
            if (!allowLowPriority(entry.timeMillis)) return
        }
        val queue = storeQueue ?: return
        val line = try {
            encode(entry)
        } catch (_: Exception) {
            return
        }
        // 队列满了只丢这一条并计数，绝不阻塞、绝不把异常抛给合成线程。
        if (!queue.offer(line)) droppedLines.incrementAndGet()
    }

    /** 固定 1 秒窗口的配额：INFO/DEBUG 每秒最多 [infoRatePerSecondForTest] 条。 */
    private fun allowLowPriority(nowMillis: Long): Boolean = synchronized(rateLock) {
        if (nowMillis < rateWindowStart || nowMillis - rateWindowStart >= 1000L) {
            rateWindowStart = nowMillis
            rateWindowCount = 0
        }
        if (rateWindowCount >= infoRatePerSecondForTest.coerceAtLeast(0)) return@synchronized false
        rateWindowCount++
        true
    }

    private fun encode(entry: LogEntry): String {
        val json = JSONObject()
            .put("t", entry.timeMillis)
            .put("l", entry.level.name)
            .put("c", entry.category.name)
            .put("m", entry.message)
        entry.detail?.let { json.put("d", it) }
        return json.toString()
    }

    /** 反解一行 JSON；字段非法、级别或分类名未知时返回 null（该行直接跳过）。 */
    private fun decode(line: String): LogEntry? {
        return try {
            val json = JSONObject(line)
            val level = Level.values().firstOrNull { it.name == json.optString("l") } ?: return null
            val category = Category.values().firstOrNull { it.name == json.optString("c") } ?: return null
            LogEntry(
                timeMillis = json.optLong("t"),
                level = level,
                category = category,
                message = sanitize(json.optString("m")).take(500),
                detail = if (json.has("d") && !json.isNull("d")) sanitizeDetail(json.optString("d")) else null,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- 写线程侧：独占一个 FileSink，所有文件操作都在这里 ----

    /** 当前打开的文件句柄；每个写线程持有自己的实例，detach 后重启也不会互相关闭。 */
    private class FileSink {
        var file: File? = null
        var stream: Writer? = null
        var bytes: Long = 0L

        fun close() {
            runCatching { stream?.flush() }
            runCatching { stream?.close() }
            stream = null
        }
    }

    private object ClearCommand

    private class RestoreCommand(val limit: Int)
    private class FlushCommand(val done: CountDownLatch = CountDownLatch(1))

    private fun writerLoop(dir: File, queue: LinkedBlockingQueue<Any>) {
        val sink = FileSink()
        try {
            if (!dir.exists()) dir.mkdirs()
            while (true) {
                val item = queue.take()
                when (item) {
                    is ClearCommand -> handleClear(dir, sink)
                    is RestoreCommand -> restoreFromDisk(dir, item.limit)
                    is FlushCommand -> { sink.stream?.flush(); item.done.countDown() }
                    is String -> appendLine(dir, sink, item)
                }
            }
        } catch (_: InterruptedException) {
            // detachStore 的正常退出路径；队列里没写完的行按设计丢弃。
        } catch (_: Exception) {
            // 目录不可写等极端情况：放弃持久化，绝不影响应用。
        } finally {
            sink.close()
        }
    }

    private fun appendLine(dir: File, sink: FileSink, line: String) {
        val dropped = droppedLines.getAndSet(0)
        if (dropped > 0) {
            val notice = LogEntry(
                timeMillis = System.currentTimeMillis(),
                level = Level.WARN,
                category = Category.STORAGE,
                message = "日志写入队列已满，丢弃 $dropped 条",
            )
            appendToMemory(notice)
            runCatching { writeLine(dir, sink, encode(notice)) }
        }
        runCatching { writeLine(dir, sink, line) }
    }

    private fun writeLine(dir: File, sink: FileSink, line: String) {
        try {
            val today = File(dir, baseName(System.currentTimeMillis()))
            if (sink.file?.name != today.name) {
                sink.close()
                if (!dir.exists() && !dir.mkdirs()) return
                sink.file = today
                sink.bytes = today.length()
                // 跨天切换到新文件时，顺手把总量收敛到 3 个历史文件。
                pruneHistory(dir, today)
            }
            if (sink.stream == null) {
                sink.stream = FileOutputStream(today, true).bufferedWriter(Charsets.UTF_8)
                sink.bytes = today.length()
            }
            val payload = line.toByteArray(Charsets.UTF_8)
            val maxBytes = maxFileBytesForTest.coerceAtLeast(MIN_WRITABLE_FILE_BYTES)
            if (sink.bytes > 0L && sink.bytes + payload.size + 1 > maxBytes) rotate(dir, sink)
            val stream = sink.stream ?: return
            stream.write(line)
            stream.write("\n")
            stream.flush()
            sink.bytes += payload.size + 1
        } catch (_: Exception) {
            // 单行写失败：丢掉句柄，下一条重新打开，不影响其它日志。
            sink.close()
        }
    }

    /** 当前文件已满：base → base-1 → base-2 → base-3，超出 3 个历史就删最旧。 */
    private fun rotate(dir: File, sink: FileSink) {
        val base = sink.file ?: return
        sink.close()
        val name = base.name
        val oldest = File(dir, historyName(name, MAX_HISTORY_FILES))
        if (oldest.exists()) check(oldest.delete()) { "无法淘汰历史日志" }
        for (index in MAX_HISTORY_FILES - 1 downTo 1) {
            val from = File(dir, historyName(name, index))
            if (from.exists()) Files.move(from.toPath(), File(dir, historyName(name, index + 1)).toPath(), REPLACE_EXISTING)
        }
        Files.move(base.toPath(), File(dir, historyName(name, 1)).toPath(), REPLACE_EXISTING)
        sink.bytes = 0L
        sink.stream = FileOutputStream(base, false).bufferedWriter(Charsets.UTF_8)
        pruneHistory(dir, base)
    }

    /** 只保留最近 [MAX_HISTORY_FILES] 个历史文件（跨日期也按修改时间淘汰）。 */
    private fun pruneHistory(dir: File, current: File) {
        val candidates = dir.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) &&
                file.name != current.name
        } ?: return
        if (candidates.size <= MAX_HISTORY_FILES) return
        candidates.sortedByDescending { it.lastModified() }
            .drop(MAX_HISTORY_FILES)
            .forEach { it.delete() }
    }

    private fun handleClear(dir: File, sink: FileSink) {
        sink.close()
        sink.file = null
        sink.bytes = 0L
        droppedLines.set(0)
        runCatching {
            dir.listFiles { file ->
                file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
            }?.forEach { it.delete() }
        }
    }

    private fun restoreFromDisk(dir: File, limit: Int) {
        try {
            val newest = dir.listFiles { file ->
                file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
            }?.maxByOrNull { it.lastModified() } ?: return
            val lines = newest.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines() }
            if (lines.isEmpty()) return
            val restored = lines.takeLast(limit).mapNotNull { decode(it) }
            if (restored.isEmpty()) return
            val snapshot = synchronized(lock) {
                restored.forEach { buffer.addLast(it) }
                while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
                buffer.toList()
            }
            _entries.value = snapshot
        } catch (_: Exception) {
            // 日志恢复不是关键路径，读失败静默。
        }
    }

    private fun baseName(nowMillis: Long): String =
        FILE_PREFIX + dateStampFormat.format(Date(nowMillis)) + FILE_SUFFIX

    private fun historyName(base: String, index: Int): String =
        base.removeSuffix(FILE_SUFFIX) + "-" + index + FILE_SUFFIX
}
