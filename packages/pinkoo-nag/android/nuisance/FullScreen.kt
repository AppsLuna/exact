// The full-screen nag: the nag route over the keyguard, the screen turned on, when the loud nag is posted. From Android
// 14 the person (or Play, for an app that is not a calling or alarm app) may take the access away; then the loud nag
// is a heads-up notification with the ring, as the platform shows a full-screen intent to someone using the phone.
// A nag that rang with the app in front has none (decision 17): the module opens the nag route in the app instead.
package pinkoo.nag

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

internal object FullScreen : Nuisance {
    override val word = "fullScreen"

    override fun allowed(c: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || runCatching { c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }.getOrDefault(false)

    override fun nag(c: Context, b: Notification.Builder, live: Live, loud: Boolean) {
        if (!loud || live.quiet) return
        val lead = live.lead ?: return
        val intent = Shade.opening(c, live.plan, lead.group, "fsi", overLock = true)
        b.setFullScreenIntent(PendingIntent.getActivity(c, 10, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
    }
}
