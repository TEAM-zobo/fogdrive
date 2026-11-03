#!/usr/bin/env python3
"""부릉탐험대 서비스 시연 영상 (1920×1080, 30 fps, 2분 51초): 장면 목록 → index.html + compositions/*.html.

    gh release download burung-media-v2 --pattern burung-demo-assets.zip && unzip -o burung-demo-assets.zip   # assets/(글꼴·음악·아이콘·클립)
    python3 prepare_clips.py        # 녹화본(record.py) → assets/clips (없으면 Release burung-demo-raw-v2에서 받음)
    python3 build.py
    npx hyperframes@0.8.196 check
    npx hyperframes@0.8.196 snapshot --at 2,15,30,50,70,90,110,130,150
    npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-demo.mp4
    ffmpeg -i renders/burung-demo.mp4 -c:v copy -af "volume=4.4dB,alimiter=limit=0.7:level=disabled:attack=1" -c:a aac -b:a 192k renders/burung-demo-final.mp4
    # 음악: Easy Lemon(2:06)이 영상보다 짧아 끝에 앞부분을 이어 붙인 파일을 씀
    #   ffmpeg -i easy-lemon.mp3 -i easy-lemon.mp3 -filter_complex "[0]atrim=0:123.3[a];[1]atrim=0:80[b];[a][b]acrossfade=d=2.5:c1=tri:c2=tri" assets/music/easy-lemon-loop.mp3
    # 음량: 렌더 -21.4 → 마지막 줄 뒤 -17.1 LUFS, 피크 -2.9 dBFS (ffmpeg -af ebur128=peak=true로 측정)

구성(docs/submission/persona.md 6장): ① 서비스 개요(인트로·핵심 가치) → ② 기능 안내(앱 구동·지도·시연 주행)
→ ③ 40대 아빠 A씨의 일주일(평일 등원·출근 → 기록·도감·내 탐험 → 토요일 추천·나들이 → 강원도 여행 → 다음 목표)
→ 설정 → 엔딩. 화면은 Pleos Connect 에뮬레이터에서 앱을 실제로 돌려 녹화한 것이고, 지난 4주 기록은 시연용 데이터입니다.
장면의 시각(beat·voice·cam)은 녹화본 기준 초이고, prepare_clips.py의 CLIPS 구간·배속으로 영상 시각으로 바꿉니다.
편집은 이 파일만 고칩니다. index.html과 compositions/는 생성물입니다.
"""
import json
import os

from prepare_clips import CLIPS

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1920, 1080
INK = "#15192A"
MINT = "#12B886"
MINT_D = "#0B7A5A"
YELLOW = "#FFD43B"
BG = "#F6F3EC"
SUB = "#5B6275"
IMG_W, IMG_H = 1705, 1210                 # app area of the recording
FX, FY, FW = 650, 96, 1200                 # app frame on screen
FH = round(FW * IMG_H / IMG_W)             # 852
FULL = (IMG_W / 2, IMG_H / 2, FW / IMG_W)  # whole app in the frame
CARD = (520, 930, 1.3)                     # the arrival card, bottom left of the map

FONTS = "".join(
    f"@font-face{{font-family:'Pretendard';src:url('assets/fonts/Pretendard-{n}.woff2') format('woff2');font-weight:{w};}}"
    for n, w in (("Regular", 400), ("SemiBold", 600), ("Bold", 700), ("ExtraBold", 800))
)

