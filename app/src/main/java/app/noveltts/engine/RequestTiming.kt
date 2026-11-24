package app.noveltts.engine

/** Stage names, elapsed time and request IDs only. Never records text or request options. */
class RequestTiming(private val requestId: String?, private val segmentId: String?) {
    private val started = System.nanoTime()
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun mark(stage: String) {
        if (seen.add(stage)) DiagnosticsLog.debug(Category.ENGINE, "请求 $requestId · $segmentId · $stage", "elapsed=${(System.nanoTime() - started) / 1_000_000}ms")
    }
}