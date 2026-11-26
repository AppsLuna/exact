// Bolinha, at De novo and at a promise's deadline: Bolota's face in a small round window over her apps
// (`TYPE_APPLICATION_OVERLAY`, which she allows as "Aparecer por cima"), plus a speech balloon in a second window,
// sized to its text, holding the step's line. It also comes with an alarm that rang without its full screen (no
// access), Bolinha on or off, so she finds the reminder after silencing it (`Group.standIn`, Glauco 2026-11-25).
//
// - The balloon is `FLAG_NOT_TOUCHABLE` and drawn at an alpha of 0.8 or less: Android 12's untrusted-touch rule passes
//   a touch through an overlay only when the windows over that point are that see-through (`InputManager`'s maximum
//   obscuring opacity), so it never swallows a tap meant for the app under it. Only the face takes taps.
// - The line shows in the brand font for about 6 s and the face wiggles once, both from the first moment the screen is
//   on and unlocked (overlays don't show over the keyguard); the balloon is a polite live region for TalkBack
//   (`View.announceForAccessibility` is deprecated in Android 16).
// - A tap on the face opens the nag route; dragging it into the nest (no X) sends it away for its step, kept by the
//   core across a process death. It lives one step: the core takes it away ([Out.bubble] with null).
// - It hides whenever the app's activity is resumed, however it came to the front. The window is added before the ring
//   service starts: from target 35 an app with "Aparecer por cima" may start a foreground service from the background
//   only while one of its overlays shows. It asks for no frames at rest.
package pinkoo.nag

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

internal object Bubble : Nuisance {
    override val word = "bubble"
    override val holds = true

    /** The balloon's alpha: at most 0.8, Android's maximum obscuring opacity for a touch to pass through. */
    const val BALLOON_ALPHA = 0.8f
    /** How long the balloon shows its line. */
    const val BALLOON_MS = 6_000L

    override fun allowed(c: Context): Boolean = Settings.canDrawOverlays(c)

    /**
     * What the bubble shows: [nag]'s face ([mascot], drawn when [drawn] changes) and its [line], with [label] for
     * TalkBack; [tapped] opens what a tap opens, and [nested] tells who asked that it went to the nest.
     */
    class Face(val nag: Int, val line: String, val label: String, val drawn: Long, val mascot: () -> MascotDrawable?,
        val tapped: (Context) -> Unit, val nested: (Context) -> Unit)

    private var face: FaceView? = null
    private var balloon: TextView? = null
    private var nest: NestView? = null
    private var showing: Face? = null
    /** The nag whose balloon and wiggle played: once per step. */
    private var greeted = 0
    private var watcher: Pair<Context, BroadcastReceiver>? = null
    private var x = -1
    private var y = -1
    private val main = Handler(Looper.getMainLooper())
    private val balloonGone = Runnable { hideBalloon() }

    /** Whether the face's window is up. */
    val shown: Boolean get() = face != null
    override val active: Boolean get() = shown
    /** Whether the balloon's window is up (its line showing). */
    val speaking: Boolean get() = balloon != null

    private fun wm(c: Context) = c.getSystemService(WindowManager::class.java)
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** The screen on and unlocked: overlays show, so the balloon and the wiggle may. */
    private fun seen(c: Context): Boolean = c.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    /** [f] shown (its window added first, before the service starts), or the bubble gone while the app is in front or
     * the access is missing. */
    fun show(c: Context, f: Face) {
        val app = c.applicationContext
        if (!allowed(app) || Engine.appInFront) return hide()
        showing = f
        val v = face ?: add(app) ?: return
        v.paint(f)
        if (greeted != f.nag) { if (seen(app)) greet(app, f) else watch(app) }
    }

    private fun add(c: Context): FaceView? {
        val size = dp(c, 64)
        val metrics = c.resources.displayMetrics
        if (x < 0) { x = metrics.widthPixels - size - dp(c, 8); y = metrics.heightPixels / 3 }
        val v = FaceView(c, size)
        val p = params(size, size, Gravity.TOP or Gravity.START, touchable = true).apply { x = this@Bubble.x; y = this@Bubble.y }
        if (runCatching { wm(c).addView(v, p) }.onFailure { android.util.Log.w("pinkoo.nag", "bubble: $it") }.isFailure) return null
        face = v
        return v
    }