# App scenes. beats: (time, chip, title, body, data used); voice: (time, seconds, line the app says);
# cam: (time, (cx, cy, scale), move seconds) in app pixels. Times are seconds into the RECORDING (raw-burung/<src>.webm);
# graphics scenes (kind="values"/"persona") sit between them. chapter: the part label shown from that scene on.
SCENES = [
    dict(id="boot", clip="boot", chapter="② 기능 안내",
         beats=[(3.0, "앱 구동", "시동을 걸면 기록이 시작돼요",
                 "차량이 부팅되면 기록 서비스가 자동으로 켜져요. 앱을 열면 행정구역과 탐험 기록을 불러와 바로 지도로 들어가요.",
                 ["시동(부팅) 연동", "행정구역 데이터(기기 안)"])],
         cam=[(3.0, FULL, 0)]),
    dict(id="map", clip="map",
         beats=[(0.5, "지도", "내가 달린 길과 레벨",
                 "왼쪽 위에는 레벨과 지금 있는 동네, '최근 주행'에는 이번 주 요약과 날짜별 주행이 보여요. 아래 카드는 가까운 안 가 본 동네를 추천해요.",
                 ["NaviHelper 집(베이스캠프)", "FusedLocation 위치"])],
         cam=[(0.5, FULL, 0)]),
    dict(id="howto", clip="howto",
         beats=[(0.5, "시연 주행", "코스와 속도를 고르고 시작",
                 "에뮬레이터는 달릴 수 없어서, 실제 도로 코스를 자동으로 달리는 시연 모드가 있어요. 위치·기어·도어·좌석 신호가 실제 차량과 같은 방식으로 앱에 들어가요.",
                 ["Vehicle 기어·도어·좌석", "시연 코스(실제 도로)"])],
         cam=[(0.5, FULL, 0)]),
    dict(id="persona", kind="persona", dur=7.2),
    dict(id="kids", clip="kids", chapter="③ A씨의 일주일 · 평일 아침",
         beats=[(0.5, "출발 전", "아이와 탈 때 키즈모드",
                 "화면 문구와 음성 안내만 아이 눈높이 말투로 바뀌어요. 기록·스탬프·칭호는 그대로예요.",
                 ["설정 › 키즈모드", "탐험대 이름 '도현 탐험대'"])],
         cam=[(0.5, FULL, 0), (2.2, (1060, 330, 0.95), 0.8)]),
    dict(id="kinder", clip="kinder",
         beats=[(1.0, "등원", "앱을 열지 않아도 달린 길이 지도에",
                 "집에서 유치원까지 2.5 km. 지나간 길만 밝게 열리고 노란 개척로로 남아요.",
                 ["FusedLocation 위치", "Vehicle 기어(D)·좌석"]),
                (28.0, "유치원 도착", "P단 + 문 열림 = 하차",
                 "아이를 내려 준 곳에 깃발이 꽂혀요. 뒷좌석에 아이가 타서 '오늘은 함께 탐험했어요!'가 떠요.",
                 ["Vehicle 기어(P)·도어·좌석"])],
         voice=[(30.36, 1.9, "도착! 오늘도 멋진 모험이었어요.")],
         cam=[(1.0, FULL, 0), (31.0, CARD, 0.9)]),
    dict(id="office", clip="office",
         beats=[(0.3, "출근 전", "1분 더 쓰는 탐험 경로",
                 "내비에 목적지가 있으면 출발 전에 탐험 우회 경로를 제안해요. '경유지 추가'를 누르면 내비 경로에 경유지로 들어가요.",
                 ["NaviHelper 목적지·경유지", "Vehicle 주행 가능 거리"]),
                (3.0, "출근", "아이를 내려 주고 회사로",
                 "유치원에서 회사까지 13.6 km. 매일 달리는 출근길도 그대로 탐험 기록이 돼요.",
                 ["FusedLocation 위치"]),
                (43.5, "회사 도착", "새 칭호 「프로 출근러」",
                 "내비에 저장된 '회사'에 평일 20번 도착하면 받는 칭호예요. 지난 4주 19번 + 오늘 1번.",
                 ["NaviHelper 즐겨찾기(회사)"])],
         voice=[(46.76, 1.9, "도착! 오늘도 멋진 모험이었어요.")],
         cam=[(0.3, (520, 900, 1.25), 0), (2.0, FULL, 0.7), (46.0, CARD, 0.9)]),
    dict(id="trips", clip="trips", chapter="③ A씨의 일주일 · 쌓인 기록",
         beats=[(0.5, "최근 주행", "모든 주행은 기록으로",
                 "주행 이름은 내비 즐겨찾기(집·회사)와 지나간 동네로 붙어요(집 → 거여2동 → 회사). 한 건을 누르면 걸린 시간·새 길 비율·탐험 면적과 경로를 다시 보고, 지울 수도 있어요.",
                 ["기기 안 저장(Room DB)", "NaviHelper 집·회사"])],
         cam=[(0.5, FULL, 0)]),
    dict(id="dex", clip="dex",
         beats=[(1.5, "지역 도감", "행정구역별 주행 기록을 한눈에",
                 "전국 → 서울 → 경기. 가 본 시·도와 시·군·구를 색칠하고 개척률이 높은 순으로 보여 줘요. 다음 탐험 후보도 골라 줘요.",
                 ["행정구역 경계(기기 안)"])],
         cam=[(1.5, FULL, 0)]),
    dict(id="exp", clip="exp",
         beats=[(0.5, "내 탐험", "칭호 · 스탬프 · 이번 주 추천",
                 "방금 받은 「프로 출근러」가 대표 칭호가 됐어요. 칭호 5종·스탬프 11종의 조건과 진행도, 이번 주에 해 볼 만한 탐험 추천 3개가 보여요.",
                 ["주행 기록(기기 안)"])],
         cam=[(0.5, FULL, 0)]),
    dict(id="suggest", clip="suggest", chapter="③ A씨의 일주일 · 토요일",
         beats=[(1.0, "어디 갈까?", "추천을 누르면 바로 내비 안내",
                 "'안 가본 동네 가보기'에서 내비 안내를 누르면 현대차 내비가 그 동네까지 경로를 찾아 안내를 시작해요.",
                 ["NaviHelper requestRoute"])],
         cam=[(1.0, FULL, 0)]),
    dict(id="olympic", clip="olympic",
         beats=[(0.5, "가족 나들이", "새 동네마다 음성으로",
                 "아내는 앞좌석, 아이는 뒷좌석. 올림픽공원까지 8 km, 처음 들어선 동네를 아이 말투로 알려 줘요.",
                 ["TextToSpeech(온디바이스)", "Vehicle 좌석(동승자)"]),
                (45.5, "공원 도착", "가족 나들이가 도감을 채워요",
                 "'오늘은 함께 탐험했어요!' 처음 가 본 동네 6곳(문정1동·송파1동·잠실6동…)이 도감에 더해져요.",
                 ["Vehicle 기어(P)·도어"])],
         voice=[(15.70, 2.0, "짜잔! 처음 와 보는 문정1동이에요!"), (47.04, 3.7, "도착! 오늘은 새 동네를 6곳이나 찾았어요. 최고예요!")],
         cam=[(0.5, FULL, 0), (46.5, CARD, 0.9)]),
    dict(id="gangneung", clip="gangneung", chapter="③ A씨의 일주일 · 분기 1회 여행",
         beats=[(1.0, "203 km", "강원도 가족 여행",
                 "위례에서 강릉 경포대까지. 고속도로를 따라 처음 가 보는 읍·면·동이 줄줄이 열리고 레벨이 올라가요.",
                 ["FusedLocation 위치", "Vehicle 좌석(동승자)"]),
                (362.0, "강릉 도착", "멀리 떠난 날, 「주말 원정대」",
                 "Lv.14 → Lv.18, 처음 가 본 동네 25곳. 새 스탬프 '읍·면·동 100곳'·'시·도 3곳 탐험'·'멀리 떠난 날'.",
                 ["NaviHelper 집(50 km 판정)"])],
         voice=[(365.1, 7.0, "도착! 오늘은 새 동네를 25곳이나 찾았어요. 최고예요! 우와, 탐험대가 18레벨이 되었어요!")],
         cam=[(1.0, FULL, 0), (364.0, CARD, 0.9)]),
    dict(id="after", clip="after",
         beats=[(0.5, "지역 도감", "강원도까지 넓어진 지도",
                 "시·도 3/16 · 시·군·구 14 · 읍·면·동 107. 이번 여행으로 전국 지도에 강원도가 색칠됐어요.",
                 ["행정구역 경계(기기 안)"]),
                (6.2, "다음 목표", "칭호 2/5, 다음은?",
                 "「프로 출근러」와 「주말 원정대」를 받았어요. '1,000 km 탐험'까지 6 km 남았어요.",
                 ["주행 기록(기기 안)"])],
         cam=[(0.5, FULL, 0)]),
    dict(id="settings", clip="settings", chapter="② 기능 안내 · 설정",
         beats=[(0.5, "설정", "연결 상태 · 안내 · 기록 관리",
                 "탐험대 이름과 키즈모드, Pleos SDK 연결 상태, 시연 모드, 우회 제안·음성 안내 켜고 끄기, 기록 내보내기·불러오기와 전체 삭제.",
                 ["Pleos SDK 연결 상태"])],
         cam=[(0.5, FULL, 0)]),
]


