package app.webmirror.android.input

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import app.webmirror.android.TypingKeyboard

/**
 * Typing from the car (plan 2.1.5). Pure helpers: validation of the page's `type` / `key` messages, the `kbd` message
 * the phone sends, and the mapping from what the accessibility service may read (the kind of the focused field, never
 * its content) to it.
 */

/** A key the page may press (`{t:"key", key}`). */
enum class TypingKey(val wire: String, val keyCode: Int) {
    BACKSPACE("Backspace", KeyEvent.KEYCODE_DEL),
    ENTER("Enter", KeyEvent.KEYCODE_ENTER),
    TAB("Tab", KeyEvent.KEYCODE_TAB),
    LEFT("ArrowLeft", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("ArrowRight", KeyEvent.KEYCODE_DPAD_RIGHT);

    companion object {
        fun parse(s: String?): TypingKey? = entries.firstOrNull { it.wire == s }
    }
}

/** `{t:"kbd", show, type, action, multiline}` for the control owner: a text field has (show) or lost input focus. */
data class KbdState(
    val show: Boolean,
    val type: String = "text",
    val action: String = "enter",
    val multiline: Boolean = false,
) {
    fun toJson(): String =
        if (!show) """{"t":"kbd","show":false}"""
        else """{"t":"kbd","show":true,"type":"$type","action":"$action","multiline":$multiline}"""

    companion object {
        val HIDDEN = KbdState(false)
    }
}

object Typing {
    /** Longest text of one `type` message (the page splits longer text). */
    const val MAX_TEXT = 256

    /** Text to commit, or null when the message must be ignored: not a string, empty, too long, or has control characters. */
    fun validateText(value: Any?): String? {
        val s = value as? String ?: return null
        if (s.isEmpty() || s.length > MAX_TEXT) return null
        for (ch in s) if (ch < ' ' || ch == '\u007f') return null
        return s
    }

    /** Typing is possible only in Tesla mode, on Android 13+ (the accessibility service's own input method). */
    const val MIN_SDK = 33

    fun supported(sdk: Int): Boolean = sdk >= MIN_SDK

    /** The mode really in effect: the Tesla keyboard needs the choice and a new enough Android. */
    fun teslaEffective(choice: TypingKeyboard, sdk: Int): Boolean = choice == TypingKeyboard.TESLA && supported(sdk)

    /**
     * Whether the accessibility service should watch for text fields and hide the phone keyboard: Tesla mode in effect
     * and some viewer owns the control.
     */
    fun active(teslaEffective: Boolean, ownerPresent: Boolean): Boolean = teslaEffective && ownerPresent

    /** `text`, `number`, `phone`, `password`, `email` or `url` from an Android `InputType` value. */
    fun fieldType(inputType: Int, isPassword: Boolean = false): String {
        if (isPassword) return "password"
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) "password" else "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> "password"
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
                InputType.TYPE_TEXT_VARIATION_URI -> "url"
                else -> "text"
            }
            else -> "text"
        }
    }

    fun isMultiLine(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    /** `done`, `search`, `go`, `send`, `next`, or `enter` (no special action: Enter is a line break / plain Enter key). */
    fun actionName(imeOptions: Int, multiLine: Boolean): String {
        if (multiLine || imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "enter"
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE -> "done"
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS -> "next"
            else -> "enter"
        }
    }

    /** The state to tell the page for a focused editable field with these properties. */
    fun kbdState(inputType: Int, imeOptions: Int, isPassword: Boolean = false, multiLineNode: Boolean = false): KbdState {
        val multi = multiLineNode || isMultiLine(inputType)
        return KbdState(true, fieldType(inputType, isPassword), actionName(imeOptions, multi), multi)
    }

    /**
     * What Enter does in the focused field: an IME editor action (`IME_ACTION_*`), or null for a plain Enter key press
     * (multi-line fields, "no enter action", or no action requested).
     */
    fun enterEditorAction(imeOptions: Int, inputType: Int): Int? {
        if (isMultiLine(inputType) || imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val a = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS,
            -> a
            else -> null
        }
    }
}
