#!/usr/bin/env python3
"""Records the demo video section by section on an emulator, with sound, for tools/demo/build_video.py.

    python3 tools/demo/prepare_demo.py                     # demo history in, parked at home
    python3 tools/demo/record_segments.py                  # all sections -> tools/demo/out/raw/*.webm
    python3 tools/demo/record_segments.py s05_drive        # one section again

Uses the emulator's own recorder (`adb emu screenrecord`, host side: 2560x1440, 30 fps, VP9 + the
emulator's audio, so voice lines and interface sounds are in the file) instead of `adb shell
screenrecord`, which runs in the guest and costs frames. Taps go through `input tap` with the touch
indicator on; every control's position is read before a recording starts, never during one (a UI dump
stalls the screen). Each section starts from the state the previous one left, so record in order the
first time; later, any single section can be redone after prepare_demo.py (and the sections before it,
if it depends on them).
"""
import json
import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "qa"))
from fogqa.device import Device  # noqa: E402

RAW = os.path.join(ROOT, "tools", "demo", "out", "raw")
SERIAL = os.environ.get("FOG_SERIAL", "emulator-5556")
dev = Device(serial=SERIAL)

# Fixed controls on the 2560x1440 Pleos screen (the rail tabs and the map's right-hand buttons).
TAB = {"지도": (913, 152), "기록": (913, 273), "도감": (913, 394), "설정": (913, 515)}
WORN = (2467, 920)
ZOOM_IN = (2467, 1024)
ZOOM_OUT = (2467, 1128)
MY_LOCATION = (2467, 1232)
APP_X = 1700  # middle of the app area, for scrolling


def tap(xy):
    subprocess.Popen(dev._cmd("shell", "input", "tap", str(xy[0]), str(xy[1])))


