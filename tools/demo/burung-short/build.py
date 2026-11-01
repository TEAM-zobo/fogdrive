#!/usr/bin/env python3
"""부릉탐험대 세로 숏폼 (1080×1920, 30 fps, 약 31초): 장면 목록 → index.html + compositions/*.html.

    python3 build.py
    npx hyperframes@0.8.196 check
    npx hyperframes@0.8.196 snapshot --at 1,6,11,15,19,23,26,29
    npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-shortform.mp4

소재(assets/shots)는 에뮬레이터에서 찍은 앱 화면(앱 영역만)과 주행 화면 연속 캡처로 만든 클립입니다.
글자는 화면 위 168–1330 px 안에만 둡니다(그 아래는 플랫폼 UI). 편집은 이 파일만 고칩니다.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1080, 1920
INK = "#1D2433"
MINT = "#0F8A63"
ORANGE = "#FF6A13"
BG = "#F4F1EA"
# The picture frame every app shot sits in (below the title, inside the 168–1330 band).
FX, FY, FW, FH = 40, 560, 1000, 760
IMG_W, IMG_H = 1705, 1210   # app-area screenshots

FONTS = "".join(
    f"@font-face{{font-family:'Pretendard';src:url('assets/fonts/Pretendard-{n}.woff2') format('woff2');font-weight:{w};}}"
    for n, w in (("Regular", 400), ("SemiBold", 600), ("Bold", 700), ("ExtraBold", 800))
)

# id, start, duration, picture, camera [(time, (cx, cy, scale), move)], title lines, accent words
SCENES = [
    dict(id="hook", start=0, dur=3.6, img="first-map.jpg",
         cam=[(0, (900, 600, 1.15), 0), (0.2, (900, 600, 1.55), 3.2)],
         lines=["매일 똑같은 출퇴근길,", "탐험이 된다면?"], accent=["탐험"]),
    dict(id="drive", start=3.2, dur=6.2, video="drive.mp4",
         lines=["시동만 걸면", "달린 길이 열려요"], accent=["달린", "길이", "열려요"]),
    dict(id="arrive", start=9.0, dur=4.8, img="arrival.jpg",
         cam=[(0, (850, 605, 0.62), 0), (0.5, (463, 940, 1.5), 1.6)],
         lines=["도착하면", "오늘의 성과 확인"], accent=["성과"]),
    dict(id="dex", start=13.4, dur=4.0, img="dex-country.jpg",
         cam=[(0, (470, 600, 1.3), 0), (0.3, (440, 560, 1.9), 3.2)],
         lines=["어디까지 가 봤나", "지역 도감"], accent=["지역", "도감"]),
    dict(id="picks", start=17.0, dur=4.2, img="exp-top.jpg",
         cam=[(0, (900, 160, 0.66), 0), (1.0, (560, 590, 1.05), 1.8)],
         lines=["이번 주엔", "어디로 가 볼까?"], accent=["어디로"]),
    dict(id="titles", start=20.8, dur=4.2, img="exp-stamps.jpg",
         cam=[(0, (560, 130, 0.95), 0), (1.2, (560, 470, 0.95), 2.2)],
         lines=["스탬프 모아", "칭호까지"], accent=["칭호까지"]),
    dict(id="together", start=24.6, dur=3.4, img="arrival.jpg",
         cam=[(0, (463, 900, 1.2), 0), (0.2, (463, 800, 1.45), 2.6)],
         lines=["함께 탄 가족도", "탐험대원"], accent=["탐험대원"]),
]
END_START, END_DUR = 27.6, 3.8
TOTAL = round(END_START + END_DUR, 2)

SFX = [  # file, start, volume
    ("impact-bass-1.mp3", 0.0, 0.35),
    ("whoosh-short.mp3", 3.1, 0.28),
    ("whoosh.mp3", 8.9, 0.28),
    ("pop.mp3", 10.6, 0.35),
    ("whoosh-short.mp3", 13.3, 0.25),
    ("whoosh.mp3", 16.9, 0.25),
    ("whoosh-short.mp3", 20.7, 0.25),
    ("sparkle.mp3", 22.2, 0.3),
    ("whoosh.mp3", 24.5, 0.25),
    ("riser.mp3", 26.0, 0.25),
    ("impact-bass-1.mp3", 27.7, 0.45),
]

BASE_CSS = FONTS + f"""
#root {{ position:absolute; inset:0; overflow:hidden; font-family:'Pretendard',sans-serif; color:{INK}; background:{BG}; }}
.title {{ position:absolute; left:64px; right:64px; top:236px; }}
.tl {{ font-size:92px; font-weight:800; letter-spacing:-0.045em; line-height:1.16; }}
.tw {{ display:inline-block; margin-right:0.26em; }}
.ac {{ color:{MINT}; }}
.frame {{ position:absolute; left:{FX}px; top:{FY}px; width:{FW}px; height:{FH}px; overflow:hidden; border-radius:40px;
  background:#E9EDF2; box-shadow:0 30px 80px rgba(29,36,51,0.22), 0 2px 8px rgba(29,36,51,0.08); }}
.shot {{ position:absolute; left:0; top:0; width:{IMG_W}px; height:{IMG_H}px; transform-origin:0 0; }}
.deco {{ position:absolute; left:0; right:0; top:1390px; height:530px;
  background:linear-gradient(180deg, rgba(46,194,142,0) 0%, rgba(46,194,142,0.16) 100%); }}
.road {{ position:absolute; left:-40px; right:-40px; top:1470px; height:22px; border-radius:11px; background:#FFD34D; opacity:0.85; }}
"""


def cam_xy(cx, cy, s):
    return round(FW / 2 - cx * s, 1), round(FH / 2 - cy * s, 1)


def title_html(sid, lines, accent):
    out = []
    for i, line in enumerate(lines):
        words = "".join(
            f'<span class="tw{" ac" if w.strip(",?!") in accent or w in accent else ""}">{w}</span>' for w in line.split(" ")
        )
        out.append(f'<div class="tl">{words.strip()}</div>')
    return f'<div id="{sid}-title" class="title">{"".join(out)}</div>'


def scene_html(sc):
    sid, dur = sc["id"], sc["dur"]
    enter = [] if sid == "hook" else [
        f'tl.from("#{sid}-title .tw",{{y:40,opacity:0,duration:0.45,ease:"back.out(1.8)",stagger:0.06}},0.05);',
        f'tl.from("#{sid}-frame",{{y:60,opacity:0,duration:0.5,ease:"power3.out"}},0);']
    js = enter + [
          f'tl.to("#{sid}-title .tw",{{opacity:0,y:-20,duration:0.25,ease:"power2.in",stagger:0.02}},{dur - 0.45});']
    if "video" in sc:
        media = (f'<video id="{sid}-video" class="clip" data-start="0" data-duration="{dur}" src="assets/shots/{sc["video"]}" '
                 f'muted playsinline style="position:absolute;left:0;top:-260px;width:{FW}px;height:1300px;object-fit:cover"></video>')
    else:
        media = f'<img id="{sid}-shot" class="shot" src="assets/shots/{sc["img"]}" alt="">'
        first = True
        for t, (cx, cy, s), move in sc["cam"]:
            x, y = cam_xy(cx, cy, s)
            if first:
                js.append(f'tl.set("#{sid}-shot",{{x:{x},y:{y},scale:{s}}},0);')
                first = False
            else:
                js.append(f'tl.to("#{sid}-shot",{{x:{x},y:{y},scale:{s},duration:{move},ease:"power2.inOut"}},{t});')
    return f"""<template>
  <style>{BASE_CSS}</style>
  <div id="root" data-composition-id="{sid}" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div class="deco"></div><div class="road" data-layout-allow-overflow></div>
    {title_html(sid, sc["lines"], sc["accent"])}
    <div id="{sid}-frame" class="frame" data-layout-allow-overflow>{media}</div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    {chr(10).join("    " + j for j in js).strip()}
    window.__timelines["{sid}"] = tl;
  </script>
</template>
"""


def end_html():
    dur = END_DUR
    css = BASE_CSS + f"""
#end-flood {{ position:absolute; inset:0; background:linear-gradient(180deg,#139A6E 0%,#0B6E50 100%); }}
#end-in {{ position:absolute; left:0; right:0; top:420px; text-align:center; color:#FFFFFF; }}
#end-icon {{ width:300px; height:300px; border-radius:72px; box-shadow:0 24px 60px rgba(0,0,0,0.25); }}
#end-name {{ font-size:150px; font-weight:800; letter-spacing:-0.05em; margin-top:48px; }}
#end-sub {{ font-size:50px; font-weight:600; opacity:0.95; margin-top:20px; letter-spacing:-0.03em; }}
#end-chip {{ display:inline-block; margin-top:56px; padding:18px 40px; border-radius:40px; background:rgba(255,255,255,0.18);
  font-size:38px; font-weight:700; }}
#end-credit {{ position:absolute; left:0; right:0; top:1250px; text-align:center; font-size:28px; color:rgba(255,255,255,0.8); }}
"""
    return f"""<template>
  <style>{css}</style>
  <div id="root" data-composition-id="end" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="end-flood"></div>
    <div id="end-in">
      <img id="end-icon" src="assets/shots/icon.png" alt="">
      <div id="end-name">부릉탐험대</div>
      <div id="end-sub">시동을 걸면 시작되는 나만의 탐험 지도</div>
      <div id="end-chip">Pleos Connect 차량 앱</div>
    </div>
    <div id="end-credit">Music: "Fretless" Kevin MacLeod (incompetech.com) CC BY 4.0 · 지도 © OpenStreetMap, OpenFreeMap · 화면은 에뮬레이터 시연</div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    tl.from("#end-flood",{{scaleY:0,transformOrigin:"50% 100%",duration:0.5,ease:"expo.out"}},0);
    tl.from("#end-icon",{{scale:0.4,opacity:0,rotation:-12,duration:0.7,ease:"back.out(2)"}},0.25);
    tl.from("#end-name",{{y:60,opacity:0,duration:0.5,ease:"expo.out"}},0.55);
    tl.from("#end-sub",{{y:30,opacity:0,duration:0.5,ease:"power3.out"}},0.8);
    tl.from("#end-chip",{{scale:0.8,opacity:0,duration:0.4,ease:"back.out(2)"}},1.1);
    tl.from("#end-credit",{{opacity:0,duration:0.4}},1.4);
    window.__timelines["end"] = tl;
  </script>
</template>
"""


def index_html():
    hosts = []
    for i, sc in enumerate(SCENES):
        hosts.append(
            f'      <div id="{sc["id"]}" class="clip" data-composition-id="{sc["id"]}" data-composition-src="compositions/{sc["id"]}.html" '
            f'data-start="{sc["start"]}" data-duration="{sc["dur"]}" data-track-index="{1 + i % 2}" data-track-kind="graphics" '
            f'data-width="{W}" data-height="{H}" style="z-index:{i + 1}"></div>')
    hosts.append(
        f'      <div id="end" class="clip" data-composition-id="end" data-composition-src="compositions/end.html" '
        f'data-start="{END_START}" data-duration="{END_DUR}" data-track-index="3" data-track-kind="graphics" '
        f'data-width="{W}" data-height="{H}" style="z-index:20"></div>')
    audio = [f'      <audio id="music" class="clip" data-start="0" data-duration="{TOTAL}" data-track-index="9" data-volume="1" '
             f"""data-automation='{{"version": 1, "lanes": [{{"target": "volume", "points": [{{"t": 0, "v": 0.6}}, {{"t": {TOTAL - 2.0}, "v": 0.6}}, {{"t": {TOTAL - 0.1}, "v": 0.0}}]}}]}}' """
             f'src="assets/music/fretless.mp3"></audio>']
    lengths = {"impact-bass-1.mp3": 2.1, "pop.mp3": 0.72, "riser.mp3": 1.6, "sparkle.mp3": 1.8,
               "whoosh.mp3": 0.57, "whoosh-short.mp3": 0.57}
    for i, (f, t, v) in enumerate(SFX):
        audio.append(f'      <audio id="sfx{i}" class="clip" data-start="{t}" data-duration="{lengths[f]}" data-track-index="{10 + i % 3}" '
                     f'data-volume="{v}" src="assets/sfx/{f}"></audio>')
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
    os.makedirs(os.path.join(HERE, "compositions"), exist_ok=True)
    for sc in SCENES:
        open(os.path.join(HERE, "compositions", sc["id"] + ".html"), "w", encoding="utf-8").write(scene_html(sc))
    open(os.path.join(HERE, "compositions", "end.html"), "w", encoding="utf-8").write(end_html())
    open(os.path.join(HERE, "index.html"), "w", encoding="utf-8").write(index_html())
    for name, data in (("hyperframes.json", {"$schema": "https://hyperframes.heygen.com/schema/hyperframes.json",
                                             "paths": {"blocks": "compositions", "assets": "assets"}, "authoringSkill": "hyperframes"}),
                       ("meta.json", {"id": "burung-short", "name": "burung-short"})):
        open(os.path.join(HERE, name), "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2))
    print(f"{len(SCENES) + 1} scenes, {TOTAL}s")


if __name__ == "__main__":
    main()
