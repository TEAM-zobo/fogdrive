#!/usr/bin/env python3
"""Writes the vertical shortform's HyperFrames compositions (1080x1920, 47 s) from the shot list below.

    python3 tools/demo/shortform/build.py      # index.html + compositions/*.html (+ assets -> ../promo/assets)
    cd tools/demo/shortform && npx hyperframes@0.8.196 check && npx hyperframes@0.8.196 render

The same recordings, fonts, music and sound as the promo film (tools/demo/promo: run its prepare_assets.sh
first), recomposed for a phone: the app's map area cropped tall, text inside the 168-1330 px band the
platform UI leaves free, a new beat every 2-5 s. Conti and rules: docs/submission/shortform.md.

A shot is a recording seen through a camera: (cx, cy, z) puts source pixel (cx, cy) of the 2560x1440
Pleos screen at the frame's centre with z frame pixels per source pixel.
"""
import importlib.util
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
PROMO = os.path.join(HERE, "..", "promo")
_spec = importlib.util.spec_from_file_location("promo_build", os.path.join(PROMO, "build.py"))
promo = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(promo)
from_promo = ("INK", "SUB", "GROUND", "ACCENT", "NIGHT_ACCENT", "WORN", "FONT_FACE", "SPEAKER", "words", "tween_words", "slam_words")
INK, SUB, GROUND, ACCENT, NIGHT_ACCENT, WORN, FONT_FACE, SPEAKER, words, tween_words, slam_words = (getattr(promo, n) for n in from_promo)

W, H = 1080, 1920
TALL = (1680, 700, 1.5)   # around the car: 720 x 1280 source px, past the status and climate bars
HASHTAG = "#HMG차량용앱개발공모전"

# ---------------------------------------------------------------------------------------------- film

SCENES = []


def scene(sid, start, dur, **kw):
    SCENES.append(dict(id=sid, start=start, dur=dur, **kw))


# Hook (0-4): the question is already on screen in frame 0 (the thumbnail); the fog clears behind the car.
scene("hook", 0, 4, kind="shot", src="hook", ms=1, cam=[(0, TALL), (0.2, (1680, 700, 1.8), 3.6)],
      title=dict(lines=["우리 동네,", "얼마나 가 봤을까?"], accent="가 봤을까?"))
# Proof (4-28): one drive.
scene("drive", 4, 5, kind="shot", src="drive", ms=2, cam=[(0, (1640, 700, 1.45)), (0.3, (1660, 700, 1.7), 4.6)],
      cap=dict(h=["시동만 걸면,", "달린 길의 안개가 걷혀요"], at=0.3))
scene("voice", 9, 6, kind="shot", src="voice", audio=True, cam=[(0, (1680, 680, 1.85)), (0.3, (1680, 660, 1.5), 5.6)],
      cap=dict(h=["처음 가 본 동네는,", "차가 먼저 알려 줘요"], at=0.3, icon=True))
scene("signals", 15, 4, kind="shot", src="signals", ms=1, cam=[(0, TALL), (0.4, (1720, 520, 1.9), 1.0)],
      cap=dict(h=["창문을 연 순간까지,", "스탬프로 남아요"], at=0.6, pos="mid"))
scene("arrive", 19, 5, kind="shot", src="arrive", ms=2.8, cam=[(0, (1310, 1000, 1.6))],
      cap=dict(h=["도착하면,", "오늘의 여정이 한 장에"], at=0.4))
scene("first", 24, 4, kind="shot", src="firstpath", cut=True, cam=[(0, (1800, 700, 1.34))],
      stat=dict(label="첫 드라이브가 밝힌 세상", value="4.07", unit="km²", sub="처음 가 본 동네 44곳", at=0.0, out=3.6))
# Payoff (28-42): the same map after weeks of driving, then the app around it.
scene("worn", 28, 6, kind="shot", src="worn", ms=1, cam=[(0, (1600, 760, 1.4))],
      cap=dict(h=["자주 다닌 길은,", "색이 깊어져요"], at=0.4, legend=True))
