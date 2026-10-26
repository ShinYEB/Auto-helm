"""Keep the local dev control tower running without exposing its token in process arguments."""

import json
import os
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
ROOT = REPO / "data/dev/control/runtime"
STATE = ROOT / "supervisor.json"
LOG = ROOT / "control-tower.log"
JAR = REPO / "control-tower/build/libs/control-tower-0.1.0-SNAPSHOT.jar"
ENV_FILE = REPO / ".env"


def alive(pid):
    try:
        os.kill(int(pid), 0)
        return True
    except (TypeError, ValueError, ProcessLookupError, PermissionError):
        return False


def state():
    try:
        return json.loads(STATE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def write_state(**fields):
    ROOT.mkdir(parents=True, exist_ok=True)
    temporary = STATE.with_suffix(".tmp")
    temporary.write_text(json.dumps({"supervisor_pid": os.getpid(), **fields}))
    temporary.replace(STATE)


def ready():
    try:
        with urllib.request.urlopen("http://127.0.0.1:8088/", timeout=2) as response:
            return response.status == 200
    except Exception:
        return False


def secret_env():
    if not ENV_FILE.exists():
        raise RuntimeError("Missing local .env file")
    values = dict(line.split("=", 1) for line in ENV_FILE.read_text().splitlines()
                  if line.startswith("AUTOHELM_CONTROL_TOKEN="))
    token = values.get("AUTOHELM_CONTROL_TOKEN", "").strip()
    if len(token) < 32:
        raise RuntimeError("AUTOHELM_CONTROL_TOKEN must have at least 32 characters")
    env = os.environ.copy()
    env.pop("DART_API_KEY", None)
    env.pop("KIS_APP_KEY", None)
    env.pop("KIS_APP_SECRET", None)
    env["AUTOHELM_CONTROL_TOKEN"] = token
    return env


def run():
    env = secret_env()
    stopping = False
    child = None
    restarts = 0

    def request_stop(_signal, _frame):
        nonlocal stopping
        stopping = True
        if child and child.poll() is None:
            child.terminate()

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    with LOG.open("ab") as log:
        try:
            while not stopping:
                child = subprocess.Popen(["java", "-jar", str(JAR)], cwd=REPO, env=env,
                                         stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
                write_state(state="running", server_pid=child.pid, restarts=restarts)
                while child.poll() is None and not stopping:
                    time.sleep(1)
                if stopping:
                    break
                restarts += 1
                write_state(state="restarting", server_pid=None, restarts=restarts)
                time.sleep(10)
        finally:
            if child and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            write_state(state="stopped", server_pid=None, restarts=restarts)


def start():
    if alive(state().get("supervisor_pid")):
        print("Control tower supervisor is already running.")
        return 0
    if ready():
        print("Port 8088 already serves a process; inspect it before starting another.")
        return 1
    if not JAR.exists() or not ENV_FILE.exists():
        print("Build control-tower and prepare local .env first.")
        return 1
    ROOT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    for key in ("AUTOHELM_CONTROL_TOKEN", "DART_API_KEY", "KIS_APP_KEY", "KIS_APP_SECRET"):
        env.pop(key, None)
    with (ROOT / "supervisor.log").open("ab") as log:
        process = subprocess.Popen([sys.executable, __file__, "run"], cwd=REPO, env=env,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    for _ in range(50):
        current = state()
        if current.get("supervisor_pid") == process.pid and alive(current.get("server_pid")) and ready():
            print("Control tower ready at http://127.0.0.1:8088/")
            return 0
        if process.poll() is not None:
            print("Control tower failed to start; inspect data/dev/control/runtime/supervisor.log")
            return 1
        time.sleep(0.2)
    print("Control tower startup timed out; inspect runtime logs")
    return 1


def status():
    current = state()
    running = alive(current.get("supervisor_pid")) and alive(current.get("server_pid"))
    healthy = ready()
    print(f"supervisor={'running' if alive(current.get('supervisor_pid')) else 'stopped'} "
          f"server={'running' if running else 'stopped'} http={'ok' if healthy else 'unavailable'} "
          f"restarts={current.get('restarts', 0)}")
    return 0 if running and healthy else 1


def stop():
    pid = state().get("supervisor_pid")
    if alive(pid):
        os.kill(pid, signal.SIGTERM)
        for _ in range(50):
            if not alive(pid):
                break
            time.sleep(0.2)
    print("Control tower stop requested.")
    return 0


if __name__ == "__main__":
    actions = {"run": run, "start": start, "status": status, "stop": stop}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        print("Usage: python3 monitoring/control_service.py {start|status|stop}")
        sys.exit(2)
    result = actions[sys.argv[1]]()
    sys.exit(result if isinstance(result, int) else 0)