    /** Gone, and its place and last greeting forgotten (the tests). */
    fun reset() { hide(); greeted = 0; x = -1; y = -1 }

    fun hide() {
        main.removeCallbacks(balloonGone)
        face?.let { v -> runCatching { wm(v.context).removeView(v) } }
        face = null
        hideBalloon()
        hideNest()
        unwatch()
        showing = null
    }

    private fun params(w: Int, h: Int, gravity: Int, touchable: Boolean) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    // MARK: the balloon and the wiggle, at the first moment she is in her apps

    /** Waits for the screen on and unlocked (`ACTION_USER_PRESENT`, or `ACTION_SCREEN_ON` with no keyguard). */
    private fun watch(c: Context) {
        if (watcher != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val f = showing ?: return unwatch()
                if (face != null && greeted != f.nag && seen(c)) greet(c, f)
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }
        // Exported: SystemUI's keyguard sends the unlock (an unexported receiver doesn't hear it, measured on Android 16);
        // both are protected broadcasts, so no app can send them.
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else c.registerReceiver(r, filter)
        watcher = c to r
    }

    private fun unwatch() {
        val (c, r) = watcher ?: return
        watcher = null
        runCatching { c.unregisterReceiver(r) }
    }

    private fun greet(c: Context, f: Face) {
        greeted = f.nag
        unwatch()
        face?.wiggle()
        if (f.line.isEmpty()) return
        hideBalloon()
        val tv = TextView(c).apply {
            text = f.line
            setTextColor(0xFF2B2233.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Fonts.brand(c)
            maxWidth = (c.resources.displayMetrics.widthPixels * 0.62f).toInt()
            val pad = dp(c, 12)
            setPadding(pad, dp(c, 9), pad, dp(c, 10))
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(c, 18).toFloat(); setStroke(dp(c, 1), 0xFFF1E3D7.toInt()) }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val size = dp(c, 64)
        val width = c.resources.displayMetrics.widthPixels
        // The face's place, read before `apply`: inside it a bare `x` is the new window's own (0), which put the balloon
        // off the screen's left edge (seen on the emulator: its frame at [-389,948][-16,1058]).
        val faceX = x
        val faceY = y
        val onRight = faceX + size / 2 >= width / 2
        val p = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or (if (onRight) Gravity.END else Gravity.START), touchable = false).apply {
            alpha = BALLOON_ALPHA
            // Beside the face, 6 dp from it: from the screen's right edge when the face sits on the right (END gravity).
            this.x = if (onRight) width - faceX + dp(c, 6) else faceX + size + dp(c, 6)
            this.y = faceY + dp(c, 8)
        }
        if (runCatching { wm(c).addView(tv, p) }.isFailure) return
        balloon = tv
        main.removeCallbacks(balloonGone)
        main.postDelayed(balloonGone, BALLOON_MS)
    }

    private fun hideBalloon() {
        main.removeCallbacks(balloonGone)
        balloon?.let { b -> runCatching { wm(b.context).removeView(b) } }
        balloon = null
    }

    // MARK: the nest

    private fun showNest(c: Context) {
        if (nest != null) return
        val size = dp(c, 72)
        val n = NestView(c)
        val p = params(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, touchable = false).apply { y = dp(c, 40) }
        if (runCatching { wm(c).addView(n, p) }.isSuccess) nest = n
    }

    private fun hideNest() { nest?.let { n -> runCatching { wm(n.context).removeView(n) } }; nest = null }

    private fun overNest(c: Context, cx: Float, cy: Float): Boolean {
        val m = c.resources.displayMetrics
        return hypot(cx - m.widthPixels / 2f, cy - (m.heightPixels - dp(c, 76).toFloat())) < dp(c, 76)
    }

    private fun tapped(c: Context) { showing?.tapped?.invoke(c) }

    private fun nested(c: Context) {
        val f = showing ?: return
        hide()
        f.nested(c)
    }

    @SuppressLint("ViewConstructor")
    private class FaceView(c: Context, val size: Int) : View(c) {
        var mascot: MascotDrawable? = null
            set(v) { field = v; invalidate() }
        /** What is drawn: a refresh that changes nothing draws nothing. */
        private var drawn = Long.MIN_VALUE

