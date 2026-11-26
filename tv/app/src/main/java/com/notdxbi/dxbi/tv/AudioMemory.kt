package com.notdxbi.dxbi.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/**
 * The audio track picked for a source, so the next play of that same source starts in it.
 * Kept per source (torrent hash and file, or the file name) with its language, codec and
 * channels, since a track's position can differ between two copies of a file.
 */
object AudioMemory {
    private const val PREFS = "audio-picks"
    private const val KEEP = 500

    fun save(context: Context, key: String, format: Format) {
        if (key.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pick = JSONObject()
            .put("lang", format.language ?: "")
            .put("mime", format.sampleMimeType ?: "")
            .put("channels", format.channelCount)
            .put("label", format.label ?: "")
            .put("at", System.currentTimeMillis())
        val edit = prefs.edit().putString(key, pick.toString())
        // (only the most recent picks are kept)
        if (prefs.all.size > KEEP) {
            prefs.all.entries
                .sortedBy { runCatching { JSONObject(it.value as String).optLong("at") }.getOrDefault(0L) }
                .take(prefs.all.size - KEEP)
                .forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    fun restore(context: Context, key: String, exo: ExoPlayer, tracks: Tracks) {
        if (key.isEmpty()) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return
        val pick = runCatching { JSONObject(saved) }.getOrNull() ?: return
        var best: Pair<Tracks.Group, Int>? = null
        var bestScore = 1 // (a lone channel-count match is not enough)
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                var score = 0
                if ((f.language ?: "") == pick.optString("lang")) score += 4
                if ((f.sampleMimeType ?: "") == pick.optString("mime")) score += 2
                if (f.channelCount == pick.optInt("channels", -1)) score += 1
                if ((f.label ?: "") == pick.optString("label") && pick.optString("label").isNotEmpty()) score += 1
                if (score > bestScore) { bestScore = score; best = group to i }
            }
        }
        val (group, i) = best ?: return
        if (group.isTrackSelected(i)) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
            .build()
    }
}
