"""Feature test cases. Each case carries its documentation (steps/expected) and its automation.

Every case starts from a clean app (data wiped, permissions granted) and a reset vehicle
(parked, doors closed, wipers/lights off, no passengers) so results do not depend on order.
"""
import re
import time
from dataclasses import dataclass, field

from .device import demo_route

ROUTE = demo_route()
START = ROUTE[0]
# A second area (Yeouido, ~10 km from the demo route) for "parked moves must not reveal" checks.
FAR = [(37.5219, 126.9245), (37.5245, 126.9270), (37.5270, 126.9300)]
# Center-display region occupied by the app (device pixels, 2560x1440), excluding status/dock bars.
APP_AREA = (1000, 200, 2500, 1250)


class CaseFailed(AssertionError):
    pass


@dataclass
class Case:
    id: str
    feature: str
    title: str
    pre: str
    steps: list
    expected: list
    fn: object = field(repr=False, default=None)


CASES = []


def case(id, feature, title, pre, steps, expected):
    def wrap(fn):
        CASES.append(Case(id, feature, title, pre, steps, expected, fn))
        return fn
    return wrap


# ---------------------------------------------------------------- helpers used by the cases

def fresh(ctx):
    ctx.vc.reset()
    ctx.dev.app_stop()
    ctx.dev.app_reset()
    ctx.dev.geo(*START)
    ctx.dev.app_start()
    # Recording service connects SDKs and loads the explored set.
    ctx.dev.tap_text("설정")
    if not ctx.dev.wait_text("실행 중", timeout=10, exact=True):
        raise CaseFailed("기록 서비스가 시작되지 않음")
    time.sleep(2)
    ctx.dev.tap_text("지도")


def value_after(texts, label):
    """StatTile/StatusLine render label then value as consecutive text nodes."""
    for i, t in enumerate(texts):
        if t == label and i + 1 < len(texts):
            return texts[i + 1]
    return None


def area_m2(s):
    """'0.63 km²' (older builds also showed '8,400 m²') -> square metres."""
    m = re.search(r"([\d.,]+)\s*(k?m)²", s or "")
    if not m:
        return None
    v = float(m.group(1).replace(",", ""))
    return v * 1_000_000 if m.group(2) == "km" else v


def number(s):
    m = re.search(r"[\d.]+", s or "")
    return float(m.group()) if m else None


def read_record(ctx):
    ctx.dev.tap_text("기록")
    time.sleep(1.2)
    t = ctx.dev.texts()
    return {
        "area": area_m2(value_after(t, "밝힌 면적")),
        "landings": number(value_after(t, "하차 지점")),
        "new_ratio": number(value_after(t, "새 길 비율")),
        "trips": sum(1 for x in t if re.match(r"\d+월 \d+일 \d\d:\d\d", x)),
    }


def tap_scrolling(ctx, text, swipes=8):
    """Taps [text] on a scrolled screen (Settings, dex): swipes up until it is on screen."""
    x = (APP_AREA[0] + APP_AREA[2]) // 2
    for _ in range(swipes):
        if ctx.dev.find(text) is not None:
            ctx.dev.tap_text(text)
            return
        ctx.dev.swipe(x, APP_AREA[3] - 100, x, APP_AREA[1] + 100, 400)
        time.sleep(0.8)
    raise CaseFailed(f"'{text}'을(를) 찾지 못함")


STAMP_NAMES = ("빗속 개척", "야간 개척", "가족 탐험", "충전소 깃발", "바람길 개척", "함께한 미션")


def read_stamps(ctx):
    """Stamp counts from the stamp tab. The stamps sit below the challenges, and off-screen nodes
    are not in the UI dump, so the tab is scrolled down until all were read."""
    ctx.dev.tap_text("스탬프")
    time.sleep(1.2)
    out = {}
    for _ in range(8):
        t = ctx.dev.texts()
        for name in STAMP_NAMES:
            if name in t and name not in out:
                i = t.index(name)
                if i + 2 < len(t):
                    out[name] = 0 if "아직" in t[i + 2] else int(number(t[i + 2]) or 0)
        if len(out) == len(STAMP_NAMES):
            break
        x = (APP_AREA[0] + APP_AREA[2]) // 2
        ctx.dev.swipe(x, APP_AREA[3] - 100, x, APP_AREA[1] + 100, 400)
        time.sleep(0.8)
    return out