        fun paint(f: Face) {
            if (f.drawn != drawn) { drawn = f.drawn; mascot = f.mascot() }
            contentDescription = f.label
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1E3D7.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * c.resources.displayMetrics.density }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332B2233 }
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false
        private var anim: ValueAnimator? = null

        override fun onDraw(canvas: Canvas) {
            val r = size / 2f
            val d = resources.displayMetrics.density
            canvas.drawCircle(r, r + 2 * d, r - 3 * d, shadow)
            canvas.drawCircle(r, r, r - 3 * d, disc)
            canvas.drawCircle(r, r, r - 3 * d, line)
            mascot?.let { val inset = (size * 0.16f).toInt(); it.setBounds(inset, inset, size - inset, size - inset); it.draw(canvas) }
        }

        /** One wiggle and a little hop, as it arrives where she can see it. */
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
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) { dragging = true; hideBalloon(); showNest(context) }
                    if (dragging) {
                        lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(this, lp) }
                        nest?.near = overNest(context, e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideNest()
                    if (!dragging) { if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); tapped(context) }; return true }
                    if (overNest(context, e.rawX, e.rawY)) { nested(context); return true }
                    val width = resources.displayMetrics.widthPixels
                    val to = if (lp.x + size / 2 < width / 2) 0 else width - size
                    ValueAnimator.ofInt(lp.x, to).setDuration(180).apply {
                        addUpdateListener { lp.x = it.animatedValue as Int; if (isAttachedToWindow) runCatching { wm.updateViewLayout(this@FaceView, lp) } }
                        start()
                    }
                    Bubble.x = to; Bubble.y = lp.y
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    /** Where a dragged bubble goes to rest: a little nest of woven twigs on a cream disc, drawn, not a glyph. It leans
     * in when the face is over it. */
    @SuppressLint("ViewConstructor")
    private class NestView(c: Context) : View(c) {
        var near = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val d = c.resources.displayMetrics.density
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFF6EC.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1E3D7.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d }
        private val bowl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB98A5E.toInt() }
        private val twig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A5F3C.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.2f * d
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - 2 * d, disc)
            canvas.drawCircle(r, r, r - 2 * d, ring)
            val s = if (near) 1.12f else 1f
            canvas.save()
            canvas.scale(s, s, r, r)
            // The bowl: a half-ellipse under the middle, its rim a flatter ellipse.
            val w = r * 0.62f
            val top = r * 1.02f
            val path = Path().apply { arcTo(RectF(r - w, top - w * 0.55f, r + w, top + w * 0.55f), 0f, 180f, false); close() }
            canvas.drawPath(path, bowl)
            // Woven twigs across it, and two sticking out of the rim.
            for (i in 0 until 4) {
                val yy = top + w * 0.1f * i
                val half = w * (0.95f - 0.17f * i)
                canvas.drawLine(r - half, yy - w * 0.08f, r + half, yy + w * 0.08f, twig)
            }
            canvas.drawLine(r - w * 0.9f, top - w * 0.05f, r - w * 1.15f, top - w * 0.32f, twig)
            canvas.drawLine(r + w * 0.85f, top - w * 0.02f, r + w * 1.1f, top - w * 0.28f, twig)
            canvas.restore()
        }
    }
}

/** The brand font (Fredoka) for what the engine draws outside the app, read once from the app's assets; the system's
 * rounded bold where the app ships none. */
internal object Fonts {
    private var brand: Typeface? = null
    private var looked = false

    fun brand(c: Context): Typeface {
        if (!looked) {
            looked = true
            brand = runCatching { find(c, "", 0)?.let { Typeface.createFromAsset(c.assets, it) } }.getOrNull()
        }
        return brand ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    /** The first asset whose name starts with "Fredoka", at most three folders deep. */
    private fun find(c: Context, dir: String, depth: Int): String? {
        val names = c.assets.list(dir) ?: return null
        names.firstOrNull { it.startsWith("Fredoka") && (it.endsWith(".ttf") || it.endsWith(".otf")) }?.let { return if (dir.isEmpty()) it else "$dir/$it" }
        if (depth >= 3) return null
        for (n in names) if ('.' !in n) find(c, if (dir.isEmpty()) n else "$dir/$n", depth + 1)?.let { return it }
        return null
    }
}
