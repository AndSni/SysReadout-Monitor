# SysReadout Monitor — build brief

Read [`../CLAUDE.md`](../CLAUDE.md) first for the rules, the exact look, the environment and the
lessons already learned. This brief says **what** to build and in what order.

## 1. What and why

**SysReadout Monitor** (short: SRM) is a standalone Android app that shows everything the phone
reveals about itself: hardware, CPU, memory, power, network, apps, sensors, storage and a live
event journal. The data is grouped into **pages you swipe left and right between**, each drawn as a
KDE Konsole terminal session on Fedora.

It exists because the same data inside a launcher (SysReadout Launcher) runs whenever the home
screen is visible, which you see many times a day. A dedicated app only works while you are
looking at it, and only for the page on screen, so the full monitor costs almost nothing the rest
of the time.

It is a **fork and rework** of SysReadout Launcher (`/home/asni/SysReadout`, public repo
`github.com/AndSni/SysReadout-Launcher`, forked from `main` at `f2fe298` or later). The data layer
comes across nearly unchanged; the launcher, its styling system and its home-screen features do not.

### Name and identity (confirm with the user before creating the repo)

| | |
|---|---|
| Store name | SysReadout Monitor |
| Icon label | SR Monitor (short, and distinct from the launcher's "SysReadout" when both are installed) |
| applicationId | `com.sysreadoutmonitor.app` (the user's `com.<name>.app` convention) |
| Code package | `com.asnidev.sysreadoutmonitor` |
| Repo | suggest `AndSni/SysReadout-Monitor`; the user creates it |

Checked on 2026-09-24: "sysreadoutmonitor" appears in no F-Droid or IzzyOnDroid app name, no Google
Play search result, no GitHub repo; `sysreadoutmonitor.com`/`.app` are unregistered;
`com.sysreadoutmonitor.app` is unused on Google Play and F-Droid. "SysReadout" itself was screened
the same way for the launcher. Re-check just before publishing.

## 2. The fork: keep, rework, drop

Copy the launcher's working tree into this folder **without** its `.git`, `build/`, `.gradle/`,
`.kotlin/`, `local.properties`, keystore or `keystore.properties`. Start a fresh git repository.
Credit the upstream in the README ("forked from SysReadout Launcher"). Licence stays GPL-3.0-only.
Rename the package and every user-visible "SysReadout Launcher" string.

**Keep (the data layer), with light edits:**

| File (under `app/src/main/java/com/asnidev/sysreadout/`) | What it gives you |
|---|---|
| `log/ProbeReader.kt`, `log/ProbeCatalog.kt` | 52 status rows (`cpu`, `mem`, `bat`, `wifi`, `gnss`…), each tagged with the access it needs (`Access`: NONE, USAGE, SHIZUKU, NOTIFICATIONS, LOCATION, PHONE, BLUETOOTH, ACTIVITY) |
| `log/SensorWatch.kt`, `LocationWatch.kt`, `StepWatch.kt`, `BtWatch.kt`, `PhoneWatch.kt` | listeners that run only while needed |
| `log/DeviceInfo.kt` (`SystemProps`, `GpuInfo`), `log/Sun.kt` | build properties, GPU, sunrise/sunset |
| `monitor/Parsers.kt` | parsers for `top`, `/proc/net/*`, `pm list`, thermal, wake locks, media sessions, logcat, `/proc/stat`, batterystats (all unit-tested) |
| `monitor/ShizukuBridge.kt`, `ShellService.kt`, `aidl/…/IShellService.aidl` | shell-level access through Shizuku |
| `monitor/UsageSource.kt` | usage access: events, screen time, traffic, today's screen-on and unlocks, data this month |
| `monitor/Dns.kt`, `DnsVpnService.kt` | the optional DNS monitor (app → hostname) |
| `monitor/NotifListener.kt` | notification log |
| `data/MonitorPrefs.kt`, `data/SettingsStore.kt` | settings storage (trim the fields that only make sense in a launcher) |
| tests: `monitor/ParsersTest.kt`, `log/SunTest.kt` | keep and keep green |

**Rework:**

- `log/LogEngine.kt` → split into **one sampler per page** behind a small interface
  (`suspend fun sample(): PageContent`). A coordinator runs only the *visible* page's sampler, only
  while the activity is started (`repeatOnLifecycle(STARTED)`). The journal page collects events
  from the event sources (usage events, DNS log, notification log, logcat, system changes) and
  backfills on open, as the engine does now. Keep the engine's labelling helpers (`pkgLabel`,
  `procLabel`, `uidLabel`, `shortPkg`) and its threading rule (one serial dispatcher, no locks).
- `MainActivity.kt`, `LauncherViewModel.kt` → a normal app activity (no HOME intent filter,
  `singleTop`), a `MonitorViewModel` holding each page's state.
- `ui/settings/*` → a single **conf** page inside the pager (see below), terminal-styled.

**Drop:** everything that exists because it's a launcher or because of the launcher's styling:
`apps/AppRepository.kt` (use `PackageManager` directly), `ui/HomeScreen.kt`, `ui/Drawer.kt`,
`ui/EntryChip.kt`, `ui/Dialogs.kt`, `ui/LogBackdrop.kt`, `ui/Crt.kt`, `ui/Fonts.kt` (font import),
`ui/Styled.kt`, `data/Theme.kt` (presets), `log/BannerText.kt`, `log/Feed.kt` and its test,
`system/LockScreen.kt`, `system/LockService.kt` and its accessibility XML, `system/SystemActions.kt`
(keep only what the conf page needs), the settings pages for appearance, CRT, lock screen, drawer,
gestures and hidden apps, the Haze dependency and the bundled fonts other than Hack. Manifest: drop
the HOME/DEFAULT categories, `EXPAND_STATUS_BAR`, `REQUEST_DELETE_PACKAGES`, `SET_ALARM`,
`SET_WALLPAPER` and the accessibility service. Keep every other permission; each is still asked
for only when the page or row that needs it is used.

## 3. The screen

```
┌─────────────────────────────────────────────┐
│ sys │ cpu │ mem │ power │ net │ apps │ …    │  ← tab strip, like Konsole's tabs
├─────────────────────────────────────────────┤
│ [user@xperia-10-iv ~]$ free -h              │  ← prompt + the page's "command"
│ mem    3.1G / 7.6G avail   59% used         │
│        [||||||||||||||||||       ] 59%      │  ← htop-style ASCII meter, coloured by threshold
│ swap   0.6G / 1.8G used   33%               │
│ # detail                                    │  ← section comment, dim
│ cached 992M  active 1.1G  dirty 12M  …      │
│ # top by memory                             │
│   PID   RES  PROCESS                        │  ← table header, bold
│   552  341M  system_server                  │
│ …                                           │
│ [user@xperia-10-iv ~]$ █                    │  ← blinking block cursor
└─────────────────────────────────────────────┘
```

- **Pages:** a Compose `HorizontalPager`. Swipe left/right, or tap a tab. The tab strip looks like
  Konsole's (active tab on `#31363B`, others on the background, text in the foreground colour, a
  thin separator). It remembers the last page.
