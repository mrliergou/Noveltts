package app.noveltts.forward

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/** A TCP FIN ends request input; the peer can still read the response after a half-close. */
internal fun watchForwardConnection(input: InputStream, context: ForwardRequestContext, closed: () -> Boolean) {
    while (!context.isFinished && !closed()) {
        try {
            if (input.read() < 0) return
        } catch (_: SocketTimeoutException) {
            if (runCatching { context.ensureActive() }.isFailure) return
        } catch (_: IOException) {
            context.cancel()
            return
        }
    }
}