def to_clip(name, raw):
    """A recording time → seconds into the clip made by prepare_clips.py."""
    t = 0.0
    for t0, t1, sp in CLIPS[name][1]:
        if raw <= t1:
            return round(t + max(0.0, raw - t0) / sp, 2)
        t += (t1 - t0) / sp
    return round(t, 2)


for _sc in SCENES:
    if _sc.get("kind"):
        continue
    c = _sc["clip"]
    _sc["dur"] = round(sum((t1 - t0) / sp for t0, t1, sp in CLIPS[c][1]), 2)
    _sc["beats"] = [(to_clip(c, b[0]),) + tuple(b[1:]) for b in _sc["beats"]]
    _sc["voice"] = [(to_clip(c, v[0]), v[1], v[2]) for v in _sc.get("voice", [])]
    _sc["cam"] = [(to_clip(c, m[0]),) + tuple(m[1:]) for m in _sc["cam"]]
    _sc["beats"][0] = (0,) + tuple(_sc["beats"][0][1:])
    _sc["cam"][0] = (0,) + tuple(_sc["cam"][0][1:])

INTRO_DUR, VALUES_DUR, END_DUR = 4.4, 8.6, 7.5
XF = 0.3   # crossfade

_chapter = None
for _sc in SCENES:   # the part label stays on until the next one
    _chapter = _sc.get("chapter", _chapter)
    _sc["chapter"] = _chapter


