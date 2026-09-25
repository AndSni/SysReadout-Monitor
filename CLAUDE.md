# SysReadout Monitor — project instructions

You are building **SysReadout Monitor**: a dedicated Android system-monitor app that shows everything
the phone will reveal about itself, grouped into pages you swipe between, drawn as a KDE Konsole
terminal on Fedora. It is a fork and rework of **SysReadout Launcher** (`/home/asni/SysReadout`,
public repo `github.com/AndSni/SysReadout-Launcher`), which already reads all of this data.

**Start by reading [`docs/BRIEF.md`](docs/BRIEF.md)**: scope, what to keep and drop from the launcher,
every page, milestones and acceptance criteria. This file holds the rules that stay true for the
whole project.

## How the user works with you

- **Work autonomously.** The user does not want to approve steps or be asked routine questions.
  `.claude/settings.json` in this folder grants the tools, provided Claude Code was started *in this
  folder*. Ask only for decisions that are really theirs: the final name, creating the GitHub repo,
  publishing anything (store submission, release tags).
- **Don't drive the UI with `adb shell input tap`** to reach a screen. The user tests the UI by hand.
  To see a state yourself, add a **debug-only** intent extra (see "Debug aids" below) and take a
  screenshot with `adb exec-out screencap -p`.
- **Verify, don't assume.** Earlier work on this code caught real bugs only by checking: name
  availability against the real store indexes, reproducible builds by diffing APKs, release asset
  URLs by downloading them. Report failures plainly, with output.
- **Text, not icons.** On a terminal screen, values are text: signal strength is written as
  *very strong … very weak / no signal*, not drawn as bars; no emoji or pictogram glyphs.
  Plain-ASCII meters like htop's `[|||||     37%]` are fine; they are terminal text.
- Keep commits focused; end commit messages with the `Co-Authored-By` trailer the harness gives you.
  Commit or push only when a milestone is done or the user asks.

## The look: Konsole defaults, exactly

The target is what Konsole shows on the user's machine with nothing customised (verified on their
Fedora 44 KDE Plasma install with Konsole 26.08: default profile, no font override). There is
**one** style. No themes, no presets, no font choice; only the text size can change.