def read_settings(ctx):
    ctx.dev.tap_text("설정")
    time.sleep(1.2)
    t = ctx.dev.texts()
    keys = ["기록 서비스", "위치 소스", "Pleos Vehicle SDK", "AAOS 차량 속성(기어)", "Pleos NaviHelper", "차량 상태"]
    return {k: value_after(t, k) for k in keys}


def drive(ctx, points, delay=0.7):
    for lat, lng in points:
        ctx.dev.geo(lat, lng)
        time.sleep(delay)
    time.sleep(1.5)


def park(ctx):
    """P단으로 바꾸고 앱 화면이 돌아올 때까지 기다린다.
    This emulator blocks non-distraction-optimized apps whenever the gear is not P (even at 0 km/h),
    so every screen read happens parked."""
    ctx.vc.gear("Park")
    if not ctx.dev.wait_text("지도", timeout=10, exact=True):
        raise CaseFailed("P단 전환 후 앱 화면이 돌아오지 않음")


# ---------------------------------------------------------------- cases

@case("TC-01", "연결", "앱 실행 시 기록 서비스와 Pleos SDK가 모두 연결된다",
      "앱 데이터 초기화, 권한 허용",
      ["앱 실행", "설정 탭 → 연결 상태 확인"],
      ["기록 서비스 실행 중", "위치 소스 Pleos FusedLocation", "Vehicle SDK·AAOS 기어·NaviHelper 연결됨, 기어 PARK"])
def tc_connect(ctx):
    fresh(ctx)
    s = read_settings(ctx)
    ctx.shot("settings")
    ctx.log(f"연결 상태: {s}")
    ctx.check(s["기록 서비스"] == "실행 중", "기록 서비스 실행 중")
    ctx.check(s["위치 소스"] == "Pleos FusedLocation", f"위치 소스 = {s['위치 소스']}")
    ctx.check(s["Pleos Vehicle SDK"] == "연결됨", "Vehicle SDK 연결")
    ctx.check((s["AAOS 차량 속성(기어)"] or "").endswith("PARK"), f"기어 = {s['AAOS 차량 속성(기어)']}")
    ctx.check(s["Pleos NaviHelper"] == "연결됨", "NaviHelper 연결")


@case("TC-02", "첫 실행 지도 채우기", "첫 실행 때 내비 최근 목적지·즐겨찾기 주변 안개가 걷힌다",
      "앱 데이터 초기화, 에뮬레이터 내비에 최근 목적지/즐겨찾기 존재",
      ["앱 첫 실행", "지도 헤더 메시지와 기록 탭 밝힌 면적 확인"],
      ["'내비 최근 목적지·즐겨찾기 N곳 주변을 밝혔어요' 표시", "밝힌 면적 > 0, 주행 기록 0건"])
def tc_seed(ctx):
    fresh(ctx)
    ctx.check(ctx.dev.wait_text("주변을 밝혔어요", timeout=10), "시드 완료 메시지 표시")
    ctx.shot("map")
    r = read_record(ctx)
    ctx.log(f"기록: {r}")
    ctx.check((r["area"] or 0) > 0, f"밝힌 면적 {(r['area'] or 0) / 1e6:.4f} km² > 0")
    ctx.check(r["trips"] == 0, "주행 기록 없음")


@case("TC-03", "자동 안개 걷기", "D단으로 주행하면 지나간 길의 안개가 걷히고 주행 기록이 생긴다",
      "앱 초기화, 차량 P단",
      ["기어 D", "실제 도로 경로 25개 지점 위치 주입", "기록 탭 확인"],
      ["밝힌 면적 증가(+0.04 km² 이상, 약 50칸)", "주행 기록 1건", "새 길 비율 90% 이상"])
