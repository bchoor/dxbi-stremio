package com.notdxbi.dxbi.tv.marquee

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.notdxbi.dxbi.tv.R

/**
 * The splash while Home loads (the brand's timing): the ticket drops in, its perforation stitches
 * down, the play button punches through, the name rises in, and a light sheen loops until Home is
 * ready. The lockup is a quarter of the screen's width.
 */
class Splash(private val activity: Activity) {
    val view = FrameLayout(activity).apply { setBackgroundColor(Ui.BG); isClickable = true }
    private val lockup = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val ticket = FrameLayout(activity)
    private val perforation = image(R.drawable.splash_perforation)
    private val play = image(R.drawable.splash_play)
    private val sheen = View(activity)
    private val word = ImageView(activity).apply { setImageResource(R.drawable.marquee_wordmark); adjustViewBounds = true }
    private var started = 0L
    private var finished = false
    private var loop: ValueAnimator? = null

    private fun image(res: Int) = ImageView(activity).apply { setImageResource(res); scaleType = ImageView.ScaleType.FIT_XY }

    fun start() {
        started = SystemClock.uptimeMillis()
        val w = activity.resources.displayMetrics.widthPixels / 4
        val h = w * 72 / 108
        ticket.addView(image(R.drawable.splash_ticket), FrameLayout.LayoutParams(w, h))
        // the sheen: a soft white band, kept inside the ticket's body
        sheen.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF))
        sheen.rotation = 18f
        sheen.alpha = 0f
        ticket.addView(sheen, FrameLayout.LayoutParams(w * 22 / 108, h * 2, Gravity.TOP).apply { topMargin = -h / 2 })
        ticket.addView(perforation, FrameLayout.LayoutParams(w, h))
        ticket.addView(play, FrameLayout.LayoutParams(w, h))
        ticket.clipToOutline = true
        ticket.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(w * 22 / 108, h * 14 / 72, w * 86 / 108, h * 58 / 72, w * 6f / 108)
        }
        lockup.addView(ticket, LinearLayout.LayoutParams(w, h))
        lockup.addView(word, LinearLayout.LayoutParams(w * 62 / 100, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = w * 3 / 100 })
        view.addView(lockup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 0–420 ms: the ticket fades and scales in with a slight overshoot
        ticket.alpha = 0f; ticket.scaleX = 0.82f; ticket.scaleY = 0.82f; ticket.rotation = -4f
        ticket.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
        // 250–650 ms: the perforation stitches down
        perforation.pivotY = h * 18f / 72; perforation.scaleY = 0f
        perforation.animate().scaleY(1f).setStartDelay(250).setDuration(400).setInterpolator(DecelerateInterpolator()).start()
        // 550–930 ms: the play button punches through
        play.pivotX = w * 62f / 108; play.pivotY = h * 36f / 72; play.scaleX = 0f; play.scaleY = 0f
        play.animate().scaleX(1f).scaleY(1f).setStartDelay(550).setDuration(380).setInterpolator(OvershootInterpolator(2f)).start()
        // 750–1150 ms: the name rises in
        word.alpha = 0f; word.translationY = activity.dp(10).toFloat()
        word.animate().alpha(1f).translationY(0f).setStartDelay(750).setDuration(400).setInterpolator(DecelerateInterpolator()).start()
        // from 1.3 s: the sheen sweeps the ticket every 1.8 s until Home is ready
        view.postDelayed({
            if (finished) return@postDelayed
            sheen.alpha = 1f
            loop = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val t = (a.animatedValue as Float / 0.55f).coerceAtMost(1f)
                    sheen.translationX = w * (0.1f + 0.8f * t)
                }
                start()
            }
        }, 1300)
        // never stuck on the splash
        view.postDelayed({ done() }, 10_000)
    }

    /** Home is ready: fade out (after at least 1.3 s on screen) */
    fun done() {
        if (finished) return
        val wait = (1300 - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0)
        finished = true
        view.postDelayed({
            loop?.cancel()
            lockup.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(280).start()
            view.animate().alpha(0f).setStartDelay(200).setDuration(240).withEndAction { (view.parent as? ViewGroup)?.removeView(view) }.start()
        }, wait)
    }
}
