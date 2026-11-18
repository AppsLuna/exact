// The engine's one foreground service. It rings while a nag rings (the alarm sound on the alarm stream at the rung's
// share of the person's alarm volume, never changing it, and a vibration that lengthens with the rung) and ends that
// ring at its ring's end as an ignore (`Rules.ringOut`; the alarm is set then too, and whichever comes first counts
// it). It also keeps the process while a nuisance needs one, the bubble or opening at unlock. Its notification is the
// nag notification, so there is never a second one.
//
// One service with both types, rather than one per job: it starts at the alarm, under the exact alarm's exemption
// from Android's background start limits, as `systemExempted` (an exact-alarm holder's; the ring) and `specialUse`
// (the hold, which has no time limit and no prerequisite). A hold that began after the ring in a second service would
// need a start from the background that Android 12 and later refuse.
package pinkoo.nag

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/**
 * What a ring plays, in order. Her alarm sound through the settings' default URI first: `MediaPlayer` plays it from the
 * ringtone cache in device-protected storage, so the ring sounds before the first unlock after a boot, when the sound's
 * own file (`content://media/…`) cannot be read; then that file. With her alarm sound set to None, her ringtone the
 * same way (decision 5), and the notification sound last.
 */
internal object Sounds {
    fun ring(c: Context): List<Uri> {
        fun set(type: Int) = runCatching { RingtoneManager.getActualDefaultRingtoneUri(c, type) }.getOrElse { Settings.System.DEFAULT_ALARM_ALERT_URI }
        val alarm = set(RingtoneManager.TYPE_ALARM)
        val ringtone = set(RingtoneManager.TYPE_RINGTONE)
        return (listOfNotNull(Settings.System.DEFAULT_ALARM_ALERT_URI.takeIf { alarm != null }, alarm,
            Settings.System.DEFAULT_RINGTONE_URI.takeIf { ringtone != null }, ringtone) + Settings.System.DEFAULT_NOTIFICATION_URI).distinct()
    }
}

class NagService : Service() {
    companion object {
        @Volatile internal var running: NagService? = null
        /** The alarm receiver's wake lock, released once the service has started. */
        @Volatile internal var waking: PowerManager.WakeLock? = null
        /** A start is on its way: the wake lock waits for it. */
        @Volatile internal var starting = false

        /** The alarm receiver's wake lock let go: the service started, or none will. */
        internal fun release() {
            waking?.let { if (it.isHeld) it.release() }
            waking = null
        }

        /** Whether [live] needs the service: something rings, or a holding nuisance is on. */
        internal fun needed(c: Context, live: Live): Boolean =
            live.ringing.isNotEmpty() || (live.groups.isNotEmpty() && Nuisances.holding(c, live))

        /**
         * The service brought to [live]: started when something rings or a holding nuisance is on, told otherwise.
         * Returns whether it shows the nag notification, which the engine then leaves to it.
         */
        internal fun sync(c: Context, live: Live): Boolean {
            val need = needed(c, live)
            val s = running
            if (s != null) { if (need) s.update(live) else s.finish(live); return need }
            if (!need) return false
            return runCatching { c.startForegroundService(Intent(c, NagService::class.java)); starting = true; true }
                .onFailure { android.util.Log.w("pinkoo.nag", "service: $it") }.getOrDefault(false)
        }

        /**
         * The person's alarm sound ([Sounds.ring]) on the alarm stream, which Do Not Disturb lets through by default,
         * looping at [volume] of the stream's level (`MediaPlayer.setVolume` scales this player alone).
         */
        internal fun player(c: Context, volume: Double): MediaPlayer? {
            for (uri in Sounds.ring(c)) {
                val p = MediaPlayer()
                try {
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    p.setDataSource(c, uri)
                    p.isLooping = true
                    p.setVolume(volume.toFloat(), volume.toFloat())
                    p.prepare()
                    p.start()
                    return p
                } catch (e: Exception) {
                    android.util.Log.w("pinkoo.nag", "ring: $uri: $e")
                    p.release()
                }
            }
            return null
        }

        /** The vibration at [rung]: longer pulses, shorter pauses as it climbs. */
        private fun pattern(rung: Int): LongArray = when (rung) {
            0 -> longArrayOf(0, 400, 800)
            1 -> longArrayOf(0, 600, 500)
            else -> longArrayOf(0, 900, 250)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playing = -1.0
    private var vibrating = -1
    /** The earliest ring's end it times, and its timer. */
    private var ringEndAt = 0L
    private val ringEnd = Runnable { val at = ringEndAt; ringEndAt = 0; Engine.ringOut(this, at) }
    private var foreground = false
    /** The latest start delivered: stopping with it ends the service only when no newer start is on its way. */
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = this
        lastStart = startId
        starting = false
        val live = runCatching { Engine.live(this) }.getOrNull()
        // A service started with startForegroundService must enter the foreground, or the system ends the app; and it
        // stops at once when nothing needs it any more (resolved, opened or ignored before it started).
        val shown = live != null && live.groups.isNotEmpty() &&
            runCatching { update(live); foreground }.onFailure { android.util.Log.e("pinkoo.nag", "service", it) }.getOrDefault(false)
        // A bare notification, which finish replaces with the waiting nag or takes away.
        if (!shown) enter(Shade.nagId(live?.nag ?: 0), Notification.Builder(this, Shade.CH_WAITING).setSmallIcon(Shade.icon(this)).build())
        if (live == null || !shown || !needed(this, live)) finish(live)
        release()
        return START_NOT_STICKY
    }

    /** In the foreground with [n] as [id]: a new id (a new loud nag) replaces the notification the service had. */
    private fun enter(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (exact) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
            startForeground(id, n, types)
        } else startForeground(id, n)
        foreground = true
    }

