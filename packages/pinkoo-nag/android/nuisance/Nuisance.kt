// What a reminder adds besides its notification, each in its own file so one can go without touching the others:
// delete its file and its line in [Nuisances.all]. The steps are the core's (`Rules`, `Steps.kt`); the surface shows
// each as the [Post] says, skipping one the platform or the person does not allow now. The ring and the notifications
// are the engine's own (`NagService`, `Shade`), not nuisances.
package pinkoo.nag

import android.content.Context

internal interface Nuisance {
    /** The plan's word for it (`NagSteps`, `liveUpdate`). */
    val word: String

    /** Whether the platform and the person allow it now (a permission, a special access, the platform's version). */
    fun allowed(c: Context): Boolean = true

    /** It needs the process kept while it shows or waits, which the ring service does. */
    val holds: Boolean get() = false

    /** Whether it shows or waits now, holding the process. */
    val active: Boolean get() = false
}

internal object Nuisances {
    val all: List<Nuisance> = listOf(FullScreen, Bubble, Unlock, LiveUpdate)

    /** Whether some nuisance holds the process now (the bubble shows, the unlock is awaited). */
    fun holding(): Boolean = all.any { it.holds && it.active }
}
