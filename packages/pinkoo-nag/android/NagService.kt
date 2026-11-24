// The ring service: the engine's one foreground service, needed only while the alarm (or the experience test's alarm
// sample) rings, while the bubble shows, or while "Abrir quando eu desbloquear" waits for her unlock. Its foreground
// notification is its own, low and ongoing on the waiting channel ("Bolota está por aqui"), never the reminder, which
// would turn ongoing and unswipeable on the lock screen and change its flags at `stopForeground`.
//
// The alarm loops the dose's sound (the default preset when hers is "Só vibrar" or "Sem som", or can't be read, as a
// phone sound before the first unlock after a reboot) through a `MediaPlayer` with `USAGE_ALARM`, at the plan's alarm
// volume through the decibel curve ([Sounds.gain]), for up to 60 s. Pinkoo never changes the system volume.
//
// The stop contract, shared by the alarm and the test ([AndroidOut.stopped]): "Parar" and the alarm notification's
// delete intent (broadcasts), opening Pinkoo from it (the app's nag screen tells the core), the unlock
// (`ACTION_USER_PRESENT`), the power key (`ACTION_SCREEN_OFF`, and `ACTION_SCREEN_ON` once the ring has played about a
// second, so the post's own wake doesn't count), the route's touch and volume keys (the app), and each cap. The screen
// receivers exist only while a sound plays, so a screen turned off before it starts stops nothing. A ring whose post
// carries the full-screen intent ([Ring.wakes]) lets Android turn the screen on and open the nag route over the keyguard
// a little after it starts (later on a slow phone), and on a phone with no lock screen that wake is also an "unlock": so
// for such a ring neither the screen turning on nor the unlock counts in its first [WAKE_GRACE_MS]. The screen going off
// still stops it at once, and the route's first touch silences it.
//
// One service with both types: it starts at the alarm, under the exact alarm's exemption from Android's background
// start limits, as `systemExempted` (an exact-alarm holder's) and `specialUse` (the bubble and the unlock wait, which
// have no time limit). The bubble's window is added before it starts: from target 35 an app with "Aparecer por cima"
// may start a foreground service from the background only while one of its overlays shows.
package pinkoo.nag

import android.app.AlarmManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import kotlin.math.pow

/**
 * The sounds: the presets in `res/raw` (`pinkoo_<id>`, rendered by the app's script and shipped by Exact for channels
 * made at runtime), "Só vibrar", "Sem som", and the phone's own sounds by their canonical address.
 */
internal object Sounds {
    /** Sininho: the default, and what an alarm plays when hers can't sound. */
    const val DEFAULT = "sininho"

    /** A phone sound's address (`content://…`), as opposed to a preset's id, "vibrate" or "silent". */
    fun phone(sound: String): Boolean = "://" in sound

    /** A preset's raw resource address (`android.resource://<package>/raw/pinkoo_<id>`), or null when the app ships no
     * such file. Readable before the first unlock after a reboot: it lives in the APK. */
    fun preset(c: Context, id: String): Uri? {
        val name = "pinkoo_" + id.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (c.resources.getIdentifier(name, "raw", c.packageName) == 0) return null
        return Uri.parse("android.resource://${c.packageName}/raw/$name")
    }

    /** The raw resource id of a preset, or 0. */
    fun raw(c: Context, id: String): Int = c.resources.getIdentifier("pinkoo_" + id.lowercase().filter { it.isLetterOrDigit() || it == '_' }, "raw", c.packageName)

    /** What a reminder channel for [sound] plays: none for "Só vibrar" and "Sem som"; a preset's file (the platform's
     * notification sound where the app ships none); a phone sound's address. */
    fun notification(c: Context, sound: String): Uri? = when {
        sound == "vibrate" || sound == "silent" -> null
        phone(sound) -> Uri.parse(sound)
        else -> preset(c, sound) ?: preset(c, DEFAULT) ?: Settings.System.DEFAULT_NOTIFICATION_URI
    }

    /** What the alarm tries, in order: her sound, then the default preset (an alarm must sound), then the platform's
     * alarm where the app ships no preset. */
    fun ring(c: Context, sound: String): List<Uri> = listOfNotNull(
        if (phone(sound)) Uri.parse(sound) else if (sound != "vibrate" && sound != "silent") preset(c, sound) else null,
        preset(c, DEFAULT), Settings.System.DEFAULT_ALARM_ALERT_URI,
    ).distinct()

