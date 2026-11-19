// The bubble: the mascot in a small round window over other apps (`TYPE_APPLICATION_OVERLAY`, which the person allows in
// Settings as "Display over other apps"). It can be dragged and snaps to the nearer edge; a tap opens the nag route;
// dragged onto the close target it closes, which is the nag's ignore, until the next nag (kept in the state, so a
// process death does not bring it back). It hides whenever the app's activity is resumed, however it came to the front.
// It shows the face of the nag it opens. It moves only in short bursts, every 20 seconds while the screen is on, and asks
// for no frames otherwise.
package pinkoo.nag

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

internal object Bubble : Nuisance {
    override val word = "bubble"
    override val holds = true

    override fun allowed(c: Context): Boolean = Settings.canDrawOverlays(c)

    private var view: BubbleView? = null
    private var target: CloseTarget? = null
    private var last: Live? = null
    private var x = -1
    private var y = -1
    private val main = Handler(Looper.getMainLooper())
    private val wiggle = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (v.context.getSystemService(PowerManager::class.java).isInteractive && v.isAttachedToWindow) v.wiggle()
            main.postDelayed(this, 20_000)
        }
    }

    /** Whether the bubble's window is up. */
    val shown: Boolean get() = view != null

    override fun apply(c: Context, live: Live, on: Boolean) {
        last = live
        if (on && !Engine.appInFront && live.nag != live.st.bubbleClosed && live.groups.isNotEmpty()) show(c.applicationContext, live) else hide()
    }

    private fun wm(c: Context) = c.getSystemService(WindowManager::class.java)
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun params(c: Context, size: Int, gravity: Int) = WindowManager.LayoutParams(size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun show(c: Context, live: Live) {
        view?.let { it.paint(live); return }
        val size = dp(c, 64)
        val metrics = c.resources.displayMetrics
        if (x < 0) { x = metrics.widthPixels - size - dp(c, 8); y = metrics.heightPixels / 3 }
        val v = BubbleView(c, size).apply { paint(live) }
        val p = params(c, size, Gravity.TOP or Gravity.START).apply { x = this@Bubble.x; y = this@Bubble.y }
        runCatching { wm(c).addView(v, p) }.onFailure { android.util.Log.w("pinkoo.nag", "bubble: $it"); return }
        view = v
        main.postDelayed(wiggle, 1_500)
    }

    fun hide() {
        main.removeCallbacks(wiggle)
        view?.let { v -> runCatching { wm(v.context).removeView(v) } }
        view = null
        hideTarget()
    }

    private fun showTarget(c: Context) {
        if (target != null) return
        val size = dp(c, 56)
        val t = CloseTarget(c)
        val p = params(c, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { y = dp(c, 48) }
        if (runCatching { wm(c).addView(t, p) }.isSuccess) target = t
    }

    private fun hideTarget() { target?.let { t -> runCatching { wm(t.context).removeView(t) } }; target = null }

    private fun overTarget(c: Context, cx: Float, cy: Float): Boolean {
        val m = c.resources.displayMetrics
        return hypot(cx - m.widthPixels / 2f, cy - (m.heightPixels - dp(c, 76).toFloat())) < dp(c, 72)
    }

    private fun tapped(c: Context) {
        val live = last ?: return
        val lead = live.lead ?: return
        runCatching { c.startActivity(Shade.opening(c, live.plan, lead.group, "bubble")) }
    }

    private fun closed(c: Context) {
        val live = last ?: return
        hide()
        Engine.bubbleClosed(c, live.nag)
    }

    @SuppressLint("ViewConstructor")
    private class BubbleView(c: Context, val size: Int) : View(c) {
        var mascot: MascotDrawable? = null
            set(v) { field = v; invalidate() }
        /** The plan and rung drawn: a refresh that changes neither draws nothing. */
        private var drawn = -1L

        fun paint(live: Live) {
            val key = live.plan.made * 16 + live.face
            if (key != drawn) { drawn = key; mascot = MascotDrawable.of(live.plan, live.face) }
            contentDescription = live.plan.texts.bubble
        }
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1E3D7.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * c.resources.displayMetrics.density }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332B2233 }
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false
        private var anim: ValueAnimator? = null

        override fun onDraw(canvas: Canvas) {
            val r = size / 2f
            val d = resources.displayMetrics.density
            canvas.drawCircle(r, r + 2 * d, r - 3 * d, shadow)
            canvas.drawCircle(r, r, r - 3 * d, face)
            canvas.drawCircle(r, r, r - 3 * d, line)
            mascot?.let { val inset = (size * 0.16f).toInt(); it.setBounds(inset, inset, size - inset, size - inset); it.draw(canvas) }
        }

        fun wiggle() {
            val m = mascot ?: return
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).setDuration(1200).apply {
                addUpdateListener { a ->
                    val t = a.animatedFraction
                    m.tilt = (sin(t * 4 * PI) * 9 * (1 - t)).toFloat()
                    m.squash = (sin(t * PI) * 0.6f).toFloat().coerceAtLeast(0f) * (if (t < 0.5f) 1f else 0.5f)
                    invalidate()
                }
                start()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lp = layoutParams as? WindowManager.LayoutParams ?: return false
            val wm = context.getSystemService(WindowManager::class.java)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) { dragging = true; showTarget(context) }
                    if (dragging) {
                        lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(this, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideTarget()
                    if (!dragging) { if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); tapped(context) }; return true }
                    if (overTarget(context, e.rawX, e.rawY)) { closed(context); return true }
                    val width = resources.displayMetrics.widthPixels
                    val to = if (lp.x + size / 2 < width / 2) 0 else width - size
                    ValueAnimator.ofInt(lp.x, to).setDuration(180).apply {
                        addUpdateListener { lp.x = it.animatedValue as Int; if (isAttachedToWindow) runCatching { wm.updateViewLayout(this@BubbleView, lp) } }
                        start()
                    }
                    Bubble.x = to; Bubble.y = lp.y
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    /** Where a dragged bubble closes: a dark disc with a cross, drawn, not a glyph. */
    @SuppressLint("ViewConstructor")
    private class CloseTarget(c: Context) : View(c) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2B2233.toInt() }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3 * c.resources.displayMetrics.density
        }
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r, disc)
            val a = r * 0.36f
            canvas.drawLine(r - a, r - a, r + a, r + a, cross); canvas.drawLine(r + a, r - a, r - a, r + a, cross)
        }
    }

}
