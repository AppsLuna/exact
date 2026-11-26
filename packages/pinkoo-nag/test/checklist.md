# @pinkoo/nag on a phone

What the emulator drive (`android-drive.mjs`, its twelve groups) cannot show. Run it on the phones the app is for, with
Pinkoo installed from a release build: the Galaxy A06 (One UI, Android 16) and her Galaxy S24 Ultra (One UI 8,
Android 17; her own script is `docs/s24-test.html`). Use Meu jeito at its shortest (5 minutes, then 5) to keep it short,
Bolinha, Tela cheia and "Abrir quando eu desbloquear" on, and the alarm "Só se passar de tudo" unless a step says
otherwise.

1. **Samsung's sleeping apps.** Settings > Battery > Background usage limits: Pinkoo is not in "Sleeping" or "Deep
   sleeping"; `requestNagAccess("sleeping")` opens "Never sleeping apps". Then the measure the emulator can't take:
   with Pinkoo untouched for a day (no launch, the phone left on battery overnight), each step comes at its minute. A
   step that comes late while the alarm comes on time means One UI holds exact while-idle alarms back: the engine then
   arms every step with the alarm clock on Samsung (`docs/architecture.md` › Nagging on the phone, Battery
   restricted). Note how late, and on which phone.
2. **The thread on the lock screen.** Phone locked with a PIN, screen off. At the dose time the notification sounds with
   her sound (the medication's, or Ajustes' default) and the screen stays dark. At De novo it sounds again, as a new
   notification. At Tela cheia the nag screen shows over the lock screen with the medication's name, silent, once:
   no later step brings it again. With "Abrir quando eu desbloquear", her first unlock after Tela cheia opens the nag
   screen, once (One UI's background start rides on "Aparecer por cima"). The alarm rings at its step, its
   notification naming no medication. Then the repeats, at most three, and nothing more until the dose is missed.
3. **What stops the alarm on a locked phone.** "Parar" on the lock screen, without unlocking (with "Show content" off
   too: the alarm's notification is public); the power key (the press that turns the screen on); unlocking. One UI 7
   and later show lock-screen notifications as icons by default: check with the notification card shown as well.
   Unlocked: a swipe on its notification, opening Pinkoo from it, and on Pinkoo's own screen a touch or a volume key.
   The 60 s cap. Nothing else stops it, and no copy promises more. Without full-screen access (Settings › Apps ›
   Pinkoo › full-screen notifications off) and with "Aparecer por cima", Bolinha and "Abrir quando eu desbloquear" off:
   the bubble comes with the ring, and the unlock that silences it opens the nag screen, once; the bubble stays until
   the next step. Without "Aparecer por cima" the ring comes with its notification alone (Glauco, 2026-11-25).
4. **The balloon on a slow phone (the A06).** At De novo, with WhatsApp open: the balloon shows Bolota's line for about
   6 s and her taps under it reach WhatsApp (Android 12's untrusted-touch rule passes them through a window drawn at
   0.8 or less); a tap on the face opens the nag screen; dragged into the nest it stays away for its step, even if One
   UI ends Pinkoo meanwhile. Locked at De novo, the balloon and its wiggle wait for the first unlocked moment.
5. **Swipe and "Agora não".** A swiped reminder stays away until the next step: opening Pinkoo, changing a setting or
   another dose's reminder never brings it back. "Agora não" from the notification turns it, silently, into "Combinado:
   te lembro de novo às {hora}." for 5 s; the next step comes at that time, never sooner than it was due.
6. **Silent, vibrate and Do Not Disturb.** The notification follows them (silent mode mutes its vibration too); the
   alarm passes them, unless Do Not Disturb blocks alarms (then Como eu te lembro's Alarme row card opens One UI's Do
   Not Disturb page, `requestNagAccess("dnd")`). Under Do Not Disturb, Tela cheia is the notification again, and nothing
   lights the room. In use, Tela cheia is a heads-up with her sound.
7. **The volumes.** Como eu te lembro shows the notification volume and the alarm volume as the phone has them, and
   "Ajustar no Android" (`requestNagAccess("volume", stream)`) shows One UI's own slider for each without changing it.
   On One UI the alarm's row may not show (its volume lives in Relógio): then the Relógio hint is the answer. The
   alarm's slider (20 to 100%) previews live and rings at the same level, through the decibel curve.
8. **Sounds.** Each preset and "Só vibrar" and "Sem som" as a medication's sound; one of the phone's sounds from
   Android's list (One UI's picker): a system sound works, one she added herself ("Adicionar") is refused with "Esse
   som não dá pra usar". After a reboot, before the first unlock, a phone sound's reminder plays Android's default and
   the alarm plays Sininho. Android 15's notification cooldown (One UI may have it on) never softens a step 5 minutes
   or more after the one before.
9. **Full screen on a store install.** From a Play internal-testing install, full-screen access starts denied (Android
   14 and later): Tela cheia is the notification again, and its row in Como eu te lembro says so in butter. Allow it
   in Settings and the next dose's Tela cheia opens over the lock screen. (The emulator simulates the denial with
   `appops`.)
10. **The promise as a Live Update.** A pinky promise shows the deadline in the Now Bar and the status-bar chip, counting
    down; nothing else comes until it. Dismissing it answers nothing and it doesn't come back for that promise. At the
    deadline everything but the alarm (Glauco, 2026-11-25): the notification with her sound, the bubble with Bolinha on,
    and the nag screen over the lock screen with Tela cheia on, even after the dose had its Tela cheia; never the alarm,
    also for a "Já na hora" medication.
11. **The experience test.** Como eu te lembro › "Sentir meu lembrete": each step she has on, locked and unlocked;
    "Testar sem som" on the "Só vibrar" channel; the full screen 5 s after the screen goes off; the alarm sample stops
    at 10 s, by "Parar", the unlock or the power key. Nothing is recorded.
12. **Reboot and force-stop.** Restart the phone mid-thread: the next step comes on time, before and after the first
    unlock. Force-stop Pinkoo in Settings: nothing comes until Pinkoo is opened; then an overdue step comes at once.
13. **Six days away.** With Pinkoo not opened for six days (the day before its seven days of reminders run out), the
    notice asking to open it shows.
14. **Notifications off, Pinkoo open.** Turn Pinkoo's notifications off in Settings, keep Pinkoo open on Today at a
    dose's time: the nag screen opens by itself with no sound and no notification. "Agora não" there moves nothing. In
    a form the pill shows, and the nag screen opens when she leaves the form. With Pinkoo in the background at that
    time, nothing opens, then or when she comes back.
15. **TalkBack.** With TalkBack on, the balloon's line is read once (a live region); Ajustes shows "Segurar é difícil
    pra mim" on, and one double tap seals the pinky on Today and on the nag screen. Check it from screenshots, never
    `uiautomator` (L87).
16. **The alarm after a late step.** No emulator delays one exact alarm on demand while another fires (group 8's rare
    bucket shows it only where Android holds code 0 back), so `EngineTest` proves the alarm on time after a step that
    fired 40 minutes late, with the steps it passed skipped. On a phone, note it if it happens: the alarm at its time,
    no reminder sound for the skipped steps.
