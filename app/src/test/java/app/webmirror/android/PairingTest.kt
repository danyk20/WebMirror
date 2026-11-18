package app.webmirror.android

import app.webmirror.android.stream.MemoryPairingStore
import app.webmirror.android.stream.Pairing
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private var now = 1_000_000L
    private val store = MemoryPairingStore(required = true, pin = "123456", pinLength = 6)
    private val p = Pairing(store, { now }, Random(42))

    private fun ok(r: Pairing.Attempt) = r as Pairing.Attempt.Ok

    @Test
    fun correctPinGivesATokenThatIsAccepted() {
        val t = ok(p.attempt("123456")).token
        assertEquals(48, t.length)
        assertTrue(t.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(p.isValidToken(t))
        assertEquals(1, p.tokenCount)
    }

    @Test
    fun wrongPinIsRefusedAndGivesNoToken() {
        assertTrue(p.attempt("000000") is Pairing.Attempt.Wrong)
        assertTrue(p.attempt("") is Pairing.Attempt.Wrong)
        assertTrue(p.attempt(null) is Pairing.Attempt.Wrong)
        assertEquals(0, p.tokenCount)
    }

    @Test
    fun unknownOrMissingTokenIsRejected() {
        val t = ok(p.attempt("123456")).token
        assertFalse(p.isValidToken(null))
        assertFalse(p.isValidToken(""))
        assertFalse(p.isValidToken(t.dropLast(1)))
        assertFalse(p.isValidToken(t.replaceRange(0, 1, if (t[0] == 'a') "b" else "a")))
    }

    @Test
    fun tokensAreDifferentAndNotDerivedFromThePin() {
        val a = ok(p.attempt("123456")).token
        val b = ok(p.attempt("123456")).token
        assertNotEquals(a, b)
        assertFalse(a.contains("123456"))
    }

    @Test
    fun fiveWrongPinsLockAndAlsoRefuseTheCorrectPin() {
        repeat(4) { assertEquals(0, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs) }
        assertEquals(Pairing.LOCK_MS, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs)
        assertEquals(Pairing.LOCK_MS, p.lockedForMs())
        now += 10_000
        val l = p.attempt("123456") as Pairing.Attempt.Locked
        assertEquals(50_000L, l.retryInMs)
        assertEquals(0, p.tokenCount)
        assertTrue(p.attempt("1") is Pairing.Attempt.Locked)
    }

    @Test
    fun lockoutEndsAfterSixtySecondsAndCountingStartsAgain() {
        repeat(5) { p.attempt("1") }
        now += 59_999
        assertTrue(p.attempt("123456") is Pairing.Attempt.Locked)
        now += 1
        assertEquals(0, p.lockedForMs())
        // four more wrong ones do not lock again, the correct one works
        repeat(4) { assertEquals(0, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs) }
        assertTrue(p.attempt("123456") is Pairing.Attempt.Ok)
    }

    @Test
    fun wrongPinsOlderThanTheWindowDoNotCount() {
        repeat(4) { p.attempt("1") }
        now += 60_000
        assertEquals(0, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs)
        repeat(3) { assertEquals(0, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs) }
        assertEquals(Pairing.LOCK_MS, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs)
    }

    @Test
    fun slowGuessingWithinNoWindowNeverLocks() {
        repeat(20) {
            assertEquals(0, (p.attempt("1") as Pairing.Attempt.Wrong).lockedForMs)
            now += 15_000
        }
    }

    @Test
    fun forgetAllRemovesEveryToken() {
        val a = ok(p.attempt("123456")).token
        val b = ok(p.attempt("123456")).token
        p.forgetAll()
        assertEquals(0, p.tokenCount)
        assertFalse(p.isValidToken(a))
        assertFalse(p.isValidToken(b))
        assertTrue(store.tokens.isEmpty())
    }

    @Test
    fun tokensAreCappedAndTheOldestIsDropped() {
        val tokens = (1..Pairing.MAX_TOKENS + 3).map { ok(p.attempt("123456")).token }
        assertEquals(Pairing.MAX_TOKENS, p.tokenCount)
        assertFalse(p.isValidToken(tokens[0]))
        assertFalse(p.isValidToken(tokens[2]))
        assertTrue(p.isValidToken(tokens[3]))
        assertTrue(p.isValidToken(tokens.last()))
    }

    @Test
    fun newPinKeepsTheTokensAndChangesThePin() {
        val t = ok(p.attempt("123456")).token
        val pin = p.newPin()
        assertEquals(6, pin.length)
        assertTrue(pin.all { it in '0'..'9' })
        assertEquals(pin, p.pin)
        assertEquals(pin, store.pin)
        assertTrue(p.isValidToken(t))
        if (pin != "123456") assertTrue(p.attempt("123456") is Pairing.Attempt.Wrong)
        assertTrue(p.attempt(pin) is Pairing.Attempt.Ok)
    }

    @Test
    fun pinIsGeneratedOnceWithLeadingZerosAndPersisted() {
        val s = MemoryPairingStore(required = true, pinLength = 6)
        val q = Pairing(s, { now }, object : Random() { override fun nextInt(bound: Int) = 42 })
        assertEquals("000042", q.pin)
        assertEquals("000042", s.pin)
        assertEquals("000042", Pairing(s, { now }, Random(1)).pin) // a second instance reads the saved one
    }

    @Test
    fun brokenStoredPinIsReplaced() {
        val s = MemoryPairingStore(required = true, pin = "12ab", pinLength = 6)
        val q = Pairing(s, { now }, Random(5))
        assertEquals(6, q.pin.length)
        assertTrue(q.pin.all { it in '0'..'9' })
    }

    @Test
    fun requiredFlagIsStored() {
        assertTrue(p.required)
        p.required = false
        assertFalse(store.required)
    }

    @Test
    fun defaultPinHasFourDigits() {
        val s = MemoryPairingStore(required = true)
        val q = Pairing(s, { now }, object : Random() { override fun nextInt(bound: Int) = 42 })
        assertEquals(4, q.pinLength)
        assertEquals("0042", q.pin)
    }

    @Test
    fun changingTheLengthMakesANewPinKeepsTokens() {
        val t = ok(p.attempt("123456")).token
        p.pinLength = 4
        assertEquals(4, p.pinLength)
        assertEquals(4, p.pin.length)
        assertEquals(p.pin, store.pin)
        assertEquals(4, store.pinLength)
        assertTrue(p.isValidToken(t))
        assertTrue(p.attempt(p.pin) is Pairing.Attempt.Ok)
        p.pinLength = 6
        assertEquals(6, p.pin.length)
        assertTrue(p.pin.all { it in '0'..'9' })
    }

    @Test
    fun sameLengthKeepsThePinAndOtherLengthsAreIgnored() {
        val pin = p.pin
        p.pinLength = 6
        assertEquals(pin, p.pin)
        p.pinLength = 5
        assertEquals(6, p.pinLength)
        assertEquals(pin, p.pin)
    }

    @Test
    fun storedPinOfTheOtherLengthIsReplaced() {
        val s = MemoryPairingStore(required = true, pin = "123456", pinLength = 4)
        assertEquals(4, Pairing(s, { now }, Random(3)).pin.length)
    }

    @Test
    fun newPinFollowsTheLength() {
        p.pinLength = 4
        repeat(20) { assertEquals(4, p.newPin().length) }
    }
}
