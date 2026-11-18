package app.webmirror.android.input

/** Executes validated input of the control owner on the phone (implemented by the accessibility service bridge). */
interface InputSink {
    fun submit(ev: InputEvent)

    /** Lift every touch that is down (owner left, switch off, rotation, stop). */
    fun releaseAll()
}
