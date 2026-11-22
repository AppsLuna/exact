// @pinkoo/nag on Android: the fixture app (`test/fixture`) built as an APK, which compiles the package's Kotlin and
// merges its manifest fragment under the allow-list. The build is checked for what the grants and the fragment derive:
// the permissions, the two receivers and the service (unexported, direct-boot aware, `systemExempted|specialUse` with
// its subtype), and the roster binding the implementation.
//
// The fixture carries the default preset, Sininho, in `res/raw` (`host.android.rawSounds`, core change C9) as the app
// does: the engine names it as a channel's sound and plays it for the alarm. Its file is synthesized here, a short
// two-note chime as 16-bit PCM WAV, so the build needs no encoder.
//
// With `--emulator <serial>` it then installs the APK on that running emulator and drives it as a person would, with
// real launches, `uiautomator` and `adb` (android-drive.mjs, its twelve groups). What needs a phone is checklist.md's.
//
// usage: bun packages/pinkoo-nag/test/android.mjs [--emulator <serial>] [--out <dir>] [--keep <dir>] [--only 1,8]
//          [--fast] [--api33] [--abis <abis>]
//   --abis       the fixture APK's ABIs, as `agent-android.mjs build --abis` takes them (default arm64-v8a); an
//                x86_64 emulator, as on a CI runner, takes x86_64
//   (ANDROID_HOME, an NDK of clang 18 or newer, and a JDK 17 or newer in JAVA_HOME; `--keep` builds in <dir> and
//   leaves it, so a second run is warm)
//   --only 1,8   drives only those groups of android-drive.mjs (each sends its own plan); `--steps` is the same flag
//   --fast       each dose 30 to 90 s out instead of 60 to 120, and one minute of forced Doze at rest instead of five
//   --api33      the groups an Android 12 or 13 image differs in (android-drive.mjs API33), on such an emulator: full
//                screen allowed by default, exact alarms, no differences in the notification permission
//   `--only` and `--fast` are for iterating only. Work that touches the engine is finished only after a whole run with
//   the fixture's own timings passes: `--emulator <serial>` with neither flag (and `--api33` on the Android 13 image).
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { checker } from '../../camera/test/fixture.mjs';
import { ensureShells } from '../../../scripts/app-shells.mjs';
import { apkBuildDir } from '../../../scripts/agent-android.mjs';
import { androidPackageId } from '../../../scripts/app.mjs';

const here = import.meta.dir, pkg = resolve(here, '..'), root = resolve(pkg, '../..');
const argv = process.argv.slice(2), flag = (f) => (argv.includes(f) ? argv[argv.indexOf(f) + 1] : null);
const emulator = flag('--emulator'), out = flag('--out'), keep = flag('--keep'), abis = flag('--abis');
const only = (flag('--only') ?? flag('--steps'))?.split(',').map(Number).filter((n) => n > 0) ?? null;
const fast = argv.includes('--fast'), api33 = argv.includes('--api33');
const run = (cmd, args, cwd, env = process.env, ok = false) => {
  const r = spawnSync(cmd, args, { cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 1 << 28 });
  if (r.status !== 0 && !ok) throw new Error(`${cmd} ${args.join(' ')}: ${r.stdout.slice(-4000)}\n${r.stderr.slice(-4000)}`);
  return r.stdout;
};
const { check, failed } = checker();

/** Sininho for the fixture: a soft two-note chime (E6 then B6, each decaying), 1 s of 16-bit mono PCM at 22.05 kHz, the
 * same bytes every time. */
function chime() {
  const rate = 22050, n = rate, data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const t = i / rate, note = (f, at) => (t < at ? 0 : Math.sin(2 * Math.PI * f * (t - at)) * Math.exp(-(t - at) * 5));
    data.writeInt16LE(Math.round(0.4 * 32767 * (note(1318.51, 0) + note(1975.53, 0.18)) / 1.6), i * 2);
  }
  const head = Buffer.alloc(44);
  head.write('RIFF', 0); head.writeUInt32LE(36 + data.length, 4); head.write('WAVE', 8); head.write('fmt ', 12);
  head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22); head.writeUInt32LE(rate, 24);
  head.writeUInt32LE(rate * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34); head.write('data', 36); head.writeUInt32LE(data.length, 40);
  return Buffer.concat([head, data]);
}

