package com.beqa.body.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.beqa.body.notify.BodyNotificationListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * M8e — what is playing right now.
 *
 * `MediaSessionManager.getActiveSessions()` normally demands MEDIA_CONTENT_CONTROL
 * (a privileged permission we cannot get). The documented escape hatch: an ENABLED
 * notification listener may pass its own ComponentName and is allowed through. That is
 * exactly what this app is, so this endpoint costs zero new permissions — but it fails
 * hard the moment the listener is disabled, which is why SecurityException is mapped to
 * `not_enabled_listener` rather than swallowed.
 */
object MediaSessionReader {

    fun read(ctx: Context): JSONObject {
        val msm = try {
            ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        } catch (t: Throwable) {
            null
        } ?: return JSONObject().put("ok", false).put("error", "no_media_session_service")

        val component = ComponentName(ctx, BodyNotificationListener::class.java)

        val controllers: List<MediaController> = try {
            msm.getActiveSessions(component) ?: emptyList()
        } catch (e: SecurityException) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_enabled_listener")
                .put("detail", e.message ?: "getActiveSessions denied")
                .put("component", component.flattenToShortString())
        } catch (t: Throwable) {
            return JSONObject()
                .put("ok", false)
                .put("error", "media_read_failed: ${t.javaClass.simpleName}")
        }

        val arr = JSONArray()
        var playingPkg: String? = null
        for (c in controllers) {
            val o = JSONObject()
            try { o.put("pkg", c.packageName) } catch (t: Throwable) { /* ignore */ }
            try { o.put("app", appLabel(ctx, c.packageName)) } catch (t: Throwable) { /* ignore */ }

            try {
                val md: MediaMetadata? = c.metadata
                if (md != null) {
                    putStr(o, "title", md.getString(MediaMetadata.METADATA_KEY_TITLE))
                    putStr(o, "artist", md.getString(MediaMetadata.METADATA_KEY_ARTIST))
                    putStr(o, "album", md.getString(MediaMetadata.METADATA_KEY_ALBUM))
                    putStr(o, "album_artist", md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                    putStr(o, "display_title", md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
                    putStr(o, "display_subtitle", md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
                    val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    if (dur > 0) o.put("duration_ms", dur)
                }
            } catch (t: Throwable) { /* metadata is optional */ }

            try {
                val ps: PlaybackState? = c.playbackState
                if (ps != null) {
                    o.put("playback_state", ps.state)
                    o.put("playback_state_name", stateName(ps.state))
                    o.put("playing", ps.state == PlaybackState.STATE_PLAYING)
                    o.put("position_ms", ps.position)
                    o.put("speed", ps.playbackSpeed.toDouble())
                    o.put("actions_bitmask", ps.actions)
                    if (ps.state == PlaybackState.STATE_PLAYING && playingPkg == null) {
                        playingPkg = try { c.packageName } catch (t: Throwable) { null }
                    }
                } else {
                    o.put("playing", false)
                }
            } catch (t: Throwable) {
                o.put("playing", false)
            }

            try {
                val info = c.playbackInfo
                if (info != null) {
                    o.put("volume", info.currentVolume)
                    o.put("volume_max", info.maxVolume)
                }
            } catch (t: Throwable) { /* optional */ }

            arr.put(o)
        }

        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("now_playing", playingPkg ?: JSONObject.NULL)
            .put("sessions", arr)
    }

    private fun stateName(v: Int): String = when (v) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "UNKNOWN"
    }

    private fun putStr(o: JSONObject, k: String, v: String?) {
        if (!v.isNullOrBlank()) o.put(k, v)
    }

    private fun appLabel(ctx: Context, pkg: String?): String {
        if (pkg.isNullOrBlank()) return "?"
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() ?: pkg
        } catch (t: Throwable) {
            pkg
        }
    }
}
