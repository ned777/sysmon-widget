# SysMon (Device Monitor Agent)

An Android home-screen widget that glances at another computer's health —
RAM, CPU, storage, network throughput, and today's local Claude Code token
usage/cost — by polling a small local HTTP agent that runs on that machine.
No cloud service involved: the widget talks directly to the agent over
whatever private network connects your phone and computer (Wi-Fi LAN,
Tailscale, etc.).

## How it works

Two independent pieces:

1. **`agent/monitor_agent.py`** — a single-file, stdlib-only Python HTTP
   server. `GET /stats` returns a JSON snapshot: RAM used/total, CPU percent
   (computed from the delta between consecutive `/proc/stat` samples),
   storage used/total for `/`, network rx/tx bytes-per-second (delta between
   consecutive `/proc/net/dev` samples), and today's Claude Code usage
   (tokens + a rough estimated cost) aggregated from every
   `~/.claude/projects/**/*.jsonl` transcript on that machine.
2. **The Android app** (`app/`) — a widget-only app. `SysMonWidgetProvider`
   fetches `/stats` from a configured `ip:port` on a schedule (and on tap)
   and renders it into the widget. `MainActivity` is just a one-field
   settings screen to set that address.

## Widget

```
192.168.1.50:8765            ← bold, larger — the configured server address
RAM: 3GB out of 15GB
CPU: 12%
Storage: 10GB out of 231GB
Network: ↓15KB/s ↑2KB/s
Claude today: 125K in / 40K out · ~$3.42
```

Labels are bold, values are regular weight. Tap the widget to force an
immediate refresh (otherwise it refreshes roughly every 30 minutes — the
floor Android enforces for widget updates). It's resizable down to about a
2×1 grid cell for a compact/near-square layout; content just clips at the
bottom if you shrink it below what fits.

If the agent is unreachable, the widget falls back to the last successful
reading and marks the title `— Unreachable (last HH:MM)` instead of going
blank.

## Requirements

- **On the computer being monitored:** Python 3 (stdlib only — no `psutil`
  or other packages needed), Linux (reads `/proc/meminfo`, `/proc/stat`,
  `/proc/net/dev`).
- **On the phone:** Android 10 (API 29) or newer.
- Both need to reach each other over the network — same Wi-Fi, a VPN like
  Tailscale, etc. There's no cloud relay.

## Running the agent

```sh
cd agent
./run_agent.sh          # binds 0.0.0.0:8765, foreground — Ctrl-C to stop
```

Override the port with `MONITOR_PORT`, or force a specific network
interface for the throughput reading with `MONITOR_IFACE` (otherwise it
auto-picks whichever non-loopback interface has the most traffic).

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

Then open the app (**Device Monitor Agent** in your app drawer), enter the
computer's `ip:port` (matching wherever `monitor_agent.py` is bound — e.g.
`192.168.1.50:8765`), tap **Save**, then long-press your home screen →
Widgets → find it and add it.

## Cost estimate caveat

`est_cost_today_usd` is a rough estimate from Anthropic's standard list
pricing per model family (Opus/Sonnet/Haiku), applied to the token counts
found in local session transcripts. It is **not** official billing — it
doesn't account for promotional/intro pricing, plan-based billing, or any
account-specific rates.

## Project structure

```
agent/
  monitor_agent.py       — stdlib-only HTTP server: GET /stats
  run_agent.sh            — convenience launcher
  sysmon-agent.service    — optional systemd --user unit (not installed)
app/src/main/java/com/sysmonwidget/app/
  SysMonWidgetProvider.kt — AppWidgetProvider: fetch, format, render, cache
  StatsClient.kt          — HttpURLConnection GET + JSON parse
  MainActivity.kt         — settings screen (server address)
```
