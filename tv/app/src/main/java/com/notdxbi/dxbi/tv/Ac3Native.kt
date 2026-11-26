package com.notdxbi.dxbi.tv

import java.nio.ByteBuffer

/** FFmpeg's Dolby Digital (AC-3) encoder (native/ac3enc); missing in builds made without the NDK */
object Ac3Native {
    val loaded: Boolean = try { System.loadLibrary("ac3enc"); true } catch (_: Throwable) { false }

    @JvmStatic external fun open(sampleRate: Int, channels: Int, bitrate: Int): Long
    @JvmStatic external fun frameSize(handle: Long): Int
    /** [pcm]: one frame of interleaved 16-bit samples; the AC-3 frame goes in [out], its size returned */
    @JvmStatic external fun encode(handle: Long, pcm: ByteBuffer, out: ByteBuffer): Int
    @JvmStatic external fun close(handle: Long)
}
