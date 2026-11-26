package com.notdxbi.dxbi.tv.marquee

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notdxbi.dxbi.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/** Search every add-on that can search, as you type */
class SearchActivity : AppCompatActivity() {
    private val rows = RowsAdapter({}, { remember(box.text.toString()); Session.open(this, it) })
    private lateinit var recent: LinearLayout
    private lateinit var recentBox: LinearLayout
    private var job: Job? = null
    private lateinit var box: EditText
    private lateinit var status: TextView
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(40), dp(28), 0, 0)
            clipChildren = false
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconButton(R.drawable.ic_mq_back, "Back").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        box = EditText(this).apply {
            hint = "Search movies and shows"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = round(Ui.SURFACE_2, 8, this@SearchActivity)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mq_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(Ui.MUTED)
        }
        top.addView(box, LinearLayout.LayoutParams(dp(520), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(top)
        status = label("", 14f, Ui.MUTED).apply { setPadding(dp(4), dp(14), 0, dp(6)) }
        root.addView(status)
        // recent searches, kept on this TV only: shown while the box is empty
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), 0, 0) }
        recentBox.addView(label("Recent searches", 14f, Ui.MUTED, bold = true))
        recent = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(dp(4), dp(10), dp(40), dp(10)) }
        recentBox.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(recent) })
        recentBox.addView(label("Long-press a search to remove it", 11f, Ui.MUTED).apply { setPadding(dp(4), dp(2), 0, 0) })
        root.addView(recentBox)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rows
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(0, 0, 0, dp(200))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                job?.cancel()
                job = lifecycleScope.launch { delay(600); search(s?.toString().orEmpty()) }
            }
        })
        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            job?.cancel()
            remember(box.text.toString())
            job = lifecycleScope.launch { search(box.text.toString()); goToResults() }
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(box.windowToken, 0)
            true
        }
        showRecent()
        box.requestFocus()
    }

    // ---- Search history ----
    private val prefs get() = getSharedPreferences("marquee", MODE_PRIVATE)

    private fun history(): List<String> =
        prefs.getString("searches", "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveHistory(list: List<String>) = prefs.edit().putString("searches", list.take(12).joinToString("\n")).apply()

    private fun remember(text: String) {
        val q = text.trim()
        if (q.length < 2) return
        saveHistory(listOf(q) + history().filterNot { it.equals(q, ignoreCase = true) })
        showRecent()
    }

    private fun showRecent() {
        val past = history()
        recent.removeAllViews()
        recentBox.visibility = if (past.isEmpty() || box.text.trim().length >= 2) android.view.View.GONE else android.view.View.VISIBLE
        for (q in past) {
            recent.addView(pillButton(R.drawable.ic_mq_search, q).apply {
                isFocusable = true
                setOnClickListener { box.setText(q); box.setSelection(q.length); remember(q); job?.cancel(); job = lifecycleScope.launch { search(q); goToResults() } }
                setOnLongClickListener { saveHistory(history().filterNot { it == q }); showRecent(); recent.getChildAt(0)?.requestFocus() ?: box.requestFocus(); true }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        }
        if (past.isNotEmpty()) recent.addView(pillButton(null, "Clear history").apply {
            isFocusable = true
            setOnClickListener { saveHistory(emptyList()); showRecent(); box.requestFocus() }
        })
    }

    private suspend fun search(text: String) {
        val q = text.trim()
        rows.rows.clear()
        rows.notifyDataSetChanged()
        showRecent()
        if (q.length < 2) { status.text = ""; return }
        val catalogs = Session.ensureAddons().flatMap { it.searchCatalogs() }
        if (catalogs.isEmpty()) { status.text = "None of your add-ons can search"; return }
        status.text = "Searching…"
        val extra = "search=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val coroutineScope = lifecycleScope
        val asked = catalogs.map { c ->
            c to coroutineScope.async {
                withTimeoutOrNull(15_000) {
                    try {
                        Api.get(c.addon.url("catalog", c.type, c.id, extra)).optJSONArray("metas").objects()
                            .map(Item::from).filter { it.id.isNotEmpty() }
                            .map { if (it.type.isEmpty()) it.copy(type = c.type) else it }
                    } catch (_: Exception) { null }
                }
            }
        }
        var found = 0
        for ((c, answer) in asked) {
            val items = answer.await()?.let { fillCaptions(it) } ?: continue
            if (items.isEmpty()) continue
            var title = typeName(c.type)
            if (rows.rows.any { it.title == title }) title = "$title · ${c.addon.name}"
            rows.rows.add(Row(title, items))
            rows.notifyItemInserted(rows.rows.size - 1)
            found += items.size
        }
        status.text = if (found == 0) "Nothing found for “$q”" else ""
    }

    /** Search answers often leave out the year and IMDb rating shown under each poster: read them from the title's details */
    private suspend fun fillCaptions(items: List<Item>): List<Item> {
        val scope = lifecycleScope
        return items.mapIndexed { i, it ->
            if ((it.year.isNotEmpty() && it.rating.isNotEmpty()) || i >= 30) scope.async { it }
            else scope.async {
                val m = withTimeoutOrNull(5_000) { Session.meta(it.type, it.id) } ?: return@async it
                val full = Item.from(m)
                it.copy(year = it.year.ifEmpty { full.year }, rating = it.rating.ifEmpty { full.rating })
            }
        }.map { it.await() }
    }

    private fun goToResults() {
        list.postDelayed({
            val boxRow = list.layoutManager?.findViewByPosition(0) as? ViewGroup ?: return@postDelayed
            val inner = (0 until boxRow.childCount).map { boxRow.getChildAt(it) }.firstOrNull { it is RecyclerView } as? RecyclerView
            inner?.getChildAt(0)?.requestFocus()
        }, 150)
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
