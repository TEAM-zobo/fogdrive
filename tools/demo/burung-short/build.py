#!/usr/bin/env python3
"""부릉탐험대 세로 숏폼 (1080×1920, 30 fps, 약 40초): 장면 목록 → index.html + compositions/*.html.

    python3 build.py
    npx hyperframes@0.8.196 check
    npx hyperframes@0.8.196 snapshot --at 1,4.5,8,12,16.5,21,25.5,30,34,38
    npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-shortform.mp4

이야기: 매일 달리는데 남는 건 0 → 기록으로 남길 수 없을까? → 부릉탐험대 → 시동 ON = 기록 시작 →
출퇴근만 했는데 「프로 출근러」 → 주말 어디 가지? 안 가 본 곳 추천 → 키즈모드 '도현 탐험대' 출동 →
도감에 빈칸 → 다음 여행지 → 도착하면 레벨 업 + 「주말 원정대」 → 너의 칭호는?
숏폼 문법: 첫 1초 안에 큰 글자 훅, 소리 없이도 읽히는 자막 위주, 장면 4–5초, 밈 말투, 마지막에 댓글 유도 질문.
화면은 시연 페르소나 6주 기록(--weeks 6, 칭호를 이미 받은 상태)에서 찍었습니다.

소재(assets/shots)는 에뮬레이터에서 찍은 앱 화면(앱 영역만, 1705×1210)과 주행 연속 캡처 클립입니다.
글자는 화면 위 168–1330 px 안에만 둡니다(그 아래는 플랫폼 UI). 편집은 이 파일만 고칩니다.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1080, 1920
INK = "#15192A"
MINT = "#12B886"
YELLOW = "#FFD43B"
CORAL = "#FF6B4A"
BG = "#F6F3EC"
FX, FY, FW, FH = 40, 470, 1000, 620      # picture frame; the sticker sits under it, all inside the band
IMG_W, IMG_H = 1705, 1210

FONTS = "".join(
    f"@font-face{{font-family:'Pretendard';src:url('assets/fonts/Pretendard-{n}.woff2') format('woff2');font-weight:{w};}}"
    for n, w in (("Regular", 400), ("SemiBold", 600), ("Bold", 700), ("ExtraBold", 800))
)

# Story scenes: a chat-bubble question on top, the app below (one or more shots with camera moves),
# a sticker with the punchline on the picture. Camera keys: (time, (cx, cy, scale), move seconds).
SCENES = [
    dict(id="commute", start=14.0, dur=5.0, bubble="출퇴근만 했을 뿐인데…",
         shots=[("trips.jpg", 0, [(0, (410, 420, 1.55), 0), (0.3, (410, 900, 1.55), 1.8)]),
                ("exp-top.jpg", 2.3, [(0, (850, 605, 0.62), 0), (0.1, (520, 190, 1.7), 1.2)])],
         sticker="「프로 출근러」 칭호 획득", sticker2="출근 20번이면 칭호 해금"),
    dict(id="suggest", start=18.6, dur=4.8, bubble="맨날 가던 길만 가는 사람 손?",
         shots=[("map-suggest.jpg", 0, [(0, (850, 600, 0.62), 0), (0.6, (470, 1100, 1.5), 1.6)])],
         sticker="안 가 본 동네, 앱이 골라 줌", sticker2="터치 한 번이면 내비 출발"),
    dict(id="kids", start=23.0, dur=5.0, bubble="등원길 지루하다는 아이, 여기 있나요?",
         shots=[("settings-kids-on.jpg", 0, [(0, (520, 280, 1.4), 0), (0.4, (1330, 330, 1.4), 1.4)]),
                ("exp-kids.jpg", 2.4, [(0, (900, 170, 0.7), 0), (0.1, (560, 170, 1.7), 1.3)])],
         sticker="키즈모드 ON", sticker2="도현 탐험대, 출동!"),
    dict(id="dex", start=27.6, dur=4.8, bubble="지역 도감 열었다가 깨달음",
         shots=[("dex-country.jpg", 0, [(0, (560, 640, 1.1), 0), (0.3, (540, 640, 1.6), 2.2)])],
         sticker="충청도 아직 0번 실화?", sticker2="다음 여행지, 정해졌다"),
    dict(id="payoff", start=32.0, dur=4.6, bubble="그리고 도착하면?",
         shots=[("gn-arrival.jpg", 0, [(0, (850, 605, 0.62), 0), (0.4, (463, 950, 1.5), 1.4)])],
         sticker="레벨 업 + 「주말 원정대」", sticker2="오늘 탐험, 클리어"),
]
END_START, END_DUR = 36.2, 4.4
TOTAL = round(END_START + END_DUR, 2)

SFX = [  # file, start, volume
    ("impact-bass-1.mp3", 0.0, 0.32),
    ("pop.mp3", 0.9, 0.3), ("pop.mp3", 1.7, 0.3), ("pop.mp3", 2.5, 0.3),
    ("whoosh.mp3", 4.3, 0.28),
    ("riser.mp3", 6.2, 0.22),
    ("impact-bass-1.mp3", 7.9, 0.42),
    ("whoosh-short.mp3", 12.5, 0.25),
    ("pop.mp3", 14.2, 0.3), ("sparkle.mp3", 16.6, 0.28),
    ("whoosh.mp3", 18.5, 0.25), ("pop.mp3", 18.8, 0.3),
    ("whoosh-short.mp3", 22.9, 0.25), ("pop.mp3", 23.2, 0.3), ("sparkle.mp3", 25.7, 0.28),
    ("whoosh.mp3", 27.5, 0.25), ("pop.mp3", 27.8, 0.3),
    ("whoosh-short.mp3", 31.9, 0.25), ("pop.mp3", 32.2, 0.3),
    ("impact-bass-1.mp3", 36.3, 0.45),
]
SFX_LEN = {"impact-bass-1.mp3": 2.1, "pop.mp3": 0.72, "riser.mp3": 1.7, "sparkle.mp3": 1.8,
           "whoosh.mp3": 0.57, "whoosh-short.mp3": 0.57}

BASE_CSS = FONTS + f"""
#root {{ position:absolute; inset:0; overflow:hidden; font-family:'Pretendard',sans-serif; color:{INK}; background:{BG}; }}
.dots {{ position:absolute; inset:0; background-image:radial-gradient(rgba(21,25,42,0.07) 2px, transparent 2.5px); background-size:36px 36px; }}
.bubble {{ position:absolute; left:56px; right:56px; top:200px; }}
.bub {{ display:inline-block; max-width:960px; background:#FFFFFF; border-radius:44px 44px 44px 12px; padding:34px 44px;
  font-size:62px; font-weight:800; letter-spacing:-0.045em; line-height:1.2; word-break:keep-all; box-shadow:0 18px 40px rgba(21,25,42,0.14); }}
