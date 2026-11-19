// Opening at every unlock: while a dose waits at this rung, each unlock (`ACTION_USER_PRESENT`, which a manifest cannot
// declare since Android 8, so it is registered here while on) opens the nag route. The start comes from the background,
// which Android allows an app holding "Display over other apps"
// (https://developer.android.com/guide/components/activities/background-starts), so without it this is skipped.
package pinkoo.nag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings

internal object Unlock : Nuisance {
    override val word = "unlock"
    override val holds = true

    override fun allowed(c: Context): Boolean = Settings.canDrawOverlays(c)

    private var receiver: BroadcastReceiver? = null
    private var last: Live? = null

    override fun apply(c: Context, live: Live, on: Boolean) {
        last = live
        val app = c.applicationContext
        if (on && receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val l = last ?: return
                    val lead = l.lead ?: return
                    if (Engine.appInFront) return
                    runCatching { app.startActivity(Shade.opening(app, l.plan, lead.group, "unlock")) }
                        .onFailure { android.util.Log.w("pinkoo.nag", "unlock: $it") }
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            // Exported: SystemUI's keyguard sends it, which an unexported receiver does not hear (measured on Android
            // 16). It is a protected broadcast, so no app can send it.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, filter)
            receiver = r
        } else if (!on && receiver != null) {
            runCatching { app.unregisterReceiver(receiver) }
            receiver = null
        }
    }
}
