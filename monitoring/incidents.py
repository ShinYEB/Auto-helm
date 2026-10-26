"""Append-only operational events for replay and daily feedback review."""

import json
import os
import uuid
import fcntl
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo


SEOUL = ZoneInfo("Asia/Seoul")


def incident_path(data_root, date):
    return Path(data_root) / "operations/incidents" / f"{date}.jsonl"


def append_incident(data_root, service, kind, *, detail=None, at=None):
    """Write one complete record. Never place credentials, URLs, or raw responses in detail."""
    at = at or datetime.now(SEOUL)
    path = incident_path(data_root, at.date().isoformat())
    path.parent.mkdir(parents=True, exist_ok=True)
    record = {
        "id": str(uuid.uuid4()), "at": at.isoformat(), "service": service,
        "kind": kind, "detail": detail or {},
    }
    payload = (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        offset = 0
        while offset < len(payload):
            written = os.write(fd, payload[offset:])
            if written <= 0:
                raise OSError("Incident journal write stopped")
            offset += written
        os.fsync(fd)
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
    return record["id"]


def incident_counts(data_root, date):
    counts = Counter()
    path = incident_path(data_root, date)
    if path.exists():
        with path.open() as stream:
            for line in stream:
                try:
                    record = json.loads(line)
                    counts[f"{record['service']}:{record['kind']}"] += 1
                except (ValueError, KeyError):
                    counts["journal:parse_error"] += 1
    return dict(counts)


def recent_restarts(data_root, service, at, minutes=15):
    path = incident_path(data_root, at.date().isoformat())
    if not path.exists():
        return []
    cutoff = at - timedelta(minutes=minutes)
    recent = []
    with path.open() as stream:
        for line in stream:
            try:
                item = json.loads(line)
                if item.get("service") != service or item.get("kind") != "RESTART_REQUESTED":
                    continue
                stamp = datetime.fromisoformat(item["at"])
                if stamp >= cutoff:
                    recent.append(stamp)
            except (ValueError, KeyError):
                continue
    return recent
