#!/usr/bin/env python3
"""Runs once at boot: checks the local sysmon agent and Telegrams the result.

Reads the bot token/chat_id from the same config the telegram-bot relay
uses (~/.config/telegram-bot/config.json) so nothing is duplicated.
"""
import json
import os
import socket
import time
import urllib.request

CONFIG_PATH = os.path.expanduser("~/.config/telegram-bot/config.json")
AGENT_URL = "http://127.0.0.1:8765/stats"
RETRIES = 6
RETRY_DELAY_SEC = 5


def check_agent():
    last_error = None
    for _ in range(RETRIES):
        try:
            with urllib.request.urlopen(AGENT_URL, timeout=5) as r:
                if r.status == 200:
                    return True, None
                last_error = f"HTTP {r.status}"
        except Exception as e:
            last_error = str(e)
        time.sleep(RETRY_DELAY_SEC)
    return False, last_error


def send_telegram(text):
    with open(CONFIG_PATH) as f:
        cfg = json.load(f)
    api = f"https://api.telegram.org/bot{cfg['token']}"
    data = json.dumps({"chat_id": cfg["chat_id"], "text": text}).encode()
    req = urllib.request.Request(
        f"{api}/sendMessage", data=data,
        headers={"Content-Type": "application/json"},
    )
    urllib.request.urlopen(req, timeout=20)


def main():
    hostname = socket.gethostname()
    ok, error = check_agent()
    if ok:
        text = f"✅ {hostname} rebooted — sysmon agent is back online."
    else:
        text = (
            f"⚠️ {hostname} rebooted but the sysmon agent is NOT responding "
            f"on :8765 ({error}). Widget will show it offline."
        )
    send_telegram(text)


if __name__ == "__main__":
    main()
