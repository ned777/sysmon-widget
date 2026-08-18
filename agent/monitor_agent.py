#!/usr/bin/env python3
"""Local stats agent for SysMon widget. Stdlib-only. GET /stats -> JSON."""

import json
import os
import shutil
import time
from datetime import datetime, date
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path

# Rough cost ESTIMATE only, not official billing. Anthropic standard list
# pricing (USD per 1M tokens) as of Aug 2026. claude-sonnet-5 has a
# temporary intro rate through 2026-08-31; intentionally using the
# standard $3/$15 rate here so this doesn't silently go stale after that.
PRICING = {
    "opus":   {"input": 5.00, "output": 25.00, "cache_write_5m": 6.25, "cache_write_1h": 10.00, "cache_read": 0.50},
    "sonnet": {"input": 3.00, "output": 15.00, "cache_write_5m": 3.75, "cache_write_1h": 6.00,  "cache_read": 0.30},
    "haiku":  {"input": 1.00, "output": 5.00,  "cache_write_5m": 1.25, "cache_write_1h": 2.00,  "cache_read": 0.10},
}

_prev_net = None  # (timestamp, rx_bytes, tx_bytes, iface)
_prev_cpu = None  # (idle_all, total)


def read_meminfo():
    info = {}
    with open("/proc/meminfo") as f:
        for line in f:
            key, _, rest = line.partition(":")
            info[key] = int(rest.strip().split()[0])  # kB
    total_kb = info.get("MemTotal", 0)
    avail_kb = info.get("MemAvailable")
    if avail_kb is None:
        avail_kb = info.get("MemFree", 0) + info.get("Buffers", 0) + info.get("Cached", 0)
    used_kb = total_kb - avail_kb
    percent = round(100 * used_kb / total_kb, 1) if total_kb else 0.0
    return {"total_mb": total_kb // 1024, "used_mb": used_kb // 1024, "percent": percent}


def read_net_dev():
    result = {}
    with open("/proc/net/dev") as f:
        lines = f.readlines()[2:]
    for line in lines:
        iface, rest = line.split(":", 1)
        iface = iface.strip()
        fields = rest.split()
        rx_bytes = int(fields[0])
        tx_bytes = int(fields[8])
        result[iface] = (rx_bytes, tx_bytes)
    return result


def pick_interface(samples):
    override = os.environ.get("MONITOR_IFACE")
    if override and override in samples:
        return override
    candidates = {k: v for k, v in samples.items() if k != "lo"}
    if not candidates:
        return "lo"
    return max(candidates, key=lambda k: candidates[k][0] + candidates[k][1])


def get_network_stats():
    global _prev_net
    now = time.time()
    samples = read_net_dev()
    iface = pick_interface(samples)
    rx, tx = samples.get(iface, (0, 0))

    rx_rate = 0
    tx_rate = 0
    if _prev_net is not None:
        prev_time, prev_rx, prev_tx, prev_iface = _prev_net
        dt = now - prev_time
        if prev_iface == iface and dt > 0 and rx >= prev_rx and tx >= prev_tx:
            rx_rate = round((rx - prev_rx) / dt)
            tx_rate = round((tx - prev_tx) / dt)

    _prev_net = (now, rx, tx, iface)
    return {"rx_bytes_per_sec": rx_rate, "tx_bytes_per_sec": tx_rate, "interface": iface}


def read_cpu_times():
    with open("/proc/stat") as f:
        fields = f.readline().split()[1:]  # skip leading "cpu"
    nums = [int(x) for x in fields[:8]]  # user nice system idle iowait irq softirq steal
    user, nice, system, idle, iowait, irq, softirq, steal = nums
    idle_all = idle + iowait
    total = user + nice + system + idle_all + irq + softirq + steal
    return idle_all, total


def get_cpu_percent():
    global _prev_cpu
    idle_all, total = read_cpu_times()

    percent = 0.0
    if _prev_cpu is not None:
        prev_idle, prev_total = _prev_cpu
        delta_total = total - prev_total
        delta_idle = idle_all - prev_idle
        if delta_total > 0:
            percent = round(100 * (1 - delta_idle / delta_total), 1)

    _prev_cpu = (idle_all, total)
    return {"percent": percent}


def read_disk_usage():
    total, used, _free = shutil.disk_usage("/")
    gib = 1024 ** 3
    total_gb = total // gib
    used_gb = used // gib
    percent = round(100 * used / total, 1) if total else 0.0
    return {"total_gb": total_gb, "used_gb": used_gb, "percent": percent}


def model_family(model_id):
    m = (model_id or "").lower()
    if "opus" in m:
        return "opus"
    if "haiku" in m:
        return "haiku"
    return "sonnet"


def iter_today_usage_records():
    root = Path.home() / ".claude" / "projects"
    if not root.is_dir():
        return
    today = date.today()
    for path in root.glob("**/*.jsonl"):
        try:
            with open(path, "r", errors="ignore") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if rec.get("type") != "assistant":
                        continue
                    message = rec.get("message") or {}
                    usage = message.get("usage")
                    ts = rec.get("timestamp")
                    if not usage or not ts:
                        continue
                    try:
                        local_date = datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone().date()
                    except ValueError:
                        continue
                    if local_date != today:
                        continue
                    session_id = rec.get("sessionId") or rec.get("session_id")
                    yield message.get("model"), usage, session_id
        except OSError:
            continue


def compute_claude_stats():
    totals = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}
    cost = 0.0
    sessions = set()

    for model, usage, session_id in iter_today_usage_records():
        if session_id:
            sessions.add(session_id)
        family = model_family(model)
        rates = PRICING[family]

        input_tok = usage.get("input_tokens", 0) or 0
        output_tok = usage.get("output_tokens", 0) or 0
        cache_read_tok = usage.get("cache_read_input_tokens", 0) or 0
        cache_creation_tok = usage.get("cache_creation_input_tokens", 0) or 0

        totals["input"] += input_tok
        totals["output"] += output_tok
        totals["cache_read"] += cache_read_tok
        totals["cache_creation"] += cache_creation_tok

        cost += input_tok / 1_000_000 * rates["input"]
        cost += output_tok / 1_000_000 * rates["output"]
        cost += cache_read_tok / 1_000_000 * rates["cache_read"]

        breakdown = usage.get("cache_creation")
        if isinstance(breakdown, dict) and (
            "ephemeral_5m_input_tokens" in breakdown or "ephemeral_1h_input_tokens" in breakdown
        ):
            tok_5m = breakdown.get("ephemeral_5m_input_tokens", 0) or 0
            tok_1h = breakdown.get("ephemeral_1h_input_tokens", 0) or 0
            cost += tok_5m / 1_000_000 * rates["cache_write_5m"]
            cost += tok_1h / 1_000_000 * rates["cache_write_1h"]
        else:
            cost += cache_creation_tok / 1_000_000 * rates["cache_write_5m"]

    return {
        "tokens_today": totals,
        "est_cost_today_usd": round(cost, 4),
        "sessions_today": len(sessions),
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/stats":
            self.send_response(404)
            self.end_headers()
            return

        payload = {
            "ram": read_meminfo(),
            "cpu": get_cpu_percent(),
            "storage": read_disk_usage(),
            "network": get_network_stats(),
            "claude": compute_claude_stats(),
            "generated_at": datetime.now().astimezone().isoformat(),
        }
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    port = int(os.environ.get("MONITOR_PORT", "8765"))
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"SysMon agent listening on 0.0.0.0:{port}")
    server.serve_forever()
