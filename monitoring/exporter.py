"""Read-only Prometheus exporter for market and event collectors.

The collector keeps its own JSONL journal. This process tails those files and
never needs KIS credentials or access to the trading API.
"""

import json
import os
from collections import Counter
from datetime import datetime, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo


DATA_ROOT = Path(os.environ.get("AUTOHELM_DATA_ROOT", "/data/dev"))
PORT = int(os.environ.get("AUTOHELM_EXPORTER_PORT", "9808"))
HOST = os.environ.get("AUTOHELM_EXPORTER_HOST", "0.0.0.0")
TEST_DATE = os.environ.get("AUTOHELM_TEST_DATE")
SEOUL = ZoneInfo("Asia/Seoul")


def epoch(value):
    if not value:
        return 0.0
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def sample(name, value, labels=None):
    suffix = ""
    if labels:
        values = ",".join(f'{key}="{str(item).replace(chr(34), "")}"' for key, item in labels.items())
        suffix = "{" + values + "}"
    return f"{name}{suffix} {value}\n"


class Tail:
    def __init__(self, path, on_row):
        self.path = path
        self.on_row = on_row
        self.offset = 0
        self.inode = None
        self.parse_errors = 0

    def update(self):
        try:
            stat = self.path.stat()
        except FileNotFoundError:
            return
        if self.inode != stat.st_ino or stat.st_size < self.offset:
            self.offset = 0
            self.inode = stat.st_ino
        with self.path.open("rb") as source:
            source.seek(self.offset)
            while True:
                line = source.readline()
                if not line or not line.endswith(b"\n"):
                    break
                self.offset = source.tell()
                try:
                    self.on_row(json.loads(line))
                except (UnicodeDecodeError, json.JSONDecodeError, KeyError, ValueError):
                    self.parse_errors += 1


