import type { Answer, Sources } from './app.contract.d.ts';
import { ackNagEvents, nagScreen, nagState, rehearse, resolveNags, scheduleNags, stopRehearsal } from '@pinkoo/nag';

// The suite writes the id `exact new` gave the app. The fixture's texts are literals: it is a test, not an app. The
// drive (android-drive.mjs, `TEXT` and `LINES`) reads several of them back from Android: keep the two the same.
export const appId = '__APP_ID__';
export const grants = 'device.alarms purpose.alarms\ndevice.notifications purpose.notifications\ndevice.full-screen-intent\ndevice.overlay';

const pad = (n: number) => String(n).padStart(2, '0');
/** A local wall-clock minute, `2026-11-18T08:00`, in the phone's zone (a `Date` from a value knows it). */
const local = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
/** [local]'s minute back to an instant, in the phone's zone. */
const instant = (minute: string) => {
  const [d, t] = minute.split('T'), [y, mo, da] = d.split('-').map(Number), [h, mi] = t.split(':').map(Number);
  return new Date(y, mo - 1, da, h, mi).getTime();
};
const minuteAfter = (now: number, sec: number) => Math.ceil((now + sec * 1000) / 60_000) * 60_000;
const MIN = 60_000;

// One line per rung (0 to 4), so the drive tells the rung from the text: the reminders that reached her before this
// one, up to 4 (`Steps.kt`, `Post.rung`).
const lines = [['Psst, it is time.'], ['Here I am again.'], ['Still here for you.'], ['One more time, gently.'], ['Whenever you are ready.']];
const SOUND_NAMES: Record<string, string> = { sininho: 'Little bell', vibrate: 'Vibrate only', silent: 'No sound' };
/** A phone sound, as `pickNagSound` answers one: Android's default notification sound, readable by every app. */
const PHONE = 'content://settings/system/notification_sound';
const texts = (sounds: string[]) => ({
  title: 'Time for {names}', body: 'Due at {time}', publicTitle: 'A reminder', publicBody: 'Unlock to see it',
  take: 'Take now', promise: 'Pinky promise', notNow: 'Not now', pending: '{names} is waiting', deadline: 'The time you promised: {names}',
  promiseChip: '{names} by {time}', bubble: 'Tap here, it is time', lines,
  notNowRemind: 'Okay: again at {time}.', notNowAlarm: 'Okay: the alarm rings at {time}.', notNowWait: 'Okay: it waits on Today.',
  alarmTitle: 'Medication alarm', alarmBody: 'Stop silences it; the dose keeps waiting.', stop: 'Stop', service: 'The fixture is around',
  channelNag: 'Full screen and alarm', channelPending: 'Waiting reminders', channelNotice: 'Notices',
  channels: [...new Set(sounds)].map((sound) => ({ sound, name: `Reminder · ${SOUND_NAMES[sound] ?? 'Phone sound'}` })),
});
// A round body with a belly, eyes, cheeks and a smile: enough to see the bubble and the picture are the plan's. Its
// parts travel whole as JSON, each with only the fields it uses.
const mascot = {
  viewBox: [0, 0, 200, 200],
  parts: [
    { t: 'ellipse', cx: 100, cy: 110, rx: 72, ry: 66, fill: '#FF7A59' },
    { t: 'ellipse', cx: 100, cy: 128, rx: 44, ry: 36, fill: '#FFE6DC' },
    { t: 'circle', cx: 76, cy: 96, r: 9, fill: '#2B2233' },
    { t: 'circle', cx: 124, cy: 96, r: 9, fill: '#2B2233' },
    { t: 'circle', cx: 62, cy: 116, r: 8, fill: '#FF9DB0', op: 0.8 },
    { t: 'circle', cx: 138, cy: 116, r: 8, fill: '#FF9DB0', op: 0.8 },
    { t: 'path', d: 'M88 118 Q100 130 112 118', fill: null, stroke: '#2B2233', sw: 4, cap: 'round' },
    { t: 'path', d: 'M100 44 C96 30 108 24 112 34', fill: null, stroke: '#2B2233', sw: 5, cap: 'round', tf: 'rotate(-10 100 44)' },
  ],
};