scene("record", 34, 2, kind="shot", whip=True, src="record", cam=[(0, (1360, 640, 1.4))], cap=dict(h=["매주 쌓이는 나의 기록"], at=0.1))
scene("dex", 36, 2, kind="shot", whip=True, src="dex", cam=[(0, (1360, 600, 1.4))], cap=dict(h=["동네마다 채우는 도감"], at=0.1))
scene("stamps", 38, 2, kind="shot", whip=True, src="stamps", cam=[(0, (1360, 640, 1.4))], cap=dict(h=["29가지 드라이브 스탬프"], at=0.1))
scene("share", 40, 2, kind="shot", whip=True, src="share", cam=[(0, (1690, 680, 1.22))], cap=dict(h=["나만의 지도를 공유해요"], at=0.1))
# End (42-47): the name, the line and the contest hashtag.
scene("end", 42, 5, kind="end")
TOTAL = 47

FADE = 0.4   # scenes overlap by this much; the next one fades (or whips) in over it
for _s in SCENES[:-1]:
    _s["dur"] = round(_s["dur"] + FADE, 2)

# ---------------------------------------------------------------------------------------------- html


def cam_state(c):
    cx, cy, z = c
    return dict(x=round(W / 2 - cx * z, 1), y=round(H / 2 - cy * z, 1), scale=z)


COMMON_CSS = f"""
#root {{ position:absolute; inset:0; overflow:hidden; font-family:'Pretendard',sans-serif; color:{INK}; }}
.cam {{ position:absolute; left:0; top:0; width:2560px; height:1440px; transform-origin:0 0; }}
.cam video {{ position:absolute; left:0; top:0; width:2560px; height:1440px; }}
.tw {{ display:inline-block; transform-origin:50% 60%; }}
.title {{ position:absolute; left:72px; right:72px; top:400px; color:#FFFFFF;
  text-shadow:0 2px 8px rgba(0,0,0,0.45), 0 10px 48px rgba(0,0,0,0.6); }}
.title .tl {{ font-size:112px; font-weight:800; letter-spacing:-0.05em; line-height:1.14; }}
.cap {{ position:absolute; left:60px; right:60px; top:360px; }}   /* below the app's top banner */
.cap.mid {{ top:560px; }}   /* between the stamp banner above and the car below */
.cap-card {{ display:inline-flex; gap:28px; align-items:center; background:rgba(255,255,255,0.97); border-radius:32px;
  padding:36px 44px 40px; box-shadow:0 24px 64px rgba(17,17,17,0.25), 0 2px 6px rgba(17,17,17,0.08); }}
.cap-icon {{ color:{ACCENT}; width:96px; height:96px; border-radius:48px; background:#E8F2FE; display:flex;
  align-items:center; justify-content:center; flex:none; }}
.cap-icon svg {{ width:52px; height:52px; }}
.cap h2 {{ font-size:66px; font-weight:800; letter-spacing:-0.04em; line-height:1.18; }}
.cap h2 .l1 {{ color:{ACCENT}; }}
.legend {{ display:grid; grid-template-columns:repeat(3, auto); gap:14px 26px; margin-top:24px; justify-content:start; }}
.lg {{ display:flex; align-items:center; gap:12px; font-size:32px; font-weight:700; color:{SUB}; }}
.lg i {{ display:block; width:56px; height:16px; border-radius:8px; }}
.stat {{ position:absolute; inset:0; }}
.stat-flood {{ position:absolute; inset:0; background:{ACCENT}; display:flex; align-items:center; justify-content:center; }}
.stat-in {{ text-align:center; color:#FFFFFF; margin-top:-180px; }}
.stat-label {{ font-size:60px; font-weight:700; letter-spacing:-0.035em; opacity:0.92; }}
.stat-value {{ font-size:250px; font-weight:800; letter-spacing:-0.06em; line-height:1.0; font-variant-numeric:tabular-nums; margin:16px 0 22px; }}
.stat-value small {{ font-size:96px; margin-left:12px; letter-spacing:-0.03em; }}
.stat-sub {{ font-size:48px; font-weight:600; opacity:0.88; }}
"""


