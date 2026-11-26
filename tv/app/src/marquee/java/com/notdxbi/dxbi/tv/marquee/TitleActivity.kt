package com.notdxbi.dxbi.tv.marquee

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.notdxbi.dxbi.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A title: what it is on the left, with Play, Trailer and Watchlist; its sources, best first, on the right */
class TitleActivity : AppCompatActivity() {
    private lateinit var item: Item
    private var videoId = ""
    private var episodeLabel = ""
    private var autoplay = false
    private val sources = mutableListOf<Source>()
    private var filter = "All"
    private var loadJob: Job? = null
    private var episodes: List<JSONObject> = emptyList()
    private var season = 1

    private lateinit var backdrop: ImageView
    private lateinit var title: TextView
    private lateinit var metaLine: TextView
    private lateinit var desc: TextView
    private lateinit var playButton: LinearLayout
    private lateinit var trailerButton: LinearLayout
    private lateinit var watchButton: LinearLayout
    private lateinit var episodesBox: LinearLayout
    private lateinit var seasonsRow: LinearLayout
    private lateinit var episodesList: RecyclerView
    private lateinit var sourcesStatus: TextView
    private lateinit var filterRow: LinearLayout
    private val adapter = SourcesAdapter({ play(it) }, { Play.inVlc(this, it, playTitle()) })
    private var trailerYt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val x = intent
        item = Item(x.getStringExtra("id").orEmpty(), x.getStringExtra("type").orEmpty(), x.getStringExtra("name").orEmpty(),
            x.getStringExtra("poster").orEmpty(), x.getStringExtra("background").orEmpty(), x.getStringExtra("description").orEmpty(),
            x.getStringExtra("year").orEmpty(), videoId = x.getStringExtra("videoId").orEmpty())
        autoplay = x.getBooleanExtra("autoplay", false)
        build()

        title.text = item.name
        desc.text = item.description
        backdrop.load(backdropFor(item))
        updateWatchButton()
        playButton.requestFocus()

