// The emulator half of android.mjs: the fixture installed and driven as a person would, with real launches (no agent),
// `uiautomator` for the app's painted controls and the shade's, and `adb` for the system's state. The fixture's paces
// are minutes (fixture/app.ts, SCENARIOS). Each check reads what the system says (`dumpsys` alarm, audio, window,
// notification, activity, batterystats, deviceidle) rather than what the app believes. The gentle reminder
// (`docs/exact.md` › Planned: the gentle reminder, item 19): time alone moves a dose along its steps.
//
//  1. The thread, every step on, the alarm "Só se passar de tudo", locked with a PIN. The plan arms code 0 exactly
//     (`setExactAndAllowWhileIdle`, no alarm clock) with an inexact backup beside it, and the alarm clock (code 1) at the
//     alarm's on-time time. T: the notification, on her reminder channel, sounding, not ongoing, with "Not now"; nothing
//     opens, no service. T+1: De novo posted anew (the other id, sounding again); the bubble and the ring service with
//     its own notification; the balloon waits for the unlock, then shows about 6 s, untouchable and see-through, while
//     the face stays. A swipe dismisses the reminder, which stays away through a resume and a changed plan. T+3: Tela
//     cheia over the PIN keyguard, silent; the bubble gone with its step; the ring service holds the process in the
//     background, and her first unlock opens the nag route, once. T+5: the alarm rings on the alarm stream, its own
//     public notification with "Stop" and no medication's name, no second full screen; with the lock screen hiding
//     sensitive content, "Stop" in the locked shade stops it without unlocking. T+6: the first repeat; its "Not now"
//     turns the post, in place and silently, into the confirmation with the next time, which code 0 then holds, gone
//     after 5 s and kept away. The next repeats, the last one's "Not now" saying it waits on Today, then nothing more
//     (the cap of 3). The events: seven fires, two "notNow", no swipe or bubble reason.
//  2. Two dose times at different steps: A's Tela cheia over the keyguard; B's first notification, on B's sound, opens
//     no full screen and A's never comes twice; no alarm where it's off; B's own Tela cheia, then B's alarm, which her
//     unlock stops.
//  3. "Já na hora": the alarm rings at the dose time with the notification folded into it (only the ring sounds) on a
//     dark phone, and keeps ringing past its own post; the power key stops it. De novo, then a promise: its countdown
//     (a Live Update on 36.1 and later), nothing else while it runs, the alarm clock at the deadline; there the alarm
//     rings again, folded, stops at its 60 s cap (logged as its timeout, read by the engine's own answer: the fixture's
//     "State"), and the thread goes on with a repeat.
//  4. The bubble over the launcher's home screen (Settings and Files hide other apps' overlays): the face and, beside it
//     on the screen, its balloon; a long press on the balloon reaches the launcher under it (its own menu opens:
//     Android's untrusted-touch rule), the balloon goes after about 6 s; a tap on the face opens the nag route; the face
//     sent to the nest stays there across a process death, and the next step brings no bubble (it lives one step).
//  5. Tela cheia that can't open, each dose time's in a minute of its own: full-screen access denied (`appops --uid`, as
//     a Play install starts on Android 14 and later), the phone in use, Do Not Disturb on: each time that dose time's
//     notification again, on her channel, and nothing opens.
//  6. The channels: one per sound the plan names (a preset from `res/raw`, "Só vibrar", "Sem som", a phone sound),
//     high importance, notification usage; "Full screen and alarm" is `pinkoo.nag`; no `pinkoo.nag.ring`; a later plan
//     that no longer names a sound deletes its channel.
//  7. Exact access revoked (`appops`): Android stops the app and drops its exact alarms; the inexact backup survives and
//     brings the step, late; then the steps are armed inexact only, and the alarm step is the notification again.
//  8. The battery: background restricted (`RUN_ANY_IN_BACKGROUND` ignored) and the restricted bucket each switch code 0
//     to the alarm clock, so the steps come on time. The rare bucket is measured (a `MEASURE` line, not a check: whether
//     Android's quota holds code 0 back); the check is that the alarm clock rings on time either way, the steps code 0
//     missed skipped.
//  9. Doze: in forced deep idle the steps come on time; at rest (two doses a day, five minutes of forced deep idle on
//     battery) the process never starts, no wakeup, no wake lock.
// 10. A force-stop drops every alarm: nothing until the app is launched; then the overdue step comes at once, in front:
//     quiet on the waiting channel, told to the app with its step (`nagState().front`).
// 11. The experience test (`rehearse`): its notification step on her channel with "Stop", its wait an exact alarm of
//     its own, gone after 15 s, no state and no event; the alarm sample stops at 10 s; the full-screen step arms at the
//     screen turning off and opens the nag route in test mode over the keyguard.
// 12. A reboot between the notification and De novo, locked with a PIN: armed again at boot, the reminder restored
//     silently; De novo (posted after the restored one went, never beside it) and the alarm come before the first
//     unlock, audibly; the unlock stops the ring.
//
// Not driven here: "the alarm on time after a code-0 fire 40 min late" has no trigger an emulator reproduces on demand
// (group 8's rare bucket shows it when Android holds code 0 back there); `EngineTest` proves it. What needs a phone is
// checklist.md's.
//
// The emulator is left as found: the fixture uninstalled, the ringer normal, no PIN, Do Not Disturb off, the battery,
// Doze and the settings it touched (lock-screen privacy, notification cooldown, stay awake) restored.
//
// For iterating only (android.mjs's flags): `only` runs the listed groups, each on its own plan; `fast` plans each dose
// 30 to 90 s out instead of 60 to 120 and holds forced Doze at rest one minute instead of five. `api33` runs the groups
// an Android 12 or 13 image differs in (API33). Work that touches the engine is finished only after a whole run with the
// fixture's own timings: `android.mjs --emulator <serial>` with none of them.
import { resolve } from 'node:path';

const wait = (ms) => new Promise((done) => setTimeout(done, ms));

/** The groups `--api33` runs: an Android 12 or 13 image (full screen allowed by default, exact alarms, notifications). */
export const API33 = [1, 2, 3, 7];

/** The fixture's texts the drive reads back from Android (fixture/app.ts says the same). */
const TEXT = {
  title: 'Time for ', alarm: 'Medication alarm', service: 'The fixture is around', stop: 'Stop', notNow: 'Not now',
  test: 'Reminder test', again: /^Okay: again at (\d\d):(\d\d)\.$/, waits: 'Okay: it waits on Today.',
};
/** The mascot's line of each rung (fixture/app.ts `lines`): the reminders that reached her before this one, up to 4. */
const LINES = ['Psst, it is time.', 'Here I am again.', 'Still here for you.', 'One more time, gently.', 'Whenever you are ready.'];
const PHONE = 'content://settings/system/notification_sound';
const MIN = 60_000;
/** A step is on time within this of its minute: the alarm, a cold process start and the post. */
const ON_TIME = 15_000;
const PIN = '1234';

