#!/usr/bin/env python3
"""Writes the promo film's HyperFrames compositions from the shot list below.

    python3 tools/demo/promo/build.py      # index.html + compositions/*.html
    cd tools/demo/promo && npx hyperframes@0.8.196 check && npx hyperframes@0.8.196 render

Storyline A (SPEC.md): the first drive on a fresh install lights the map out of the fog, then the same
map after weeks of driving (the demo record) shows what that became. Music "Fretless" (Kevin MacLeod, 100 BPM,
bright guitar and glockenspiel) under the whole film; scenes start on 2-s steps.

A shot is a recording (assets/shots) seen through a camera: (cx, cy, z) puts source pixel (cx, cy) at
the frame's centre with z frame pixels per source pixel. APP = the app's map area, filling the frame.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1920, 1080
APP = (1760, 690, 1.2)   # the app's map area (x 960-2560 of the 2560-px Pleos screen, past the tab rail)

INK = "#111111"
SUB = "#4A5260"
GROUND = "#F6F8FC"
ACCENT = "#0081F8"   # the app's day accent (the car's position arrow)
NIGHT_ACCENT = "#3D9BFF"
AMBER = "#EF8A00"
WORN = [("2회", "#0081F8"), ("3회", "#0071E0"), ("5회", "#3D3AE8"), ("10회", "#8E2DC8"), ("20회", "#F07A00"), ("50회", "#E03030")]

# ---------------------------------------------------------------------------------------------- film

SCENES = []


def scene(sid, start, dur, **kw):
    SCENES.append(dict(id=sid, start=start, dur=dur, **kw))


# Hook (0-8): the very first seconds of the first drive. Everything is fog; a road clears behind the car.
scene("hook", 0, 8, kind="shot", src="hook", cam=[(0, APP), (3.0, (1700, 690, 1.4), 2.0)],
      title=dict(lines=["모든 길은,", "처음엔 안개였습니다"], at=0.6, out=7.4, pos="top-left", dark=True, accent="안개"))
# Tension (8-14): zoomed out, the whole map is still fog.
scene("fog", 8, 6, kind="shot", src="fogwide", cam=[(0, (1760, 700, 1.3))],
      title=dict(lines=["익숙한 길 너머,", "아직 만나지 못한 동네들"], at=0.5, out=5.6, pos="top-left", dark=True))
# Reveal (14-20): the product, on its own ground.
scene("reveal", 14, 6, kind="brand")
# Proof (20-62): one drive, from the first suggestion to the summary.
scene("detour", 20, 6, kind="shot", src="detour", cam=[(0, APP), (0.6, (1630, 900, 1.45), 1.2)],
      cap=dict(h="오늘은, 조금 다른 길로 가 볼까요?", s="출발 전, 가 보지 않은 동네를 지나는 길을 추천해 드려요", at=1.4, out=5.7, pos="top-left"))
scene("drive", 26, 10, kind="shot", src="drive", cam=[(0, APP), (2.5, (1700, 690, 1.3), 2.0)],
      cap=dict(h="달리는 모든 순간이, 지도가 됩니다", s="지나온 길을 따라 안개가 걷히고, 나만의 지도가 그려집니다", at=1.0, out=9.4))
scene("voice", 36, 6, kind="shot", src="voice", audio=True, cam=[(0, (1700, 690, 1.25))],
      cap=dict(h="처음 만나는 동네가, 먼저 인사합니다", s="새로운 동네에 들어서면 음성으로 알려 드려요", at=0.3, out=5.7, icon="speaker"))
scene("signals", 42, 6, kind="shot", src="signals", cam=[(0, APP), (0.6, (1700, 500, 1.4), 1.2)],
      cap=dict(h="창문을 연 그 순간까지, 기억합니다", s="날씨, 동승자, 창문… 차가 아는 순간이 스탬프가 됩니다", at=1.4, out=5.7))
scene("arrive", 48, 8, kind="shot", src="arrive", cam=[(0, APP), (3.4, (1650, 920, 1.4), 1.2)],
      cap=dict(h="도착하면, 오늘의 여정을 한 장에", s="새로 밝힌 길과 처음 가 본 동네, 그 동네의 이야기까지", at=4.4, out=7.7, pos="top-left"))
scene("first", 56, 6, kind="shot", src="firstpath", cam=[(0, APP)],
      stat=dict(label="첫 드라이브가 밝힌 세상", value="4.07", unit="km²", sub="35.8 km · 처음 가 본 동네 44곳", at=1.0, out=5.7))
# Payoff (62-96): the same map after weeks of everyday driving (the demo record), straight on from the first drive.
scene("worn", 62, 14, kind="shot", src="worn", cam=[(0, APP)],
      cap=dict(h="자주 다닌 길에는, 나의 일상이 색으로", s="반복해 달린 횟수만큼 길의 색이 깊어집니다", at=1.0, out=13.4, legend=True))
scene("lapse", 76, 8, kind="shot", src="timelapse", cam=[(0, APP), (0.8, (1700, 640, 1.25), 1.2)],
      cap=dict(h="지나온 모든 길을, 다시 한 번", s="첫 드라이브부터 오늘까지, 지도가 밝아진 순간들", at=1.6, out=7.7, pos="top-left"))
scene("record", 84, 4, kind="shot", whip=True, src="record", cam=[(0, (1720, 620, 1.2))],
      cap=dict(h="매주, 매달 쌓여 가는 나의 기록", s="지난주보다 얼마나 더 멀리 갔는지 한눈에", at=0.4, out=3.8))
scene("dex", 88, 4, kind="shot", whip=True, src="dex", cam=[(0, (1720, 600, 1.2))],
      cap=dict(h="동네 하나하나, 채워 가는 즐거움", s="시·도부터 읍·면·동까지, 나만의 지역 도감", at=0.4, out=3.8))
scene("stamps", 92, 4, kind="shot", whip=True, src="stamps", cam=[(0, (1760, 700, 1.2))],
      cap=dict(h="그날의 날씨와 동승자까지", s="차량과 함께 모으는 29가지 스탬프", at=0.4, out=3.8))
# Claim and end card (96-106).
scene("claim", 96, 4, kind="claim")
scene("end", 100, 6, kind="end")
TOTAL = 106

# Every scene but the last runs FADE s past the next one's start, which fades in over it.
FADE = 0.6
for _s in SCENES[:-1]:
    _s["dur"] = round(_s["dur"] + FADE, 2)

# ---------------------------------------------------------------------------------------------- html

FONT_FACE = "".join(
    f"@font-face{{font-family:'Pretendard';src:url('assets/fonts/Pretendard-{n}.woff2') format('woff2');font-weight:{w};}}"
    for n, w in (("Regular", 400), ("SemiBold", 600), ("Bold", 700), ("ExtraBold", 800)))

SPEAKER = ('<svg viewBox="0 0 24 24" width="44" height="44"><path fill="currentColor" d="M3 9v6h4l5 5V4L7 9H3z"/>'
           '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M16 8.5a5 5 0 0 1 0 7M19 5.5a9 9 0 0 1 0 13"/></svg>')


def cam_state(c):
    cx, cy, z = c
    return dict(x=round(W / 2 - cx * z, 1), y=round(H / 2 - cy * z, 1), scale=z)


def words(text, cls):
    return " ".join(f'<span class="{cls}">{w}</span>' for w in text.split(" "))


def caption_html(s, c):
    pos = c.get("pos", "bottom-left")
    icon = f'<div class="cap-icon">{SPEAKER}</div>' if c.get("icon") == "speaker" else ""
    legend = ""
    if c.get("legend"):
        legend = '<div class="legend">' + "".join(
            f'<div class="lg"><i style="background:{col}"></i><b>{n}</b></div>' for n, col in WORN) + "</div>"
    return (f'<div id="{s}-cap" class="clip cap {pos}" data-start="{c["at"]}" data-duration="{round(c["out"] - c["at"], 2)}">'
            f'<div class="cap-card">{icon}<div class="cap-text"><h2>{words(c["h"], "w")}</h2>'
            f'<p>{c["s"]}</p>{legend}</div></div></div>')


def title_html(s, t):
    lines = []
    for i, line in enumerate(t["lines"]):
        html = words(line, "tw")
        if t.get("accent") and t["accent"] in line:
            html = html.replace(f'>{t["accent"]}', f' style="color:{NIGHT_ACCENT if t.get("dark") else ACCENT}">{t["accent"]}')
        lines.append(f'<div class="tl tl{i}">{html}</div>')
    tone = "on-dark" if t.get("dark") else ""
    return (f'<div id="{s}-title" class="clip title {t["pos"]} {tone}" data-start="{t["at"]}" '
            f'data-duration="{round(t["out"] - t["at"], 2)}">{"".join(lines)}</div>')


def stat_html(s, t):
    return (f'<div id="{s}-stat" class="clip stat" data-start="{t["at"]}" data-duration="{round(t["out"] - t["at"], 2)}">'
            f'<div class="stat-flood"><div class="stat-in"><div class="stat-label">{t["label"]}</div>'
            f'<div class="stat-value"><span id="{s}-num">0.00</span><small>{t["unit"]}</small></div>'
            f'<div class="stat-sub">{t["sub"]}</div></div></div></div>')


COMMON_CSS = f"""
#root {{ position:absolute; inset:0; overflow:hidden; font-family:'Pretendard',sans-serif; color:{INK}; }}
.cam {{ position:absolute; left:0; top:0; width:2560px; height:1440px; transform-origin:0 0; }}
.cam video {{ position:absolute; left:0; top:0; width:2560px; height:1440px; }}
.cap {{ position:absolute; }}
.cap.bottom-left {{ left:96px; bottom:96px; }}
.cap.top-left {{ left:96px; top:96px; }}
.cap.top-right {{ right:96px; top:96px; }}
.cap-card {{ display:flex; gap:28px; align-items:center; background:rgba(255,255,255,0.97); border-radius:28px;
  padding:34px 44px 36px; box-shadow:0 24px 64px rgba(17,17,17,0.22), 0 2px 6px rgba(17,17,17,0.08); max-width:1100px; }}
