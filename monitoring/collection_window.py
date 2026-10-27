"""Run dev market, event and prediction services only in the trial window.

Intended to run in the foreground under launchd. No order service is started.
"""

import json
import os
import signal
import subprocess
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, time as clock_time
from pathlib import Path
from zoneinfo import ZoneInfo

from incidents import append_incident, incident_counts, recent_restarts
from recovery import STARTUP_GRACE, heartbeat_issues, market_issues, prediction_issues


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


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, ValueError, OSError):
        return None


def modified_at(path):
    try:
        return datetime.fromtimestamp(path.stat().st_mtime, SEOUL)
    except FileNotFoundError:
        return None


def restart_permitted(service, moment, reason):
    recent = recent_restarts(DATA, service, moment)
    interval = 300 if reason != "PROCESS_EXITED" else 30
    return len(recent) < 3 and (not recent or (moment - recent[-1]).total_seconds() >= interval)


def active_issues(service, child, moment, started_at, market_health):
    if service == "market":
        return market_issues(market_health, child.pid, moment, started_at, sessions(moment.date().isoformat()))
    if service == "event":
        health = read_json(DATA / "events/health.json")
        return heartbeat_issues("event", health, child.pid, moment, started_at)
    output = modified_at(DATA / f"prediction/predictions/{moment.date().isoformat()}.jsonl")
    return prediction_issues(market_health, output, moment, started_at)


def atomic_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False))
    temp.replace(path)


