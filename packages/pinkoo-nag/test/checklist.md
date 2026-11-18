# @pinkoo/nag on a phone

What the emulator drive (`android-drive.mjs`) cannot show. Run it on the phone the app is for (a Galaxy S24 Ultra,
One UI 8) with Pinkoo installed from a release build, with its ladder at Insistent (10 then 5 minutes) to keep it short.

1. **Sleeping apps.** Settings > Battery > Background usage limits: Pinkoo is not in "Sleeping" or "Deep sleeping".
   `requestNagAccess("sleeping")` opens "Never sleeping apps"; add Pinkoo there and check the panel says so.
2. **The full-screen nag on the lock screen.** Phone locked with a PIN, screen off: at the dose's time the nag route
   shows over the lock screen with the mascot and the medication's name, and the alarm plays.
3. **The lock screen's notification.** With the phone locked, the nag notification shows the public text, without the
   name. One UI 7 and later show lock-screen notifications as icons by default: check with "Show content" on too.
4. **Silent, vibrate and Do Not Disturb.** The ring plays on the alarm stream in each (DND lets alarms through by
   default); the vibration follows the rung.
5. **Swipe, bubble, unlock.** Swipe the loud nag: it comes back silent at once, and a second swipe before the next
   nag changes nothing. At the third rung the bubble shows over other apps and hides when Pinkoo is in front; dragging
   it onto the cross closes it until the next nag. At the fourth rung each unlock opens the nag route (One UI's
   background activity starts with "Display over other apps").
6. **The promise as a Live Update.** A pinky promise shows the deadline in the Now Bar and the status-bar chip, counting
   down. Dismissing it does not bring it back for that promise and counts no ignore.
7. **Reboot and force-stop.** Restart the phone mid-ladder: the next nag comes on time, before and after the first
   unlock. Force-stop Pinkoo in Settings: nothing nags until Pinkoo is opened; then an overdue nag rings at once.
8. **A week away.** With no plan sent for seven days, the notice asking to open Pinkoo shows.
