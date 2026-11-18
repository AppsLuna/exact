// A nuisance the ladder turns on, each in its own file so one can go without touching the others: delete its file and
// its line in [Nuisances.all]. A plan's rung naming a nuisance that is not here turns on nothing, and one the platform
// or the person does not allow now is skipped while the others keep their order. The ring and its notification are
// the engine's own (`NagService`, `Shade`), not nuisances.
package pinkoo.nag

import android.app.Notification
import android.content.Context

internal interface Nuisance {
    /** The plan's word for it, in a rung's `add`. */
    val word: String

    /** Whether the platform and the person allow it now (a permission, a special access, the platform's version). */
    fun allowed(c: Context): Boolean = true

    /** It needs the process kept while on, which the engine's foreground service does. */
    val holds: Boolean get() = false

    /** A swiped waiting notification comes back while it is on. */
    val returns: Boolean get() = false

    /** A promise turns it on, whatever the rung. */
    val promised: Boolean get() = false

    /** Its part of the nag notification, loud or waiting. */
    fun nag(c: Context, b: Notification.Builder, live: Live, loud: Boolean) {}

    /** Its part of a promise's notification. */
    fun promise(c: Context, b: Notification.Builder, live: Live) {}

    /** Its own part in the process, on or off; main thread. */
    fun apply(c: Context, live: Live, on: Boolean) {}
}

internal object Nuisances {
    val all: List<Nuisance> = listOf(FullScreen, Returning, Bubble, Unlock, LiveUpdate)

    /** Whether [n] is on for [live]: named by a live group's rung (or a promise turns it on), and allowed. */
    fun on(c: Context, n: Nuisance, live: Live): Boolean =
        (n.word in live.words || (n.promised && live.promised.isNotEmpty())) && n.allowed(c)

    fun nag(c: Context, b: Notification.Builder, live: Live, loud: Boolean) { for (n in all) if (on(c, n, live)) n.nag(c, b, live, loud) }

    fun promise(c: Context, b: Notification.Builder, live: Live) { for (n in all) if (on(c, n, live)) n.promise(c, b, live) }

    fun holding(c: Context, live: Live): Boolean = all.any { it.holds && on(c, it, live) }

    fun returning(c: Context, live: Live): Boolean = all.any { it.returns && on(c, it, live) }

    /** Each one's own part on or off for [live]. */
    fun apply(c: Context, live: Live) { for (n in all) n.apply(c, live, on(c, n, live)) }
}
