package app.webmirror.android.update

enum class AutoAction {
    /** Download and install now. */
    Install,
    /** Install as soon as mirroring stops (installing kills the app). */
    Postpone,
    /** Only tell the user an update is available. */
    NotifyOnly,
}

/** Pure decisions for the update engine. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun decideAuto(autoInstall: Boolean, mirrorRunning: Boolean, canRequestInstalls: Boolean): AutoAction = when {
        !autoInstall -> AutoAction.NotifyOnly
        !canRequestInstalls -> AutoAction.NotifyOnly
        mirrorRunning -> AutoAction.Postpone
        else -> AutoAction.Install
    }

    fun isCheckDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS
}
