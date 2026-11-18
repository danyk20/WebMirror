package app.webmirror.android.update

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** Nothing known: no releases yet, or the repository does not exist. */
    data object NoInfo : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    /** [progress] 0f..1f, or -1f when the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo) : UpdateState
    /** The user must allow "install unknown apps" for WebMirror (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES). */
    data class NeedsInstallPermission(val release: ReleaseInfo) : UpdateState
    /** Waiting for the system to finish (or for the user to confirm). */
    data class Installing(val release: ReleaseInfo) : UpdateState
    /** Auto-install is waiting until mirroring stops. */
    data class Postponed(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}
