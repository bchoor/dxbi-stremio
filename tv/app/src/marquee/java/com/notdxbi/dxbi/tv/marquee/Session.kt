package com.notdxbi.dxbi.tv.marquee

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What every screen shares while the app runs */
object Session {
    var addons: List<Addon> = emptyList()

    /** The account's add-ons, fetched again when the app was restarted on a later screen */
    suspend fun ensureAddons(): List<Addon> {
        if (addons.isEmpty()) addons = try { Api.addons() } catch (_: Exception) { emptyList() }
        if (Play.library.isEmpty()) try { Play.library = Api.library().associateBy { it.optString("_id") }.toMutableMap() } catch (_: Exception) { }
        return addons
    }

    // ---- The year and IMDb rating under every poster and in the hero ----
    /** id to (year, rating), read once from the title's details when a catalog or the library left them out */
    private val captions = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val asking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val captionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val captionLimit = Semaphore(4)

    fun yearOf(item: Item) = item.year.ifEmpty { captions[item.id]?.first.orEmpty() }
    fun ratingOf(item: Item) = item.rating.ifEmpty { captions[item.id]?.second.orEmpty() }

    /** Calls [done] once the year and rating are known (at once when they already are) */
    fun caption(item: Item, done: () -> Unit) {
        if ((item.year.isNotEmpty() && item.rating.isNotEmpty()) || captions.containsKey(item.id) || item.id.isEmpty()) return
        if (!asking.add(item.id)) return
        captionScope.launch {
            val m = captionLimit.withPermit { withTimeoutOrNull(8_000) { withContext(Dispatchers.IO) { meta(item.type, item.id) } } }
            asking.remove(item.id)
            if (m == null) return@launch
            val full = Item.from(m)
            captions[item.id] = full.year to full.rating
            done()
        }
    }

    /** A title's details, from the first add-on that has them (Cinemeta) */
    suspend fun meta(type: String, id: String): JSONObject? {
        for (a in ensureAddons().filter { it.supports("meta", type, id) }) {
            try { Api.get(a.url("meta", type, id), 10_000).optJSONObject("meta")?.let { return it } } catch (_: Exception) { }
        }
        return null
    }

    fun trailerId(meta: JSONObject?): String? {
        meta ?: return null
        meta.optJSONArray("trailerStreams").objects().firstOrNull()?.optString("ytId")?.ifEmpty { null }?.let { return it }
        return meta.optJSONArray("trailers").objects().firstOrNull { it.optString("type", "Trailer") == "Trailer" }?.optString("source")?.ifEmpty { null }
    }

    fun open(activity: Activity, item: Item, autoplay: Boolean = false) {
        activity.startActivity(Intent(activity, TitleActivity::class.java)
            .putExtra("id", item.id).putExtra("type", item.type).putExtra("name", item.name)
            .putExtra("poster", item.poster).putExtra("background", item.background)
            .putExtra("description", item.description).putExtra("year", item.year)
            .putExtra("videoId", item.videoId).putExtra("autoplay", autoplay))
    }

    /** Library entries in progress, newest first */
    fun continueWatching(library: Collection<JSONObject>): List<Item> = library
        .filter { !it.optBoolean("removed") && (it.optJSONObject("state")?.optLong("timeOffset") ?: 0) > 0 }
        .sortedByDescending { it.optJSONObject("state")?.optString("lastWatched").orEmpty() }
        .take(20)
        .map { e ->
            val s = e.optJSONObject("state")!!
            val video = s.optString("video_id").takeIf { it != "null" }.orEmpty()
            val parts = video.split(':')
            val ep = if (e.optString("type") == "series" && parts.size >= 3) "S${parts[parts.size - 2]} E${parts.last()}" else ""
            val left = ((s.optLong("duration") - s.optLong("timeOffset")) / 60_000).coerceAtLeast(1)
            Item(e.optString("_id"), e.optString("type"), e.optString("name"), e.optString("poster"), "", "", "",
                progress = (s.optLong("timeOffset").toFloat() / s.optLong("duration").coerceAtLeast(1)).coerceIn(0f, 1f),
                videoId = video, subtitle = listOf(ep, "$left min left").filter { it.isNotEmpty() }.joinToString(" · "))
        }

    fun watchlist(library: Collection<JSONObject>): List<Item> = library
        .filter { !it.optBoolean("removed") && !it.optBoolean("temp") }
        .sortedByDescending { it.optString("_mtime") }
        .take(40)
        .map { e -> Item(e.optString("_id"), e.optString("type"), e.optString("name"), e.optString("poster"), "", "", "") }
}