    /**
     * Her alarm volume, 20 to 100%, as the player's own volume through a decibel curve: 20% is −20 dB, 100% is 0 dB,
     * so each notch sounds like the same change (`MediaPlayer.setVolume` is a linear amplitude, and a straight share
     * would leave the slider's lower half nearly silent on a phone whose alarm stream is already low).
     */
    fun gain(share: Double): Float {
        val s = share.coerceIn(0.2, 1.0)
        return 10.0.pow((s - 1.0) * 25.0 / 20.0).toFloat()
    }

    /** Whether Pinkoo's own process can open [uri]: Android silently swaps a sound the app can't read for its default
     * in the posted notification, so a phone sound is refused at the pick when it can't. */
    fun readable(c: Context, uri: Uri): Boolean = runCatching {
        c.contentResolver.openInputStream(uri)?.use { it.read(); true } ?: false
    }.getOrDefault(false)

    /** A player for [sound] with [usage], looping or not, at [gain]; the default preset when hers can't be opened. */
    fun player(c: Context, sound: String, usage: Int, gain: Float, looping: Boolean): MediaPlayer? {
        for (uri in ring(c, sound)) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                p.setDataSource(c, uri)
                p.isLooping = looping
                p.setVolume(gain, gain)
                p.prepare()
                p.start()
                return p
            } catch (e: Exception) {
                android.util.Log.w("pinkoo.nag", "sound: $uri: $e")
                p.release()
            }
        }
        return null
    }

    /** The soft vibration a reminder brings, repeating while the alarm rings. */
    val PATTERN = longArrayOf(0, 400, 800)

    fun vibrate(c: Context, repeat: Boolean, usage: Int = VibrationAttributes.USAGE_ALARM) {
        val v = c.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(PATTERN, if (repeat) 0 else -1)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(usage))
        else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }
}