.frame {{ position:absolute; left:{FX}px; top:{FY}px; width:{FW}px; height:{FH}px; overflow:hidden; border-radius:44px;
  background:#E9EDF2; border:6px solid {INK}; box-shadow:12px 14px 0 {INK}; }}
.shot {{ position:absolute; left:0; top:0; width:{IMG_W}px; height:{IMG_H}px; transform-origin:0 0; }}
.sticker {{ position:absolute; left:70px; right:70px; top:1120px; text-align:center; }}
.st1 {{ display:inline-block; background:{YELLOW}; color:{INK}; border:5px solid {INK}; border-radius:22px; padding:14px 30px;
  font-size:54px; font-weight:800; letter-spacing:-0.04em; box-shadow:7px 8px 0 {INK}; }}
.st2 {{ display:inline-block; margin-top:14px; background:{INK}; color:#FFFFFF; border-radius:18px; padding:10px 26px;
  font-size:40px; font-weight:700; letter-spacing:-0.03em; }}
.foot {{ position:absolute; left:0; right:0; top:1430px; height:490px;
  background:linear-gradient(180deg, rgba(18,184,134,0) 0%, rgba(18,184,134,0.18) 100%); }}
"""


def cam_xy(cx, cy, s):
    return round(FW / 2 - cx * s, 1), round(FH / 2 - cy * s, 1)


def story_html(sc):
    sid, dur = sc["id"], sc["dur"]
    imgs, js = [], []
    js.append(f'tl.from("#{sid}-bub",{{scale:0.4,opacity:0,transformOrigin:"0% 100%",duration:0.45,ease:"back.out(2.2)"}},0.05);')
    js.append(f'tl.from("#{sid}-frame",{{y:90,rotation:-3,opacity:0,duration:0.55,ease:"back.out(1.4)"}},0.2);')
    for k, (img, at, cam) in enumerate(sc["shots"]):
        iid = f"{sid}-shot{k}"
        imgs.append(f'<img id="{iid}" class="shot" src="assets/shots/{img}" alt="">')
        if k > 0:
            js.append(f'tl.set("#{iid}",{{opacity:0}},0);')
            js.append(f'tl.to("#{iid}",{{opacity:1,duration:0.25}},{at});')
        for j, (t, (cx, cy, s), move) in enumerate(cam):
            x, y = cam_xy(cx, cy, s)
            if j == 0:
                js.append(f'tl.set("#{iid}",{{x:{x},y:{y},scale:{s}}},0);')
            else:
                js.append(f'tl.to("#{iid}",{{x:{x},y:{y},scale:{s},duration:{move},ease:"power3.inOut"}},{round(at + t, 2)});')
    st_at = round(dur - 3.0, 2)
    js.append(f'tl.from("#{sid}-st1",{{scale:0,rotation:-14,duration:0.45,ease:"back.out(2.6)"}},{st_at});')
    js.append(f'tl.from("#{sid}-st2",{{y:30,opacity:0,duration:0.35,ease:"power3.out"}},{st_at + 0.3});')
    js.append(f'tl.to("#{sid}-st1",{{rotation:-3,duration:0.3,ease:"power2.out"}},{st_at + 0.45});')
    js.append(f'tl.to(["#{sid}-bub","#{sid}-sticker"],{{opacity:0,y:-24,duration:0.25,ease:"power2.in"}},{dur - 0.35});')
    return f"""<template>
  <style>{BASE_CSS}</style>
  <div id="root" data-composition-id="{sid}" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div class="dots"></div><div class="foot"></div>
    <div class="bubble"><div id="{sid}-bub" class="bub">{sc["bubble"]}</div></div>
    <div id="{sid}-frame" class="frame" data-layout-allow-overflow>{"".join(imgs)}</div>
    <div id="{sid}-sticker" class="sticker"><div id="{sid}-st1" class="st1">{sc["sticker"]}</div><br><div id="{sid}-st2" class="st2">{sc["sticker2"]}</div></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    {chr(10).join("    " + j for j in js).strip()}
    window.__timelines["{sid}"] = tl;
  </script>
</template>
"""


def hook_html():
    """0–4.4 s: 출퇴근 / 근교 드라이브 / 여행 stack up over the moving map, then 운전은 우리의 일상."""
    dur = 4.6
    css = BASE_CSS + f"""
#hook-bg {{ position:absolute; inset:0; background:{INK}; }}
#hook-vid {{ position:absolute; left:0; top:-330px; width:1080px; height:2400px; object-fit:cover; opacity:0.38; }}
.tag {{ position:absolute; left:80px; font-size:118px; font-weight:800; letter-spacing:-0.05em; color:#FFFFFF; }}
.tag b {{ color:{YELLOW}; }}
#hook-t1 {{ top:300px; }} #hook-t2 {{ top:470px; }} #hook-t3 {{ top:640px; }}
#hook-line {{ position:absolute; left:80px; right:80px; top:880px; font-size:80px; font-weight:800; letter-spacing:-0.045em; color:#FFFFFF; line-height:1.2; }}
#hook-line span {{ color:{MINT}; }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="hook" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="hook-bg"></div>
    <video id="hook-vid" class="clip" data-start="0" data-duration="{dur}" src="assets/shots/drive.mp4" muted playsinline></video>
    <div id="hook-t1" class="tag"><b>출퇴근</b>,</div>
    <div id="hook-t2" class="tag"><b>근교 드라이브</b>,</div>
    <div id="hook-t3" class="tag"><b>여행</b>…</div>
    <div id="hook-line">매일 달리는데<br><span>남는 건 0</span></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#hook-t2",{{x:-120,opacity:0,duration:0.35,ease:"back.out(2)"}},0.8);
    tl.from("#hook-t3",{{x:-120,opacity:0,duration:0.35,ease:"back.out(2)"}},1.6);
    tl.from("#hook-line",{{y:50,opacity:0,duration:0.5,ease:"expo.out"}},2.4);
    tl.to(["#hook-t1","#hook-t2","#hook-t3","#hook-line"],{{opacity:0,y:-30,duration:0.25,stagger:0.04,ease:"power2.in"}},{dur - 0.45});
    window.__timelines["hook"] = tl;
  </script>
</template>
"""


def ask_html():
    """4.2–8 s: the question, on yellow."""
    dur = 3.8
    css = BASE_CSS + f"""
#ask-bg {{ position:absolute; inset:0; background:{YELLOW}; }}
#ask-q {{ position:absolute; left:70px; right:70px; top:470px; font-size:118px; font-weight:800; letter-spacing:-0.055em; line-height:1.14; }}
#ask-q .u {{ background:linear-gradient(transparent 62%, #FFFFFF 62%); }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="ask" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="ask-bg"></div>
    <div id="ask-q"><div class="w"><span class="u">기록</span>으로</div><div class="w">남길 수</div><div class="w">없을까?</div></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#ask-bg",{{scaleY:0,transformOrigin:"50% 0%",duration:0.4,ease:"expo.out"}},0);
    tl.from("#ask-q .w",{{y:70,opacity:0,duration:0.45,ease:"back.out(1.8)",stagger:0.18}},0.25);
    tl.to("#ask-q",{{scale:1.06,duration:2.4,ease:"sine.inOut"}},0.9);
    tl.to("#ask-q",{{opacity:0,duration:0.25}},{dur - 0.3});
    window.__timelines["ask"] = tl;
  </script>
</template>
"""


def intro_html():
    """7.8–12.4 s: the app."""
    dur = 4.6
    css = BASE_CSS + f"""
#intro-bg {{ position:absolute; inset:0; background:linear-gradient(180deg,#139A6E 0%,#0B6E50 100%); }}
#intro-in {{ position:absolute; left:0; right:0; top:330px; text-align:center; color:#FFFFFF; }}
#intro-icon {{ width:280px; height:280px; border-radius:66px; box-shadow:0 24px 60px rgba(0,0,0,0.25); }}
#intro-name {{ font-size:150px; font-weight:800; letter-spacing:-0.05em; margin-top:36px; }}
#intro-sub {{ font-size:56px; font-weight:700; letter-spacing:-0.035em; line-height:1.3; margin-top:18px; }}
#intro-sub b {{ color:{YELLOW}; }}
#intro-chip {{ display:inline-block; margin-top:44px; padding:18px 36px; border-radius:40px; background:#FFFFFF; color:{INK};
  font-size:44px; font-weight:800; letter-spacing:-0.03em; }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="intro" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="intro-bg"></div>
    <div id="intro-in">
      <img id="intro-icon" src="assets/shots/icon.png" alt="">
      <div id="intro-name">부릉탐험대</div>
      <div id="intro-sub"><b>주행</b>이 기록이 되고,<br><b>기록</b>이 탐험이 되는 차량 앱</div>
      <div id="intro-chip">시동 ON = 기록 시작</div>
    </div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#intro-bg",{{clipPath:"circle(0% at 50% 40%)",duration:0.55,ease:"expo.out"}},0);
    tl.from("#intro-icon",{{scale:0.3,rotation:-16,opacity:0,duration:0.6,ease:"back.out(2.2)"}},0.2);
    tl.from("#intro-name",{{y:60,opacity:0,duration:0.45,ease:"expo.out"}},0.5);
    tl.from("#intro-sub",{{y:40,opacity:0,duration:0.45,ease:"power3.out"}},0.8);
    tl.from("#intro-chip",{{scale:0.6,opacity:0,duration:0.4,ease:"back.out(2.4)"}},1.3);
    tl.to("#intro-in",{{opacity:0,y:-30,duration:0.3,ease:"power2.in"}},{dur - 0.35});
    window.__timelines["intro"] = tl;
  </script>
</template>
"""


def drive_html():
    """12–13.8 s: driving, the road opening."""
    dur = 2.4
    css = BASE_CSS + f"""
#drive-vid {{ position:absolute; left:0; top:-330px; width:{W}px; height:2400px; object-fit:cover; }}
#drive-cap {{ position:absolute; left:60px; right:60px; top:1130px; text-align:center; }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="drive" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <video id="drive-vid" class="clip" data-start="0" data-duration="{dur}" data-media-start="3" src="assets/shots/drive.mp4" muted playsinline></video>
    <div id="drive-cap"><div id="drive-st" class="st1">앱 안 켜도 OK, 달리면 열림</div></div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#drive-st",{{scale:0,rotation:-10,duration:0.4,ease:"back.out(2.4)"}},0.1);
    window.__timelines["drive"] = tl;
  </script>
</template>
"""


def end_html():
    dur = END_DUR
    css = BASE_CSS + f"""
#end-flood {{ position:absolute; inset:0; background:{INK}; }}
#end-in {{ position:absolute; left:0; right:0; top:380px; text-align:center; color:#FFFFFF; }}
#end-icon {{ width:300px; height:300px; border-radius:72px; }}
#end-name {{ font-size:150px; font-weight:800; letter-spacing:-0.05em; margin-top:40px; }}
#end-sub {{ font-size:52px; font-weight:700; margin-top:16px; letter-spacing:-0.03em; color:{YELLOW}; }}
#end-ask {{ font-size:46px; font-weight:800; margin-top:44px; letter-spacing:-0.035em; color:#FFFFFF; }}
#end-chip {{ display:inline-block; margin-top:34px; padding:16px 36px; border-radius:40px; border:3px solid rgba(255,255,255,0.6);
  font-size:38px; font-weight:700; }}
#end-credit {{ position:absolute; left:60px; right:60px; top:1220px; text-align:center; font-size:28px; line-height:1.4; color:rgba(255,255,255,0.78); }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="end" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="end-flood"></div>
    <div id="end-in">
      <img id="end-icon" src="assets/shots/icon.png" alt="">
      <div id="end-name">부릉탐험대</div>
      <div id="end-sub">시동을 걸면 시작되는 나만의 탐험 지도</div>
      <div id="end-ask">나는 프로 출근러. 너의 칭호는?</div>
      <div id="end-chip">Pleos Connect 차량 앱</div>
    </div>
    <div id="end-credit">Music "Think About You" Square a Saw (Jamendo), CC BY 3.0<br>지도 © OpenStreetMap, OpenFreeMap · 화면은 에뮬레이터 시연</div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#end-flood",{{scaleY:0,transformOrigin:"50% 100%",duration:0.45,ease:"expo.out"}},0);
    tl.from("#end-icon",{{scale:0.4,opacity:0,rotation:-12,duration:0.6,ease:"back.out(2)"}},0.2);
    tl.from("#end-name",{{y:60,opacity:0,duration:0.45,ease:"expo.out"}},0.45);
    tl.from("#end-sub",{{y:30,opacity:0,duration:0.45,ease:"power3.out"}},0.7);
    tl.from("#end-ask",{{scale:0.7,opacity:0,duration:0.4,ease:"back.out(2.4)"}},1.2);
    tl.from("#end-chip",{{scale:0.8,opacity:0,duration:0.4,ease:"back.out(2)"}},1.5);
    window.__timelines["end"] = tl;
  </script>
</template>
"""


FIXED = [("hook", 0, 4.6, hook_html), ("ask", 4.2, 3.8, ask_html), ("intro", 7.8, 4.6, intro_html),
         ("drive", 12.0, 2.4, drive_html)]


def index_html():
    hosts, z = [], 1
    rows = [(sid, st, du) for sid, st, du, _ in FIXED] + [(s["id"], s["start"], s["dur"]) for s in SCENES] + [("end", END_START, END_DUR)]
    for i, (sid, st, du) in enumerate(rows):
        hosts.append(
            f'      <div id="{sid}" class="clip" data-composition-id="{sid}" data-composition-src="compositions/{sid}.html" '
            f'data-start="{st}" data-duration="{du}" data-track-index="{1 + i % 2}" data-track-kind="graphics" '
            f'data-width="{W}" data-height="{H}" style="z-index:{i + 1}"></div>')
    audio = [f'      <audio id="music" class="clip" data-start="0" data-duration="{TOTAL}" data-track-index="9" data-volume="1" '
             f"""data-automation='{{"version": 1, "lanes": [{{"target": "volume", "points": [{{"t": 0, "v": 0.36}}, {{"t": {TOTAL - 2.0}, "v": 0.36}}, {{"t": {TOTAL - 0.1}, "v": 0.0}}]}}]}}' """
             f'data-media-start="20" src="assets/music/think-about-you.mp3"></audio>']
    for i, (f, t, v) in enumerate(SFX):
        audio.append(f'      <audio id="sfx{i}" class="clip" data-start="{t}" data-duration="{SFX_LEN[f]}" data-track-index="{10 + i % 3}" '
                     f'data-volume="{v}" src="assets/sfx/{f}"></audio>')
    return f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width={W}, height={H}" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: {W}px; height: {H}px; overflow: hidden; background: {INK}; }}
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
    keep = {f[0] for f in FIXED} | {s["id"] for s in SCENES} | {"end"}
    for f in os.listdir(comp):
        if f.endswith(".html") and f[:-5] not in keep:
            os.remove(os.path.join(comp, f))
    for sid, _, _, fn in FIXED:
        open(os.path.join(comp, sid + ".html"), "w", encoding="utf-8").write(fn())
    for sc in SCENES:
        open(os.path.join(comp, sc["id"] + ".html"), "w", encoding="utf-8").write(story_html(sc))
    open(os.path.join(comp, "end.html"), "w", encoding="utf-8").write(end_html())
    open(os.path.join(HERE, "index.html"), "w", encoding="utf-8").write(index_html())
    for name, data in (("hyperframes.json", {"$schema": "https://hyperframes.heygen.com/schema/hyperframes.json",
                                             "paths": {"blocks": "compositions", "assets": "assets"}, "authoringSkill": "hyperframes"}),
                       ("meta.json", {"id": "burung-short", "name": "burung-short"})):
        open(os.path.join(HERE, name), "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2))
    print(f"{len(FIXED) + len(SCENES) + 1} scenes, {TOTAL}s")


if __name__ == "__main__":
    main()