class Metrics:
    def __init__(self):
        self.date = None
        self.reset_day(self.current_date())

    def current_date(self):
        return TEST_DATE or datetime.now(SEOUL).date().isoformat()

    def reset_day(self, date):
        self.date = date
        self.frames = Counter()
        self.last_frame = {"KRX": 0.0, "NXT": 0.0}
        self.bars = Counter()
        self.last_ready_bar = {"KRX": 0.0, "NXT": 0.0}
        self.gaps = Counter()
        self.log_errors = Counter()
        self.incidents = Counter()
        self.event_failures = Counter()
        base = DATA_ROOT / "market"
        self.tails = [
            Tail(base / "raw" / f"{date}.jsonl", self.raw),
            Tail(base / "bars" / f"{date}.jsonl", self.bar),
            Tail(base / "gaps" / f"{date}.jsonl", self.gap),
            Tail(DATA_ROOT / "operations/incidents" / f"{date}.jsonl", self.incident),
            Tail(DATA_ROOT / "events/health-events" / f"{date}.jsonl", self.event_failure),
        ]
        self.log_offset = 0

    def raw(self, row):
        parts = row["text"].split("|", 3)
        venue = {"H0STCNT0": "KRX", "H0NXCNT0": "NXT"}.get(parts[1])
        if venue:
            self.frames[venue] += 1
            self.last_frame[venue] = max(self.last_frame[venue], epoch(row["receivedAt"]))

    def bar(self, row):
        venue, quality = row["venue"], row["quality"]
        self.bars[venue, quality] += 1
        if quality == "READY":
            self.last_ready_bar[venue] = max(self.last_ready_bar[venue], epoch(row["sealedAt"]))

    def gap(self, row):
        self.gaps[row["reason"]] += 1

    def incident(self, row):
        self.incidents[row["service"], row["kind"]] += 1

    def event_failure(self, row):
        self.event_failures[row["source"], row["kind"]] += 1

    def update_log(self):
        path = DATA_ROOT / f"market-day-{self.date}.log"
        try:
            size = path.stat().st_size
        except FileNotFoundError:
            return
        if size < self.log_offset:
            self.log_offset = 0
        with path.open("rb") as source:
            source.seek(self.log_offset)
            while True:
                line = source.readline()
                if not line or not line.endswith(b"\n"):
                    break
                self.log_offset = source.tell()
                text = line.decode("utf-8", errors="replace")
                for marker, kind in (
                    ("WebSocket connection failed", "websocket_connection"),
                    ("subscription rejected", "subscription_rejected"),
                    ("Rejected market frame", "invalid_frame"),
                    ("Market pipeline stopped", "pipeline_error"),
                    ("collector exited with code", "collector_exit"),
                ):
                    if marker in text:
                        self.log_errors[kind] += 1

    def status(self):
        path = DATA_ROOT / f"market-day-{self.date}.status.json"
        try:
            return json.loads(path.read_text())
        except (FileNotFoundError, UnicodeDecodeError, json.JSONDecodeError):
            return {}

    def session_open(self, venue):
        now = datetime.now(SEOUL)
        if now.date().isoformat() != self.date:
            return 0
        clock = now.time()
        if venue == "KRX":
            return int(time(9, 0) <= clock < time(15, 30))
        return int(time(8, 0) <= clock < time(8, 50) or time(9, 0) <= clock < time(15, 20) or time(15, 40) <= clock < time(20, 0))

    def render(self):
        if self.current_date() != self.date:
            self.reset_day(self.current_date())
        for tail in self.tails:
            tail.update()
        self.update_log()
        status = self.status()
        heartbeat = epoch(status.get("at"))
        now = datetime.now(SEOUL).timestamp()
        running = status.get("state") == "running" and status.get("collector_pid") is not None
        up = int(running and 0 <= now - heartbeat < 30)

        restart_value = status.get("restarts", 0)
        restart_total = sum(restart_value.values()) if isinstance(restart_value, dict) else int(restart_value)
        lines = [
            "# HELP autohelm_collector_up Collector and supervisor heartbeat are current.\n",
            "# TYPE autohelm_collector_up gauge\n",
            sample("autohelm_collector_up", up),
            sample("autohelm_supervisor_heartbeat_timestamp_seconds", heartbeat),
            sample("autohelm_collector_restarts_total", restart_total),
            sample("autohelm_unresolved_alerts", len(status.get("alerts", []))),
        ]
        for (service, kind), count in self.incidents.items():
            lines.append(sample("autohelm_incidents_total", count, {"service": service, "kind": kind}))
        for (source, kind), count in self.event_failures.items():
            lines.append(sample("autohelm_event_failures_total", count, {"source": source, "kind": kind}))
        for venue in ("KRX", "NXT"):
            labels = {"venue": venue}
            lines += [
                sample("autohelm_market_session_open", self.session_open(venue), labels),
                sample("autohelm_market_frames_total", self.frames[venue], labels),
                sample("autohelm_market_last_frame_timestamp_seconds", self.last_frame[venue], labels),
                sample("autohelm_market_last_ready_bar_timestamp_seconds", self.last_ready_bar[venue], labels),
            ]
            for quality in ("READY", "INCOMPLETE", "EMPTY_UNVERIFIED"):
                lines.append(sample("autohelm_market_bars_total", self.bars[venue, quality], {"venue": venue, "quality": quality}))
        for reason in ("DISCONNECTED", "INVALID_FRAME", "LATE_TICK", "QUEUE_OVERFLOW", "PROCESS_RESTART"):
            lines.append(sample("autohelm_market_gaps_total", self.gaps[reason], {"reason": reason}))
        for kind in ("websocket_connection", "subscription_rejected", "invalid_frame", "pipeline_error", "collector_exit"):
            lines.append(sample("autohelm_market_log_events_total", self.log_errors[kind], {"kind": kind}))
        try:
            event = json.loads((DATA_ROOT / "events/health.json").read_text())
        except (FileNotFoundError, UnicodeDecodeError, json.JSONDecodeError):
            event = {}
        pid = event.get("pid")
        try:
            os.kill(int(pid), 0)
            event_process_alive = True
        except (TypeError, ValueError, ProcessLookupError, PermissionError):
            event_process_alive = False
        lines.append(sample("autohelm_event_up", int(bool(event.get("running")) and event_process_alive)))
        lines.append(sample("autohelm_event_heartbeat_timestamp_seconds", epoch(event.get("heartbeatAt"))))
        for source in ("DART", "GDELT", "EINFOMAX"):
            state = event.get("sources", {}).get(source, {})
            labels = {"source": source}
            lines += [
                sample("autohelm_event_source_enabled", int(bool(state.get("enabled"))), labels),
                sample("autohelm_event_queries_total", int(state.get("attempts", 0)), {**labels, "result": "attempt"}),
                sample("autohelm_event_queries_total", int(state.get("successes", 0)), {**labels, "result": "success"}),
                sample("autohelm_event_queries_total", int(state.get("failures", 0)), {**labels, "result": "failure"}),
                sample("autohelm_event_rate_limits_total", int(state.get("rateLimits", 0)), labels),
                sample("autohelm_event_discovered_total", int(state.get("discovered", 0)), labels),
                sample("autohelm_event_last_attempt_timestamp_seconds", epoch(state.get("lastAttemptAt")), labels),
                sample("autohelm_event_last_success_timestamp_seconds", epoch(state.get("lastSuccessAt")), labels),
            ]
        for feed, state in event.get("rssFeeds", {}).items():
            labels = {"feed": feed}
            lines += [
                sample("autohelm_rss_feed_items", int(state.get("lastItemCount", 0)), labels),
                sample("autohelm_rss_feed_overlap", int(state.get("lastOverlap") or 0), labels),
                sample("autohelm_rss_feed_new", int(state.get("lastNewCount", 0)), labels),
                sample("autohelm_rss_feed_possible_gaps_total", int(state.get("possibleGaps", 0)), labels),
            ]
        lines.append(sample("autohelm_exporter_parse_errors_total", sum(t.parse_errors for t in self.tails)))
        return "".join(lines).encode()


METRICS = Metrics()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/metrics", "/health"):
            self.send_error(404)
            return
        try:
            payload = METRICS.render() if self.path == "/metrics" else b"ok\n"
        except Exception:
            self.send_error(500)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    HTTPServer((HOST, PORT), Handler).serve_forever()