def swipe(y1, y2, ms=500, x=APP_X):
    subprocess.Popen(dev._cmd("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), str(ms)))


def where(text, exact=True):
    """Centre of [text] on screen now (read before recording)."""
    n = dev.find(text, exact=exact)
    if n is None:
        raise SystemExit(f"'{text}' not on screen")
    return n.center


def record(name, seconds, script):
    """Records [seconds] while [script] (list of (offset s, action)) runs; writes raw/<name>.webm."""
    os.makedirs(RAW, exist_ok=True)
    out = os.path.join(RAW, name + ".webm")
    if os.path.exists(out):
        os.remove(out)
    dev.run("emu", "screenrecord", "start", "--fps", "30", "--bit-rate", "24000000",
            "--time-limit", str(seconds), out)
    t0 = time.time()
    events = []
    for at, action in script:
        time.sleep(max(0.0, t0 + at - time.time()))
        events.append(round(time.time() - t0, 2))
        action()
    time.sleep(max(0.0, t0 + seconds + 1.5 - time.time()))
    if not os.path.exists(out) or os.path.getsize(out) < 100_000:
        raise SystemExit(f"{name}: recording missing")
    with open(os.path.join(RAW, name + ".json"), "w") as f:
        json.dump({"seconds": seconds, "events": events}, f)
    print(f"{name}: {seconds}s, {os.path.getsize(out) // 1024} KB")


# ---------------------------------------------------------------- sections

def s02_launch():
    """App start from cold: loading screen, then the map at home with two months of worn roads."""
    dev.shell("am", "force-stop", "--user", "current", "com.zobo.fogdrive")
    time.sleep(1)
    record("s02_launch", 12, [
        (0.5, lambda: dev.shell("am", "start", "--user", "current", "-n", "com.zobo.fogdrive/.ui.MainActivity",
                                "--ez", "skip_onboarding", "true")),
    ])


def s03_worn():
    """Close the suggestion card, zoom in on the home roads, worn colours off and on, zoom out."""
    close = where("닫기") if dev.find("안 가본 동네 가보기") else None
    record("s03_worn", 20, [
        *([(0.8, lambda: tap(close))] if close else []),
        (2.0, lambda: tap(ZOOM_IN)),
        (7.0, lambda: tap(WORN)),          # off: one colour
        (10.0, lambda: tap(WORN)),         # on again
        (14.0, lambda: tap(ZOOM_OUT)),
        (16.5, lambda: tap(ZOOM_OUT)),
    ])


def s04_drive():
    """Demo course 성수 주말 나들이 at 100 km/h: detour card, worn home roads, new fog lifting,
    turn guidance, speed limit, window/rain stamps, passenger mission, arrival and trip summary."""
    tap(MY_LOCATION)
    time.sleep(1)
    tap(where("시연 주행"))
    time.sleep(1.5)
    faster = where("빠르게")
    for _ in range(4):  # 60 -> 100 km/h
        tap(faster)
        time.sleep(0.4)
    time.sleep(1)
    start = where("시연 시작")
    record("s04_drive", 150, [(1.0, lambda: tap(start))])


def s05_overview():
    """After arrival: close the summary, zoom out to the whole day's drive and the two fog levels."""
    close = where("닫기") if dev.find("이번 주행 요약") else None
    record("s05_overview", 14, [
        *([(0.8, lambda: tap(close))] if close else []),
        (2.5, lambda: tap(ZOOM_OUT)),
        (5.0, lambda: tap(ZOOM_OUT)),
        (8.0, lambda: tap(ZOOM_OUT)),
    ])


def s06_record():
    """기록: totals, this week / this month reports, the trip list; a trip shown on the map."""
    tap(TAB["기록"])
    time.sleep(2)
    first_trip = None
    for n in dev.ui_nodes():
        if "월" in n.text and "일" in n.text and ":" in n.text:
            first_trip = n.center
            break
    tap(TAB["지도"])
    time.sleep(1.5)
    record("s06_record", 20, [
        (0.8, lambda: tap(TAB["기록"])),
        (7.0, lambda: swipe(1100, 700)),
        (11.0, lambda: swipe(700, 1100)),
        *([(13.0, lambda: tap(first_trip))] if first_trip else []),
    ])


def s07_timelapse():
    tap(TAB["기록"])
    time.sleep(2)
    button = where("개척 타임랩스")
    record("s07_timelapse", 16, [(0.8, lambda: tap(button))])
    close = dev.find("닫기")
    if close:
        dev.tap(*close.center)


def s08_dex():
    """도감: 전국 → 서울특별시 → 광진구 (동 cards with milestone badges); challenges; stamps."""
    tap(TAB["도감"])
    time.sleep(2)
    seoul = where("서울특별시")
    record("s08_dex", 26, [
        (0.5, lambda: None),
        (4.0, lambda: tap(seoul)),
    ])


def s09_dex_district():
    """Into a 구 whose 동 cards carry milestone badges, back to 전국, then challenges and stamps."""
    tap(TAB["도감"])
    time.sleep(1.5)
    if dev.find("전국") is not None and dev.find("서울특별시") is None:
        dev.tap_text("전국")
        time.sleep(1.5)
    if dev.find("중구") is None:
        dev.tap_text("서울특별시")
        time.sleep(1.5)
    gu = where("중구")
    home = where("전국")
    record("s09_dex_district", 24, [
        (0.8, lambda: tap(gu)),
        (6.5, lambda: tap(home)),
        (8.5, lambda: swipe(1200, 500, 900)),
        (12.5, lambda: swipe(1200, 500, 900)),
        (16.5, lambda: swipe(1200, 500, 900)),
        (20.0, lambda: swipe(1200, 500, 900)),
    ])


def s10_share():
    tap(TAB["기록"])
    time.sleep(2)
    button = where("내 안개지도 카드")
    record("s10_share", 9, [(0.8, lambda: tap(button))])
    close = dev.find("닫기")
    if close:
        dev.tap(*close.center)


def s11_settings():
    record("s11_settings", 14, [
        (0.8, lambda: tap(TAB["설정"])),
        (6.0, lambda: swipe(1150, 500, 900)),
        (10.0, lambda: swipe(1150, 500, 900)),
    ])


SECTIONS = [s02_launch, s03_worn, s04_drive, s05_overview, s06_record, s07_timelapse, s08_dex,
            s09_dex_district, s10_share, s11_settings]


def main():
    want = set(sys.argv[1:])
    for user in ("0", "current"):
        dev.shell("settings", "put", "--user", user, "system", "show_touches", "1")
    for s in SECTIONS:
        if not want or s.__name__ in want:
            s()


if __name__ == "__main__":
    main()