def tc_drive_reveal(ctx):
    fresh(ctx)
    before = read_record(ctx)
    ctx.dev.tap_text("지도")
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:25])
    park(ctx)
    ctx.shot("map_after_drive")
    after = read_record(ctx)
    ctx.log(f"전 {before} → 후 {after}")
    ctx.check(after["area"] - before["area"] >= 40_000, f"밝힌 면적 +{(after['area'] - before['area']) / 1e6:.3f} km²")
    ctx.check(after["trips"] == 1, f"주행 기록 {after['trips']}건")
    ctx.check((after["new_ratio"] or 0) >= 90, f"새 길 비율 {after['new_ratio']}%")


@case("TC-04", "자동 안개 걷기", "P단에서 위치가 바뀌어도 안개가 걷히지 않는다",
      "앱 초기화, 차량 P단",
      ["기어 P 유지", "다른 지역(여의도)으로 위치 3회 주입", "기록 탭 확인"],
      ["밝힌 면적 변화 없음", "주행 기록 0건"])
def tc_parked_no_reveal(ctx):
    fresh(ctx)
    before = read_record(ctx)
    drive(ctx, FAR, delay=1.0)
    after = read_record(ctx)
    ctx.log(f"전 {before} → 후 {after}")
    ctx.check(after["area"] == before["area"], "밝힌 면적 변화 없음")
    ctx.check(after["trips"] == 0, "주행 기록 없음")


@case("TC-05", "하차 지점", "주행 후 P단에서 도어를 열면 하차 지점이 기록된다",
      "앱 초기화",
      ["기어 D로 15개 지점 주행", "기어 P", "운전석 도어 열기"],
      ["'하차 지점을 기록했어요' 표시", "하차 지점 1곳", "밝힌 면적 추가 증가(7×7칸 공개)"])
def tc_landing(ctx):
    fresh(ctx)
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:15])
    park(ctx)
    mid = read_record(ctx)
    ctx.dev.tap_text("지도")
    ctx.vc.door("front_left", True)
    ctx.check(ctx.dev.wait_text("하차 지점을 기록했어요", timeout=8), "하차 메시지 표시")
    ctx.shot("landing")
    ctx.vc.door("front_left", False)
    after = read_record(ctx)
    ctx.log(f"주행 후 {mid} → 하차 후 {after}")
    ctx.check(after["landings"] == 1, f"하차 지점 {after['landings']}곳")
    ctx.check(after["area"] > mid["area"], "하차로 면적 추가 공개")


@case("TC-06", "하차 지점", "주행 중(D단) 도어가 열려도 하차로 기록하지 않는다",
      "앱 초기화",
      ["기어 D로 10개 지점 주행", "D단 상태에서 도어 열기"],
      ["하차 지점 0곳"])
def tc_no_landing_while_driving(ctx):
    fresh(ctx)
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:10])
    ctx.vc.door("front_left", True)
    time.sleep(3)
    ctx.vc.door("front_left", False)
    park(ctx)
    r = read_record(ctx)
    ctx.log(f"기록: {r}")
    ctx.check(r["landings"] == 0, f"하차 지점 {r['landings']}곳")


def _stamp_case(ctx, setup, name):
    fresh(ctx)
    setup()
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:12])
    park(ctx)
    stamps = read_stamps(ctx)
    ctx.shot("dex")
    ctx.log(f"스탬프: {stamps}")
    ctx.check(stamps.get(name, 0) >= 1, f"{name} 획득")
    others = {k: v for k, v in stamps.items() if k != name and v}
    ctx.check(not others, f"다른 스탬프 없음 {others}")


@case("TC-07", "상황 스탬프", "와이퍼를 켜고 새 길을 달리면 '빗속 개척'을 얻는다",
      "앱 초기화",
      ["앞 와이퍼 Low", "기어 D로 12개 지점 주행", "스탬프 탭 확인"],
      ["빗속 개척 1회 획득", "다른 스탬프 없음"])
