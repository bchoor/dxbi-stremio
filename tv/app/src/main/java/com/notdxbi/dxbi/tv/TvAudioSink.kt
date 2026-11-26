package com.notdxbi.dxbi.tv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sound that goes out of the TV.
 *
 * DTS and TrueHD are never sent out as they are: some TVs say their HDMI/ARC output takes DTS when
 * the soundbar behind it can't play it (silence). They're decoded here instead, and when the
 * decoded sound has 5.1 or more channels and the output takes Dolby Digital, it's encoded live to
 * Dolby Digital 5.1 (AC-3, 640 kbps), which any soundbar plays, instead of going out as stereo.
 */
@OptIn(UnstableApi::class)
class TvAudioSink(private val sink: AudioSink) : ForwardingAudioSink(sink) {
    companion object {
        /** What it's doing now, for the player's stats: "" when the sound goes out as it is */
        @Volatile var live = ""
        private const val BITRATE = 640_000
    }

    private fun heavy(format: Format) = when (format.sampleMimeType) {
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_TRUEHD -> true
        else -> false
    }

    override fun supportsFormat(format: Format): Boolean = !heavy(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (heavy(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    // ---- Live Dolby Digital ----
    private var handle = 0L
    private var inChannels = 0
    private var rate = 48_000
    private var frame = 1536
    private var acc: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pcm6: ByteBuffer = ByteBuffer.allocateDirect(0)
    private val out: ByteBuffer = ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder())
    private var pending = false
    private var pendingUs = 0L
    private var accStartUs = C.TIME_UNSET
    private var lastBuffer: ByteBuffer? = null
    private var lastBufferUs = C.TIME_UNSET
    private var lastBufferStart = 0

    private val encoding get() = handle != 0L

    private fun stopEncoder() {
        if (handle != 0L) Ac3Native.close(handle)
        handle = 0L
        live = ""
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        stopEncoder()
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(inputFormat.sampleRate).build()
        val wanted = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW &&
            inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT &&
            inputFormat.channelCount >= 6 &&
            inputFormat.sampleRate in setOf(48_000, 44_100, 32_000) &&
            Ac3Native.loaded &&
            super.getFormatSupport(ac3) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        if (wanted) {
            val h = try { Ac3Native.open(inputFormat.sampleRate, 6, BITRATE) } catch (_: Throwable) { 0L }
            if (h != 0L) {
                handle = h
                inChannels = inputFormat.channelCount
                rate = inputFormat.sampleRate
                frame = Ac3Native.frameSize(h).takeIf { it > 0 } ?: 1536
                acc = ByteBuffer.allocateDirect(frame * inChannels * 2).order(ByteOrder.nativeOrder())
                pcm6 = ByteBuffer.allocateDirect(frame * 6 * 2).order(ByteOrder.nativeOrder())
                pending = false
                accStartUs = C.TIME_UNSET
                lastBuffer = null
                live = "Dolby Digital 5.1 · ${BITRATE / 1000} kbps (encoded live from ${inChannels.let { if (it == 8) "7.1" else if (it == 6) "5.1" else "$it ch" }})"
                super.configure(ac3, 0, null)
                return
            }
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!encoding) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // where this buffer started, to time each frame cut from it
        if (buffer !== lastBuffer || presentationTimeUs != lastBufferUs) {
            lastBuffer = buffer
            lastBufferUs = presentationTimeUs
            lastBufferStart = buffer.position()
        }
        val bytesPerFrame = inChannels * 2
        while (true) {
            if (pending) {
                if (!super.handleBuffer(out, pendingUs, 1)) return false
                pending = false
            }
            if (!buffer.hasRemaining()) return true
            if (acc.position() == 0) {
                val offsetFrames = (buffer.position() - lastBufferStart) / bytesPerFrame
                accStartUs = presentationTimeUs + offsetFrames * 1_000_000L / rate
            }
            val n = minOf(buffer.remaining(), acc.remaining())
            val slice = buffer.duplicate()
            slice.limit(buffer.position() + n)
            acc.put(slice)
            buffer.position(buffer.position() + n)
            if (!acc.hasRemaining()) {
                encodeFrame()
                acc.clear()
            }
        }
    }

    /** One frame of [acc] (5.1 or 7.1) as 5.1, into the encoder; its AC-3 frame waits in [out] */
    private fun encodeFrame() {
        acc.flip()
        pcm6.clear()
        val s = acc.asShortBuffer()
        val d = pcm6.asShortBuffer()
        for (i in 0 until frame) {
            val b = i * inChannels
            if (inChannels == 6) {
                for (c in 0 until 6) d.put(s.get(b + c))
            } else {
                // 7.1 (FL FR FC LFE BL BR SL SR): the side channels folded into the back ones
                for (c in 0 until 4) d.put(s.get(b + c))
                val bl = s.get(b + 4) * 0.707f + s.get(b + 6) * 0.707f
                val br = s.get(b + 5) * 0.707f + s.get(b + 7) * 0.707f
                d.put(bl.coerceIn(-32768f, 32767f).toInt().toShort())
                d.put(br.coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
        out.clear()
        val size = Ac3Native.encode(handle, pcm6, out)
        if (size > 0) {
            out.position(0)
            out.limit(size)
            pending = true
            pendingUs = accStartUs
        }
    }

    override fun playToEndOfStream() {
        if (encoding && pending) {
            if (!super.handleBuffer(out, pendingUs, 1)) return
            pending = false
        }
        super.playToEndOfStream()
    }

    override fun hasPendingData(): Boolean = (encoding && pending) || super.hasPendingData()

    override fun flush() {
        acc.clear()
        pending = false
        lastBuffer = null
        accStartUs = C.TIME_UNSET
        super.flush()
    }

    override fun reset() {
        stopEncoder()
        super.reset()
    }

    override fun release() {
        stopEncoder()
        super.release()
    }
}
