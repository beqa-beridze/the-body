package com.beqa.body.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Known-answer test for [MediaSessionReader].
 *
 * `/media` returning `{"count":0}` on a device where nothing is playing is the correct
 * answer, but it proves only that getActiveSessions() was permitted. It never exercises
 * the metadata/playback-state extraction. So this publishes a MediaSession with metadata
 * we chose, reads it back through the SAME reader a caller uses, and releases it.
 *
 * Silent and request-scoped by construction: state is PAUSED, no audio focus is requested,
 * no media notification is posted, and `release()` runs in a `finally`. The session
 * cannot outlive the HTTP request that created it.
 */
object MediaSelfCheck {

    const val TITLE = "Body self-check"
    const val ARTIST = "com.beqa.body"
    const val ALBUM = "sense layer M8"
    const val DURATION_MS = 123_000L
    const val POSITION_MS = 4_200L

    fun run(ctx: Context): JSONObject {
        val thread = HandlerThread("body-media-selfcheck")
        thread.start()
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<JSONObject>(1)

        handler.post {
            var session: MediaSession? = null
            try {
                session = MediaSession(ctx, "body-selfcheck")
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, TITLE)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, ARTIST)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, ALBUM)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, DURATION_MS)
                        .build()
                )
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PAUSED, POSITION_MS, 0f)
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .build()
                )
                session.isActive = true

                val observed = MediaSessionReader.read(ctx)
                val mine = findSelf(observed)
                box[0] = JSONObject()
                    .put("ok", true)
                    .put("expected", JSONObject()
                        .put("title", TITLE)
                        .put("artist", ARTIST)
                        .put("album", ALBUM)
                        .put("duration_ms", DURATION_MS)
                        .put("position_ms", POSITION_MS)
                        .put("playback_state_name", "PAUSED"))
                    .put("observed_self", mine ?: JSONObject.NULL)
                    .put("match", mine != null &&
                        mine.optString("title") == TITLE &&
                        mine.optString("artist") == ARTIST &&
                        mine.optLong("duration_ms") == DURATION_MS &&
                        mine.optString("playback_state_name") == "PAUSED")
                    .put("full_read", observed)
            } catch (t: Throwable) {
                box[0] = JSONObject()
                    .put("ok", false)
                    .put("error", "selfcheck_failed: ${t.javaClass.simpleName}")
                    .put("detail", t.message)
            } finally {
                try { session?.isActive = false } catch (t: Throwable) { /* ignore */ }
                try { session?.release() } catch (t: Throwable) { /* ignore */ }
                latch.countDown()
            }
        }

        return try {
            if (!latch.await(6, TimeUnit.SECONDS)) {
                JSONObject().put("ok", false).put("error", "selfcheck_timeout")
            } else {
                box[0] ?: JSONObject().put("ok", false).put("error", "selfcheck_no_result")
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "selfcheck_interrupted")
        } finally {
            try { thread.quitSafely() } catch (t: Throwable) { /* ignore */ }
        }
    }

    private fun findSelf(read: JSONObject): JSONObject? {
        return try {
            val arr = read.optJSONArray("sessions") ?: return null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("pkg") == "com.beqa.body") return o
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
