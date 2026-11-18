// The notification that comes back: the waiting nag notification, swiped away, is posted again at once and silently.
// The swipe was the nag's one ignore if nothing ignored it before; a swipe after that counts nothing, so swiping is
// never a snooze.
package pinkoo.nag

internal object Returning : Nuisance {
    override val word = "returning"
    override val returns = true
}
