package com.notdxbi.dxbi.tv.marquee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * A YouTube trailer's picture and sound addresses, read with NewPipe's extractor, so it plays in
 * Marquee's own player instead of the YouTube app. YouTube changes now and then; when reading
 * fails, the trailer opens in the YouTube app as before.
 */
object Trailer {
    class Streams(val video: String, val audio: String?, val label: String)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var ready = false

    private object Net : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            request.headers().forEach { (k, values) -> b.removeHeader(k); values.forEach { b.addHeader(k, it) } }
            http.newCall(b.build()).execute().use { r ->
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    /** The best picture up to 1080p (H.264 first: every TV decodes it) and the best AAC sound */
    suspend fun streams(ytId: String): Streams? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(20_000) {
            try {
                if (!ready) { NewPipe.init(Net); ready = true }
                val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$ytId")
                val progressive = { d: DeliveryMethod -> d == DeliveryMethod.PROGRESSIVE_HTTP }
                val video = info.videoOnlyStreams
                    .filter { it.isUrl && progressive(it.deliveryMethod) && it.height in 1..1080 }
                    .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                        .thenByDescending { it.codec.orEmpty().startsWith("avc") })
                    .firstOrNull()
                val audio = info.audioStreams
                    .filter { it.isUrl && progressive(it.deliveryMethod) }
                    .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.codec.orEmpty().startsWith("mp4a") }
                        .thenByDescending { it.averageBitrate })
                    .firstOrNull()
                if (video != null && audio != null) return@withTimeoutOrNull Streams(video.content, audio.content, "${video.height}p")
                // a stream with picture and sound together (360p) when the separate ones aren't there
                val muxed = info.videoStreams.filter { it.isUrl && progressive(it.deliveryMethod) }.maxByOrNull { it.height } ?: return@withTimeoutOrNull null
                Streams(muxed.content, null, "${muxed.height}p")
            } catch (_: Throwable) { null }
        }
    }
}
