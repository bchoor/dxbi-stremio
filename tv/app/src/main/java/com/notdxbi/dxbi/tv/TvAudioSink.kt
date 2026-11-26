package com.notdxbi.dxbi.tv

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * DTS and TrueHD are never sent out as they are. Some TVs say their HDMI/ARC output takes DTS when
 * the soundbar behind it can't play it (silence), so they're always decoded here instead.
 */
@OptIn(UnstableApi::class)
class TvAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private fun heavy(format: Format) = when (format.sampleMimeType) {
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_TRUEHD -> true
        else -> false
    }

    override fun supportsFormat(format: Format): Boolean = !heavy(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (heavy(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}
