package app.webmirror.android.net

/**
 * The car cannot reach the phone when the virtual address is not active and the car depends on it: either it was
 * switched off behind the user's back ([lost]: another VPN app or Android revoked it), or a secure https name is
 * configured (that name points to the virtual address).
 */
fun carCannotReach(active: Boolean, lost: Boolean, httpsNameConfigured: Boolean): Boolean =
    !active && (lost || httpsNameConfigured)
