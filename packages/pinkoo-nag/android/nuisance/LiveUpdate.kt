// The promise's countdown as a Live Update (Android 16 QPR1, API 36.1): the status-bar chip and the lock screen show
// the deadline. The platform's rules: an ongoing notification with a title, not colorized, no custom views, on a channel
// above IMPORTANCE_MIN, from an app holding POST_PROMOTED_NOTIFICATIONS that the person has not turned off; one the
// person dismisses or demotes is not promoted again for that promise
// (https://developer.android.com/develop/ui/views/notifications/live-update).
package pinkoo.nag

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

internal object LiveUpdate : Nuisance {
    override val word = "liveUpdate"

    /** Whether this Android has Live Updates at all, before the person's switch. */
    fun platform(): Boolean = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

    override fun allowed(c: Context): Boolean =
        platform() && c.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /** The promise's notification asks to be promoted (the person may turn it off in Pinkoo's settings: the plan's
     * `liveUpdate`; the chip then shows the countdown alone). */
    fun promote(b: Notification.Builder) { if (platform()) b.setRequestPromotedOngoing(true) }
}