def write_once_json(path, payload):
    """Publish an immutable daily report only after its bytes are durable."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("x") as output:
            json.dump(payload, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def credentials():
    values = {}
    for line in ENV_FILE.read_text().splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            if key in {"KIS_APP_KEY", "KIS_APP_SECRET", "DART_API_KEY", "JEV_API_KEY",
                       "NAVER_API_HUB_CLIENT_ID", "NAVER_API_HUB_CLIENT_SECRET"}:
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
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        child.wait()
        return
    try:
        child.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGKILL)
        child.wait()


def cleanup_orphans():
    """A killed supervisor may leave detached Java children; stop only verified JAR PIDs."""
    previous = read_json(RUNTIME / "status.json") or {}
    prior_supervisor = previous.get("supervisor_pid")
    if isinstance(prior_supervisor, int) and prior_supervisor != os.getpid():
        command = subprocess.run(["ps", "-p", str(prior_supervisor), "-o", "command="],
                                 capture_output=True, text=True)
        if command.returncode == 0 and str(REPO / "monitoring/collection_window.py") in command.stdout:
            raise RuntimeError("Collection supervisor is already running")
    for name, pid in previous.get("processes", {}).items():
        if name not in JARS or not isinstance(pid, int) or pid <= 0:
            continue
        command = subprocess.run(["ps", "-p", str(pid), "-o", "command="],
                                 capture_output=True, text=True)
        if command.returncode != 0 or str(JARS[name]) not in command.stdout:
            continue
        append_incident(DATA, name, "ORPHAN_STOP_REQUESTED", detail={"pid": pid})
        try:
            os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            continue
        for _ in range(20):
            if subprocess.run(["ps", "-p", str(pid)], capture_output=True).returncode != 0:
                break
            time.sleep(0.5)
        else:
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


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
    source_failures, event_analysis, prediction_status = Counter(), Counter(), Counter()
    for path, target, field in (
        (DATA / f"events/health-events/{date}.jsonl", source_failures, "kind"),
        (DATA / f"events/analysis/{date}.jsonl", event_analysis, "status"),
        (DATA / f"prediction/predictions/{date}.jsonl", prediction_status, "status"),
    ):
        if not path.exists():
            continue
        with path.open() as stream:
            for line in stream:
                try:
                    row = json.loads(line)
                    label = f"{row.get('source', '')}:{row[field]}" if target is source_failures else str(row[field])
                    target[label] += 1
                except (ValueError, KeyError):
                    parse_errors += 1
    incidents = incident_counts(DATA, date)
    write_once_json(DATA / f"market-day-{date}.report.json", {
        "date": date, "environment": "dev", "window": "07:30-20:30 KST",
        "stoppedAt": stopped_at.isoformat(), "automaticStop": True,
        "frames": dict(frames), "bars": dict(bars), "gaps": dict(gaps),
        "logEvents": dict(log_events), "jsonParseErrors": parse_errors,
        "processRestarts": {name: incidents.get(f"{name}:RESTART_REQUESTED", 0) for name in JARS},
        "incidents": incidents,
        "eventSourceFailures": dict(source_failures),
        "eventAnalysis": dict(event_analysis),
        "predictionStatus": dict(prediction_status),
    })


def run():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if not all(path.exists() for path in JARS.values()):
        raise RuntimeError("Build market-data, event-data and price-prediction first")
    if not (DATA / "event-dev.yml").exists():
        raise RuntimeError("Missing event dev configuration")
    cleanup_orphans()
    env = {**os.environ, **credentials()}
    append_incident(DATA, "collection-supervisor", "SUPERVISOR_STARTED", detail={"pid": os.getpid()})
    children = {}
    restarts = {name: 0 for name in JARS}
    launch_at = {}
    started_at = {}
    alerts = set()
    suppressed = set()
    pending_restarts = set()
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
                    launch_at.clear()
                    alerts.clear()
                    suppressed.clear()
                    pending_restarts.clear()
                market_health = read_json(DATA / "market/health.json")
                for name in ("event", "prediction", "market"):
                    child = children.get(name)
                    if child is not None and child.poll() is not None:
                        append_incident(DATA, name, "PROCESS_EXITED", detail={"exitCode": child.returncode, "pid": child.pid}, at=moment)
                        children.pop(name)
                        started_at.pop(name, None)
                        restarts[name] += 1
                        child = None
                    if child is None:
                        reason = "RECOVERY_START" if name in pending_restarts else (
                            "PROCESS_EXITED" if name in launch_at else "INITIAL_START")
                        if reason == "PROCESS_EXITED" and not restart_permitted(name, moment, reason):
                            if name not in suppressed:
                                append_incident(DATA, name, "RESTART_SUPPRESSED", detail={"reason": reason}, at=moment)
                                suppressed.add(name)
                            continue
                        if time.monotonic() - launch_at.get(name, 0) >= 10:
                            if reason == "PROCESS_EXITED":
                                append_incident(DATA, name, "RESTART_REQUESTED", detail={"reason": reason}, at=moment)
                            children[name] = launch(name, today, env)
                            launch_at[name] = time.monotonic()
                            started_at[name] = moment
                            append_incident(DATA, name, "PROCESS_STARTED", detail={"pid": children[name].pid}, at=moment)
                            pending_restarts.discard(name)
                            suppressed.discard(name)
                        continue
                    if moment - started_at.get(name, moment) < STARTUP_GRACE:
                        continue
                    problems = active_issues(name, child, moment, started_at.get(name, moment), market_health)
                    keys = {(name, p["kind"], p["detail"].get("venue")) for p in problems}
                    for problem in problems:
                        key = (name, problem["kind"], problem["detail"].get("venue"))
                        if key not in alerts:
                            append_incident(DATA, name, "ALERT_OPENED", detail={"code": problem["kind"], **problem["detail"]}, at=moment)
                            alerts.add(key)
                    for key in list(alerts):
                        if key[0] == name and key not in keys:
                            append_incident(DATA, name, "ALERT_RESOLVED", detail={"code": key[1], "venue": key[2]}, at=moment)
                            alerts.remove(key)
                    restart_reason = next((p["kind"] for p in problems if p["restart"]), None)
                    if restart_reason:
                        if restart_permitted(name, moment, restart_reason):
                            append_incident(DATA, name, "RESTART_REQUESTED", detail={"reason": restart_reason, "pid": child.pid}, at=moment)
                            stop_child(child)
                            children.pop(name)
                            started_at.pop(name, None)
                            restarts[name] += 1
                            pending_restarts.add(name)
                            suppressed.discard(name)
                        elif name not in suppressed:
                            append_incident(DATA, name, "RESTART_SUPPRESSED", detail={"reason": restart_reason}, at=moment)
                            suppressed.add(name)
            else:
                for name, child in children.items():
                    append_incident(DATA, name, "SCHEDULED_STOP", detail={"pid": child.pid}, at=moment)
                    stop_child(child)
                children.clear()
                started_at.clear()
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
                "alerts": [{"service": service, "code": code, "venue": venue} for service, code, venue in sorted(alerts)],
                "environment": "dev", "window": "07:30-20:30 KST",
            }
            atomic_json(RUNTIME / "status.json", status)
            if today in DATES:
                atomic_json(DATA / f"market-day-{today}.status.json", status)
            time.sleep(5)
    except Exception as error:
        append_incident(DATA, "collection-supervisor", "SUPERVISOR_FAILED",
                        detail={"error": type(error).__name__})
        raise
    finally:
        for name, child in children.items():
            try:
                append_incident(DATA, name, "SUPERVISOR_STOP", detail={"pid": child.pid})
            except OSError:
                print("Incident journal unavailable during shutdown", file=sys.stderr)
            stop_child(child)
        moment = now()
        status = {"state": "stopped", "at": moment.isoformat(), "supervisor_pid": os.getpid(),
                  "collector_pid": None, "processes": {}, "restarts": restarts, "environment": "dev"}
        atomic_json(RUNTIME / "status.json", status)
        if moment.date().isoformat() in DATES:
            atomic_json(DATA / f"market-day-{moment.date().isoformat()}.status.json", status)
        append_incident(DATA, "collection-supervisor", "SUPERVISOR_STOPPED", detail={"pid": os.getpid()})


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
