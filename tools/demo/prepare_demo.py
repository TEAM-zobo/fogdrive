#!/usr/bin/env python3
"""Puts an emulator into the demo starting state: app reset, the demo persona's record imported, car parked at home.

    python3 tools/demo/persona/make_persona_backup.py      # builds tools/demo/out/persona-backup.json (dated today)
    python3 tools/demo/prepare_demo.py [--serial emulator-5556] [--apk app/build/outputs/apk/debug/app-debug.apk]
    python3 tools/demo/prepare_demo.py --fresh             # a first-run app, not opened yet

Steps: (install) → Virtual Console reset (P, doors shut, no passengers, wipers/lights off) → app data
cleared and permissions granted → location: the persona's home (위례) → record copied into the
app's files folder → app opened and 설정 → 불러오기 tapped → back on the map. 집, 회사 and the
즐겨찾기 come from the navigation (PLEOS NaviHelper): save them there once (docs/submission/persona.md).
Uses the QA tool (qa/fogqa) for adb and the Virtual Console.
"""
import argparse
import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "qa"))
from fogqa.device import PACKAGE, Device  # noqa: E402
from fogqa.vconsole import VConsole  # noqa: E402

BACKUP = os.path.join(ROOT, "tools", "demo", "out", "persona-backup.json")
HOME = tuple(__import__("json").load(open(os.path.join(ROOT, "tools", "demo", "persona", "routes.json"), encoding="utf-8"))["places"]["home"])
X_CENTER = 1700  # middle of the app area on the 2560-px screen, for scrolling


def scroll_to(dev, text, swipes=12):
    for _ in range(swipes):
        if dev.find(text) is not None:
            return True
        dev.swipe(X_CENTER, 1150, X_CENTER, 550, 400)
        time.sleep(0.7)
    return dev.find(text) is not None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--serial", default="emulator-5556")
    p.add_argument("--vc-port", type=int, default=None, help="Virtual Console local port (5554: 8088, 5556: 18088)")
    p.add_argument("--apk", help="install this APK first")
    p.add_argument("--fresh", action="store_true", help="first run: no record, app left closed (tutorial on next open)")
    a = p.parse_args()
    if not a.fresh and not os.path.exists(BACKUP):
        sys.exit("demo record missing: run python3 tools/demo/persona/make_persona_backup.py first")

    dev = Device(serial=a.serial)
    port = a.vc_port or (8088 if a.serial == "emulator-5554" else 18088)
    dev.forward(port, 8088)
    if a.apk:
        print("install", a.apk, dev.install(a.apk).strip()[-40:])

    vc = VConsole(base=f"http://127.0.0.1:{port}")
    vc.start()
    try:
        vc.reset()
        dev.app_stop()
        dev.app_reset()
        dev.geo(*HOME)
        if a.fresh:
            print("ready: first run, parked at home (위례), app closed")
            return
        user = dev.shell("am", "get-current-user").strip()
        # The driver profile's external app folder is closed to adb; run-as writes the internal one,
        # which Import also reads (FogRuntime.importCandidates).
        with open(BACKUP, "rb") as f:
            # One string: adb escapes separate arguments, which would break the quoted sh -c script.
            cmd = dev._cmd("exec-in", f"run-as {PACKAGE} --user {user} sh -c 'mkdir -p files; cat > files/fogdrive-backup.json'")
            r = subprocess.run(cmd, stdin=f, capture_output=True, timeout=120)
        # exec-in returns before the device side has finished writing; wait for the whole file.
        for _ in range(30):
            size = dev.shell("run-as", PACKAGE, "--user", user, "stat", "-c", "%s", "files/fogdrive-backup.json").strip()
            if size == str(os.path.getsize(BACKUP)):
                break
            time.sleep(0.5)
        if r.returncode != 0 or size != str(os.path.getsize(BACKUP)):
            sys.exit(f"copy failed ({size} bytes on device): {r.stderr.decode(errors='ignore')}")
        print("demo history copied", os.path.getsize(BACKUP) // 1024, "KB")

        dev.app_start(tutorial=False)
        dev.tap_text("설정")
        time.sleep(1.5)
        if not scroll_to(dev, "불러오기"):
            sys.exit("'불러오기' not found in Settings")
        dev.tap_text("불러오기")
        # Import replaces the current record, so the app asks first.
        if dev.wait_text("지금 기록을 백업으로 바꾸기", timeout=5):
            dev.tap_text("지금 기록을 백업으로 바꾸기")
        if not dev.wait_text("불러왔어요", timeout=60):
            sys.exit("import did not finish: " + " / ".join(t for t in dev.texts() if "불러" in t))
        print(next(t for t in dev.texts() if "불러왔어요" in t))
        time.sleep(2)
        dev.tap_text("지도")
        time.sleep(2)
        print("ready: parked at home (위례), persona record loaded, 지도 tab open")
    finally:
        vc.stop()


if __name__ == "__main__":
    main()