        lifecycleScope.launch {
            Session.ensureAddons()
            if (item.type != "series") { videoId = item.id; loadSources() }
            else if (item.videoId.isNotEmpty()) { videoId = item.videoId; episodeLabel = labelFor(item.videoId, null); loadSources() }
            val meta = Session.meta(item.type, item.id)
            if (meta != null) fill(meta)
            else if (item.type == "series" && videoId.isEmpty()) sourcesStatus.text = "Couldn't load the episodes"
        }
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.6f }
        root.addView(backdrop, match())
        root.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF20B0C0F.toInt(), 0x990B0C0F.toInt())) }, match())

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(dp(40), dp(24), dp(24), dp(20)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        left.addView(iconButton(R.drawable.ic_mq_back, "Back").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        title = label("", 32f, bold = true, lines = 2)
        left.addView(title, LinearLayout.LayoutParams(dp(500), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        metaLine = label("", 13f, Ui.MUTED)
        left.addView(metaLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        desc = label("", 14f, lines = 4).apply { setLineSpacing(0f, 1.15f) }
        left.addView(desc, LinearLayout.LayoutParams(dp(500), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val gap = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) } }
        playButton = pillButton(R.drawable.ic_mq_play, "Play", primary = true, sub = "Finding sources…").apply { setOnClickListener { sources.firstOrNull()?.let { play(it) } } }
        trailerButton = pillButton(R.drawable.ic_mq_trailer, "Trailer").apply { visibility = View.GONE; setOnClickListener { trailerYt?.let { Play.trailer(this@TitleActivity, it) } } }
        watchButton = pillButton(R.drawable.ic_mq_bookmark, "Watchlist").apply { setOnClickListener { toggleWatchlist() } }
        buttons.addView(playButton, gap()); buttons.addView(trailerButton, gap()); buttons.addView(watchButton, gap())
        left.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        // episodes (series only): season chips, then the episodes of that season
        episodesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; clipChildren = false; clipToPadding = false }
        seasonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(6), dp(2), dp(6)) }
        episodesBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(seasonsRow) })
        episodesList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(dp(12), dp(8), dp(40), dp(8))
        }
        episodesBox.addView(episodesList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(128)))
        left.addView(episodesBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        body.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(20) })

        // sources, on the right
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xE616181D.toInt(), 12, this@TitleActivity)
            setPadding(dp(12), dp(14), dp(12), dp(8))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(4), 0, dp(4), 0) }
        head.addView(label("Sources", 18f, bold = true))
        sourcesStatus = label("", 11f, Ui.MUTED)
        head.addView(sourcesStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        right.addView(head)
        filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(10), dp(2), dp(8)) }
        for (f in listOf("All", "4K", "1080p", "DV", "Instant")) {
            filterRow.addView(chip(f).apply {
                isSelected = f == filter
                setOnClickListener { filter = f; for (i in 0 until filterRow.childCount) filterRow.getChildAt(i).isSelected = filterRow.getChildAt(i) == this; render() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        right.addView(filterRow)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@TitleActivity.adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(40))
        }
        right.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(right, LinearLayout.LayoutParams(dp(400), LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(body, match())
        setContentView(root)
    }

    private fun fill(meta: JSONObject) {
        val name = meta.optString("name").ifEmpty { item.name }
        val poster = meta.optString("poster").ifEmpty { item.poster }
        item = item.copy(name = name, poster = poster, background = meta.optString("background").ifEmpty { item.background },
            description = meta.optString("description").ifEmpty { item.description })
        title.text = name
        desc.text = item.description
        metaLine.text = listOf(
            meta.optString("releaseInfo", meta.optString("year")),
            meta.optString("runtime"),
            meta.optJSONArray("genres").strings().take(3).joinToString(", "),
            meta.optString("imdbRating").takeIf { it.isNotEmpty() && it != "null" }?.let { "IMDb $it" }.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (item.background.isNotEmpty()) backdrop.load(item.background)
        trailerYt = Session.trailerId(meta)
        trailerButton.visibility = if (trailerYt != null) View.VISIBLE else View.GONE

        if (item.type == "series") {
            episodes = meta.optJSONArray("videos").objects()
                .filter { it.optInt("season") > 0 }
                .sortedWith(compareBy<JSONObject>({ it.optInt("season") }, { it.optInt("episode") }))
            if (episodes.isEmpty()) return
            // where you left off, else the first episode
            val last = Play.library[item.id]?.optJSONObject("state")?.optString("video_id")?.takeIf { it.isNotEmpty() && it != "null" }
            val start = episodes.firstOrNull { it.optString("id") == videoId }
                ?: episodes.firstOrNull { it.optString("id") == last }
                ?: episodes.first()
            episodesBox.visibility = View.VISIBLE
            val seasons = episodes.map { it.optInt("season") }.distinct()
            seasonsRow.removeAllViews()
            for (s in seasons) seasonsRow.addView(chip("Season $s").apply { tag = s; setOnClickListener { showSeason(s) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            showSeason(start.optInt("season"))
            if (videoId != start.optString("id")) selectEpisode(start)
            else { episodeLabel = labelFor(videoId, start); (episodesList.adapter as? EpisodesAdapter)?.chosen = videoId; episodesList.adapter?.notifyDataSetChanged() }
        }
    }

    private fun showSeason(s: Int) {
        season = s
        for (i in 0 until seasonsRow.childCount) seasonsRow.getChildAt(i).let { it.isSelected = it.tag == s }
        val list = episodes.filter { it.optInt("season") == s }
        episodesList.adapter = EpisodesAdapter(list, videoId) { selectEpisode(it) }
        val at = list.indexOfFirst { it.optString("id") == videoId }
        if (at > 0) episodesList.scrollToPosition(at)
    }

    private fun selectEpisode(ep: JSONObject) {
        videoId = ep.optString("id")
        episodeLabel = labelFor(videoId, ep)
        (episodesList.adapter as? EpisodesAdapter)?.let { it.chosen = videoId; it.notifyDataSetChanged() }
        loadSources()
    }

    private fun labelFor(id: String, ep: JSONObject?): String {
        val parts = id.split(':')
        val se = if (parts.size >= 3) "S${parts[parts.size - 2]} E${parts.last()}" else ""
        val name = if (ep == null) "" else ep.optString("name", ep.optString("title"))
        return listOf(se, name).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Every add-on with streams is asked at once; the list fills in as each one answers */
    private fun loadSources() {
        loadJob?.cancel()
        sources.clear()
        render()
        val id = videoId
        val askers = Session.addons.filter { it.supports("stream", item.type, id) }
        if (askers.isEmpty()) { sourcesStatus.text = "None of your add-ons has sources for this"; return }
        var answered = 0
        sourcesStatus.text = "Asking ${askers.size} add-ons…"
        setPill(playButton, null, playWord(), "Finding sources…")
        loadJob = lifecycleScope.launch {
            val jobs = askers.map { a ->
                launch {
                    val got = withTimeoutOrNull(30_000) {
                        try { Api.get(a.url("stream", item.type, id)).optJSONArray("streams").objects().map { Source(a, it) } } catch (_: Exception) { emptyList() }
                    } ?: emptyList()
                    answered++
                    merge(got.filter { it.url.isNotEmpty() })
                    sourcesStatus.text = "${sources.size} · $answered of ${askers.size} add-ons answered"
                }
            }
            if (autoplay) {
                withTimeoutOrNull(12_000) { jobs.joinAll() }
                autoplay = false
                sources.firstOrNull()?.let { play(it) }
            }
            jobs.joinAll()
            if (sources.isEmpty()) { sourcesStatus.text = "No sources found"; setPill(playButton, null, playWord(), "No sources") }
        }
    }

    private fun merge(got: List<Source>) {
        val best = LinkedHashMap<String, Source>()
        for (s in sources + got) { val old = best[s.key]; if (old == null || s.score > old.score) best[s.key] = s }
        sources.clear()
        sources.addAll(best.values.sortedByDescending { it.score })
        render()
    }

    private fun render() {
        adapter.items = sources.filter {
            when (filter) {
                "4K" -> it.res >= 2160
                "1080p" -> it.res in 1080..2159
                "DV" -> it.dv
                "Instant" -> it.instant
                else -> true
            }
        }
        adapter.notifyDataSetChanged()
        sources.firstOrNull()?.let { best -> setPill(playButton, null, playWord(), best.badges().take(3).joinToString(" · ") { it.first }) }
    }

    private fun resumeMs(): Long {
        val state = Play.library[item.id]?.optJSONObject("state") ?: return 0
        if (item.type == "series" && state.optString("video_id") != videoId) return 0
        return state.optLong("timeOffset")
    }

    private fun playWord() = if (resumeMs() > 30_000) "Resume" else "Play"

    private fun playTitle() = if (episodeLabel.isEmpty()) item.name else "${item.name} · $episodeLabel"

    private fun play(source: Source) {
        lifecycleScope.launch { Play.start(this@TitleActivity, item, videoId, episodeLabel, source, Session.addons, resumeMs()) }
    }

    override fun onResume() {
        super.onResume()
        if (::playButton.isInitialized) { updateWatchButton(); sources.firstOrNull()?.let { render() } ?: setPill(playButton, null, playWord()) }
    }

    private fun updateWatchButton() {
        val on = Play.inWatchlist(item.id)
        setPill(watchButton, if (on) R.drawable.ic_mq_bookmark_added else R.drawable.ic_mq_bookmark, if (on) "In watchlist" else "Watchlist")
    }

    private fun toggleWatchlist() {
        val on = !Play.inWatchlist(item.id)
        Play.setWatchlist(item, on) { ok ->
            if (ok) updateWatchButton() else Toast.makeText(this, "Couldn't update your watchlist", Toast.LENGTH_SHORT).show()
        }
    }
}

/** The sources list: what each one is (badges), its name, where it's from, and a VLC button */
class SourcesAdapter(private val onPlay: (Source) -> Unit, private val onVlc: (Source) -> Unit) : RecyclerView.Adapter<SourcesAdapter.Holder>() {
    var items: List<Source> = emptyList()

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = items[position].key.hashCode().toLong()
    override fun getItemCount() = items.size

    class Holder(view: View, val main: LinearLayout, val badges: LinearLayout, val name: TextView, val from: TextView, val vlc: ImageView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(4) }
        }
        val main = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), round(Ui.SURFACE_2, 8, ctx, 2, Color.WHITE))
                addState(intArrayOf(), round(Color.TRANSPARENT, 8, ctx))
            }
        }
        val badges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        main.addView(badges)
        val name = ctx.label("", 12f, lines = 2)
        main.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(5) })
        val from = ctx.label("", 10f, Ui.MUTED)
        main.addView(from, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
        row.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val vlc = ctx.iconButton(R.drawable.ic_mq_vlc, "Play in VLC", 34)
        row.addView(vlc, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginStart = ctx.dp(6) })
        return Holder(row, main, badges, name, from, vlc)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = items[position]
        val ctx = holder.itemView.context
        holder.badges.removeAllViews()
        for ((text, kind) in s.badges()) holder.badges.addView(ctx.badge(text, kind), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(4) })
        if (s.size.isNotEmpty()) holder.badges.addView(ctx.label(s.size, 11f, Ui.MUTED), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(4) })
        holder.name.text = s.fileName.ifEmpty { s.name }
        holder.from.text = s.from
        holder.main.contentDescription = "${s.resLabel} ${s.audio} ${s.from}"
        holder.main.setOnClickListener { onPlay(s) }
        holder.vlc.setOnClickListener { onVlc(s) }
    }
}