const dir = keep ? resolve(keep) : mkdtempSync(join(tmpdir(), 'pinkoo-nag-')), name = 'nagfixture', app = join(dir, name);
try {
  if (!existsSync(join(app, 'app.json'))) run('bun', [resolve(root, 'scripts/exact.mjs'), 'new', app, '--with', 'android'], root);
  for (const f of ['app.contract', 'app.ts']) cpSync(join(here, 'fixture', f), join(app, f));
  rmSync(join(app, 'app.test.contract'), { force: true });
  mkdirSync(resolve(app, 'strings'), { recursive: true });
  const strings = resolve(app, 'strings/en.json');
  writeFileSync(strings, JSON.stringify({ ...(existsSync(strings) ? JSON.parse(readFileSync(strings, 'utf8')) : {}), ...JSON.parse(readFileSync(join(here, 'fixture/strings/en.json'), 'utf8')) }, null, 2));
  mkdirSync(join(app, 'sounds'), { recursive: true });
  writeFileSync(join(app, 'sounds/pinkoo_sininho.wav'), chime());
  const appJson = JSON.parse(readFileSync(join(app, 'app.json'), 'utf8'));
  appJson.host = { ...appJson.host, android: { ...appJson.host?.android, rawSounds: ['sounds/pinkoo_sininho.wav'] } };
  writeFileSync(join(app, 'app.json'), JSON.stringify(appJson, null, 2));
  const id = appJson.app.id;
  writeFileSync(join(app, 'app.ts'), readFileSync(join(app, 'app.ts'), 'utf8').replace('__APP_ID__', id));
  const manifest = JSON.parse(readFileSync(join(app, 'package.json'), 'utf8'));
  manifest.dependencies = { ...manifest.dependencies, '@pinkoo/nag': `file:${pkg}` };
  writeFileSync(join(app, 'package.json'), JSON.stringify(manifest, null, 2));
  run('bun', ['install'], app);
  ensureShells(app);
  const env = { ...process.env, EXACT_APP_DIR: app, EXACT: root, HERMES_LEAN_SYS_OFFLINE: '1' };
  const built = spawnSync('bun', [resolve(root, 'scripts/agent-android.mjs'), 'build', name, '--apk', ...(abis ? ['--abis', abis] : [])], { cwd: root, env, encoding: 'utf8', maxBuffer: 1 << 28 });
  check('android', 'the fixture builds as an APK', built.status === 0, (built.stdout + built.stderr).slice(-3000));

  const build = apkBuildDir(app);
  const roster = existsSync(resolve(build, 'extensions/exact/app/ExactExtensions.kt')) ? readFileSync(resolve(build, 'extensions/exact/app/ExactExtensions.kt'), 'utf8') : '';
  check('android', 'the roster binds the implementation, made at load', roster.includes('"nag" to { c -> pinkoo.nag.NagModule(c) }') && /startAtLoad[^\n]*"nag"/.test(roster), roster);
  const merged = existsSync(build) && readdirSync(build, { recursive: true }).find((f) => String(f).includes('merged_manifests/release/') && String(f).endsWith('AndroidManifest.xml'));
  const xml = merged ? readFileSync(resolve(build, merged), 'utf8') : '';
  const permissions = ['SCHEDULE_EXACT_ALARM', 'POST_NOTIFICATIONS', 'RECEIVE_BOOT_COMPLETED', 'WAKE_LOCK', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SYSTEM_EXEMPTED',
    'USE_FULL_SCREEN_INTENT', 'SYSTEM_ALERT_WINDOW', 'FOREGROUND_SERVICE_SPECIAL_USE', 'POST_PROMOTED_NOTIFICATIONS', 'VIBRATE'];
  const missing = permissions.filter((p) => !new RegExp(`<uses-permission\\s+android:name="android\\.permission\\.${p}"`).test(xml));
  check('android', 'the grants and the fragment derive every permission the engine uses', !missing.length, `missing ${missing.join(', ')}`);
  const element = (tag, cls) => xml.match(new RegExp(`<${tag}[^>]*android:name="pinkoo\\.nag\\.${cls}"[^>]*>`))?.[0] ?? '';
  const service = element('service', 'NagService'), receiver = element('receiver', 'NagReceiver'), system = element('receiver', 'NagSystemReceiver');
  check('android', 'two receivers and the service, none exported, each directBootAware',
    [service, receiver, system].every((e) => /android:exported="false"/.test(e) && /android:directBootAware="true"/.test(e)), [service, receiver, system]);
  check('android', 'the service is systemExempted and specialUse, with its subtype',
    /android:foregroundServiceType="systemExempted\|specialUse"/.test(service) && /PROPERTY_SPECIAL_USE_FGS_SUBTYPE/.test(xml), service);
  check('android', 'the system receiver hears the boot before unlock, the clock, the zone and the exact-alarm switch',
    ['LOCKED_BOOT_COMPLETED', 'TIME_SET', 'TIMEZONE_CHANGED', 'SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED'].every((a) => xml.includes(a)), merged ?? 'no merged manifest');
  const apk = resolve(build, 'outputs/apk/release/app-release.apk');
  const dex = spawnSync('unzip', ['-p', apk, 'classes*.dex'], { maxBuffer: 1 << 28 }).stdout ?? Buffer.alloc(0);
  const absent = ['NagReceiver', 'NagSystemReceiver', 'NagService'].filter((c) => !dex.includes(`Lpinkoo/nag/${c};`));
  check('android', "the fragment's classes are compiled in under their names", !absent.length, `missing ${absent.join(', ')}`);
  const files = spawnSync('unzip', ['-l', apk], { encoding: 'utf8', maxBuffer: 1 << 26 }).stdout ?? '';
  const table = spawnSync('unzip', ['-p', apk, 'resources.arsc'], { maxBuffer: 1 << 28 }).stdout ?? Buffer.alloc(0);
  const named = table.includes('pinkoo_sininho') || table.includes(Buffer.from('pinkoo_sininho', 'utf16le'));
  check('android', 'the default preset ships as a raw resource (host.android.rawSounds)', /res\/\S*\.wav\b/.test(files) && named, files.split('\n').filter((l) => /res\/raw|\.wav/.test(l)).join('\n') || 'no raw sound');

  if (emulator) {
    if (out) mkdirSync(out, { recursive: true });
    // The drive speaks to adb, which knows the Android package (the app's id with `-` as `_`), not the app's id.
    const packageId = androidPackageId(JSON.parse(readFileSync(join(app, 'app.json'), 'utf8')));
    await import('./android-drive.mjs').then((m) => m.drive({ root, apk, id: packageId, emulator, check, run, out, only, fast, api33 }));
  }
} finally {
  if (!keep) rmSync(dir, { recursive: true, force: true });
}
console.log(failed() ? `${failed()} failed` : 'all passed');
process.exit(failed() ? 1 : 0);
