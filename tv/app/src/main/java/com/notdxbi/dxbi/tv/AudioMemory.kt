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

    /** Whether a track was picked by hand for this video before */
    fun has(context: Context, key: String) = key.isNotEmpty() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(key)

    /**
     * A file whose first sound is DTS or TrueHD often carries the same sound in Dolby too (5.1 or
     * more, same language, not commentary): that one goes to a soundbar as it is, so it's chosen
     */
    fun preferDolby(exo: ExoPlayer, tracks: Tracks) {
        val chosen = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return
        val now = (0 until chosen.length).firstOrNull { chosen.isTrackSelected(it) }?.let { chosen.getTrackFormat(it) } ?: return
        val heavy = now.sampleMimeType.orEmpty().let { it.contains("dts") || it == androidx.media3.common.MimeTypes.AUDIO_TRUEHD }
        if (!heavy) return
        val dolby = setOf(androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC, androidx.media3.common.MimeTypes.AUDIO_E_AC3, androidx.media3.common.MimeTypes.AUDIO_AC3)
        var best: Pair<Tracks.Group, Int>? = null
        var bestScore = -1
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                if (f.sampleMimeType !in dolby || f.channelCount < 6) continue
                if (now.language != null && f.language != null && f.language != now.language) continue
                if ((f.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0 || f.label.orEmpty().contains("comment", true)) continue
                val score = when (f.sampleMimeType) { androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> 3; androidx.media3.common.MimeTypes.AUDIO_E_AC3 -> 2; else -> 1 } * 10 + f.channelCount
                if (score > bestScore) { bestScore = score; best = group to i }
            }
        }
        val (group, index) = best ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
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