def tc_stamp_rain(ctx):
    _stamp_case(ctx, lambda: ctx.vc.wiper("Low"), "빗속 개척")


@case("TC-08", "상황 스탬프", "전조등(로우빔)을 켜고 새 길을 달리면 '야간 개척'을 얻는다",
      "앱 초기화",
      ["헤드램프 LowBeam", "기어 D로 12개 지점 주행", "스탬프 탭 확인"],
      ["야간 개척 1회 획득", "다른 스탬프 없음"])
def tc_stamp_night(ctx):
    _stamp_case(ctx, lambda: ctx.vc.headlamp("LowBeam"), "야간 개척")


@case("TC-09", "상황 스탬프", "뒷좌석 동승자와 새 길을 달리면 '가족 탐험'을 얻는다",
      "앱 초기화",
      ["뒷좌석 왼쪽 탑승", "기어 D로 12개 지점 주행", "스탬프 탭 확인"],
      ["가족 탐험 1회 획득", "다른 스탬프 없음"])
def tc_stamp_family(ctx):
    _stamp_case(ctx, lambda: ctx.vc.seat("rear_left", True), "가족 탐험")


@case("TC-10", "상황 스탬프", "정차 중 배터리가 2%p 이상 오르면 '충전소 깃발'을 얻는다",
      "앱 초기화, P단",
      ["배터리 40%로 설정", "배터리 43%로 설정", "스탬프 탭 확인"],
      ["충전소 깃발 1회 획득 (정차 직후 값보다 낮아졌다 오른 경우도 인정)"])
def tc_stamp_charge(ctx):
    fresh(ctx)
    ctx.vc.soc(40)
    time.sleep(2)
    ctx.vc.soc(43)
    time.sleep(2)
    stamps = read_stamps(ctx)
    ctx.shot("dex")
    ctx.log(f"스탬프: {stamps}")
    ctx.check(stamps.get("충전소 깃발", 0) >= 1, "충전소 깃발 획득")


@case("TC-11", "주행 모드", "주행 중에는 주행 모드(지도·현재 위치·짧은 숫자)만 보이고, 정차하면 전체 화면으로 돌아온다",
      "앱 실행 중 (distraction-optimized 액티비티)",
      ["기어 D, 차속 40km/h", "위치 10개 지점 주입", "화면 확인", "차속 0, 기어 P"],
      ["'주행 모드' 표시, 기록·도감·설정 탭 숨김", "시스템 차단 화면 없음(앱 지도가 계속 보임)", "정차 후 탭 복귀"])
def tc_driving_mode(ctx):
    fresh(ctx)
    ctx.vc.gear("Drive")
    ctx.vc.speed(40)
    try:
        ctx.check(ctx.dev.wait_text("주행 모드", timeout=10, exact=True), "주행 모드 표시")
        drive(ctx, ROUTE[:10])
        texts = ctx.dev.texts()
        hidden = [t for t in ("기록", "도감", "스탬프", "설정") if t in texts]
        ctx.shot("driving_mode")
        ctx.check(not hidden, f"주행 중 탭 숨김 {hidden}")
        # The system block screen is flat light gray; the app's dark map means we are not blocked.
        mean, spread = ctx.dev.region_brightness(*APP_AREA)
        ctx.log(f"주행 중 앱 영역 밝기 {mean:.0f} (편차 {spread:.0f})")
        ctx.check(not (mean > 180 and spread < 25), "시스템 차단 화면 없음")
    finally:
        ctx.vc.speed(0)
        ctx.vc.gear("Park")
    ctx.check(ctx.dev.wait_text("기록", timeout=10, exact=True), "정차 후 탭 복귀")
    ctx.check(ctx.dev.find("주행 모드") is None, "정차 후 주행 모드 해제")


