package app.webmirror.android

import app.webmirror.android.server.AsyncClose
import app.webmirror.android.stream.Expirable
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncCloseTest {

    /** A viewer whose close blocks forever, like a TLS close_notify written to a vanished peer. */
    private class Stuck(val started: CountDownLatch, val release: CountDownLatch) : StreamSink, Expirable {
        override fun onHello(json: String) {}
        override fun onPacket(p: Packet) {}
        override fun closeIfIdle(maxIdleMs: Long): Boolean {
            AsyncClose.run("test-closer") {
                started.countDown()
                release.await()
            }
            return true
        }
    }

    @Test
    fun sweepReturnsAndReachesLaterViewersEvenWhenACloseBlocks() {
        val hub = StreamHub()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var secondSwept = false
        val second = object : StreamSink, Expirable {
            override fun onHello(json: String) {}
            override fun onPacket(p: Packet) {}
            override fun closeIfIdle(maxIdleMs: Long): Boolean { secondSwept = true; return true }
        }
        hub.register(Stuck(started, release))
        hub.register(second)
        val t0 = System.nanoTime()
        hub.sweepIdle(1000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("sweep blocked for $ms ms", ms < 1000)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(secondSwept)
        release.countDown()
    }

    @Test
    fun exceptionsInTheCloseActionAreSwallowed() {
        val t = AsyncClose.run("test-closer") { throw IllegalStateException("x") }
        t.join(2000)
        assertEquals(false, t.isAlive)
    }
}
