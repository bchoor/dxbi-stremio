package com.notdxbi.dxbi.tv.marquee

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.notdxbi.dxbi.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sign in with a phone: scan the code, approve it in Stremio, and the TV is signed in. No typing. */
class LoginActivity : AppCompatActivity() {
    private lateinit var qr: ImageView
    private lateinit var code: android.widget.TextView
    private lateinit var hint: android.widget.TextView
    private var polling: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(ImageView(this).apply { setImageResource(R.drawable.marquee_banner) }, LinearLayout.LayoutParams(dp(192), dp(108)).apply { bottomMargin = dp(20) })
        words.addView(label("Sign in with your phone", 28f, bold = true))
        hint = label("Scan the code with your phone's camera, or go to link.stremio.com and enter the code. Approve it while signed in to Stremio.", 15f, Ui.MUTED, lines = 4)
        words.addView(hint, LinearLayout.LayoutParams(dp(380), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        code = label("····", 40f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.3f }
        words.addView(code, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        root.addView(words, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(56) })
        qr = ImageView(this).apply { setBackgroundColor(Color.WHITE); val p = dp(10); setPadding(p, p, p, p) }
        root.addView(qr, LinearLayout.LayoutParams(dp(260), dp(260)))
        setContentView(root)
        newCode()
    }

    private fun newCode() {
        polling?.cancel()
        polling = lifecycleScope.launch {
            val link = try { Api.linkCreate() } catch (e: Exception) {
                hint.text = "Couldn't reach Stremio (${e.message}). Trying again…"
                delay(5000); newCode(); return@launch
            }
            code.text = link.code
            qr.setImageBitmap(qrBitmap(link.link.ifEmpty { "https://link.stremio.com" }, dp(240)))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 10 * 60_000) {
                delay(3000)
                val key = Api.linkRead(link.code) ?: continue
                Api.authKey = key
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
                return@launch
            }
            newCode() // codes run out: show a fresh one
        }
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
