// Tela cheia: the nag route over the keyguard, the screen turned on, at most once per dose. Android launches a
// full-screen intent only for a notification newly posted, and opens it only over a locked or dark phone: with the
// phone in use it shows a heads-up instead, and with Do Not Disturb on it treats it as any notification. So the step
// posts with the intent only while the phone is locked or its screen off and Do Not Disturb is off ([now]); otherwise
// it is her reminder posted anew, sounding, as without the access. Either way the dose's full screen is spent (the
// core counts it). From Android 14 the person (or Play, for an app that is neither a calling nor an alarm app) may take
// the access away. In front it has none (decision 17): the app opens the nag route itself (`nagState().front`).
package pinkoo.nag

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

internal object FullScreen : Nuisance {
    override val word = "fullScreen"

    override fun allowed(c: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || runCatching { c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }.getOrDefault(false)

    /** Whether Android would open a full screen now: the phone locked or its screen off, and Do Not Disturb off. */
    fun now(c: Context): Boolean {
        val locked = c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val dark = c.getSystemService(PowerManager::class.java)?.isInteractive == false
        val filter = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val quiet = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return (locked || dark) && !quiet
    }
}
