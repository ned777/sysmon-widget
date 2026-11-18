# SysMon (Device Monitor Agent)

An Android home-screen widget that glances at another computer's health —
RAM, storage, temperature, load and uptime — by polling a small local HTTP
agent that runs on that machine. The companion app shows the full picture
per device, including CPU usage and local Claude Code token usage. No cloud
service involved: the phone talks directly to the agent over whatever
private network connects it to the computer (Wi-Fi LAN, a VPN, etc.), and
the widget only refreshes when you ask it to.

## How it works

Two independent pieces:

1. **`agent/monitor_agent.py`** — a single-file, stdlib-only Python HTTP
   server. `GET /stats` returns a JSON snapshot: RAM used/total, storage
   used/total for `/`, CPU usage % (sampled over a quarter second), core
   count and load averages, the hottest CPU temperature (when the machine
   exposes a sensor), uptime, and Claude Code token usage aggregated from
   every `~/.claude/projects/**/*.jsonl` transcript on that machine, both
   for today and for a rolling 7-day window.
2. **The Android app** (`app/`) — lets you register one or more devices
   (a name + `ip:port` each), and shows a home-screen widget per device.
   Adding a widget prompts you to pick which registered device it should
   monitor; each widget instance remembers its own device and its own
   last-known reading.

## Widget

```
┌──────────────────────────────────────┐
│               HOME PC                │  ← device name, white, centered
│ IP Address               192.0.2.10  │
│ RAM   ▰▰▰▱▱▱▱▱▱▱▱▱▱▱▱      3.9/15GB  │  ← one line each, usage bar
│ Disk  ▰▱▱▱▱▱▱▱▱▱▱▱▱▱▱      17/231GB  │
│   Temp 57°C   Load 0.73   Up 2d 16h  │  ← one summary line
│         Tap below to refresh         │
│    Online · Updated Nov 17, 11:44    │  ← or "Unreachable — last …"
└──────────────────────────────────────┘
```

Same look as the rest of my widget family (Today, Weather, Garage):
pitch-black card with a 2dp teal outline, teal labels and white values.

- **Fixed rows, no scrolling.** IP address, then RAM and Disk on one line
  each with an inline usage bar, then one summary line for temperature,
  load and uptime. The label and value columns are fixed widths, so both
  bars are always exactly the same length.
- **Usage bars change color**: teal below 75%, yellow from 75%, red from 90%.
- **Rows hide themselves** when that stat is switched off for the widget
  (you choose when adding it) or when the device's agent doesn't report it
  — e.g. an older agent without temperature/uptime, or a machine with no
  temperature sensor.
- **Tapping:** tap anywhere on the widget to open the app. Tap the bottom
  strip (**Tap below to refresh**) to fetch fresh stats. While it works,
  the two bottom lines are hidden and a bigger **Updating…** takes their
  place, so you can tell it's busy. It stays up for at least 1.2 seconds,
  because a fetch on the home network finishes so fast it would otherwise
  just flash by. It stays longer if the fetch is slow.
- **Manual refresh only** — periodic auto-refresh is off
  (`updatePeriodMillis="0"`), so the widget never polls in the background.
  If the agent doesn't answer, the widget keeps the last successful reading
  and the footer switches to **Unreachable — last &lt;time&gt;**.

CPU usage and Claude token usage are deliberately left off the widget to
keep it short; the app shows both.

## App

The app (**Device Monitor Agent**) is styled like the widget: black
background, a centered header with the gauge icon, and one teal-outlined
card per device showing its name, address, every stat it reports
(including CPU and Claude Code daily/weekly tokens) and when it was last
updated. Pull down to refresh all devices; ✎ edits a device and ✕
removes it; **+ Add device** registers a new one.

### Why CPU is in the app but not the widget

CPU percent is a rate: it only means something when measured over a window.
The agent samples it over a quarter second at request time, which is fine
for a quick look in the app, but a widget that only updates when tapped
would keep showing that one moment's reading for hours. Load average (on
the widget's summary line) is already smoothed over 1 and 5 minutes, so it
answers "has this machine been busy?" better from a snapshot. Network
throughput isn't reported for the same reason.

### Why there's no cost estimate

An earlier version also showed an estimated dollar cost per model's list
pricing. That's only meaningful if you're paying per-token on the API —
if you're on a flat monthly plan (e.g. $20/month), it doesn't correspond
to anything you're actually billed, so it was removed. Token counts
(Daily/Weekly) are still shown since they're a real, direct measurement
rather than an estimate.

## Requirements

- **On each computer being monitored:** Python 3 (stdlib only — no
  `psutil` or other packages needed), Linux (reads `/proc/meminfo`,
  `/proc/stat`, `/proc/uptime` and `/sys/class/hwmon` / `thermal`, and uses
  `shutil.disk_usage`).
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

