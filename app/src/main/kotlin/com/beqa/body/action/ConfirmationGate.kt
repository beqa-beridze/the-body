package com.beqa.body.action

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-phase confirmation gate for irreversible actions.
 *
 * First call (confirm == null) issues a one-time token bound to the exact
 * action + canonical params. The caller must echo that token back, with the
 * IDENTICAL action + canonical, to actually proceed. A token issued for
 * "text Mom hi" can never authorize "text a stranger something else".
 */
object ConfirmationGate {

    private const val TTL_MS = 120_000L
    private const val MAX_PENDING = 5
    private const val EXPIRES_IN_S = 120
    private const val TOKEN_BYTES = 16

    private data class Pending(
        val action: String,
        val canonicalSha256: String,
        val expiresAtMs: Long
    )

    /** Keyed by confirm token. Raw params are never stored, only the canonical hash. */
    private val pending = ConcurrentHashMap<String, Pending>()

    private val random = SecureRandom()

    /**
     * @param action    short action name, e.g. "sms.send"
     * @param canonical a stable string uniquely identifying the exact action + its params
     * @param summary   a caller-provided, already-safe JSONObject describing the action (echoed back)
     * @param confirm   the confirm token the caller is echoing, or null on the first call
     * @return null  -> PROCEED (confirm was valid, unexpired, single-use, and bound to this exact action+canonical)
     *         else  -> a gate JSONObject the caller must return as-is:
     *           {ok:false, confirmation_required:true, confirm_token:"...", action:"...",
     *            summary:{...}, expires_in_s:120}  (+ "error":"confirm_invalid" if a bad/expired token was supplied)
     */
    fun guard(action: String, canonical: String, summary: JSONObject, confirm: String?): JSONObject? {
        val now = System.currentTimeMillis()
        pruneExpired(now)

        val canonicalHash = sha256Hex(canonical)
        var invalidTokenSupplied = false

        if (!confirm.isNullOrBlank()) {
            val confirmBytes = confirm.toByteArray(StandardCharsets.UTF_8)
            var matchedToken: String? = null
            for ((token, entry) in pending) {
                val tokenMatches = MessageDigest.isEqual(
                    token.toByteArray(StandardCharsets.UTF_8),
                    confirmBytes
                )
                if (tokenMatches &&
                    entry.action == action &&
                    entry.canonicalSha256 == canonicalHash &&
                    entry.expiresAtMs > now
                ) {
                    matchedToken = token
                    break
                }
            }
            // remove() is the atomic single-use claim: if two threads race with the
            // same token, exactly one wins and proceeds.
            if (matchedToken != null && pending.remove(matchedToken) != null) {
                return null // PROCEED
            }
            invalidTokenSupplied = true
        }

        // Issue path: mint a fresh one-time token bound to this exact action+params.
        evictIfFull()
        val token = newToken()
        pending[token] = Pending(action, canonicalHash, now + TTL_MS)

        val gate = JSONObject()
            .put("ok", false)
            .put("confirmation_required", true)
            .put("confirm_token", token)
            .put("action", action)
            .put("summary", summary)
            .put("expires_in_s", EXPIRES_IN_S)
        if (invalidTokenSupplied) {
            gate.put("error", "confirm_invalid")
        }
        return gate
    }

    private fun pruneExpired(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAtMs <= now) {
                it.remove()
            }
        }
    }

    /** Keep at most MAX_PENDING entries; evict the soonest-to-expire (oldest) first. */
    private fun evictIfFull() {
        while (pending.size >= MAX_PENDING) {
            val oldest = pending.entries.minByOrNull { it.value.expiresAtMs } ?: return
            pending.remove(oldest.key, oldest.value)
        }
    }

    private fun newToken(): String {
        val raw = ByteArray(TOKEN_BYTES)
        random.nextBytes(raw)
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }
}