def timeline():
    rows = [("intro", 0.0, INTRO_DUR)]
    t = INTRO_DUR - XF
    rows.append(("values", round(t, 2), VALUES_DUR))
    t += VALUES_DUR - XF
    for sc in SCENES:
        rows.append((sc["id"], round(t, 2), sc["dur"] + XF))
        sc["start"] = round(t, 2)
        t += sc["dur"]
    rows.append(("end", round(t, 2), END_DUR))
    return rows, round(t + END_DUR, 2)


ROWS, TOTAL = timeline()

BASE_CSS = FONTS + f"""
#root {{ position:absolute; inset:0; overflow:hidden; font-family:'Pretendard',sans-serif; color:{INK}; background:{BG}; }}
.dots {{ position:absolute; inset:0; background-image:radial-gradient(rgba(21,25,42,0.06) 2px, transparent 2.5px); background-size:34px 34px; }}
.frame {{ position:absolute; left:{FX}px; top:{FY}px; width:{FW}px; height:{FH}px; overflow:hidden; border-radius:30px;
  background:#E9EDF2; border:5px solid {INK}; box-shadow:10px 12px 0 {INK}; }}
.vid {{ position:absolute; left:0; top:0; width:{IMG_W}px; height:{IMG_H}px; transform-origin:0 0; }}
.side {{ position:absolute; left:64px; top:96px; width:540px; }}
.chapter {{ display:inline-block; background:{INK}; color:#fff; border-radius:14px; padding:10px 20px; font-size:26px; font-weight:700; letter-spacing:-0.02em; }}
.beat {{ position:absolute; left:0; top:78px; width:540px; }}
.chip {{ display:inline-block; background:{YELLOW}; border:3px solid {INK}; border-radius:12px; padding:6px 16px; font-size:26px; font-weight:800; }}
.title {{ font-size:54px; font-weight:800; letter-spacing:-0.045em; line-height:1.18; margin-top:18px; word-break:keep-all; }}
.body {{ font-size:29px; font-weight:500; line-height:1.5; color:{SUB}; margin-top:18px; letter-spacing:-0.02em; word-break:keep-all; }}
.data {{ margin-top:22px; }}
.data .lbl {{ font-size:21px; font-weight:700; color:{MINT_D}; letter-spacing:0.02em; }}
.data .tag {{ display:inline-block; margin:8px 8px 0 0; padding:6px 14px; border-radius:10px; background:#fff; border:2px solid rgba(21,25,42,0.12);
  font-size:22px; font-weight:600; color:{INK}; }}
.voice {{ position:absolute; left:64px; top:800px; width:540px; background:{MINT}; color:#fff; border-radius:24px 24px 24px 6px; padding:20px 26px;
  box-shadow:0 14px 30px rgba(18,184,134,0.35); }}
.voice .lbl {{ font-size:20px; font-weight:700; opacity:0.85; }}
.voice .txt {{ font-size:33px; font-weight:800; letter-spacing:-0.03em; margin-top:4px; line-height:1.3; word-break:keep-all; }}
"""


def cam_xy(cx, cy, s):
    x = FW / 2 - cx * s
    y = FH / 2 - cy * s
    # Keep the picture covering the frame.
    x = min(0, max(FW - IMG_W * s, x))
    y = min(0, max(FH - IMG_H * s, y))
    return round(x, 1), round(y, 1)


