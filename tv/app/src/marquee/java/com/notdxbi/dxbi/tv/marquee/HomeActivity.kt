package com.notdxbi.dxbi.tv.marquee

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.notdxbi.dxbi.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home, laid out like Netflix on a TV: a menu on the left that opens with its names when the remote
 * goes into it, a big picture of the focused title, and rows of posters
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var backdrop: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroText: TextView
    private lateinit var listButton: LinearLayout
    private lateinit var rowsView: RecyclerView
    private lateinit var status: TextView
    private lateinit var rail: LinearLayout
    private lateinit var dim: View
    private val railItems = mutableListOf<Pair<String, LinearLayout>>()
    private val rows = RowsAdapter({ show(it) }, { Session.open(this, it) })
    /** Every row we have; [rows] shows the ones for the page chosen in the menu */
    private val allRows = mutableListOf<Row>()
    private var page = HOME
    private var hero: Item? = null
    private var loaded = false
    private var pictureJob: Job? = null
    private var focusedOnce = false
    private var railOpen = false
    private var splash: Splash? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG); clipChildren = false }

        // the focused title's picture, top right, fading into the background
        val picture = FrameLayout(this)
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        picture.addView(backdrop, match())
        picture.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.BG, 0x000B1026)) },
            FrameLayout.LayoutParams(dp(280), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        picture.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.BG, 0x000B1026)) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM))
        root.addView(picture, FrameLayout.LayoutParams(dp(660), dp(372), Gravity.TOP or Gravity.END))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(heroPanel(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(262)))
        status = label("Loading your catalogs…", 14f, Ui.MUTED).apply { setPadding(dp(16), 0, 0, dp(8)) }
        content.addView(status)
        rowsView = RecyclerView(this).apply {
            layoutManager = TopRowsLayout(context)
            adapter = rows
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(0, 0, 0, dp(320))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setItemViewCacheSize(4)
        }
        content.addView(rowsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(RAIL_SHUT) })

        // the page behind darkens while the menu is open
        dim = View(this).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE }
        root.addView(dim, match())
        rail = menu()
        root.addView(rail, FrameLayout.LayoutParams(dp(RAIL_SHUT), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        // the animated splash covers Home until its first row is in
        if (savedInstanceState == null && Api.authKey != null) splash = Splash(this).also { root.addView(it.view, match()); it.start() }
        setContentView(root)

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> openRail(now != null && inside(rail, now)) }
        // Back: first to the menu (as Netflix does), then out of the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (railOpen) finish() else railItems.firstOrNull { it.first == page }?.second?.requestFocus()
            }
        })
    }

    private fun inside(parent: View, v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    // ---- The menu ----
    private fun menu(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(24), dp(14), dp(24))
        fun add(key: String, icon: Int, name: String, action: () -> Unit) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                contentDescription = name
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), round(0x33FFFFFF, 8, context))
                    addState(intArrayOf(), round(0x00000000, 8, context))
                }
                addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(context.label(name, 17f, bold = true).apply { visibility = View.GONE },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(18) })
                setOnClickListener { action() }
            }
            railItems.add(key to item)
            addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) })
        }
        add("search", R.drawable.ic_mq_search, "Search") { startActivity(Intent(this@HomeActivity, SearchActivity::class.java)) }
        add(HOME, R.drawable.ic_mq_home, "Home") { showPage(HOME) }
        add(SHOWS, R.drawable.ic_mq_tv, "Shows") { showPage(SHOWS) }
        add(MOVIES, R.drawable.ic_mq_movie, "Movies") { showPage(MOVIES) }
        add(ANIME, R.drawable.ic_mq_anime, "Anime") { showPage(ANIME) }
        add(MY_LIST, R.drawable.ic_mq_add, "My List") { showPage(MY_LIST) }
        add("settings", R.drawable.ic_mq_settings, "Settings") { settings() }
        paintRail()
    }

    private fun openRail(open: Boolean) {
        if (open == railOpen) return
        railOpen = open
        rail.layoutParams = rail.layoutParams.apply { width = dp(if (open) RAIL_OPEN else RAIL_SHUT) }
        rail.background = if (open) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFA0B1026.toInt(), 0xEE0B1026.toInt(), 0x000B1026))
            else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE60B1026.toInt(), 0x000B1026))
        dim.visibility = if (open) View.VISIBLE else View.GONE
        for ((_, item) in railItems) item.getChildAt(1).visibility = if (open) View.VISIBLE else View.GONE
        paintRail()
    }

    /** The page you're on is gold; the rest white (names grey until the remote is on them) */
    private fun paintRail() {
        for ((key, item) in railItems) {
            val here = key == page
            (item.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (here) Ui.ACCENT else android.graphics.Color.WHITE)
            (item.getChildAt(1) as TextView).setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(android.graphics.Color.WHITE, if (here) Ui.ACCENT else Ui.MUTED)))
            (item.getChildAt(1) as TextView).isDuplicateParentStateEnabled = true
        }
    }

    private fun showPage(p: String) {
        page = p
        paintRail()
        render()
        val first = rows.rows.firstOrNull()?.items?.firstOrNull()
        if (first != null) { show(first); focusRow(0) }
        else {
            status.visibility = View.VISIBLE
            status.text = when (p) {
                MY_LIST -> "My List is empty. Press ＋ My List on any title to add it."
                ANIME -> "None of your add-ons has anime catalogs"
                else -> "Nothing here yet"
            }
        }
    }

    /** Fills the rows for the chosen page */
    private fun render() {
        val shown = when (page) {
            HOME -> allRows.toList()
            MY_LIST -> allRows.filter { it.title == MY_LIST_ROW }
            ANIME -> allRows.filter { it.kind == ANIME }
            else -> {
                val type = if (page == MOVIES) "movie" else "series"
                allRows.filter { it.kind != ANIME }.mapNotNull { r ->
                    r.items.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { Row(r.title, it, r.wide, r.kind) }
                }
            }
        }
        rows.rows.clear()
        rows.rows.addAll(shown)
        rows.notifyDataSetChanged()
        if (shown.isNotEmpty()) status.visibility = View.GONE
    }

    // ---- The hero ----
    private fun heroPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(16), dp(24), 0, dp(14))
        heroTitle = label("", 30f, bold = true, lines = 2)
        addView(heroTitle, LinearLayout.LayoutParams(dp(480), LinearLayout.LayoutParams.WRAP_CONTENT))
        heroMeta = label("", 13f, Ui.MUTED)
        addView(heroMeta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        heroText = label("", 14f, lines = 3).apply { setLineSpacing(0f, 1.15f) }
        addView(heroText, LinearLayout.LayoutParams(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val gap = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) } }
        buttons.addView(pillButton(R.drawable.ic_mq_play, "Play", primary = true).apply { setOnClickListener { hero?.let { Session.open(this@HomeActivity, it, autoplay = true) } } }, gap())
        buttons.addView(pillButton(R.drawable.ic_mq_info, "More info").apply { setOnClickListener { hero?.let { Session.open(this@HomeActivity, it) } } }, gap())
        listButton = pillButton(R.drawable.ic_mq_add, "My List").apply { setOnClickListener { toggleList() } }
        buttons.addView(listButton, gap())
        addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    override fun onResume() {
        super.onResume()
        if (Api.authKey == null) { signIn(); return }
        if (!loaded) { loaded = true; loadAll() } else {
            placeLibraryRows()
            lifecycleScope.launch { refreshLibrary() }
        }
        hero?.let { show(it) }
    }

    private fun signIn() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun loadAll() = lifecycleScope.launch {
        status.visibility = View.VISIBLE
        status.text = "Loading your catalogs…"
        val addons = try { Api.addons() } catch (e: Exception) {
            if (Api.authKey == null) { signIn(); return@launch }
            status.text = "Couldn't reach Stremio (${e.message}). Open Home again to retry."
            splash?.done()
            loaded = false
            return@launch
        }
        Session.addons = addons
        refreshLibrary()
        val catalogs = addons.flatMap { it.homeCatalogs() }.filter { it.type == "movie" || it.type == "series" || it.type == "anime" }.take(40)
        // every catalog is asked at once; rows still appear in the add-ons' order
        val asked = catalogs.map { c ->
            c to async {
                withTimeoutOrNull(20_000) {
                    try {
                        Api.get(c.addon.url("catalog", c.type, c.id)).optJSONArray("metas").objects()
                            .map(Item::from).filter { it.id.isNotEmpty() && it.poster.isNotEmpty() }
                            .map { if (it.type.isEmpty()) it.copy(type = c.type) else it }
                    } catch (_: Exception) { null }
                }
            }
        }
        for ((c, answer) in asked) {
            val items = answer.await() ?: continue
            if (items.isEmpty()) continue
            val kind = if (c.type == "anime" || c.addon.name.contains("anime", ignoreCase = true)) ANIME else c.type
            var title = "${c.name} ${if (kind == ANIME && c.type != "anime") "Anime" else typeName(c.type)}"
            if (allRows.any { it.title == title }) title = "$title · ${c.addon.name}"
            val row = Row(title, items, kind = kind)
            allRows.add(row)
            if (page == HOME) {
                rows.rows.add(row)
                rows.notifyItemInserted(rows.rows.size - 1)
                status.visibility = View.GONE
            } else if (row.kind == ANIME && page == ANIME || page == MOVIES || page == SHOWS) render()
            firstFocus()
        }
        if (allRows.isEmpty()) status.text = "None of your add-ons had a catalog to show"
        splash?.done()
    }

    private suspend fun refreshLibrary() {
        val lib = try { Api.library() } catch (_: Exception) { return }
        Play.library = lib.associateBy { it.optString("_id") }.toMutableMap()
        placeLibraryRows()
    }

    /** Continue watching and My List go first, and change only when what's in them changed */
    private fun placeLibraryRows() {
        val wanted = listOfNotNull(
            Session.continueWatching(Play.library.values).takeIf { it.isNotEmpty() }?.let { Row(CONTINUE, it, wide = true) },
            Session.watchlist(Play.library.values).takeIf { it.isNotEmpty() }?.let { Row(MY_LIST_ROW, it) },
        )
        val current = allRows.filter { it.title == CONTINUE || it.title == MY_LIST_ROW }
        fun sig(list: List<Row>) = list.joinToString("|") { r -> r.title + ":" + r.items.joinToString(",") { "${it.id}/${it.videoId}/${(it.progress * 100).toInt()}" } }
        if (sig(wanted) == sig(current)) return
        allRows.removeAll { it.title == CONTINUE || it.title == MY_LIST_ROW }
        allRows.addAll(0, wanted)
        render()
        firstFocus()
    }

    private fun firstFocus() {
        if (rows.rows.isNotEmpty()) splash?.done()
        if (focusedOnce || rows.rows.isEmpty()) return
        focusedOnce = true
        rowsView.postDelayed({ focusRow(0) }, 150)
    }

    private fun focusRow(index: Int) {
        if (index !in rows.rows.indices) return
        rowsView.scrollToPosition(index)
        rowsView.postDelayed({
            val box = rowsView.layoutManager?.findViewByPosition(index) as? ViewGroup ?: return@postDelayed
            val list = (0 until box.childCount).map { box.getChildAt(it) }.firstOrNull { it is RecyclerView } as? RecyclerView
            list?.getChildAt(0)?.requestFocus()
        }, 120)
    }

    /** The hero follows the remote: title, words and picture of whatever is focused */
    private fun show(item: Item) {
        hero = item
        heroTitle.text = item.name
        heroMeta.text = listOf(item.year, if (item.type == "series") "Series" else if (item.type == "movie") "Movie" else "", item.subtitle)
            .filter { it.isNotEmpty() }.joinToString("  ·  ")
        heroText.text = item.description
        updateListButton()
        pictureJob?.cancel()
        pictureJob = lifecycleScope.launch {
            delay(220) // not while the remote is racing along a row
            backdrop.load(backdropFor(item))
            if (item.description.isEmpty()) {
                val meta = Session.meta(item.type, item.id) ?: return@launch
                if (hero?.id == item.id) heroText.text = meta.optString("description")
            }
        }
    }

    /** ＋ My List, or ✓ My List once it's on the list */
    private fun updateListButton() {
        val on = hero?.let { Play.inWatchlist(it.id) } == true
        setPill(listButton, if (on) R.drawable.ic_mq_check else R.drawable.ic_mq_add, "My List")
        listButton.contentDescription = if (on) "Remove from My List" else "Add to My List"
    }

    private fun toggleList() {
        val item = hero ?: return
        val on = !Play.inWatchlist(item.id)
        Play.setWatchlist(item, on) { ok ->
            if (!ok) { Toast.makeText(this, "Couldn't update My List", Toast.LENGTH_SHORT).show(); return@setWatchlist }
            updateListButton()
            Toast.makeText(this, if (on) "Added to My List" else "Removed from My List", Toast.LENGTH_SHORT).show()
            placeLibraryRows()
        }
    }

    private fun settings() {
        val names = Session.addons.map { it.name }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Your add-ons (from your Stremio account)")
            .setItems(names, null)
            .setPositiveButton("Sign out") { _, _ ->
                Api.authKey = null
                Play.library.clear()
                Session.addons = emptyList()
                signIn()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Keeps the focused row at the top, as Netflix does */
    private class TopRowsLayout(ctx: Context) : LinearLayoutManager(ctx) {
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
            val dy = child.top - parent.paddingTop
            if (dy == 0) return false
            if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
            return true
        }
    }

    companion object {
        const val CONTINUE = "Continue watching"
        const val MY_LIST_ROW = "My List"
        const val HOME = "home"
        const val SHOWS = "shows"
        const val MOVIES = "movies"
        const val ANIME = "anime"
        const val MY_LIST = "mylist"
        const val RAIL_SHUT = 76
        const val RAIL_OPEN = 230
    }
}
