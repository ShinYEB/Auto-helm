"""Run dev market, event and prediction services only in the trial window.

Intended to run in the foreground under launchd. No order service is started.
"""

import json
import os
import signal
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, time as clock_time
from pathlib import Path
from zoneinfo import ZoneInfo


REPO = Path(__file__).resolve().parent.parent
DATA = REPO / "data/dev"
RUNTIME = DATA / "collection-window"
ENV_FILE = REPO / ".env"
SEOUL = ZoneInfo("Asia/Seoul")
DATES = {"2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30"}
OPEN = clock_time(7, 30)
CLOSE = clock_time(20, 30)
JARS = {
    "event": REPO / "event-data/build/libs/event-data-0.1.0-SNAPSHOT.jar",
    "prediction": REPO / "price-prediction/build/libs/price-prediction-0.1.0-SNAPSHOT.jar",
    "market": REPO / "market-data/build/libs/market-data-0.1.0-SNAPSHOT.jar",
}


def now():
    return datetime.now(SEOUL)


def active(moment):
    return moment.date().isoformat() in DATES and OPEN <= moment.time() < CLOSE


def atomic_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False))
    temp.replace(path)


def credentials():
    values = {}
    for line in ENV_FILE.read_text().splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            if key in {"KIS_APP_KEY", "KIS_APP_SECRET", "DART_API_KEY", "JEV_API_KEY"}:
                values[key] = value.strip()
    missing = {"KIS_APP_KEY", "KIS_APP_SECRET", "DART_API_KEY", "JEV_API_KEY"} - values.keys()
    if missing or any(not value for value in values.values()):
        raise RuntimeError("Required local credentials are missing")
    return values


def sessions(date):
    # Bound the data model to actual trading sessions, not the collector's wider uptime.
    return [
        ("KRX", "regular", "09:00:00", "15:30:00"),
        ("NXT", "pre", "08:00:00", "08:50:00"),
        ("NXT", "main", "09:00:00", "15:20:00"),
        ("NXT", "after", "15:40:00", "20:00:00"),
    ]


def config(date, service):
    path = RUNTIME / f"{service}-{date}.yml"
    session_lines = "\n".join(
        f'    - venue: {venue}\n      name: {name}\n      dates: ["{date}"]\n'
        f'      start: "{start}"\n      end: "{end}"'
        for venue, name, start, end in sessions(date)
    )
    text = (
        "spring:\n  data:\n    redis:\n      host: 127.0.0.1\n      port: 16379\n"
        "market:\n  enabled: true\n  environment: dev\n  data-dir: ./data\n"
        "  rest-url: https://openapivts.koreainvestment.com:29443\n"
        "  ws-url: ws://ops.koreainvestment.com:31000/tryitout\n"
        "  redis:\n    enabled: true\n  sessions:\n" + session_lines + "\n"
        if service == "market" else
        "spring:\n  data:\n    redis:\n      host: 127.0.0.1\n      port: 16379\n"
        "prediction:\n  enabled: true\n  environment: dev\n  data-dir: ./data\n"
        "  sessions:\n" + session_lines + "\n"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


def launch(service, date, env):
    if service == "event":
        cfg = DATA / "event-dev.yml"
    else:
        cfg = config(date, service)
    log = DATA / f"market-day-{date}.log" if service == "market" else RUNTIME / f"{service}-{date}.log"
    with log.open("ab") as output:
        return subprocess.Popen(
            ["java", "-jar", str(JARS[service]), f"--spring.config.additional-location={cfg.as_uri()}"],
            cwd=REPO, env=env, stdin=subprocess.DEVNULL, stdout=output,
            stderr=subprocess.STDOUT, start_new_session=True,
        )


def stop_child(child):
    if child.poll() is not None:
        return
    os.killpg(child.pid, signal.SIGTERM)
    try:
        child.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGKILL)
        child.wait()


