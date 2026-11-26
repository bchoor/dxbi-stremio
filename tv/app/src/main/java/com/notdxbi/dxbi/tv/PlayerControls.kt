package com.notdxbi.dxbi.tv

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** What the player needs from its controls: the remote's ([PlayerControls]) or a phone's ([PhoneControls]) */
interface Controls {
    val visible: Boolean
    val panelOpen: Boolean
    fun show()
    /** Shows the timeline with the remote on it, to move along it */
    fun scrub() = show()
    fun hide()
    /** Pressing anything keeps them up a while longer */
    fun poke()
    fun release()
    /** Closes an open menu (true if one was open) */
    fun closePanel(): Boolean
    /** The remote inside an open menu (true if it took the key) */
    fun panelKey(code: Int): Boolean
    fun party(on: Boolean, code: String, people: List<String>)
    fun fit(widthDp: Int, upright: Boolean)
}

/**
 * dx.bi's player controls: the same layout as on a computer (back at the top, then the times and
 * seek bar, playback on the left, the title in the middle, options on the right), worked by remote.
 */
@OptIn(UnstableApi::class)
class PlayerControls(
    private val activity: PlayerActivity,
    private val playerView: PlayerView,
    private val exo: ExoPlayer,
    private val sound: SoundMode,
    private val zoom: PictureZoom,
    private val subtitleDelay: (Long) -> Long,
    accent: Int,
    private val clock: View?,
    hasNext: Boolean,
    private val seek: (Int) -> Unit,
    private val leaveParty: () -> Unit,
    private val leave: (Boolean) -> Unit,
) : Controls {
    private val root: View = activity.findViewById(R.id.controls)
    private val panel: View = activity.findViewById(R.id.panel)
    private val panelTitle: TextView = activity.findViewById(R.id.panel_title)
    private val panelRows: LinearLayout = activity.findViewById(R.id.panel_rows)
    private val line: SeekLine = activity.findViewById(R.id.seek_line)
    private val play: ImageButton = activity.findViewById(R.id.ctl_play)
    private val timeNow: TextView = activity.findViewById(R.id.time_now)
    private val timeLeft: TextView = activity.findViewById(R.id.time_left)
    private val handler = Handler(Looper.getMainLooper())
    private var opener: View? = null
    private var lastFocus: View = play
    private var stats: TextView? = null
    // what the stats panel reports that the player only tells as it happens
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var audioOut = ""
    private var bandwidth = 0L
    private var stalls = 0
    private var wasReady = false
    private val opened = android.os.SystemClock.elapsedRealtime()
    private var firstFrameMs = -1L

    private val hide = Runnable { if (!panelOpen) hide() }
    private val tick = object : Runnable {
        override fun run() {
            paint()
            handler.postDelayed(this, 500)
        }
    }

    private val buttons = listOf(R.id.ctl_play, R.id.ctl_restart, R.id.ctl_rewind, R.id.ctl_forward, R.id.ctl_next, R.id.ctl_stats, R.id.ctl_picture, R.id.ctl_speed, R.id.ctl_subtitles, R.id.ctl_audio, R.id.ctl_external, R.id.ctl_party)
        .map { activity.findViewById<View>(it) }

    override val visible get() = root.visibility == View.VISIBLE
    override val panelOpen get() = panel.visibility == View.VISIBLE

    init {
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
            override fun onAudioDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
            override fun onAudioTrackInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                val enc = when (audioTrackConfig.encoding) {
                    C.ENCODING_AC3 -> "Dolby Digital (passthrough)"
                    C.ENCODING_E_AC3 -> "Dolby Digital+ (passthrough)"
                    C.ENCODING_E_AC3_JOC -> "Dolby Digital+ Atmos (passthrough)"
                    C.ENCODING_DTS -> "DTS (passthrough)"
                    C.ENCODING_DTS_HD -> "DTS-HD (passthrough)"
                    C.ENCODING_DOLBY_TRUEHD -> "TrueHD (passthrough)"
                    C.ENCODING_PCM_16BIT -> "PCM 16-bit (decoded)"
                    C.ENCODING_PCM_FLOAT -> "PCM float (decoded)"
                    else -> "encoding ${audioTrackConfig.encoding}"
                }
                val ch = Integer.bitCount(audioTrackConfig.channelConfig)
                audioOut = "$enc · ${channelName(ch)} · ${audioTrackConfig.sampleRate / 1000} kHz" + (if (audioTrackConfig.tunneling) " · tunneled" else "")
            }
            override fun onBandwidthEstimate(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) { bandwidth = bitrateEstimate }
            override fun onRenderedFirstFrame(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (firstFrameMs < 0) firstFrameMs = android.os.SystemClock.elapsedRealtime() - opened
            }
            override fun onPlaybackStateChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) wasReady = true
                else if (state == androidx.media3.common.Player.STATE_BUFFERING && wasReady) stalls++
            }
        })
        line.accent = accent
        click(R.id.ctl_back) { leave(false) }
        click(R.id.ctl_play) { exo.playWhenReady = !exo.playWhenReady; paint() }
        click(R.id.ctl_restart) { exo.seekTo(0L); exo.play(); paint() }
        click(R.id.ctl_rewind) { seek(-1); paint() }
        click(R.id.ctl_forward) { seek(+1); paint() }
        click(R.id.ctl_next) { leave(true) }
        activity.findViewById<View>(R.id.ctl_next).visibility = if (hasNext) View.VISIBLE else View.GONE
        click(R.id.ctl_stats) { showStats(it) }
        click(R.id.ctl_picture) { showPicture(it) }
        click(R.id.ctl_speed) { showSpeed(it) }
        click(R.id.ctl_subtitles) { showTracks(it, C.TRACK_TYPE_TEXT, "Subtitles") }
        click(R.id.ctl_audio) { showTracks(it, C.TRACK_TYPE_AUDIO, "Audio") }
        click(R.id.ctl_party) { showParty(it) }
        click(R.id.ctl_external) { showPlayers(it) }
        // Phones: a tap shows or hides them, and the bar can be touched
        playerView.setOnClickListener { if (visible) hide() else show() }
        root.setOnClickListener { hide() }
        line.setOnTouchListener { view, event ->
            val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return@setOnTouchListener false
            exo.seekTo((duration * (event.x / view.width).coerceIn(0f, 1f)).toLong())
            paint()
            poke()
            true
        }
        // Left/Right on the timeline: a press moves 10 s; held, it speeds up (5 s, 15 s, 30 s, then a minute
        // a step), and the video jumps there when the key is let go
        line.setOnKeyListener { _, code, event ->
            val direction = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> +1
                else -> return@setOnKeyListener false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    val held = event.eventTime - event.downTime
                    val step = when {
                        event.repeatCount == 0 -> 10_000L
                        held < 1_500 -> 5_000L
                        held < 3_000 -> 15_000L
                        held < 5_000 -> 30_000L
                        else -> 60_000L
                    }
                    val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    var target = ((scrubTo ?: exo.currentPosition) + direction * step).coerceAtLeast(0L)
                    if (duration != null) target = target.coerceAtMost((duration - 1_000L).coerceAtLeast(0L))
                    scrubTo = target
                }
                KeyEvent.ACTION_UP -> {
                    scrubTo?.let { exo.seekTo(it) }
                    scrubTo = null
                }
            }
            paint()
            poke()
            true
        }
    }

    /** Upright on a phone: the title moves above the buttons, and they share the width */
    override fun fit(widthDp: Int, upright: Boolean) {
        val shown = buttons.count { it.visibility != View.GONE }
        val size = dp(minOf(52, (widthDp - 16) / shown - 2))
        for (button in buttons) {
            button.layoutParams = (button.layoutParams as ViewGroup.MarginLayoutParams).apply { width = size; height = size; marginEnd = dp(if (upright) 2 else 4) }
            button.setPadding(size / 4, size / 4, size / 4, size / 4)
        }
        val row = activity.findViewById<ViewGroup>(R.id.controls_row)
        val titles = activity.findViewById<View>(R.id.controls_title)
        row.setPadding(0, if (upright) dp(46) else 0, 0, 0)
        titles.layoutParams = (titles.layoutParams as android.widget.FrameLayout.LayoutParams).apply { gravity = if (upright) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER; topMargin = if (upright) dp(-44) else 0 }
        val side = dp(if (upright) 12 else 56)
        // (the seek bar gets the room when it's narrow)
        for (id in listOf(R.id.time_now, R.id.time_left)) activity.findViewById<TextView>(id).minWidth = dp(if (upright) 44 else 72)
        activity.findViewById<View>(R.id.controls_bottom).setPadding(side, 0, side, dp(26))
        panel.layoutParams = (panel.layoutParams as android.widget.FrameLayout.LayoutParams).apply { width = minOf(dp(340), dp(widthDp - 24)); marginEnd = if (upright) dp(12) else dp(56); bottomMargin = dp(if (upright) 170 else 104) }
    }

    private fun click(id: Int, action: (View) -> Unit) {
        activity.findViewById<View>(id).apply {
            setOnClickListener { action(it) }
            setOnFocusChangeListener { view, has ->
                if (has) lastFocus = view
                // (each icon's name shows while it has focus)
                activity.findViewById<TextView>(R.id.ctl_hint)?.takeIf { it.tag != "plain" }?.apply {
                    text = if (has) view.contentDescription else ""
                    visibility = if (has && !view.contentDescription.isNullOrEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** Where the timeline is being moved to (the video jumps there when the key is let go) */
    private var scrubTo: Long? = null

    override fun scrub() {
        show()
        line.isFocusable = true
        line.requestFocus()
    }

    override fun show() {
        if (!visible) {
            root.visibility = View.VISIBLE
            if (clock != null) clock.visibility = View.VISIBLE
            // Subtitles sit above the controls while they're up
            playerView.subtitleView?.translationY = -dp(if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 0 else 110).toFloat()
            handler.post(tick)
            (if (lastFocus.isShown) lastFocus else play).requestFocus()
        }
        poke()
    }

    /** Pressing anything keeps them up a while longer */
    override fun poke() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, HIDE_AFTER_MS)
    }

    override fun hide() {
        closePanel()
        root.visibility = View.GONE
        clock?.visibility = View.GONE
        playerView.subtitleView?.translationY = 0f
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hide)
        playerView.requestFocus()
    }

    override fun release() = handler.removeCallbacksAndMessages(null)

    fun paint() {
        play.setImageResource(if (exo.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val at = scrubTo ?: exo.currentPosition
        timeNow.text = time(at)
        timeLeft.text = if (duration > 0) "-" + time(duration - at) else "--:--"
        line.played = if (duration > 0) at.toFloat() / duration else 0f
        line.loaded = if (duration > 0) exo.bufferedPosition.toFloat() / duration else 0f
        stats?.let { it.text = statsText() }
    }

    private fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The remote stays inside an open menu: up and down move through it, sideways does nothing */
    override fun panelKey(code: Int): Boolean {
        if (!panelOpen) return false
        val rows = (0 until panelRows.childCount).map { panelRows.getChildAt(it) }.filter { it.isFocusable }
        val at = rows.indexOf(panelRows.findFocus())
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> rows.getOrNull(at - 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> rows.getOrNull(at + 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            else -> return false
        }
        return true
    }

    override fun closePanel(): Boolean {
        if (!panelOpen) return false
        panel.visibility = View.GONE
        stats = null
        panelRows.removeAllViews()
        opener?.requestFocus()
        poke()
        return true
    }

    private fun openPanel(from: View, title: String) {
        opener = from
        panelTitle.text = title
        panelRows.removeAllViews()
        panel.visibility = View.VISIBLE
    }

    private fun heading(text: String) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Color.argb(153, 255, 255, 255))
        setPadding(dp(12), dp(12), dp(12), dp(4))
        panelRows.addView(this)
    }

    /** Track rows: picking one switches straight away and the menu stays open, to try the next */
    private val pickRows = mutableListOf<TextView>()

    private fun row(text: String, on: Boolean, keepOpen: Boolean = false, pick: Boolean = false, action: () -> Unit): TextView = TextView(activity).apply {
        this.text = if (on) "✓  $text" else text
        if (pick) { contentDescription = text; pickRows.add(this) }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(42)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setBackgroundResource(R.drawable.panel_row)
        setTextColor(if (on) Color.WHITE else Color.argb(204, 255, 255, 255))
        isFocusable = true
        setOnFocusChangeListener { view, has ->
            (view as TextView).setTextColor(if (has) Color.BLACK else if (on) Color.WHITE else Color.argb(204, 255, 255, 255))
        }
        setOnClickListener {
            action()
            if (pick) for (r in pickRows) r.text = if (r === this) "✓  ${r.contentDescription}" else r.contentDescription
            else if (!keepOpen) closePanel()
            poke()
        }
        panelRows.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tag = if (on) "on" else null
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun focusFirst() {
        val rows = (0 until panelRows.childCount).map { panelRows.getChildAt(it) }.filter { it.isFocusable }
        (rows.firstOrNull { it.tag == "on" } ?: rows.firstOrNull())?.requestFocus()
    }

    private var partyCode = ""
    private var partyPeople = emptyList<String>()

    /** The Party button shows while this viewer is in a watch party */
    override fun party(on: Boolean, code: String, people: List<String>) {
        partyCode = code
        partyPeople = people
        val button = activity.findViewById<View>(R.id.ctl_party)
        val shown = if (on) View.VISIBLE else View.GONE
        if (button.visibility != shown) {
            button.visibility = shown
            activity.refit()
        }
    }

    private fun showParty(from: View) {
        openPanel(from, "Watch party · $partyCode")
        heading(if (partyPeople.isEmpty()) "Waiting for friends" else "Watching with")
        for (name in partyPeople) row(name, false, keepOpen = true) {}
        row("Leave the party", false) { leaveParty() }
        focusFirst()
    }

    private fun showSpeed(from: View) {
        openPanel(from, "Playback speed")
        for (speed in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
            row(if (speed == 1f) "Normal" else "${speed}x", exo.playbackParameters.speed == speed) { exo.setPlaybackSpeed(speed) }
        }
        focusFirst()
    }

    private fun showPicture(from: View) {
        openPanel(from, "Picture & sound")
        heading("Picture")
        val modes = listOf(
            "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
            "Crop" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
        )
        for ((name, mode) in modes) row(name, playerView.resizeMode == mode) { playerView.resizeMode = mode }
        heading("Zoom")
        val zooms = listOfNotNull("off" to "Off", ("auto" to "Remove black bars").takeIf { zoom.canMeasure }, "1.1" to "110%", "1.25" to "125%", "1.33" to "133%")
        for ((value, name) in zooms) row(name, zoom.mode == value) { zoom.set(value) }
        heading("Sound")
        for (mode in SoundMode.MODES) row(mode.label, sound.mode === mode) { sound.mode = mode }
        focusFirst()
    }

    private fun showTracks(from: View, type: Int, title: String) {
        openPanel(from, title)
        pickRows.clear()
        val groups = exo.currentTracks.groups.filter { it.type == type }
        val anySelected = groups.any { it.isSelected }
        if (type == C.TRACK_TYPE_TEXT && anySelected) {
            heading("Timing")
            val shown = { ms: Long -> panelTitle.text = if (ms == 0L) "Subtitles" else "Subtitles · %+.2f s".format(ms / 1000f) }
            shown(subtitleDelay(0))
            row("Show 0.25 s earlier", false, keepOpen = true) { shown(subtitleDelay(-250)) }
            row("Show 0.25 s later", false, keepOpen = true) { shown(subtitleDelay(+250)) }
        }
        if (type == C.TRACK_TYPE_TEXT) {
            if (anySelected) heading("Track")
            row("Off", !anySelected, pick = true) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
            }
        }
        var number = 0
        for (group in groups) for (i in 0 until group.length) {
            val supported = group.isTrackSupported(i)
            // (a sound track that can't play stays listed, so it's clear why there's no sound)
            if (!supported && type != C.TRACK_TYPE_AUDIO) continue
            number++
            val format = group.getTrackFormat(i)
            val name = format.label ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotEmpty() } ?: "Track $number"
            val channels = if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) " · ${channelName(format.channelCount)}" else ""
            val kind = if (type == C.TRACK_TYPE_AUDIO) audioKind(format.sampleMimeType) else ""
            if (!supported) {
                row("$name$channels$kind · can't play here", false) {}
                continue
            }
            row(name + channels + kind, group.isTrackSelected(i), pick = true) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                if (type == C.TRACK_TYPE_AUDIO) AudioMemory.save(activity, activity.sourceKey, format)
            }
        }
        if (number == 0 && type == C.TRACK_TYPE_AUDIO) row("No other audio", false) {}
        focusFirst()
    }

    /** Hands the video, and where it is, to another player (VLC or whichever you pick) */
    /** The video, for another player: from where it is now (VLC and MX Player start from "position") */
    private fun elsewhereIntent(): android.content.Intent? {
        val stream = org.json.JSONObject(activity.intent.getStringExtra(PlayerActivity.EXTRA_STREAM) ?: "{}")
        val url = stream.optString("url")
        if (url.isEmpty()) return null
        return android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(url), "video/*")
            .putExtra("title", stream.optString("title"))
            .putExtra("position", exo.currentPosition)
            .putExtra("from_start", false)
    }

    /** The players on this TV that can take the video, in the same panel as subtitles and audio */
    private fun showPlayers(from: View) {
        val intent = elsewhereIntent() ?: return
        val pm = activity.packageManager
        val players = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        openPanel(from, "Play in another player")
        if (players.isEmpty()) row("No other video player is installed", false) {}
        for (p in players) {
            row(p.loadLabel(pm).toString(), false) {
                exo.playWhenReady = false
                paint()
                val go = elsewhereIntent() ?: return@row
                go.setClassName(p.activityInfo.packageName, p.activityInfo.name)
                try { activity.startActivity(go) }
                catch (_: Exception) { android.widget.Toast.makeText(activity, "That player couldn't open it", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        focusFirst()
    }

    private fun audioKind(mime: String?) = when (mime) {
        MimeTypes.AUDIO_AC3 -> " · Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> " · Dolby Digital+"
        MimeTypes.AUDIO_TRUEHD -> " · TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> " · DTS"
        else -> ""
    }

    private fun channelName(count: Int) = when (count) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count channels"
    }

    private fun showStats(from: View) {
        openPanel(from, "Stream stats")
        val text = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(12), 0, dp(12), dp(10))
            isFocusable = true
        }
        panelRows.addView(text)
        stats = text
        text.requestFocus()
        paint()
    }

    private fun statsText(): String {
        val video = exo.videoFormat
        val audio = exo.audioFormat
        fun codec(mime: String?) = when (mime) {
            null -> "—"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            else -> mime.substringAfter('/').removePrefix("x-").uppercase()
        }
        fun mbps(bits: Int) = "%.1f Mbps".format(bits / 1_000_000f)
        val lines = mutableListOf<String>()

        lines += "— VIDEO —"
        if (video != null) {
            lines += "${video.width} × ${video.height}" + (video.frameRate.takeIf { it > 0 }?.let { " · %.3f fps".format(it).replace(Regex("""\.?0+ fps"""), " fps") } ?: "")
            lines += codec(video.sampleMimeType) + (video.codecs?.let { "  ($it)" } ?: "")
            val c = video.colorInfo
            val picture = when {
                video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || video.codecs?.startsWith("dv") == true -> "Dolby Vision" + (video.codecs?.let { Regex("""dv\w{2}\.(\d+)""").find(it)?.groupValues?.get(1)?.let { p -> " profile ${p.toInt()}" } } ?: "")
                c?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                c?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
            val space = when (c?.colorSpace) { C.COLOR_SPACE_BT2020 -> "BT.2020"; C.COLOR_SPACE_BT709 -> "BT.709"; C.COLOR_SPACE_BT601 -> "BT.601"; else -> null }
            val depth = c?.lumaBitdepth?.takeIf { it > 0 }?.let { "$it-bit" }
            lines += listOfNotNull(picture, space, depth).joinToString(" · ")
            listOfNotNull(
                video.averageBitrate.takeIf { it > 0 }?.let { "avg ${mbps(it)}" },
                video.peakBitrate.takeIf { it > 0 }?.let { "peak ${mbps(it)}" },
                video.bitrate.takeIf { it > 0 && video.averageBitrate <= 0 }?.let { mbps(it) },
            ).takeIf { it.isNotEmpty() }?.let { lines += "Bitrate " + it.joinToString(" · ") }
        } else lines += "—"
        if (videoDecoder.isNotEmpty()) lines += "Decoder " + videoDecoder + (if (videoDecoder.startsWith("c2.android") || videoDecoder.startsWith("OMX.google")) " (software)" else " (hardware)")
        exo.videoDecoderCounters?.let { d ->
            d.ensureUpdated()
            lines += "Frames ${d.renderedOutputBufferCount} shown · ${d.droppedBufferCount} dropped · ${d.skippedOutputBufferCount} skipped"
        }

        lines += ""
        lines += "— AUDIO —"
        if (audio != null) {
            lines += codec(audio.sampleMimeType).let { if (audio.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC) "E-AC3 Atmos" else it } +
                (audio.channelCount.takeIf { it > 0 }?.let { " · " + channelName(it) } ?: "") +
                (audio.sampleRate.takeIf { it > 0 }?.let { " · ${it / 1000} kHz" } ?: "") +
                (audio.bitrate.takeIf { it > 0 }?.let { " · ${it / 1000} kbps" } ?: "")
            listOfNotNull(audio.language, audio.label).filter { it.isNotEmpty() && it != "und" }.takeIf { it.isNotEmpty() }?.let { lines += "Track " + it.joinToString(" · ") }
        } else lines += "—"
        if (audioDecoder.isNotEmpty()) lines += "Decoder $audioDecoder"
        if (audioOut.isNotEmpty()) lines += "To the TV  $audioOut"
        val audioTracks = exo.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
        val textTracks = exo.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
        lines += "$audioTracks audio tracks · $textTracks subtitle tracks"
        exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { g ->
            val f = (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) }
            if (f != null) lines += "Subtitles " + listOfNotNull(f.language, f.label, f.sampleMimeType?.substringAfter('/')).filter { it.isNotEmpty() }.joinToString(" · ")
        }

        lines += ""
        lines += "— NETWORK —"
        lines += "Buffered %.0f s ahead".format(exo.totalBufferedDuration / 1000f) + (if (bandwidth > 0) " · speed %.0f Mbps".format(bandwidth / 1_000_000f) else "")
        lines += "Stalls $stalls" + (if (firstFrameMs >= 0) " · started in %.1f s".format(firstFrameMs / 1000f) else "")
        val stream = org.json.JSONObject(activity.intent.getStringExtra(PlayerActivity.EXTRA_STREAM) ?: "{}")
        android.net.Uri.parse(stream.optString("url")).host?.let { lines += "From $it" }
        video?.containerMimeType?.let { lines += "Container " + it.substringAfter('/').removePrefix("x-") }

        lines += ""
        lines += "— DISPLAY —"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.let { d ->
                val m = d.mode
                lines += "${m.physicalWidth} × ${m.physicalHeight} · %.0f Hz".format(m.refreshRate)
                val hdr = d.hdrCapabilities?.supportedHdrTypes?.map {
                    when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "type $it" }
                }.orEmpty()
                if (hdr.isNotEmpty()) lines += "TV can show " + hdr.joinToString(", ")
            }
        }
        return lines.joinToString("\n")
    }

    private companion object {
        const val HIDE_AFTER_MS = 6000L
    }
}
