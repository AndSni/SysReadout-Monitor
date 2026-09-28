# SysReadout Monitor — user manual

SysReadout Monitor (SR Monitor) shows what your phone reveals about itself on pages you swipe
between. Each page looks like a KDE Konsole session on Fedora: a prompt with a command, then that
command's output. The page on screen is the only one that samples, and only while the app is
open.

1. [Moving around](#1-moving-around)
2. [Reading a page](#2-reading-a-page)
3. [The pages](#3-the-pages)
4. [The scan page: find a network or a device](#4-the-scan-page-find-a-network-or-a-device)
5. [The shell](#5-the-shell)
6. [Access and permissions](#6-access-and-permissions)
7. [Shizuku](#7-shizuku)
8. [The DNS monitor](#8-the-dns-monitor)
9. [conf](#9-conf)
10. [Battery use](#10-battery-use)
11. [Privacy](#11-privacy)

## 1. Moving around

- **Swipe** left or right, or tap a **tab**, to change page. The app opens on `sys`
  (`conf › remember_page` makes it open where you left it).
- **Scroll** a page up and down; nothing is ever cut off. Long lines continue on the next row,
  indented under their column.
- **Pinch** with two fingers to change the text size, 8 to 20 sp. It's remembered;
  `conf › text_size` sets it back to 12.
- **Long-press** any line to copy it. You get the whole line even when it wraps over several
  rows; it lights up for a moment when copied.
- **Tap the first line** of a page (the prompt with the command) to pause that page, like
  pressing Ctrl+Z. It ends with `^Z` and `[1]+ Stopped` until you tap the prompt again.
- **Cyan, underlined** text does something when tapped: grants what's missing, changes a
  setting, or goes to another page.

## 2. Reading a page

- **Blue bold** words on the left are row names (`mem`, `wifi`) and table headers.
- **Grey** is secondary: units, process IDs, timestamps, `# comments`.
- Colour means something, and only then:

| Colour | Means |
|---|---|
| green | fine: a meter below its warning level, a strong signal, `none` thermal status |
| orange | warning: CPU or memory from 70 %, battery below 30 %, above 45 °C, storage above 85 %, medium signal, light or moderate thermal status |
| red | critical: CPU or memory from 90 %, battery below 15 %, above 55 °C, storage above 95 %, weak signal, severe thermal status, low memory |

- Meters work like htop's: `[|||||||      37.0%]`, the bars filling in the colour of the value.
- A line that starts with `!` says what's missing and what a tap does, for example
  `! needs usage access, tap to grant`.

## 3. The pages

| Tab | Command | What it shows |
|---|---|---|
| `sys` | `fastfetch` | in sections. **device**: maker and model, chipset, every ABI and the memory page size, GPU and graphics APIs, display. **android**: version and API level; build number, type and keys, incremental, build date, fingerprint; security and vendor patch with the security patch's age (orange after 3 months, red after 6); Google Play system update; the Android it shipped with, VNDK, the oldest apps it installs; build properties (A/B slot, verified boot, bootloader lock, treble); encryption, verified boot, verity and SELinux (Shizuku), each coloured by what it means; time zone, tzdata version and locale. **kernel and firmware**: kernel release, how it was built (date, flags, compiler; Android 14 lets only Shizuku read it), bootloader and baseband. **runtime and apps**: WebView, Play services and Play Store versions, ART, SDK extensions, media performance class, app counts. **time**: uptime, boot count, clock |
| `cpu` | `top` | cores with load meters (Shizuku) or clock meters (without), load average, thermal status and headroom, every temperature sensor, the monitor's own use, processes by CPU |
| `mem` | `free -h` | RAM and swap meters, memory detail, processes by memory |
| `power` | `upower -d` | battery meter, state, temperature, health, voltage, current and watts, average current and energy left, charge counter, capacity, cycles, time to full, the charger and the most it may deliver (V, A, W), cell technology and low-battery flag, battery saver, doze, do-not-disturb, ringer. With Shizuku, since the last charge: drain overall and per hour, with the screen on and off, in light and deep doze, rated, estimated and learned capacity, time left at that pace, battery use by part of the phone and by app, and wake locks held now |
| `net` | `ip addr; ss -tunp` | connection and speed, Wi-Fi signal, name, channel and standard, networks nearby, IP and gateway, mobile operator and signal, signal quality, 5G / LTE-CA and bandwidths, serving cell, traffic since boot and this month, traffic per app today, open connections per app with server names, recent DNS lookups per app |
| `scan` | `nmcli device wifi list; bluetoothctl scan on` | see [section 4](#4-the-scan-page-find-a-network-or-a-device) |
| `apps` | `dumpsys usagestats` | screen-on time and unlocks today, notifications, screen time per app, notifications per app, foreground services running |
| `sensors` | `sensors` | light, pressure, altitude, temperature, humidity, compass, GPS fix and every satellite, sunrise and sunset, moon, steps, radios, Bluetooth devices and their battery, audio, what's playing, next alarm, developer options and USB |
| `storage` | `df -h` | internal and removable storage, file systems (read-only system images are always full; they're greyed, not flagged), apps by size |
| `journal` | `journalctl -f` | a live event log, newest at the bottom (see below) |
| `shell` | | type commands, see [section 5](#5-the-shell) |
| `conf` | `nano ~/.config/srm.conf` | the settings, see [section 9](#9-conf) |

**The journal** keeps up to 500 events: network, power, battery, thermal and memory changes, apps
installed, updated and removed (`net power bat therm mem pkg`); app switches, foreground services,
screen and lock (`fg svc scrn lock`, usage access); new processes and connections, errors and
warnings from logcat (`proc conn logE logW`, Shizuku); DNS lookups (`dns`, DNS monitor);
notifications (`ntf`, notification access). When you come back to it, it catches up on what
Android kept meanwhile (the last hour of usage events, the DNS and notification logs, logcat).
Processes and connections are only seen while the page is open. Each source can be switched off in
`conf › [journal]`.

## 4. The scan page: find a network or a device

**Wi-Fi.** Every access point in range, strongest first, its name on the line under its numbers:

```
  dBm  % ±dB SEEN BAND CH SIGNAL
* -52 80 1.2  5/5 5G   36 ||||||||
    HomeNet
```

- `*` marks the network you're connected to; its signal updates live.
- `%` is the signal on the scale `nmcli` uses: -40 dBm or better is 100 %, -100 dBm is 0 %.
- `±dB` is how much the signal wanders between scans, `SEEN` how many of the recent scans found
  it. Low `±dB` and a full `SEEN` mean a steady network.
- Android lets an app scan 4 times in 2 minutes, so the list refreshes about every 30 s. To
  compare spots (say, three home networks from the garden shed), stay at each spot for a minute.
- Needs the location permission and location switched on: Android shows no scan results without
  them.

**Bluetooth.** Paired devices first, even when silent (`linked`: connected but not advertising;
`unseen`: not heard), then everything else heard nearby, each with dBm, %, and `↑` (stronger than
a moment ago), `↓` (weaker) or `=`, and its name on the line below.

**Finding a lost device.** Tap its name. It moves to the top of the page with a full-width meter and
`↑`/`↓`. Walk around slowly and follow the numbers up; hold still for a moment at each spot,
because Bluetooth readings jump. A device that's connected to your phone, like a watch, usually
stops advertising, so SR Monitor reads its signal through the existing connection. A device that's
switched off, out of range or connected to another phone stays silent. Tap the name at the top
again to stop.

Needs the "nearby devices" permission on Android 12 and newer (never used for location), location
on older Android. Scanning runs only while this page is on screen.

## 5. The shell

A line-based shell: type a command, press Send (or Enter), see its output.

- With Shizuku connected, commands run as the `shell` user, with the access adb has. **What they
  change, they change for real** (settings, packages). Without Shizuku they run as SR Monitor's
  own user, which can see little of the system. The prompt says which:
  `[shell@your-phone /]$` or `[u0_a123@your-phone /]$`.
- `cd` carries over from one command to the next; variables don't.
- There's no terminal, so full-screen programs (`vi`, `less`, `top` without `-b -n 1`) don't
  work, and nothing can read input.
- The keys under the prompt: `^C` stops the running command (and everything it started), `↑` and
  `↓` step through history, `clear` clears the screen.
- A running command stops when SR Monitor leaves the screen.

Some commands worth trying: `dumpsys battery`, `getprop ro.product.model`, `pm list packages -3`,
`settings list global`, `cmd wifi status`, `logcat -d -t 50` (stop long ones with `^C`).

## 6. Access and permissions

Nothing is asked for until you tap something that needs it.

| Access | What it adds | How to grant |
|---|---|---|
| Usage access | screen time, traffic, app sizes, app switches and services, screen events | Android settings, opened by the tap |
| Notification access | notification counts and events, what's playing | Android settings, opened by the tap |
| Location | GPS, satellites, Wi-Fi name, networks nearby, serving cell, sunrise | permission dialog |
| Phone | 5G / LTE-CA details | permission dialog |
| Nearby devices | Bluetooth scanning on the scan page | permission dialog |
| Bluetooth | connected devices and their battery | permission dialog |
| Physical activity | steps | permission dialog |
| Shizuku | see [section 7](#7-shizuku) | the Shizuku app |

If you refuse a permission twice, Android stops showing its dialog; the tap then opens SR
Monitor's page in Android settings instead. `conf › revoke` opens it too, to take a permission back.

## 7. Shizuku

[Shizuku](https://shizuku.rikka.app/) lets apps you allow use the shell's access, the same as a
computer connected over adb. It's optional: every page works without it. With it, SR Monitor also
shows processes, connections, per-core load, temperatures, wake locks, battery use per app and
logcat, and the shell runs as `shell`.

`conf › [shizuku]` walks you through it, each step a link:

1. **install**: get Shizuku from its download page (Play Store or GitHub).
2. **start**: open Shizuku and start it. On Android 11 and newer use "Start via wireless
   debugging" and follow its pairing steps (developer options must be on); older Android needs a
   computer with adb once; with root, start it with root.
3. **allow**: let SR Monitor use it.
4. **access** (once it's connected): one tap switches on usage and notification access through
   Shizuku, the same switches as in Android's settings. They stay on after Shizuku stops.

Without root, Shizuku stops when the phone restarts; start it again in the Shizuku app. Until then
the pages show `! shizuku isn't running, tap to start it`. If Shizuku stops while SR Monitor is
open, the pages switch to that line and pick up again by themselves about two seconds after
Shizuku is back. If its helper keeps failing, SR Monitor leaves it alone for a while (30 s,
doubling up to 5 minutes) and says so; tap the line to try again at once.

## 8. The DNS monitor

Shows which app looks up which server name (`imap.gmail.com`, not just an IP), on the net page and
in the journal. It's a local VPN that routes only DNS: each lookup is noted and passed on unchanged
to your network's DNS server; only if the network names no DNS server at all does it fall back to
Quad9 (9.9.9.9) and Cloudflare (1.1.1.1). Nothing else goes through it and nothing is sent
anywhere else.
Switch it on in `conf › dns_monitor`; Android asks once whether to allow the VPN.

- Android allows one VPN at a time, so it can't run next to another VPN.
- If private DNS is set to a specific provider (Android settings › network › private DNS),
  lookups go straight there and the monitor sees none; set it to automatic or off.
- Apps with their own encrypted DNS (some browsers) bypass it.

## 9. conf

Settings are shown as a config file; tap a value to change it.

| Setting | What it does |
|---|---|
| `refresh` | how often the visible page samples: 1, 2, 5 or 10 s. Shell-based tables refresh every 5 s at most, dumpsys every 10 s, battery use every 5 minutes |
| `text_size` | tap to go back to 12 sp (pinch on any page to change it) |
| `banner` | the SYSTEM READOUT MONITOR lines at the top of sys |
| `remember_page` | open on the last page instead of sys |
| pages | `[x]` shows or hides a page, `↑ ↓` move it; conf is always last |
| `[access]`, `[shizuku]` | what's granted, and a link for what isn't |
| `dns_monitor` | see [section 8](#8-the-dns-monitor) |
| `reverse_dns` | look up server names for connections the DNS monitor didn't see |
| `[journal]` | which event sources the journal shows; notification titles (off: they can be private); logcat errors only, or warnings too |
| `[about]` | version, licences |

## 10. Battery use

SR Monitor samples only the page on screen, only while the app is open. With the app in the
background it uses no CPU at all (measured: 0 CPU ticks in 30 s). With a page open it used about 1
to 2 % of one core on the test emulator, Shizuku pages included. GPS and Bluetooth scanning run
only while their page is on screen. The DNS monitor and the notification log, when you switch them
on, are the only things that keep working in the background; both only react to events.

## 11. Privacy

No ads, no analytics, no tracking, no accounts. Everything is read on the phone and kept in memory
while the app runs; only your settings are stored. Nothing is sent anywhere. The only network use
is the optional DNS monitor passing your apps' own lookups on to your DNS server (Quad9 or
Cloudflare only if the network names none), and optional reverse-DNS lookups of connection
addresses, made by Shizuku's helper.
