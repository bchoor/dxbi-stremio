package com.notdxbi.dxbi.tv.marquee

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.notdxbi.dxbi.tv.R

/** Marquee's look, in one place */
object Ui {
    const val BG = 0xFF0B1026.toInt() // brand navy
    const val SURFACE = 0xFF141A36.toInt()
    const val SURFACE_2 = 0xFF1F2647.toInt()
    const val TEXT = 0xFFF2F3F5.toInt()
    const val MUTED = 0xFFA3A8B3.toInt()
    const val ACCENT = 0xFFFF8A3D.toInt() // brand tangerine
    const val PEACH = 0xFFFFD9C2.toInt()
    const val GO = 0xFF6FE3BC.toInt()
    const val GO_BG = 0xFF173A30.toInt()
    const val WARN = 0xFFF5C46A.toInt()
    const val WARN_BG = 0xFF3A2F1E.toInt()
    const val BUTTON = 0x7376808A
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun round(color: Int, radiusDp: Int, ctx: Context, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = ctx.dp(radiusDp).toFloat()
    if (stroke > 0) setStroke(ctx.dp(stroke), strokeColor)
}

fun Context.label(text: CharSequence = "", sizeSp: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false, lines: Int = 1) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    includeFontPadding = false
}

fun Context.badge(text: String, kind: Badge) = label(text, 10f, bold = true).apply {
    val (fg, bg, line) = when (kind) {
        Badge.SOLID -> Triple(Ui.BG, Ui.PEACH, 0)
        Badge.LINE -> Triple(Ui.TEXT, Color.TRANSPARENT, 0xFF6E7480.toInt())
        Badge.WARN -> Triple(Ui.WARN, Ui.WARN_BG, 0)
        Badge.GOLD -> Triple(Ui.ACCENT, Color.TRANSPARENT, Ui.ACCENT)
        Badge.GO -> Triple(Ui.GO, Ui.GO_BG, 0)
        Badge.EXTENDED -> Triple(0xFF1A1030.toInt(), 0xFFC79BFF.toInt(), 0)
        Badge.THEATRICAL -> Triple(0xFF0B2622.toInt(), 0xFF5FD6C8.toInt(), 0)
        Badge.CUT -> Triple(Ui.BG, 0xFFF5C46A.toInt(), 0)
    }
    setTextColor(fg)
    background = round(bg, 4, this@badge, if (line != 0) 1 else 0, line)
    setPadding(dp(5), dp(3), dp(5), dp(3))
}

/** Grows a little and gets a white ring when the remote is on it (Netflix-style focus) */
fun View.focusGrow(scale: Float = 1.06f) {
    isFocusable = true
    isClickable = true
    setOnFocusChangeListener { v, has ->
        if (has) v.unclipParents()
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
        v.elevation = if (has) v.dp(6).toFloat() else 0f
    }
}

/** Lets a focused view grow past its parents' edges instead of being cut off */
fun View.unclipParents() {
    var p = parent
    while (p is ViewGroup) {
        p.clipChildren = false
        p.clipToPadding = false
        p = p.parent
    }
}

/** A round icon button (Back, Trailer…) with its name for TalkBack */
fun Context.iconButton(icon: Int, name: String, sizeDp: Int = 40, bg: Int = Ui.BUTTON): ImageView = ImageView(this).apply {
    setImageResource(icon)
    contentDescription = name
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    val pad = dp(sizeDp / 4)
    setPadding(pad, pad, pad, pad)
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(Color.WHITE, sizeDp, this@iconButton))
        addState(intArrayOf(), round(bg, sizeDp, this@iconButton))
    }
    imageTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Ui.BG, Color.WHITE))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    focusGrow(1.08f)
}

/** A button with an icon and a word under or beside it */
fun Context.pillButton(icon: Int?, text: String, primary: Boolean = false, sub: String? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(10), dp(18), dp(10))
    val normal = if (primary) Color.WHITE else Ui.BUTTON
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(Color.WHITE, 8, this@pillButton, 3, Ui.ACCENT))
        addState(intArrayOf(), round(normal, 8, this@pillButton))
    }
    val fg = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Ui.BG, if (primary) Ui.BG else Color.WHITE))
    if (icon != null) addView(ImageView(context).apply {
        setImageResource(icon)
        imageTintList = fg
        isDuplicateParentStateEnabled = true
    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
    val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
    words.addView(context.label(text, 15f, bold = true).apply { setTextColor(fg); isDuplicateParentStateEnabled = true })
    if (sub != null) words.addView(context.label(sub, 10f, bold = true).apply { setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF3A3F48.toInt(), if (primary) 0xFF3A3F48.toInt() else Ui.MUTED))); isDuplicateParentStateEnabled = true; tag = "sub" })
    addView(words)
    focusGrow(1.04f)
}

fun frame(ctx: Context) = FrameLayout(ctx)

fun match() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

/** Stremio's poster service: a landscape picture for any IMDb title */
fun backdropFor(item: Item): String = item.background.ifEmpty {
    if (item.id.startsWith("tt")) "https://images.metahub.space/background/medium/${item.id.substringBefore(':')}/img" else item.poster
}

fun icon(ctx: Context, res: Int) = ContextCompat.getDrawable(ctx, res)

@Suppress("unused") private val keep = R.drawable.ic_mq_play
