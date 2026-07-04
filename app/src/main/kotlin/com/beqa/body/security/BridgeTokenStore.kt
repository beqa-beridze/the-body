package com.beqa.body.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bridge auth token: a random secret required as `Authorization: Bearer <token>` on
 * every control route except /health. Generated lazily, persisted in app-private
 * SharedPreferences, rotatable from the UI. Writes use commit() so it's durable before
 * the server starts. Validation is constant-time and length-padded so neither the value
 * nor its length leaks via timing. Plain SharedPreferences (EncryptedSharedPreferences
 * would drag in an AndroidX AAR that breaks the zero-AAR on-device build).
 */
class BridgeTokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        prefs.getString(KEY, null)?.let { return it }
        val fresh = generate()
        prefs.edit().putString(KEY, fresh).commit()
        return fresh
    }

    fun rotate(): String {
        val fresh = generate()
        prefs.edit().putString(KEY, fresh).commit()
        return fresh
    }

    /** Constant-time, length-padded comparison against the stored token. */
    fun validate(provided: String): Boolean {
        val current = prefs.getString(KEY, null) ?: return false
        val a = current.toByteArray(Charsets.UTF_8)
        val b = provided.toByteArray(Charsets.UTF_8)
        val n = maxOf(a.size, b.size, PAD)
        val pa = ByteArray(n); System.arraycopy(a, 0, pa, 0, a.size)
        val pb = ByteArray(n); System.arraycopy(b, 0, pb, 0, b.size)
        // MessageDigest.isEqual is constant-time; the padding removes the length short-circuit.
        return MessageDigest.isEqual(pa, pb) && a.size == b.size
    }

    private fun generate(): String {
        val raw = ByteArray(24)
        SecureRandom().nextBytes(raw)
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val PREFS = "body"
        const val KEY = "bridge_token"
        private const val PAD = 64
    }
}