@case("TC-12", "탐험 우회 경로", "출발 전 목적지가 있으면 우회 제안 카드가 뜨고, 주행이 시작되면 숨겨진다",
      "앱 초기화, 시연 모드(목적지: 뚝섬한강공원)",
      ["지도 → 시연 주행 → 시연 시작", "출발 전(P단) 카드 확인", "주행 시작 후 카드 확인"],
      ["P단: '탐험 우회 경로' 카드와 경유지 추가 버튼", "주행 중: 카드 숨김"])
def tc_detour(ctx):
    fresh(ctx)
    # Started from the map's own demo panel: the demo stays parked for only ~1 s, too short to also
    # switch tabs, so card and button are checked from one UI dump right after the start.
    ctx.dev.tap_text("지도")
    ctx.dev.tap_text("시연 주행")
    ctx.dev.tap_text("시연 시작")
    end = time.time() + 3
    nodes = []
    while time.time() < end:
        try:
            nodes = ctx.dev.ui_nodes()
        except RuntimeError:  # dump failed (screen not idle): keep polling
            continue
        if ctx.dev.find("탐험 우회 경로", nodes=nodes):
            break
        time.sleep(0.3)
    ctx.shot("detour_parked")
    ctx.check(ctx.dev.find("탐험 우회 경로", nodes=nodes) is not None, "출발 전 우회 카드 표시")
    ctx.check(ctx.dev.find("경유지 추가", nodes=nodes) is not None, "경유지 추가 버튼")
    ctx.check(ctx.dev.wait_text("탐험 우회 경로", timeout=15, present=False), "주행 중 카드 숨김")
    ctx.shot("detour_driving")
    # The demo is now driving, so the driving view hides the settings tab; stopping the app ends the demo.
    ctx.dev.app_stop()


@case("TC-13", "시연 모드", "시연 모드가 출발→주행→하차까지 끝까지 재생된다",
      "앱 초기화",
      ["지도 → 시연 주행 → 시연 시작", "완료 메시지까지 대기(성수 코스 36 km, 기본 60 km/h·10배속으로 약 4분)", "기록·도감 확인", "시연 중지"],
      ["'시연 주행 완료' 표시", "하차 지점 1곳, 주행 기록 1건", "빗속 개척·가족 탐험 획득"])
def tc_demo(ctx):
    fresh(ctx)
    # Started from the map's own demo panel: the drive ends back on the map, where the completion
    # notice shows (started from settings, it ends on settings, which has no notice).
    ctx.dev.tap_text("지도")
    ctx.dev.tap_text("시연 주행")
    ctx.dev.tap_text("시연 시작")
    ctx.check(ctx.dev.wait_text("시연 주행 완료", timeout=330), "시연 완료")
    ctx.shot("demo_done")
    r = read_record(ctx)
    s = read_stamps(ctx)
    ctx.log(f"기록 {r} / 스탬프 {s}")
    ctx.check(r["landings"] == 1 and r["trips"] == 1, "하차 1곳, 주행 1건")
    ctx.check(s.get("빗속 개척", 0) >= 1 and s.get("가족 탐험", 0) >= 1, "빗속 개척·가족 탐험")
    ctx.dev.tap_text("설정")
    ctx.dev.tap_text("시연 중지")


@case("TC-14", "기록 관리", "기록 전체 삭제 후 모든 기록이 0으로 돌아간다",
      "앱 초기화 후 한 번 주행",
      ["기어 D로 10개 지점 주행 후 P", "설정 → 기록 전체 삭제 → 정말 삭제", "기록 탭 확인"],
      ["밝힌 면적 0, 하차 0곳, 주행 기록 0건"])
def tc_reset(ctx):
    fresh(ctx)
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:10])
    park(ctx)
    ctx.dev.tap_text("설정")
    tap_scrolling(ctx, "기록 전체 삭제")
    ctx.dev.tap_text("정말 삭제")
    time.sleep(2)
    r = read_record(ctx)
    ctx.shot("record_after_reset")
    ctx.log(f"기록: {r}")
    ctx.check(r["area"] == 0 and r["landings"] == 0 and r["trips"] == 0, "모든 기록 0")