    /** The notification, the ring and the nuisances brought to [live]; a nuisance that fails stops neither the ring nor
     * the others. The notification is posted only when it changed, but always as the service enters the foreground,
     * which a start for the foreground must. */
    internal fun update(live: Live) {
        Shade.nag(this, live, loud = live.ringing.isNotEmpty(), force = !foreground) { id, n -> enter(id, n) }
        runCatching { ring(live) }.onFailure { android.util.Log.e("pinkoo.nag", "ring", it) }
        runCatching { Nuisances.apply(this, live) }.onFailure { android.util.Log.e("pinkoo.nag", "nuisances", it) }
    }

    /** Nothing rings and nothing needs the process: the ring stops, the nag notification stays while something waits. */
    internal fun finish(live: Live?) {
        silence()
        if (live != null) Nuisances.apply(this, live)
        if (foreground) {
            val waiting = live != null && live.groups.isNotEmpty() && !(live.st.swiped == live.nag && !Nuisances.returning(this, live))
            if (waiting) {
                Shade.nag(this, live!!, loud = false, force = true) { id, n -> Shade.notify(this, id, n) }
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                Shade.cancelNag(this)
            }
            foreground = false
        }
        running = null
        stopSelf(lastStart)
    }

    private fun ring(live: Live) {
        if (live.ringing.isEmpty() || "ring" !in live.ringing.flatMap { live.plan.words(Rules.rung(live.plan, it)) }) { silence(); return }
        val volume = live.plan.volume(live.ringingRung)
        if (player == null) play(volume) else if (volume != playing) player?.setVolume(volume.toFloat(), volume.toFloat())
        playing = volume
        if (vibrating != live.ringingRung) vibrate(live.ringingRung)
        // Each ring ends unanswered at its ring's end, on the uptime clock, which no change of the wall clock moves.
        val end = live.ringing.mapNotNull { Rules.ringEnd(live.plan, it) }.minOrNull() ?: return
        if (end != ringEndAt) {
            main.removeCallbacks(ringEnd)
            ringEndAt = end
            main.postDelayed(ringEnd, (end - Engine.clock()).coerceIn(0, live.plan.ladder.ringSec * 1000L))
        }
    }

    private fun play(volume: Double) {
        player = player(this, volume)
        if (player == null) android.util.Log.w("pinkoo.nag", "ring: no alarm sound")
    }

    private fun vibrate(rung: Int) {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrating = rung
        val effect = VibrationEffect.createWaveform(pattern(rung), 0)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    private fun silence() {
        player?.runCatching { stop(); release() }
        player = null
        playing = -1.0
        if (vibrating >= 0) getSystemService(Vibrator::class.java)?.cancel()
        vibrating = -1
        main.removeCallbacks(ringEnd)
        ringEndAt = 0
    }

    override fun onDestroy() {
        silence()
        if (running === this) running = null
        super.onDestroy()
    }
}