def scene_html(sc):
    sid, dur = sc["id"], sc["dur"] + XF
    js = [f'tl.from("#{sid}-in",{{opacity:0,duration:{XF},ease:"none"}},0);']
    beats = []
    for k, (t, chip, title, body, data) in enumerate(sc["beats"]):
        bid = f"{sid}-b{k}"
        tags = "".join(f'<span class="tag">{d}</span>' for d in data)
        beats.append(f'<div id="{bid}" class="beat"><span class="chip">{chip}</span><div class="title">{title}</div>'
                     f'<div class="body">{body}</div><div class="data"><div class="lbl">사용 데이터</div>{tags}</div></div>')
        if k == 0:
            js.append(f'tl.from("#{bid} > *",{{y:26,opacity:0,duration:0.45,stagger:0.08,ease:"power3.out"}},0.15);')
        else:
            js.append(f'tl.to("#{sid}-b{k - 1}",{{opacity:0,y:-18,duration:0.25,ease:"power2.in"}},{t});')
            js.append(f'tl.set("#{bid}",{{opacity:0}},0);')
            js.append(f'tl.to("#{bid}",{{opacity:1,duration:0.01}},{t + 0.25});')
            js.append(f'tl.from("#{bid} > *",{{y:26,opacity:0,duration:0.45,stagger:0.08,ease:"power3.out"}},{t + 0.25});')
    voices = []
    for k, (t, d, line) in enumerate(sc.get("voice", [])):
        vid = f"{sid}-v{k}"
        voices.append(f'<div id="{vid}" class="voice"><div class="lbl">앱 음성 안내 (키즈모드)</div><div class="txt">“{line}”</div></div>')
        js.append(f'tl.set("#{vid}",{{opacity:0}},0);')
        js.append(f'tl.fromTo("#{vid}",{{opacity:0,y:24,scale:0.92}},{{opacity:1,y:0,scale:1,duration:0.3,ease:"back.out(2)"}},{t});')
        js.append(f'tl.to("#{vid}",{{opacity:0,y:-10,duration:0.25}},{t + d + 0.2});')
    for j, (t, (cx, cy, s), move) in enumerate(sc["cam"]):
        x, y = cam_xy(cx, cy, s)
        if j == 0 or move == 0:
            js.append(f'tl.set("#{sid}-vid",{{x:{x},y:{y},scale:{s}}},{t});')
        else:
            js.append(f'tl.to("#{sid}-vid",{{x:{x},y:{y},scale:{s},duration:{move},ease:"power3.inOut"}},{t});')
    chapter = f'<span class="chapter">{sc["chapter"]}</span>' if sc.get("chapter") else ""
    return f"""<template>
  <style>{BASE_CSS}</style>
  <div id="root" data-composition-id="{sid}" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="{sid}-in" style="position:absolute;inset:0;">
      <div class="dots"></div>
      <div class="frame" data-layout-allow-overflow>
        <video id="{sid}-vid" class="vid clip" data-start="0" data-duration="{dur}" src="assets/clips/{sc["clip"]}.mp4" muted playsinline></video>
      </div>
      <div class="side">{chapter}{"".join(beats)}</div>
      {"".join(voices)}
    </div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    {chr(10).join("    " + j for j in js).strip()}
    window.__timelines["{sid}"] = tl;
  </script>
</template>
"""


def intro_html():
    dur = INTRO_DUR
    css = BASE_CSS + f"""
#bg {{ position:absolute; inset:0; background:linear-gradient(160deg,#139A6E 0%,#0B6E50 100%); }}
#in {{ position:absolute; left:0; right:0; top:250px; text-align:center; color:#fff; }}
#icon {{ width:220px; height:220px; border-radius:52px; box-shadow:0 24px 60px rgba(0,0,0,0.25); }}
#name {{ font-size:120px; font-weight:800; letter-spacing:-0.05em; margin-top:26px; }}
#sub {{ font-size:44px; font-weight:700; letter-spacing:-0.03em; margin-top:8px; }}
#sub b {{ color:{YELLOW}; }}
#chip {{ display:inline-block; margin-top:36px; padding:14px 30px; border-radius:40px; background:#fff; color:{INK}; font-size:30px; font-weight:800; }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="intro" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="bg"></div>
    <div id="in">
      <img id="icon" src="assets/img/icon.png" alt="">
      <div id="name">부릉탐험대</div>
      <div id="sub"><b>주행</b>이 기록이 되고, <b>기록</b>이 탐험이 되는 차량 앱</div>
      <div id="chip">서비스 시연 · Pleos Connect 에뮬레이터 실동작 화면</div>
    </div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#icon",{{scale:0.3,rotation:-14,opacity:0,duration:0.6,ease:"back.out(2.2)"}},0.1);
    tl.from("#name",{{y:50,opacity:0,duration:0.45,ease:"expo.out"}},0.4);
    tl.from("#sub",{{y:30,opacity:0,duration:0.45,ease:"power3.out"}},0.7);
    tl.from("#chip",{{scale:0.7,opacity:0,duration:0.4,ease:"back.out(2.2)"}},1.1);
    window.__timelines["intro"] = tl;
  </script>
</template>
"""