**Colour scheme: Konsole "Breeze"** (from KDE's `konsole/data/color-schemes/Breeze.colorscheme`):

| Slot | Normal | Intense (bold) | Faint |
|---|---|---|---|
| Background | `#232627` | `#000000` | `#31363B` |
| Foreground | `#FCFCFC` | `#3DAEE9` | `#EFF0F1` |
| 0 black | `#232627` | `#7F8C8D` | `#31363B` |
| 1 red | `#ED1515` | `#C0392B` | `#783228` |
| 2 green | `#11D116` | `#1CDC9A` | `#17A262` |
| 3 yellow (Breeze's is orange) | `#F67400` | `#FDBC4B` | `#B65619` |
| 4 blue | `#1D99F3` | `#3DAEE9` | `#1B668F` |
| 5 magenta | `#9B59B6` | `#8E44AD` | `#614A73` |
| 6 cyan | `#1ABC9C` | `#16A085` | `#186C60` |
| 7 white | `#FCFCFC` | `#FFFFFF` | `#63686D` |

**Font: Hack**, Regular and Bold (Plasma's default fixed-width font, 10 pt on the desktop).
Bundle it from `github.com/source-foundry/Hack` releases (v3.003, MIT + Bitstream Vera licence, fine
for GPL and F-Droid) with its licence in `assets/licenses/`. Default size about 12 sp on a phone;
pinch to zoom between 8 and 20 sp, remembered.

**Prompt: Fedora's default bash prompt** `[\u@\h \W]\$ `, uncoloured, e.g. `[user@xperia-10-iv ~]$`.
Use `user` as the user name and the phone's name (Settings.Global `device_name`, lowercased,
spaces as hyphens, falling back to `Build.MODEL`) as the host.

**Colour meaning** (use sparingly, like `ls --color`, `htop` or `sensors` would):

| Meaning | Colour |
|---|---|
| Normal text and values | Foreground `#FCFCFC` |
| Row keys / labels | Intense foreground `#3DAEE9` (bold) |
| Good / normal state, meter fill below the warning level | green `#11D116` |
| Warning | yellow `#F67400` |
| Critical | red `#ED1515` |
| Secondary: units, timestamps, PIDs, comments, meter brackets | intense black `#7F8C8D` |
| Section comments (`# temperatures`) | intense black `#7F8C8D` |
| Things you can tap (e.g. `! needs shizuku, tap to set up`) | cyan `#1ABC9C`, underlined |

Put every threshold in one place (a `Thresholds` object) with unit tests. Starting values:
CPU % and memory used % warn at 70 / critical at 90; battery warn < 30 / critical < 15;
temperatures warn > 45 °C / critical > 55 °C; storage used warn > 85 % / critical > 95 %;
thermal status `none` green, `light`/`moderate` yellow, `severe` and worse red;
signal *very strong*/*strong* green, *medium* yellow, *weak*/*very weak*/*no signal* red;
`low memory` red.

## Environment

- **JDK:** the system Java 25 has no `javac`. Always
  `export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2` before `./gradlew`.
- **Toolchain** (same as the launcher, keep it): AGP 8.5.2, Kotlin 2.0.21, Compose BOM 2024.12.01,
  Gradle 9.3.0 wrapper with `distributionSha256Sum`. minSdk 26, target/compile 36.
- **Checks before every commit:** `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`.
- **Emulator:** AVD `sharpright_avd` (Android 14, x86_64), shared with other projects: don't wipe it
  or change its default apps. Start it with
  `nohup ~/Android/Sdk/emulator/emulator -avd sharpright_avd -no-snapshot-save &`.
- **The user's phone:** Sony Xperia 10 IV (Android 14), adb serial in `.claude/local.md` (kept out of git).
  It has a second user profile, so `pm` commands need `--user 0`. With both devices connected,
  always pass `adb -s <serial>`. The launcher is installed on it; don't disturb it.
- **Shizuku** (13.6.0) is installed on the emulator and the phone. Its server stops at every reboot.
  To start it over adb: run
  `$(dirname $(adb shell pm path --user 0 moe.shizuku.privileged.api | cut -d: -f2))/lib/<abi>/libshizuku.so`
  in `adb shell` (abi `x86_64` on the emulator, `arm64` on the phone). `adb root` kills it; after
  `adb unroot`, start it again. Apps must be granted access in Shizuku's own dialog, which the user
  taps.

## Lessons from the launcher (don't relearn these)

- **What a normal app can read:** `/proc/meminfo`, `/proc/cpuinfo`, `/proc/self/*`,
  `/sys/devices/system/cpu/*` (current frequencies, governor), `getprop` output. **Not readable:**
  `/proc/loadavg`, `/proc/stat`, `/proc/net/*`, thermal zones, other apps' `/proc/<pid>`. The shell
  user (Shizuku) can read all of those.
- **Shell commands that work and are cheap:** `top -b -n 1 -q -o PID,UID,%CPU,RES,NAME` (~0.3 s),
  `dumpsys batterystats --usage` (~50 ms, per-app mAh), `dumpsys thermalservice`, `dumpsys power`
  (wake locks), `dumpsys media_session`, `logcat -d -v epoch -T <epoch> '*:E'`,
  `pm list packages -U`. Parsers for all of them exist in `monitor/Parsers.kt`, with tests.
- **Shizuku access** goes through a user service (`ShellService` + `IShellService.aidl`) running as
  the shell user; `ShizukuBridge` handles state, permission and binding.
- **DNS monitor** (`DnsVpnService`): a VPN that routes only the fake resolver address. It must call
  `VpnService.prepare(this)` itself before `establish()`, or `establish()` silently returns null.
  Test with `adb shell appops set <pkg> ACTIVATE_VPN allow`, then `adb root` and
  `am startservice -n <pkg>/<service>`, then `ping` a hostname, then stop it, `adb unroot`, and
  restart Shizuku.
- **Granting test permissions without the UI:** `pm grant <pkg> <permission>`,
  `appops set <pkg> GET_USAGE_STATS allow`,
  `cmd notification allow_listener <pkg>/<listener-class>`. Revoke afterwards so the user sees the
  real prompts.
- **Emulator quirks:** sensors report junk values (keep the range filters in `ProbeReader.env()`),
  there is no step counter, CPU frequencies read as 1 kHz (filtered), GPS is a fixed fake location.
- **App names:** `QUERY_ALL_PACKAGES` + `PackageManager.getApplicationLabel`; process names like
  `com.google.android.gms.persistent` need the package found by trimming segments (`procLabel`).
- **Hidden APIs:** `BluetoothDevice.getBatteryLevel()` works by reflection, with the
  `BATTERY_LEVEL_CHANGED` broadcast as fallback.
- **Debug aids:** in debug builds only, `MainActivity` reads intent extras to show states without
  tapping (the launcher had `--es preview <preset>`, `--es rows a,b,c`, `--es layout feed`,
  `--es screen settings`, `--ez snapshot true`). Do the same here, e.g. `--es page cpu`, and gate it
  on `BuildConfig.DEBUG`.
- **Measure battery cost** the way the launcher was measured:
  `adb shell top -b -n 5 -d 2 -p <pid> -o PID,%CPU,RES,NAME -q` with the app visible and with
  another app in front.

## Releases and F-Droid (the launcher's setup, proven)

Copy the launcher's `.github/workflows/` (android, release, release-tag), `.fdroid.yml`, fastlane
`metadata/en-US/` layout, README "Releasing a version" checklist and signing setup, renamed.
Rules that were learned the hard way:

- APKs are never committed. `release.yml` publishes a rolling **pre-release** tagged `latest`;
  `release-tag.yml` publishes a permanent release per `vX.Y.Z` tag, which F-Droid's reproducible-build
  check downloads (`Binaries`) and compares by certificate (`AllowedAPKSigningKeys`).
- The asset name must be fixed in the workflow (`cp` to the final name before upload): `gh release
  create file#label` only sets a display label. After publishing, **download the `Binaries` URL** and
  check its certificate SHA-256 with `apksigner verify --print-certs`.
- `.fdroid.yml`: `subdir: app` (the module, where the APK lands), `commit:` the **full hash** of the
  release commit (not a tag), only the current version under `Builds`,
  `UpdateCheckMode: Tags ^v[0-9.]+$` (so `latest` is ignored). Categories come from fdroiddata's
  current `config/categories.yml` (fine-grained now: e.g. System, Network Analyzer, Battery).
  Lint it: copy it to a scratch `metadata/<appid>.yml`, put fdroiddata's `categories.yml` minus its
  `icon:` lines in scratch `config/`, run `fdroid rewritemeta <appid>` and `fdroid lint <appid>`,
  and keep rewritemeta's formatting.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }` in `app/build.gradle.kts`.
  Check the APK's signing blocks by parsing the APK Signing Block yourself: fdroidserver 2.4.5's
  scanner never matches with the installed androguard. Only `0x7109871a` (v2) and `0x42726577`
  (padding) should be there.
- CI builds on **JDK 17** (F-Droid's build server). The launcher's CI build and a local JDK 21 build
  were byte-identical; re-check that here with the same zip-entry hash comparison.
- **New signing key for this app** (`sysreadoutmonitor-release.jks` + `keystore.properties` in the repo
  root, both gitignored). PKCS12 ignores `-keypass`, so store and key password must be equal. Tell
  the user where the key is and that it must be backed up.
- Public repos are committed as `AndSni <snukzz@gmail.com>`: set it in this repo's local git config.
- Before opening any merge request on a project the user doesn't own (F-Droid's fdroiddata), read
  its MR template first and fill it in exactly.
