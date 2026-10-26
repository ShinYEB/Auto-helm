"""Restore local dev dependencies after login or a component failure."""

import subprocess
import sys
import urllib.request
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
REDIS_DIR = REPO / "data/dev/redis"


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
        REDIS_DIR.mkdir(parents=True, exist_ok=True)
        subprocess.run(["/opt/homebrew/bin/redis-server", "--bind", "127.0.0.1", "--port", "16379",
                        "--appendonly", "yes", "--dir", str(REDIS_DIR), "--daemonize", "yes",
                        "--pidfile", str(REDIS_DIR / "redis.pid"),
                        "--logfile", str(REDIS_DIR / "redis.log")], check=True)
    if not ready("http://127.0.0.1:8088/"):
        subprocess.run([sys.executable, str(REPO / "monitoring/control_service.py"), "start"], cwd=REPO, check=True)
    if not all(ready(url) for url in ("http://127.0.0.1:9808/health",
                                      "http://127.0.0.1:9090/-/ready",
                                      "http://127.0.0.1:3000/api/health")):
        subprocess.run([sys.executable, str(REPO / "monitoring/native.py"), "stop"], cwd=REPO, check=True)
        subprocess.run([sys.executable, str(REPO / "monitoring/native.py"), "start"], cwd=REPO, check=True)


if __name__ == "__main__":
    main()