def title_html(s, t):
    lines = []
    for i, line in enumerate(t["lines"]):
        html = words(line, "tw")
        if t.get("accent") and t["accent"].split(" ")[0] in line:
            for w in t["accent"].split(" "):
                html = html.replace(f'class="tw">{w}<', f'class="tw" style="color:{NIGHT_ACCENT}">{w}<')
        lines.append(f'<div class="tl tl{i}">{html}</div>')
    return f'<div id="{s}-title" class="title">{"".join(lines)}</div>'


def caption_html(s, c, dur):
    icon = f'<div class="cap-icon">{SPEAKER}</div>' if c.get("icon") else ""
    legend = ""
    if c.get("legend"):
        legend = '<div class="legend">' + "".join(
            f'<div class="lg"><i style="background:{col}"></i><b>{n}</b></div>' for n, col in WORN) + "</div>"
    lines = "".join(f'<div class="l{i}">{words(h, "w")}</div>' for i, h in enumerate(c["h"]))
    out = round(dur - FADE - 0.1 if dur > FADE + 1 else dur, 2)
    return (f'<div id="{s}-cap" class="clip cap {c.get("pos", "")}" data-start="{c["at"]}" data-duration="{round(out - c["at"], 2)}">'
            f'<div class="cap-card">{icon}<div class="cap-text"><h2>{lines}</h2>{legend}</div></div></div>')


def stat_html(s, t):
    return (f'<div id="{s}-stat" class="clip stat" data-start="{t["at"]}" data-duration="{round(t["out"] - t["at"], 2)}">'
            f'<div class="stat-flood"><div class="stat-in"><div class="stat-label">{t["label"]}</div>'
            f'<div class="stat-value"><span id="{s}-num">0.00</span><small>{t["unit"]}</small></div>'
            f'<div class="stat-sub">{t["sub"]}</div></div></div></div>')