### Running it as a service (survives logout/reboot)

Running it manually in a terminal only lasts as long as that session — after
a reboot (e.g. a power outage) it won't come back and the widget will show
the device as offline. To have it start at boot and keep running whether or
not anyone is logged in, install `agent/sysmon-agent.service` as a
`systemd --user` unit:

```sh
mkdir -p ~/.config/systemd/user
cp agent/sysmon-agent.service ~/.config/systemd/user/sysmon-agent.service
sed -i "s#REPLACE_WITH_PATH_TO#$(pwd)/agent#" ~/.config/systemd/user/sysmon-agent.service
systemctl --user daemon-reload
systemctl --user enable --now sysmon-agent
loginctl enable-linger "$USER"   # let it start at boot without a login session
```

### Getting a heads-up after a reboot (optional)

Even with the service above, you'd only find out it's back by opening the
widget. `agent/sysmon-boot-notify.service` is a companion oneshot unit that
runs once per boot, checks `http://127.0.0.1:8765/stats` (retrying for ~30s
in case the agent is still starting), and sends a Telegram message saying
whether the agent came back up or not — so you know either way, without
having to log in or check the widget. It reads the bot token/chat ID from
`~/.config/telegram-bot/config.json` (the same config used by an existing
Telegram relay bot on this machine), so it assumes that file already exists.

```sh
cp agent/sysmon-boot-notify.service ~/.config/systemd/user/sysmon-boot-notify.service
sed -i "s#REPLACE_WITH_PATH_TO#$(pwd)/agent#" ~/.config/systemd/user/sysmon-boot-notify.service
systemctl --user daemon-reload
systemctl --user enable sysmon-boot-notify   # don't --now: it's meant to fire on boot, not immediately
```

`enable-linger` + `enable --now` means it starts during normal boot even if
you never log in, and `Restart=on-failure` brings it back if it ever
crashes. Check on it with `systemctl --user status sysmon-agent` or
`journalctl --user -u sysmon-agent -f`.

This isn't installed automatically; do it per machine you want always-on
monitoring for. (Claude Code doesn't need to be installed on a monitored
machine — if `~/.claude/projects` doesn't exist there, the Claude
Daily/Weekly rows are simply left out instead of erroring.)

**No authentication.** Anything that can reach the port can read these
stats. That's an accepted tradeoff for a personal tool running over a
private/trusted network — don't expose the port publicly.

### Updating the agent

Copy the new `monitor_agent.py` over the old one on each machine and
restart it (`systemctl --user restart sysmon-agent`). The app and widget
work with older agents too — they just hide the stats an older agent
doesn't send.

## Installing the widget

```sh
export JAVA_HOME=<path to a JDK 17>
./gradlew installDebug     # installs over adb (USB or wireless debugging)
```

Then open the app (**Device Monitor Agent** in your app drawer):

1. Tap **Add device**, give it a name and its `ip:port` (matching wherever
   that machine's `monitor_agent.py` is bound — e.g. `192.0.2.10:8765`).
   Repeat for each machine you want to monitor.
2. Long-press your home screen → Widgets → find **Device Monitor Agent**
   and drag it on. Android will prompt you to **choose which device** this
   widget instance should show, then which stats it should display.
3. Tap **Tap below to refresh** on the widget any time to update it; tap
   anywhere else on it to open the app.

Removing a device from the app (✕ button) makes any widget still pointed
at it fall back to an offline state rather than crashing.

## Project structure

```
agent/
  monitor_agent.py             — stdlib-only HTTP server: GET /stats
  run_agent.sh                  — convenience launcher
  sysmon-agent.service          — optional systemd --user unit (not installed)
  boot_notify.py                — Telegrams whether the agent came back up, run once per boot
  sysmon-boot-notify.service    — optional systemd --user unit for boot_notify.py (not installed)
app/src/main/java/com/sysmonwidget/app/
  Device.kt                     — Device model + SharedPreferences-backed store
  DeviceAdapter.kt               — device cards, shared by the manager screen and picker
  DeviceStatsCache.kt             — last fetched stats per device (shared by app + widgets)
  MainActivity.kt                  — device manager (add/edit/remove, pull to refresh)
  WidgetConfigActivity.kt           — per-widget device + stats picker, shown when adding a widget
  SysMonWidgetProvider.kt            — AppWidgetProvider: fetch, cache, render, tap handling
  StatsFormat.kt                      — turns agent JSON into stat rows (widget + app)
  StatsClient.kt                       — HttpURLConnection GET + JSON parse
  WidgetCorners.kt                      — matches the widget border radius to the launcher's mask
```
