// @pinkoo/nag on Android: the fixture app (`test/fixture`) built as an APK, which compiles the package's Kotlin and
// merges its manifest fragment under the allow-list. The build is checked for what the grants and the fragment derive:
// the permissions, the two receivers and the service (unexported, direct-boot aware, `systemExempted|specialUse` with
// its subtype), and the roster binding the implementation.
//
// With `--emulator <serial>` it then installs the APK on that running emulator and drives it as a person would, with
// real launches, `uiautomator` and `adb` (android-drive.mjs). What needs a phone is checklist.md's.
//
// usage: bun packages/pinkoo-nag/test/android.mjs [--emulator <serial>] [--out <dir>] [--keep <dir>] [--steps 1,12]
//   (ANDROID_HOME, an NDK of clang 18 or newer, and a JDK 17 or newer in JAVA_HOME; `--keep` builds in <dir> and
//   leaves it, so a second run is warm; `--steps` drives only those steps of android-drive.mjs, for iterating: the
//   whole drive still runs before a change is done)
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
const emulator = flag('--emulator'), out = flag('--out'), keep = flag('--keep');
const only = flag('--steps')?.split(',').map(Number).filter((n) => n > 0) ?? null;
const run = (cmd, args, cwd, env = process.env, ok = false) => {
  const r = spawnSync(cmd, args, { cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 1 << 28 });
  if (r.status !== 0 && !ok) throw new Error(`${cmd} ${args.join(' ')}: ${r.stdout.slice(-4000)}\n${r.stderr.slice(-4000)}`);
  return r.stdout;
};
const { check, failed } = checker();

const dir = keep ? resolve(keep) : mkdtempSync(join(tmpdir(), 'pinkoo-nag-')), name = 'nagfixture', app = join(dir, name);
try {
  if (!existsSync(join(app, 'app.json'))) run('bun', [resolve(root, 'scripts/exact.mjs'), 'new', app, '--with', 'android'], root);
  for (const f of ['app.contract', 'app.ts']) cpSync(join(here, 'fixture', f), join(app, f));
  rmSync(join(app, 'app.test.contract'), { force: true });
  mkdirSync(resolve(app, 'strings'), { recursive: true });
  const strings = resolve(app, 'strings/en.json');
  writeFileSync(strings, JSON.stringify({ ...(existsSync(strings) ? JSON.parse(readFileSync(strings, 'utf8')) : {}), ...JSON.parse(readFileSync(join(here, 'fixture/strings/en.json'), 'utf8')) }, null, 2));
  const id = JSON.parse(readFileSync(join(app, 'app.json'), 'utf8')).app.id;
  writeFileSync(join(app, 'app.ts'), readFileSync(join(app, 'app.ts'), 'utf8').replace('__APP_ID__', id));
  const manifest = JSON.parse(readFileSync(join(app, 'package.json'), 'utf8'));
  manifest.dependencies = { ...manifest.dependencies, '@pinkoo/nag': `file:${pkg}` };
  writeFileSync(join(app, 'package.json'), JSON.stringify(manifest, null, 2));
  run('bun', ['install'], app);
  ensureShells(app);
  const env = { ...process.env, EXACT_APP_DIR: app, EXACT: root, HERMES_LEAN_SYS_OFFLINE: '1' };
  const built = spawnSync('bun', [resolve(root, 'scripts/agent-android.mjs'), 'build', name, '--apk'], { cwd: root, env, encoding: 'utf8', maxBuffer: 1 << 28 });
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

  if (emulator) {
    if (out) mkdirSync(out, { recursive: true });
    // The drive speaks to adb, which knows the Android package (the app's id with `-` as `_`), not the app's id.
    const packageId = androidPackageId(JSON.parse(readFileSync(join(app, 'app.json'), 'utf8')));
    await import('./android-drive.mjs').then((m) => m.drive({ root, apk, id: packageId, emulator, check, run, out, only }));
  }
} finally {
  if (!keep) rmSync(dir, { recursive: true, force: true });
}
console.log(failed() ? `${failed()} failed` : 'all passed');
process.exit(failed() ? 1 : 0);
