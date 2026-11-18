// The engine's one foreground service. It rings while a nag rings (the alarm sound on the alarm stream at the rung's
// share of the person's alarm volume, never changing it, and a vibration that lengthens with the rung) and ends that
// ring after the ladder's ring time as an ignore. It also keeps the process while a nuisance needs one, the bubble or
// opening at unlock. Its notification is the nag notification, so there is never a second one.
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

class NagService : Service() {
    companion object {
        @Volatile internal var running: NagService? = null
        /** The alarm receiver's wake lock, released once the service has started. */
        @Volatile internal var waking: PowerManager.WakeLock? = null

        /**
         * The service brought to [live]: started when something rings or a holding nuisance is on, told otherwise.
         * Returns whether it shows the nag notification, which the engine then leaves to it.
         */
        internal fun sync(c: Context, live: Live): Boolean {
            val need = live.ringing.isNotEmpty() || (live.groups.isNotEmpty() && Nuisances.holding(c, live))
            val s = running
            if (s != null) { if (need) s.update(live) else s.finish(live); return need }
            if (!need) return false
            return runCatching { c.startForegroundService(Intent(c, NagService::class.java)); true }
                .onFailure { android.util.Log.w("pinkoo.nag", "service: $it") }.getOrDefault(false)
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
    private val timeouts = HashMap<Int, Runnable>()
    private var foreground = false
    /** The latest start delivered: stopping with it ends the service only when no newer start is on its way. */
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = this
        lastStart = startId
        val live = Engine.live(this)
        // A service started with startForegroundService must enter the foreground, or the system ends the app.
        if (live == null || live.groups.isEmpty()) {
            enter(Notification.Builder(this, Shade.CH_WAITING).setSmallIcon(Shade.icon(this)).build())
            finish(live)
        } else update(live)
        waking?.let { if (it.isHeld) it.release() }
        waking = null
        return START_NOT_STICKY
    }

    private fun enter(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (exact) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
            startForeground(Shade.NAG, n, types)
        } else startForeground(Shade.NAG, n)
        foreground = true
    }

    internal fun update(live: Live) {
        enter(Shade.nag(this, live, loud = live.ringing.isNotEmpty()))
        ring(live)
        Nuisances.apply(this, live)
    }

    /** Nothing rings and nothing needs the process: the ring stops, the nag notification stays while something waits. */
    internal fun finish(live: Live?) {
        silence()
        if (live != null) Nuisances.apply(this, live)
        if (foreground) {
            val waiting = live != null && live.groups.isNotEmpty() && !(live.st.swiped == live.nag && !Nuisances.returning(this, live))
            if (waiting) {
                Shade.post(this, Shade.NAG, Shade.nag(this, live!!, loud = false))
                stopForeground(STOP_FOREGROUND_DETACH)
            } else stopForeground(STOP_FOREGROUND_REMOVE)
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
        // Each nag ends unanswered after the ring time, once.
        for (nag in live.ringing.map { it.nag }.distinct()) if (nag !in timeouts) {
            val end = Runnable { timeouts.remove(nag); Engine.ignore(this, nag, "timeout", System.currentTimeMillis()) }
            timeouts[nag] = end
            main.postDelayed(end, live.plan.ladder.ringSec * 1000L)
        }
        for (nag in timeouts.keys.toList()) if (live.ringing.none { it.nag == nag }) main.removeCallbacks(timeouts.remove(nag)!!)
    }

    /** The person's alarm sound on the alarm stream, which Do Not Disturb lets through by default, at [volume] of the
     * stream's level (`MediaPlayer.setVolume` scales this player alone). */
    private fun play(volume: Double) {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@NagService, uri)
                isLooping = true
                setVolume(volume.toFloat(), volume.toFloat())
                prepare()
                start()
            }
        }.onFailure { android.util.Log.w("pinkoo.nag", "ring: no alarm sound: $it") }.getOrNull()
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
        for (r in timeouts.values) main.removeCallbacks(r)
        timeouts.clear()
    }

    override fun onDestroy() {
        silence()
        if (running === this) running = null
        super.onDestroy()
    }
}
