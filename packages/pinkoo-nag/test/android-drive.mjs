// The emulator half of android.mjs: the fixture installed and driven as a person would, with real launches (no agent),
// `uiautomator` for the app's painted controls and the shade's, and `adb` for the system's state. The fixture's ladder
// is one minute and one minute, its ring 20 seconds. Each step checks what the system says (`dumpsys` alarm, audio,
// window, notification, activity, batterystats) rather than what the app believes.
//
//  1. A dose about a minute out, the process killed, the ringer silent, the screen off and a PIN set: at its time the
//     full-screen nag shows over the keyguard, the alarm stream plays, and `dumpsys alarm` listed an alarm clock.
//  2. Unanswered for the ring time: one ignore (timeout), the notification waits silent, the next nag a minute on;
//     swiping it then counts nothing and it comes back.
//  3. The second nag, rung in the background (Android 17's audio hardening must not mute it), swiped: its ignore,
//     back at once silent; the third rung's bubble is an overlay window, hidden while the app is in front.
//  4. The third nag's "Not now": its ignore, with no activity started.
//  5. The fourth rung: an unlock opens the nag route, with no background activity start blocked; with the bubble
//     closed too, when the start rests on the overlay access alone.
//  6. A promise: the promise notification counts down, the next alarm is its deadline; on 36.1 and later it is a
//     Live Update (promoted). While promised no service runs and an unlock opens nothing; at the deadline it rings at
//     the same rung (decision 4), the bubble and the opening at an unlock back.
//  7. Taken: no notification, service, overlay or nag alarm left.
//  8. A reboot mid-ladder, locked with a PIN: the alarm is set again at boot and the next nag rings, audibly, before
//     the first unlock (the sound's own file is in credential-encrypted storage then).
//  9. A force-stop: nothing until the app is launched; then the overdue nag rings at once.
// 10. Two doses a day and nothing else, in forced deep Doze: the app's process never starts, no wakeup, no wakelock.
// 11. No exact-alarm access (Android 14 and later's default): the inexact alarm gives no start of the service, so the
//     nag sounds itself, on the ring channel; the alarm at its ring's end still counts its one ignore, and the ladder
//     climbs.
// 12. Every nag its own full-screen nag: the first and the second over the keyguard (a new notification each). Then a
//     process the alarm started, with no activity: the bubble hides over the app the waiting notification opens (the
//     module is made after that resume), and a pause and a resume post nothing again. The bubble closed on its cross
//     and the process gone: a new process keeps it closed until the next nag brings it back.
//
// The emulator is left as found: the fixture uninstalled, the ringer normal, no PIN, the battery and Doze reset.
//
// For iterating only (android.mjs's `--only` and `--fast`): `only` runs the listed steps, where 1 is steps 1 to 7
// (they build on each other) and 8 to 12 stand alone; `fast` keeps the 1/1-minute ladder but rings 10 seconds instead
// of 20 (the buttons "Plan fast" and "Soon fast"), plans step 1's dose 30 to 90 s out instead of 60 to 120, and holds
// forced Doze one minute instead of five. Neither replaces the whole drive with the fixture's own timings: work that
// touches the engine is finished only after `android.mjs --emulator <serial>` with neither flag passes.
import { resolve } from 'node:path';

const wait = (ms) => new Promise((done) => setTimeout(done, ms));

