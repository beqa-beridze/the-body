package com.beqa.body.server

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe per-key failure rate limiter.
 *
 * After [maxFailures] failures within a rolling [windowMs], the key is blocked
 * for [blockMs]. A successful attempt clears the key's failure record.
 *
 * Plain Kotlin/JVM + java.util.concurrent only. The [clock] is injectable for tests.
 */
class AuthRateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000L,
    private val blockMs: Long = 300_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private class Record {
        /** Failure timestamps, oldest first. Guarded by the Record's monitor. */
        val failures = ArrayDeque<Long>()

        /** Epoch millis until which the key is blocked; 0 = not blocked. Guarded by the Record's monitor. */
        var blockedUntil: Long = 0L
    }

    private val records = ConcurrentHashMap<String, Record>()

    /** Returns true iff the key is currently blocked. Cleans up expired/empty records. */
    fun isBlocked(key: String): Boolean {
        val record = records[key] ?: return false
        val now = clock()
        var removeRecord = false
        val blocked = synchronized(record) {
            if (record.blockedUntil > 0L && now >= record.blockedUntil) {
                // Block expired: reset so the key gets a fresh start.
                record.blockedUntil = 0L
                record.failures.clear()
            }
            prune(record, now)
            if (record.blockedUntil == 0L && record.failures.isEmpty()) {
                removeRecord = true
            }
            now < record.blockedUntil
        }
        if (removeRecord) {
            // Remove only if the map still holds this exact (now-empty) record.
            records.remove(key, record)
        }
        return blocked
    }

    /** Records a failed attempt for the key; blocks the key once the threshold is reached. */
    fun recordFailure(key: String) {
        while (true) {
            val record = records.computeIfAbsent(key) { Record() }
            val now = clock()
            val done = synchronized(record) {
                // If another thread removed this record after we fetched it, retry
                // so we don't mutate an orphaned instance.
                if (records[key] !== record) {
                    false
                } else {
                    if (record.blockedUntil > 0L && now >= record.blockedUntil) {
                        record.blockedUntil = 0L
                        record.failures.clear()
                    }
                    prune(record, now)
                    record.failures.addLast(now)
                    if (record.failures.size >= maxFailures) {
                        record.blockedUntil = now + blockMs
                    }
                    true
                }
            }
            if (done) return
        }
    }

    /** Clears the key's failure record entirely. */
    fun recordSuccess(key: String) {
        records.remove(key)
    }

    /** Drops failure timestamps older than the rolling window. Caller must hold the record's monitor. */
    private fun prune(record: Record, now: Long) {
        val cutoff = now - windowMs
        while (record.failures.isNotEmpty() && record.failures.peekFirst() < cutoff) {
            record.failures.pollFirst()
        }
    }
}