type Alarm = 'off' | 'afterAll' | 'onTime';
type Dose = { at: number; sound: string; alarm: Alarm };
type Scenario = {
  ladder: { firstMin: number; thenMin: number; repeatFirstMin: number; repeatEveryMin: number; repeats: number };
  steps: { bubble: boolean; fullScreen: boolean; unlock: boolean };
  /** The doses, from the first one's minute [t] (each its own dose time). */
  doses: (t: number) => Dose[];
  /** How far out the first dose is, in seconds before its whole minute (the drive's own work comes first). */
  lead?: number;
};
const ladder = (firstMin: number, thenMin: number, repeatFirstMin: number, repeatEveryMin: number, repeats: number) =>
  ({ firstMin, thenMin, repeatFirstMin, repeatEveryMin, repeats });
const none = { bubble: false, fullScreen: false, unlock: false };

// The drive's scenarios, each a button (app.contract) and a group of android-drive.mjs. The paces are minutes, so a
// whole thread fits in a few: the engine takes a 1-minute interval as it takes Meu jeito's 5.
const SCENARIOS: Record<string, Scenario> = {
  // 1. Every step on, the alarm "Só se passar de tudo": T, T+1 (the bubble), T+3 (the full screen), T+5 (the alarm),
  // then three repeats a minute apart.
  thread: { ladder: ladder(1, 2, 1, 1, 3), steps: { bubble: true, fullScreen: true, unlock: true }, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'afterAll' }] },
  // 2. Two dose times three minutes apart, the alarm on only for the second (on another sound).
  two: { ladder: ladder(1, 1, 1, 1, 0), steps: { bubble: false, fullScreen: true, unlock: false },
    doses: (t) => [{ at: t, sound: 'sininho', alarm: 'off' }, { at: t + 3 * MIN, sound: 'vibrate', alarm: 'afterAll' }] },
  // 3. "Já na hora": the alarm with the dose time's notification folded into it, then De novo and one repeat.
  ontime: { ladder: ladder(1, 1, 2, 1, 1), steps: none, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'onTime' }] },
  // 4. The bubble alone: T, T+1 (the bubble), T+2 (one repeat).
  bubble: { ladder: ladder(1, 1, 1, 1, 1), steps: { bubble: true, fullScreen: false, unlock: false }, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'off' }] },
  // 5. Three dose times three minutes apart, each reaching Tela cheia in a minute of its own (T+2, T+5, T+8): no other
  // dose time's step falls in it, so the post at that minute is that Tela cheia's.
  fullscreen: { ladder: ladder(1, 1, 1, 1, 0), steps: { bubble: false, fullScreen: true, unlock: false },
    doses: (t) => [0, 1, 2].map((i) => ({ at: t + 3 * i * MIN, sound: 'sininho', alarm: 'off' as Alarm })) },
  // 6. Every kind of sound, hours ahead: their channels are made with the plan.
  channels: { ladder: ladder(20, 15, 30, 60, 3), steps: none, lead: 6 * 3600,
    doses: (t) => ['sininho', 'vibrate', 'silent', PHONE].map((sound, i) => ({ at: t + i * 60 * MIN, sound, alarm: 'off' as Alarm })) },
  // 6. Then only the preset: the other channels go.
  onechannel: { ladder: ladder(20, 15, 30, 60, 3), steps: none, lead: 6 * 3600, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'off' }] },
  // 7. The alarm on with no other step: T, T+1 (De novo), T+2 (the alarm, a notification without exact access).
  revoke: { ladder: ladder(1, 1, 1, 1, 0), steps: none, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'afterAll' }] },
  // 8 and 9. The notification and De novo, nothing else.
  battery: { ladder: ladder(1, 1, 1, 1, 0), steps: none, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'off' }] },
  // 8. The rare bucket: T, T+1, then the alarm at T+2 (its alarm clock is never held back).
  rare: { ladder: ladder(1, 1, 1, 1, 0), steps: none, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'afterAll' }] },
  // 9. Two doses a day, far from now: what the battery's idle window measures.
  idle: { ladder: ladder(20, 15, 30, 60, 3), steps: none, lead: 6 * 3600, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'off' }, { at: t + 12 * 60 * MIN, sound: 'sininho', alarm: 'off' }] },
  // 10. The next whole minute (a force-stop comes first).
  soon: { ladder: ladder(1, 1, 1, 1, 0), steps: none, lead: 1, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'off' }] },
  // 12. A reboot between the notification (T) and De novo (T+3), the alarm at T+4.
  reboot: { ladder: ladder(3, 1, 1, 1, 0), steps: none, doses: (t) => [{ at: t, sound: 'sininho', alarm: 'afterAll' }] },
};

