// What runs with no session: the alarms, the reminder's "Agora não" and dismissal, the promise notification's dismissal
// (explicit broadcasts, so the fragment lists no actions for them), and the system's boot, clock, zone, update and
// exact-alarm broadcasts, and the surface's own (the alarm's and the test's "Parar", the test's waits). Both receivers
// are direct-boot aware and read only the device-protected store.
package pinkoo.nag

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class NagReceiver : BroadcastReceiver() {
    companion object {
        const val FIRE = "pinkoo.nag.FIRE"
        /** [FIRE]'s extra: the request code that fired ([Chain.STEP], [Chain.ALARM], [Chain.BACKUP]). */
        const val BY = "by"
        const val NOT_NOW = "pinkoo.nag.NOT_NOW"
        const val DISMISS = "pinkoo.nag.DISMISS"
        const val PROMISE_DISMISSED = "pinkoo.nag.PROMISE_DISMISSED"
    }

    override fun onReceive(c: Context, intent: Intent) {
        val nag = intent.getIntExtra("nag", 0)
        when (intent.action) {
            FIRE -> {
                // Held until the service this fire starts has started (it releases it), at most ten seconds; let go at
                // once when the fire starts none.
                val lock = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pinkoo:nag")
                lock.acquire(10_000)
                NagService.release()
                NagService.waking = lock
                Engine.fire(c, by = intent.getIntExtra(BY, Chain.STEP))
                if (!NagService.starting) NagService.release()
            }
            NOT_NOW -> Engine.notNow(c, nag)
            DISMISS -> Engine.dismissed(c, nag)
            PROMISE_DISMISSED -> Engine.promiseDismissed(c, intent.getLongExtra("at", 0))
            // The surface's own: the alarm's and the test's "Parar" and delete intent; the test's waits.
            AndroidOut.STOP -> AndroidOut.stopped(c, intent)
            Rehearsal.ACTION -> Rehearsal.fire(c, intent)
            Rehearsal.STOP -> Rehearsal.stopped(c, intent)
        }
    }
}

/** A boot (before and after the first unlock), the package replaced, the clock or the zone changed, the exact-alarm
 * access changed. Android 15 and later also send the boot broadcasts when an app leaves the stopped state, at its first
 * launch after a force-stop, which dropped every alarm. */
class NagSystemReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Engine.restore(c)
        }
    }
}
