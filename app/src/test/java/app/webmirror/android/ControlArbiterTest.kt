package app.webmirror.android

import app.webmirror.android.input.ControlArbiter
import app.webmirror.android.input.InputEvent
import app.webmirror.android.input.InputSink
import app.webmirror.android.input.RateLimiter
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlArbiterTest {

    private fun ready() = ControlArbiter().also { it.setState(true, true) }

    @Test
    fun deniedWhileSwitchOffOrServiceNotReady() {
        val a = ControlArbiter()
        assertEquals("""{"t":"controlDenied","reason":"notAllowed"}""", a.request(1, true).toJson())
        a.setState(true, false)
        assertEquals("""{"t":"controlDenied","reason":"notReady"}""", a.request(1, true).toJson())
        assertFalse(a.canInput(1))
    }

    @Test
    fun firstViewerOwnsOthersAreDenied() {
        val a = ready()
        assertEquals("""{"t":"control","on":true,"owner":true}""", a.request(1, true).toJson())
        assertEquals("""{"t":"controlDenied","reason":"busy"}""", a.request(2, true).toJson())
        assertTrue(a.canInput(1))
        assertFalse(a.canInput(2))
        assertTrue(a.request(1, true).owner) // asking again keeps it
    }

    @Test
    fun ownerTurningOffOrLeavingReleases() {
        val a = ready()
        a.request(1, true)
        assertEquals("""{"t":"control","on":false,"owner":false}""", a.request(2, false).toJson()) // non-owner off: no effect
        assertTrue(a.canInput(1))
        a.request(1, false)
        assertNull(a.ownerId)
        assertTrue(a.request(2, true).owner)
        assertTrue(a.release(2))
        assertFalse(a.release(2))
        assertTrue(a.request(1, true).owner)
    }

    @Test
    fun switchOffOrServiceLossDropsTheOwner() {
        val a = ready()
        a.request(1, true)
        assertEquals(1 to "notAllowed", a.setState(false, true))
        assertFalse(a.canInput(1))
        assertNull(a.setState(true, true))
        a.request(1, true)
        assertEquals(1 to "notReady", a.setState(true, false))
    }

    @Test
    fun rateLimiterCapsAt120PerSecondAndNeverRefusesUpOrCancel() {
        val r = RateLimiter(120)
        var ok = 0
        for (i in 0 until 300) if (r.allow(1000L + i)) ok++ // 300 messages within 300 ms
        assertEquals(120, ok)
        assertTrue(r.allow(1500, essential = true))
        assertFalse(r.allow(1999))
        assertTrue(r.allow(2000)) // next window
    }

    @Test
    fun rateLimiterSurvivesAClockGoingBack() {
        val r = RateLimiter(2)
        assertTrue(r.allow(5000)); assertTrue(r.allow(5001)); assertFalse(r.allow(5002))
        assertTrue(r.allow(100))
    }

    private class Viewer(override val viewerId: Int) : StreamSink {
        val texts = ArrayList<String>()
        override fun onHello(json: String) {}
        override fun onPacket(p: Packet) {}
        override fun onText(json: String) { texts.add(json) }
    }

    private class Recorder : InputSink {
        val events = ArrayList<InputEvent>()
        var releases = 0
        override fun submit(ev: InputEvent) { events.add(ev) }
        override fun releaseAll() { releases++ }
    }

    private val ev = InputEvent.validate("down", 1, 0.5, 0.5, 0.0)!!

    @Test
    fun hubOnlyForwardsInputOfTheOwnerAndReleasesWhenItLeaves() {
        val hub = StreamHub()
        val rec = Recorder()
        hub.inputSink = rec
        val v1 = Viewer(1)
        val v2 = Viewer(2)
        hub.register(v1); hub.register(v2)
        hub.setControl(true, true)
        assertTrue(hub.helloJson().endsWith(""""control":{"allowed":true,"ready":true}}"""))
        hub.controlInput(1, ev)
        assertEquals(0, rec.events.size) // nobody owns yet
        assertTrue(hub.controlRequest(v1, true).owner)
        assertTrue(hub.controlRequest(v2, true).denied)
        hub.controlInput(2, ev)
        hub.controlInput(1, ev)
        assertEquals(1, rec.events.size)
        hub.unregister(v1)
        assertEquals(1, rec.releases)
        assertTrue(hub.controlRequest(v2, true).owner)
    }

    @Test
    fun switchingOffTellsTheOwnerAndLiftsTouches() {
        val hub = StreamHub()
        val rec = Recorder()
        hub.inputSink = rec
        val v1 = Viewer(1)
        hub.register(v1)
        hub.setControl(true, true)
        hub.controlRequest(v1, true)
        hub.setControl(false, true)
        assertEquals(listOf("""{"t":"control","on":false,"owner":false,"reason":"notAllowed"}"""), v1.texts)
        assertEquals(1, rec.releases)
        assertTrue(hub.helloJson().contains(""""control":{"allowed":false,"ready":true}"""))
    }
}