/** A season's episodes as small pictures; the chosen one has a gold ring */
class EpisodesAdapter(private val items: List<JSONObject>, var chosen: String, private val onPick: (JSONObject) -> Unit) : RecyclerView.Adapter<EpisodesAdapter.Holder>() {
    class Holder(val card: FrameLayout, val image: ImageView, val words: TextView) : RecyclerView.ViewHolder(card)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val card = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ctx.dp(176), ctx.dp(99)).apply { marginEnd = ctx.dp(10) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            focusGrow(1.06f)
        }
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        card.addView(View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000)) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ctx.dp(50), Gravity.BOTTOM))
        val words = ctx.label("", 11f, bold = true, lines = 2).apply { setPadding(ctx.dp(8), 0, ctx.dp(8), ctx.dp(6)) }
        card.addView(words, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return Holder(card, image, words)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ep = items[position]
        val ctx = holder.card.context
        val on = ep.optString("id") == chosen
        holder.card.background = round(Ui.SURFACE_2, 6, ctx)
        holder.card.foreground = if (on) round(Color.TRANSPARENT, 6, ctx, 3, Ui.ACCENT) else icon(ctx, R.drawable.mq_focus)
        holder.words.text = "E${ep.optInt("episode")}  ${ep.optString("name", ep.optString("title"))}"
        holder.card.contentDescription = holder.words.text
        holder.image.load(ep.optString("thumbnail").ifEmpty { null })
        holder.card.setOnClickListener { onPick(ep) }
    }
}
