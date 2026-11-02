#!/usr/bin/env python3
"""부릉탐험대 서비스 시연 영상 (1920×1080, 30 fps, 약 1분 14초): 장면 목록 → index.html + compositions/*.html.

    python3 prepare_clips.py        # 녹화본 → assets/clips (없으면 Release burung-demo-raw-v1에서 받음)
    python3 build.py
    npx hyperframes@0.8.196 check
    npx hyperframes@0.8.196 snapshot --at 2,6,11,20,30,36,42,50,58,65,71
    npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-demo.mp4
    ffmpeg -i renders/burung-demo.mp4 -c:v copy -af "volume=3.6dB,alimiter=limit=0.85:level=disabled" -c:a aac -b:a 192k renders/burung-demo-final.mp4
    # 음량: 마지막 줄 뒤 -17.1 LUFS, 피크 -1 dBFS 근처 (ffmpeg -af ebur128=peak=true로 측정)

시나리오(기획서 4. 주요 사용 시나리오, 40대 아빠 A씨): ① 평일 아침 키즈모드 ON → 유치원 등원(하차 깃발·함께 탐험)
→ 출근(20번째 출근, 「프로 출근러」) ② 토요일 도감·추천으로 목적지 → 올림픽공원 나들이(처음 가는 동네 음성,
'주말 드라이브') → 내 탐험. 화면은 Pleos Connect 에뮬레이터에서 앱을 실제로 돌려 녹화한 것이고, 지난 4주 기록은
시연용 데이터입니다. v1 녹화는 페르소나 통일 전의 기록(출퇴근만 한 4주, 출근 19번)으로 찍었습니다. 다시 찍을 때는
docs/submission/persona.md 4장(A씨 통일 시나리오)과 기본 기록(make_persona_backup.py)을 씁니다.
편집은 이 파일만 고칩니다. index.html과 compositions/는 생성물입니다.
"""
import json
import os

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
# cam: (time, (cx, cy, scale), move seconds) in app pixels. Times are seconds into the scene.
SCENES = [
    dict(id="kids", clip="kids", dur=5.5, chapter="① 평일 아침 · 등원 + 출근",
         beats=[(0, "출발 전", "키즈모드 ON",
                 "아이와 함께 탈 때 켜면 화면 문구와 음성 안내가 아이 눈높이 말투로 바뀝니다. 기록·칭호는 그대로예요.",
                 ["설정 › 키즈모드", "탐험대 이름 '도현 탐험대'"])],
         cam=[(0, FULL, 0), (1.7, (1060, 330, 0.95), 0.8)]),
    dict(id="kinder", clip="kinder", dur=13.3,
         beats=[(0, "주행", "시동만 걸면 자동 기록",
                 "앱을 열지 않아도 달린 길이 지도에 열립니다. 아이와 유치원까지 2.5 km, 처음 지나는 길은 밝게 남아요.",
                 ["FusedLocation 위치", "Vehicle 기어(D)·좌석"]),
                (7.2, "유치원 도착", "P단 + 문 열림 = 하차 깃발",
                 "아이를 내려 준 곳에 깃발이 꽂히고, 뒷좌석에 아이가 탔으니 '함께 탐험' 스탬프를 받아요.",
                 ["Vehicle 기어(P)·도어·좌석"])],
         voice=[(7.0, 1.9, "짜잔! 처음 와 보는 거여2동이에요!"), (9.7, 3.6, "도착! 오늘은 새 동네를 1곳이나 찾았어요. 최고예요!")],
         cam=[(0, FULL, 0), (7.6, CARD, 0.9)]),
    dict(id="office", clip="office", dur=11.2,
         beats=[(0, "출근", "아이를 내려 주고 회사로",
                 "유치원에서 회사까지 13.6 km. 매일 달리는 출근길도 그대로 탐험 기록이 됩니다.",
                 ["FusedLocation 위치", "NaviHelper 경로 안내"]),
                (6.5, "회사 도착", "새 칭호 「프로 출근러」",
                 "내비에 저장된 '회사'에 평일 20번 도착하면 칭호를 받아요. 지난 4주 19번 + 오늘 1번.",
                 ["NaviHelper 즐겨찾기(회사)"])],
         voice=[(7.3, 3.8, "도착! 오늘은 새 동네를 11곳이나 찾았어요. 최고예요!")],
         cam=[(0, FULL, 0), (6.9, CARD, 0.9)]),
    dict(id="plan", clip="plan", dur=7.7, chapter="② 토요일 · 가족 나들이",
         beats=[(0, "출발 전", "이번 주말, 어디 갈까?",
                 "지역 도감의 '다음 탐험 후보'와 이번 주 탐험 추천을 보고, 아직 안 가 본 곳으로 목적지를 정해요.",
                 ["행정구역 도감(기기 안)", "이번 주 탐험 추천"])],
         cam=[(0, (1130, 520, 0.95), 0), (0.3, (1150, 560, 1.1), 3.8), (4.8, FULL, 0)]),
    dict(id="olympic", clip="olympic", dur=15.9,
         beats=[(0, "주행", "가족 나들이도 탐험",
                 "올림픽공원까지 8 km. 처음 지나는 동네마다 아이에게 음성으로 알려 줘요.",
                 ["TextToSpeech(온디바이스)", "Vehicle 좌석(앞·뒤 동승)"]),
                (10.4, "공원 도착", "새 스탬프 '주말 드라이브'",
                 "토요일에 가족과 달린 기록으로 새 스탬프를 받고, 처음 가 본 동네 9곳이 도감에 채워져요.",
                 ["Vehicle 기어(P)·도어"])],
         voice=[(3.4, 1.9, "짜잔! 처음 와 보는 문정1동이에요!"), (12.1, 3.6, "도착! 오늘은 새 동네를 9곳이나 찾았어요. 최고예요!")],
         cam=[(0, FULL, 0), (10.8, CARD, 0.9)]),
    dict(id="exp", clip="exp", dur=6.9,
         beats=[(0, "내 탐험", "쌓인 기록은 칭호와 스탬프로",
                 "칭호 5종·스탬프 11종의 진행도와 이번 주 추천을 한 화면에서 보고, 다음 목표를 정해요.",
                 ["주행 기록(기기 안 Room DB)"])],
         cam=[(0, FULL, 0)]),
]
INTRO_DUR, PERSONA_DUR, END_DUR = 4.4, 4.6, 5.0
XF = 0.3   # crossfade


