"""Run the dev event collector in the background and restart it after failures."""

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
ROOT = REPO / "data/dev/events/runtime"
STATE = ROOT / "supervisor.json"
LOG = ROOT / "event-data.log"
ENV_FILE = REPO / ".env"
CONFIG = REPO / "data/dev/event-dev.yml"
JAR = REPO / "event-data/build/libs/event-data-0.1.0-SNAPSHOT.jar"


def alive(pid):
    try:
        os.kill(int(pid), 0)
        return True
    except (TypeError, ValueError, ProcessLookupError, PermissionError):
        return False


def read_state():
    try:
        return json.loads(STATE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def write_state(**fields):
    ROOT.mkdir(parents=True, exist_ok=True)
    temp = STATE.with_suffix(".tmp")
    temp.write_text(json.dumps({"supervisor_pid": os.getpid(), **fields}))
    temp.replace(STATE)


def secret_env():
    if not ENV_FILE.exists():
        raise RuntimeError("Missing local .env file")
    key = next((line.partition("=")[2].strip() for line in ENV_FILE.read_text().splitlines()
                if line.startswith("DART_API_KEY=")), "")
    if not key:
        raise RuntimeError("DART_API_KEY is missing from .env")
    env = os.environ.copy()
    env["DART_API_KEY"] = key
    return env


def run():
    if not JAR.exists() or not CONFIG.exists():
        raise RuntimeError("Build event-data and create data/dev/event-dev.yml first")
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
                child = subprocess.Popen(
                    ["java", "-jar", str(JAR), f"--spring.config.additional-location={CONFIG.as_uri()}"],
                    cwd=REPO, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                )
                write_state(state="running", collector_pid=child.pid, restarts=restarts)
                while child.poll() is None and not stopping:
                    time.sleep(1)
                if stopping:
                    break
                restarts += 1
                write_state(state="restarting", collector_pid=None, restarts=restarts)
                time.sleep(10)
        finally:
            if child and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            write_state(state="stopped", collector_pid=None, restarts=restarts)


def start():
    previous = read_state()
    if alive(previous.get("supervisor_pid")):
        print("Event collector supervisor is already running.")
        return 0
    if not JAR.exists() or not CONFIG.exists() or not ENV_FILE.exists():
        print("Build event-data and prepare .env plus data/dev/event-dev.yml first.")
        return 1
    ROOT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.pop("DART_API_KEY", None)
    with (ROOT / "supervisor.log").open("ab") as log:
        process = subprocess.Popen([sys.executable, __file__, "run"], cwd=REPO, env=env,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    for _ in range(30):
        state = read_state()
        if state.get("supervisor_pid") == process.pid and state.get("state") == "running":
            print(f"Event collector running (pid {state['collector_pid']}).")
            return 0
        if process.poll() is not None:
            print("Event collector failed to start; inspect data/dev/events/runtime/supervisor.log")
            return 1
        time.sleep(0.2)
    print("Event collector startup timed out; inspect supervisor.log")
    return 1


def status():
    state = read_state()
    supervisor = alive(state.get("supervisor_pid"))
    collector = alive(state.get("collector_pid"))
    print(f"supervisor={'running' if supervisor else 'stopped'} "
          f"collector={'running' if collector else 'stopped'} restarts={state.get('restarts', 0)}")
    return 0 if supervisor and collector else 1


def stop():
    state = read_state()
    pid = state.get("supervisor_pid")
    if alive(pid):
        os.kill(pid, signal.SIGTERM)
        for _ in range(50):
            if not alive(pid):
                break
            time.sleep(0.2)
    print("Event collector stop requested.")
    return 0


if __name__ == "__main__":
    actions = {"start": start, "run": run, "status": status, "stop": stop}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        print("Usage: python3 monitoring/event_service.py {start|status|stop}")
        sys.exit(2)
    result = actions[sys.argv[1]]()
    sys.exit(result if isinstance(result, int) else 0)