- **Each page** is one vertically scrollable `LazyColumn` of terminal lines, so nothing is ever cut
  off. It starts with the prompt and a command that fits the page, and ends with a prompt and a
  blinking block cursor (blinks only while visible).
- **Lines** are lists of coloured spans (`AnnotatedString`), monospaced Hack, one line each, no
  wrapping. Keys in a left column, as in the launcher's rows. Tables use fixed-width columns with a
  bold header line.
- **Text size:** pinch to zoom, 8–20 sp, remembered.
- **Missing access:** a page or row that needs something shows a cyan, tappable line such as
  `! needs shizuku, tap to set up` or `! needs location permission, tap to allow`, which runs the
  same grant flow as the launcher (runtime permission request, usage-access settings,
  notification-listener settings, Shizuku permission, VPN consent).
- **Pause:** a tap on the prompt line pauses and resumes sampling for that page (the cursor stops
  blinking while paused).

## 4. Pages

Every launcher row, table and event type must appear on one of these pages. The "command" is
flavour text for the prompt line.

| # | Tab | Prompt command | Contents |
|---|---|---|---|
| 1 | `sys` | `fastfetch` | device, maker, model, codename; Android version, API, security patch; kernel; build props (A/B slot, verified boot, bootloader lock, treble, first API, build type); SoC and ABI; GPU, GLES and Vulkan; display (resolution, refresh rate, density, brightness); uptime and awake %; boot count; time zone and unix epoch; installed/launchable app counts. Rows: `dev` `os` `kern` `props` `soc` `gpu` `disp` `up` `boot` `time` `apps` |
| 2 | `cpu` | `top` | online cores, clock range and governor (`cpu`); a meter per core (`cores`, Shizuku) with its current clock; load average (`load`, Shizuku); thermal status and headroom (`therm`); every temperature sensor (`temps`, Shizuku); processes sorted by CPU (table, Shizuku); SysReadout Monitor's own process (`self`) |
| 3 | `mem` | `free -h` | RAM with a meter and the low-memory flag (`mem`); swap/zram (`swap`); detail (`vm`); processes sorted by memory (table, Shizuku) |
| 4 | `power` | `upower -d` | battery level with a meter, status, temperature, health (`bat`); voltage, current, watts, source (`pwr`); charge counter, estimated capacity, cycles, time to full (`chg`); saver, doze, do-not-disturb, ringer (`mode`); battery drain per app since the last charge (table, Shizuku); wake locks (table, Shizuku) |
| 5 | `net` | `ip addr; ss -tunp` | connection type and throughput (`net`); Wi-Fi signal, speed, band (`wifi`); SSID, BSSID, channel, standard (`ssid`, location); nearby networks (`aps`, location); IP and gateway (`ip`); mobile operator and signal (`cell`), signal quality (`rf`), 5G/LTE-CA and bandwidths (`link`, phone), serving cell (`tower`, location); traffic since boot (`data`) and this month (`month`, usage); traffic per app today (table, usage); open connections per app with host names (table, Shizuku; host names from the DNS monitor, else reverse DNS); recent DNS lookups per app (table, DNS monitor) |
| 6 | `apps` | `dumpsys usagestats` | screen-on time and unlocks today (`today`); notifications showing now and received today (`ntf`, notification access); screen time per app (table, usage); notifications per app today (table, notification access); currently running foreground services (from usage events) |
| 7 | `sensors` | `sensors` | ambient light, pressure, altitude, temperature, humidity (`env`); compass (`compass`); GPS fix (`gps`) and satellites per constellation (`gnss`), plus a per-satellite table (constellation, ID, C/N0, used) from `GnssStatus`; sunrise and sunset (`sun`); moon phase (`moon`); steps (`steps`); radios (`radio`: airplane, Bluetooth, NFC, location); connected Bluetooth devices and their battery (`bt`); audio volumes and output (`audio`); now playing (`media`, Shizuku); next alarm (`alarm`); debug state (`debug`: developer options, adb, wireless adb, USB) |
| 8 | `storage` | `df -h` | internal storage with a meter (`fs`); removable volumes (`sd`); **new:** app sizes (app, data, cache) for the largest apps via `StorageStatsManager` (usage access) |
| 9 | `journal` | `journalctl -f` | the launcher's event stream: `net power bat therm mem pkg` (system), `fg svc scrn lock` (usage), `proc conn logE logW` (Shizuku), `dns` (DNS monitor), `ntf` (notifications). Newest at the bottom, auto-scrolls while at the bottom, timestamps on (dim), keys coloured by source |
| 10 | `conf` | `nano ~/.config/srm.conf` | refresh interval (1/2/5/10 s; shell-based tables at least 5 s); which pages are shown and their order; access status with grant actions (usage access, notification access, Shizuku with the start instructions, DNS monitor with its consent and caveats, each runtime permission); reverse-DNS toggle; logcat level (errors / +warnings); show notification titles; about, version, licences |