@case("TC-15", "내비게이션", "하위 탭에서 뒤로가기를 누르면 앱을 닫지 않고 지도로 돌아간다",
      "앱 실행 중",
      ["기록 탭 이동", "뒤로가기"],
      ["지도 화면(레벨 헤더) 표시", "앱이 계속 포그라운드"])
def tc_back(ctx):
    fresh(ctx)
    ctx.dev.tap_text("기록")
    ctx.check(ctx.dev.wait_text("나의 개척 기록", timeout=5), "기록 화면")
    ctx.dev.key("back")
    ctx.check(ctx.dev.wait_text("Lv.", timeout=5), "지도 화면 복귀")
    ctx.check("com.zobo.burung" in ctx.dev.top_activity(), "앱 포그라운드 유지")


@case("TC-16", "튜토리얼", "첫 실행 때 튜토리얼이 뜨고, 끝내면 다시 뜨지 않으며, 설정에서 다시 볼 수 있다",
      "앱 데이터 초기화, 튜토리얼 건너뛰기 없이 실행",
      ["앱 첫 실행", "다음 4번 → 시작하기", "앱 재시작", "설정 → 튜토리얼 다시 보기 → 건너뛰기"],
      ["'부릉탐험대 둘러보기 1/5' 표시", "시작하기 후 닫힘", "재시작해도 다시 뜨지 않음", "설정에서 다시 열리고 건너뛰기로 닫힘"])
def tc_tutorial(ctx):
    ctx.vc.reset()
    ctx.dev.app_stop()
    ctx.dev.app_reset()
    ctx.dev.geo(*START)
    ctx.dev.app_start(tutorial=True)
    ctx.check(ctx.dev.wait_text("부릉탐험대 둘러보기 1/5", timeout=10, exact=True), "튜토리얼 1/5 표시")
    ctx.shot("tutorial")
    for _ in range(4):
        ctx.dev.tap_text("다음")
        time.sleep(0.5)
    ctx.check(ctx.dev.find("부릉탐험대 둘러보기 5/5") is not None, "마지막 장")
    ctx.dev.tap_text("시작하기")
    ctx.check(ctx.dev.wait_text("부릉탐험대 둘러보기", timeout=5, present=False), "시작하기 후 닫힘")
    ctx.dev.app_stop()
    ctx.dev.app_start(tutorial=True)
    time.sleep(2)
    ctx.check(ctx.dev.find("부릉탐험대 둘러보기 1/5") is None, "재시작해도 다시 뜨지 않음")
    ctx.dev.tap_text("설정")
    tap_scrolling(ctx, "다시 보기")
    ctx.check(ctx.dev.wait_text("부릉탐험대 둘러보기 1/5", timeout=5, exact=True), "설정에서 다시 열림")
    ctx.dev.tap_text("건너뛰기")
    ctx.check(ctx.dev.wait_text("부릉탐험대 둘러보기", timeout=5, present=False), "건너뛰기로 닫힘")


@case("TC-17", "상황 스탬프", "창문을 열고 새 길을 달리면 '바람길 개척'을 얻는다",
      "앱 초기화",
      ["운전석 창문 50% 열기", "기어 D로 12개 지점 주행", "스탬프 탭 확인"],
      ["바람길 개척 1회 획득", "다른 스탬프 없음"])
def tc_stamp_window(ctx):
    _stamp_case(ctx, lambda: ctx.vc.window("front_left", 50), "바람길 개척")


@case("TC-18", "주행 요약", "하차하면 지도에 이번 주행 요약 카드가 뜨고, 닫으면 사라진다",
      "앱 초기화",
      ["기어 D로 20개 지점 주행", "기어 P", "운전석 도어 열기", "요약 카드 확인 후 닫기"],
      ["'이번 주행 요약' 카드와 밝힌 면적·거리 표시", "처음 가 본 동네 표시", "닫기 후 카드 사라짐"])
