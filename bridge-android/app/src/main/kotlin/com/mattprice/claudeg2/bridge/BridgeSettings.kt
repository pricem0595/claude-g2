package com.mattprice.claudeg2.bridge

import android.content.SharedPreferences
import java.security.SecureRandom

const val PREFS_NAME = "bridge"

// No 0/O or 1/I/L, since the token is read off one screen and typed into another.
private const val TOKEN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

// 31^5 ≈ 29 million combinations: plenty for a server only reachable from this phone.
private const val TOKEN_LENGTH = 5

class BridgeSettings(private val prefs: SharedPreferences) {

    /**
     * Pairing token the glasses app must send. Created on first use; one in an older format
     * (8 characters with a dash, before v0.2) is replaced.
     */
    val token: String
        get() = prefs.getString("token", null)?.takeIf { it.length == TOKEN_LENGTH }
            ?: newToken().also { prefs.edit().putString("token", it).apply() }

    fun resetToken(): String = newToken().also { prefs.edit().putString("token", it).apply() }

    /** The Claude app's package; editable in case it differs from the default on this phone. */
    var claudePackage: String
        get() = prefs.getString("package", null) ?: ClaudeUi.DEFAULT_PACKAGE
        set(value) = prefs.edit().putString("package", value.trim()).apply()

    /** Keep the screen on while the service runs, so the Claude app keeps drawing. */
    var keepAwake: Boolean
        get() = prefs.getBoolean("keepAwake", true)
        set(value) = prefs.edit().putBoolean("keepAwake", value).apply()

    private fun newToken(): String {
        val random = SecureRandom()
        return (1..TOKEN_LENGTH).map { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] }.joinToString("")
    }
}