export async function drive({ root, apk, id, emulator, check, run, out, only = null, fast = false, api33 = false }) {
  const adb = (...a) => run('adb', ['-s', emulator, ...a], root, process.env, true);
  const sh = (cmd) => adb('shell', cmd);
  const sdk = Number(sh('getprop ro.build.version.sdk').trim());
  const sdkFull = sh('getprop ro.build.version.sdk_full').trim();
  const shot = (file) => out && run('sh', ['-c', `adb -s ${emulator} exec-out screencap -p > '${resolve(out, file)}'`], root, process.env, true);
  const save = (file, text) => out && Bun.write(resolve(out, file), String(text ?? ''));
  const until = async (f, ms, step = 500) => { const end = Date.now() + ms; let v; while (Date.now() < end) { if ((v = await f())) return v; await wait(step); } return v; };
  const groups = only ?? (api33 ? API33 : null);
  const runs = (n) => !groups || groups.includes(n);
  if (api33 && sdk > 33) console.log(`--api33 on an Android ${sdkFull} image: its groups run, but it proves nothing about Android 12 or 13`);

  // ---------- time, in the phone's clock and zone ----------
  const deviceNow = () => Number(sh('date +%s%3N').trim()) || Number(sh('date +%s').trim()) * 1000;
  const offset = (() => { const z = sh('date +%z').trim().match(/([+-])(\d\d)(\d\d)/); return z ? (z[1] === '-' ? -1 : 1) * (+z[2] * 60 + +z[3]) * 60_000 : 0; })();
  /** The fixture's local minute (`2026-11-22T20:00`) as an instant. */
  const epochOf = (local) => { const m = local.match(/(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)/); return m ? Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5]) - offset : NaN; };
  const hhmm = (ms) => { const d = new Date(ms + offset); return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`; };
  const near = (a, b, ms = 2000) => Number.isFinite(a) && Number.isFinite(b) && Math.abs(a - b) <= ms;
  const untilDevice = async (epoch) => { const left = epoch - deviceNow(); if (left > 0) await wait(left); };
  const late = (r, at) => (r ? `${((r.created - at) / 1000).toFixed(1)} s after ${hhmm(at)}` : `nothing by ${hhmm(deviceNow())}`);
  /** How far a time worked out from post [r]'s fire may sit from its nominal one: 2 s, plus how late [r] came after [at]. */
  const slack = (r, at) => 2000 + Math.max(0, (r?.created ?? at) - at);

  // ---------- the screen ----------
  let W = 1080, H = 2340;
  /** The nodes on screen, from `uiautomator`: each one's text, description, package and centre. */
  const nodes = () => {
    sh('uiautomator dump /sdcard/nag-ui.xml >/dev/null 2>&1');
    const xml = sh('cat /sdcard/nag-ui.xml');
    return [...xml.matchAll(/<node [^>]*>/g)].map((m) => {
      const n = m[0], b = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
      const attr = (k) => (n.match(new RegExp(`${k}="([^"]*)"`)) ?? [])[1] ?? '';
      return { text: attr('text'), desc: attr('content-desc'), pkg: attr('package'), x: b ? (+b[1] + +b[3]) >> 1 : 0, y: b ? (+b[2] + +b[4]) >> 1 : 0, b: b?.slice(1).map(Number) };
    });
  };
  const matches = (n, label) => (typeof label === 'string' ? n.text === label || n.desc === label : label.test(n.text) || label.test(n.desc));
  const find = (label, list = nodes()) => list.find((n) => matches(n, label));
  const tap = (n) => sh(`input tap ${n.x} ${n.y}`);
  const press = async (label, ms = 15000) => {
    const n = await until(() => find(label), ms, 700);
    if (n) tap(n);
    return !!n;
  };
  const SYSTEMUI = /^com\.android\.systemui/;
  const shade = () => nodes().filter((n) => SYSTEMUI.test(n.pkg));
  /** The fixture's line that starts with [prefix] (`doses `, `events `, `front `). */
  const line = (prefix) => find(new RegExp(`^${prefix}`))?.text ?? '';
  /** The app in front at its home screen, whatever route it was left on (a link's location, as any app may send). */
  const launch = (url = '/') => sh(`am start -W -a android.intent.action.VIEW -d '${url}' -n ${id}/dev.exact.app.MainActivity`);
  const home = () => sh('input keyevent KEYCODE_HOME');
  /** The app sent behind the launcher on a locked phone: the home key, and the launcher started by its intent, since
   * Android 13's home key doesn't reach past an app that was shown over the keyguard (her unlock then brought the app
   * itself back, and nothing was left for "Abrir quando eu desbloquear" to open). */
  const awayLocked = () => { home(); if (sdk < 34) sh('am start -a android.intent.action.MAIN -c android.intent.category.HOME'); };
  const back = () => sh('input keyevent KEYCODE_BACK');
  const sleep = () => sh('input keyevent KEYCODE_SLEEP');
  const wake = () => sh('input keyevent KEYCODE_WAKEUP');
  const top = () => (sh('dumpsys activity activities').match(/topResumedActivity=.*|mResumedActivity: .*/) ?? [''])[0];
  const ours = () => top().includes(`${id}/`);
  const awake = () => /mWakefulness=Awake/.test(sh('dumpsys power | grep mWakefulness='));
  const keyguard = () => {
    const w = sh('dumpsys window');
    return { showing: /isKeyguardShowing=true|mShowingLockscreen=true|KeyguardShowing=true/.test(w), occluded: /mKeyguardOccluded=true|occluded=true/.test(w) };
  };
  let pinSet = false;
  const setPin = () => { if (!pinSet) { sh(`locksettings set-pin ${PIN}`); pinSet = true; } };
  const clearPin = () => { if (pinSet) { sh(`locksettings clear --old ${PIN}`); pinSet = false; } };
  /** The keyguard gone: the PIN typed when one is set, else dismissed. Whether it went. */
  const unlock = async () => {
    wake();
    await wait(700);
    if (!keyguard().showing) return true;
    if (pinSet) {
      sh(`input swipe ${W >> 1} ${Math.round(H * 0.75)} ${W >> 1} ${Math.round(H * 0.25)} 200`);
      await wait(900);
      sh(`input text ${PIN}`);
      sh('input keyevent KEYCODE_ENTER');
    } else sh('wm dismiss-keyguard');
    return !!(await until(() => !keyguard().showing, 8000, 700));
  };
  /** The PIN off and the keyguard gone (`locksettings` clears it while locked; dismissing is then an unlock). */
  const open = async () => { clearPin(); wake(); sh('wm dismiss-keyguard'); await until(() => !keyguard().showing, 5000, 500); };
  /** A route's dose time [t] as its `NAG <group>` line shows it (`2026-11-22T20:00`, perhaps encoded). */
  const routeOf = (t) => { const [hh, mm] = hhmm(t).split(':'); return new RegExp(`T${hh}(:|%3A)${mm}`); };

  // ---------- the system's state ----------
  let uid = '?';
  const pid = () => sh(`pidof ${id}`).trim();
  const stopApp = () => until(() => { sh(sdk >= 34 ? `am stop-app ${id}` : `am kill ${id}`); return !pid(); }, 15000, 1500);
  /** One of our players on the alarm stream, started (`dumpsys audio`'s playback configurations). */
  const playing = () => sh('dumpsys audio').split('\n').some((l) => l.includes('AudioPlaybackConfiguration') && l.includes(`u/pid:${uid}/`) && /state:started/.test(l) && /USAGE_ALARM/.test(l));
  const services = () => sh(`dumpsys activity services ${id}`);
  const serving = () => /NagService[\s\S]*isForeground=true/.test(services());
  const logcat = () => adb('logcat', '-d', '-v', 'time');
  const clearLog = () => adb('logcat', '-b', 'all', '-c');

  /** Notification flags by name, from hex (older images) or names (Android 16: `flags=ONGOING_EVENT|…`). */
  const FLAG_BITS = { 0x2: 'ONGOING_EVENT', 0x4: 'INSISTENT', 0x8: 'ONLY_ALERT_ONCE', 0x10: 'AUTO_CANCEL', 0x20: 'NO_CLEAR', 0x40: 'FOREGROUND_SERVICE' };
  const flagSet = (f) => new Set(/^0x[0-9a-f]+$/i.test(f) ? Object.entries(FLAG_BITS).filter(([b]) => (Number.parseInt(f, 16) & Number(b)) !== 0).map(([, n]) => n) : f.split('|'));
  /** Our notifications, each read off its `NotificationRecord`. */
  const records = () => sh('dumpsys notification --noredact').split(/\n(?=\s*NotificationRecord\()/).filter((r) => r.includes(`pkg=${id} `)).map((r) => {
    const get = (re) => (r.match(re) ?? [])[1] ?? '';
    return {
      id: Number(get(/\bid=(-?\d+)/)), key: get(/\bkey=(\S+?):?\s/), channel: get(/Notification\(channel=(\S+)/) || get(/channel=([\w.:-]+)/),
      flags: flagSet(get(/Notification\([^\n]*? flags=(\S+)/)), category: get(/Notification\([^\n]*?category=(\w+)/), vis: get(/Notification\([^\n]*? vis=(\w+)/),
      title: get(/android\.title=String \((.*)\)\s*$/m), text: get(/android\.text=String \((.*)\)\s*$/m),
      created: Number(get(/mCreationTimeMs=(\d+)/)), updated: Number(get(/mUpdateTimeMs=(\d+)/)),
      fsi: !!get(/fullscreenIntent=(\S+)/) && get(/fullscreenIntent=(\S+)/) !== 'null',
      actions: [...r.matchAll(/\[\d+\] "([^"]*)"/g)].map((m) => m[1]),
      raw: r.slice(0, 8000),
    };
  });
  const isAlarm = (r) => r.title === TEXT.alarm;
  const isService = (r) => r.title === TEXT.service;
  const isTest = (r) => r.title === TEXT.test;
  // The reminder notification's two ids (`Shade.nagId`), or its title: one notification for every open dose time.
  const NAGS = [0x6e6100, 0x6e6104];
  const reminders = (rs = records()) => rs.filter((r) => !isAlarm(r) && !isService(r) && !isTest(r) && (NAGS.includes(r.id) || r.title.startsWith(TEXT.title)));
  const reminder = (rs = records()) => reminders(rs).sort((a, b) => b.created - a.created)[0] ?? null;
  const alarmPost = () => records().find(isAlarm) ?? null;
  /** A reminder posted anew after [since] (a new record: each step that sounds is one), waited for until [by]. */
  const posted = (since, by) => until(() => reminders().filter((r) => r.created > since).sort((a, b) => a.created - b.created)[0] ?? null, Math.max(1000, by - deviceNow()), 700);
  /** Her reminder channel for [sound]: `pinkoo.remind.<preset>` (versioned), a hash for a phone sound. */
  const remindChannel = (r) => !!r && r.channel.startsWith('pinkoo.remind.');
  const channelOf = (r, sound) => remindChannel(r) && r.channel.includes(sound);
  /** The notification the system is sounding (`mSoundNotificationKey`), cleared when that one goes. */
  const soundKey = () => (sh('dumpsys notification').match(/mSoundNotificationKey=(\S+)/) ?? [])[1] ?? 'null';
  const sounded = (r) => !!r && until(() => soundKey() === r.key, 5000, 700);

  /** Our pending alarms, from `dumpsys alarm`: each one's time, window, flags, whether an alarm clock. */
  const alarms = () => {
    const lines = sh('dumpsys alarm').split('\n'), list = [];
    for (let i = 0; i < lines.length; i++) {
      const head = lines[i].match(/^(\s*)(?:RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED) #\d+: Alarm\{[^}]*\borigWhen (-?\d+)[^}]*\s(\S+)\}/);
      if (!head || head[3] !== id) continue;
      let j = i + 1;
      while (j < lines.length && lines[j].trim() && lines[j].match(/^\s*/)[0].length > head[1].length) j++;
      const block = lines.slice(i, j).join('\n');
      const window = (block.match(/\bwindow=(\S+)/) ?? [])[1] ?? '';
      const flags = Number.parseInt((block.match(/\bflags=0x([0-9a-f]+)/) ?? [])[1] ?? '0', 16);
      const clock = /Alarm clock:/.test(block);
      const exact = !clock && /^0(ms)?$/.test(window);
      list.push({ at: Number(head[2]), tag: (block.match(/tag=(\S+)/) ?? [])[1] ?? '', window, flags, clock, exact, inexact: !clock && !exact,
        policy: (block.match(/policyWhenElapsed: (.*)/) ?? [])[1] ?? '', block });
    }
    return list.filter((a, i) => list.findIndex((b) => b.at === a.at && b.tag === a.tag && b.window === a.window) === i);
  };
  const list = (as) => as.map((a) => `${hhmm(a.at)} ${a.clock ? 'clock' : a.exact ? 'exact' : `inexact(${a.window})`} ${a.tag}`).join('; ') || 'none';
  /** Code 0, the next step's exact alarm: exact while-idle, no alarm clock. */
  const code0 = (as = alarms()) => as.filter((a) => a.exact && (a.flags & 0x4)).sort((a, b) => a.at - b.at)[0] ?? as.filter((a) => a.exact).sort((a, b) => a.at - b.at)[0] ?? null;

  /** Our overlay windows (the bubble's face, its balloon), from `dumpsys window`. */
  const overlays = () => sh('dumpsys window windows').split(/\n(?=\s*Window #\d+)/).filter((b) => b.includes(id) && /ty=APPLICATION_OVERLAY|type=2038/.test(b)).map((b) => {
    const f = b.match(/ frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/), frame = f ? f.slice(1).map(Number) : null;
    return { frame, x: frame ? (frame[0] + frame[2]) >> 1 : 0, y: frame ? (frame[1] + frame[3]) >> 1 : 0, alpha: Number((b.match(/\balpha=([\d.]+)/) ?? [])[1] ?? 1),
      notTouchable: /\bfl=[^\n]*\bNOT_TOUCHABLE\b/.test(b), visible: /isVisible=true/.test(b) || (/isOnScreen=true/.test(b) && !/isVisible=false/.test(b)), block: b.slice(0, 1500) };
  });
  const overlay = () => overlays().length > 0;
  /** The balloon: the untouchable overlay, shown. */
  const balloon = () => overlays().find((w) => w.notTouchable && w.visible) ?? null;
  const face = () => overlays().find((w) => !w.notTouchable) ?? null;

  /** Our notification channels, from `dumpsys notification`'s preferences. */
  const channels = () => {
    const all = sh('dumpsys notification --noredact'), start = all.indexOf(`AppSettings: ${id} (`);
    if (start < 0) return [];
    const next = all.indexOf('AppSettings: ', start + 13), own = all.slice(start, next < 0 ? undefined : next);
    return [...own.matchAll(/NotificationChannel\{mId='([^']+)'([^\n]*)/g)].map((m) => {
      const s = m[2], f = (k) => (s.match(new RegExp(`\\b${k}=([^,}]*)`)) ?? [])[1]?.trim() ?? '';
      return { id: m[1], name: f('mName'), importance: Number(f('mImportance')), sound: f('mSound'), vibrate: /mVibrationEnabled=/.test(s) ? f('mVibrationEnabled') === 'true' : /mVibrationEffect=(?!null)/.test(s),
        deleted: f('mDeleted') === 'true', usage: (s.match(/usage=(\w+)/) ?? [])[1] ?? '', raw: s.slice(0, 600) };
    });
  };

  // ---------- the fixture ----------
  /** A scenario's plan sent from the fixture, in front: its dose times as instants, earliest first. */
  const plan = async (label) => {
    if (fast) await press('Fast');
    const before = find(/^\d{4}-\d\d-\d\dT\d\d:\d\d/)?.text ?? '';
    await press(label);
    const p = await until(() => { const n = find(/^\d{4}-\d\d-\d\dT\d\d:\d\d/); return n && n.text !== before ? n.text : null; }, 15000, 700);
    return p ? p.split(' ').map(epochOf) : [];
  };
  const act = async (label) => { await press(label); await wait(1500); };
  /** Our notification titled [title] swiped away in the shade (unlocked); whether it went. */
  const swipeAway = async (title) => {
    for (let attempt = 0; attempt < 3; attempt++) {
      sh('cmd statusbar expand-notifications');
      await wait(1200);
      const n = await until(() => shade().find((x) => x.text.startsWith(title) || x.desc.includes(title)), 6000, 700);
      if (n) sh(`input swipe ${Math.min(n.x, W >> 2)} ${n.y} ${W - 20} ${n.y} 300`);
      await wait(1200);
      sh('cmd statusbar collapse');
      if (!n) return false;
      if (await until(() => !reminder(), 3000, 500)) return true;
    }
    // Her other way to dismiss it, where the swipe didn't take (Android 13's shade, with the bubble's windows up): the
    // shade's "Clear all", which sends each cleared notification's delete intent as a swipe does.
    sh('cmd statusbar expand-notifications');
    await wait(1200);
    const all = await until(() => shade().find((x) => /^Clear all/i.test(x.text) || /^Clear all/i.test(x.desc)), 6000, 700);
    if (all) { console.log('   the swipe did not take: cleared with "Clear all"'); tap(all); }
    await wait(1200);
    sh('cmd statusbar collapse');
    return !!all && !!(await until(() => !reminder(), 3000, 500));
  };
  /** A collapsed notification in the shade opened so its actions show: its own expand button (the chevron at the row's
   * end; a drag down on the row doesn't open one on the keyguard's shade), else dragged down. */
  const expand = async (row) => {
    const chevron = shade().filter((n) => /^Expand/i.test(n.desc) && Math.abs(n.y - row.y) < 160).sort((a, b) => Math.abs(a.y - row.y) - Math.abs(b.y - row.y))[0];
    if (chevron) tap(chevron);
    else sh(`input swipe ${row.x} ${row.y} ${row.x} ${row.y + 300} 250`);
    await wait(900);
  };
  /** The action [label] of our notification titled [title], pressed in the shade. */
  const pressInShade = async (title, label) => {
    sh('cmd statusbar expand-notifications');
    await wait(1200);
    let a = shade().find((n) => matches(n, label));
    if (!a) {
      // Expanded so its actions show.
      const row = await until(() => shade().find((n) => n.text.startsWith(title)), 6000, 700);
      if (row) await expand(row);
      a = await until(() => shade().find((n) => matches(n, label)), 4000, 700);
    }
    if (a) tap(a);
    await wait(900);
    sh('cmd statusbar collapse');
    return !!a;
  };

  // ---------- settings the drive changes, restored at the end ----------
  const setting = (ns, key) => { const v = sh(`settings get ${ns} ${key}`).trim(); return v === 'null' || v === '' ? null : v; };
  const restore = (ns, key, v) => sh(v === null ? `settings delete ${ns} ${key}` : `settings put ${ns} ${key} ${v}`);
  const saved = {
    privateNotifications: setting('secure', 'lock_screen_allow_private_notifications'),
    cooldown: setting('system', 'notification_cooldown_enabled'),
    stayOn: setting('global', 'stay_on_while_plugged_in'),
  };
  const stayOn = (on) => (on ? sh('svc power stayon true') : restore('global', 'stay_on_while_plugged_in', saved.stayOn));

  /** After every group, whatever it left: the phone open, the battery and Doze real, the grants back, nothing open. */
  const tidy = async () => {
    sh('cmd statusbar collapse');
    sh('dumpsys deviceidle unforce');
    sh('dumpsys battery reset');
    sh('cmd notification set_dnd off');
    sh(`cmd appops set ${id} SCHEDULE_EXACT_ALARM allow`);
    sh(`cmd appops set ${id} RUN_ANY_IN_BACKGROUND allow`);
    if (sdk >= 34) sh(`cmd appops set --uid ${id} USE_FULL_SCREEN_INTENT allow`);
    sh(`am set-standby-bucket ${id} active`);
    stayOn(false);
    await open();
    launch();
    await press('Take', 8000);
    await wait(1500);
    home();
  };
  const group = async (n, title, body) => {
    if (!runs(n)) return;
    const started = Date.now();
    console.log(`-- ${n}. ${title}`);
    try {
      await body();
    } catch (e) {
      check('android', `${n}. ${title}: the group ran to its end`, false, e?.stack ?? String(e));
    }
    try { await tidy(); } catch (e) { console.log(`   tidy after ${n}: ${e}`); }
    console.log(`   ${n}. ${title}: ${((Date.now() - started) / 60_000).toFixed(1)} min`);
  };
  const c = (n, name, ok, detail) => check('android', `${n}. ${name}`, !!ok, detail);

  // Setup: the fixture installed with its runtime permission, exact alarms, full-screen intents and the overlay allowed.
  adb('uninstall', id);
  adb('install', '-r', '-g', apk);
  uid = (sh(`cmd package list packages -U ${id}`).match(/uid:(\d+)/) ?? [])[1] ?? '?';
  sh(`cmd appops set ${id} SCHEDULE_EXACT_ALARM allow`);
  sh(`cmd appops set ${id} SYSTEM_ALERT_WINDOW allow`);
  // Full-screen access is the uid's mode (the install's grant sets it), which a package mode can't override.
  if (sdk >= 34) sh(`cmd appops set --uid ${id} USE_FULL_SCREEN_INTENT allow`);
  [W, H] = (sh('wm size').match(/(\d+)x(\d+)/) ?? [0, W, H]).slice(1).map(Number);
  sh('cmd audio set-ringer-mode NORMAL');
  // Android 15's notification cooldown softens an app's alerts that come within a minute or two of each other, as the
  // fixture's steps do (Pinkoo's paces are 5 minutes or more apart): off for the drive, as found after.
  sh('settings put system notification_cooldown_enabled 0');
  wake();
  sh('wm dismiss-keyguard');
  clearLog();
  launch();
  const status = await until(() => find(/^true granted exact=/), 30000, 1000);
  check('android', 'status: granted, exact alarms, full-screen and overlay allowed', /exact=true fsi=true overlay=true/.test(status?.text ?? ''), status?.text);
  check('android', `Live Update availability on ${sdkFull}`, /promoted=(granted|denied|unavailable)/.test(status?.text ?? ''), status?.text);
  const lockDisabled = sh('locksettings get-disabled').trim();

  try {
    await group(1, 'the thread', async () => {
      launch();
      const [T] = await plan('Thread');
      c(1, 'the plan is sent', Number.isFinite(T), line('doses'));
      const a0 = alarms();
      save('01-alarms-plan.txt', a0.map((a) => a.block).join('\n\n'));
      c(1, 'the plan arms the first step exactly, with no alarm clock (code 0)', near(code0(a0)?.at, T), list(a0));
      c(1, 'and an inexact backup at the same time', a0.some((a) => a.inexact && near(a.at, T)), list(a0));
      c(1, "and the alarm clock (code 1) at the alarm's on-time time, T+5, already", a0.filter((a) => a.clock).length === 1 && a0.some((a) => a.clock && near(a.at, T + 5 * MIN)), list(a0));
      home();
      await stopApp();
      setPin();
      sleep();
      clearLog();
      c(1, 'the process is gone before the dose', !pid(), pid());

      // T: the notification.
      const n1 = await posted(T - 1500, T + 25_000);
      shot('01-notify.png');
      c(1, 'T: the notification posts on time', n1 && n1.created - T < ON_TIME, late(n1, T));
      c(1, 'on her reminder channel (the dose\'s sound)', channelOf(n1, 'sininho'), n1?.channel);
      c(1, 'in the reminder category, not ongoing, with "Not now"', n1?.category === 'reminder' && !n1.flags.has('ONGOING_EVENT') && n1.actions.includes(TEXT.notNow), n1?.raw);
      c(1, 'sounding: the system plays its channel\'s sound', await sounded(n1), soundKey());
      c(1, "its line is rung 0's", n1?.text === LINES[0], n1?.text);
      c(1, 'no full screen, no ring, no service: the screen stays dark', n1 && !n1.fsi && !playing() && !serving() && !awake(), `${top()} ${services().slice(0, 300)}`);
      c(1, 'the alarm started the process for its broadcast', /Start proc .*for broadcast/.test(logcat()), null);
      const a1 = alarms();
      // Each step counts from the one before as it really fired (`Rules.fire`), so a fire a few seconds late moves the
      // next ones, the alarm clock included, by as much.
      const late1 = slack(n1, T);
      c(1, 'then code 0 at De novo (T+1), the backup beside it, the alarm clock kept at T+5', near(code0(a1)?.at, T + MIN, late1) && a1.some((a) => a.inexact && near(a.at, T + MIN, late1)) && a1.some((a) => a.clock && near(a.at, T + 5 * MIN, late1)), list(a1));

      // T+1: De novo, with the bubble.
      const n2 = await posted(Math.max(n1?.created ?? 0, T + MIN - 5000), T + MIN + 25_000);
      c(1, 'T+1: De novo is posted anew (the other id), on time', n2 && n1 && n2.id !== n1.id && n2.created - (T + MIN) < ON_TIME, `${late(n2, T + MIN)} ids ${n1?.id} ${n2?.id}`);
      c(1, 'sounding again', await sounded(n2), soundKey());
      c(1, 'one reminder at a time: the first one went', reminders().length === 1, reminders().map((r) => r.id));
      c(1, "its line is rung 1's", n2?.text === LINES[1], n2?.text);
      // The service starts beside the post (its window first). Android shows a foreground service's notification up to
      // 10 s late (its deferral), so what it runs under is read off the service's own record: its notification's id and
      // channel.
      const fg = await until(() => { const m = services().match(/isForeground=true[\s\S]*?foregroundId=(-?\d+)[\s\S]*?foregroundNoti=Notification\(channel=(\S+)/); return m ? { id: Number(m[1]), channel: m[2] } : null; }, 8000, 700);
      c(1, 'the ring service holds the bubble, on its own low-importance notification', serving() && fg?.channel === 'pinkoo.nag.waiting' && fg.id !== n2?.id, JSON.stringify(fg) + services().slice(0, 1500));
      c(1, 'and the reminder stays swipeable (not ongoing)', n2 && !reminder()?.flags.has('ONGOING_EVENT'), reminder()?.raw);
      c(1, 'locked and dark: the balloon waits', !balloon(), overlays().map((w) => w.block));
      await open();
      const unlockedAt = Date.now();
      const b1 = await until(() => balloon(), 5000, 300);
      shot('01-balloon.png');
      await wait(1200);
      shot('01-balloon-drawn.png');
      c(1, 'unlocked: the balloon shows at once, beside the face', !!b1 && !!face(), overlays().map((w) => w.block));
      c(1, 'it lets taps through: untouchable, drawn at an alpha of 0.8 or less', b1 && b1.notTouchable && b1.alpha <= 0.8, b1?.block);
      await wait(Math.max(0, unlockedAt + 9000 - Date.now()));
      c(1, 'the balloon goes after about 6 s; the face stays', !balloon() && !!face(), overlays().map((w) => w.block));
      c(1, 'a swipe dismisses the reminder', await swipeAway(TEXT.title), reminder()?.raw);
      launch();
      c(1, 'the bubble hides while the app is in front', await until(() => !overlay(), 6000), null);
      await act('Resend');
      home();
      c(1, 'and comes back when it leaves', await until(() => overlay(), 6000), null);
      await wait(1500);
      c(1, 'the reminder stays away through a resume and a changed plan, silent', !reminder() && soundKey() !== n2?.key, reminder()?.raw);

      // T+3: Tela cheia over the PIN keyguard.
      setPin();
      sleep();
      const fsi = await until(() => ours() && awake() && keyguard().occluded, T + 3 * MIN + 20_000 - deviceNow(), 700);
      shot('01-full-screen.png');
      const n3 = reminder();
      c(1, 'T+3: Tela cheia opens the nag route over the PIN keyguard, the screen on', !!fsi && keyguard().showing, `${top()} ${JSON.stringify(keyguard())}`);
      // The route's resume refreshes the post in place within a second (waiting and silent, on her channel), which drops
      // the intent from the record: the launch above is the intent's proof, and the record that it came anew at T+3.
      c(1, 'through a new post with its full-screen intent, on time, the swipe forgotten', n3 && (n3.fsi || !!fsi) && n3.id !== n2?.id && n3.created > (n2?.created ?? 0) && n3.created - (T + 3 * MIN) < ON_TIME, `${late(n3, T + 3 * MIN)} ${n3?.raw}`);
      c(1, 'silent: no sound of its own, no ring', n3 && soundKey() !== n3.key && !playing(), soundKey());
      c(1, 'the route is the full screen\'s', !!(await until(() => find(/via=fsi/), 6000, 700)), line('/'));
      await press('Leave', 6000);
      awayLocked();
      sleep();
      c(1, 'the bubble went with its step', await until(() => !overlay(), 4000), overlays().map((w) => w.block));
      await wait(15_000);
      c(1, '15 s in the background, the ring service holds the process for her unlock', serving() && !!pid(), services().slice(0, 400));
      clearLog();
      await unlock();
      const opened = await until(() => ours() && find(/via=unlock/), 12000, 1000);
      shot('01-unlock.png');
      c(1, 'her first unlock opens the nag route', !!opened, top());
      c(1, 'with no background activity start blocked', !/Background activity launch blocked|Abort background activity starts|BAL_BLOCK/i.test(logcat()), null);
      home();
      sleep();
      await wait(1500);
      await unlock();
      await wait(4000);
      c(1, 'once: the next unlock opens nothing', !ours(), top());
      c(1, 'and the ring service let go', await until(() => !serving(), 5000), services().slice(0, 300));

      // T+5: the alarm, the lock screen hiding sensitive content.
      home();
      sh('settings put secure lock_screen_allow_private_notifications 0');
      sleep();
      await untilDevice(T + 5 * MIN - 2500);
      stayOn(true);
      wake();
      const ring = await until(() => playing(), 20_000, 500);
      const ringAt = deviceNow();
      shot('01-alarm.png');
      c(1, 'T+5: the alarm rings on the alarm stream ("Só se passar de tudo"), on time', ring && ringAt - (T + 5 * MIN) < ON_TIME, `${((ringAt - T - 5 * MIN) / 1000).toFixed(1)} s`);
      const ap = alarmPost();
      c(1, 'its own notification: public, the alarm category, "Stop", no medication\'s name', ap && ap.vis === 'PUBLIC' && ap.category === 'alarm' && ap.actions.includes(TEXT.stop) && !/Pill/.test(ap.title + ap.text), ap?.raw ?? records().map((r) => r.raw.slice(0, 300)));
      c(1, 'no second full screen', !records().some((r) => r.fsi && r.created > T + 5 * MIN - 5000) && !ours(), `${top()} ${records().filter((r) => r.fsi).map((r) => `${r.id} ${hhmm(r.created)}`)}`);
      c(1, 'the ring service plays it, in the foreground', serving(), services().slice(0, 300));
      sh('cmd statusbar expand-notifications');
      await wait(1500);
      let locked = shade();
      save('01-locked-shade.txt', locked.map((n) => `${n.text} | ${n.desc}`).join('\n'));
      c(1, 'the locked shade hides the medication\'s name', locked.length > 0 && !locked.some((n) => /Pill A/.test(n.text + n.desc)), locked.map((n) => n.text).filter(Boolean));
      let stop = locked.find((n) => matches(n, TEXT.stop));
      if (!stop) {
        const row = locked.find((n) => n.text.startsWith(TEXT.alarm));
        if (row) await expand(row);
        stop = await until(() => shade().find((n) => matches(n, TEXT.stop)), 4000, 700);
      }
      shot('01-locked-shade.png');
      c(1, 'and shows the alarm whole, "Stop" included', !!stop, locked.map((n) => n.text).filter(Boolean));
      if (stop) tap(stop);
      c(1, '"Stop" stops the ring on the locked phone, without unlocking', await until(() => !playing(), 6000) && keyguard().showing, JSON.stringify(keyguard()));
      sh('cmd statusbar collapse');
      stayOn(false);
      restore('secure', 'lock_screen_allow_private_notifications', saved.privateNotifications);
      c(1, 'the alarm\'s notification goes with it', await until(() => !alarmPost(), 5000), alarmPost()?.raw);

      // T+6: the first repeat and its "Not now".
      await open();
      const r1 = await posted(T + 6 * MIN - 5000, T + 6 * MIN + 25_000);
      c(1, 'T+6: the first repeat, posted anew, on time', r1 && r1.created - (T + 6 * MIN) < ON_TIME, late(r1, T + 6 * MIN));
      c(1, 'sounding, with no bubble, full screen or alarm', (await sounded(r1)) && !r1.fsi && !overlay() && !playing(), `${soundKey()} ${r1?.raw}`);
      c(1, "its line is rung 4's (the top)", r1?.text === LINES[4], r1?.text);
      c(1, '"Not now" pressed in the shade', await pressInShade(TEXT.title, TEXT.notNow), null);
      // The confirmation is the post's title (`Shade.confirm`), with no text under it.
      const conf = await until(() => { const r = reminder(); return r && r.created === r1?.created && TEXT.again.test(r.title) ? r : null; }, 6000, 500);
      c(1, 'it turns the post, in place and silently, into the confirmation with the next time', conf && conf.flags.has('ONLY_ALERT_ONCE'), reminder()?.raw);
      c(1, 'and opens nothing', !ours(), top());
      const next = code0();
      const said = conf?.title.match(TEXT.again);
      c(1, 'the next repeat moved to one interval after the tap, the time it says', conf && next && near(next.at, conf.updated + MIN, 5000) && said && hhmm(next.at) === `${said[1]}:${said[2]}`, `${list(alarms())} said ${conf?.title} at ${hhmm(conf?.updated ?? 0)}`);
      c(1, 'the confirmation goes after 5 s', await until(() => !reminder(), 9000, 500), reminder()?.raw);
      launch();
      await act('Resend');
      home();
      await wait(1500);
      c(1, 'and stays away through a resume and a changed plan', !reminder(), reminder()?.raw);

      // The second and third repeats, then nothing.
      const r2 = await posted((next?.at ?? T + 7 * MIN) - 5000, (next?.at ?? T + 7 * MIN) + 25_000);
      c(1, 'the second repeat at the moved time', r2 && next && r2.created - next.at < ON_TIME, late(r2, next?.at ?? T + 7 * MIN));
      const r3 = await posted((r2?.created ?? T + 7 * MIN) + MIN - 5000, (r2?.created ?? T + 7 * MIN) + MIN + 25_000);
      c(1, 'the third repeat a minute later', r3 && r2 && r3.created - (r2.created + MIN) < ON_TIME, late(r3, (r2?.created ?? 0) + MIN));
      c(1, '"Not now" on the last one', await pressInShade(TEXT.title, TEXT.notNow), null);
      const last = await until(() => { const r = reminder(); return r && r.title === TEXT.waits ? r : null; }, 6000, 500);
      c(1, 'says the dose waits on Today: nothing comes before it is missed', !!last, reminder()?.title);
      const capped = alarms();
      c(1, 'the cap of 3: no step armed within the hour, no alarm clock', !capped.some((a) => a.clock) && !(code0(capped)?.at < deviceNow() + 60 * MIN), list(capped));
      await wait(75_000);
      c(1, 'and none comes', !reminders().some((r) => r.created > (r3?.created ?? T)), reminders().map((r) => r.raw.slice(0, 200)));
      launch();
      const events = await until(() => { const t = line('events '); return t.includes('fired') ? t : null; }, 10000, 700) ?? line('events ');
      const kinds = events.replace(/^events /, '').split(',');
      save('01-events.txt', `${events}\n${line('doses ')}`);
      c(1, 'the events: seven fires, two "Not now", no other reason', kinds.filter((k) => k.startsWith('fired')).length === 7 && kinds.filter((k) => k === 'ignored:notNow').length === 2
        && !kinds.some((k) => k.startsWith('ignored') && k !== 'ignored:notNow'), events);

      // Taken: nothing left.
      await act('Take');
      home();
      await wait(1500);
      c(1, 'taken: no notification, service or overlay left', !records().length && !serving() && !overlay(), records().map((r) => r.raw.slice(0, 160)));
      const after = alarms();
      c(1, 'only the stale notice is armed, six days out', after.length > 0 && after.every((a) => !a.clock && a.at - deviceNow() > 5.9 * 24 * 60 * MIN), list(after));
    });

    await group(2, 'two dose times at different steps', async () => {
      launch();
      const [TA, TB] = await plan('Two');
      c(2, 'two dose times, three minutes apart', near(TB - TA, 3 * MIN, 1000), line('doses'));
      const a0 = alarms();
      c(2, 'the alarm clock only where the alarm is on: B\'s, at its on-time time', a0.filter((a) => a.clock).length === 1 && a0.some((a) => a.clock && near(a.at, TB + 3 * MIN)), list(a0));
      home();
      await stopApp();
      setPin();
      sleep();
      // A: TA, TA+1, then its Tela cheia at TA+2.
      const fsiA = await until(() => ours() && awake() && keyguard().occluded, TA + 2 * MIN + 20_000 - deviceNow(), 700);
      const nagA = find(/^NAG /)?.text ?? '';
      shot('02-full-screen-a.png');
      c(2, "A's Tela cheia over the keyguard, its own route", !!fsiA && routeOf(TA).test(nagA), `${top()} ${nagA}`);
      await press('Leave', 6000);
      awayLocked();
      sleep();
      await wait(2000);
      // TB: B's first notification.
      const nB = await posted(TB - 1500, TB + 25_000);
      await wait(4000);
      shot('02-b-first.png');
      c(2, "B's first notification: a new post on B's sound (Só vibrar), naming both doses", nB && channelOf(nB, 'vibrate') && /Pill A/.test(nB.title) && /Pill B/.test(nB.title), nB?.raw);
      c(2, "it opens no full screen, and A's doesn't come twice: the screen stays dark", nB && !nB.fsi && !awake() && !ours(), `${top()} awake=${awake()}`);
      c(2, "A's alarm is off: its \"Só se passar de tudo\" time rings nothing", !playing(), null);
      const nB2 = await posted(TB + MIN - 5000, TB + MIN + 25_000);
      await wait(3000);
      c(2, "B's De novo: no full screen either", nB2 && !nB2.fsi && !awake() && !ours(), `${late(nB2, TB + MIN)} awake=${awake()}`);
      const fsiB = await until(() => ours() && awake() && keyguard().occluded, TB + 2 * MIN + 20_000 - deviceNow(), 700);
      const nagB = find(/^NAG /)?.text ?? '';
      shot('02-full-screen-b.png');
      c(2, "B's own Tela cheia, over the keyguard, B's route", !!fsiB && routeOf(TB).test(nagB), `${top()} ${nagB}`);
      // The screen kept on, so only the unlock can stop the alarm below (the screen turning on would too).
      stayOn(true);
      const ring = await until(() => playing(), TB + 3 * MIN + 20_000 - deviceNow(), 500);
      c(2, "B's alarm rings, where it's on", !!ring, null);
      home();
      await wait(1500);
      c(2, 'leaving the route does not stop it', playing(), null);
      await unlock();
      c(2, 'her unlock stops it', await until(() => !playing(), 6000), JSON.stringify(keyguard()));
      stayOn(false);
    });

    await group(3, '"Já na hora", the power key, a promise and the cap', async () => {
      launch();
      const [T] = await plan('On time');
      const a0 = alarms();
      c(3, 'the alarm clock at the dose time ("Já na hora")', a0.some((a) => a.clock && near(a.at, T)), list(a0));
      home();
      await stopApp();
      setPin();
      sleep();
      const ring = await until(() => playing(), T + 25_000 - deviceNow(), 500);
      const ringAt = deviceNow();
      c(3, 'T: the alarm rings with the dose time, on time', ring && ringAt - T < ON_TIME, `${((ringAt - T) / 1000).toFixed(1)} s`);
      const ap = alarmPost();
      c(3, 'on its own public notification with "Stop"', ap && ap.vis === 'PUBLIC' && ap.actions.includes(TEXT.stop), ap?.raw);
      const key = soundKey();
      c(3, 'the notification folded into it: none of its posts sounds, only the ring', !records().some((r) => r.key === key), `${key} ${records().map((r) => `${r.id} ${r.channel}`)}`);
      c(3, 'the phone stays dark: no full screen', !awake() && !ours(), top());
      await wait(3000);
      c(3, 'it keeps ringing past its own post', playing(), null);
      sh('input keyevent KEYCODE_POWER');
      c(3, 'the power key on the dark phone stops it', await until(() => !playing(), 5000), null);
      c(3, 'without unlocking', keyguard().showing, JSON.stringify(keyguard()));
      await open();
      const n2 = await posted(T + MIN - 5000, T + MIN + 25_000);
      c(3, 'T+1: De novo, on her channel, sounding', n2 && channelOf(n2, 'sininho') && (await sounded(n2)) && n2.created - (T + MIN) < ON_TIME, late(n2, T + MIN));

      // A promise, and its deadline.
      launch();
      await press('Promise');
      const said = await until(() => find(/^promised \d+ until \d+/)?.text, 8000, 700);
      const deadline = Number(said?.match(/until (\d+)/)?.[1]);
      await until(() => /promised:/.test(line('doses ')), 5000, 700);
      const promisedWith = `${line('doses ')}\n${line('events ')}\n${top()}\npid ${pid()}`;
      const promise = await until(() => records().find((r) => / by \d\d:\d\d/.test(r.title)), 8000, 700);
      save('03-promise.txt', promise?.raw ?? records().map((r) => r.raw).join('\n----\n'));
      c(3, 'the promise posts its countdown', !!promise && /chronometerCountDown=[^\n]*true/.test(promise.raw), promise?.raw?.slice(0, 600));
      c(3, 'the reminder goes while promised', !reminder(), reminder()?.raw);
      const promoted = /FLAG_PROMOTED_ONGOING|promoted=true|PROMOTED_ONGOING|isPromoted/i.test(sh('dumpsys notification --noredact'));
      if (sdk >= 36 && sdkFull !== '36.0') c(3, 'the promise is a Live Update', promoted, null);
      else c(3, `no Live Update on this image (${sdkFull})`, !promoted, null);
      const ad = alarms();
      c(3, 'the alarm clock at its deadline ("Já na hora": the deadline is her time)', ad.some((a) => a.clock && near(a.at, deadline, 3000)), `${list(ad)} deadline ${hhmm(deadline)}`);
      home();
      await wait(3000);
      c(3, 'promised: nothing holds the process', !serving(), services().slice(0, 300));
      // Dark, so nothing but the cap ends the ring (the screen timing out would count as the power key).
      sleep();
      const ring2 = await until(() => playing(), deadline + 25_000 - deviceNow(), 500);
      const ring2At = deviceNow();
      c(3, 'at the deadline the alarm rings again, on time', ring2 && ring2At - deadline < ON_TIME, `${((ring2At - deadline) / 1000).toFixed(1)} s`);
      const key2 = soundKey();
      c(3, 'folded: none of its posts sounds', !records().some((r) => r.key === key2), key2);
      const quiet = await until(() => !playing(), 80_000, 1000);
      const quietAt = deviceNow();
      c(3, 'unanswered, it stops at its 60 s cap', quiet && quietAt - ring2At > 50_000 && quietAt - deadline < 75_000, `${((quietAt - ring2At) / 1000).toFixed(0)} s`);
      const r = await posted(deadline + 30_000, deadline + 2 * MIN + 30_000);
      c(3, 'then the thread goes on: a repeat, with her sound and no ring', r && (await sounded(r)) && !playing(), late(r, deadline + 2 * MIN));
      const before3 = `pid ${pid()}\n${sh(`dumpsys activity activities | grep -E "ActivityRecord\\{.*${id}"`)}\n${list(alarms())}`;
      // The phone is still dark and locked from the deadline: opened first, or the app stays paused under the keyguard,
      // its screen what it last drew and taps landing on the keyguard.
      await open();
      launch();
      // What the engine answers, by a call of its own (the fixture's "State"), and what the screen's resource shows: the
      // check is the engine's; a resource still showing the promise is the app runtime's (it is recorded beside it).
      await until(() => line('events '), 8000, 700);
      const shown3 = `${line('events ')} | ${line('doses ')}`;
      await act('State');
      const told = await until(() => find(/^state /)?.text, 8000, 700) ?? '';
      if (!/timeout/.test(shown3) && /timeout/.test(told)) console.log(`   3. the screen's resource still showed ${shown3}; the engine's call said ${told}`);
      save('03-events.txt', [`engine: ${told}\nscreen: ${shown3}\n${top()}\npid ${pid()}`, `after the promise:\n${promisedWith}`, `before the launch:\n${before3}`,
        logcat().split('\n').filter((l) => /pinkoo|nagfixture|exact/i.test(l) && !/attributionTag/.test(l)).slice(-120).join('\n')].join('\n\n----\n'));
      c(3, 'the cap is logged as the alarm\'s timeout', /\bignored:timeout\b/.test(told), `engine: ${told} | screen: ${shown3}`);
    });

    await group(4, 'the bubble over another app', async () => {
      launch();
      const [T] = await plan('Bubble');
      home();
      await stopApp();
      clearLog();
      await untilDevice(T + 20_000);
      // The app under it: the launcher's home screen, empty where the balloon shows. Not Settings, nor Files: they hide
      // every other app's overlay while they show (`HIDE_NON_SYSTEM_OVERLAY_WINDOWS`), so the bubble never appears there.
      home();
      const b = await until(() => balloon(), T + MIN + 25_000 - deviceNow(), 300);
      const seenAt = Date.now();
      shot('04-balloon.png');
      save('04-overlays.txt', overlays().map((w) => `${JSON.stringify({ frame: w.frame, alpha: w.alpha, notTouchable: w.notTouchable, visible: w.visible })}\n${w.block}`).join('\n\n'));
      await wait(1200);
      shot('04-balloon-drawn.png');
      c(4, 'T+1: the face and its balloon over another app', !!b && !!face(), overlays().map((w) => w.block));
      c(4, 'the ring service started from the background, the window first', serving() && !/Background started FGS: Disallowed[^\n]*NagService/.test(logcat()), services().slice(0, 300));
      if (b) {
        const before = top(), screen = nodes().map((n) => n.text).join('|');
        clearLog();
        // A long press there opens the launcher's own menu (Wallpaper, Widgets…) only if the touch passed through.
        sh(`input swipe ${b.x} ${b.y} ${b.x} ${b.y} 1200`);
        await wait(800);
        const log = logcat(), after = nodes().map((n) => n.text).join('|');
        save('04-tap-through.txt', `${before}\n${top()}\n${screen}\n${after}\n${log.split('\n').filter((l) => /InputDispatcher|untrusted|obscur/i.test(l)).join('\n')}`);
        c(4, 'a touch on the balloon reaches the app under it, not Pinkoo', !/Untrusted touch|Dropping untrusted touch/i.test(log) && !ours() && (top() !== before || after !== screen), `${before} -> ${top()}: ${after}`);
        back();
      }
      await wait(Math.max(0, seenAt + 9000 - Date.now()));
      c(4, 'the balloon goes after about 6 s; the face stays', !balloon() && !!face(), overlays().map((w) => w.block));
      home();
      // Seen (not hidden by the app it was over) and laid out, so the tap lands on it.
      const f = await until(() => { const w = face(); return w?.visible && w.frame ? w : null; }, 8000, 500);
      if (f) sh(`input tap ${f.x} ${f.y}`);
      // The nag route of this dose time: its link (`via=bubble`) where the app was already up; a cold start shows the
      // route's title only (the fixture's link line fills on navigation).
      const tapped = !!f && !!(await until(() => ours() && (find(/via=bubble/) || routeOf(T).test(find(/^NAG /)?.text ?? '')), 10000, 700));
      if (!tapped) shot('04-face-tap.png');
      c(4, 'a tap on the face opens the nag route', tapped, `${JSON.stringify(f?.frame)} ${top()} ${find(/^NAG /)?.text ?? ''} ${find(/^\/nag\//)?.text ?? ''}`);
      home();
      const f2 = await until(() => face(), 6000, 500);
      c(4, 'leaving brings the face back', !!f2, null);
      if (f2) sh(`input swipe ${f2.x} ${f2.y} ${W >> 1} ${H - Math.round(H / 12)} 1500`);
      c(4, 'dragged into its nest, it goes', !!f2 && (await until(() => !overlay(), 5000)), overlays().map((w) => w.block));
      await stopApp();
      launch();
      await wait(2500);
      home();
      await wait(3000);
      c(4, 'a new process keeps it in its nest', !overlay(), overlays().map((w) => w.block));
      const r = await posted(T + 2 * MIN - 5000, T + 2 * MIN + 25_000);
      await wait(2000);
      c(4, 'the next step (a repeat) brings no bubble: it lives one step', !!r && !overlay(), `${late(r, T + 2 * MIN)} ${overlays().length}`);
    });

    await group(5, 'Tela cheia that cannot open', async () => {
      launch();
      const [TX, TY, TZ] = await plan('Full screen');
      // Each dose time's Tela cheia in a minute of its own (fixture/app.ts): the post then is that dose time's, which its
      // look says (`lead`), and how it showed (`kind`: REMIND where Android wouldn't open a full screen).
      const fullAt = [TX + 2 * MIN, TY + 2 * MIN, TZ + 2 * MIN];
      const lead = (r, t) => !!r && r.raw.includes(`lead=${new Date(t + offset).toISOString().slice(0, 16)},`) && /Look\(kind=REMIND/.test(r.raw);
      home();
      await stopApp();
      // X's Tela cheia, the access denied (from Android 14: a Play install starts so; Android 12 and 13 have no
      // such switch, so there the phone stays in use for it).
      if (sdk >= 34) {
        sh(`cmd appops set --uid ${id} USE_FULL_SCREEN_INTENT deny`);
        setPin();
        sleep();
        const x = await posted(fullAt[0] - 1500, fullAt[0] + 25_000);
        await wait(3000);
        c(5, 'access denied: Tela cheia is the notification again, on her channel, sounding; nothing opens', lead(x, TX) && remindChannel(x) && !x.fsi && !awake() && !ours() && (await sounded(x)), `${late(x, fullAt[0])} ${x?.raw}`);
        sh(`cmd appops set --uid ${id} USE_FULL_SCREEN_INTENT allow`);
      } else await untilDevice(fullAt[0] + 5000);
      // Y's, the phone in use.
      await open();
      home();
      const y = await posted(fullAt[1] - 1500, fullAt[1] + 25_000);
      await wait(2000);
      shot('05-in-use.png');
      c(5, 'the phone in use: Tela cheia is a heads-up on her channel, sounding; nothing opens', lead(y, TY) && remindChannel(y) && !y.fsi && !ours() && (await sounded(y)), `${late(y, fullAt[1])} ${top()} ${y?.raw}`);
      // Z's, Do Not Disturb on.
      sh('cmd notification set_dnd priority');
      setPin();
      sleep();
      const z = await posted(fullAt[2] - 1500, fullAt[2] + 25_000);
      await wait(3000);
      c(5, 'Do Not Disturb on: Tela cheia is the notification again, and nothing lights the room', lead(z, TZ) && remindChannel(z) && !z.fsi && !awake() && !ours(), `${late(z, fullAt[2])} awake=${awake()} ${z?.raw}`);
      sh('cmd notification set_dnd off');
    });

    await group(6, 'the channels', async () => {
      launch();
      await plan('Channels');
      await wait(1500);
      const all = channels();
      save('06-channels.txt', all.map((ch) => `${ch.id}: ${ch.raw}`).join('\n'));
      const of = (sound) => all.find((ch) => ch.id.startsWith('pinkoo.remind.') && !ch.deleted && (sound === PHONE ? ch.sound.includes(PHONE) : ch.id.includes(sound)));
      const preset = of('sininho'), vibrate = of('vibrate'), silent = of('silent'), phone = of(PHONE);
      c(6, 'one reminder channel per sound the plan names, made with the plan', preset && vibrate && silent && phone, all.map((ch) => ch.id));
      c(6, 'each high importance, on the notification usage', [preset, vibrate, silent, phone].every((ch) => ch && ch.importance === 4 && (!ch.usage || ch.usage === 'USAGE_NOTIFICATION')), [preset, vibrate, silent, phone].map((ch) => ch?.raw));
      c(6, 'the preset plays its raw sound, with a soft vibration', preset && /android\.resource:\/\/[^,]*\/raw\/pinkoo_sininho|android\.resource:\/\/[^,]*\/\d+/.test(preset.sound) && preset.vibrate, preset?.raw);
      c(6, '"Só vibrar" vibrates with no sound; "Sem som" has neither', vibrate && /^(null)?$/.test(vibrate.sound) && vibrate.vibrate && silent && /^(null)?$/.test(silent.sound) && !silent.vibrate, [vibrate?.raw, silent?.raw]);
      c(6, 'the phone sound under a hashed id, with its address', phone && !phone.id.includes('content') && phone.sound.includes(PHONE), phone?.raw);
      const nag = all.find((ch) => ch.id === 'pinkoo.nag');
      c(6, '"Full screen and alarm" is pinkoo.nag', nag?.name === 'Full screen and alarm', nag?.raw);
      c(6, 'no pinkoo.nag.ring', !all.some((ch) => ch.id === 'pinkoo.nag.ring' && !ch.deleted), all.map((ch) => ch.id));
      await plan('One channel');
      await act('Resend');
      const left = channels();
      const live = (ch) => ch && left.some((l) => l.id === ch.id && !l.deleted);
      c(6, 'a later plan that no longer names a sound deletes its channel', live(preset) && !live(vibrate) && !live(silent) && !live(phone), left.map((ch) => `${ch.id}${ch.deleted ? ' (deleted)' : ''}`));
    });

    await group(7, 'exact access revoked', async () => {
      launch();
      const [T] = await plan('Revoke');
      const a0 = alarms();
      c(7, 'armed with exact access: code 0, its backup, the alarm clock', near(code0(a0)?.at, T) && a0.some((a) => a.inexact && near(a.at, T)) && a0.some((a) => a.clock), list(a0));
      home();
      clearLog();
      sh(`cmd appops set ${id} SCHEDULE_EXACT_ALARM deny`);
      const stopped = await until(() => !pid(), 10000, 700);
      const left = alarms();
      save('07-alarms-revoked.txt', left.map((a) => a.block).join('\n\n'));
      c(7, 'taken away: Android stops the app and drops its exact alarms; the inexact backup survives', stopped && !left.some((a) => a.exact || a.clock) && left.some((a) => a.inexact && near(a.at, T, 2000)), list(left));
      const r = await posted(T - 1500, T + 150_000);
      c(7, 'the backup brings the step, late or on time', !!r, late(r, T));
      c(7, 'on her channel, sounding', r && channelOf(r, 'sininho') && (await sounded(r)), r?.raw);
      c(7, 'the alarm started the process', /Start proc .*for broadcast/.test(logcat()), null);
      const a1 = alarms();
      c(7, 'then the steps are armed inexact only: no exact alarm, no alarm clock', a1.some((a) => a.inexact) && !a1.some((a) => a.exact || a.clock), list(a1));
      const r2 = await posted(r?.created ?? T, (r?.created ?? T) + 150_000);
      c(7, 'De novo comes the same way', !!r2, late(r2, (r?.created ?? T) + MIN));
      const r3 = await posted(r2?.created ?? T, (r2?.created ?? T) + 150_000);
      await wait(2000);
      c(7, 'the alarm step is the notification again: on her channel, sounding, no ring', r3 && channelOf(r3, 'sininho') && (await sounded(r3)) && !playing() && !alarmPost(), `${late(r3, (r2?.created ?? T) + MIN)} ${r3?.raw}`);
    });

    await group(8, 'the battery: restricted, the restricted bucket, the rare bucket', async () => {
      const bucket = () => sh(`am get-standby-bucket ${id}`).trim();
      // Background restricted ("Restrito"): RUN_ANY_IN_BACKGROUND ignored.
      sh(`cmd appops set ${id} RUN_ANY_IN_BACKGROUND ignore`);
      launch();
      c(8, 'background restricted, as the app reads it', !!(await until(() => find(/restricted=true/), 10000, 700)), find(/^true /)?.text);
      let [T] = await plan('Battery');
      let a = alarms();
      save('08-restricted-alarms.txt', a.map((x) => x.block).join('\n\n'));
      c(8, 'restricted: code 0 is an alarm clock at the step', a.some((x) => x.clock && near(x.at, T)) && !a.some((x) => x.exact && near(x.at, T)), list(a));
      home();
      sh('dumpsys battery unplug');
      sleep();
      let r = await posted(T - 1500, T + 30_000);
      c(8, 'and the step comes on time all the same', r && r.created - T < ON_TIME, late(r, T));
      await tidy();

      // The restricted bucket, set while the app is in front (only her own use brings an app out of it).
      launch();
      sh(`am set-standby-bucket ${id} restricted`);
      [T] = await plan('Battery');
      a = alarms();
      c(8, 'the restricted bucket: code 0 is an alarm clock too', /^45$|restricted/i.test(bucket()) && a.some((x) => x.clock && near(x.at, T)) && !a.some((x) => x.exact && near(x.at, T)), `${bucket()} ${list(a)}`);
      home();
      sh('dumpsys battery unplug');
      sleep();
      r = await posted(T - 1500, T + 30_000);
      c(8, 'the step comes on time', r && r.created - T < ON_TIME, `${late(r, T)} bucket ${bucket()}`);
      a = alarms();
      c(8, 'and the next one is armed as an alarm clock again', a.some((x) => x.clock && near(x.at, T + MIN, slack(r, T) + 1000)), `${bucket()} ${list(a)}`);
      const r2 = await posted(r?.created ?? T, T + MIN + 30_000);
      c(8, 'which comes on time', r2 && r2.created - (T + MIN) < ON_TIME, late(r2, T + MIN));
      await tidy();

      // The rare bucket, measured: Android's standby quota for wakeup alarms (alarm clocks exempt) after this run's
      // earlier ones. Unplugged: charging lifts the buckets' limits.
      launch();
      [T] = await plan('Rare');
      home();
      sh(`am set-standby-bucket ${id} rare`);
      sh('dumpsys battery unplug');
      sleep();
      const armed = alarms();
      save('08-rare-alarms.txt', `bucket ${bucket()}\n${armed.map((x) => x.block).join('\n\n')}`);
      r = await posted(T - 1500, T + 45_000);
      const ring = await until(() => playing(), T + 2 * MIN + 20_000 - deviceNow(), 500);
      const ringAt = deviceNow();
      const all = reminders();
      save('08-rare-after.txt', `bucket ${bucket()}\n${list(alarms())}\n${all.map((x) => `${x.id} ${hhmm(x.created)}`).join('\n')}`);
      // A measurement, not a check (`docs/architecture.md` › Nagging on the phone, the price): Android's quota for the
      // rare bucket (one wakeup alarm an hour) may hold code 0 back; what must hold either way is the alarm clock.
      const held = !r || r.created - T >= ON_TIME;
      console.log(`MEASURE 8. the rare bucket (${bucket()}): code 0 ${held ? 'held back' : 'on time'}, ${late(r, T)}; its policy ${code0(armed)?.policy || '?'}`);
      c(8, `the rare bucket: the alarm clock rings on time${held ? ', the steps code 0 missed skipped' : ', after the steps'} (code 0 ${held ? 'held back' : 'on time'})`,
        ring && ringAt - (T + 2 * MIN) < ON_TIME && (!held || !all.some((x) => x.created < ringAt - 2000)), `${((ringAt - T - 2 * MIN) / 1000).toFixed(1)} s, ${all.length} posts before it`);
    });

    await group(9, 'Doze', async () => {
      launch();
      const [T] = await plan('Battery');
      home();
      await stopApp();
      sh('dumpsys battery unplug');
      sleep();
      sh('dumpsys deviceidle force-idle deep');
      clearLog();
      c(9, 'forced into deep idle', /IDLE/.test(sh('dumpsys deviceidle get deep')), sh('dumpsys deviceidle get deep'));
      const r = await posted(T - 1500, T + 30_000);
      c(9, 'in deep idle the step comes on time', r && r.created - T < ON_TIME, late(r, T));
      c(9, 'its alarm started the process', /Start proc .*for broadcast/.test(logcat()), null);
      const r2 = await posted(r?.created ?? T, T + MIN + 30_000);
      c(9, 'and the next one a minute later, still in deep idle', r2 && r2.created - (T + MIN) < ON_TIME && /IDLE/.test(sh('dumpsys deviceidle get deep')), late(r2, T + MIN));
      await tidy();

      // At rest: two doses a day, forced deep Doze, on battery.
      launch();
      await plan('Idle');
      home();
      await stopApp();
      sh('dumpsys batterystats --reset');
      sh('dumpsys battery unplug');
      sleep();
      sh('dumpsys deviceidle force-idle deep');
      await wait((fast ? 1 : 5) * MIN);
      const stats = sh(`dumpsys batterystats ${id}`);
      save('09-batterystats.txt', stats);
      // Only the fixture's own block counts (`u0a214:` up to the next uid's): another app's periodic job may wake the
      // phone in the same minutes (Settings' battery job did, 2026-11-19).
      const app = `u0a${Number(uid) - 10000}:`;
      const own = stats.includes(app) ? stats.slice(stats.indexOf(app)).split(/\n {2}(?:u0a\d+|\d+):\n|\n\n/)[0] : stats;
      const wakeups = /Wakeup alarm/.test(own), locks = /Wake lock .*pinkoo/i.test(stats) || /Wake lock/.test(own);
      c(9, 'at rest: no wakeup alarm, no wake lock, the process never started', !wakeups && !locks && !pid(), `uid ${uid}`);
    });

    await group(10, 'a force-stop', async () => {
      launch();
      const [T] = await plan('Soon');
      home();
      sh(`am force-stop ${id}`);
      c(10, 'force-stopped: no alarm left', await until(() => !alarms().length, 5000), list(alarms()));
      await untilDevice(T + 8000);
      c(10, 'nothing comes while stopped', !reminder() && !pid(), null);
      launch();
      const r = await until(() => reminder(), 15000, 700);
      c(10, 'launched: the overdue step comes at once, in front: quiet on the waiting channel', r && r.channel === 'pinkoo.nag.waiting' && !r.fsi, r?.raw);
      const front = await until(() => { const t = line('front '); return t && t !== 'front -' ? t : null; }, 8000, 700) ?? line('front ');
      c(10, 'and told to the app with its step', /^front notify \/nag\//.test(front), front);
    });

    await group(11, 'the experience test', async () => {
      launch();
      await until(() => line('doses ') && line('events '), 8000, 700);
      const before = `${line('doses ')}|${line('events ')}`;
      await press('Test notify');
      // The press reaches the engine through the app's call a moment later: waited for.
      const soon = (as) => as.some((a) => a.exact && a.at > deviceNow() - 2000 && a.at - deviceNow() < 10_000);
      const armed = (await until(() => { const as = alarms(); return soon(as) ? as : null; }, 5000, 400)) ?? alarms();
      home();
      c(11, 'its wait is an exact alarm of its own', soon(armed), list(armed));
      const t = await until(() => records().find(isTest), 16000, 700);
      shot('11-test-notify.png');
      c(11, 'its notification step posts on her channel, with "Stop" and a public version', t && remindChannel(t) && t.actions.includes(TEXT.stop) && /publicVersion=Notification/.test(t.raw), t?.raw);
      c(11, 'it goes by itself after 15 s', await until(() => !records().some(isTest), 22_000, 1000), null);
      launch();
      await wait(1500);
      c(11, 'no state and no event', `${line('doses ')}|${line('events ')}` === before, `${before} -> ${line('doses ')}|${line('events ')}`);
      await press('Test alarm');
      const ring = await until(() => playing(), 5000, 300);
      const ringAt = Date.now();
      const quiet = await until(() => !playing(), 14_000, 500);
      c(11, 'the alarm sample rings and stops at its 10 s cap', ring && quiet && Date.now() - ringAt < 12_500, `${((Date.now() - ringAt) / 1000).toFixed(1)} s`);
      setPin();
      await press('Test full');
      await wait(800);
      sleep();
      const fsi = await until(() => ours() && awake() && keyguard().occluded && find(/via=test/), 20_000, 700);
      shot('11-test-full.png');
      c(11, 'the full-screen step arms at the screen turning off and opens the nag route in test mode over the keyguard', !!fsi, `${top()} ${JSON.stringify(keyguard())}`);
      await press('Stop test', 5000);
    });

    await group(12, 'a reboot mid-thread, locked', async () => {
      launch();
      const [T] = await plan('Reboot');
      home();
      await stopApp();
      sleep();
      const n1 = await posted(T - 1500, T + 25_000);
      c(12, 'T: the notification', !!n1, late(n1, T));
      setPin();
      clearLog();
      adb('reboot');
      await wait(15000);
      adb('wait-for-device');
      await until(() => sh('getprop sys.boot_completed').trim() === '1', 240_000, 2000);
      const bootedAt = deviceNow();
      sleep();
      const lockedUser = () => /RUNNING_LOCKED/.test(sh('dumpsys user'));
      const again = await until(() => { const a = alarms(); return a.some((x) => x.clock) ? a : null; }, 60_000, 2000) ?? alarms();
      save('12-alarms-after-reboot.txt', again.map((a) => a.block).join('\n\n'));
      const late12 = slack(n1, T);
      c(12, 'after the reboot, before the first unlock: De novo armed again, and the alarm clock', lockedUser() && (near(code0(again)?.at, T + 3 * MIN, late12) || bootedAt > T + 3 * MIN) && again.some((a) => a.clock && near(a.at, T + 4 * MIN, late12)), `${list(again)} ${lockedUser() ? 'locked' : 'unlocked'}`);
      const n2 = await posted(T + 3 * MIN - 5000, Math.max(T + 3 * MIN, bootedAt) + 30_000);
      const sounded2 = !!n2 && (await sounded(n2));
      if (!sounded2) {
        // What the system did with it before the first unlock: its sound keys, the record's channel, the audio log.
        const nd = sh('dumpsys notification --noredact');
        save('12-sound.txt', [nd.split('\n').filter((l) => /mSoundNotificationKey|mVibrateNotificationKey|mDisableNotificationEffects|mSystemReady/i.test(l)).join('\n'),
          n2?.raw ?? '', sh('dumpsys audio').split('\n').filter((l) => /ringtone|notification|playAsync|USAGE_NOTIFICATION/i.test(l)).slice(-40).join('\n'),
          logcat().split('\n').filter((l) => /Ringtone|NotifAttention|NotificationService|AudioService|MediaPlayer/i.test(l)).slice(-60).join('\n')].join('\n\n----\n'));
      }
      c(12, 'De novo comes before the first unlock, sounding', n2 && lockedUser() && sounded2, `${late(n2, T + 3 * MIN)} ${lockedUser() ? 'locked' : 'unlocked'} ${soundKey()}`);
      const ring = await until(() => playing(), Math.max(T + 4 * MIN, bootedAt) + 30_000 - deviceNow(), 500);
      const log = logcat();
      save('12-logcat.txt', log.split('\n').filter((l) => /pinkoo|MediaPlayer|FGS/i.test(l)).join('\n'));
      c(12, 'the alarm rings before the first unlock, audibly', ring && lockedUser() && !/no alarm sound|ring: no sound/i.test(log), lockedUser() ? null : 'the user was unlocked');
      await unlock();
      c(12, 'waking the phone to unlock it stops it', await until(() => !playing(), 6000), null);
    });
  } finally {
    clearPin();
    sh(`locksettings set-disabled ${lockDisabled === 'true'}`);
    sh('cmd audio set-ringer-mode NORMAL');
    sh('cmd notification set_dnd off');
    sh('dumpsys deviceidle unforce');
    sh('dumpsys battery reset');
    sh('cmd statusbar collapse');
    restore('secure', 'lock_screen_allow_private_notifications', saved.privateNotifications);
    restore('system', 'notification_cooldown_enabled', saved.cooldown);
    restore('global', 'stay_on_while_plugged_in', saved.stayOn);
    wake();
    sh('wm dismiss-keyguard');
    adb('uninstall', id);
  }
}
