#!/usr/bin/env python3
"""부릉탐험대 시연 영상 녹화: 에뮬레이터에서 앱을 자동으로 조작하며 장면별로 녹화합니다 (docs/submission/persona.md 7장).

    python3 record.py list                  # 장면 목록
    python3 record.py part1                 # 앱 구동·지도·시연 모드 사용법·평일 아침
    python3 record.py part2                 # 기록·도감·내 탐험·추천 경로·토요일(시계를 토요일로)·다음 목표·설정
    python3 record.py s_dex s_exp           # 장면 하나씩 다시

녹화는 에뮬레이터가 호스트에서 하는 `adb emu screenrecord`(2560×1440, 30 fps, 앱 음성 포함)로 합니다. 결과는
tools/demo/out/raw-burung/<장면>.webm (git 제외, 다 찍으면 GitHub Release에 올림). 장면마다 탭 시각을 <장면>.json에 남깁니다.
앞 장면이 남긴 상태에서 다음 장면이 이어지므로 처음에는 part1 → part2 순서로 찍습니다.

준비: persona.md 6장(시연 기록 불러오기, 내비 즐겨찾기, 탐험대 이름, 키즈모드 끔, 실제 시간). 환경변수 FOG_SERIAL로
에뮬레이터를 고릅니다(기본 emulator-5554). 탭 좌표는 Pleos Connect v2.1 화면(2560×1440) 기준이고, 버튼은 되도록
글자로 찾습니다. 장면 길이·탭 시각은 찍어 본 뒤 화면을 보고 맞춥니다.
"""
import json
import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "qa"))
from fogqa.device import Device  # noqa: E402

RAW = os.path.join(ROOT, "tools", "demo", "out", "raw-burung")
SERIAL = os.environ.get("FOG_SERIAL", "emulator-5554")
APP = "com.zobo.burung/.ui.MainActivity"
NAVI = "ai.umos.maps.android.navigation.app/.MainActivity"
d = Device(serial=SERIAL)

# Rail tabs and fixed controls on the 2560x1440 screen.
TAB = {"지도": (913, 154), "도감": (913, 273), "내 탐험": (913, 397), "설정": (913, 518)}
KIDS = (2443, 429)            # 설정 › 키즈모드 스위치
COURSE_ROW = (2460, 265)      # 시연 주행 패널의 코스 펼치기
APP_X = 1700                  # 앱 화면 가운데 (스크롤)
LOG = {}


def adb(*a, wait=False):
    cmd = d._cmd(*map(str, a))
    return subprocess.run(cmd) if wait else subprocess.Popen(cmd)


def tap(xy):
    LOG.setdefault("taps", []).append((round(time.time() - LOG["t0"], 2), xy[0], xy[1]))
    adb("shell", "input", "tap", xy[0], xy[1])


def tap_label(text):
    """A tap on [text], located now (a UI dump takes ~1 s: call it before recording or where a pause is fine)."""
    n = d.find(text, exact=False)
    if n is None:
        print("  화면에 없음:", text, flush=True)
        return
    tap(n.center)


def swipe(y1, y2, ms=700, x=APP_X):
    adb("shell", "input", "swipe", x, y1, x, y2, ms)


def record(name, seconds, script, bitrate=24_000_000):
    """Records [seconds] while [script] (list of (offset s, action)) runs → raw-burung/<name>.webm."""
    os.makedirs(RAW, exist_ok=True)
    out = os.path.join(RAW, name + ".webm")
    if os.path.exists(out):
        os.remove(out)
    d.run("emu", "screenrecord", "start", "--fps", "30", "--bit-rate", str(bitrate), "--time-limit", str(seconds), out)
    LOG.clear()
    LOG["t0"] = t0 = time.time()
    for at, action in script:
        time.sleep(max(0.0, t0 + at - time.time()))
        action()
    time.sleep(max(0.0, t0 + seconds + 2 - time.time()))
    d.run("emu", "screenrecord", "stop")
    time.sleep(2)
    json.dump({"taps": LOG.get("taps", [])}, open(os.path.join(RAW, name + ".json"), "w"))
    print(name, os.path.getsize(out) if os.path.exists(out) else "녹화 파일 없음", flush=True)