/** One row of posters (or wide pictures with progress, for Continue watching) */
class CardsAdapter(private val items: List<Item>, private val wide: Boolean, private val onFocus: (Item) -> Unit, private val onOpen: (Item) -> Unit) :
    RecyclerView.Adapter<CardsAdapter.Holder>() {

    class Holder(view: View, val card: FrameLayout, val image: ImageView, val title: android.widget.TextView, val sub: android.widget.TextView?, val bar: View?,
                 val year: android.widget.TextView?, val rating: android.widget.TextView?) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (wide) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val w = ctx.dp(if (wide) 200 else 112)
        val h = ctx.dp(if (wide) 112 else 168)
        val card = FrameLayout(ctx).apply {
            layoutParams = if (wide) RecyclerView.LayoutParams(w, h).apply { marginEnd = ctx.dp(12) } else LinearLayout.LayoutParams(w, h)
            background = round(Ui.SURFACE_2, 8, ctx)
            foreground = icon(ctx, com.notdxbi.dxbi.tv.R.drawable.mq_focus)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            focusGrow(1.08f)
        }
        // the title shows while the picture loads (or when there is none)
        val title = ctx.label("", 13f, bold = true, lines = 3).apply { setPadding(ctx.dp(10), 0, ctx.dp(10), ctx.dp(14)) }
        card.addView(title, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        var sub: android.widget.TextView? = null
        var bar: View? = null
        if (wide) {
            val shade = View(ctx).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000)) }
            card.addView(shade, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ctx.dp(56), Gravity.BOTTOM))
            val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) }
            val t2 = ctx.label("", 13f, bold = true)
            sub = ctx.label("", 11f, Ui.MUTED)
            words.addView(t2); words.addView(sub)
            card.addView(words, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            card.tag = t2
            val track = View(ctx).apply { setBackgroundColor(0x40FFFFFF) }
            card.addView(track, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ctx.dp(3), Gravity.BOTTOM))
            bar = View(ctx).apply { setBackgroundColor(Ui.ACCENT) }
            card.addView(bar, FrameLayout.LayoutParams(0, ctx.dp(3), Gravity.BOTTOM))
        }
        if (wide) return Holder(card, card, image, title, sub, bar, null, null)
        // posters: the year on the left under the picture, the IMDb rating on the right
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(12) }
        }
        box.addView(card)
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(2), ctx.dp(7), ctx.dp(2), 0) }
        val year = ctx.label("", 11f, Ui.MUTED)
        info.addView(year, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rating = ctx.label("", 11f, Ui.ACCENT, bold = true)
        info.addView(rating)
        box.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(box, card, image, title, sub, bar, year, rating)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.card.contentDescription = item.name
        if (wide) {
            (holder.card.tag as android.widget.TextView).text = item.name
            holder.sub?.text = item.subtitle
            holder.bar?.layoutParams = (holder.bar?.layoutParams as FrameLayout.LayoutParams).apply { width = (holder.card.layoutParams.width * item.progress).toInt() }
            holder.image.load(backdropFor(item))
        } else {
            holder.image.load(item.poster.ifEmpty { null })
            fun fill() {
                holder.year?.text = Session.yearOf(item)
                val r = Session.ratingOf(item)
                holder.rating?.text = if (r.isEmpty()) "" else "★ $r"
            }
            fill()
            Session.caption(item) { if (holder.bindingAdapterPosition == position) fill() }
        }
        holder.card.setOnClickListener { onOpen(item) }
        holder.card.setOnFocusChangeListener { v, has ->
            if (has) v.unclipParents()
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            v.elevation = if (has) v.dp(6).toFloat() else 0f
            if (has) onFocus(item)
        }
    }
}

/** The rows on Home, one under the other */
class RowsAdapter(private val onFocus: (Item) -> Unit, private val onOpen: (Item) -> Unit) : RecyclerView.Adapter<RowsAdapter.Holder>() {
    val rows = mutableListOf<Row>()
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40); setMaxRecycledViews(1, 16) }

    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = rows[position].title.hashCode().toLong()

    class Holder(view: LinearLayout, val title: android.widget.TextView, val list: RecyclerView) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, ctx.dp(6), 0, ctx.dp(10))
            clipChildren = false
            clipToPadding = false
        }
        val title = ctx.label("", 16f, bold = true).apply { setPadding(ctx.dp(16), 0, 0, ctx.dp(8)) }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false).apply { initialPrefetchItemCount = 6 }
            setRecycledViewPool(pool)
            clipChildren = false
            clipToPadding = false
            // room for the focused card to grow and show its whole ring
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(48), ctx.dp(10))
            itemAnimator = null
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        box.addView(title)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(box, title, list)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.list.adapter = CardsAdapter(row.items, row.wide, onFocus, onOpen)
    }
}

fun typeName(type: String): String = when (type) {
    "movie" -> "Movies"
    "series" -> "Series"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** A small focusable word (a filter or a season) that shows when it is the chosen one */
fun android.content.Context.chip(text: String): android.widget.TextView = label(text, 13f, bold = true).apply {
    setPadding(dp(12), dp(6), dp(12), dp(6))
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(android.graphics.Color.WHITE, 16, this@chip))
        addState(intArrayOf(android.R.attr.state_selected), round(Ui.SURFACE_2, 16, this@chip, 2, Ui.ACCENT))
        addState(intArrayOf(), round(Ui.SURFACE_2, 16, this@chip))
    }
    setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(Ui.BG, Ui.ACCENT, Ui.TEXT)))
    focusGrow(1.05f)
}

/** Changes a pill button's icon and word */
fun setPill(button: LinearLayout, iconRes: Int?, text: String, sub: String? = null) {
    if (iconRes != null) (button.getChildAt(0) as? ImageView)?.setImageResource(iconRes)
    val words = button.getChildAt(button.childCount - 1) as LinearLayout
    (words.getChildAt(0) as android.widget.TextView).text = text
    if (sub != null) (words.findViewWithTag<android.widget.TextView>("sub"))?.text = sub
}