def persona_html():
    dur = next(sc["dur"] for sc in SCENES if sc["id"] == "persona") + XF
    rows = [("평일", "아이를 유치원에 내려 주고 출근", "키즈모드로 등원 · 하차 깃발 · 20번째 출근에 「프로 출근러」"),
            ("토요일", "가족과 가까운 곳으로 나들이", "추천으로 안 가 본 곳 고르기 · 새 동네 음성 · 함께 탐험"),
            ("분기 1회", "강원도 1박 2일 가족 여행", "'멀리 떠난 날'로 「주말 원정대」 · 도감에 강원도 색칠")]
    css = BASE_CSS + f"""
#in {{ position:absolute; left:150px; top:120px; width:1620px; }}
#k {{ font-size:30px; font-weight:800; color:{MINT_D}; }}
#t {{ font-size:72px; font-weight:800; letter-spacing:-0.05em; margin-top:8px; }}
#s {{ font-size:30px; font-weight:600; color:{SUB}; margin-top:10px; letter-spacing:-0.02em; }}
.row {{ display:flex; align-items:center; margin-top:24px; background:#fff; border-radius:24px; padding:24px 34px; box-shadow:0 10px 26px rgba(21,25,42,0.08); }}
.row .k {{ width:170px; font-size:32px; font-weight:800; color:{MINT_D}; }}
.row .v {{ width:560px; font-size:36px; font-weight:700; letter-spacing:-0.03em; }}
.row .a {{ flex:1; font-size:28px; font-weight:600; color:{SUB}; letter-spacing:-0.02em; word-break:keep-all; }}
#note {{ margin-top:30px; font-size:25px; color:{SUB}; font-weight:600; }}
"""
    rr = "".join(f'<div class="row"><div class="k">{k}</div><div class="v">{v}</div><div class="a">{x}</div></div>' for k, v, x in rows)
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="persona" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="pin" style="position:absolute;inset:0;"><div class="dots"></div>
    <div id="in"><div id="k">③ 사용 시나리오</div><div id="t">40대 아빠 A씨의 일주일</div>
      <div id="s">위례에 살고 양재로 출근, 유치원생 아이 한 명 · 탐험대 이름 '도현 탐험대'</div>{rr}
      <div id="note">지난 4주 기록(주행 67건 · 768 km · 출근 19번)은 시연용 가상 데이터, 오늘부터는 에뮬레이터에서 직접 달린 화면입니다.</div></div></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#pin",{{opacity:0,duration:{XF},ease:"none"}},0);
    tl.from(["#k","#t","#s"],{{y:30,opacity:0,duration:0.45,stagger:0.1,ease:"power3.out"}},0.15);
    tl.from(".row",{{x:-60,opacity:0,duration:0.45,stagger:0.22,ease:"power3.out"}},0.6);
    tl.from("#note",{{opacity:0,duration:0.4}},1.6);
    window.__timelines["persona"] = tl;
  </script>
</template>
"""


def values_html():
    dur = VALUES_DUR
    vals = [("1", "운전이 나만의 탐험 지도가 돼요", "출퇴근도 주말 나들이도 지도와 지역 도감에 쌓여요."),
            ("2", "새로운 지역으로 주행해요", "안 가 본 동네·우회 경로를 제안하고 현대차 내비 안내로 바로 이어져요."),
            ("3", "함께 탄 가족도 탐험대원", "함께 달린 날은 '함께 탐험'으로, 키즈모드는 아이 말투로 안내해요.")]
    menus = [("지도", "주행 · 시연 주행"), ("도감", "지역별 개척 현황"), ("내 탐험", "칭호 · 스탬프 · 추천"), ("설정", "연결 · 안내 · 기록")]
    css = BASE_CSS + f"""
