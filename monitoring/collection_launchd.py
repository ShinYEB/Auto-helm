"""Install or inspect the current user's collection-window launch agent."""

import os
import plistlib
import subprocess
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
LABEL = "dev.autohelm.collection-window"
PLIST = Path.home() / "Library/LaunchAgents" / f"{LABEL}.plist"
ALWAYS_LABEL = "dev.autohelm.always-on"
ALWAYS_PLIST = Path.home() / "Library/LaunchAgents" / f"{ALWAYS_LABEL}.plist"
CONTROL_LABEL = "dev.autohelm.control-tower"
CONTROL_PLIST = Path.home() / "Library/LaunchAgents" / f"{CONTROL_LABEL}.plist"
RUNTIME = REPO / "data/dev/collection-window"
DOMAIN = f"gui/{os.getuid()}"


def install():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    PLIST.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "Label": LABEL,
        "ProgramArguments": [sys.executable, str(REPO / "monitoring/collection_window.py"), "run"],
        "WorkingDirectory": str(REPO),
        "RunAtLoad": True,
        "KeepAlive": True,
        "StandardOutPath": str(RUNTIME / "launchd.log"),
        "StandardErrorPath": str(RUNTIME / "launchd.log"),
    }
    if PLIST.exists():
        subprocess.run(["launchctl", "bootout", DOMAIN, str(PLIST)], capture_output=True)
    with PLIST.open("wb") as stream:
        plistlib.dump(payload, stream)
    subprocess.run(["launchctl", "bootstrap", DOMAIN, str(PLIST)], check=True)
    always_payload = {
        "Label": ALWAYS_LABEL,
        "ProgramArguments": [sys.executable, str(REPO / "monitoring/ensure_always_on.py")],
        "WorkingDirectory": str(REPO),
        "RunAtLoad": True,
        "StartInterval": 60,
        "StandardOutPath": str(RUNTIME / "always-on.log"),
        "StandardErrorPath": str(RUNTIME / "always-on.log"),
    }
    if ALWAYS_PLIST.exists():
        subprocess.run(["launchctl", "bootout", DOMAIN, str(ALWAYS_PLIST)], capture_output=True)
    with ALWAYS_PLIST.open("wb") as stream:
        plistlib.dump(always_payload, stream)
    subprocess.run(["launchctl", "bootstrap", DOMAIN, str(ALWAYS_PLIST)], check=True)
    control_payload = {
        "Label": CONTROL_LABEL,
        "ProgramArguments": [sys.executable, str(REPO / "monitoring/control_service.py"), "run"],
        "WorkingDirectory": str(REPO),
        "RunAtLoad": True,
        "KeepAlive": True,
        "StandardOutPath": str(RUNTIME / "control-supervisor.log"),
        "StandardErrorPath": str(RUNTIME / "control-supervisor.log"),
    }
    if CONTROL_PLIST.exists():
        subprocess.run(["launchctl", "bootout", DOMAIN, str(CONTROL_PLIST)], capture_output=True)
    else:
        subprocess.run([sys.executable, str(REPO / "monitoring/control_service.py"), "stop"], cwd=REPO, check=True)
    with CONTROL_PLIST.open("wb") as stream:
        plistlib.dump(control_payload, stream)
    subprocess.run(["launchctl", "bootstrap", DOMAIN, str(CONTROL_PLIST)], check=True)
    print(f"Installed {LABEL}, {ALWAYS_LABEL}, and {CONTROL_LABEL}")


def status():
    result = subprocess.run(["launchctl", "print", f"{DOMAIN}/{LABEL}"], capture_output=True, text=True)
    always = subprocess.run(["launchctl", "print", f"{DOMAIN}/{ALWAYS_LABEL}"], capture_output=True, text=True)
    control = subprocess.run(["launchctl", "print", f"{DOMAIN}/{CONTROL_LABEL}"], capture_output=True, text=True)
    print(f"collection: {'loaded' if result.returncode == 0 else 'not loaded'}")
    print(f"always-on: {'loaded' if always.returncode == 0 else 'not loaded'}")
    print(f"control: {'loaded' if control.returncode == 0 else 'not loaded'}")
    return 0 if result.returncode == always.returncode == control.returncode == 0 else 1


def uninstall():
    if PLIST.exists():
        subprocess.run(["launchctl", "bootout", DOMAIN, str(PLIST)], capture_output=True)
        PLIST.unlink()
    if ALWAYS_PLIST.exists():
        subprocess.run(["launchctl", "bootout", DOMAIN, str(ALWAYS_PLIST)], capture_output=True)
        ALWAYS_PLIST.unlink()
    if CONTROL_PLIST.exists():
        subprocess.run(["launchctl", "bootout", DOMAIN, str(CONTROL_PLIST)], capture_output=True)
        CONTROL_PLIST.unlink()
    print(f"Removed {LABEL}, {ALWAYS_LABEL}, and {CONTROL_LABEL}")


if __name__ == "__main__":
    actions = {"install": install, "status": status, "uninstall": uninstall}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        print("Usage: python3 monitoring/collection_launchd.py {install|status|uninstall}")
        sys.exit(2)
    sys.exit(actions[sys.argv[1]]() or 0)