.cap-icon {{ color:{ACCENT}; width:84px; height:84px; border-radius:42px; background:#E8F2FE; display:flex;
  align-items:center; justify-content:center; flex:none; }}
.cap h2 {{ font-size:56px; font-weight:800; letter-spacing:-0.035em; line-height:1.15; }}
.cap p {{ margin-top:12px; font-size:30px; font-weight:600; color:{SUB}; letter-spacing:-0.02em; }}
.legend {{ display:flex; gap:18px; margin-top:22px; }}
.lg {{ display:flex; align-items:center; gap:10px; font-size:26px; font-weight:700; color:{SUB}; }}
.lg i {{ display:block; width:44px; height:14px; border-radius:7px; }}
.title {{ position:absolute; }}
.tw {{ display:inline-block; transform-origin:50% 60%; }}
.title.top-left {{ left:120px; top:120px; }}
.title .tl {{ font-size:96px; font-weight:800; letter-spacing:-0.045em; line-height:1.12; }}
.title.on-dark {{ color:#FFFFFF; text-shadow:0 2px 6px rgba(0,0,0,0.35), 0 8px 40px rgba(0,0,0,0.55); }}
.stat {{ position:absolute; inset:0; }}
.stat-flood {{ position:absolute; inset:0; background:{ACCENT}; display:flex; align-items:center; justify-content:center; }}
.stat-in {{ text-align:center; color:#FFFFFF; }}
.stat-label {{ font-size:52px; font-weight:700; letter-spacing:-0.03em; opacity:0.9; }}
.stat-value {{ font-size:300px; font-weight:800; letter-spacing:-0.06em; line-height:1.0; font-variant-numeric:tabular-nums; margin:10px 0 18px; }}
.stat-value small {{ font-size:120px; margin-left:18px; letter-spacing:-0.03em; }}
.stat-sub {{ font-size:40px; font-weight:600; opacity:0.85; }}
"""


def tween_words(sel, at, stagger=0.067):
    return (f'tl.fromTo("{sel}",{{opacity:0,filter:"blur(8px)"}},{{opacity:1,filter:"blur(0px)",duration:0.2,'
            f'ease:"power4.out",stagger:{stagger}}},{at});')


def slam_words(sel, at, stagger=0.1):
    """Headline words landing hard: from 125% and blurred to rest in 0.32 s, each its own hit."""
    return (f'tl.fromTo("{sel}",{{opacity:0,scale:1.25,filter:"blur(14px)"}},{{opacity:1,scale:1,filter:"blur(0px)",'
            f'duration:0.32,ease:"expo.out",stagger:{stagger}}},{at});')


def shot_scene(s):
    sid = s["id"]
    body, js = [], []
    media = f'data-has-audio="true"' if s.get("audio") else "muted"
    body.append(f'<div id="{sid}-cam" class="cam"><video id="{sid}-video" class="clip" data-start="0" '
                f'data-duration="{s["dur"]}" src="assets/shots/{s["src"]}.mp4" {media} playsinline></video></div>')
    first = cam_state(s["cam"][0][1])
    js.append(f'tl.fromTo("#{sid}-cam",{json.dumps(first)},{json.dumps(first)},0);')
    for at, c, *d in s["cam"][1:]:
        st = cam_state(c)
        js.append(f'tl.to("#{sid}-cam",{{...{json.dumps(st)},duration:{d[0] if d else 0.6},ease:"sine.inOut"}},{at});')
    if s.get("whip"):  # the montage: each screen whips in from the right over the last
        js.insert(1, f'tl.fromTo("#{sid}-wrap",{{opacity:0,x:220,filter:"blur(10px)"}},{{opacity:1,x:0,filter:"blur(0px)",duration:0.4,ease:"expo.out"}},0);')
    elif s["start"] > 0:  # crossfade over the scene still showing below (scenes overlap by FADE s)
        js.insert(1, f'tl.fromTo("#{sid}-wrap",{{opacity:0,scale:1.02}},{{opacity:1,scale:1,duration:{FADE},ease:"sine.inOut"}},0);')
    if len(s["cam"]) == 1:  # a held framing still drifts in 4 % over the shot
        cx, cy, z = s["cam"][0][1]
        js.append(f'tl.to("#{sid}-cam",{{...{json.dumps(cam_state((cx, cy, z * 1.04)))},duration:{s["dur"]},ease:"none"}},0);')
    if "cap" in s:
        c = s["cap"]
        body.append(caption_html(sid, c))
        js.append(f'tl.fromTo("#{sid}-cap .cap-card",{{opacity:0,y:24,filter:"blur(10px)"}},{{opacity:1,y:0,filter:"blur(0px)",duration:0.45,ease:"expo.out"}},{c["at"]});')
        js.append(tween_words(f"#{sid}-cap .w", c["at"] + 0.12))
        js.append(f'tl.fromTo("#{sid}-cap p, #{sid}-cap .legend",{{opacity:0}},{{opacity:1,duration:0.3,ease:"power2.out"}},{c["at"] + 0.45});')
        if c.get("legend"):
            js.append(f'tl.fromTo("#{sid}-cap .lg",{{opacity:0,x:-12}},{{opacity:1,x:0,duration:0.3,ease:"expo.out",stagger:0.08}},{c["at"] + 0.7});')
        js.append(f'tl.to("#{sid}-cap .cap-card",{{opacity:0,filter:"blur(8px)",duration:0.22,ease:"power3.in"}},{c["out"] - 0.25});')
    if "title" in s:
        t = s["title"]
        body.append(title_html(sid, t))
        js.append(slam_words(f"#{sid}-title .tl0 .tw", t["at"], 0.1))
        js.append(slam_words(f"#{sid}-title .tl1 .tw", t["at"] + 0.8, 0.1))
        js.append(f'tl.to("#{sid}-title .tw",{{opacity:0,filter:"blur(8px)",duration:0.2,ease:"power3.in",stagger:0.03}},{t["out"] - 0.35});')
    if "stat" in s:
        t = s["stat"]
        body.append(stat_html(sid, t))
        js.append(f'tl.fromTo("#{sid}-stat .stat-flood",{{clipPath:"inset(100% 0% 0% 0%)"}},{{clipPath:"inset(0% 0% 0% 0%)",duration:0.5,ease:"power3.inOut"}},{t["at"]});')
        js.append(f'tl.fromTo("#{sid}-stat .stat-label, #{sid}-stat .stat-sub",{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,ease:"expo.out",stagger:0.5}},{t["at"] + 0.35});')
        js.append(f'tl.fromTo("#{sid}-stat .stat-value",{{opacity:0,scale:1.3,filter:"blur(16px)"}},{{opacity:1,scale:1,filter:"blur(0px)",duration:0.4,ease:"expo.out"}},{t["at"] + 0.45});')
        js.append(f'const n{sid}={{v:0}}; tl.fromTo(n{sid},{{v:0}},{{v:{t["value"]},duration:1.4,ease:"power3.out",'
                  f'onUpdate:()=>{{document.querySelector("#{sid}-num").textContent=n{sid}.v.toFixed(2);}}}},{t["at"] + 0.45});')
        js.append(f'tl.to("#{sid}-stat .stat-flood",{{clipPath:"inset(0% 0% 100% 0%)",duration:0.45,ease:"power3.inOut"}},{t["out"] - 0.5});')
    inner = "".join(body)
    return wrap_scene(sid, s["dur"], f'<div id="{sid}-wrap" class="wrap">{inner}</div>', "\n".join(js),
                      extra_css=f"#{sid}-wrap{{position:absolute;inset:0;background:#0B0F14;transform-origin:50% 50%;}}")


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


def brand_scene(s):
    sid = s["id"]
    body = f"""
<div id="{sid}-flood" class="bg"></div>
<div id="{sid}-bg" class="bg"></div>
<div id="{sid}-lock" class="lock">
  <img id="{sid}-icon" class="icon" src="assets/icon.svg" alt="안개지도">
  <div class="names"><div id="{sid}-name" class="name">안개지도</div><div id="{sid}-en" class="en">FogDrive · for Pleos Connect</div></div>
</div>
<div id="{sid}-line" class="line">{words("달린 만큼, 세상이 밝아집니다", "tw")}</div>"""
    css = f"""
#{sid}-flood{{position:absolute;inset:0;background:{ACCENT};}}
#{sid}-bg{{position:absolute;inset:0;background:{GROUND};}}
.lock{{position:absolute;left:0;right:0;top:330px;display:flex;align-items:center;justify-content:center;gap:48px;}}
.icon{{width:220px;height:220px;border-radius:52px;box-shadow:0 30px 70px rgba(10,20,40,0.28);}}
.name{{font-size:150px;font-weight:800;letter-spacing:-0.055em;line-height:1;}}
.en{{font-size:38px;font-weight:600;color:{SUB};margin-top:16px;letter-spacing:-0.01em;}}
.line{{position:absolute;left:0;right:0;top:700px;text-align:center;font-size:58px;font-weight:700;letter-spacing:-0.035em;}}
.line .tw:nth-child(n+3){{color:{ACCENT};}}"""
    js = f"""
tl.fromTo("#{sid}-flood",{{clipPath:"circle(0% at 50% 50%)"}},{{clipPath:"circle(80% at 50% 50%)",duration:0.45,ease:"power3.in"}},0);
tl.fromTo("#{sid}-bg",{{clipPath:"circle(0% at 50% 50%)"}},{{clipPath:"circle(80% at 50% 50%)",duration:0.5,ease:"expo.out"}},0.5);
tl.fromTo("#{sid}-icon",{{scale:2.4,opacity:0,filter:"blur(18px)"}},{{scale:1,opacity:1,filter:"blur(0px)",duration:0.45,ease:"expo.out"}},0.55);
tl.fromTo("#{sid}-name",{{clipPath:"inset(0 100% 0 0)"}},{{clipPath:"inset(0 0% 0 0)",duration:0.5,ease:"power3.out"}},0.9);
tl.fromTo("#{sid}-en",{{opacity:0}},{{opacity:1,duration:0.4,ease:"power2.out"}},1.1);
{tween_words(f"#{sid}-line .tw", 1.6, 0.09)}
tl.to("#{sid}-icon",{{scale:9,opacity:0,duration:0.5,ease:"power3.in"}},5.45);
tl.to("#{sid}-name, #{sid}-en, #{sid}-line",{{opacity:0,filter:"blur(8px)",duration:0.25,ease:"power3.in"}},5.3);"""
    return wrap_scene(sid, s["dur"], body, js, css)


def claim_scene(s):
    sid = s["id"]
    body = f"""<div id="{sid}-bg" class="bg"></div>
<div id="{sid}-a" class="cl a">{words("길 위의 모든 순간을,", "tw")}</div>
<div id="{sid}-b" class="cl b">{words("당신만의 지도로", "tw")}</div>"""
    css = f"""#{sid}-bg{{position:absolute;inset:0;background:{ACCENT};}}
.cl{{position:absolute;left:0;right:0;text-align:center;font-size:132px;font-weight:800;letter-spacing:-0.05em;color:#FFFFFF;}}
.cl.a{{top:330px;opacity:0.92;}} .cl.b{{top:510px;}}"""
    js = f"""tl.fromTo("#{sid}-bg",{{clipPath:"inset(0 100% 0 0)"}},{{clipPath:"inset(0 0% 0 0)",duration:0.5,ease:"power2.inOut"}},0);
{slam_words(f"#{sid}-a .tw", 0.45, 0.12)}
{slam_words(f"#{sid}-b .tw", 1.3, 0.14)}
tl.to("#{sid}-a .tw, #{sid}-b .tw",{{opacity:0,filter:"blur(8px)",duration:0.2,ease:"power3.in",stagger:0.03}},3.55);"""
    return wrap_scene(sid, s["dur"], body, js, css)


def end_scene(s):
    sid = s["id"]
    body = f"""<div id="{sid}-bg" class="bg"></div>
<div id="{sid}-lock" class="lock">
  <img id="{sid}-icon" class="icon" src="assets/icon.svg" alt="안개지도">
  <div class="names"><div id="{sid}-name" class="name">안개지도</div><div id="{sid}-en" class="en">FogDrive</div></div>
</div>
<div id="{sid}-cta" class="cta">달린 만큼, 세상이 밝아집니다 · Pleos Connect · 팀 zobo</div>
<div id="{sid}-credit" class="credit">Music: “Fretless” Kevin MacLeod (incompetech.com) · CC BY 4.0　|　지도 © OpenStreetMap contributors · OpenFreeMap　|　Pleos Connect Emulator v2.1.0 화면 · 누적 기록은 시연용 예시</div>"""
    css = f"""#{sid}-bg{{position:absolute;inset:0;background:{GROUND};}}
.lock{{position:absolute;left:0;right:0;top:360px;display:flex;align-items:center;justify-content:center;gap:44px;}}
.icon{{width:200px;height:200px;border-radius:46px;box-shadow:0 30px 70px rgba(10,20,40,0.28);}}
.name{{font-size:140px;font-weight:800;letter-spacing:-0.055em;line-height:1;}}
.en{{font-size:44px;font-weight:700;color:{ACCENT};margin-top:10px;letter-spacing:-0.01em;}}
.cta{{position:absolute;left:0;right:0;top:680px;text-align:center;font-size:40px;font-weight:600;color:{SUB};}}
.credit{{position:absolute;left:0;right:0;bottom:70px;text-align:center;font-size:20px;color:#8A93A0;}}"""
    js = f"""tl.fromTo("#{sid}-bg",{{opacity:0}},{{opacity:1,duration:0.6,ease:"sine.inOut"}},0);
tl.fromTo("#{sid}-icon",{{scale:0.5,opacity:0}},{{scale:1,opacity:1,duration:0.8,ease:"back.out(1.5)"}},0.4);
tl.fromTo("#{sid}-name",{{clipPath:"inset(0 100% 0 0)"}},{{clipPath:"inset(0 0% 0 0)",duration:0.6,ease:"power3.out"}},0.6);
tl.fromTo("#{sid}-en",{{opacity:0,y:10}},{{opacity:1,y:0,duration:0.4,ease:"expo.out"}},1.0);
tl.fromTo("#{sid}-cta",{{opacity:0}},{{opacity:1,duration:0.5}},1.4);
tl.fromTo("#{sid}-credit",{{opacity:0}},{{opacity:1,duration:0.5}},1.8);"""
    return wrap_scene(sid, s["dur"], body, js, css)


KINDS = dict(shot=shot_scene, brand=brand_scene, claim=claim_scene, end=end_scene)


# Sound on what the picture does (Pixabay library files, no credit needed): (file, start s, volume, offset into file s, length s).
SFX = [
    ("impact-bass-1", 0.6, 0.45, 0, 2.1),            # the opening line lands
    ("riser", 10.6, 0.5, 6.0, 4.0),                   # builds into the brand reveal
    ("impact-bass-1", 14.55, 0.7, 0, 2.1),           # the icon lands
    ("whoosh-short", 19.9, 0.3, 0, 0.57),
    ("whoosh-short", 25.9, 0.25, 0, 0.57),
    ("whoosh-short", 47.9, 0.25, 0, 0.57),
    ("whoosh", 56.9, 0.35, 0, 0.57),                  # the blue floods up
    ("impact-bass-1", 57.45, 0.45, 0, 2.1),          # the number lands
    ("pop", 58.95, 0.4, 0, 0.72),                     # the count settles on 4.07
    ("whoosh-short", 61.9, 0.3, 0, 0.57),
    ("whoosh", 84.0, 0.35, 0, 0.57),                  # montage whips
    ("whoosh", 88.0, 0.35, 0, 0.57),
    ("whoosh", 92.0, 0.35, 0, 0.57),
    ("impact-bass-1", 96.45, 0.55, 0, 2.1),          # the closing line
    ("sparkle", 100.5, 0.35, 0, 1.8),                 # the end card
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
    # Music: full bed, dipped under the one spoken line (voice scene 36-42), faded out under the end card.
    duck = {"version": 1, "lanes": [{"target": "volume", "points": [
        {"t": 0, "v": 0.0}, {"t": 0.8, "v": 0.9}, {"t": 36.0, "v": 0.9}, {"t": 36.4, "v": 0.2}, {"t": 41.6, "v": 0.2},
        {"t": 42.2, "v": 0.9}, {"t": TOTAL - 4, "v": 0.9}, {"t": TOTAL - 0.2, "v": 0.0}]}]}
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


def main():
    os.makedirs(os.path.join(HERE, "compositions"), exist_ok=True)
    for s in SCENES:
        with open(os.path.join(HERE, "compositions", s["id"] + ".html"), "w", encoding="utf-8") as f:
            f.write(KINDS[s["kind"]](s))
    with open(os.path.join(HERE, "index.html"), "w", encoding="utf-8") as f:
        f.write(index_html())
    print(f"{len(SCENES)} scenes, {TOTAL} s")


if __name__ == "__main__":
    main()