def daily_report(date, restarts, stopped_at):
    base = DATA / "market"
    frames, bars, gaps, log_events = Counter(), Counter(), Counter(), Counter()
    parse_errors = 0
    for kind, target in (("raw", frames), ("bars", bars), ("gaps", gaps)):
        path = base / kind / f"{date}.jsonl"
        if not path.exists():
            continue
        with path.open() as stream:
            for line in stream:
                try:
                    row = json.loads(line)
                    if kind == "raw":
                        channel = row["text"].split("|", 3)[1]
                        venue = {"H0STCNT0": "KRX", "H0NXCNT0": "NXT"}.get(channel)
                        if venue:
                            target[venue] += 1
                    elif kind == "bars":
                        target[f"{row['venue']}:{row['quality']}"] += 1
                    else:
                        target[row["reason"]] += 1
                except (ValueError, KeyError, IndexError):
                    parse_errors += 1
    path = DATA / f"market-day-{date}.log"
    if path.exists():
        with path.open(errors="replace") as stream:
            for line in stream:
                for marker, label in (("WebSocket connection failed", "websocket_connection"),
                                      ("subscription rejected", "subscription_rejected"),
                                      ("Rejected market frame", "invalid_frame"),
                                      ("Market pipeline stopped", "pipeline_error")):
                    if marker in line:
                        log_events[label] += 1
    atomic_json(DATA / f"market-day-{date}.report.json", {
        "date": date, "environment": "dev", "window": "07:30-20:30 KST",
        "stoppedAt": stopped_at.isoformat(), "automaticStop": True,
        "frames": dict(frames), "bars": dict(bars), "gaps": dict(gaps),
        "logEvents": dict(log_events), "jsonParseErrors": parse_errors,
        "processRestarts": restarts,
    })


def run():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if not all(path.exists() for path in JARS.values()):
        raise RuntimeError("Build market-data, event-data and price-prediction first")
    if not (DATA / "event-dev.yml").exists():
        raise RuntimeError("Missing event dev configuration")
    env = {**os.environ, **credentials()}
    children = {}
    restarts = {name: 0 for name in JARS}
    launch_at = {}
    stopped = False
    date = None

    def request_stop(_signal, _frame):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    try:
        while not stopped:
            moment = now()
            today = moment.date().isoformat()
            in_window = active(moment)
            if in_window:
                if date != today:
                    date = today
                    restarts = {name: 0 for name in JARS}
                for name in ("event", "prediction", "market"):
                    child = children.get(name)
                    if child is None or child.poll() is not None:
                        if child is not None:
                            restarts[name] += 1
                        if time.monotonic() - launch_at.get(name, 0) >= 10:
                            children[name] = launch(name, today, env)
                            launch_at[name] = time.monotonic()
            else:
                for child in children.values():
                    stop_child(child)
                children.clear()
                observed_run = date == today or (DATA / f"market-day-{today}.log").exists()
                if today in DATES and moment.time() >= CLOSE and observed_run:
                    report_path = DATA / f"market-day-{today}.report.json"
                    if not report_path.exists():
                        daily_report(today, restarts, moment)
            status = {
                "state": "running" if in_window else "waiting",
                "at": moment.isoformat(), "stop_at": f"{today}T20:30:00+09:00",
                "supervisor_pid": os.getpid(),
                "collector_pid": children["market"].pid if "market" in children and children["market"].poll() is None else None,
                "processes": {name: child.pid for name, child in children.items() if child.poll() is None},
                "restarts": restarts,
                "environment": "dev", "window": "07:30-20:30 KST",
            }
            atomic_json(RUNTIME / "status.json", status)
            if today in DATES:
                atomic_json(DATA / f"market-day-{today}.status.json", status)
            time.sleep(5)
    finally:
        for child in children.values():
            stop_child(child)
        moment = now()
        status = {"state": "stopped", "at": moment.isoformat(), "supervisor_pid": os.getpid(),
                  "collector_pid": None, "processes": {}, "restarts": restarts, "environment": "dev"}
        atomic_json(RUNTIME / "status.json", status)
        if moment.date().isoformat() in DATES:
            atomic_json(DATA / f"market-day-{moment.date().isoformat()}.status.json", status)


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "status":
        try:
            print((RUNTIME / "status.json").read_text())
        except FileNotFoundError:
            print("Collection window supervisor has not started")
            sys.exit(1)
    elif len(sys.argv) == 2 and sys.argv[1] == "run":
        run()
    else:
        print("Usage: python3 monitoring/collection_window.py {run|status}")
        sys.exit(2)
