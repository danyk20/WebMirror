package app.webmirror.android.net

/**
 * While mirroring ([mirroring]: running or paused), the car cannot reach the phone when the virtual address is not
 * active and the car depends on it: either it was switched off behind the user's back ([lost]: another VPN app or
 * Android revoked it), or a secure https name is configured (that name points to the virtual address).
 * Not mirroring with the VPN off is the normal state.
 */
fun carCannotReach(mirroring: Boolean, active: Boolean, lost: Boolean, httpsNameConfigured: Boolean): Boolean =
    mirroring && !active && (lost || httpsNameConfigured)
