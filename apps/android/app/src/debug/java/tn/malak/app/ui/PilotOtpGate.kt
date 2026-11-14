package tn.malak.app.ui

/**
 * Debug APK only: fixed pilot PIN for local UI navigation.
 * It never issues a bearer token, proves a phone number, or authorizes
 * private uploads. NEVER copy this to main/release source sets.
 */
internal object PilotOtpGate {
    const val enabled = true
    fun verify(localPhone: String, code: String): Boolean =
        localPhone.matches(Regex("^[2-9][0-9]{7}$")) && code == "1234"
}
