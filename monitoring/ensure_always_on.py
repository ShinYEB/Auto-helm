"""Restore local dev dependencies after login or a component failure."""

import subprocess
import sys
import urllib.request
from pathlib import Path

from incidents import append_incident


REPO = Path(__file__).resolve().parent.parent
REDIS_DIR = REPO / "data/dev/redis"
DATA = REPO / "data/dev"


def ready(url):
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200
    except Exception:
        return False


def redis_ready():
    response = subprocess.run(["/opt/homebrew/bin/redis-cli", "-h", "127.0.0.1", "-p", "16379", "ping"],
                              capture_output=True, text=True)
    return response.returncode == 0 and response.stdout.strip() == "PONG"


def main():
    if not redis_ready():
        append_incident(DATA, "redis", "RESTART_REQUESTED", detail={"reason": "PING_FAILED"})
        REDIS_DIR.mkdir(parents=True, exist_ok=True)
        subprocess.run(["/opt/homebrew/bin/redis-server", "--bind", "127.0.0.1", "--port", "16379",
                        "--appendonly", "yes", "--dir", str(REDIS_DIR), "--daemonize", "yes",
                        "--pidfile", str(REDIS_DIR / "redis.pid"),
                        "--logfile", str(REDIS_DIR / "redis.log")], check=True)
        append_incident(DATA, "redis", "PROCESS_STARTED")
    if not all(ready(url) for url in ("http://127.0.0.1:9808/health",
                                      "http://127.0.0.1:9090/-/ready",
                                      "http://127.0.0.1:3000/api/health")):
        append_incident(DATA, "monitoring", "RESTART_REQUESTED", detail={"reason": "HTTP_FAILED"})
        subprocess.run([sys.executable, str(REPO / "monitoring/native.py"), "stop"], cwd=REPO, check=True)
        subprocess.run([sys.executable, str(REPO / "monitoring/native.py"), "start"], cwd=REPO, check=True)
        append_incident(DATA, "monitoring", "PROCESS_STARTED")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        append_incident(DATA, "always-on", "RECOVERY_FAILED", detail={"error": type(error).__name__})
        raise