def shot_scene(s):
    sid = s["id"]
    body, js = [], []
    media = 'data-has-audio="true"' if s.get("audio") else "muted"
    ms = f' data-media-start="{s["ms"]}"' if s.get("ms") else ""
    body.append(f'<div id="{sid}-cam" class="cam" data-layout-allow-overflow><video id="{sid}-video" class="clip" data-start="0" '
                f'data-duration="{s["dur"]}"{ms} src="assets/shots/{s["src"]}.mp4" {media} playsinline></video></div>')
    first = cam_state(s["cam"][0][1])
    js.append(f'tl.fromTo("#{sid}-cam",{json.dumps(first)},{json.dumps(first)},0);')
    for at, c, d in s["cam"][1:]:
        js.append(f'tl.to("#{sid}-cam",{{...{json.dumps(cam_state(c))},duration:{d},ease:"sine.inOut"}},{at});')
    if len(s["cam"]) == 1:  # a held framing still drifts in 5 % over the shot
        cx, cy, z = s["cam"][0][1]
        js.append(f'tl.to("#{sid}-cam",{{...{json.dumps(cam_state((cx, cy, z * 1.05)))},duration:{s["dur"]},ease:"none"}},0);')
    if s.get("whip"):  # the montage: each screen whips up from below over the last
        js.insert(1, f'tl.fromTo("#{sid}-wrap",{{opacity:0,y:260,filter:"blur(10px)"}},{{opacity:1,y:0,filter:"blur(0px)",duration:0.35,ease:"expo.out"}},0);')
    elif s["start"] > 0 and not s.get("cut"):
        js.insert(1, f'tl.fromTo("#{sid}-wrap",{{opacity:0,scale:1.03}},{{opacity:1,scale:1,duration:{FADE},ease:"sine.inOut"}},0);')
    if "title" in s:  # on screen from frame 0: the words are already there and settle with a small push
        body.append(title_html(sid, s["title"]))
        js.append(f'tl.fromTo("#{sid}-title",{{scale:1.06}},{{scale:1,duration:0.6,ease:"expo.out",transformOrigin:"0% 0%"}},0);')
        js.append(f'tl.to("#{sid}-title .tw",{{opacity:0,filter:"blur(8px)",duration:0.2,ease:"power3.in",stagger:0.03}},{s["dur"] - FADE - 0.35});')
    if "cap" in s:
        c = s["cap"]
        body.append(caption_html(sid, c, s["dur"]))
        js.append(f'tl.fromTo("#{sid}-cap .cap-card",{{opacity:0,y:30,filter:"blur(10px)"}},{{opacity:1,y:0,filter:"blur(0px)",duration:0.4,ease:"expo.out"}},{c["at"]});')
        js.append(slam_words(f"#{sid}-cap .w", c["at"] + 0.08, 0.07))
        if c.get("legend"):
            js.append(f'tl.fromTo("#{sid}-cap .lg",{{opacity:0,x:-12}},{{opacity:1,x:0,duration:0.3,ease:"expo.out",stagger:0.08}},{c["at"] + 0.7});')
        if not s.get("whip"):
            js.append(f'tl.to("#{sid}-cap .cap-card",{{opacity:0,filter:"blur(8px)",duration:0.2,ease:"power3.in"}},{s["dur"] - FADE - 0.3});')
    if "stat" in s:
        t = s["stat"]
        body.append(stat_html(sid, t))
        if s.get("cut"):  # the blue rises over the last scene; this shot shows only once the blue covers it
            js.append(f'tl.fromTo("#{sid}-cam",{{opacity:0}},{{opacity:1,duration:0.01}},{t["at"] + 0.42});')
        js.append(f'tl.fromTo("#{sid}-stat .stat-flood",{{clipPath:"inset(100% 0% 0% 0%)"}},{{clipPath:"inset(0% 0% 0% 0%)",duration:0.4,ease:"power3.inOut"}},{t["at"]});')
        js.append(f'tl.fromTo("#{sid}-stat .stat-label, #{sid}-stat .stat-sub",{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,ease:"expo.out",stagger:0.5}},{t["at"] + 0.3});')
        js.append(f'tl.fromTo("#{sid}-stat .stat-value",{{opacity:0,scale:1.3,filter:"blur(16px)"}},{{opacity:1,scale:1,filter:"blur(0px)",duration:0.4,ease:"expo.out"}},{t["at"] + 0.4});')
        js.append(f'const n{sid}={{v:0}}; tl.fromTo(n{sid},{{v:0}},{{v:{t["value"]},duration:1.2,ease:"power3.out",'
                  f'onUpdate:()=>{{document.querySelector("#{sid}-num").textContent=n{sid}.v.toFixed(2);}}}},{t["at"] + 0.4});')
        js.append(f'tl.to("#{sid}-stat .stat-flood",{{clipPath:"inset(0% 0% 100% 0%)",duration:0.4,ease:"power3.inOut"}},{t["out"] - 0.4});')
    return wrap_scene(sid, s["dur"], f'<div id="{sid}-wrap" class="wrap">{"".join(body)}</div>', "\n".join(js),
                      extra_css=f"#{sid}-wrap{{position:absolute;inset:0;background:{'transparent' if s.get('cut') else '#0B0F14'};transform-origin:50% 50%;}}")


def wrap_scene(sid, dur, body, js, extra_css=""):
    return f"""<template>
  <style>{FONT_FACE}{COMMON_CSS}{extra_css}</style>
  <div id="root" data-composition-id="{sid}" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">{body}</div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    {js}
    window.__timelines["{sid}"] = tl;
  </script>
</template>
"""


