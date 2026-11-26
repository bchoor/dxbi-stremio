package com.notdxbi.dxbi.tv

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The native player: plays the stream Stremio picked (MKV, HEVC, HDR and surround sound included,
 * as far as the TV's hardware can), with the addons' subtitles. Reports its position back so the
 * watch progress is kept.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controls: Controls
    private var extras: PlayerHooks? = null
    // the subtitles' size (Stremio's setting), put back after the phone app's small window's bigger one
    // a little under Media3's 5.33% of the screen's height, the size TV players use
    private var subtitleSize = 0.045f
    private lateinit var zoom: PictureZoom
    private var party = false
    private val partyTick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            PlaybackBus.listener?.onPartyTick(exo.currentPosition, duration, !exo.playWhenReady, exo.playbackState == Player.STATE_BUFFERING, exo.playbackParameters.speed)
            handler.postDelayed(this, 500)
        }
    }
    private val hideToast = Runnable { seekFlash.visibility = View.GONE }

    /** The page's watch party: plays, pauses and seeks with everyone, and says what's happening */
    fun control(json: String) {
        val exo = player ?: return
        val c = JSONObject(json)
        when (c.optString("cmd")) {
            "play" -> exo.play()
            "pause" -> exo.pause()
            "seek" -> exo.seekTo(c.optLong("ms"))
            "rate" -> exo.setPlaybackSpeed(c.optDouble("rate", 1.0).toFloat())
            "close" -> finishWith()
            "party" -> {
                val on = c.optBoolean("on")
                if (on != party) {
                    party = on
                    handler.removeCallbacks(partyTick)
                    if (on) handler.post(partyTick)
                }
                controls.party(on, c.optString("code"), c.optJSONArray("people")?.let { list -> (0 until list.length()).map { list.optString(it) } } ?: emptyList())
            }
            "toast" -> {
                seekFlash.text = c.optString("text")
                seekFlash.visibility = View.VISIBLE
                handler.removeCallbacks(hideToast)
                handler.postDelayed(hideToast, 3500)
            }
        }
    }
    private val sound = SoundMode()
    @Volatile private var subtitleDelayMs = 0L
    private val refreshSubtitles = Runnable { player?.let { it.seekTo(it.currentPosition) } }
    // Up next: what's after this episode, how long the card counts down (0: never), the card's state
    private var nextTitle = ""
    private var nextArt = ""
    private var upNextSeconds = 0
    private var upNextLeft = 0
    private var upNextDismissed = false
    private lateinit var upNextCard: View
    private val upNextCount = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (upNextCard.visibility != View.VISIBLE) return
            if (exo.isPlaying) upNextLeft--
            findViewById<TextView>(R.id.up_next_count).text = upNextLeft.coerceAtLeast(0).toString()
            if (upNextLeft <= 0) finishWith(next = true) else handler.postDelayed(this, 1000)
        }
    }

    private inner class Renderers : DefaultRenderersFactory(this) {
        // The TV's own DTS decoder (MediaTek's DSP) gives out stereo only, so a 5.1/7.1 DTS track
        // reached the Dolby Digital encoder as 2 channels. DTS and TrueHD go to FFmpeg instead,
        // which keeps every channel.
        init {
            setMediaCodecSelector { mime, secure, tunneling ->
                when (mime) {
                    androidx.media3.common.MimeTypes.AUDIO_DTS, androidx.media3.common.MimeTypes.AUDIO_DTS_HD,
                    androidx.media3.common.MimeTypes.AUDIO_DTS_EXPRESS, androidx.media3.common.MimeTypes.AUDIO_DTS_X,
                    androidx.media3.common.MimeTypes.AUDIO_TRUEHD -> emptyList()
                    else -> androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                }
            }
        }

        override fun buildTextRenderers(context: android.content.Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
            val own = ArrayList<Renderer>()
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, own)
            own.mapTo(out) { DelayedText(it) { subtitleDelayMs * 1000 } }
        }

        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            TvAudioSink(DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // (Dialogue boost works on 16-bit sound)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(sound))
                .build())
    }

    private val phone get() = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType != Configuration.UI_MODE_TYPE_TELEVISION &&
        !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private lateinit var clock: TextView
    private var showClock = true
    private lateinit var seekFlash: TextView
    private lateinit var loading: View
    private lateinit var loadingWords: TextView
    @Volatile private var busyUntil = 0L
    // What arrives from the source while the picture waits: whether the server has sent anything
    // yet (Easynews can take a while to start a file it hasn't served lately), and how fast
    private val bytesIn = java.util.concurrent.atomic.AtomicLong()
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var rate = 0.0 // bytes a second, smoothed
    private val fromEasynews by lazy {
        val st = try { JSONObject(intent.getStringExtra(EXTRA_STREAM) ?: "{}") } catch (_: Exception) { JSONObject() }
        Uri.parse(st.optString("url")).host.orEmpty().contains("easynews", true) || st.optString("url").contains("easynews", true) ||
            st.optString("provider").contains("easynews", true)
    }
    private val received = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) { bytesIn.addAndGet(bytesTransferred.toLong()) }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
    }

    /** "Waiting for the server…", then "Receiving 18 MB/s · 1.2 of 2.5 s ready" */
    private fun arrival(now: Long): String {
        val got = bytesIn.get()
        if (lastBytesAt > 0 && now > lastBytesAt) {
            val r = (got - lastBytes) * 1000.0 / (now - lastBytesAt)
            rate = if (rate == 0.0) r else rate * 0.6 + r * 0.4
        }
        lastBytes = got; lastBytesAt = now
        val exo = player ?: return ""
        val who = if (fromEasynews) "Easynews" else "The server"
        if (got == 0L) return "$who is preparing the file…"
        val need = if (everReady) 5.0 else 2.5 // seconds the player wants before it plays (Media3's defaults)
        val ready = (exo.totalBufferedDuration / 1000.0).coerceAtMost(need)
        return "Receiving %.1f MB/s · %.1f of %.1f s ready".format(rate / 1_000_000, ready, need)
    }
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var lastError: String? = null
    private var lastText: List<String?> = emptyList()
    /** Which source is playing (the page's sourceKey), for the audio track picked last time */
    var sourceKey = ""
        private set
    private var audioRestored = false

    private val reportProgress = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }
    private val hideFlash = Runnable { seekFlash.visibility = View.GONE }

    // Skip intro / recap / credits: the episode's times (seconds; end -1 = to the end), whether dx.bi
    // skips by itself ("intro": intros and recaps, "all": credits too), and which were skipped already
    private class Segment(val type: String, val start: Double, val end: Double)
    private var segments: List<Segment> = emptyList()
    private var autoSkip = "off"
    private val autoSkipped = mutableSetOf<Segment>()
    private var showing: Segment? = null
    private lateinit var skipButton: TextView
    // A torrent played on the TV itself: how it's going, while the picture waits for it
    private var torrent = false
    private var waitingSince = 0L
    private var everReady = false
    private val torrentStatus = object : Runnable {
        override fun run() {
            val exo = player
            val waiting = exo == null || exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE
            // Any other stream: how long it has been waiting, so slow (an Easynews start) can be
            // told from stuck (Back tries another source)
            val now = android.os.SystemClock.elapsedRealtime()
            if (!waiting) waitingSince = 0L else if (waitingSince == 0L) waitingSince = now
            val waited = if (waitingSince > 0L) (now - waitingSince) / 1000 else 0L
            val text = if (torrent && waiting) TorrentEngine.status() else null
            // any other stream: a quiet spinner in the middle, a word under it only when it's slow
            val spin = !torrent && waiting && (waited >= 1 || !everReady)
            loading.visibility = if (spin) View.VISIBLE else View.GONE
            val stage = if (spin && waited >= 2) arrival(now) else { lastBytesAt = 0; rate = 0.0; "" }
            loadingWords.text = when {
                now < busyUntil -> "The source is busy · trying again…"
                waited >= 45 -> "$stage\nThis source isn't starting · Back to pick another"
                waited >= 15 -> "$stage\nBack tries another source"
                else -> stage
            }.trim()
            loadingWords.visibility = if (loadingWords.text.isEmpty()) View.GONE else View.VISIBLE
            // never ready and nothing received in 90 s: it isn't coming, say so instead of spinning on
            if (!torrent && !everReady && waited >= 90 && bytesIn.get() == 0L && !closed) {
                finishWith(error = "The source didn't send anything in 90 s. Pick another source.")
                return
            }
            if (text != null) {
                seekFlash.text = text
                seekFlash.visibility = View.VISIBLE
            } else if (seekFlash.text.contains("peer") || seekFlash.text.startsWith("Finding") || seekFlash.text.startsWith("Buffering ·") || seekFlash.text.startsWith("Starting the stream")) {
                seekFlash.visibility = View.GONE
            }
            handler.postDelayed(this, 700)
        }
    }
    private val updateClock = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null) {
                val format = android.text.format.DateFormat.getTimeFormat(this@PlayerActivity)
                val left = if (exo.duration > 0) ((exo.duration - exo.currentPosition) / exo.playbackParameters.speed).toLong() else 0L
                val now = System.currentTimeMillis()
                clock.text = format.format(now) + if (left > 0) " · Ends at ${format.format(now + left)}" else ""
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val checkSkip = object : Runnable {
        override fun run() {
            updateSkip()
            updateUpNext()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones play full screen sideways, like Netflix (either way round), unless the viewer chose
        // dx.bi settings → Player → Turn with my phone; TVs stay landscape
        if (phone) requestedOrientation =
            if (getSharedPreferences("phone-cache", MODE_PRIVATE).getBoolean("playerTurns", false)) ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        clock = findViewById(R.id.clock)
        seekFlash = findViewById(R.id.seek_flash)
        run {
            val d = resources.displayMetrics.density
            loadingWords = TextView(this).apply {
                setTextColor(0xCCFFFFFF.toInt()); textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (14 * d).toInt(), 0, 0)
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
            }
            loading = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                visibility = View.GONE
                isClickable = false; isFocusable = false
                addView(android.widget.ProgressBar(this@PlayerActivity).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
                }, android.widget.LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
                addView(loadingWords)
            }
            loadingWords.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            (seekFlash.parent as android.view.ViewGroup).addView(loading, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }
        skipButton = findViewById(R.id.skip_button)
        skipButton.setOnClickListener { doSkip() }

        val stream = JSONObject(intent.getStringExtra(EXTRA_STREAM) ?: "{}")
        // dx.bi for phones: its controls, gestures, small window and downloads (none in dx.bi TV)
        extras = Phone.player(host, stream)
        segments = stream.optJSONArray("skips")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type")
                if (type !in setOf("intro", "recap", "credits")) return@mapNotNull null
                Segment(type, o.optDouble("start", 0.0), if (o.isNull("end")) -1.0 else o.optDouble("end", -1.0))
            }
        } ?: emptyList()
        autoSkip = stream.optString("autoSkip", "off")
        showClock = stream.optBoolean("clock", true)
        torrent = stream.optBoolean("torrent", false)
        // "plain": no words over the picture (title, details, button names): Marquee's player
        val plain = stream.optBoolean("plain", false)
        findViewById<TextView>(R.id.title).apply {
            text = stream.optString("title")
            if (plain) visibility = View.GONE
        }
        findViewById<TextView>(R.id.subtitle).apply {
            text = stream.optString("subtitle")
            visibility = if (plain || text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        if (plain) findViewById<View>(R.id.ctl_hint)?.tag = "plain"

        stream.optJSONObject("subtitleStyle")?.let { styleSubtitles(it) }

        val url = stream.optString("url")
        sourceKey = stream.optString("sourceKey").ifEmpty { url.substringBefore('?') }
        if (url.isEmpty()) {
            finishWith(error = "no stream address")
            return
        }

        // Addons can ask for headers on their links (Stremio's proxyHeaders)
        val headers = mutableMapOf<String, String>()
        stream.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // a torrent's first bytes wait for its details and first pieces to come from peers
            .readTimeout(if (torrent) 150L else 30L, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        // (an addon's own User-Agent replaces ours: Media3 would send both)
        val ownAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val online = OkHttpDataSource.Factory(http)
            .setUserAgent(if (ownAgent) null else "dxbi-tv/${BuildConfigVersion.name(this)}")
            .setDefaultRequestProperties(headers)
            .setTransferListener(received)
        val dataSource = extras?.dataSource(online) ?: online
        // A host that answers 429 (too many requests: Real-Debrid when it's busy) is asked again
        // after 2, 4, 8 and 16 s, and said so on screen; after that the error shows instead of a
        // spinner that never ends
        val errors = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val code = (info.exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code == 429) {
                    busyUntil = android.os.SystemClock.elapsedRealtime() + 20_000
                    return if (info.errorCount <= 4) 2000L shl (info.errorCount - 1) else C.TIME_UNSET
                }
                if (code != null && code in 400..499) return C.TIME_UNSET // a refused link won't start working by waiting
                return super.getRetryDelayMsFor(info)
            }
            override fun getMinimumLoadableRetryCount(dataType: Int) = 5
        }

        // The TV's own decoders first, falling back to software ones when a format isn't supported
        val renderers = Renderers()
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(errors))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        player = exo
        playerView.player = exo
        val accent = try { Color.parseColor(stream.optString("accent")) } catch (_: Exception) { Color.parseColor("#E50914") }
        zoom = PictureZoom(playerView)
        subtitlesOnScreen()
        val next = stream.optJSONObject("next")
        nextTitle = next?.optString("title").orEmpty()
        nextArt = next?.optString("thumbnail").orEmpty()
        upNextSeconds = if (next == null) 0 else stream.optString("upNext", "10").toIntOrNull() ?: 0
        upNextCard = findViewById(R.id.up_next)
        controls = extras?.controls(exo, sound, zoom, accent, next != null, showClock)
            ?: PlayerControls(this, playerView, exo, sound, zoom, ::shiftSubtitles, accent, clock.takeIf { showClock }, next != null, ::seek, { PlaybackBus.listener?.onPartyLeave() }) { go -> finishWith(next = go) }
        PlaybackBus.player = this
        extras?.started(exo, controls)
        fitControls()
        findViewById<View>(R.id.up_next_play).setOnClickListener { finishWith(next = true) }
        findViewById<View>(R.id.up_next_cancel).setOnClickListener {
            upNextDismissed = true
            hideUpNext()
        }

        // Subtitles start in Stremio's subtitle language (Settings → Player) when dx.bi's "Subtitles when
        // a video starts" is on: the page sends that language, and marks the subtitle file to start
        // with. Off (dx.bi's default, like Netflix): pick one from the subtitles button.
        // Files with several audio tracks (dual-audio releases) start in Stremio's audio language
        // (Settings → Player), when they have it
        val audioLanguage = stream.optString("audioLanguage").ifEmpty { null }
        val subtitleLanguage = stream.optString("subtitleLanguage").ifEmpty { null }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(subtitleLanguage)
            .setSelectUndeterminedTextLanguage(false)
            .apply { if (audioLanguage != null) setPreferredAudioLanguage(audioLanguage) }
            .build()

        val subtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
        stream.optJSONArray("subtitles")?.let { list ->
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val subUrl = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                subtitles += MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setId(extras?.subtitleId(s.optString("source", "addon"), i))
                    .setMimeType(subtitleMime(subUrl))
                    .setLanguage(s.optString("lang").ifEmpty { null })
                    .setLabel(s.optString("label").ifEmpty { s.optString("lang") })
                    .setSelectionFlags(if (s.optBoolean("start")) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
        }

        val item = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setSubtitleConfigurations(subtitles)
            .build()
        val startMs = stream.optLong("startMs", 0L).coerceAtLeast(0L)
        val audioUrl = stream.optString("audioUrl")
        if (audioUrl.isNotEmpty()) {
            // picture and sound from separate addresses (YouTube trailers), played together
            val factory = DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(errors)
            exo.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(
                factory.createMediaSource(item), factory.createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))), startMs)
        } else exo.setMediaItem(item, startMs)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) everReady = true
                if (state == Player.STATE_ENDED) finishWith(ended = true, next = upNextSeconds > 0 && !upNextDismissed)
            }

            // A subtitle switched on mid-line only shows from its next line: a seek to where we are
            // brings the current one up straight away
            override fun onTracksChanged(tracks: Tracks) {
                if (!audioRestored && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    audioRestored = true
                    if (AudioMemory.has(this@PlayerActivity, sourceKey)) AudioMemory.restore(this@PlayerActivity, sourceKey, exo, tracks)
                    else AudioMemory.preferDolby(exo, tracks)
                }
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { i -> g.getTrackFormat(i).id } }
                if (text.isNotEmpty() && text != lastText) exo.seekTo(exo.currentPosition)
                lastText = text
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = (if (torrent) TorrentEngine.failed() else null) ?: describe(error)
                // A phone: a format this player can't handle may play in another app. Handed on, it
                // closes without an error (the page would otherwise move on to another stream)
                if (!torrent && cantHandle(error) && Phone.offerOtherApp(this@PlayerActivity, stream, lastError ?: "") { elsewhere ->
                        finishWith(error = if (elsewhere) null else lastError)
                    }) return
                finishWith(error = lastError)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) { extras?.changed() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { extras?.changed() }
        })
        exo.prepare()
        exo.playWhenReady = true
        handler.postDelayed(reportProgress, PROGRESS_INTERVAL_MS)
        if (showClock) handler.post(updateClock)
        handler.postDelayed(checkSkip, 500)
        handler.post(torrentStatus)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back closes an open panel, then hides the controls, then leaves the player (the position is kept)
            override fun handleOnBackPressed() {
                if (controls.closePanel()) return
                if (controls.visible) { controls.hide(); return }
                finishWith()
            }
        })
    }

    /**
     * With the controls hidden, the remote works like Netflix's: left/right skip 10 seconds, OK
     * pauses or plays. With the controls showing, the arrows move between them as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { exo.playWhenReady = !exo.playWhenReady; controls.show(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); controls.show(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(+1); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-1); return true }
            }
            // OK presses Skip while it's up (and the controls aren't)
            if (showing != null && !controls.visible && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                doSkip()
                return true
            }
            if (controls.panelKey(event.keyCode)) return true
            if (controls.visible) controls.poke()
            // The up next card's buttons take the remote
            if (upNextCard.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            if (!controls.visible) {
                when (event.keyCode) {
                    // Left/Right bring up the timeline and move along it (faster the longer they're held);
                    // the key goes on to the timeline, which takes it from here
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { controls.scrub(); return super.dispatchKeyEvent(event) }
                    // OK, Up and Down bring the controls up (pause from their play button, or the remote's play/pause key)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { controls.show(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun segmentEnd(s: Segment, durationMs: Long): Long =
        if (s.end < 0) durationMs else (s.end * 1000).toLong()

    private fun updateSkip() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        val now = exo.currentPosition
        // (not the last second of a part: pressing then would skip nothing)
        // (the up next card takes over from Skip credits)
        val seg = segments.firstOrNull { now >= (it.start * 1000).toLong() && now < segmentEnd(it, duration) - 1000 && !(it.type == "credits" && upNextSeconds > 0) }
        val auto = seg != null && (autoSkip == "all" || (autoSkip == "intro" && seg.type != "credits"))
        if (seg != null && auto && seg !in autoSkipped) {
            autoSkipped += seg
            doSkip(seg)
            return
        }
        if (seg == showing) return
        showing = seg
        if (seg == null) {
            skipButton.visibility = View.GONE
            return
        }
        skipButton.text = when (seg.type) {
            "intro" -> "Skip intro"
            "recap" -> "Skip recap"
            else -> "Skip credits"
        }
        skipButton.visibility = View.VISIBLE
        if (!controls.visible) skipButton.requestFocus()
    }

    private fun updateUpNext() {
        val exo = player ?: return
        if (upNextSeconds <= 0 || exo.duration == C.TIME_UNSET || exo.duration < 120_000) return
        val duration = exo.duration
        val credits = segments.firstOrNull { it.type == "credits" }?.let { (it.start * 1000).toLong() }?.takeIf { it > duration / 2 && it < duration }
        val at = credits ?: (duration - 30_000)
        if (exo.currentPosition < at - 2000) {
            upNextDismissed = false
            hideUpNext()
        } else if (exo.currentPosition >= at && !upNextDismissed && upNextCard.visibility != View.VISIBLE) {
            showUpNext()
        }
    }

    private fun showUpNext() {
        if (controls.visible) controls.hide()
        upNextLeft = upNextSeconds
        findViewById<TextView>(R.id.up_next_title).text = nextTitle
        findViewById<TextView>(R.id.up_next_count).text = upNextLeft.toString()
        upNextCard.visibility = View.VISIBLE
        findViewById<View>(R.id.up_next_play).requestFocus()
        handler.postDelayed(upNextCount, 1000)
        if (nextArt.startsWith("https://")) kotlin.concurrent.thread(name = "dxbi-up-next-art") {
            val art = try { java.net.URL(nextArt).openStream().use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            if (art != null) runOnUiThread { findViewById<ImageView>(R.id.up_next_art).setImageBitmap(art) }
        }
    }

    private fun hideUpNext() {
        if (upNextCard.visibility != View.VISIBLE) return
        upNextCard.visibility = View.GONE
        handler.removeCallbacks(upNextCount)
        playerView.requestFocus()
    }

    /** Moves the subtitles by [byMs] and returns where they are now */
    private fun shiftSubtitles(byMs: Long): Long {
        if (byMs != 0L) {
            subtitleDelayMs += byMs
            // lines already passed only come back from a seek; once, after the last press
            handler.removeCallbacks(refreshSubtitles)
            handler.postDelayed(refreshSubtitles, 600)
        }
        return subtitleDelayMs
    }

    fun refit() = fitControls()

    private fun fitControls() {
        val config = resources.configuration
        controls.fit(config.screenWidthDp, config.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controls.isInitialized) fitControls()
    }

    private fun doSkip(seg: Segment? = showing) {
        val exo = player ?: return
        val s = seg ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        exo.seekTo(segmentEnd(s, duration).coerceAtMost(duration))
        showing = null
        skipButton.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun seek(direction: Int): Unit = seek(direction, SEEK_STEP_MS)

    private fun seek(direction: Int, stepMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET }
        val target = (exo.currentPosition + direction * stepMs).coerceAtLeast(0L).let { if (duration != null) it.coerceAtMost(duration) else it }
        exo.seekTo(target)
        // No pop-up: the controls come up with the timeline, which shows where it went
        if (::controls.isInitialized) controls.scrub()
    }

    private fun report() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (extras?.keep(exo.currentPosition, duration, ended = false) != true) PlaybackBus.listener?.onProgress(exo.currentPosition, duration, !exo.isPlaying)
    }

    /** Closes the player and says how it went ([positionMs]/[durationMs]: where another app left it, instead of this one) */
    private fun finishWith(ended: Boolean = false, error: String? = null, next: Boolean = false, positionMs: Long? = null, durationMs: Long? = null) {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.release()
        val exo = player
        val duration = durationMs?.takeIf { it > 0 } ?: exo?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (ended) duration else positionMs ?: exo?.currentPosition ?: 0L
        if (extras?.keep(position, duration, ended) != true) PlaybackBus.closed(position, duration, ended, error, next)
        if (torrent) TorrentEngine.close()
        finish()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app (Home button) pauses, unless it carries on in the phone app's small window;
        // the progress so far is kept
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) player?.pause()
        report()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        extras?.leaving()
    }

    /** The phone app's small window (dx.bi TV never opens one): just the picture in it, a little bigger subtitles */
    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) {
            if (::controls.isInitialized) controls.hide()
            skipButton.visibility = View.GONE
            seekFlash.visibility = View.GONE
            if (::upNextCard.isInitialized) upNextCard.visibility = View.GONE
            playerView.subtitleView?.setFractionalTextSize(subtitleSize * 1.6f)
        } else {
            playerView.subtitleView?.setFractionalTextSize(subtitleSize)
            // The small window was closed (not opened back up): the video's over
            if (lifecycle.currentState == Lifecycle.State.CREATED) finishWith()
        }
    }

    /** What the phone app's part of the player can ask of it */
    private val host = object : PlayerHost {
        override val activity get() = this@PlayerActivity
        override val playerView get() = this@PlayerActivity.playerView
        override val seekFlash get() = this@PlayerActivity.seekFlash
        override fun seek(direction: Int) = this@PlayerActivity.seek(direction)
        override fun shiftSubtitles(byMs: Long) = this@PlayerActivity.shiftSubtitles(byMs)
        override fun leaveParty() { PlaybackBus.listener?.onPartyLeave() }
        override fun finishWith(ended: Boolean, next: Boolean, positionMs: Long?, durationMs: Long?) =
            this@PlayerActivity.finishWith(ended = ended, next = next, positionMs = positionMs, durationMs = durationMs)

        override fun flash(text: String, forMs: Long) {
            handler.removeCallbacks(hideFlash)
            if (text.isEmpty()) {
                seekFlash.visibility = View.GONE
                return
            }
            seekFlash.text = text
            seekFlash.visibility = View.VISIBLE
            handler.postDelayed(hideFlash, forMs)
        }
    }

    /**
     * Media3 draws subtitles inside the picture's frame, so zooming in (or Fill, or removing black
     * bars) scaled them with the picture and pushed them off the screen. They're drawn over the
     * player instead, over the part of the picture that's on screen: under the video as before,
     * and never past the screen's edges whatever the zoom. Re-measured only when the frame moves.
     */
    private fun subtitlesOnScreen() {
        val subs = playerView.subtitleView ?: return
        val overlay = playerView.overlayFrameLayout ?: return
        val frame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        subs.setFractionalTextSize(subtitleSize)
        subs.setBottomPaddingFraction(0.05f)
        subs.setApplyEmbeddedFontSizes(false)
        if (subs.parent !== overlay) {
            (subs.parent as? android.view.ViewGroup)?.removeView(subs)
            overlay.addView(subs, android.widget.FrameLayout.LayoutParams(0, 0))
        }
        var last = android.graphics.Rect()
        playerView.viewTreeObserver.addOnPreDrawListener {
            // the frame as drawn (scaled about its centre, then moved), within the player
            val cx = frame.left + frame.width / 2f + frame.translationX
            val cy = frame.top + frame.height / 2f + frame.translationY
            val hw = frame.width * frame.scaleX / 2f
            val hh = frame.height * frame.scaleY / 2f
            val shown = android.graphics.Rect(
                maxOf(0, (cx - hw).toInt()), maxOf(0, (cy - hh).toInt()),
                minOf(playerView.width, (cx + hw).toInt()), minOf(playerView.height, (cy + hh).toInt()),
            )
            if (shown.width() <= 0 || shown.height() <= 0 || shown == last) return@addOnPreDrawListener true
            last = shown
            // across the picture's width, down to the bottom of the screen (into a film's black bar,
            // as Netflix does), so lines sit low and the same size whatever the picture's shape
            subs.layoutParams = (subs.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = shown.width()
                height = playerView.height
                leftMargin = shown.left
                topMargin = 0
            }
            false // drawn on the next frame, at the new size
        }
    }

    override fun onDestroy() {
        if (!closed) finishWith()
        handler.removeCallbacksAndMessages(null)
        if (PlaybackBus.player === this) PlaybackBus.player = null
        if (::controls.isInitialized) controls.release()
        if (::zoom.isInitialized) zoom.release()
        extras?.release()
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Stremio's subtitle settings (size, colours, outline, position) instead of the player's white
     * text on black boxes. Colours come as #RRGGBB or #RRGGBBAA.
     */
    private fun styleSubtitles(style: JSONObject) {
        val view = playerView.subtitleView ?: return
        fun color(key: String, fallback: Int): Int {
            val hex = style.optString(key).removePrefix("#")
            val v = hex.toLongOrNull(16) ?: return fallback
            return when (hex.length) {
                6 -> (0xFF000000 or v).toInt()
                8 -> ((v and 0xFF) shl 24 or (v ushr 8)).toInt()
                else -> fallback
            }
        }
        val opacity = (style.optDouble("opacity", 100.0) / 100).coerceIn(0.0, 1.0)
        val text = color("text", Color.WHITE).let { Color.argb((Color.alpha(it) * opacity).toInt(), Color.red(it), Color.green(it), Color.blue(it)) }
        val outline = color("outline", Color.BLACK)
        val typeface = if (style.optBoolean("bold")) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setStyle(CaptionStyleCompat(
            text,
            color("background", Color.TRANSPARENT),
            Color.TRANSPARENT,
            if (Color.alpha(outline) > 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
            outline,
            typeface,
        ))
        // the file's own colours and sizes would win over these otherwise
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        val size = style.optDouble("size", 100.0).coerceIn(50.0, 300.0) / 100
        subtitleSize = (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size).toFloat()
        view.setFractionalTextSize(subtitleSize)
        // Stremio's offset is how far up from the bottom of the picture the lines sit
        val offset = style.optDouble("offset", 5.0).coerceIn(0.0, 40.0) / 100
        view.setBottomPaddingFraction(0f)
        view.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val pad = ((bottom - top) * offset).toInt()
            if (v.paddingBottom != pad) v.post { v.setPadding(0, 0, 0, pad) }
        }
    }

    private fun subtitleMime(url: String): String {
        val path = Uri.parse(url).lastPathSegment?.lowercase() ?: ""
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /** Errors about the file itself (its format or codecs), not the connection: another player may manage */
    private fun cantHandle(error: PlaybackException): Boolean = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "the server refused it"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "the connection failed"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            // No software decoders are bundled: DTS and TrueHD audio (common in BluRay rips) only play
            // on TVs that decode them
            "this ${if (extras != null) "phone" else "TV"} can't decode its video or audio format (DTS and TrueHD audio often can't play: pick a release with AAC, AC3 or DD+ audio)"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "its file format isn't supported"
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val EXTRA_STREAM = "stream"
        private const val PROGRESS_INTERVAL_MS = 5000L
        private const val SEEK_STEP_MS = 10_000L
    }
}