def close_cards():
    for _ in range(3):
        if "닫기" not in d.texts():
            return
        d.tap_text("닫기")
        time.sleep(1)


def to_map():
    d.tap(*TAB["지도"])
    time.sleep(2)
    close_cards()


def demo_panel(course, kmh):
    """지도 › 시연 주행 패널을 [course], [kmh]로 맞춰 둠 (녹화 전 준비)."""
    to_map()
    if "시연 코스" not in d.texts():
        d.tap_text("시연 주행")
        time.sleep(2)
    if course not in d.texts():
        d.tap(*COURSE_ROW)
        time.sleep(2)
        for _ in range(6):
            if course in d.texts():
                break
            adb("shell", "input", "swipe", 2250, 1250, 2250, 350, 900, wait=True)
            time.sleep(1.5)
        d.tap_text(course)
        time.sleep(2)
    for _ in range(30):
        speed = next((n for n in d.ui_nodes() if n.text and n.text.endswith(" km/h") and n.text.split()[0].isdigit()), None)
        if speed is None or int(speed.text.split()[0]) == kmh:
            break
        x, y = speed.center
        d.tap(x + 74 if int(speed.text.split()[0]) < kmh else x - 74, y)
        time.sleep(0.4)
    print("  시연 코스", course, kmh, "km/h", flush=True)


def start_xy():
    nodes = d.ui_nodes()   # one dump for both labels (each dump takes ~1 s)
    n = d.find("시연 시작", nodes=nodes) or d.find("다시 달리기", nodes=nodes)   # after a finished run the button reads 다시 달리기
    if n is None:
        sys.exit("시연 시작/다시 달리기 버튼이 화면에 없음")
    return n.center


# ---- 장면 (persona.md 7장의 번호) ----

def s_boot():
    """2 앱 구동: 앱을 닫아 두고 실행 → 로딩 '시동을 걸면 시작되는 나만의 탐험 지도' → 지도."""
    adb("shell", "am", "force-stop", "com.zobo.burung", wait=True)
    time.sleep(2)
    record("s_boot", 12, [(0.8, lambda: adb("shell", "am", "start", "--user", "current", "-n", APP))])


def s_map():
    """3 지도 둘러보기: 레벨·현재 동네, 최근 주행 열고 닫기, 안 가본 동네 카드."""
    to_map()
    trips = d.find("최근 주행").center
    record("s_map", 12, [(1.5, lambda: tap(trips)), (6.0, lambda: adb("shell", "input", "keyevent", "BACK"))])


def s_demo_howto():
    """4 시연 모드 사용법: 시연 주행 → 코스 목록 → 코스 고르기 → 속도 −/+ (출발은 다음 장면)."""
    to_map()
    chip = d.find("시연 주행").center
    record("s_demo_howto", 14, [(1.0, lambda: tap(chip)), (3.0, lambda: tap(COURSE_ROW)),
                                (6.5, lambda: tap_label("평일 등원길: 집 → 유치원")),
                                (9.0, lambda: tap_label("km/h")), (11.0, lambda: None)])


def s_kids():
    """5 키즈모드 ON."""
    to_map()
    record("s_kids", 9, [(0.8, lambda: tap(TAB["설정"])), (3.0, lambda: tap(KIDS)), (6.5, lambda: tap(TAB["지도"]))])


def s_kinder():
    """6–7 등원 주행 → 유치원 도착(P단 + 문 열림) → 결과 카드."""
    demo_panel("평일 등원길: 집 → 유치원", 40)
    xy = start_xy()
    record("s_kinder", 48, [(1.0, lambda: tap(xy))])


def s_office():
    """8–9 출근 → 회사 도착 → 결과 카드(새 칭호 「프로 출근러」)."""
    demo_panel("평일 출근길: 유치원 → 회사", 120)
    xy = start_xy()
    record("s_office", 62, [(1.0, lambda: tap(xy))])


def s_trips():
    """10 최근 주행: 이번 주 요약, 날짜별 목록, 한 건 펼치기(상세·경로)."""
    d.tap_text("시연 중지")
    time.sleep(2)
    to_map()
    trips = d.find("최근 주행").center
    record("s_trips", 13, [(1.0, lambda: tap(trips)), (4.0, lambda: tap_label("→ 회사")),
                           (10.5, lambda: adb("shell", "input", "keyevent", "BACK"))])


