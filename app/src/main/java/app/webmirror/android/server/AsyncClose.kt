package app.webmirror.android.server

/**
 * Runs a close action on a short-lived daemon thread so that a close that blocks (for example a TLS close_notify
 * written to a peer that vanished without RST) can never block the caller, such as the idle sweeper.
 */
object AsyncClose {
    fun run(name: String, action: () -> Unit): Thread =
        Thread({
            try {
                action()
            } catch (e: Throwable) {
                // already closed
            }
        }, name).apply {
            isDaemon = true
            start()
        }
}
