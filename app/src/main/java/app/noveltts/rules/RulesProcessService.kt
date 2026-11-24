package app.noveltts.rules

import android.app.Service
import android.content.Intent
import android.os.*
import app.noveltts.domain.RuleReply
import app.noveltts.domain.RuleRuntime
import app.noveltts.domain.RuleWork
import java.util.concurrent.Executors

/** Contains text/rules only. No settings, secrets, voice registry or audio backend is initialized. */
class RulesProcessService : Service() {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "noveltts-rules") }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == 1) {
                runCatching { message.replyTo.send(Message.obtain(null, 1, Process.myPid(), 0)) }
                return
            }
            if (message.what != 2) return
            @Suppress("DEPRECATION") val input = message.data.getParcelable<ParcelFileDescriptor>("input") ?: return
            @Suppress("DEPRECATION") val output = message.data.getParcelable<ParcelFileDescriptor>("output") ?: run { input.close(); return }
            val reply = message.replyTo
            executor.execute {
                try {
                    val result = try {
                        val work = ParcelFileDescriptor.AutoCloseInputStream(input).use { RuleWire.read(it) as RuleWork }
                        RuleRuntime.local(work)
                    } catch (_: StackOverflowError) { RuleReply(error = "规则过于复杂，请简化正则") }
                    catch (_: OutOfMemoryError) { RuleReply(error = "规则处理超过内存预算") }
                    catch (error: Exception) { RuleReply(error = error.message?.take(200) ?: "规则处理失败") }
                    ParcelFileDescriptor.AutoCloseOutputStream(output).use { RuleWire.write(it, result) }
                    reply.send(Message.obtain(null, 2))
                } catch (_: Exception) { runCatching { reply.send(Message.obtain(null, 3)) } }
                finally { runCatching { input.close() }; runCatching { output.close() } }
            }
        }
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}