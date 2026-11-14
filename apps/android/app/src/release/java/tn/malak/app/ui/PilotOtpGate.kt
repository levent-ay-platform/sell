package tn.malak.app.ui

/** Production always rejects fixed pilot OTP, regardless of caller. */
internal object PilotOtpGate {
    const val enabled = false
    fun verify(localPhone: String, code: String): Boolean = false
}