def end_scene(s):
    sid = s["id"]
    body = f"""<div id="{sid}-flood" class="bg"></div>
<div id="{sid}-bg" class="bg"></div>
<img id="{sid}-icon" class="icon" src="assets/icon.svg" alt="안개지도">
<div id="{sid}-name" class="name">안개지도</div>
<div id="{sid}-en" class="en">FogDrive · for Pleos Connect</div>
<div id="{sid}-line" class="line">{words("달린 만큼, 세상이 밝아집니다", "tw")}</div>
<div id="{sid}-tag" class="tag">{HASHTAG}</div>
<div id="{sid}-credit" class="credit" data-layout-ignore>Music: “Fretless” Kevin MacLeod (incompetech.com) · CC BY 4.0<br>지도 © OpenStreetMap contributors · OpenFreeMap · Pleos Connect Emulator v2.1.0 화면 · 누적 기록은 시연용 예시 · 팀 zobo</div>"""
    css = f"""#{sid}-flood{{position:absolute;inset:0;background:{ACCENT};}}
#{sid}-bg{{position:absolute;inset:0;background:{GROUND};}}
.icon{{position:absolute;left:400px;top:330px;width:280px;height:280px;border-radius:64px;box-shadow:0 30px 70px rgba(10,20,40,0.28);}}
.name{{position:absolute;left:0;right:0;top:680px;text-align:center;font-size:164px;font-weight:800;letter-spacing:-0.055em;line-height:1;}}
.en{{position:absolute;left:0;right:0;top:868px;text-align:center;font-size:40px;font-weight:600;color:{SUB};}}
.line{{position:absolute;left:0;right:0;top:980px;text-align:center;font-size:60px;font-weight:700;letter-spacing:-0.035em;}}
.line .tw:nth-child(n+3){{color:{ACCENT};}}
.tag{{position:absolute;left:50%;top:1110px;transform:translateX(-50%);white-space:nowrap;padding:18px 40px;border-radius:999px;
  background:{ACCENT};color:#FFFFFF;font-size:46px;font-weight:800;letter-spacing:-0.02em;}}
.credit{{position:absolute;left:60px;right:60px;top:1236px;text-align:center;font-size:24px;line-height:1.5;color:#8A93A0;}}"""
    js = f"""tl.fromTo("#{sid}-flood",{{clipPath:"inset(100% 0% 0% 0%)"}},{{clipPath:"inset(0% 0% 0% 0%)",duration:0.4,ease:"power3.inOut"}},0);
tl.fromTo("#{sid}-bg",{{clipPath:"inset(100% 0% 0% 0%)"}},{{clipPath:"inset(0% 0% 0% 0%)",duration:0.45,ease:"power3.inOut"}},0.35);
tl.fromTo("#{sid}-icon",{{scale:2.4,opacity:0,filter:"blur(18px)"}},{{scale:1,opacity:1,filter:"blur(0px)",duration:0.45,ease:"expo.out"}},0.5);
tl.fromTo("#{sid}-name",{{clipPath:"inset(0 100% 0 0)"}},{{clipPath:"inset(0 0% 0 0)",duration:0.5,ease:"power3.out"}},0.8);
tl.fromTo("#{sid}-en",{{opacity:0}},{{opacity:1,duration:0.4,ease:"power2.out"}},1.0);
{tween_words(f"#{sid}-line .tw", 1.3, 0.09)}
tl.fromTo("#{sid}-tag",{{opacity:0,scale:1.4,filter:"blur(12px)"}},{{opacity:1,scale:1,filter:"blur(0px)",duration:0.35,ease:"expo.out"}},1.9);
tl.fromTo("#{sid}-credit",{{opacity:0}},{{opacity:1,duration:0.5}},2.2);"""
    return wrap_scene(sid, s["dur"], body, js, css)


KINDS = dict(shot=shot_scene, end=end_scene)

