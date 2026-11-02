#!/usr/bin/env python3
"""Records the demo video section by section on an emulator, with sound, for tools/demo/build_video.py.

    python3 tools/demo/prepare_demo.py --fresh             # part 1 "첫 시동": first run, app closed
    python3 tools/demo/record_segments.py part1            # -> tools/demo/out/raw/a*.webm
    python3 tools/demo/prepare_demo.py                     # part 2 "두 달 뒤": two months of record in
    python3 tools/demo/record_segments.py part2            # -> tools/demo/out/raw/b*.webm
    python3 tools/demo/record_segments.py b4_dex           # one section again

Uses the emulator's own recorder (`adb emu screenrecord`, host side: 2560x1440, 30 fps, VP9 + the
emulator's audio, so voice lines and interface sounds are in the file) instead of `adb shell
screenrecord`, which runs in the guest and costs frames. Taps go through `input tap`; each tap's time
and position is saved next to the recording (raw/<name>.json) so the edit can draw a tap ring there.
Every control's position is read before a recording starts, never during one (a UI dump stalls the
screen). Each section starts from the state the previous one left, so record in order the
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


TAPS = []      # (seconds into the current recording, x, y)
_t0 = [0.0]


def tap(xy):
    TAPS.append((round(time.time() - _t0[0], 2), xy[0], xy[1]))
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
    _t0[0] = t0
    TAPS.clear()
    events = []
    for at, action in script:
        time.sleep(max(0.0, t0 + at - time.time()))
        events.append(round(time.time() - t0, 2))
        action()
    time.sleep(max(0.0, t0 + seconds + 1.5 - time.time()))
    if not os.path.exists(out) or os.path.getsize(out) < 100_000:
        raise SystemExit(f"{name}: recording missing")
    with open(os.path.join(RAW, name + ".json"), "w") as f:
        json.dump({"seconds": seconds, "events": events, "taps": TAPS}, f)
    print(f"{name}: {seconds}s, {os.path.getsize(out) // 1024} KB")


# ---------------------------------------------------------------- part 1: 첫 시동 (fresh install)

TUTORIAL_NEXT = (1998, 782)   # "다음" / last page's button, same place on every page


def a1_first_launch():
    """First open: loading, the five-page tutorial, then the fogged map with the navigation's recent
    places already cleared."""
    record("a1_first_launch", 26, [
        (0.5, lambda: dev.shell("am", "start", "--user", "current", "-n", "com.zobo.burung/.ui.MainActivity")),
        *[(t, lambda: tap(TUTORIAL_NEXT)) for t in (7.5, 10.5, 13.5, 16.5, 19.5)],
    ])


def a2_first_map():
    """The whole map is still fog: zoom out to see how little is cleared."""
    record("a2_first_map", 11, [(1.5, lambda: tap(ZOOM_OUT)), (4.5, lambda: tap(ZOOM_OUT)), (8.0, lambda: tap(ZOOM_IN))])


def a3_first_drive():
    """The first drive (성수 주말 나들이, 80 km/h): detour card, fog lifting from nothing, voice lines,
    stamps and the passenger mission, arrival and the trip summary."""
    tap(MY_LOCATION)
    time.sleep(1)
    tap(where("시연 주행"))
    time.sleep(1.5)
    faster = where("빠르게")
    for _ in range(2):  # 60 -> 80 km/h
        tap(faster)
        time.sleep(0.4)
    time.sleep(1)
    start = where("시연 시작")
    record("a3_first_drive", 175, [(1.0, lambda: tap(start))])


def a4_first_after():
    """Close the summary and zoom out: one bright path through the fog."""
    close = where("닫기") if dev.find("이번 주행 요약") else None
    record("a4_first_after", 13, [
        *([(0.8, lambda: tap(close))] if close else []),
        (2.5, lambda: tap(ZOOM_OUT)), (5.0, lambda: tap(ZOOM_OUT)), (8.0, lambda: tap(ZOOM_OUT)),
    ])


def a5_first_record():
    """기록 after one drive, then 도감 with the first regions and stamps."""
    record("a5_first_record", 17, [
        (0.8, lambda: tap(TAB["기록"])),
        (6.0, lambda: tap(TAB["도감"])),
        (10.5, lambda: swipe(1200, 450, 700)),
        (13.5, lambda: swipe(1200, 450, 700)),
    ])


# ---------------------------------------------------------------- part 2: 두 달 뒤 (record imported)

def b1_worn():
    """Two months of driving: worn-road colours around home, off and on, then zoomed out."""
    close = where("닫기") if dev.find("안 가본 동네 가보기") else None
    record("b1_worn", 21, [
        *([(0.8, lambda: tap(close))] if close else []),
        (2.0, lambda: tap(ZOOM_IN)),
        (7.5, lambda: tap(WORN)),
        (10.5, lambda: tap(WORN)),
        (14.0, lambda: tap(ZOOM_OUT)),
        (16.5, lambda: tap(ZOOM_OUT)),
    ])


def b2_record():
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
    record("b2_record", 20, [
        (0.8, lambda: tap(TAB["기록"])),
        (7.0, lambda: swipe(1100, 700)),
        (11.0, lambda: swipe(700, 1100)),
        *([(13.0, lambda: tap(first_trip))] if first_trip else []),
    ])


def b3_timelapse():
    tap(TAB["기록"])
    time.sleep(2)
    button = where("개척 타임랩스")
    record("b3_timelapse", 16, [(0.8, lambda: tap(button))])
    close = dev.find("닫기")
    if close:
        dev.tap(*close.center)


def b4_dex():
    """도감: 전국 → 서울특별시 → 중구 (동 cards with milestone badges) → 전국 → challenges and stamps."""
    tap(TAB["도감"])
    time.sleep(1.5)
    if dev.find("전국") is not None:   # the breadcrumb: shown only below the top level
        dev.tap_text("전국")
        time.sleep(1.5)
    seoul = where("서울특별시")
    record("b4_dex", 36, [
        (3.0, lambda: tap(seoul)),
        (8.5, lambda: tap(GU)),
        (15.0, lambda: tap(BACK_HOME)),
        (17.0, lambda: swipe(1200, 500, 900)),
        (21.0, lambda: swipe(1200, 500, 900)),
        (25.0, lambda: swipe(1200, 500, 900)),
        (29.0, lambda: swipe(1200, 500, 900)),
    ])


GU = (1428, 382)          # 중구 card on the 서울특별시 page (second card in the first row)
BACK_HOME = (1093, 154)   # "전국" in the breadcrumb


def b5_share():
    tap(TAB["기록"])
    time.sleep(2)
    button = where("내 안개지도 카드")
    record("b5_share", 9, [(0.8, lambda: tap(button))])
    close = dev.find("닫기")
    if close:
        dev.tap(*close.center)


def b6_settings():
    record("b6_settings", 14, [
        (0.8, lambda: tap(TAB["설정"])),
        (6.0, lambda: swipe(1150, 500, 900)),
        (10.0, lambda: swipe(1150, 500, 900)),
    ])


PART1 = [a1_first_launch, a2_first_map, a3_first_drive, a4_first_after, a5_first_record]
PART2 = [b1_worn, b2_record, b3_timelapse, b4_dex, b5_share, b6_settings]


def main():
    want = set(sys.argv[1:])
    names = {"part1", "part2"} | {s.__name__ for s in PART1 + PART2}
    # No default: part 2 needs prepare_demo.py (the record imported) between the two parts.
    if not want or want - names:
        bad = sorted(want - names)
        sys.exit("usage: record_segments.py part1 | part2 | <section>..." + (f"  (unknown: {', '.join(bad)})" if bad else ""))
    for user in ("0", "current"):
        dev.shell("settings", "put", "--user", user, "system", "show_touches", "0")
    for s in PART1 + PART2:
        part = "part1" if s in PART1 else "part2"
        if part in want or s.__name__ in want:
            s()


if __name__ == "__main__":
    main()