#in {{ position:absolute; left:150px; top:110px; width:1620px; }}
#k {{ font-size:30px; font-weight:800; color:{MINT_D}; }}
#t {{ font-size:66px; font-weight:800; letter-spacing:-0.05em; margin-top:8px; }}
#vals {{ display:flex; gap:28px; margin-top:40px; }}
.val {{ flex:1; background:#fff; border-radius:26px; padding:30px 32px; box-shadow:0 10px 26px rgba(21,25,42,0.08); min-height:270px; }}
.val .n {{ display:inline-block; width:56px; height:56px; border-radius:16px; background:{YELLOW}; border:3px solid {INK}; text-align:center; line-height:50px; font-size:30px; font-weight:800; }}
.val .h {{ font-size:38px; font-weight:800; letter-spacing:-0.04em; margin-top:20px; line-height:1.22; word-break:keep-all; }}
.val .b {{ font-size:26px; font-weight:500; color:{SUB}; margin-top:14px; line-height:1.45; word-break:keep-all; }}
#mk {{ margin-top:46px; font-size:26px; font-weight:800; color:{SUB}; }}
#menus {{ display:flex; gap:20px; margin-top:16px; }}
.menu {{ flex:1; background:{INK}; color:#fff; border-radius:20px; padding:20px 26px; }}
.menu .m {{ font-size:34px; font-weight:800; }}
.menu .d {{ font-size:24px; font-weight:600; opacity:0.7; margin-top:4px; }}
"""
    vv = "".join(f'<div class="val"><div class="n">{n}</div><div class="h">{h}</div><div class="b">{b}</div></div>' for n, h, b in vals)
    mm = "".join(f'<div class="menu"><div class="m">{m}</div><div class="d">{d}</div></div>' for m, d in menus)
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="values" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="pin" style="position:absolute;inset:0;"><div class="dots"></div>
    <div id="in"><div id="k">① 서비스 개요</div><div id="t">시동을 걸면, 달린 길이 탐험 지도에 쌓여요</div>
      <div id="vals">{vv}</div>
      <div id="mk">화면 구성 · 왼쪽 메뉴 4개 (주행 중에는 지도만, 조작은 P단에서)</div><div id="menus">{mm}</div></div></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#pin",{{opacity:0,duration:{XF},ease:"none"}},0);
    tl.from(["#k","#t"],{{y:30,opacity:0,duration:0.45,stagger:0.1,ease:"power3.out"}},0.15);
    tl.from(".val",{{y:50,opacity:0,duration:0.5,stagger:0.35,ease:"power3.out"}},0.6);
    tl.from("#mk",{{opacity:0,duration:0.4}},3.4);
    tl.from(".menu",{{y:30,opacity:0,duration:0.4,stagger:0.15,ease:"power3.out"}},3.6);
    window.__timelines["values"] = tl;
  </script>
</template>
"""


def end_html():
    dur = END_DUR
    css = BASE_CSS + f"""
#flood {{ position:absolute; inset:0; background:{INK}; }}
#in {{ position:absolute; left:0; right:0; top:200px; text-align:center; color:#fff; }}
#icon {{ width:220px; height:220px; border-radius:52px; }}
#name {{ font-size:120px; font-weight:800; letter-spacing:-0.05em; margin-top:26px; }}
#sub {{ font-size:44px; font-weight:700; margin-top:8px; color:{YELLOW}; letter-spacing:-0.03em; }}
#chip {{ display:inline-block; margin-top:36px; padding:12px 30px; border-radius:40px; border:3px solid rgba(255,255,255,0.6); font-size:30px; font-weight:700; }}
#credit {{ position:absolute; left:0; right:0; top:960px; text-align:center; font-size:22px; line-height:1.5; color:rgba(255,255,255,0.7); }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="end" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="flood"></div>
    <div id="in">
      <img id="icon" src="assets/img/icon.png" alt="">
      <div id="name">부릉탐험대</div>
      <div id="sub">시동을 걸면 시작되는 나만의 탐험 지도</div>
      <div id="chip">Pleos Connect 차량 앱</div>
    </div>
    <div id="credit">화면: Pleos Connect 에뮬레이터에서 앱 실동작 녹화 · 지난 주행 기록은 시연용 예시 데이터<br>
      Music "Easy Lemon" Kevin MacLeod (incompetech.com), CC BY 4.0 · 지도 © OpenStreetMap contributors, OpenFreeMap</div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#flood",{{opacity:0,duration:{XF},ease:"none"}},0);
    tl.from("#icon",{{scale:0.4,opacity:0,rotation:-12,duration:0.6,ease:"back.out(2)"}},0.2);
    tl.from("#name",{{y:50,opacity:0,duration:0.45,ease:"expo.out"}},0.45);
    tl.from("#sub",{{y:26,opacity:0,duration:0.45,ease:"power3.out"}},0.7);
    tl.from(["#chip","#credit"],{{opacity:0,duration:0.4}},1.0);
    window.__timelines["end"] = tl;
  </script>
</template>
"""


def music_lane():
    """Music under everything, ducked while the app speaks, out at the end."""
    lo, hi = 0.15, 0.5
    pts = [(0.0, hi)]
    for sc in SCENES:
        for t, d, _ in sc.get("voice", []) if not sc.get("kind") else []:
            a, b = sc["start"] + XF + t, sc["start"] + XF + t + d
            pts += [(a - 0.35, hi), (a - 0.05, lo), (b + 0.1, lo), (b + 0.5, hi)]
    pts += [(TOTAL - 2.0, hi), (TOTAL - 0.1, 0.0)]
    pts = sorted(pts)
    return json.dumps({"version": 1, "lanes": [{"target": "volume", "points": [{"t": round(t, 2), "v": v} for t, v in pts]}]})


def index_html():
    hosts = []
    for i, (sid, st, du) in enumerate(ROWS):
        hosts.append(
            f'      <div id="{sid}" class="clip" data-composition-id="{sid}" data-composition-src="compositions/{sid}.html" '
            f'data-start="{st}" data-duration="{round(du, 2)}" data-track-index="{1 + i % 2}" data-track-kind="graphics" '
            f'data-width="{W}" data-height="{H}" style="z-index:{i + 1}"></div>')
    audio = [f'      <audio id="music" class="clip" data-start="0" data-duration="{TOTAL}" data-track-index="9" data-volume="1" '
             f"data-automation='{music_lane()}' data-media-start=\"0\" src=\"assets/music/easy-lemon-loop.mp3\"></audio>"]
    for k, sc in enumerate(SCENES):
        if sc.get("voice") and not sc.get("kind"):
            audio.append(f'      <audio id="app{k}" class="clip" data-start="{round(sc["start"] + XF, 2)}" data-duration="{sc["dur"]}" '
                         f'data-track-index="{10 + k % 2}" data-volume="1" src="assets/clips/{sc["clip"]}.wav"></audio>')
    return f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width={W}, height={H}" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: {W}px; height: {H}px; overflow: hidden; background: {BG}; }}
      #root {{ position: relative; width: 100%; height: 100%; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL}" data-width="{W}" data-height="{H}">
{chr(10).join(hosts)}
{chr(10).join(audio)}
    </div>
    <script>
      window.__timelines = window.__timelines || {{}};
      window.__timelines["main"] = gsap.timeline({{ paused: true }});
    </script>
  </body>
</html>
"""


def main():
    comp = os.path.join(HERE, "compositions")
    os.makedirs(comp, exist_ok=True)
    keep = {r[0] for r in ROWS}
    for f in os.listdir(comp):
        if f.endswith(".html") and f[:-5] not in keep:
            os.remove(os.path.join(comp, f))
    for name, fn in (("intro", intro_html), ("values", values_html), ("persona", persona_html), ("end", end_html)):
        open(os.path.join(comp, name + ".html"), "w", encoding="utf-8").write(fn())
    for sc in SCENES:
        if not sc.get("kind"):
            open(os.path.join(comp, sc["id"] + ".html"), "w", encoding="utf-8").write(scene_html(sc))
    open(os.path.join(HERE, "index.html"), "w", encoding="utf-8").write(index_html())
    for name, data in (("hyperframes.json", {"$schema": "https://hyperframes.heygen.com/schema/hyperframes.json",
                                             "paths": {"blocks": "compositions", "assets": "assets"}, "authoringSkill": "hyperframes"}),
                       ("meta.json", {"id": "burung-demo", "name": "burung-demo"})):
        open(os.path.join(HERE, name), "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2))
    print(f"{len(ROWS)} scenes, {TOTAL}s")


if __name__ == "__main__":
    main()
