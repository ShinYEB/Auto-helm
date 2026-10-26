"""Run the read-only monitoring stack directly on macOS when Docker is unavailable."""

import json
import os
import secrets
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
ROOT = REPO / "data/monitoring/native"
STATE = ROOT / "processes.json"
GRAFANA_HOME = Path("/opt/homebrew/opt/grafana/share/grafana")
GRAFANA_CONFIG = Path("/opt/homebrew/etc/grafana/grafana.ini")


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except (ProcessLookupError, PermissionError):
        return False


def config_files():
    ROOT.mkdir(parents=True, exist_ok=True)
    (ROOT / "prometheus").mkdir(exist_ok=True)
    (ROOT / "grafana/provisioning/datasources").mkdir(parents=True, exist_ok=True)
    (ROOT / "grafana/provisioning/dashboards").mkdir(parents=True, exist_ok=True)
    (ROOT / "grafana/data").mkdir(parents=True, exist_ok=True)
    (ROOT / "grafana/logs").mkdir(parents=True, exist_ok=True)
    (ROOT / "prometheus.yml").write_text('''global:\n  scrape_interval: 15s\n  evaluation_interval: 15s\nscrape_configs:\n  - job_name: autohelm-market\n    static_configs:\n      - targets: ["127.0.0.1:9808"]\n  - job_name: prometheus\n    static_configs:\n      - targets: ["127.0.0.1:9090"]\n''')
    (ROOT / "grafana/provisioning/datasources/prometheus.yml").write_text('''apiVersion: 1\ndatasources:\n  - name: Prometheus\n    uid: autohelm-prometheus\n    type: prometheus\n    access: proxy\n    url: http://127.0.0.1:9090\n    isDefault: true\n    editable: false\n''')
    (ROOT / "grafana/provisioning/dashboards/provider.yml").write_text(f'''apiVersion: 1\nproviders:\n  - name: Auto-helm\n    orgId: 1\n    folder: Auto-helm\n    type: file\n    disableDeletion: true\n    updateIntervalSeconds: 30\n    allowUiUpdates: false\n    options:\n      path: {REPO / "monitoring/grafana/dashboards"}\n''')
    password_file = ROOT / "grafana-admin-password"
    if not password_file.exists():
        password_file.write_text(secrets.token_urlsafe(24))
        password_file.chmod(0o600)
    return password_file.read_text().strip()


def launch(name, command, env):
    log = (ROOT / f"{name}.log").open("ab")
    process = subprocess.Popen(command, cwd=REPO, env=env, stdin=subprocess.DEVNULL,
                               stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    log.close()
    return process.pid


def check(url):
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200
    except Exception:
        return False


def start():
    if STATE.exists():
        previous = json.loads(STATE.read_text())
        if any(alive(pid) for pid in previous.values()):
            print("Monitoring processes already exist; use status or stop first.")
            return 1
    if not GRAFANA_HOME.exists() or not Path("/opt/homebrew/bin/prometheus").exists():
        print("Install prerequisites with: brew install prometheus grafana")
        return 1
    for port in (9808, 9090, 3000):
        if check(f"http://127.0.0.1:{port}/"):
            print(f"Port {port} is already in use.")
            return 1

    admin_password = config_files()
    base_env = os.environ.copy()
    base_env.pop("KIS_APP_KEY", None)
    base_env.pop("KIS_APP_SECRET", None)
    base_env.pop("DART_API_KEY", None)
    exporter_env = {**base_env, "AUTOHELM_DATA_ROOT": str(REPO / "data/dev"),
                    "AUTOHELM_EXPORTER_HOST": "127.0.0.1"}
    grafana_env = {**base_env, "GF_PATHS_PROVISIONING": str(ROOT / "grafana/provisioning"),
                   "GF_PATHS_DATA": str(ROOT / "grafana/data"), "GF_PATHS_LOGS": str(ROOT / "grafana/logs"),
                   "GF_SERVER_HTTP_ADDR": "127.0.0.1", "GF_SERVER_HTTP_PORT": "3000",
                   "GF_AUTH_ANONYMOUS_ENABLED": "true", "GF_AUTH_ANONYMOUS_ORG_ROLE": "Viewer",
                   "GF_AUTH_DISABLE_LOGIN_FORM": "true", "GF_USERS_ALLOW_SIGN_UP": "false",
                   "GF_SECURITY_ADMIN_PASSWORD": admin_password}
    pids = {}
    try:
        pids["exporter"] = launch("exporter", ["python3", str(REPO / "monitoring/exporter.py")], exporter_env)
        pids["prometheus"] = launch("prometheus", ["/opt/homebrew/bin/prometheus",
            f"--config.file={ROOT / 'prometheus.yml'}", f"--storage.tsdb.path={ROOT / 'prometheus'}",
            "--web.listen-address=127.0.0.1:9090", "--storage.tsdb.retention.time=7d"], base_env)
        pids["grafana"] = launch("grafana", ["/opt/homebrew/bin/grafana", "server",
            "--homepath", str(GRAFANA_HOME), "--config", str(GRAFANA_CONFIG), "--packaging=brew"], grafana_env)
        STATE.write_text(json.dumps(pids, indent=2))
        for _ in range(30):
            if all(check(url) for url in ("http://127.0.0.1:9808/health", "http://127.0.0.1:9090/-/ready", "http://127.0.0.1:3000/api/health")):
                print("Monitoring ready: http://127.0.0.1:3000/d/autohelm-market-day/auto-helm-market-data")
                return 0
            time.sleep(1)
        print("Monitoring startup timed out; inspect data/monitoring/native/*.log")
        return 1
    except Exception:
        for pid in pids.values():
            if alive(pid):
                os.kill(pid, signal.SIGTERM)
        raise


def status():
    if not STATE.exists():
        print("Monitoring has not been started.")
        return 1
    pids = json.loads(STATE.read_text())
    for name, pid in pids.items():
        print(f"{name}: {'running' if alive(pid) else 'stopped'} (pid {pid})")
    for name, url in (("exporter", "http://127.0.0.1:9808/health"),
                      ("prometheus", "http://127.0.0.1:9090/-/ready"),
                      ("grafana", "http://127.0.0.1:3000/api/health")):
        print(f"{name} HTTP: {'ok' if check(url) else 'unavailable'}")
    return 0


def stop():
    if not STATE.exists():
        return 0
    pids = json.loads(STATE.read_text())
    for pid in pids.values():
        if alive(pid):
            os.kill(pid, signal.SIGTERM)
    STATE.unlink()
    print("Monitoring stopped.")
    return 0


if __name__ == "__main__":
    actions = {"start": start, "status": status, "stop": stop}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        print("Usage: python3 monitoring/native.py {start|status|stop}")
        sys.exit(2)
    sys.exit(actions[sys.argv[1]]())
