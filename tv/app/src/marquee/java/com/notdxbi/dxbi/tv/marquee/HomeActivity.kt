package com.notdxbi.dxbi.tv.marquee

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

/** Home: a big picture of the focused title on top, rows of posters under it, icons on the left */
class HomeActivity : AppCompatActivity() {
    private lateinit var backdrop: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroText: TextView
    private lateinit var watchButton: ImageView
    private lateinit var rowsView: RecyclerView
    private lateinit var status: TextView
    private val rows = RowsAdapter({ show(it) }, { Session.open(this, it) })
    private var hero: Item? = null
    private var loaded = false
    private var pictureJob: Job? = null
    private var focusedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }

        // the focused title's picture, top right, fading into the background
        val picture = FrameLayout(this)
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        picture.addView(backdrop, match())
        picture.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.BG, 0x000B0C0F)) },
            FrameLayout.LayoutParams(dp(280), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        picture.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.BG, 0x000B0C0F)) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM))
        root.addView(picture, FrameLayout.LayoutParams(dp(660), dp(372), Gravity.TOP or Gravity.END))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(rail(), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(heroPanel(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(262)))
        status = label("Loading your catalogs…", 14f, Ui.MUTED).apply { setPadding(dp(4), 0, 0, dp(8)) }
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
        body.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, match())
        setContentView(root)
    }

    private fun rail(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF00B0C0F.toInt(), 0x000B0C0F))
        fun add(icon: Int, name: String, action: () -> Unit) = addView(iconButton(icon, name, 44, 0x00000000).apply { setOnClickListener { action() } },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        add(R.drawable.ic_mq_search, "Search") { startActivity(Intent(this@HomeActivity, SearchActivity::class.java)) }
        add(R.drawable.ic_mq_home, "Home") { focusRow(0) }
        add(R.drawable.ic_mq_bookmark, "Watchlist") {
            val i = rows.rows.indexOfFirst { it.title == WATCHLIST }
            if (i >= 0) focusRow(i) else Toast.makeText(this@HomeActivity, "Your watchlist is empty", Toast.LENGTH_SHORT).show()
        }
        add(R.drawable.ic_mq_settings, "Settings") { settings() }
    }

    private fun heroPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(4), dp(24), 0, dp(14))
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
        buttons.addView(iconButton(R.drawable.ic_mq_trailer, "Trailer").apply { setOnClickListener { trailer() } },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        watchButton = iconButton(R.drawable.ic_mq_bookmark, "Add to watchlist").apply { setOnClickListener { toggleWatchlist() } }
        buttons.addView(watchButton, LinearLayout.LayoutParams(dp(40), dp(40)))
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
            loaded = false
            return@launch
        }
        Session.addons = addons
        refreshLibrary()
        val catalogs = addons.flatMap { it.homeCatalogs() }.filter { it.type == "movie" || it.type == "series" }.take(30)
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
            var title = "${c.name} ${typeName(c.type)}"
            if (rows.rows.any { it.title == title }) title = "$title · ${c.addon.name}"
            rows.rows.add(Row(title, items))
            rows.notifyItemInserted(rows.rows.size - 1)
            status.visibility = View.GONE
            firstFocus()
        }
        if (rows.rows.isEmpty()) status.text = "None of your add-ons had a catalog to show"
    }

    private suspend fun refreshLibrary() {
        val lib = try { Api.library() } catch (_: Exception) { return }
        Play.library = lib.associateBy { it.optString("_id") }.toMutableMap()
        placeLibraryRows()
    }

    /** Continue watching and Watchlist go first, and change only when what's in them changed */
    private fun placeLibraryRows() {
        val wanted = listOfNotNull(
            Session.continueWatching(Play.library.values).takeIf { it.isNotEmpty() }?.let { Row(CONTINUE, it, wide = true) },
            Session.watchlist(Play.library.values).takeIf { it.isNotEmpty() }?.let { Row(WATCHLIST, it) },
        )
        val current = rows.rows.filter { it.title == CONTINUE || it.title == WATCHLIST }
        fun sig(list: List<Row>) = list.joinToString("|") { r -> r.title + ":" + r.items.joinToString(",") { "${it.id}/${it.videoId}/${(it.progress * 100).toInt()}" } }
        if (sig(wanted) == sig(current)) return
        rows.rows.removeAll { it.title == CONTINUE || it.title == WATCHLIST }
        rows.rows.addAll(0, wanted)
        rows.notifyDataSetChanged()
        firstFocus()
    }

    private fun firstFocus() {
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
        updateWatchButton()
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

    private fun updateWatchButton() {
        val on = hero?.let { Play.inWatchlist(it.id) } == true
        watchButton.setImageResource(if (on) R.drawable.ic_mq_bookmark_added else R.drawable.ic_mq_bookmark)
        watchButton.contentDescription = if (on) "Remove from watchlist" else "Add to watchlist"
    }

    private fun toggleWatchlist() {
        val item = hero ?: return
        val on = !Play.inWatchlist(item.id)
        Play.setWatchlist(item, on) { ok ->
            if (!ok) { Toast.makeText(this, "Couldn't update your watchlist", Toast.LENGTH_SHORT).show(); return@setWatchlist }
            updateWatchButton()
            Toast.makeText(this, if (on) "Added to your watchlist" else "Removed from your watchlist", Toast.LENGTH_SHORT).show()
            placeLibraryRows()
        }
    }

    private fun trailer() {
        val item = hero ?: return
        lifecycleScope.launch {
            val yt = Session.trailerId(Session.meta(item.type, item.id))
            if (yt == null) Toast.makeText(this@HomeActivity, "No trailer for this one", Toast.LENGTH_SHORT).show()
            else Play.trailer(this@HomeActivity, yt)
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
        const val WATCHLIST = "Your watchlist"
    }
}
