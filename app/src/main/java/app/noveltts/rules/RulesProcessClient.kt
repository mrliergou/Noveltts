package app.noveltts.rules

import android.content.*
import android.os.*
import app.noveltts.domain.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class RulesProcessClient(context: Context) {
    private val app = context.applicationContext
    private val gate = java.util.concurrent.locks.ReentrantLock(true)
    private val latestPreview = AtomicLong()
    private val callbacks = HandlerThread("noveltts-rule-ipc").apply { start() }
    init {
        synchronized(preparedDirectories) {
            val root = app.cacheDir.canonicalFile
            if (preparedDirectories.add(root.path)) root.listFiles().orEmpty().filter {
                it.isFile && it.canonicalFile.parentFile == root &&
                    it.name.matches(Regex("rule-(in|out)-.+\\.bin"))
            }.forEach { it.delete() }
        }
    }
    fun execute(work: RuleWork, preview: Boolean): RuleReply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "规则校验或预览必须在后台执行" }
        val coalescible = preview && work.validation == null
        val generation = if (coalescible) latestPreview.incrementAndGet() else 0
        val timeoutMs = if (preview) 2_000L else 10_000L
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        if (!gate.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) throw PipelineLimitException("规则队列等待超时，请稍后重试")
        return try {
            if (coalescible && generation != latestPreview.get()) throw PipelineLimitException("预览已被更新请求替代")
            val input = File.createTempFile("rule-in-", ".bin", app.cacheDir)
            val output = File.createTempFile("rule-out-", ".bin", app.cacheDir)
            var bound = false
            var inputFd: ParcelFileDescriptor? = null
            var outputFd: ParcelFileDescriptor? = null
            var workerPid = 0
            var terminateWorker = false
            val workerDied = CountDownLatch(1)
            val connected = CountDownLatch(1)
            val done = CountDownLatch(1)
            val endpoint = AtomicReference<Messenger?>()
            val failed = AtomicReference<String?>()
            val deathRecipient = IBinder.DeathRecipient {
                workerDied.countDown()
                failed.compareAndSet(null, "规则进程已断开")
                connected.countDown()
                done.countDown()
            }
            val reply = Messenger(object : Handler(callbacks.looper) {
                override fun handleMessage(message: Message) {
                    if (message.what == 1) { workerPid = message.arg1; connected.countDown() }
                    else { if (message.what != 2) failed.set("规则进程异常退出"); done.countDown() }
                }
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    try {
                        val remote = Messenger(binder)
                        endpoint.set(remote)
                        binder.linkToDeath(deathRecipient, 0)
                        remote.send(Message.obtain(null, 1).apply { replyTo = reply })
                    } catch (_: Exception) { failed.set("规则进程连接失败"); connected.countDown(); done.countDown() }
                }
                override fun onServiceDisconnected(name: ComponentName) { failed.set("规则进程已断开"); connected.countDown(); done.countDown() }
                override fun onBindingDied(name: ComponentName) { failed.set("规则进程绑定已失效"); connected.countDown(); done.countDown() }
                override fun onNullBinding(name: ComponentName) { failed.set("规则进程无法绑定"); connected.countDown(); done.countDown() }
            }
            // The deadline includes queue wait, binding and execution.
            fun await(latch: CountDownLatch) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
                    terminateWorker = true
                    throw PipelineLimitException(if (preview) "规则预览超时（2 秒），请简化正则后重试" else "规则规划超时（10 秒），请简化正则后重试")
                }
                failed.get()?.let { throw PipelineLimitException(it) }
            }
            try {
                input.outputStream().use { RuleWire.write(it, work) }
                inputFd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
                outputFd = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
                bound = app.bindService(Intent(app, RulesProcessService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound) { "规则进程启动失败" }
                await(connected)
                requireNotNull(endpoint.get()).send(Message.obtain(null, 2).apply {
                    replyTo = reply
                    data = Bundle().apply { putParcelable("input", inputFd); putParcelable("output", outputFd) }
                })
                await(done)
                require(output.length() <= RuleWire.MAX_BYTES) { "规则结果超过传输预算" }
                output.inputStream().use { RuleWire.read(it) as RuleReply }
            } catch (error: InterruptedException) {
                terminateWorker = true
                Thread.currentThread().interrupt()
                throw PipelineLimitException("规则请求已取消")
            } finally {
                if (bound) runCatching { app.unbindService(connection) }
                // Drop the binding before killing the timed-out worker. Rebinding while its
                // death is still pending can attach the next request to a dying service.
                if (terminateWorker && workerPid > 0 && workerPid != Process.myPid()) {
                    val interrupted = Thread.interrupted()
                    var interruptedDuringCleanup = false
                    try {
                        runCatching {
                            if (endpoint.get()?.binder?.isBinderAlive == true) Process.killProcess(workerPid)
                            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                            while (true) {
                                val remaining = cleanupDeadline - System.nanoTime()
                                if (remaining <= 0) break
                                try { workerDied.await(remaining, TimeUnit.NANOSECONDS); break }
                                catch (_: InterruptedException) { interruptedDuringCleanup = true }
                            }
                        }
                    } finally {
                        if (interrupted || interruptedDuringCleanup) Thread.currentThread().interrupt()
                    }
                }
                runCatching { endpoint.get()?.binder?.unlinkToDeath(deathRecipient, 0) }
                runCatching { inputFd?.close() }; runCatching { outputFd?.close() }
                input.delete(); output.delete()
            }
        } finally { gate.unlock() }
    }
    private companion object { val preparedDirectories = HashSet<String>() }
}