class NagService : Service() {
    companion object {
        /** The alarm rings for at most this long; the test's sample for [TEST_MS]. */
        const val RING_MS = 60_000L
        const val TEST_MS = 10_000L
        /** The power key's press that wakes a dark phone counts once the ring has played this long. */
        const val SCREEN_ON_AFTER_MS = 1_000L
        /** A ring that brings its full screen ([Ring.wakes]): the screen turning on and the unlock count only after this
         * long, so the full screen's own wake never stops it. */
        const val WAKE_GRACE_MS = 5_000L

        @Volatile internal var running: NagService? = null
        /** The alarm receiver's wake lock, released once the service has started. */
        @Volatile internal var waking: PowerManager.WakeLock? = null
        /** A start is on its way: the wake lock waits for it. */
        @Volatile internal var starting = false

        /** A ring asked for: the alarm's for [nag], or the experience test's sample ([test]), playing [sound] at the
         * share [volume] for at most [cap] ms; [wakes] when its notification carries the full-screen intent. */
        internal data class Ring(val nag: Int, val sound: String, val volume: Double, val cap: Long, val test: Boolean,
            val wakes: Boolean = false)

        /** The ring the surface asks for now, or null. */
        @Volatile internal var ring: Ring? = null
        /** The last alarm whose ring started: a ring plays once per nag, whatever asks again ([Out.ring] comes at every
         * refresh). */
        @Volatile internal var rang = 0
        /** The service notification's words ("Bolota está por aqui"). */
        @Volatile internal var words = ""

        /** The alarm receiver's wake lock let go: the service started, or none will. */
        internal fun release() {
            waking?.let { if (it.isHeld) it.release() }
            waking = null
        }

        /** Whether anything needs the service: a ring, the bubble, the unlock wait, a test that holds it. */
        internal fun needed(): Boolean = ring != null || Nuisances.holding() || Rehearsal.holding

        /** Whether the alarm rings for [nag] now. */
        internal fun ringing(nag: Int): Boolean = ring?.let { it.nag == nag && !it.test } == true

        /** The service brought to what is needed: started (from the alarm's exemption, or with an overlay showing), told,
         * or finished. Whether it runs. */
        internal fun sync(c: Context): Boolean {
            val need = needed()
            val s = running
            if (s != null) { if (need) s.update() else s.finish(); return need }
            if (!need) return false
            return runCatching { c.startForegroundService(Intent(c, NagService::class.java)); starting = true; true }
                .onFailure { android.util.Log.w("pinkoo.nag", "service: $it") }.getOrDefault(false)
        }

        /** The alarm stopped now, before the engine hears of it: it never starts again for that nag. */
        internal fun silence(c: Context) {
            ring?.let { if (!it.test) rang = it.nag }
            ring = null
            running?.quiet()
            sync(c)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    /** What plays now, and since when (uptime). */
    private var playing: Ring? = null
    private var playedSince = 0L
    private var vibrating = false
    private var stops: BroadcastReceiver? = null
    private val cap = Runnable { capped() }
    private var foreground = false
    /** The latest start delivered: stopping with it ends the service only when no newer start is on its way. */
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = this
        lastStart = startId
        starting = false
        // A service started with startForegroundService must enter the foreground, or the system ends the app; it stops
        // at once when nothing needs it any more (answered, opened or hidden before it started).
        runCatching { enter() }.onFailure { android.util.Log.e("pinkoo.nag", "service", it) }
        if (needed()) update() else finish()
        release()
        return START_NOT_STICKY
    }

    private fun enter() {
        if (foreground) return
        val n = Shade.service(this, words.ifEmpty { Store.plan(this)?.texts?.service ?: "" })
        if (Build.VERSION.SDK_INT >= 34) {
            val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (exact) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
            startForeground(Shade.SERVICE, n, types)
        } else startForeground(Shade.SERVICE, n)
        foreground = true
    }

    /** The ring brought to what is asked: started once per ring, or stopped. */
    internal fun update() {
        val r = ring
        if (r == null) { quiet(); return }
        if (playing == r) return
        quiet()
        play(r)
    }

    private fun play(r: Ring) {
        playing = r
        if (!r.test) rang = r.nag
        player = Sounds.player(this, r.sound, AudioAttributes.USAGE_ALARM, Sounds.gain(r.volume), looping = true)
        if (player == null) android.util.Log.w("pinkoo.nag", "ring: no sound")
        if (r.sound != "silent" && getSystemService(Vibrator::class.java)?.hasVibrator() == true) { Sounds.vibrate(this, repeat = true); vibrating = true }
        playedSince = SystemClock.uptimeMillis()
        listen()
        main.removeCallbacks(cap)
        main.postDelayed(cap, r.cap)
    }

    /** The stop contract's screen and unlock signals, only while a sound plays. */
    private fun listen() {
        if (stops != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = playing ?: return
                val played = SystemClock.uptimeMillis() - playedSince
                // The full screen's own wake (and, with no lock screen, its "unlock") never counts.
                val woken = now.wakes && played < WAKE_GRACE_MS
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> if (!woken) AndroidOut.stopped(this@NagService, now.nag, "unlock", now.test)
                    Intent.ACTION_SCREEN_OFF -> AndroidOut.stopped(this@NagService, now.nag, "power", now.test)
                    Intent.ACTION_SCREEN_ON -> if (!woken && played >= SCREEN_ON_AFTER_MS)
                        AndroidOut.stopped(this@NagService, now.nag, "power", now.test)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }
        // Exported: SystemUI sends the unlock, which an unexported receiver doesn't hear; all three are protected.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else registerReceiver(r, filter)
        stops = r
    }

    /** The cap: the alarm rang out unanswered (the core's `timeout`), or the test's sample ended. */
    private fun capped() {
        val r = playing ?: return
        AndroidOut.stopped(this, r.nag, "cap", r.test)
    }

    /** Nothing plays: the player, the vibration, the screen signals and the cap gone. */
    internal fun quiet() {
        player?.runCatching { stop(); release() }
        player = null
        playing = null
        if (vibrating) getSystemService(Vibrator::class.java)?.cancel()
        vibrating = false
        stops?.let { runCatching { unregisterReceiver(it) } }
        stops = null
        main.removeCallbacks(cap)
    }

    /** Nothing needs the process: the ring stops and the service's own notification goes with it. */
    internal fun finish() {
        quiet()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (running === this) running = null
        stopSelf(lastStart)
    }

    override fun onDestroy() {
        quiet()
        if (running === this) running = null
        super.onDestroy()
    }
}
