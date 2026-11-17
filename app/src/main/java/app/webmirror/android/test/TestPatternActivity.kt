package app.webmirror.android.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Full-screen test content for the automatic test: something that moves in every way a screen can (a bar,
 * scrolling text, a counter, a millisecond clock, coloured noise that stresses the encoder), drawn once per
 * display refresh. The corner shows the phase. Back aborts the test. Any orientation works.
 */
class TestPatternActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val view = PatternView(this)
        setContentView(view)
        WindowInsetsControllerCompat(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AutoTestRunner.abort()
                finish()
            }
        })
        current = WeakReference(this)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<TestPatternActivity>? = null

        /** Closes the pattern (the run ended). */
        fun close() {
            current?.get()?.finish()
            current = null
        }
    }
}

private class PatternView(context: Context) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val random = Random()
    private var frame = 0L
    private var startNanos = 0L
    private var nowNanos = 0L
    private var running = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNanos == 0L) startNanos = frameTimeNanos
        nowNanos = frameTimeNanos
        frame++
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = minOf(w, h) / 100f // 1 % of the short side
        val t = (nowNanos - startNanos) / 1e9
        canvas.drawColor(Color.rgb(16, 20, 28))

        // Horizontal bar, 40 % of the width per second.
        val barW = 14 * unit
        val travel = w + barW
        val x = ((t * 0.4 * w) % travel).toFloat() - barW
        paint.color = Color.WHITE
        canvas.drawRect(x, h * 0.30f, x + barW, h * 0.42f, paint)

        // Frame counter and millisecond clock.
        textPaint.color = Color.WHITE
        textPaint.textSize = 9 * unit
        canvas.drawText("frame $frame", 3 * unit, 14 * unit, textPaint)
        textPaint.color = Color.rgb(255, 214, 102)
        canvas.drawText(clockFormat.format(Date()), 3 * unit, 25 * unit, textPaint)

        // Scrolling text block (lower left half): 90 px per 1 % of the short side per second.
        val lineH = 6 * unit
        val blockL = 3 * unit
        val blockT = h * 0.48f
        val blockR = w * 0.52f
        val blockB = h - 3 * unit
        textPaint.textSize = 4.4f * unit
        textPaint.color = Color.rgb(170, 220, 255)
        canvas.save()
        canvas.clipRect(blockL, blockT, blockR, blockB)
        val scroll = t * 18 * unit
        val firstLine = Math.floor(scroll / lineH).toLong()
        val offset = (scroll - firstLine * lineH).toFloat()
        var line = 0
        while (blockT + line * lineH - offset < blockB + lineH) {
            val n = firstLine + line
            canvas.drawText("%06d the quick brown fox".format(n), blockL, blockT + (line + 1) * lineH - offset, textPaint)
            line++
        }
        canvas.restore()

        // Changing coloured noise (lower right): a new random colour in every cell, every frame.
        val cols = 32
        val rows = 18
        val nl = w * 0.56f
        val nt = h * 0.48f
        val cw = (w - 3 * unit - nl) / cols
        val ch = (h - 3 * unit - nt) / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                canvas.drawRect(nl + c * cw, nt + r * ch, nl + (c + 1) * cw, nt + (r + 1) * ch, paint)
            }
        }

        // Phase info, top right.
        val s = AutoTestRunner.state.value
        val lines = ArrayList<String>()
        when (s.status) {
            AutoTestRunner.Status.WAITING -> {
                lines.add("WAITING")
                lines.addAll(s.missing)
            }
            AutoTestRunner.Status.RUNNING -> {
                lines.add(s.phaseName)
                lines.add("phase ${s.phaseIndex}/${s.phaseTotal}  ${s.stage}  ${s.remainingS} s")
                lines.add("total left ${s.totalRemainingS / 60}:${"%02d".format(s.totalRemainingS % 60)}")
            }
            else -> lines.add(s.message.ifEmpty { "Test not running" })
        }
        lines.add("Back = abort")
        textPaint.textSize = 3.6f * unit
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, textPaint.measureText(l))
        val boxW = maxW + 4 * unit
        val boxH = lines.size * 5.2f * unit + 2 * unit
        paint.color = Color.argb(200, 0, 0, 0)
        canvas.drawRect(w - boxW - 2 * unit, 2 * unit, w - 2 * unit, 2 * unit + boxH, paint)
        textPaint.color = Color.WHITE
        for ((i, l) in lines.withIndex()) {
            canvas.drawText(l, w - boxW, 2 * unit + (i + 1) * 5.2f * unit, textPaint)
        }
    }
}