def s_dex():
    """11 지역 도감: 전국 → 서울 → 경기 → 전국, 다음 탐험 후보."""
    to_map()
    d.tap(*TAB["도감"])
    time.sleep(3)
    chips = {k: d.find(k, exact=False).center for k in ("서울", "경기", "전국")}
    record("s_dex", 15, [(2.5, lambda: tap(chips["서울"])), (6.5, lambda: tap(chips["경기"])), (10.5, lambda: tap(chips["전국"]))])


def s_exp():
    """12 내 탐험: 칭호, 이번 주 탐험 추천, 칭호 5종, 스탬프."""
    record("s_exp", 14, [(0.5, lambda: tap(TAB["내 탐험"])), (5.0, lambda: swipe(1150, 650)), (9.0, lambda: swipe(1150, 650))])


def s_suggest():
    """13 추천 경로: 지도의 '안 가본 동네 가보기' → '내비 안내' → 내비 경로 → 앱으로 돌아오기."""
    adb("shell", "am", "start", "--user", "10", "-n", NAVI, wait=True)   # 내비를 먼저 띄워 둠
    time.sleep(8)
    adb("shell", "am", "start", "--user", "current", "-n", APP, wait=True)
    time.sleep(4)
    d.tap(*TAB["지도"])
    time.sleep(2)
    go = d.find("내비 안내")
    if go is None:
        print("  '안 가본 동네 가보기' 카드가 없음: 지도에서 잠시 기다리거나 설정 › 안 가본 동네 추천을 켬", flush=True)
        return
    record("s_suggest", 14, [(2.0, lambda: tap(go.center)),
                             (10.0, lambda: adb("shell", "am", "start", "--user", "current", "-n", APP))])


def s_olympic():
    """14–15 토요일 가족 나들이(키즈 음성) → 결과 카드('주말 드라이브': 에뮬레이터 시계가 토요일이어야 함), 끝에 시연 중지."""
    demo_panel("토요일 가족 나들이: 올림픽공원", 70)
    xy = start_xy()
    record("s_olympic", 70, [(1.0, lambda: tap(xy)), (66.0, lambda: tap_label("시연 중지"))])


def s_gangneung():
    """(선택) 강원도 가족 여행(200 km/h, 약 6분 30초) → 결과 카드(「주말 원정대」). 기본 장면 구성에는 없음."""
    demo_panel("강원도 가족 여행: 강릉 경포대 (분기 1회)", 200)
    xy = start_xy()
    record("s_gangneung", 420, [(1.0, lambda: tap(xy))], bitrate=12_000_000)


def s_after():
    """16 다음 목표: 도감(넓어진 지도) → 내 탐험(「주말 원정대」 2/3, '멀리 떠난 날' → 강원도 가족 여행)."""
    d.tap_text("시연 중지")
    time.sleep(2)
    to_map()
    record("s_after", 14, [(0.5, lambda: tap(TAB["도감"])), (6.0, lambda: tap(TAB["내 탐험"])), (10.0, lambda: swipe(1150, 700))])


def s_settings():
    """17 설정: 탐험대·키즈모드, 연결 상태(SDK), 시연 모드, 지도 표시·안내, 데이터 동기화, 기록과 개인정보."""
    to_map()
    record("s_settings", 18, [(0.5, lambda: tap(TAB["설정"])), (5.0, lambda: swipe(1250, 450, 900)),
                              (9.5, lambda: swipe(1250, 450, 900)), (14.0, lambda: swipe(1250, 450, 900))])


PARTS = {
    "part1": [s_boot, s_map, s_demo_howto, s_kids, s_kinder, s_office],
    "part2": [s_trips, s_dex, s_exp, s_suggest, s_olympic, s_after, s_settings],
}

if __name__ == "__main__":
    if not sys.argv[1:] or sys.argv[1] == "list":
        for part, fs in PARTS.items():
            print(part, ":", " ".join(f.__name__ for f in fs))
        sys.exit(0)
    for arg in sys.argv[1:]:
        for f in PARTS.get(arg) or [globals()[arg]]:
            print("▶", f.__name__, (f.__doc__ or "").strip().splitlines()[0], flush=True)
            f()
