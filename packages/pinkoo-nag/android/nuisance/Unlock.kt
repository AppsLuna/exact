// "Abrir quando eu desbloquear", an extra under Tela cheia: from the full-screen step until her first unlock, the
// dose's answer or promise, or the next step (at most 2 h, the core's `until`), the nag route opens once at her unlock.
// The same wait follows an alarm that rang without its full screen (no access), the switch on or off: the unlock that
// silences it opens the nag screen (`Group.standIn`, Glauco 2026-11-25).
// `ACTION_USER_PRESENT` reaches only a receiver registered at runtime, and a cached app gets such broadcasts only once it
// leaves the cached state (Android 14 and later) and may be killed meanwhile, so the ring service holds the process
// for the wait. The start comes from the background, which Android allows an app holding "Display over other apps"
// (https://developer.android.com/guide/components/activities/background-starts), so without it nothing waits.
package pinkoo.nag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

internal object Unlock : Nuisance {
    override val word = "unlock"
    override val holds = true

    /** The longest it waits, whatever the core asks. */
    const val MAX_MS = 2 * 60 * 60_000L

    override fun allowed(c: Context): Boolean = Settings.canDrawOverlays(c)

    private var receiver: BroadcastReceiver? = null
    private var until: Long? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val over = Runnable { app?.let { stop(it) } }

    /** Waiting for her unlock now. */
    val waiting: Boolean get() = receiver != null
    override val active: Boolean get() = waiting

    /**
     * Wait for her unlock until [at] ([Out.waitUnlock]; null, a time past or no access: stop waiting), then open the
     * route [open] gives (the live reminder's lead dose time) once, and tell [opened], so the core marks the wait done
     * and no later refresh waits again for that step (the app's activity resuming is one). Idempotent: the core calls it
     * at every refresh.
     */
    fun wait(c: Context, at: Long?, now: Long, opened: (Context) -> Unit = {}, open: () -> Intent?) {
        val ctx = c.applicationContext
        if (at == null || at <= now || !allowed(ctx)) return stop(ctx)
        val end = minOf(at, now + MAX_MS)
        opener = open
        told = opened
        if (receiver != null && until == end) return
        app = ctx
        until = end
        main.removeCallbacks(over)
        main.postDelayed(over, end - now)
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Engine.appInFront) return
                val i = opener() ?: return
                stop(ctx)
                runCatching { ctx.startActivity(i) }.onFailure { android.util.Log.w("pinkoo.nag", "unlock: $it") }
                runCatching { told(ctx) }.onFailure { android.util.Log.w("pinkoo.nag", "unlock: $it") }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        // Exported: SystemUI's keyguard sends it, which an unexported receiver does not hear (measured on Android 16).
        // It is a protected broadcast, so no app can send it.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, filter)
        receiver = r
    }

    private var opener: () -> Intent? = { null }
    private var told: (Context) -> Unit = {}

    /** No more waiting: the receiver gone, and the service let go of the process unless something else holds it. */
    fun stop(c: Context) {
        main.removeCallbacks(over)
        until = null
        val r = receiver ?: return
        receiver = null
        runCatching { c.applicationContext.unregisterReceiver(r) }
        NagService.sync(c.applicationContext)
    }
}
