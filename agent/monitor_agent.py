#!/usr/bin/env python3
"""Local stats agent for SysMon widget. Stdlib-only. GET /stats -> JSON."""

import json
import os
import shutil
from datetime import datetime, date, timedelta
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path


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


def read_disk_usage():
    total, used, _free = shutil.disk_usage("/")
    gib = 1024 ** 3
    total_gb = total // gib
    used_gb = used // gib
    percent = round(100 * used / total, 1) if total else 0.0
    return {"total_gb": total_gb, "used_gb": used_gb, "percent": percent}


def read_cpu_temp():
    base = Path("/sys/class/thermal")
    if not base.is_dir():
        return None
    fallback = None
    for zone in sorted(base.glob("thermal_zone*")):
        try:
            zone_type = (zone / "type").read_text().strip().lower()
            temp_raw = int((zone / "temp").read_text().strip())
        except (OSError, ValueError):
            continue
        celsius = temp_raw / 1000.0
        if "pkg" in zone_type or "cpu" in zone_type:
            return round(celsius, 1)
        if fallback is None:
            fallback = celsius
    return round(fallback, 1) if fallback is not None else None


def compute_claude_stats():
    today = date.today()
    cutoff = today - timedelta(days=6)  # rolling 7-day window, inclusive of today

    daily = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}
    weekly = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}
    daily_sessions = set()
    weekly_sessions = set()

    root = Path.home() / ".claude" / "projects"
    if root.is_dir():
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
                        if local_date < cutoff:
                            continue

                        session_id = rec.get("sessionId") or rec.get("session_id")
                        input_tok = usage.get("input_tokens", 0) or 0
                        output_tok = usage.get("output_tokens", 0) or 0
                        cache_read_tok = usage.get("cache_read_input_tokens", 0) or 0
                        cache_creation_tok = usage.get("cache_creation_input_tokens", 0) or 0

                        weekly["input"] += input_tok
                        weekly["output"] += output_tok
                        weekly["cache_read"] += cache_read_tok
                        weekly["cache_creation"] += cache_creation_tok
                        if session_id:
                            weekly_sessions.add(session_id)

                        if local_date == today:
                            daily["input"] += input_tok
                            daily["output"] += output_tok
                            daily["cache_read"] += cache_read_tok
                            daily["cache_creation"] += cache_creation_tok
                            if session_id:
                                daily_sessions.add(session_id)
            except OSError:
                continue

    return {
        "tokens_daily": daily,
        "sessions_daily": len(daily_sessions),
        "tokens_weekly": weekly,
        "sessions_weekly": len(weekly_sessions),
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/stats":
            self.send_response(404)
            self.end_headers()
            return

        payload = {
            "ram": read_meminfo(),
            "storage": read_disk_usage(),
            "cpu_temp_c": read_cpu_temp(),
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
