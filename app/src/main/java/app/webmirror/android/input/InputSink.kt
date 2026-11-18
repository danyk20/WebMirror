package app.webmirror.android.input

/** Executes validated input of the control owner on the phone (implemented by the accessibility service bridge). */
interface InputSink {
    fun submit(ev: InputEvent)

    /** Lift every touch that is down (owner left, switch off, rotation, stop). */
    fun releaseAll()

    /** Tesla keyboard: watch for focused text fields and hide the phone keyboard (true), or stop and restore it (false). */
    fun setTypingActive(active: Boolean) {}

    /** Commit [text] (already validated) into the focused text field. */
    fun typeText(text: String) {}

    /** Press [key] in the focused text field. */
    fun pressKey(key: TypingKey) {}
}

/** Receives what the accessibility service learned about text focus (kind of field only, never its content). */
fun interface KbdListener {
    fun onKbd(state: KbdState)
}
