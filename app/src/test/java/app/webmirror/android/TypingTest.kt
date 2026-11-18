package app.webmirror.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.webmirror.android.input.InputSink
import app.webmirror.android.input.InputEvent
import app.webmirror.android.input.KbdState
import app.webmirror.android.input.Typing
import app.webmirror.android.input.TypingKey
import app.webmirror.android.stream.Packet
import app.webmirror.android.stream.StreamHub
import app.webmirror.android.stream.StreamSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTest {

    @Test
    fun textValidation() {
        assertEquals("héllo 😀", Typing.validateText("héllo 😀"))
        assertNull(Typing.validateText(null))
        assertNull(Typing.validateText(42))
        assertNull(Typing.validateText(""))
        assertNull(Typing.validateText("a\nb"))
        assertNull(Typing.validateText("a\u0000b"))
        assertNull(Typing.validateText("x".repeat(Typing.MAX_TEXT + 1)))
        assertEquals(Typing.MAX_TEXT, Typing.validateText("x".repeat(Typing.MAX_TEXT))!!.length)
    }

    @Test
    fun keyParsing() {
        assertEquals(TypingKey.BACKSPACE, TypingKey.parse("Backspace"))
        assertEquals(TypingKey.ENTER, TypingKey.parse("Enter"))
        assertEquals(TypingKey.TAB, TypingKey.parse("Tab"))
        assertEquals(TypingKey.LEFT, TypingKey.parse("ArrowLeft"))
        assertEquals(TypingKey.RIGHT, TypingKey.parse("ArrowRight"))
        assertNull(TypingKey.parse("ArrowUp"))
        assertNull(TypingKey.parse("backspace"))
        assertNull(TypingKey.parse(null))
    }

    @Test
    fun fieldTypeFromInputType() {
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals("text", Typing.fieldType(text))
        assertEquals("text", Typing.fieldType(0))
        assertEquals("number", Typing.fieldType(InputType.TYPE_CLASS_NUMBER))
        assertEquals("number", Typing.fieldType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals("password", Typing.fieldType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals("phone", Typing.fieldType(InputType.TYPE_CLASS_PHONE))
        assertEquals("password", Typing.fieldType(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals("password", Typing.fieldType(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertEquals("password", Typing.fieldType(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertEquals("email", Typing.fieldType(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals("email", Typing.fieldType(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertEquals("url", Typing.fieldType(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertEquals("text", Typing.fieldType(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertEquals("password", Typing.fieldType(text, isPassword = true))
    }

    @Test
    fun multiLineAndActions() {
        val multi = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertTrue(Typing.isMultiLine(multi))
        assertFalse(Typing.isMultiLine(InputType.TYPE_CLASS_TEXT))
        assertFalse(Typing.isMultiLine(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals("done", Typing.actionName(EditorInfo.IME_ACTION_DONE, false))
        assertEquals("search", Typing.actionName(EditorInfo.IME_ACTION_SEARCH, false))
        assertEquals("go", Typing.actionName(EditorInfo.IME_ACTION_GO, false))
        assertEquals("send", Typing.actionName(EditorInfo.IME_ACTION_SEND, false))
        assertEquals("next", Typing.actionName(EditorInfo.IME_ACTION_NEXT, false))
        assertEquals("enter", Typing.actionName(EditorInfo.IME_ACTION_NONE, false))
        assertEquals("enter", Typing.actionName(EditorInfo.IME_ACTION_SEARCH, true)) // multi-line: Enter is a line break
        assertEquals("enter", Typing.actionName(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION, false))
        val s = Typing.kbdState(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO)
        assertEquals("""{"t":"kbd","show":true,"type":"url","action":"go","multiline":false}""", s.toJson())
        assertEquals("""{"t":"kbd","show":false}""", KbdState.HIDDEN.toJson())
        assertTrue(Typing.kbdState(InputType.TYPE_CLASS_TEXT, 0, multiLineNode = true).multiline)
    }

    @Test
    fun enterUsesTheEditorActionOnlyWhenThereIsOne() {
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals(EditorInfo.IME_ACTION_SEARCH, Typing.enterEditorAction(EditorInfo.IME_ACTION_SEARCH, text))
        assertEquals(EditorInfo.IME_ACTION_DONE, Typing.enterEditorAction(EditorInfo.IME_ACTION_DONE, text))
        assertNull(Typing.enterEditorAction(EditorInfo.IME_ACTION_NONE, text))
        assertNull(Typing.enterEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED, text))
        assertNull(Typing.enterEditorAction(EditorInfo.IME_ACTION_SEND, text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertNull(Typing.enterEditorAction(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, text))
    }

    @Test
    fun modeAndGating() {
        assertFalse(Typing.supported(32))
        assertTrue(Typing.supported(33))
        assertTrue(Typing.teslaEffective(TypingKeyboard.TESLA, 33))
        assertFalse(Typing.teslaEffective(TypingKeyboard.TESLA, 32))
        assertFalse(Typing.teslaEffective(TypingKeyboard.PHONE, 36))
        assertTrue(Typing.active(true, true))
        assertFalse(Typing.active(true, false))
        assertFalse(Typing.active(false, true))
        assertEquals(TypingKeyboard.PHONE, TypingKeyboard.fromKey(null))
        assertEquals(TypingKeyboard.PHONE, TypingKeyboard.fromKey("junk"))
        assertEquals(TypingKeyboard.TESLA, TypingKeyboard.fromKey("tesla"))
    }

    private class Viewer(override val viewerId: Int) : StreamSink {
        val texts = ArrayList<String>()
        override fun onHello(json: String) {}
        override fun onPacket(p: Packet) {}
        override fun onText(json: String) { texts.add(json) }
    }

    private class Rec : InputSink {
        val typed = ArrayList<String>()
        val keys = ArrayList<TypingKey>()
        val active = ArrayList<Boolean>()
        override fun submit(ev: InputEvent) {}
        override fun releaseAll() {}
        override fun setTypingActive(active: Boolean) { this.active.add(active) }
        override fun typeText(text: String) { typed.add(text) }
        override fun pressKey(key: TypingKey) { keys.add(key) }
    }

    @Test
    fun hubForwardsTypingOnlyForTheOwnerInTeslaMode() {
        val hub = StreamHub()
        val rec = Rec()
        hub.inputSink = rec
        val v1 = Viewer(1); val v2 = Viewer(2)
        hub.register(v1); hub.register(v2)
        hub.setControl(true, true)
        hub.controlRequest(v1, true)
        hub.typeText(1, "a"); hub.pressKey(1, TypingKey.ENTER)
        assertTrue(rec.typed.isEmpty() && rec.keys.isEmpty()) // phone keyboard mode: refused
        assertTrue(hub.helloJson().endsWith(""""ownerPresent":true}}"""))

        hub.setTypingKeyboard(tesla = true, supported = true)
        assertTrue(hub.helloJson().endsWith(""""ownerPresent":true,"kbd":"tesla"}}"""))
        assertEquals(listOf(true), rec.active)
        hub.typeText(2, "x") // not the owner
        hub.typeText(1, "a"); hub.pressKey(1, TypingKey.BACKSPACE)
        assertEquals(listOf("a"), rec.typed)
        assertEquals(listOf(TypingKey.BACKSPACE), rec.keys)

        // the service's focus report reaches only the owner
        hub.kbdChanged(KbdState(true, "email", "go", false))
        assertEquals(listOf("""{"t":"kbd","show":true,"type":"email","action":"go","multiline":false}"""), v1.texts)
        assertTrue(v2.texts.isEmpty())

        // back to the phone keyboard: the page's keyboard goes away, the service stops watching
        hub.setTypingKeyboard(tesla = false, supported = true)
        assertEquals("""{"t":"kbd","show":false}""", v1.texts.last())
        assertEquals(listOf(true, false), rec.active)
        hub.typeText(1, "b")
        assertEquals(1, rec.typed.size)
    }

    @Test
    fun teslaNeedsAndroid13AndStopsWhenControlEnds() {
        val hub = StreamHub()
        val rec = Rec()
        hub.inputSink = rec
        val v1 = Viewer(1)
        hub.register(v1)
        hub.setControl(true, true)
        hub.setTypingKeyboard(tesla = true, supported = false)
        assertTrue(hub.helloJson().endsWith(""""ownerPresent":false,"kbdUnsupported":true}}"""))
        hub.controlRequest(v1, true)
        hub.typeText(1, "a")
        assertTrue(rec.typed.isEmpty() && rec.active.isEmpty())

        hub.setTypingKeyboard(tesla = true, supported = true)
        assertEquals(listOf(true), rec.active)
        hub.controlRequest(v1, false) // the owner turns control off
        assertEquals(listOf(true, false), rec.active)
        assertEquals("""{"t":"kbd","show":false}""", v1.texts.last())

        hub.controlRequest(v1, true)
        hub.unregister(v1) // the owner leaves
        assertEquals(listOf(true, false, true, false), rec.active)
    }

    @Test
    fun switchingControlOffStopsTyping() {
        val hub = StreamHub()
        val rec = Rec()
        hub.inputSink = rec
        val v1 = Viewer(1)
        hub.register(v1)
        hub.setControl(true, true)
        hub.setTypingKeyboard(true, true)
        hub.controlRequest(v1, true)
        hub.setControl(false, true)
        assertEquals(listOf(true, false), rec.active)
        hub.typeText(1, "a")
        assertTrue(rec.typed.isEmpty())
    }
}