def tc_trip_summary(ctx):
    fresh(ctx)
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:20])
    park(ctx)
    ctx.vc.door("front_left", True)
    ctx.check(ctx.dev.wait_text("이번 주행 요약", timeout=8, exact=True), "요약 카드 표시")
    ctx.vc.door("front_left", False)
    t = ctx.dev.texts()
    ctx.shot("trip_summary")
    ctx.check(any(x.startswith("+") and "km" in x for x in t), "면적·거리 줄 표시")
    ctx.check(any(x.startswith("처음 가 본 동네") for x in t), "처음 가 본 동네 표시")
    ctx.dev.tap_text("닫기")
    ctx.check(ctx.dev.wait_text("이번 주행 요약", timeout=5, present=False), "닫기 후 사라짐")


@case("TC-19", "동승자 미션", "동승자가 타면 주행 미션이 생기고, 주행 요약에 결과가 나온다",
      "앱 초기화",
      ["조수석 탑승", "기어 D로 20개 지점 주행", "기어 P, 도어 열기", "요약 카드 확인"],
      ["주행 요약에 '동승자 미션' 줄 표시"])
def tc_crew_mission(ctx):
    fresh(ctx)
    ctx.vc.seat("passenger", True)
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:20])
    park(ctx)
    ctx.vc.door("front_left", True)
    ctx.check(ctx.dev.wait_text("이번 주행 요약", timeout=8, exact=True), "요약 카드 표시")
    ctx.vc.door("front_left", False)
    t = ctx.dev.texts()
    ctx.shot("crew_mission")
    line = next((x for x in t if x.startswith("동승자 미션")), None)
    ctx.log(f"미션: {line}")
    ctx.check(line is not None, "동승자 미션 결과 표시")


@case("TC-20", "차량 신호", "창문·주행 가능 거리 신호가 연결 상태에 그대로 보인다",
      "앱 초기화, P단",
      ["주행 가능 거리 250 km 설정", "운전석 창문 60% 열기", "설정 → 연결 상태 확인"],
      ["차량 상태에 '창문 열림', '주행 가능 250 km' 표시"])
def tc_vehicle_extras(ctx):
    fresh(ctx)
    ctx.vc.range_km(250)
    ctx.vc.window("front_left", 60)
    time.sleep(2)
    s = read_settings(ctx)
    ctx.shot("vehicle_extras")
    state = s["차량 상태"] or ""
    ctx.log(f"차량 상태: {state}")
    ctx.check("창문 열림" in state, "창문 열림 표시")
    ctx.check("주행 가능 250 km" in state, "주행 가능 거리 250 km")


@case("TC-21", "공유 카드", "기록 탭에서 '내 탐험 지도 카드'를 만들면 이미지가 저장되고 미리보기가 뜬다",
      "앱 초기화 후 한 번 주행",
      ["기어 D로 10개 지점 주행 후 P", "기록 탭 → 내 탐험 지도 카드", "미리보기 확인 후 닫기"],
      ["미리보기 대화상자와 '이미지로 저장했어요' 표시", "앱 저장소에 PNG 파일 생성"])
def tc_share_card(ctx):
    fresh(ctx)
    ctx.vc.gear("Drive")
    drive(ctx, ROUTE[:10])
    park(ctx)
    ctx.dev.tap_text("기록")
    ctx.dev.shell("logcat", "-c")
    ctx.dev.tap_text("내 탐험 지도 카드")
    ctx.check(ctx.dev.wait_text("이미지로 저장했어요", timeout=10), "저장 안내 표시")
    ctx.shot("share_card")
    # The driver profile's app folder isn't readable over adb; the app logs what it saved.
    saved = ctx.dev.shell("logcat", "-d", "-s", "FogShare:I")
    m = re.search(r"card saved (\S+fogdrive-card-\S+\.png) \((\d+) bytes\)", saved)
    ctx.log(f"저장: {m.group(1) if m else saved.strip()[-200:]}")
    ctx.check(m is not None and int(m.group(2)) > 10_000, "PNG 파일 생성")
    ctx.dev.tap_text("닫기")
