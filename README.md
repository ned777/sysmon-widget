# SysMon (Device Monitor Agent)

An Android home-screen widget that glances at another computer's health —
RAM, storage, and local Claude Code token usage — by polling a small local
HTTP agent that runs on that machine. No cloud service involved: the widget
talks directly to the agent over whatever private network connects your
phone and computer (Wi-Fi LAN, Tailscale, etc.), and only refreshes when you
tap it.

## How it works

Two independent pieces:

1. **`agent/monitor_agent.py`** — a single-file, stdlib-only Python HTTP
   server. `GET /stats` returns a JSON snapshot: RAM used/total, storage
   used/total for `/`, and Claude Code token usage aggregated from every
   `~/.claude/projects/**/*.jsonl` transcript on that machine, both for
   today and for a rolling 7-day window.
2. **The Android app** (`app/`) — lets you register one or more devices
   (a name + `ip:port` each), and shows a home-screen widget per device.
   Adding a widget prompts you to pick which registered device it should
   monitor; each widget instance remembers its own device and its own
   last-known reading.

## Widget

```
Home PC                      ← bold, centered — the device's name
RAM: 3GB out of 15GB
Storage: 10GB out of 231GB
Claude (Daily): 125K in / 40K out
Claude (Weekly): 800K in / 210K out
Status: Online
Updated 14:32                ← bottom, centered
```

Labels are bold, values are regular weight. **The widget only updates when
you tap it** — periodic auto-refresh is turned off (`updatePeriodMillis="0"`
in the widget's manifest), so it's a manual "check now" tool rather than
something quietly polling in the background. If the agent doesn't respond,
the widget keeps showing the last successful reading and flips `Status` to
`Offline` instead of going blank. Stat rows are backed by a scrollable list,
so shrinking the widget (down to about a 2×1 grid cell) scrolls instead of
clipping.

### Why there's no CPU or network row

An earlier version showed CPU usage and network throughput. Both were
removed: CPU percent is only meaningful as an average measured over a
window of continuous sampling, but this widget only takes a single
snapshot whenever you tap it — a lone reading like "12%" doesn't actually
describe how busy the machine has been, and a widget that's deliberately
*not* running in the background has no business implying it does. Network
throughput had the same problem (it's a rate between two samples, and taps
are irregular and infrequent). RAM and storage are fine as one-off
snapshots since they're stateful gauges, not rates.

### Why there's no cost estimate

An earlier version also showed an estimated dollar cost per model's list
pricing. That's only meaningful if you're paying per-token on the API —
if you're on a flat monthly plan (e.g. $20/month), it doesn't correspond
to anything you're actually billed, so it was removed. Token counts
(Daily/Weekly) are still shown since they're a real, direct measurement
rather than an estimate.

## Requirements

- **On each computer being monitored:** Python 3 (stdlib only — no
  `psutil` or other packages needed), Linux (reads `/proc/meminfo` and
  uses `shutil.disk_usage`).
- **On the phone:** Android 10 (API 29) or newer.
- Phone and computer need to reach each other over the network — same
  Wi-Fi, a VPN like Tailscale, etc. There's no cloud relay.

## Running the agent

```sh
cd agent
./run_agent.sh          # binds 0.0.0.0:8765, foreground — Ctrl-C to stop
```

Override the port with `MONITOR_PORT`. Run one instance per machine you
want to monitor.

`agent/sysmon-agent.service` is an optional `systemd --user` unit if you'd
rather it start automatically and restart on failure — it's not installed
by default; the agent is meant to be something you start manually when you
want it running.

**No authentication.** Anything that can reach the port can read these
stats. That's an accepted tradeoff for a personal tool running over a
private/trusted network — don't expose the port publicly.

## Installing the widget

```sh
export JAVA_HOME=<path to a JDK 17>
./gradlew installDebug     # installs over adb (USB or wireless debugging)
```

Then open the app (**Device Monitor Agent** in your app drawer):

1. Tap **Add device**, give it a name and its `ip:port` (matching wherever
   that machine's `monitor_agent.py` is bound — e.g. `192.168.1.50:8765`).
   Repeat for each machine you want to monitor.
2. Long-press your home screen → Widgets → find **Device Monitor Agent**
   and drag it on. Android will prompt you to **choose which device** this
   widget instance should show.
3. Tap the widget any time to refresh it.

Removing a device from the app (✕ button) makes any widget still pointed
at it fall back to an offline state rather than crashing.

## Project structure

```
agent/
  monitor_agent.py             — stdlib-only HTTP server: GET /stats
  run_agent.sh                  — convenience launcher
  sysmon-agent.service          — optional systemd --user unit (not installed)
app/src/main/java/com/sysmonwidget/app/
  Device.kt                     — Device model + SharedPreferences-backed store
  DeviceAdapter.kt               — list adapter shared by the manager screen and picker
  MainActivity.kt                 — device manager (add/remove)
  WidgetConfigActivity.kt          — per-widget device picker, shown when adding a widget
  SysMonWidgetProvider.kt           — AppWidgetProvider: fetch, cache, render
  SysMonRemoteViewsService.kt        — RemoteViewsService/ListView adapter (scrollable stat rows)
  StatsFormat.kt                      — shared row-formatting logic
  StatsClient.kt                       — HttpURLConnection GET + JSON parse
```