/** The plan of [name] for doses [ds]: what Pinkoo's data module sends (`src/app/plan.ts`), with the fixture's texts. */
const plan = (now: number, name: string, ds: Dose[], volume: number) => {
  const s = SCENARIOS[name];
  return {
    v: 2, made: now, zone: '', locale: 'en', ladder: s.ladder, steps: s.steps, alarm: { volume }, liveUpdate: true,
    // The scenario rides in the dose's key, so `Resend` builds the same plan again from the engine's state.
    doses: ds.map((d, i) => ({ key: `${name}.${i}@${local(d.at)}`, group: local(d.at), name: `Pill ${'ABCD'[i]}`, miss: local(d.at + 24 * 60 * MIN),
      promiseUntil: null, lineSeed: 0, sound: d.sound, alarm: d.alarm })),
    texts: texts(ds.map((d) => d.sound)), mascot, mascots: [mascot], stock: null,
    idle: { afterDays: 6, title: 'Open the fixture', body: 'to keep the reminders going' },
  };
};

/** The drive's `--fast`: each dose sooner (its whole minute 30 to 90 s out instead of 60 to 120). Lives as long as the
 * process; the drive presses "Fast" before each plan. */
let fast = false;

/** The rehearsal (`rehearse`) as the experience test sends it: its own texts, mascot and sound, no plan needed. */
const test = (step: 'notify' | 'bubble' | 'full' | 'alarm', after: number) => rehearse({
  step, after, quiet: false, title: 'Reminder test', body: 'Just a test', line: lines[0][0], publicTitle: 'Fixture · Reminder test',
  stop: 'Stop', channel: 'Reminder · Little bell', mascot: JSON.stringify(mascot), sound: 'sininho', volume: 0.6, route: '/nag/test?via=test',
});

const sources: Sources = {
  planNags: async ([now, name]) => {
    const s = SCENARIOS[name];
    const t = minuteAfter(now, s.lead ?? (fast ? 30 : 60));
    const ds = s.doses(t);
    await scheduleNags({ plan: plan(now, name, ds, 0.6) });
    return { text: ds.map((d) => local(d.at)).join(' ') };
  },
  actNags: async ([what, now]) => {
    const s = await nagState({});
    const keys = s.doses.filter((d) => d.state !== 'resolved' && d.state !== 'lapsed').map((d) => d.key);
    if (what === 'seen') await nagScreen({ showing: true, keys });
    else if (what === 'leave') await nagScreen({ showing: false, keys: [] });
    else if (what === 'promised') {
      const until = now + 90_000;
      await resolveNags({ keys, kind: 'promised', until });
      return { text: `promised ${keys.length} until ${until}` };
    } else if (what === 'ack') { const last = s.events.at(-1); if (last) await ackNagEvents({ through: last.id }); }
    else if (what === 'fast') fast = true;
    // What the engine answers now, read by a call of its own rather than the `doses` resource the screen shows.
    else if (what === 'state') return { text: `state ${s.doses.map((d) => `${d.state}:${d.step ?? '-'}:${d.fires}`).join(',')} | ${s.events.map((v) => `${v.kind}:${v.reason ?? '-'}`).join(',')}` };
    else if (what === 'resend') {
      // The plan of the engine's doses again, with a changed setting (the alarm's volume), as Pinkoo sends one after
      // a change in Ajustes: the engine reads it whole again.
      const first = s.doses.map((d) => d.key.match(/^(\w+)\.0@(.+)$/)).filter((m): m is RegExpMatchArray => m !== null).at(-1);
      const name = first?.[1];
      if (first && name && SCENARIOS[name]) {
        const ds = SCENARIOS[name].doses(instant(first[2]));
        await scheduleNags({ plan: plan(now, name, ds, 0.7) });
      }
      return { text: `resend ${name ?? 'nothing'}` };
    } else if (what === 'testNotify') await test('notify', 8000);
    else if (what === 'testAlarm') await test('alarm', 0);
    else if (what === 'testFull') await test('full', 0);
    else if (what === 'stopTest') await stopRehearsal({});
    else await resolveNags({ keys, kind: 'taken' });
    return { text: `${what} ${keys.length}` };
  },
};

export const answer: Answer = (source, args, store, storage, native) => sources[source](args, store, storage, native);