export async function drive({ root, apk, id, emulator, check, run, out, only = null, fast = false }) {
  // The fixture's buttons: its own timings, or the fast ones.
  const PLAN = fast ? 'Plan fast' : 'Plan', SOON = fast ? 'Soon fast' : 'Soon';
  const adb = (...a) => run('adb', ['-s', emulator, ...a], root, process.env, true);
  const sh = (cmd) => adb('shell', cmd);
  const sdk = Number(sh('getprop ro.build.version.sdk').trim());
  const sdkFull = sh('getprop ro.build.version.sdk_full').trim();
  const shot = (file) => out && run('sh', ['-c', `adb -s ${emulator} exec-out screencap -p > '${resolve(out, file)}'`], root, process.env, true);
  const save = (file, text) => out && Bun.write(resolve(out, file), text);
  const until = async (f, ms, step = 500) => { const end = Date.now() + ms; let v; while (Date.now() < end) { if ((v = await f())) return v; await wait(step); } return v; };
  const step = (n) => !only || only.includes(n);

  /** The nodes on screen, from `uiautomator`: each one's text, description and centre. */
  const nodes = () => {
    sh('uiautomator dump /sdcard/nag-ui.xml >/dev/null 2>&1');
    const xml = sh('cat /sdcard/nag-ui.xml');
    return [...xml.matchAll(/<node [^>]*>/g)].map((m) => {
      const n = m[0], b = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
      const attr = (k) => (n.match(new RegExp(`${k}="([^"]*)"`)) ?? [])[1] ?? '';
      return { text: attr('text'), desc: attr('content-desc'), pkg: attr('package'), x: b ? (+b[1] + +b[3]) >> 1 : 0, y: b ? (+b[2] + +b[4]) >> 1 : 0, b: b?.slice(1).map(Number) };
    });
  };
  const find = (label) => nodes().find((n) => (typeof label === 'string' ? n.text === label || n.desc === label : label.test(n.text) || label.test(n.desc)));
  const press = async (label, ms = 15000) => {
    const n = await until(() => find(label), ms, 700);
    if (n) sh(`input tap ${n.x} ${n.y}`);
    return !!n;
  };
  /** The fixture's line `d.state:ignores:rung` for its dose. */
  const doses = async (want, ms = 15000) => (await until(() => { const n = find(/^(upcoming|ringing|pending|promised|resolved|lapsed):\d+:\d+/); return n && want(n.text) ? n.text : null; }, ms, 700)) ?? find(/^(upcoming|ringing|pending|promised|resolved|lapsed):/)?.text;
  /** The app in front at its home screen, whatever route it was left on (a link's location, as any app may send). */
  const launch = (url = '/') => sh(`am start -W -a android.intent.action.VIEW -d '${url}' -n ${id}/dev.exact.app.MainActivity`);
  const home = () => sh('input keyevent KEYCODE_HOME');
  const alarm = () => sh('dumpsys alarm');
  /** Our alarm clock's trigger time, from `dumpsys alarm`'s pending list. */
  const armed = () => {
    // The pending list: `RTC_WAKEUP #n: Alarm{… <package>}`, then its `tag=*walarm*:pinkoo.nag.FIRE`, `origWhen=…`, `Alarm clock:`.
    const lines = alarm().split('\n');
    const i = lines.findIndex((l, k) => l.includes('pinkoo.nag.FIRE') && (lines[k - 1] ?? '').includes(id));
    if (i < 0) return null;
    const block = lines.slice(i - 1, i + 8).join('\n');
    return { clock: /Alarm clock:|alarmClock=|AlarmClock/i.test(block), at: (block.match(/origWhen=([0-9-]+ [0-9:.]+)/) ?? [])[1] ?? null, block };
  };
  const services = () => sh(`dumpsys activity services ${id}`);
  const notifications = () => {
    const all = sh('dumpsys notification --noredact');
    return all.split(/\n(?=\s*NotificationRecord\()/).filter((r) => r.includes(`pkg=${id}`));
  };
  // The engine's notification ids (`Shade`): the nag (two, one loud nag to the next), the promise.
  const NAGS = ['id=7233792 ', 'id=7233796 '], PROMISE = 'id=7233793 ';
  const nagShown = () => notifications().find((r) => NAGS.some((n) => r.includes(n)));
  const promiseShown = () => notifications().find((r) => r.includes(PROMISE));
  const channelOf = (r) => (r?.match(/channel=([\w.]+)/) ?? [])[1] ?? (r?.match(/mChannelId=([\w.]+)/) ?? [])[1] ?? '';
  /** The notification's text: the mascot's line of its rung (the fixture has one line on each rung from 1). */
  const textOf = (r) => (r?.match(/android\.text=String \((.*)\)\s*$/m) ?? [])[1] ?? null;
  const LINE3 = 'Every unlock, until you do.';
  let uid = '?';
  /** One of our players on the alarm stream, started (`dumpsys audio`'s playback configurations). */
  const playing = () => sh('dumpsys audio').split('\n').some((l) => l.includes('AudioPlaybackConfiguration') && l.includes(`u/pid:${uid}/`) && /state:started/.test(l) && /USAGE_ALARM/.test(l));
  const overlay = () => sh('dumpsys window windows').split('\n').some((l) => l.includes(id) && /APPLICATION_OVERLAY|ty=2038|type=2038/.test(l))
    || new RegExp(`Window\\{[^}]*${id.replaceAll('.', '\\.')}[^}]*\\}[\\s\\S]{0,1500}ty=APPLICATION_OVERLAY`).test(sh('dumpsys window windows'));
  const top = () => (sh('dumpsys activity activities').match(/topResumedActivity=.*|mResumedActivity: .*/) ?? [''])[0];
  /** The bubble's window frame's centre, from `dumpsys window`. */
  const bubbleAt = () => {
    const block = sh('dumpsys window windows').split(/\n(?=\s*Window #)/).find((b) => b.includes(id) && /ty=APPLICATION_OVERLAY/.test(b));
    const f = block?.match(/ frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    return f ? { x: (+f[1] + +f[3]) >> 1, y: (+f[2] + +f[4]) >> 1 } : null;
  };
  const pid = () => sh(`pidof ${id}`).trim();
  const minuteOf = (text) => text?.match(/T(\d\d):(\d\d)/);
  const msTo = (hhmm, extra = 0) => {
    const now = new Date(Number(sh('date +%s').trim()) * 1000);
    const t = new Date(now); t.setHours(+hhmm[1], +hhmm[2], 0, 0);
    if (t < now - 60_000) t.setDate(t.getDate() + 1);
    return t - now + extra;
  };
  const plan = async (button = 'Plan') => {
    await press(button);
    const p = await until(() => find(/^\d{4}-\d\d-\d\dT\d\d:\d\d/), 15000, 700);
    return p?.text ?? null;
  };
  /** Our notification titled [label] swiped away in the shade; a swipe the shade took for a scroll is tried again. */
  const swipeOurs = async (label) => {
    // A record's creation time: the engine's repost after a swipe is a new record.
    const created = () => (nagShown()?.match(/mCreationTimeMs=(\d+)/) ?? [])[1] ?? null;
    const before = created();
    for (let attempt = 0; attempt < 3; attempt++) {
      sh('cmd statusbar expand-notifications');
      await wait(1200);
      const n = await until(() => nodes().find((x) => (x.text === label || x.desc?.includes(label)) && x.pkg === 'com.android.systemui'), 6000, 700);
      if (n) sh(`input swipe ${n.x} ${n.y} ${n.x + 900} ${n.y} 300`);
      await wait(1200);
      sh('cmd statusbar collapse');
      if (!n) return false;
      // Swiped: the notification was removed, and the engine posts its waiting one in its place.
      if (created() !== before) return true;
    }
    return false;
  };
  const pressInShade = async (label) => {
    sh('cmd statusbar expand-notifications');
    await wait(1200);
    // Expand our notification so its actions show.
    const row = await until(() => find(/^Time for Pill A/), 6000, 700);
    if (row) { sh(`input swipe ${row.x} ${row.y} ${row.x} ${row.y + 300} 200`); await wait(800); }
    const ok = await press(label, 6000);
    await wait(800);
    sh('cmd statusbar collapse');
    return ok;
  };
  const logcat = () => adb('logcat', '-d', '-v', 'time');

  // Setup: the fixture installed with its runtime permission, exact alarms, full-screen intents and the overlay allowed.
  adb('uninstall', id);
  adb('install', '-r', '-g', apk);
  uid = (sh(`cmd package list packages -U ${id}`).match(/uid:(\d+)/) ?? [])[1] ?? '?';
  sh(`cmd appops set ${id} SCHEDULE_EXACT_ALARM allow`);
  sh(`cmd appops set ${id} SYSTEM_ALERT_WINDOW allow`);
  if (sdk >= 34) sh(`cmd appops set ${id} USE_FULL_SCREEN_INTENT allow`);
  sh('cmd audio set-ringer-mode NORMAL');
  sh('input keyevent KEYCODE_WAKEUP');
  sh('wm dismiss-keyguard');
  adb('logcat', '-c');
  launch();
  const status = await until(() => find(/^true granted exact=/), 30000, 1000);
  check('android', 'status: granted, exact alarms, full-screen and overlay allowed', /exact=true fsi=true overlay=true/.test(status?.text ?? ''), status?.text);
  check('android', `Live Update availability on ${sdkFull}`, /promoted=(granted|denied|unavailable)/.test(status?.text ?? ''), status?.text);

  // The lock screen as found (an emulator often has none), restored at the end.
  const lockDisabled = sh('locksettings get-disabled').trim();
  let pinSet = false;
  try {
    if (step(1)) {
      // 1. Killed, silent, asleep, locked with a PIN: the full-screen nag over the keyguard, the alarm stream playing.
      const group = await plan(PLAN);
      const a = armed();
      check('android', 'the plan sets one alarm clock at the dose', !!a?.clock && !!group, a?.block ?? alarm().slice(0, 400));
      save('dumpsys-alarm-plan.txt', a?.block ?? '');
      home();
      // The process ended as the system ends one in the background: `am stop-app` (Android 14) keeps the alarms that
      // `force-stop` drops; `am kill` spares the previous app.
      await until(() => { sh(sdk >= 34 ? `am stop-app ${id}` : `am kill ${id}`); return !pid(); }, 15000, 1500);
      sh('locksettings set-pin 1234');
      pinSet = true;
      sh('cmd audio set-ringer-mode SILENT');
      sh('input keyevent KEYCODE_SLEEP');
      check('android', 'the process is gone before the nag', (await until(() => !pid(), 5000)) && !pid(), pid());
      await wait(Math.max(0, msTo(minuteOf(group), 4000)));
      // The ring first: it lasts the fixture's 20 seconds.
      check('android', 'the alarm stream plays though the ringer is silent', await until(() => playing(), 8000), null);
      check('android', 'the ring runs in the foreground service', /NagService[\s\S]*isForeground=true/.test(services()), services().slice(0, 500));
      // The app's activity, started through its unexported alias.
      const fsi = await until(() => /MainActivity|\.Notify/.test(top()) && /Awake/.test(sh('dumpsys power | grep mWakefulness=')), 10000);
      shot('01-fsi-over-keyguard.png');
      const keyguard = sh('dumpsys window | grep -iE "mShowingLockscreen|KeyguardShowing|isKeyguardShowing|mOccluded|occluded" | head -8');
      save('01-keyguard.txt', keyguard + '\n' + top());
      check('android', 'the full-screen nag shows over the keyguard with the screen on', !!fsi && /true/i.test(keyguard), `${top()} ${keyguard}`);
      const log = logcat();
      save('01-logcat.txt', log.split('\n').filter((l) => /pinkoo|nagfixture|nagfix|FGS|Background started/i.test(l)).join('\n'));
      check('android', 'the alarm started the process and allowed the service', /Start proc .*for broadcast/.test(log) && /ALARM_MANAGER_ALARM_CLOCK|Background started FGS: Allowed/.test(log), null);

      // 2. Unanswered: one ignore, the notification silent, the next nag a minute from the timeout.
      const quiet = await until(() => !playing(), 30000, 1000);
      check('android', 'the ring ends after its ring time', !!quiet, null);
      await wait(1500);
      check('android', 'the nag notification stays, now on the waiting channel', /waiting/.test(channelOf(nagShown())), nagShown()?.slice(0, 400));
      sh('locksettings clear --old 1234');
      pinSet = false;
      sh('cmd audio set-ringer-mode NORMAL');
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      await wait(1000);
      // Swiping the waiting notification of a nag already ignored counts nothing, and it comes back.
      const swiped = await swipeOurs('Pill A is waiting');
      await wait(1500);
      check('android', 'the waiting notification swiped comes back', swiped && !!nagShown(), null);
      launch();
      const one = await doses((t) => /^pending:1:1$/.test(t));
      check('android', 'one ignore for the first nag: pending, 1 ignore, rung 1', one === 'pending:1:1', one);
      check('android', 'the events: fired, then one ignore', !!find(/^fired,ignored$/), find(/^(fired|ignored|opened|lapsed)/)?.text);
      home();

      // 3. The second nag (rung 1) swiped while ringing: its ignore, back silent; rung 2 shows the bubble.
      const ringing = await until(() => playing(), 90000, 1000);
      check('android', 'the second nag rings a minute after the ignore', !!ringing, null);
      shot('02-second-nag.png');
      // Android 17's background audio hardening (Android 16 only logs what it would mute): this ring starts with the app
      // in the background and its activity not shown.
      const audio = sh('dumpsys audio');
      const player = audio.split('\n').find((l) => l.includes('AudioPlaybackConfiguration') && l.includes(`u/pid:${uid}/`) && /state:started/.test(l)) ?? '';
      const hardening = audio.split('\n').filter((l) => /AudioHardening/.test(l) && l.includes(id));
      save('03-audio-hardening.txt', [player, ...hardening].join('\n'));
      check('android', `the background ring is not muted on ${sdkFull} (${hardening.length ? hardening.at(-1).replace(/^.*AudioHardening\s*/, '') : 'no hardening log'})`,
        /mutedState:none/.test(player) && !hardening.some((l) => /muted/.test(l) && !/would be muted/.test(l)), player);
      const swipedLoud = await swipeOurs('Time for Pill A');
      check('android', 'the loud nag swiped', swipedLoud, null);
      check('android', 'its ring stops', await until(() => !playing(), 8000), null);
      check('android', 'and the notification is back at once, silent', await until(() => /waiting/.test(channelOf(nagShown())), 5000), nagShown()?.slice(0, 300));
      const bubble = await until(() => overlay(), 8000);
      shot('03-bubble.png');
      check('android', 'rung 2: the bubble is an overlay window', !!bubble, sh(`dumpsys window windows | grep -i ${id} | head -5`));
      check('android', 'and the service holds the process', /NagService[\s\S]*isForeground=true/.test(services()), null);
      launch();
      check('android', 'the bubble hides while the app is in front', await until(() => !overlay(), 6000), null);
      home();
      check('android', 'and comes back when it leaves', await until(() => overlay(), 6000), null);

      // 4. The third nag's "Not now": its ignore, no activity started.
      await until(() => playing(), 90000, 1000);
      const before = top();
      const notNow = await pressInShade('Not now');
      check('android', '"Not now" pressed in the shade', notNow, null);
      check('android', 'it ends the ring', await until(() => !playing(), 8000), null);
      check('android', 'and opens nothing', !top().includes(id) || top() === before, top());

      // 5. Rung 3: every unlock opens the nag route; Android lets a holder of the overlay access start it. A swipe lock
      // screen, so that waking the phone is an unlock (`ACTION_USER_PRESENT` comes as the keyguard goes).
      home();
      sh('locksettings set-disabled false');
      await wait(1000);
      adb('logcat', '-c');
      sh('input keyevent KEYCODE_SLEEP');
      await wait(1500);
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      const opened = await until(() => /MainActivity|\.Notify/.test(top()) && find(/via=unlock/), 12000, 1000);
      shot('04-unlock.png');
      const blocked = /Background activity launch blocked|Abort background activity starts|BAL_BLOCK/i.test(logcat());
      check('android', 'an unlock opens the nag route', !!opened, top());
      check('android', 'with no background activity start blocked', !blocked, null);
      // The bubble dragged onto its cross: no window of the app shows, and the unlock's start rests on the overlay
      // access alone.
      home();
      const at = await until(() => bubbleAt(), 8000, 700);
      const [w, h] = (sh('wm size').match(/(\d+)x(\d+)/) ?? [0, 1080, 2400]).slice(1).map(Number);
      if (at) sh(`input swipe ${at.x} ${at.y} ${w >> 1} ${h - Math.round(h / 12)} 1500`);
      check('android', 'the bubble closed on its cross', !!at && (await until(() => !overlay(), 5000)), at);
      adb('logcat', '-c');
      sh('input keyevent KEYCODE_SLEEP');
      await wait(1500);
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      const reopened = await until(() => /MainActivity|\.Notify/.test(top()), 12000, 1000);
      const log5 = logcat();
      save('05-unlock-bubble-closed.txt', log5.split('\n').filter((l) => /BAL_|Background activity|Notify/.test(l)).join('\n'));
      check('android', 'with the bubble closed, an unlock still opens the nag route', !!reopened && !/Background activity launch blocked|Abort background activity starts|BAL_BLOCK/i.test(log5), top());

      // 6. A promise: the countdown, the next alarm its deadline, a Live Update on 36.1 and later.
      await press('Later');
      await wait(2000);
      const promised = await until(() => promiseShown(), 8000);
      save('06-promise-notification.txt', promised ?? notifications().join('\n----\n'));
      check('android', 'the promise posts its countdown notification', !!promised && /chronometerCountDown=[^\n]*true/.test(promised), promised?.slice(0, 600));
      check('android', 'and the nag notification is gone while promised', !nagShown(), null);
      check('android', 'the next alarm is the deadline', !!armed()?.clock, armed()?.block);
      const promoted = /FLAG_PROMOTED_ONGOING|promoted=true|PROMOTED_ONGOING|isPromoted/i.test(sh('dumpsys notification --noredact'));
      if (sdk >= 36 && sdkFull !== '36.0') check('android', 'the promise is a Live Update', promoted, null);
      else check('android', 'no Live Update on this image (36.0)', !promoted, null);
      shot('06-promise.png');
      // Decision 4: while promised the opening at an unlock pauses, and nothing holds the process. (The bubble, closed
      // on its cross in step 5, stays closed until the next nag anyway: its pause is NuisanceTest's.)
      home();
      await wait(3000);
      check('android', 'promised: no service holds the process', !/NagService/.test(services()), services().slice(0, 300));
      sh('input keyevent KEYCODE_SLEEP');
      await wait(1500);
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      await wait(4000);
      check('android', 'and an unlock opens nothing', !/MainActivity|\.Notify/.test(top()), top());
      // At its deadline it nags again at the same rung: a promise never resets the ladder.
      check('android', 'at the deadline it rings again', !!(await until(() => playing(), 100_000, 1000)), null);
      check('android', 'at the same rung, 3: its line', (await until(() => textOf(nagShown()) === LINE3, 8000)) === true, textOf(nagShown()));
      check('android', 'and the bubble is back', !!(await until(() => overlay(), 8000)), null);
      // Once it rang out, from the home screen (its full-screen nag may have taken the screen at the deadline).
      await until(() => !playing(), 40_000, 1000);
      sh('wm dismiss-keyguard');
      home();
      await until(() => !/MainActivity|\.Notify/.test(top()), 6000, 700);
      sh('input keyevent KEYCODE_SLEEP');
      await wait(1500);
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      check('android', 'and so is the opening at an unlock', !!(await until(() => /MainActivity|\.Notify/.test(top()) && find(/via=unlock/), 12000, 1000)), top());
      shot('06-deadline.png');

      // 7. Taken: nothing left.
      await press('Done');
      await wait(2500);
      check('android', 'taken: no nag or promise notification left', !nagShown() && !promiseShown(), notifications().map((r) => r.slice(0, 160)));
      check('android', 'the service stopped', !/NagService/.test(services()), services().slice(0, 300));
      check('android', 'no overlay window', !overlay(), null);
      const after = armed();
      const days = after?.at ? (new Date(after.at.replace(' ', 'T')) - new Date(Number(sh('date +%s').trim()) * 1000)) / 86_400_000 : 7;
      check('android', 'only the stale notice is armed, six days out', days > 5.9, after?.block);
    }

    if (step(8)) {
      // 8. A reboot mid-ladder, locked with a PIN: the next nag is set again at boot and rings before the first unlock.
      launch();
      const group = await plan(SOON);
      home();
      await wait(Math.max(0, msTo(minuteOf(group), 3000)));
      await until(() => playing(), 20000, 1000);
      await until(() => !playing(), 40000, 1000);
      const before = armed();
      save('08-before-reboot.txt', before?.block ?? '');
      sh('locksettings set-pin 1234');
      pinSet = true;
      adb('logcat', '-c');
      adb('reboot');
      await wait(15000);
      adb('wait-for-device');
      await until(() => sh('getprop sys.boot_completed').trim() === '1', 180000, 2000);
      const again = await until(() => armed(), 60000, 2000);
      save('08-after-reboot.txt', again?.block ?? alarm().slice(0, 2000));
      check('android', 'after a reboot the next nag is armed again', !!again?.clock, again?.block);
      check('android', 'and it rings', !!(await until(() => playing(), 100000, 1000)), null);
      const locked = /RUNNING_LOCKED/.test(sh('dumpsys user'));
      check('android', 'audibly, before the first unlock', locked && !/ring: no alarm sound/.test(logcat()), locked ? null : 'the user was unlocked');
      save('08-logcat.txt', logcat().split('\n').filter((l) => /pinkoo|MediaPlayer|FGS/i.test(l)).join('\n'));
      // Unlocked with the PIN, then the PIN cleared.
      sh('input keyevent KEYCODE_WAKEUP');
      await wait(800);
      sh('input swipe 540 1800 540 600 200');
      await wait(800);
      sh('input text 1234');
      sh('input keyevent KEYCODE_ENTER');
      await until(() => /RUNNING_UNLOCKED/.test(sh('dumpsys user')), 15000, 1000);
      sh('locksettings clear --old 1234');
      pinSet = false;
      sh('wm dismiss-keyguard');
      shot('08-after-reboot.png');
      launch();
      await press('Take');
      await until(() => !playing() && !nagShown(), 10000);
    }

    if (step(9)) {
      // 9. A force-stop drops every alarm: nothing rings until the app is launched, then the overdue nag rings at once.
      launch();
      const group = await plan(SOON);
      home();
      sh(`am force-stop ${id}`);
      check('android', 'force-stopped: no alarm left', await until(() => !armed(), 5000), armed()?.block);
      await wait(Math.max(0, msTo(minuteOf(group), 8000)));
      check('android', 'nothing rings while stopped', !playing() && !pid(), null);
      launch();
      check('android', 'launched: the overdue nag rings at once', !!(await until(() => playing(), 20000, 1000)), null);
      // Its full-screen nag may have taken the screen: back home, then Take.
      launch();
      await press('Take');
      await until(() => !playing() && !nagShown(), 10000);
      check('android', 'and taken, nothing is left', !nagShown() && !/NagService/.test(services()), null);
    }

    if (step(10)) {
      // 10. Idle: two doses a day, forced deep Doze, five minutes on battery.
      launch();
      await press('Idle');
      await until(() => find(/T\d\d:\d\d \d{4}-/), 10000, 700);
      home();
      await until(() => { sh(sdk >= 34 ? `am stop-app ${id}` : `am kill ${id}`); return !pid(); }, 15000, 1500);
      sh('dumpsys batterystats --reset');
      sh('dumpsys battery unplug');
      sh('input keyevent KEYCODE_SLEEP');
      sh('dumpsys deviceidle force-idle deep');
      await wait((fast ? 1 : 5) * 60_000);
      const stats = sh(`dumpsys batterystats ${id}`);
      save('10-batterystats.txt', stats);
      sh('dumpsys deviceidle unforce');
      sh('dumpsys battery reset');
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      // Only the fixture's own block counts (`u0a214:` up to the next uid's): another app's periodic job may wake
      // the phone in the same five minutes (Settings' battery job did, 2026-11-19).
      const app = `u0a${Number(uid) - 10000}:`;
      const own = stats.includes(app) ? stats.slice(stats.indexOf(app)).split(/\n  (?:u0a\d+|\d+):\n|\n\n/)[0] : stats;
      const wakeups = /Wakeup alarm/.test(own), locks = /Wake lock .*pinkoo/i.test(stats) || /Wake lock/.test(own);
      check('android', 'idle: no wakeup alarm, no wake lock, the process never started', !wakeups && !locks && !pid(), `uid ${uid}`);
      launch();
      await press('Take');
    }

    if (step(11)) {
      // 11. Without exact-alarm access the alarm is inexact, and an inexact alarm gives no start of the service from
      // the background: the nag shows, silent. The alarm at its ring's end counts its one ignore all the same.
      sh(`cmd appops set ${id} SCHEDULE_EXACT_ALARM deny`);
      await wait(2000);
      launch();
      check('android', 'no exact alarms', !!(await until(() => find(/exact=false/), 15000, 700)), find(/exact=/)?.text);
      adb('logcat', '-c');
      const group = await plan(SOON);
      home();
      // An inexact alarm comes within its window, about a minute here.
      const shown = await until(() => nagShown(), msTo(minuteOf(group), 150_000), 2000);
      check('android', 'inexact: the nag shows', !!shown, null);
      check('android', 'the service was refused, so the app plays nothing', /Background started FGS: Disallowed[^\n]*pinkoo\.nag\.NagService/.test(logcat()) && !playing(), null);
      // Decision 5: it sounds itself, on the ring channel, insistent (FLAG_INSISTENT, 0x4), played by the system. The
      // notification's flags print as hex on some versions and by name on others (Android 16: `flags=ONGOING_EVENT|INSISTENT|…`).
      const flags = (shown?.match(/ flags=(\S+)/) ?? [])[1] ?? '';
      const insistent = /^0x[0-9a-f]+$/i.test(flags) ? (Number.parseInt(flags, 16) & 4) !== 0 : flags.split('|').includes('INSISTENT');
      check('android', 'it sounds itself on the ring channel, insistent', channelOf(shown) === 'pinkoo.nag.ring' && insistent, shown?.slice(0, 400));
      save('11-sound.txt', sh('dumpsys notification | grep -iE "mSoundNotificationKey|mVibrateNotificationKey"'));
      check('android', 'and the system plays its sound', new RegExp(`mSoundNotificationKey=[^\\n]*\\|${id.replaceAll('.', '\\.')}\\|`).test(sh('dumpsys notification')), null);
      const quiet = await until(() => /waiting/.test(channelOf(nagShown())), 180_000, 3000);
      check('android', 'its ring ends at the alarm on its ring end: the waiting notification', !!quiet, nagShown()?.slice(0, 300));
      check('android', 'and its sound stops', !new RegExp(`mSoundNotificationKey=[^\\n]*\\|${id.replaceAll('.', '\\.')}\\|`).test(sh('dumpsys notification')), null);
      launch();
      const one = await doses((t) => /^pending:1:1$/.test(t));
      check('android', 'one ignore, a rung up, with no service', one === 'pending:1:1', one);
      await press('Take');
      sh(`cmd appops set ${id} SCHEDULE_EXACT_ALARM allow`);
    }

    if (step(12)) {
      // 12. Every loud nag is a new notification, so each launches its full-screen nag (an update launches none).
      launch();
      const group = await plan(SOON);
      home();
      sh('locksettings set-pin 1234');
      pinSet = true;
      for (const n of [1, 2]) {
        sh('input keyevent KEYCODE_SLEEP');
        check('android', `nag ${n} rings`, !!(await until(() => playing(), 100_000, 1000)), null);
        const fsi = await until(() => /MainActivity|\.Notify/.test(top()) && /Awake/.test(sh('dumpsys power | grep mWakefulness=')), 10000);
        const keyguard = sh('dumpsys window | grep -iE "mShowingLockscreen|KeyguardShowing|isKeyguardShowing|mOccluded|occluded" | head -8');
        shot(`12-nag${n}-over-keyguard.png`);
        check('android', `nag ${n}: its own full-screen nag over the keyguard, the screen turned on`, !!fsi && /true/i.test(keyguard), `${top()} ${keyguard}`);
        await until(() => !playing(), 40_000, 1000);
        // The route left, as Pinkoo's root does: the keyguard covers it again, and only a new full-screen nag shows it.
        await press('Leave', 6000);
      }
      sh('locksettings clear --old 1234');
      pinSet = false;
      sh('input keyevent KEYCODE_WAKEUP');
      sh('wm dismiss-keyguard');
      home();
      // Rung 2: the process ends (the bubble's window with it); the third nag's alarm starts it again, with no activity.
      await until(() => { sh(sdk >= 34 ? `am stop-app ${id}` : `am kill ${id}`); return !pid(); }, 15000, 1500);
      check('android', 'nag 3 rings in a process the alarm started', !!(await until(() => playing(), 100_000, 1000)) && !!pid(), null);
      await until(() => !playing(), 40_000, 1000);
      check('android', 'rung 3, waiting: the bubble shows over the home screen', !!(await until(() => overlay(), 8000)), null);
      // The waiting notification tapped: the app comes through the alias, its module made after that resume.
      sh('cmd statusbar expand-notifications');
      await wait(1200);
      const row = await until(() => find(/^Pill A is waiting/), 6000, 700);
      if (row) sh(`input tap ${row.x} ${row.y}`);
      const opened = await until(() => top().includes(id), 10000, 700);
      await wait(2500);
      shot('12-opened-from-the-notification.png');
      check('android', 'opened from the notification, the app hides the bubble', !!row && !!opened && !overlay(), top());
      // Nothing changed: a pause and a resume post nothing again (an update popped the heads-up again).
      const stamp = () => (nagShown()?.match(/mUpdateTimeMs=(\d+)/) ?? [])[1] ?? null;
      const before = stamp();
      home();
      await wait(1500);
      launch();
      await wait(1500);
      home();
      await wait(1500);
      check('android', 'a pause and a resume post nothing again', !!before && stamp() === before, `${before} ${stamp()}`);
      // The bubble closed on its cross, then the process gone: a new process keeps it closed (the engine's state file)
      // until the next nag brings it back.
      const [w, h] = (sh('wm size').match(/(\d+)x(\d+)/) ?? [0, 1080, 2400]).slice(1).map(Number);
      const at = await until(() => bubbleAt(), 8000, 700);
      if (at) sh(`input swipe ${at.x} ${at.y} ${w >> 1} ${h - Math.round(h / 12)} 1500`);
      check('android', 'the bubble closed on its cross', !!at && !!(await until(() => !overlay(), 5000)), at);
      await until(() => { sh(sdk >= 34 ? `am stop-app ${id}` : `am kill ${id}`); return !pid(); }, 15000, 1500);
      launch();
      await wait(2500);
      home();
      await wait(3000);
      check('android', 'a new process keeps it closed', !playing() && !overlay(), sh(`dumpsys window windows | grep -i ${id} | head -5`));
      check('android', 'until the next nag brings it back', !!(await until(() => playing(), 100_000, 1000)) && !!(await until(() => overlay(), 8000)), null);
      launch();
      await press('Take');
      await until(() => !playing() && !nagShown(), 10000);
    }
  } finally {
    if (pinSet) sh('locksettings clear --old 1234');
    sh(`locksettings set-disabled ${lockDisabled === 'true'}`);
    sh('cmd audio set-ringer-mode NORMAL');
    sh('dumpsys deviceidle unforce');
    sh('dumpsys battery reset');
    sh('input keyevent KEYCODE_WAKEUP');
    sh('wm dismiss-keyguard');
    adb('uninstall', id);
  }
}