GPS and satellites switch the GPS on, so they run only while the `sensors` page is visible. Say so
on the page in a dim comment line.

## 5. Battery and performance targets

- **Nothing runs while the app isn't visible**, except the optional DNS monitor and the notification
  listener, which are event-driven and cheap (the same as in the launcher).
- **Only the visible page samples.** Switching pages stops the old page's sources (sensors, GPS,
  telephony callbacks, Shizuku commands) and starts the new one's.
- **Targets, measured with `top -p` on the emulator:** under 3 % of one core on pages without shell
  commands, under 8 % on the `cpu`/`mem`/`power`/`net` pages with Shizuku, 0 % with another app in
  front. The launcher measured about 2 % idle and 5 % with every Shizuku table on.
- The cursor blink and meters must not cause continuous recomposition of the whole page: blink only
  the cursor span.

## 6. Milestones

Commit at the end of each milestone with the checks green.

1. **Fork and strip.** Copy, rename (package, app ID, strings), remove everything in the Drop list,
   fresh git repo with the local identity from `CLAUDE.md`. Builds, lint clean, kept tests pass. The
   app opens to a placeholder.
2. **Terminal shell.** Pager, tab strip, Hack font, Breeze colours, prompt and cursor, span-based
   lines, tables, meters, pinch zoom, the `Thresholds` object with tests, and the debug-only
   `--es page <tab>` extra.
3. **Pages 1–4** (`sys`, `cpu`, `mem`, `power`) with per-page samplers and lifecycle handling.
   Screenshot each page on the emulator with the debug extras (never by tapping).
4. **Pages 5–8** (`net`, `apps`, `sensors`, `storage`), including the per-satellite table and the
   app-size table.
5. **Journal and conf pages**, all access and grant flows, persisted settings.
6. **Verification:** battery measurements against the targets, every page checked with the needed
   permissions granted on the emulator (then revoked), no row cut off at 20 sp.
7. **Release** (ask the user for the repo and the go-ahead first): README, `docs/MANUAL.md`,
   fastlane metadata with screenshots, `.fdroid.yml`, workflows, new signing key, CI secrets,
   v0.1.0 tag, then the full verification list in `CLAUDE.md` (Binaries URL, certificate,
   signing blocks, reproducibility).

## 7. Done means

- Every datum the launcher can show appears on some page; nothing is cut off at any text size.
- Swiping between all pages is smooth; each page scrolls vertically.
- Colours follow the table in `CLAUDE.md`; thresholds are unit-tested.
- No permission is requested before the user opens the thing that needs it.
- 0 % CPU in the background; the targets above are met and the numbers are reported.
- `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` is green, and the CI release
  build is byte-identical to a local build.
- The user has installed it on the phone and confirmed the pages look right.
