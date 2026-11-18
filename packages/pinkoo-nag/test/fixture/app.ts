import type { Answer, Sources } from './app.contract.d.ts';
import { ackNagEvents, nagScreen, nagState, previewNag, resolveNags, scheduleNags } from '@pinkoo/nag';

// The suite writes the id `exact new` gave the app. The fixture's texts are literals: it is a test, not an app.
export const appId = '__APP_ID__';
export const grants = 'device.alarms purpose.alarms\ndevice.notifications purpose.notifications\ndevice.full-screen-intent\ndevice.overlay';

const pad = (n: number) => String(n).padStart(2, '0');
/** A local wall-clock minute, `2026-11-18T08:00`, in the phone's zone (a `Date` from a value knows it). */
const local = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const minuteAfter = (now: number, sec: number) => Math.ceil((now + sec * 1000) / 60_000) * 60_000;

const texts = {
  title: 'Time for {names}', body: 'Due at {time}', publicTitle: 'A reminder', publicBody: 'Unlock to see it',
  take: 'Take now', promise: 'Pinky promise', notNow: 'Not now', pending: '{names} is waiting', promiseChip: '{names} by {time}',
  bubble: 'Open the reminder', channelNag: 'Reminders', channelPending: 'Waiting reminders', channelNotice: 'Notices',
  channelRing: 'Reminders with sound',
  lines: [['Psst, it is time.', 'Your turn now.'], ['Still waiting here.'], ['I am not going anywhere.'], ['Every unlock, until you do.'],
    ['Top of the ladder.']],
};
// One rung per ignore up to the top, 4, which adds nothing of its own: Pinkoo's plan (src/app/plan.ts).
const rungs = [
  { ignores: 0, add: ['ring', 'fullScreen'] },
  { ignores: 1, add: ['returning'] },
  { ignores: 2, add: ['bubble'] },
  { ignores: 3, add: ['unlock'] },
  { ignores: 4, add: [] },
];
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

// A ring time of 0 asks for the whole ladder from the first nag, with a 20-second ring.
const plan = (now: number, times: number[], firstMin: number, repeatMin: number, ringSec: number) => ({
  v: 1, made: now, zone: '', locale: 'en', ladder: { firstMin, repeatMin, ringSec: ringSec || 20 },
  rungs: ringSec ? rungs : [{ ignores: 0, add: rungs.flatMap((r) => r.add) }], liveUpdate: true, volume: [0.6, 0.8, 1],
  doses: times.map((t) => ({ key: `pill@${local(t)}`, group: local(t), name: 'Pill A', miss: local(t + 24 * 3_600_000), promiseUntil: null, lineSeed: 0 })),
  texts, mascot, mascots: [mascot], stock: { title: 'Pill A is running low', body: '3 left' },
  idle: { afterDays: 6, title: 'Open the fixture', body: 'to keep the reminders going' },
});

const sources: Sources = {
  planNags: async ([now, delaySec, firstMin, repeatMin, ringSec]) => {
    const at = minuteAfter(now, delaySec);
    await scheduleNags({ plan: plan(now, [at], firstMin, repeatMin, ringSec) });
    return { text: local(at) };
  },
  // Two doses a day, far from now: what the battery's idle window measures.
  planIdle: async ([now]) => {
    const times = [minuteAfter(now, 6 * 3600), minuteAfter(now, 18 * 3600)];
    await scheduleNags({ plan: plan(now, times, 20, 15, 60) });
    return { text: times.map(local).join(' ') };
  },
  actNags: async ([what, now]) => {
    const s = await nagState({});
    const keys = s.doses.filter((d) => d.state !== 'resolved' && d.state !== 'lapsed').map((d) => d.key);
    if (what === 'seen') await nagScreen({ showing: true, keys });
    else if (what === 'leave') await nagScreen({ showing: false, keys: [] });
    else if (what === 'promised') await resolveNags({ keys, kind: 'promised', until: now + 90_000 });
    else if (what === 'ack') { const last = s.events.at(-1); if (last) await ackNagEvents({ through: last.id }); }
    else if (what === 'preview') await previewNag({ rung: 1 });
    else await resolveNags({ keys, kind: 'taken' });
    return { text: `${what} ${keys.length}` };
  },
};

export const answer: Answer = (source, args, store, storage, native) => sources[source](args, store, storage, native);
