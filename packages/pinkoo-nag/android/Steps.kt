// The engine's seam: what the decisions ([Rules], [Engine]) hand to what Android shows and plays ([Out], implemented by
// `AndroidOut.kt` over `Shade`, `FullScreen`, `Bubble`, `NagService` and `Unlock`). The core decides which step a group
// is at, what a post carries and when; the surface only shows it. The core's tests run on a fake [Out]; the surface's
// tests feed [Post] fixtures. `docs/exact.md` › The gentle reminder, items 1, 4, 9 to 12 and 17.
//
// The other way, the surface tells the core what she did, each a way into [Engine]: the reminder's delete intent
// (`NagReceiver.DISMISS`, `Engine.dismissed`), its "Agora não" (`NagReceiver.NOT_NOW`, `Engine.notNow`), the stop
// contract's every path by her ("Parar", the unlock, the power key, a swipe, the nag screen: `Engine.silenced`), the
// ring's 60 s cap (`Engine.ringOut`, the alarm's `timeout`), the bubble sent to the nest (`Engine.bubbleClosed`), and
// the nag screen showing a dose, at the unlock too (`Engine.opened`, from `nagScreen`; its "Agora não",
// `Engine.notNowKeys`).
package pinkoo.nag

/**
 * A dose time's steps, softest first. [NOTIFY] at the dose time; [AGAIN] (De novo, with the bubble when it is on);
 * [FULL] (Tela cheia, once per dose); [ALARM] ("Só se passar de tudo"; "Já na hora" folds into [NOTIFY]); then up to 3
 * [REPEAT]s; [DEADLINE] at a promise's deadline, which is not one of the repeats.
 */
enum class Step { NOTIFY, AGAIN, FULL, ALARM, REPEAT, DEADLINE }

/**
 * One reminder as the engine decided it, for the one reminder notification every open dose time shares.
 *
 * - [nag]: the post's number, new for every step that fires (the notification's id alternates by it, `Shade.nagId`), the
 *   same for the silent refreshes that follow it.
 * - [groups]: the firing dose times (`2026-11-18T08:00`), earliest first; for a refresh, those of the post showing.
 * - [step]: the step of the earliest firing group (the post's own; another group's step never adds anything to it).
 * - [keys], [names]: every open dose not under a promise and not swiped away, in the plan's order (Hoje's), and their
 *   names, distinct.
 * - [channel]: the sound of the earliest firing group's first dose (`NagDose.sound`: a preset's id, `vibrate`, `silent`,
 *   or a phone sound's canonical address), which names her reminder channel; the surface maps it to the channel's id.
 * - [sounding]: her sound plays with it, posted anew on her reminder channel. False for a refresh; for the alarm and a
 *   step [folded] into it (the ring is the only sound); for the full screen (silent over the lock screen: where the
 *   surface falls back to a heads-up, it sounds it); and in [front] (the app plays her sound itself).
 * - [fullScreen]: a firing group enters Tela cheia and its doses haven't had it, or a firing group rings the alarm (never
 *   in [front]). For Tela cheia the surface attaches the full-screen intent only while the phone is locked or dark, Do
 *   Not Disturb is off and the access is granted; otherwise it posts on her reminder channel, sounding. Either way the
 *   core has already counted the dose's full screen. For the alarm, every time it rings, it rides on the alarm's own
 *   notification whenever the access is granted (Android opens the nag route over a locked or dark phone and shows a
 *   heads-up to one in use; Do Not Disturb treats it as the alarm it is), and it counts as no Tela cheia.
 * - [alarm]: an alarm rings now (with exact access only): the post is the alarm's own notification (`pinkoo.nag`, public,
 *   "Parar"), with the full-screen intent when it is the fire that rings ([fullScreen]).
 * - [bubble]: some open group is inside its De novo step with Bolinha on and wasn't sent to the nest.
 * - [folded]: a step rides on the alarm's notification ("Já na hora", a promise's deadline for such a dose, another dose
 *   time's step while it rings): silent, so the ring is the only sound.
 * - [front]: it fired with the app in front (decision 17): the waiting channel, no heads-up, no full-screen intent, to its
 *   end. The app reads it in `nagState().front` and acts.
 * - [title], [line]: the plan's texts filled ("Hora de tomar: {names}", the waiting state's on a refresh) and the
 *   mascot's line for [rung] (the plan's deadline line at a promise's deadline).
 * - [rung]: the reminders that reached her before this one, up to 4: the mascot's face and lines.
 */
data class Post(val nag: Int, val groups: List<String>, val step: Step, val keys: List<String>, val names: List<String>,
    val channel: String, val sounding: Boolean, val fullScreen: Boolean, val alarm: Boolean, val bubble: Boolean,
    val folded: Boolean, val front: Boolean, val title: String, val line: String, val rung: Int)

/**
 * What the core asks of Android. After every change the core calls the reminder's one call ([post], [refresh] or
 * [clear]), then [bubble], [ring] or [stopRing], and [waitUnlock], with the state as it is: each must be idempotent.
 *
 * - [post]: a step fired: the reminder posted anew (the other id cancelled), sounding when [Post.sounding], with the
 *   full-screen intent or as the alarm's notification as the post says.
 * - [refresh]: the post showing, changed or not (its names, line, the waiting state, the alarm over): an in-place
 *   silent update (`setOnlyAlertOnce`) on her reminder channel, posted only if what it shows changed. Never called while
 *   she swiped it away.
 * - [clear]: no reminder shows: both ids gone ([nag] the last post's number). Called at every change with none.
 * - [ring]: the alarm rings for [Post] (her sound, the plan's alarm volume), in the ring service, up to its 60 s cap.
 *   Called again while it rings: the same post keeps ringing.
 * - [stopRing]: no alarm rings ([why] for the log: `step`, `timeout`, `stop`, `dismissed`, `notNow`, `opened`,
 *   `answered`, …). Called at every change while none rings.
 * - [bubble]: the bubble shows for [Post], or goes with null.
 * - [waitUnlock]: open the nag route at her first unlock until [until] (null: stop waiting).
 * - [confirm]: the notification's "Agora não" answered: [nag]'s post turns, silently, into [text] for 5 s, then goes;
 *   the core leaves the reminder alone meanwhile.
 */
interface Out { fun post(p: Post); fun refresh(p: Post); fun clear(nag: Int); fun ring(p: Post); fun stopRing(why: String)
    fun bubble(p: Post?); fun waitUnlock(until: Long?); fun confirm(nag: Int, text: String) }
