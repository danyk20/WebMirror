package app.webmirror.android.stream

/** Optional extra for sinks backed by a connection that can silently die. */
interface Expirable {
    /** Shuts the sink down if nothing was received for more than [maxIdleMs]. Returns true if it did. */
    fun closeIfIdle(maxIdleMs: Long): Boolean
}
