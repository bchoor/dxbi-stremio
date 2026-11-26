package com.notdxbi.dxbi.tv.marquee

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.notdxbi.dxbi.tv.PlaybackBus
import com.notdxbi.dxbi.tv.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MarqueeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.init(this)
        PlaybackBus.listener = Play
    }
}

/** Playing a source: in the built-in player (watch progress saved to Stremio), or in VLC */
object Play : PlaybackBus.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playing: Item? = null
    private var playingVideo: String = ""

    /** The account's library, kept for progress and the watchlist */
    var library: MutableMap<String, JSONObject> = mutableMapOf()

    suspend fun start(activity: Activity, item: Item, videoId: String, episodeLabel: String, source: Source, addons: List<Addon>, resumeMs: Long) {
        if (source.url.isEmpty()) { Toast.makeText(activity, "This source has no link to play", Toast.LENGTH_SHORT).show(); return }
        val subtitles = subtitles(addons, item.type, videoId)
        val stream = JSONObject()
            .put("url", source.url)
            .put("title", item.name)
            .put("subtitle", listOf(episodeLabel, listOf(source.resLabel, source.audio).filter { it.isNotEmpty() }.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString("  ·  "))
            .put("sourceKey", source.key)
            .put("accent", "#F5B544")
            .put("clock", true)
            .put("plain", true)
            .put("videoId", videoId)
            .put("subtitles", subtitles)
        source.headers?.let { stream.put("headers", it) }
        if (resumeMs > 30_000) stream.put("startMs", resumeMs)
        playing = item
        playingVideo = videoId
        val json = stream.toString()
        PlaybackBus.started(json)
        activity.startActivity(Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM, json))
    }

    /** Subtitles from the subtitle add-ons (OpenSubtitles), English and Hindi listed first */
    private suspend fun subtitles(addons: List<Addon>, type: String, videoId: String): JSONArray {
        val out = JSONArray()
        val found = withTimeoutOrNull(5000) {
            addons.filter { it.supports("subtitles", type, videoId) }.map { a ->
                scope.async(Dispatchers.IO) {
                    try { Api.get(a.url("subtitles", type, videoId), 5000).optJSONArray("subtitles").objects().map { a to it } } catch (_: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        } ?: emptyList()
        val first = listOf("eng", "en", "hin", "hi")
        found.sortedBy { (_, s) -> first.indexOf(s.optString("lang").lowercase()).let { if (it < 0) 99 else it } }
            .take(40)
            .forEach { (a, s) ->
                val lang = s.optString("lang")
                out.put(JSONObject().put("url", s.optString("url")).put("lang", lang).put("label", "${language(lang)} · ${a.name}").put("source", "addon"))
            }
        return out
    }

    fun language(code: String): String = try {
        Locale.forLanguageTag(code).displayLanguage.ifEmpty { code }
    } catch (_: Exception) { code }

    fun inVlc(activity: Activity, source: Source, title: String) {
        if (source.url.isEmpty()) return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(source.url), "video/*").putExtra("title", title)
        try {
            activity.startActivity(Intent(intent).setPackage("org.videolan.vlc"))
        } catch (_: ActivityNotFoundException) {
            try { activity.startActivity(Intent.createChooser(intent, "Play with")) }
            catch (_: ActivityNotFoundException) { Toast.makeText(activity, "Install VLC to play sources there", Toast.LENGTH_LONG).show() }
        }
    }

    fun trailer(activity: Activity, ytId: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$ytId"))) }
        catch (_: ActivityNotFoundException) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$ytId"))) }
            catch (_: ActivityNotFoundException) { Toast.makeText(activity, "No app here plays YouTube trailers", Toast.LENGTH_LONG).show() }
        }
    }

    fun inWatchlist(id: String): Boolean = library[id]?.let { !it.optBoolean("removed") && !it.optBoolean("temp") } == true

    /** Adds to or takes off the Stremio library (which Trakt follows, where it's connected) */
    fun setWatchlist(item: Item, on: Boolean, done: (Boolean) -> Unit) = scope.launch {
        val entry = Api.libraryItem(library[item.id], item).put("removed", !on).put("temp", false)
        try {
            Api.libraryPut(entry)
            library[item.id] = entry
            done(true)
        } catch (_: Exception) { done(false) }
    }

    // ---- What the player reports ----
    override fun onProgress(positionMs: Long, durationMs: Long, paused: Boolean) = Unit
    override fun onPartyTick(positionMs: Long, durationMs: Long, paused: Boolean, buffering: Boolean, rate: Float) = Unit
    override fun onPartyLeave() = Unit

    override fun onClosed() {
        val close = PlaybackBus.takePendingClose()?.let { JSONObject(it) } ?: return
        val item = playing ?: return
        val time = close.optLong("timeMs")
        val duration = close.optLong("durationMs")
        if (duration <= 0 || time < 60_000 && !close.optBoolean("ended")) return
        val done = close.optBoolean("ended") || time > duration * 0.9
        val entry = Api.libraryItem(library[item.id], item).put("removed", false)
        val state = entry.getJSONObject("state")
        state.put("lastWatched", Api.now())
            .put("timeOffset", if (done) 0 else time)
            .put("duration", duration)
            .put("video_id", playingVideo)
            .put("timeWatched", state.optLong("timeWatched") + time)
            .put("overallTimeWatched", state.optLong("overallTimeWatched") + time)
        if (done) state.put("timesWatched", state.optInt("timesWatched") + 1).put("flaggedWatched", if (item.type == "movie") 1 else state.optInt("flaggedWatched"))
        library[item.id] = entry
        scope.launch { try { Api.libraryPut(entry) } catch (_: Exception) { } }
    }
}