# Sound on what the picture does (Pixabay library files, no credit needed): (file, start s, volume, offset into file s, length s).
SFX = [
    ("impact-bass-1", 0.0, 0.45, 0, 2.1),            # frame 0: the question is already there
    ("whoosh-short", 3.9, 0.3, 0, 0.57),
    ("whoosh-short", 18.9, 0.25, 0, 0.57),
    ("whoosh", 23.9, 0.35, 0, 0.57),                  # the blue floods up
    ("impact-bass-1", 24.4, 0.45, 0, 2.1),           # the number lands
    ("pop", 25.6, 0.4, 0, 0.72),                      # the count settles on 4.07
    ("whoosh-short", 27.9, 0.3, 0, 0.57),
    ("whoosh", 34.0, 0.3, 0, 0.57),                   # montage whips
    ("whoosh", 36.0, 0.3, 0, 0.57),
    ("whoosh", 38.0, 0.3, 0, 0.57),
    ("whoosh", 40.0, 0.3, 0, 0.57),
    ("impact-bass-1", 42.5, 0.55, 0, 2.1),           # the icon lands
    ("pop", 43.9, 0.35, 0, 0.72),                     # the hashtag
    ("sparkle", 44.3, 0.3, 0, 1.8),
]


def sfx_tags():
    tags = []
    for i, (f, at, vol, off, length) in enumerate(SFX):
        media = f' data-media-start="{off}"' if off else ""
        tags.append(f'      <audio id="sfx{i}" class="clip" data-start="{at}" data-duration="{length}"{media} data-track-index="{10 + i % 3}" '
                    f'data-volume="{vol}" src="assets/sfx/{f}.mp3"></audio>')
    return "\n".join(tags)


def index_html():
    hosts = []
    for i, s in enumerate(SCENES):
        hosts.append(f'      <div id="{s["id"]}" class="clip" data-composition-id="{s["id"]}" data-composition-src="compositions/{s["id"]}.html" '
                     f'data-start="{s["start"]}" data-duration="{s["dur"]}" data-track-index="{i % 2 + 1}" data-track-kind="graphics" '
                     f'data-width="{W}" data-height="{H}" style="z-index:{i + 1}"></div>')
    # Music: full from frame 0, dipped under the one spoken line (voice scene 9-15), faded out over the last 2 s.
    duck = {"version": 1, "lanes": [{"target": "volume", "points": [
        {"t": 0, "v": 0.9}, {"t": 9.0, "v": 0.9}, {"t": 9.4, "v": 0.2}, {"t": 14.6, "v": 0.2},
        {"t": 15.2, "v": 0.9}, {"t": TOTAL - 2.5, "v": 0.9}, {"t": TOTAL - 0.1, "v": 0.0}]}]}
    return f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width={W}, height={H}" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: {W}px; height: {H}px; overflow: hidden; background: {GROUND}; }}
      #root {{ position: relative; width: 100%; height: 100%; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL}" data-width="{W}" data-height="{H}">
{chr(10).join(hosts)}
{sfx_tags()}
      <audio id="bgm" class="clip" data-start="0" data-duration="{TOTAL}" data-track-index="9" data-volume="1"
        data-automation='{json.dumps(duck)}' src="assets/music/fretless.mp3"></audio>
    </div>
    <script>
      window.__timelines = window.__timelines || {{}};
      window.__timelines["main"] = gsap.timeline({{ paused: true }});
    </script>
  </body>
</html>
"""


def link_assets():
    """assets/ is the promo's (shots, fonts, music, sfx, icon): a symlink, or a copy where links are unavailable."""
    dst, src = os.path.join(HERE, "assets"), os.path.join(PROMO, "assets")
    if os.path.exists(dst):
        return
    try:
        os.symlink(os.path.relpath(src, HERE), dst, target_is_directory=True)
    except OSError:  # Windows without developer mode
        shutil.copytree(src, dst)


def main():
    link_assets()
    os.makedirs(os.path.join(HERE, "compositions"), exist_ok=True)
    for s in SCENES:
        with open(os.path.join(HERE, "compositions", s["id"] + ".html"), "w", encoding="utf-8") as f:
            f.write(KINDS[s["kind"]](s))
    with open(os.path.join(HERE, "index.html"), "w", encoding="utf-8") as f:
        f.write(index_html())
    print(f"{len(SCENES)} scenes, {TOTAL} s")


if __name__ == "__main__":
    main()