def timeline():
    rows = [("intro", 0.0, INTRO_DUR)]
    t = INTRO_DUR - XF
    rows.append(("persona", round(t, 2), PERSONA_DUR))
    t += PERSONA_DUR - XF
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
    dur = PERSONA_DUR
    rows = [("평일", "아이를 유치원에 내려 주고 출근 (왕복 약 30 km)"),
            ("주말", "가족과 가까운 공원·근교로 나들이"),
            ("바람", "아이와 함께하는 차 안 시간을 기록으로 남기고 싶다")]
    css = BASE_CSS + f"""
#in {{ position:absolute; left:160px; top:170px; width:1600px; }}
#k {{ font-size:30px; font-weight:800; color:{MINT_D}; }}
#t {{ font-size:76px; font-weight:800; letter-spacing:-0.05em; margin-top:10px; }}
.row {{ display:flex; align-items:center; margin-top:28px; background:#fff; border-radius:24px; padding:26px 34px; box-shadow:0 10px 26px rgba(21,25,42,0.08); }}
.row .k {{ width:120px; font-size:32px; font-weight:800; color:{MINT_D}; }}
.row .v {{ font-size:40px; font-weight:700; letter-spacing:-0.03em; }}
#note {{ margin-top:34px; font-size:26px; color:{SUB}; font-weight:600; }}
"""
    rr = "".join(f'<div class="row"><div class="k">{k}</div><div class="v">{v}</div></div>' for k, v in rows)
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="persona" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="pin" style="position:absolute;inset:0;"><div class="dots"></div>
    <div id="in"><div id="k">시연 시나리오</div><div id="t">40대 아빠 A씨의 일주일</div>{rr}
      <div id="note">시연 데이터: 내비에 집·회사 저장, 지난 4주 출근 19번 기록 (시연용 예시)</div></div></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#pin",{{opacity:0,duration:{XF},ease:"none"}},0);
    tl.from(["#k","#t"],{{y:30,opacity:0,duration:0.45,stagger:0.1,ease:"power3.out"}},0.15);
    tl.from(".row",{{x:-60,opacity:0,duration:0.45,stagger:0.18,ease:"power3.out"}},0.5);
    tl.from("#note",{{opacity:0,duration:0.4}},1.3);
    window.__timelines["persona"] = tl;
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
      Music "Think About You" Square a Saw (Jamendo), CC BY 3.0 · 지도 © OpenStreetMap contributors, OpenFreeMap</div>
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
    lo, hi = 0.07, 0.24
    pts = [(0.0, hi)]
    for sc in SCENES:
        for t, d, _ in sc.get("voice", []):
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
             f"data-automation='{music_lane()}' data-media-start=\"8\" src=\"assets/music/think-about-you.mp3\"></audio>"]
    for k, sc in enumerate(SCENES):
        if sc.get("voice"):
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
    for name, fn in (("intro", intro_html), ("persona", persona_html), ("end", end_html)):
        open(os.path.join(comp, name + ".html"), "w", encoding="utf-8").write(fn())
    for sc in SCENES:
        open(os.path.join(comp, sc["id"] + ".html"), "w", encoding="utf-8").write(scene_html(sc))
    open(os.path.join(HERE, "index.html"), "w", encoding="utf-8").write(index_html())
    for name, data in (("hyperframes.json", {"$schema": "https://hyperframes.heygen.com/schema/hyperframes.json",
                                             "paths": {"blocks": "compositions", "assets": "assets"}, "authoringSkill": "hyperframes"}),
                       ("meta.json", {"id": "burung-demo", "name": "burung-demo"})):
        open(os.path.join(HERE, name), "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2))
    print(f"{len(ROWS)} scenes, {TOTAL}s")


if __name__ == "__main__":
    main()
