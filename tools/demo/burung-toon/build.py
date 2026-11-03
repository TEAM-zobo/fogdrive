#!/usr/bin/env python3
"""부릉탐험대 캐릭터 숏폼 (1080×1920, 30 fps, 48.7초): 장면 목록 → index.html + compositions/*.html.

    python3 build.py
    npx hyperframes@0.8.196 check
    npx hyperframes@0.8.196 snapshot --at 1.5,5,9,13,15.5,18.5,23,27,31,33.5,36.5,40.5
    npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-toon.mp4
    ffmpeg -i renders/burung-toon.mp4 -c:v copy -af "volume=2.8dB,alimiter=limit=0.7:level=disabled:attack=1" -c:a aac -b:a 192k renders/burung-toon-final.mp4
    # 음량: 렌더 -19.6 → -16.8 LUFS, 피크 -3.1 dBFS (ffmpeg -af ebur128=peak=true로 측정)

docs/submission/shortform.md 가이드의 10장면 + 핵심 기능 1장면: 훅(하루 30.8km, 남는 것은?) → 문제(내비 끝나면 기록 0, 지루한 아이)
→ 앱 등장 → 달리면 지나간 길이 지도에 남음(실제 주행 녹화 assets/shots/drive.mp4) → 출퇴근 → 「프로 출근러」 → 주말 여행 → 「주말 원정대」 → 도감으로 다음 여행지 → 우회 제안
→ 키즈모드 탐험대원 변신 → 도착 '오늘은 함께 탐험했어요!' → 엔딩(당신의 칭호는? 댓글로!).

캐릭터(아빠 A씨·엄마·아이 도현·빨간 차)는 이 파일의 SVG 함수로 그립니다. 모든 장면이 같은 함수를 쓰므로 얼굴·옷·색이
같습니다. 앱 화면(assets/shots)은 tools/demo/out/raw-burung의 2026-11-02 에뮬레이터 녹화에서 뽑은 정지 화면
(앱 영역 1705×1210)과 docs/images/burung/form/01-boot.png입니다. 주행 중 장면에는 지도 화면만 씁니다.
글자는 화면 위 168–1330 px 안에만 둡니다(그 아래는 플랫폼 UI). 편집은 이 파일만 고칩니다.

assets/(글꼴·음악·앱 화면·주행 클립)는 git에 없습니다. Release burung-media-v2의 burung-toon-assets.zip을 이 폴더에 풉니다:
    gh release download burung-media-v2 --pattern burung-toon-assets.zip && unzip -o burung-toon-assets.zip
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1080, 1920
INK = "#15192A"
MINT = "#12B886"
MINT_D = "#0B7A5A"
YELLOW = "#FFD43B"
CORAL = "#FF6B4A"
SKY = "#D9F1FF"
CREAM = "#F6F3EC"
SKIN = "#F7CBA6"
BLUSH = "#FF9E8A"
IMG_W, IMG_H = 1705, 1210
DX, DY, DW = 110, 500, 860                  # car display (bezel) on screen
IW = DW - 32                               # picture inside the bezel
IH = round(IW * IMG_H / IMG_W)
DH = IH + 32
FULL = (IMG_W / 2, IMG_H / 2, IW / IMG_W)
XF = 0.25

FONTS = "".join(
    f"@font-face{{font-family:'Pretendard';src:url('assets/fonts/Pretendard-{n}.woff2') format('woff2');font-weight:{w};}}"
    for n, w in (("Regular", 400), ("SemiBold", 600), ("Bold", 700), ("ExtraBold", 800))
)

BASE_CSS = FONTS + f"""
#root {{ position:absolute; inset:0; overflow:hidden; font-family:'Pretendard',sans-serif; color:{INK}; }}
.bg {{ position:absolute; inset:0; }}
.dots {{ position:absolute; inset:0; background-image:radial-gradient(rgba(21,25,42,0.07) 3px, transparent 3.5px); background-size:44px 44px; }}
.cap {{ position:absolute; left:50px; right:50px; top:190px; text-align:center; }}
.kick {{ display:inline-block; background:{INK}; color:#fff; border-radius:16px; padding:10px 24px; font-size:34px; font-weight:800; letter-spacing:-0.02em; }}
.line {{ font-size:78px; font-weight:800; letter-spacing:-0.05em; line-height:1.16; margin-top:18px; word-break:keep-all; }}
.line b {{ background:linear-gradient(transparent 58%, {YELLOW} 58%); }}
.sub {{ font-size:44px; font-weight:700; letter-spacing:-0.04em; line-height:1.25; margin-top:14px; color:#3B4256; word-break:keep-all; }}
.disp {{ position:absolute; left:{DX}px; top:{DY}px; width:{DW}px; height:{DH}px; border-radius:42px; background:#1D2233;
  border:7px solid {INK}; box-shadow:12px 14px 0 {INK}; overflow:hidden; }}
.scr {{ position:absolute; left:9px; top:9px; width:{IW}px; height:{IH}px; border-radius:30px; overflow:hidden; background:#E9EDF2; }}
.shot {{ position:absolute; left:0; top:0; width:{IMG_W}px; height:{IMG_H}px; transform-origin:0 0; }}
.sticker {{ position:absolute; left:0; right:0; text-align:center; }}
.sticker span {{ display:inline-block; background:{CORAL}; color:#fff; border:6px solid {INK}; border-radius:26px; padding:16px 34px;
  font-size:58px; font-weight:800; letter-spacing:-0.04em; box-shadow:8px 10px 0 {INK}; }}
.sticker.y span {{ background:{YELLOW}; color:{INK}; }}
.sticker.m span {{ background:{MINT}; }}
.bubble {{ position:absolute; background:#fff; border:6px solid {INK}; border-radius:34px; padding:18px 30px; font-size:44px; font-weight:800;
  letter-spacing:-0.04em; box-shadow:8px 10px 0 {INK}; word-break:keep-all; }}
.chr {{ position:absolute; }}
"""


# ---------- characters (SVG) ----------

def _eyes_mouth(kind, cx=200, ey=205, my=275, kid=False):
    """Face parts for one expression. kid: rounder, closer eyes."""
    dx = 40 if kid else 46
    l, r = cx - dx, cx + dx
    s = f'stroke="{INK}" stroke-width="9" stroke-linecap="round" fill="none"'
    if kind == "blank":
        return (f'<path d="M{l-16} {ey} L{l+16} {ey}" {s}/><path d="M{r-16} {ey} L{r+16} {ey}" {s}/>'
                f'<circle cx="{l}" cy="{ey+9}" r="6" fill="{INK}"/><circle cx="{r}" cy="{ey+9}" r="6" fill="{INK}"/>'
                f'<path d="M{cx-26} {my} L{cx+26} {my-4}" {s}/>')
    if kind == "sparkle":
        def eye(x):
            return (f'<circle cx="{x}" cy="{ey}" r="25" fill="{INK}"/><circle cx="{x-8}" cy="{ey-9}" r="9" fill="#fff"/>'
                    f'<circle cx="{x+9}" cy="{ey+8}" r="4.5" fill="#fff"/>')
        return eye(l) + eye(r) + f'<path d="M{cx-36} {my-10} Q{cx} {my+40} {cx+36} {my-10} Z" fill="{INK}"/><path d="M{cx-18} {my+12} Q{cx} {my+24} {cx+18} {my+12}" fill="{CORAL}"/>'
    if kind == "happy":
        return (f'<path d="M{l-18} {ey+6} Q{l} {ey-16} {l+18} {ey+6}" {s}/><path d="M{r-18} {ey+6} Q{r} {ey-16} {r+18} {ey+6}" {s}/>'
                f'<path d="M{cx-40} {my-12} Q{cx} {my+44} {cx+40} {my-12} Z" fill="{INK}"/><path d="M{cx-20} {my+12} Q{cx} {my+26} {cx+20} {my+12}" fill="{CORAL}"/>')
    if kind == "yawn":
        return (f'<path d="M{l-16} {ey} Q{l} {ey+12} {l+16} {ey}" {s}/><path d="M{r-16} {ey} Q{r} {ey+12} {r+16} {ey}" {s}/>'
                f'<ellipse cx="{cx}" cy="{my+6}" rx="20" ry="28" fill="{INK}"/>')
    return (f'<ellipse cx="{l}" cy="{ey}" rx="11" ry="15" fill="{INK}"/><ellipse cx="{r}" cy="{ey}" rx="11" ry="15" fill="{INK}"/>'
            f'<path d="M{cx-28} {my-6} Q{cx} {my+20} {cx+28} {my-6}" {s}/>')


def eyes_mouth(kind, cx=200, ey=205, my=275, kid=False, lashes=False):
    out = _eyes_mouth(kind, cx, ey, my, kid)
    if lashes and kind in ("normal", "happy", "sparkle"):
        dx = 40 if kid else 46
        o = 26 if kind == "sparkle" else 14
        s = f'stroke="{INK}" stroke-width="6" stroke-linecap="round"'
        out += (f'<path d="M{cx - dx - o + 4} {ey - 6} L{cx - dx - o - 8} {ey - 14}" {s}/>'
                f'<path d="M{cx + dx + o - 4} {ey - 6} L{cx + dx + o + 8} {ey - 14}" {s}/>')
    return out


def faces(cid, exprs, kid=False, lashes=False):
    out = []
    for k, e in enumerate(exprs):
        op = 1 if k == 0 else 0
        out.append(f'<g id="{cid}-{e}" opacity="{op}">{eyes_mouth(e, kid=kid, ey=215 if kid else 205, my=285 if kid else 275, lashes=lashes)}</g>')
    return "".join(out)


def _union(shapes, fill, w=14):
    """Draw shapes as one outlined silhouette: ink outline layer, then fill layer on top."""
    return (f'<g fill="{INK}" stroke="{INK}" stroke-width="{w}" stroke-linejoin="round">{shapes}</g>'
            f'<g fill="{fill}">{shapes}</g>')


def _finger(cx, base, length, width, angle):
    """A rounded finger standing on (cx, base), tilted by angle (deg, + = clockwise)."""
    return (f'<rect x="{cx - width / 2}" y="{base - length}" width="{width}" height="{length + width / 2}" '
            f'rx="{width / 2}" transform="rotate({angle} {cx} {base})"/>')


HAND_PATHS = {  # Microsoft Fluent Emoji (flat), MIT License, (c) Microsoft Corporation; 32x32 viewBox
    "v": (['M10.2179 11.1711C10.6295 9.95904 11.5803 9.51562 12.6875 9.51562C13.3636 9.51562 13.9815 9.75233 14.4538 10.2023L14.125 4.59376C14.0469 3.84375 14.7438 2.21876 16.4688 2.09376C17.3021 2.02085 19.05 2.40625 19.25 4.40625C19.4428 6.33443 19.6415 10.003 19.7395 11.8134L19.75 12.0078C19.75 12.1485 19.9688 12.3516 20.1485 12.0625L22.125 6.20313C22.4115 5.42188 23.4844 4.02813 25.4844 4.70312C27.4844 5.37812 27.2188 7.59375 26.9063 8.4375L25.125 13.6875C24.6423 15.0422 24.7803 16.6451 25.106 20.4291L25.1094 20.4688C25.4375 24.2812 22.7344 29.9062 15.0469 29.9062C10.5938 29.9062 7.12502 27.1875 6.34377 22.0312C5.83297 18.6602 5.99221 16.0547 5.99221 16.0547H6.00304C5.98048 15.9149 5.96875 15.7687 5.96875 15.6161V13.3682C5.96875 11.5189 7.17199 10.5703 8.65625 10.5703C9.28467 10.5703 9.81509 10.7949 10.2179 11.1711Z'], ['M15.1302 4.92717C15.1359 5.04989 15.1413 5.16675 15.1445 5.27544L15.6698 13.004C15.6844 13.219 15.5609 13.4114 15.375 13.4945V12.6898C15.375 11.599 15.0288 10.7651 14.4787 10.2264L14.308 7.71435L14.125 4.59376C14.0503 3.87699 14.6835 2.36111 16.2454 2.11902L16.2483 2.22284C15.8842 2.23287 15.6721 2.35166 15.5301 2.50333C15.3746 2.66938 15.2618 2.92043 15.1924 3.25274C15.0777 3.80182 15.1063 4.4155 15.1302 4.92717Z', 'M8.65622 18.0625C9.09969 18.0625 9.49436 17.982 9.8298 17.828C9.48133 17.2534 9.17969 16.3916 9.17969 15.5234C9.17969 14.9598 9.20145 14.1944 9.22288 13.4807L9.22854 13.293C9.24806 12.6459 9.26562 12.0635 9.26562 11.7187C9.26562 11.3158 9.32088 10.9679 9.39962 10.6856C9.74843 10.8 10.0511 10.9951 10.2997 11.2515C10.2789 11.3884 10.2656 11.5444 10.2656 11.7187C10.2656 12.0797 10.2476 12.6779 10.2283 13.3171L10.2224 13.5107C10.2009 14.2282 10.1797 14.9777 10.1797 15.5234C10.1797 16.1323 10.3815 16.7581 10.6182 17.1934L10.626 17.1828C11.119 17.8158 11.8595 18.1875 12.6875 18.1875C13.8951 18.1875 14.9167 17.3967 15.2555 16.1578C15.1374 17.9215 14.5677 20 12.6718 20C11.5218 20 10.8281 19.5 10.5138 18.8575C10.1231 19.4697 9.67185 20 8.42185 20C6.4854 20 6.11744 17.6638 6.03064 16.1989C6.29439 17.3855 7.36975 18.0625 8.65622 18.0625Z', 'M18.5938 19.9241C18.9934 20.0827 19.446 20.1954 19.9375 20.25C22.4688 20.5313 23.4687 22.7735 23.4687 23.4688C23.4687 23.9416 23.2181 24.3056 22.8468 24.4568C22.6097 24.5237 22.3807 24.5415 22.1753 24.5024C22.1017 24.486 22.0268 24.4627 21.9511 24.4323C21.677 24.3083 21.468 24.0529 21.375 23.6406C21.1622 22.6968 20.3804 21.2104 18.7714 20.8902C17.9032 21.0969 17.069 21.5697 16.1734 22.2705C15.2608 22.9848 14.3168 23.909 13.2255 24.9816C13.0286 25.1751 12.712 25.1724 12.5185 24.9755C12.3249 24.7785 12.3276 24.462 12.5246 24.2684C13.6052 23.2063 14.5913 22.2389 15.5571 21.483C16.0123 21.1268 16.4701 20.8117 16.9398 20.5521C15.9268 19.9982 15.7188 18.7533 15.7188 18.1562C15.7188 17.375 16.2516 15.8134 18.1249 15.7344L18.1806 15.7322C15.964 17.2662 16.6315 19.1287 18.5632 19.9119C18.572 19.9098 18.5808 19.9078 18.5896 19.9058L18.5938 19.9241Z', 'M20.0882 12.1856L20.1121 12.114C20.1243 12.0989 20.1365 12.0818 20.1485 12.0625L22.125 6.20312C22.3811 5.50478 23.2655 4.31705 24.8788 4.55491C24.2999 4.56661 23.8188 5.02335 23.3944 5.8105C23.0668 6.41797 22.8502 7.0725 22.6779 7.59334C22.6146 7.78471 22.5573 7.95803 22.5026 8.10435L21.0369 12.5019C20.9495 12.7638 20.6664 12.9054 20.4044 12.8181C20.1424 12.7308 20.0008 12.4476 20.0882 12.1856Z']),
}

# where each Fluent hand's wrist is (32x32 units) and which way its forearm runs (dx per unit of dy)
HAND_WRIST = {"v": (16, 29, 0.35)}


def hand(kind, x, y, flip=False, arm=None):
    """A raised hand at (x, y) with its sleeve. Shapes are the Fluent emoji hands (a right hand held out to
    the viewer), so they sit on the viewer's left of a character; flip mirrors one into a left hand.
    kind: v (the only gesture the characters use; the rest is told by their faces)."""
    main, det = HAND_PATHS[kind]
    s = 4.5
    wx, wy, slope = HAND_WRIST[kind]
    shapes = "".join(f'<path d="{d}"/>' for d in main)
    marks = "".join(f'<path d="{d}" fill="#C98A63"/>' for d in det)
    pic = (f'<g fill="{INK}" stroke="{INK}" stroke-width="3.2" stroke-linejoin="round">{shapes}</g>'
           f'<g fill="{SKIN}">{shapes}</g>{marks}')
    sleeve = ""
    if arm:
        # forearm continuing from the wrist, down and in toward the body
        hx, hy = 0, 40                       # wrist position in the figure, relative to (x, y)
        length = 470 - y - hy + 30
        ex, ey = hx + slope * length, hy + length
        # the sleeve starts under the palm so its end is hidden and only the forearm shows below the wrist
        sleeve = (f'<path d="M{hx} {hy - 24} L{ex} {ey}" stroke="{INK}" stroke-width="62" fill="none"/>'
                  f'<path d="M{hx} {hy - 18} L{ex} {ey + 10}" stroke="{arm}" stroke-width="48" fill="none"/>')
    t = f' transform="translate({x} {y}){" scale(-1 1)" if flip else ""}"'
    return f'<g{t}>{sleeve}<g transform="translate({-wx * s} {-wy * s + 40}) scale({s})">{pic}</g></g>'


def _nose(cx, y):
    return f'<path d="M{cx - 7} {y} Q{cx} {y + 10} {cx + 7} {y}" fill="none" stroke="{INK}" stroke-width="6" stroke-linecap="round" opacity="0.55"/>'


def _ear(cx, cy, r, left):
    d = 1 if left else -1
    return (f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{SKIN}" stroke="{INK}" stroke-width="7"/>'
            f'<path d="M{cx + d * 6} {cy - 9} Q{cx - d * 6} {cy} {cx + d * 6} {cy + 9}" fill="none" stroke="{INK}" stroke-width="4" stroke-linecap="round" opacity="0.5"/>')


def dad(cid, exprs=("normal",), hands=()):
    hair, shirt = "#2B2B3A", "#4D8EF7"
    return f"""<svg viewBox="0 0 400 470" xmlns="http://www.w3.org/2000/svg" overflow="visible">
  <path d="M30 470 C30 370 100 336 200 336 C300 336 370 370 370 470 Z" fill="{shirt}" stroke="{INK}" stroke-width="8"/>
  <path d="M160 338 L200 392 L240 338 Z" fill="#fff" stroke="{INK}" stroke-width="7" stroke-linejoin="round"/>
  <rect x="168" y="282" width="64" height="62" fill="{SKIN}" stroke="{INK}" stroke-width="7"/>
  <path d="M172 318 Q200 330 228 318" fill="none" stroke="{INK}" stroke-width="5" opacity="0.25"/>
  {_ear(82, 210, 24, True)}{_ear(318, 210, 24, False)}
  <ellipse cx="200" cy="195" rx="122" ry="132" fill="{SKIN}" stroke="{INK}" stroke-width="8"/>
  <path d="M80 214 C62 120 108 50 196 44 C282 38 346 92 324 214 C318 186 314 160 300 140
           C270 150 226 146 196 124 C176 146 132 156 100 160 C92 176 86 194 80 214 Z" fill="{hair}" stroke="{INK}" stroke-width="7" stroke-linejoin="round"/>
  <path d="M196 124 C214 96 250 78 292 82" fill="none" stroke="#4A4A5E" stroke-width="7" stroke-linecap="round"/>
  <path d="M150 74 C130 90 118 110 112 132" fill="none" stroke="#4A4A5E" stroke-width="6" stroke-linecap="round"/>
  <path d="M130 168 Q154 156 178 164" stroke="{INK}" stroke-width="10" stroke-linecap="round" fill="none"/><path d="M222 164 Q246 156 270 168" stroke="{INK}" stroke-width="10" stroke-linecap="round" fill="none"/>
  <circle cx="122" cy="252" r="18" fill="{BLUSH}" opacity="0.5"/><circle cx="278" cy="252" r="18" fill="{BLUSH}" opacity="0.5"/>
  {_nose(200, 240)}
  {faces(cid, exprs)}
  <clipPath id="{cid}-clip"><rect x="-300" y="-300" width="1000" height="766"/></clipPath><g clip-path="url(#{cid}-clip)">{"".join(hand(*h, arm=shirt) for h in hands)}</g>
</svg>"""


def mom(cid, exprs=("normal",), hands=()):
    hair, shirt = "#6B3E26", "#FF8A65"
    return f"""<svg viewBox="0 0 400 470" xmlns="http://www.w3.org/2000/svg" overflow="visible">
  <path d="M70 320 C34 230 50 70 200 56 C350 70 366 230 330 320 C318 342 290 340 276 326 L124 326 C110 340 82 342 70 320 Z" fill="{hair}" stroke="{INK}" stroke-width="8" stroke-linejoin="round"/>
  <path d="M36 470 C36 372 104 340 200 340 C296 340 364 372 364 470 Z" fill="{shirt}" stroke="{INK}" stroke-width="8"/>
  <path d="M150 344 Q200 380 250 344" fill="none" stroke="{INK}" stroke-width="7"/>
  <rect x="170" y="284" width="60" height="60" fill="{SKIN}" stroke="{INK}" stroke-width="7"/>
  <ellipse cx="200" cy="200" rx="112" ry="124" fill="{SKIN}" stroke="{INK}" stroke-width="8"/>
  <path d="M88 236 C72 120 130 70 204 70 C280 70 330 120 312 236 C306 196 296 170 280 150
           C246 144 206 128 184 100 C170 132 132 160 100 172 C94 190 90 212 88 236 Z" fill="{hair}"/>
  <path d="M312 236 C306 196 296 170 280 150 C246 144 206 128 184 100 C170 132 132 160 100 172 C94 190 90 212 88 236" fill="none" stroke="{INK}" stroke-width="7" stroke-linejoin="round" stroke-linecap="round"/>
  <path d="M190 104 C220 132 256 142 290 146" fill="none" stroke="#8A5537" stroke-width="6" stroke-linecap="round"/>
  <path d="M120 112 C140 88 168 78 196 76" fill="none" stroke="#8A5537" stroke-width="6" stroke-linecap="round"/>
  <path d="M134 172 Q156 162 176 170" stroke="{INK}" stroke-width="7" stroke-linecap="round" fill="none"/><path d="M224 170 Q244 162 266 172" stroke="{INK}" stroke-width="7" stroke-linecap="round" fill="none"/>
  <circle cx="92" cy="262" r="10" fill="{YELLOW}" stroke="{INK}" stroke-width="5"/><circle cx="308" cy="262" r="10" fill="{YELLOW}" stroke="{INK}" stroke-width="5"/>
  <circle cx="126" cy="254" r="18" fill="{BLUSH}" opacity="0.6"/><circle cx="274" cy="254" r="18" fill="{BLUSH}" opacity="0.6"/>
  {_nose(200, 242)}
  {faces(cid, exprs, lashes=True)}
  <clipPath id="{cid}-clip"><rect x="-300" y="-300" width="1000" height="766"/></clipPath><g clip-path="url(#{cid}-clip)">{"".join(hand(*h, arm=shirt) for h in hands)}</g>
</svg>"""


def kid(cid, exprs=("normal",), hands=(), explorer=False):
    hair = "#3A2A20"
    shirt = MINT if explorer else YELLOW
    cape = (f'<path d="M70 360 C40 400 30 450 24 470 L376 470 C370 450 360 400 330 360 Z" fill="{CORAL}" stroke="{INK}" stroke-width="8"/>'
            if explorer else "")
    bangs = (f'<path d="M96 216 C78 120 136 86 200 84 C266 84 324 120 304 216 C300 180 294 160 284 146 '
             f'Q268 172 252 140 Q232 170 210 138 Q190 168 170 140 Q150 168 132 146 C116 166 102 188 96 216 Z" '
             f'fill="{hair}" stroke="{INK}" stroke-width="7" stroke-linejoin="round"/>')
    if explorer:
        top = (bangs +
               f'<path d="M30 132 C40 104 120 98 200 98 C280 98 360 104 370 132 C350 150 270 146 200 146 C130 146 50 150 30 132 Z" fill="#C8A165" stroke="{INK}" stroke-width="8" stroke-linejoin="round"/>'
               f'<path d="M100 128 C96 46 150 26 200 26 C252 26 304 46 300 128 C260 138 140 138 100 128 Z" fill="#D9B77E" stroke="{INK}" stroke-width="8" stroke-linejoin="round"/>'
               f'<path d="M102 108 C150 116 250 116 298 108 L300 128 C250 136 150 136 100 128 Z" fill="{MINT}" stroke="{INK}" stroke-width="5" stroke-linejoin="round"/>'
               f'<path d="M262 62 l9 18 20 3 -14 14 3 20 -18 -9 -18 9 3 -20 -14 -14 20 -3 Z" fill="{YELLOW}" stroke="{INK}" stroke-width="5" stroke-linejoin="round"/>')
    else:
        top = (bangs +
               f'<path d="M200 86 C192 58 210 44 228 52 M206 86 C214 66 232 62 244 70" fill="none" stroke="{INK}" stroke-width="7" stroke-linecap="round"/>'
               f'<path d="M150 104 C134 118 124 132 118 150" fill="none" stroke="#5A4434" stroke-width="6" stroke-linecap="round"/>')
    badge = (f'<circle cx="250" cy="424" r="26" fill="{YELLOW}" stroke="{INK}" stroke-width="6"/><path d="M250 408 l5 10 11 2 -8 8 2 11 -10 -5 -10 5 2 -11 -8 -8 11 -2 Z" fill="{INK}"/>'
             if explorer else "")
    return f"""<svg viewBox="0 0 400 470" xmlns="http://www.w3.org/2000/svg" overflow="visible">
  {cape}
  <path d="M74 470 C74 392 128 360 200 360 C272 360 326 392 326 470 Z" fill="{shirt}" stroke="{INK}" stroke-width="8"/>
  {badge}
  <rect x="176" y="310" width="48" height="54" fill="{SKIN}" stroke="{INK}" stroke-width="7"/>
  {_ear(94, 228, 22, True)}{_ear(306, 228, 22, False)}
  <ellipse cx="200" cy="214" rx="112" ry="110" fill="{SKIN}" stroke="{INK}" stroke-width="8"/>
  {top}
  <path d="M140 178 Q158 170 174 176" stroke="{INK}" stroke-width="7" stroke-linecap="round" fill="none"/><path d="M226 176 Q242 170 260 178" stroke="{INK}" stroke-width="7" stroke-linecap="round" fill="none"/>
  <circle cx="128" cy="262" r="20" fill="{BLUSH}" opacity="0.65"/><circle cx="272" cy="262" r="20" fill="{BLUSH}" opacity="0.65"/>
  {_nose(200, 252)}
  {faces(cid, exprs, kid=True)}
  <clipPath id="{cid}-clip"><rect x="-300" y="-300" width="1000" height="766"/></clipPath><g clip-path="url(#{cid}-clip)">{"".join(hand(*h, arm=shirt) for h in hands)}</g>
</svg>"""


def car_svg():
    """The app icon's little red car with the yellow explorer flag (side view)."""
    return f"""<svg viewBox="0 0 320 210" xmlns="http://www.w3.org/2000/svg" overflow="visible">
  <path d="M196 70 L196 6" stroke="{INK}" stroke-width="7" stroke-linecap="round"/>
  <path d="M196 8 L246 22 L196 38 Z" fill="{YELLOW}" stroke="{INK}" stroke-width="6" stroke-linejoin="round"/>
  <path d="M30 150 C26 110 50 98 80 96 L104 60 C112 50 124 46 138 46 L214 46 C230 46 244 54 252 68 L270 98 C296 102 300 120 298 150 Z"
        fill="#F0483E" stroke="{INK}" stroke-width="8" stroke-linejoin="round"/>
  <path d="M116 66 L138 62 L170 62 L170 96 L100 96 Z" fill="#BFE6FF" stroke="{INK}" stroke-width="6" stroke-linejoin="round"/>
  <path d="M184 62 L214 62 C224 62 232 68 238 78 L248 96 L184 96 Z" fill="#BFE6FF" stroke="{INK}" stroke-width="6" stroke-linejoin="round"/>
  <circle cx="92" cy="156" r="32" fill="#2E3242" stroke="{INK}" stroke-width="7"/><circle cx="92" cy="156" r="12" fill="#C9CED8"/>
  <circle cx="236" cy="156" r="32" fill="#2E3242" stroke="{INK}" stroke-width="7"/><circle cx="236" cy="156" r="12" fill="#C9CED8"/>
  <rect x="276" y="112" width="18" height="12" rx="5" fill="{YELLOW}" stroke="{INK}" stroke-width="4"/>
</svg>"""


def shield(text):
    return f"""<svg viewBox="0 0 300 330" xmlns="http://www.w3.org/2000/svg" overflow="visible">
  <path d="M150 12 L272 54 C272 190 226 270 150 316 C74 270 28 190 28 54 Z" fill="#FFA62B" stroke="{INK}" stroke-width="10" stroke-linejoin="round"/>
  <path d="M150 44 L244 76 C242 180 206 240 150 278 C94 240 58 180 56 76 Z" fill="#FFC857"/>
  <rect x="108" y="128" width="84" height="64" rx="12" fill="#fff" stroke="{INK}" stroke-width="8"/><rect x="132" y="112" width="36" height="20" rx="6" fill="none" stroke="{INK}" stroke-width="8"/>
</svg>"""


# ---------- scenes ----------
# Each scene: id, dur, bg, html (body), js (GSAP lines; t in seconds into the scene).

def cam_xy(cx, cy, s):
    x = IW / 2 - cx * s
    y = IH / 2 - cy * s
    x = min(0, max(IW - IMG_W * s, x))
    y = min(0, max(IH - IMG_H * s, y))
    return round(x, 1), round(y, 1)


def display(sid, shots):
    """shots: [(img, cams [(t, (cx, cy, s), move)], show_at)] → bezel html + js."""
    imgs, js = [], []
    for k, (img, cams, at) in enumerate(shots):
        iid = f"{sid}-s{k}"
        imgs.append(f'<img id="{iid}" class="shot" src="assets/shots/{img}" alt="">')
        if k:
            js.append(f'tl.set("#{iid}",{{opacity:0}},0);tl.to("#{iid}",{{opacity:1,duration:0.2}},{at});')
        for j, (t, (cx, cy, s), mv) in enumerate(cams):
            x, y = cam_xy(cx, cy, s)
            if j == 0 or mv == 0:
                js.append(f'tl.set("#{iid}",{{x:{x},y:{y},scale:{s}}},{t});')
            else:
                js.append(f'tl.to("#{iid}",{{x:{x},y:{y},scale:{s},duration:{mv},ease:"power3.inOut"}},{t});')
    return f'<div id="{sid}-disp" class="disp"><div class="scr">{"".join(imgs)}</div></div>', js


def caption(sid, kick, line, sub=None, at=0.1):
    html = (f'<div id="{sid}-cap" class="cap">' + (f'<span class="kick">{kick}</span>' if kick else "") +
            f'<div class="line">{line}</div>' + (f'<div class="sub">{sub}</div>' if sub else "") + '</div>')
    js = [f'tl.from("#{sid}-cap > *",{{y:40,opacity:0,duration:0.35,stagger:0.08,ease:"back.out(1.8)"}},{at});']
    return html, js


def pop(sel, at, frm=0.3, ease="back.out(2.4)", dur=0.35):
    return f'tl.fromTo("{sel}",{{scale:{frm},opacity:0}},{{scale:1,opacity:1,duration:{dur},ease:"{ease}"}},{at});'


def swap(cid, a, b, at):
    return f'tl.to("#{cid}-{a}",{{opacity:0,duration:0.08}},{at});tl.to("#{cid}-{b}",{{opacity:1,duration:0.08}},{at});'


def chr_box(cid, svg, x, y, w, rot=0):
    return f'<div id="{cid}" class="chr" style="left:{x}px;top:{y}px;width:{w}px;"><div id="{cid}-r" style="transform:rotate({rot}deg)">{svg}</div></div>'


def sticker(sid, text, top, cls="", rot=-4):
    return f'<div id="{sid}" class="sticker {cls}" style="top:{top}px"><span style="transform:rotate({rot}deg)">{text}</span></div>'


def bob(sel, at, n, amp=10, per=0.35):
    return f'tl.to("{sel}",{{y:-{amp},duration:{per},ease:"sine.inOut",yoyo:true,repeat:{n}}},{at});'


SCENES = []


def scene(sid, dur, bg, parts, js):
    SCENES.append(dict(id=sid, dur=dur, bg=bg, html="".join(parts), js=js))


# 1 hook: 하루 30.8km, 남는 것은?
def s_hook():
    sid, d = "hook", 3.6
    road = (f'<svg id="hook-road" viewBox="0 0 1080 900" style="position:absolute;left:0;top:560px;width:1080px;height:900px">'
            f'<path d="M420 0 L660 0 L1080 900 L0 900 Z" fill="#8E96A8"/>'
            f'<g id="hook-dash">' + "".join(f'<rect x="{528 - 2 * k}" y="{k * 120 - 120}" width="{24 + 4 * k}" height="{60 + 8 * k}" rx="8" fill="#fff"/>' for k in range(10)) +
            f'</g></svg>')
    cap, j1 = caption(sid, "매일 운전하는 당신", "하루 평균 <b>30.8km</b><br>운전하는데…", '<span style="font-size:76px;color:#FF6B4A;font-weight:800">남는 것은?</span>')
    odo = f'<div id="hook-odo" class="bubble" style="left:640px;top:600px;font-size:64px;">0.0 km</div>'
    wheel = (f'<svg viewBox="0 0 600 300" style="position:absolute;left:240px;top:1290px;width:600px;">'
             f'<path d="M30 290 A270 270 0 0 1 570 290" fill="none" stroke="{INK}" stroke-width="56" stroke-linecap="round"/>'
             f'<path d="M30 290 A270 270 0 0 1 570 290" fill="none" stroke="#3B4256" stroke-width="36" stroke-linecap="round"/></svg>')
    dad_html = chr_box("hook-dad", dad("hd", ("blank", "normal")), 290, 880, 500)
    js = j1 + [
        'tl.from("#hook-road",{opacity:0,duration:0.2},0);',
        'tl.fromTo("#hook-dash",{y:0},{y:120,duration:0.4,ease:"none",repeat:8},0);',
        'tl.from("#hook-dad",{y:300,duration:0.5,ease:"power3.out"},0.1);',
        pop("#hook-odo", 0.5),
        'const o={v:0};tl.to(o,{v:30.8,duration:1.8,ease:"power2.out",onUpdate:()=>{document.querySelector("#hook-odo").textContent=o.v.toFixed(1)+" km";}},0.6);',
        'tl.to("#hook-dad-r",{rotation:-3,duration:0.5,ease:"sine.inOut",yoyo:true,repeat:5},0.5);',
    ]
    scene(sid, d, f"linear-gradient(180deg,{SKY} 0%,#F4FBFF 40%,{CREAM} 100%)", [road, dad_html, wheel, cap, odo], js)


# 2 problem: 내비 끝나면 기록 0, 아이에겐 기다리는 시간
def s_problem():
    sid, d = "problem", 4.2
    navi = (f'<div id="pb-navi" class="disp" style="background:#1D2233"><div class="scr" style="background:#2A3044;display:flex;align-items:center;justify-content:center;flex-direction:column;">'
            f'<div style="font-size:64px;color:#fff;font-weight:800;letter-spacing:-0.04em">🏁</div>'.replace("🏁", "") +
            f'<svg viewBox="0 0 120 120" style="width:120px"><path d="M30 110 L30 12" stroke="#fff" stroke-width="10" stroke-linecap="round"/><path d="M30 14 L100 30 L30 50 Z" fill="{CORAL}"/></svg>'
            f'<div style="font-size:58px;color:#fff;font-weight:800;letter-spacing:-0.04em;margin-top:16px">목적지에 도착했습니다</div>'
            f'<div style="font-size:34px;color:rgba(255,255,255,0.6);font-weight:600;margin-top:10px">경로 안내를 종료합니다</div></div></div>')
    zero = f'<div id="pb-zero" style="position:absolute;left:0;right:0;top:640px;text-align:center;font-size:300px;font-weight:800;color:{CORAL};-webkit-text-stroke:10px {INK};letter-spacing:-0.06em">0</div>'
    cap, j1 = caption(sid, "지금까지는", "내비 끝나면<br>기록 <b>0</b>", None)
    sub = f'<div id="pb-sub" class="cap" style="top:1150px"><div class="sub" style="font-size:50px;color:{INK}">아이에겐 그냥 <b style="background:linear-gradient(transparent 58%, {YELLOW} 58%)">기다리는 시간</b></div></div>'
    kid_html = chr_box("pb-kid", kid("pk", ("normal", "yawn")), 640, 1290, 360)
    zz = f'<div id="pb-zz" style="position:absolute;left:900px;top:1280px;font-size:70px;font-weight:800;color:#4D8EF7">z z Z</div>'
    js = j1 + [
        pop("#pb-navi", 0.15, 0.8, "power3.out"),
        'tl.to("#pb-navi",{opacity:0,scale:0.92,duration:0.35,ease:"power2.in"},1.5);',
        pop("#pb-zero", 1.75, 2.2, "back.out(1.6)", 0.4),
        'tl.from("#pb-kid",{y:300,duration:0.45,ease:"power3.out"},2.2);',
        swap("pk", "normal", "yawn", 2.8),
        'tl.from("#pb-sub > *",{y:30,opacity:0,duration:0.35,ease:"back.out(1.8)"},2.4);',
        'tl.from("#pb-zz",{opacity:0,y:30,duration:0.4},3.0);',
    ]
    scene(sid, d, CREAM, ['<div class="dots"></div>', navi, zero, sub, kid_html, zz, cap], js)


# 3 앱 등장: 시동만 걸면 자동 기록
def s_appear():
    sid, d = "appear", 3.8
    disp, jd = display(sid, [("boot.jpg", [(0, FULL, 0), (1.2, (850, 560, 0.85), 1.6)], 0)])
    rays = (f'<svg id="ap-rays" viewBox="-300 -300 600 600" style="position:absolute;left:240px;top:540px;width:600px;">' +
            "".join(f'<rect x="-10" y="-290" width="20" height="90" rx="10" fill="{YELLOW}" transform="rotate({a})"/>' for a in range(0, 360, 30)) + '</svg>')
    cap, j1 = caption(sid, "시동 ON", "시동만 걸면<br><b>자동 기록</b>", None, 0.5)
    name = sticker("ap-st", "「부릉탐험대」", 1120, "m", -3)    # under the screen, not between caption and screen
    dad_html = chr_box("ap-dad", dad("ad", ("normal", "sparkle")), 60, 1200, 420)
    js = j1 + jd + [
        pop("#appear-disp", 0.05, 0.5, "back.out(1.7)", 0.45),
        'tl.fromTo("#ap-rays",{scale:0.3,opacity:1,rotation:0},{scale:1.6,opacity:0,rotation:40,duration:0.7,ease:"power2.out"},0.3);',
        'tl.from("#ap-dad",{y:320,duration:0.45,ease:"power3.out"},0.6);',
        swap("ad", "normal", "sparkle", 1.2),
        bob("#ap-dad", 1.3, 5, 14, 0.22),
        pop("#ap-st", 0.9),
    ]
    scene(sid, d, f"linear-gradient(180deg,#CFF5E7 0%,{CREAM} 70%)", ['<div class="dots"></div>', rays, disp, dad_html, name, cap], js)


# 3-2 핵심 기능: 달리기만 하면 지나간 길이 지도에 남는다 (실제 주행 녹화, 올림픽공원 가는 길 약 6배속)
def s_record():
    sid, d = "record", 5.0
    disp = (f'<div id="{sid}-disp" class="disp"><div class="scr">'
            f'<video id="rc-vid" class="clip" data-start="0.2" data-duration="4.6" src="assets/shots/drive.mp4" muted playsinline '
            f'style="position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover"></video></div></div>')
    cap, j1 = caption(sid, "달리기만 하면", "지나간 길이<br><b>탐험 지도</b>로 남아요", None)
    st = sticker("rc-st", "앱을 안 열어도 자동으로!", 1120, "y", -3)
    path = "M-60 1560 C220 1480 360 1640 560 1560 C760 1480 880 1600 1140 1520"
    road = (f'<svg viewBox="0 0 1080 1920" style="position:absolute;inset:0;width:1080px;height:1920px">'
            f'<path d="{path}" fill="none" stroke="#D9D4C7" stroke-width="64" stroke-linecap="round"/>'
            f'<path id="rc-trail" d="{path}" fill="none" stroke="{YELLOW}" stroke-width="30" stroke-linecap="round" stroke-dasharray="1400" stroke-dashoffset="1400"/></svg>')
    car = f'<div id="rc-car" style="position:absolute;left:-300px;top:1400px;width:260px">{car_svg()}</div>'
    js = j1 + [
        pop("#record-disp", 0.05, 0.5, "back.out(1.7)", 0.45),
        'tl.to("#rc-trail",{attr:{"stroke-dashoffset":0},duration:4.0,ease:"none"},0.5);',
        'tl.to("#rc-car",{x:1460,duration:4.3,ease:"none"},0.3);',
        'tl.to("#rc-car",{y:-24,duration:0.3,ease:"sine.inOut",yoyo:true,repeat:13},0.3);',
        pop("#rc-st", 1.2),
    ]
    scene(sid, d, f"linear-gradient(180deg,#FFF1C9 0%,{CREAM} 70%)", ['<div class="dots"></div>', road, disp, car, st, cap], js)


# 4 출퇴근 → 「프로 출근러」
def s_commute():
    sid, d = "commute", 5.2
    disp, jd = display(sid, [("trips.jpg", [(0, (420, 520, 0.95), 0), (0.2, (420, 820, 0.95), 1.6)], 0),
                             ("exp.jpg", [(0, (520, 140, 1.25), 0)], 2.2)])
    cal = (f'<div id="cm-cal" style="position:absolute;left:720px;top:1150px;width:240px;height:230px;background:#fff;border:6px solid {INK};'
           f'border-radius:26px;box-shadow:8px 10px 0 {INK};overflow:hidden;text-align:center">'
           f'<div style="background:{CORAL};color:#fff;font-size:34px;font-weight:800;padding:8px 0">출근</div>'
           f'<div id="cm-n" style="font-size:110px;font-weight:800;letter-spacing:-0.05em;line-height:150px">1</div></div>')
    sh = f'<div id="cm-shield" style="position:absolute;left:400px;top:900px;width:280px">{shield("")}</div>'
    cap, j1 = caption(sid, "평일 아침", "출퇴근만<br>해도!", None)
    st = sticker("cm-st", "「프로 출근러」 칭호 획득!", 1120, "y", -4)
    dad_html = chr_box("cm-dad", dad("cd", ("normal", "happy")), 40, 1240, 380)
    js = j1 + jd + [
        pop("#commute-disp", 0.0, 0.85, "power3.out", 0.3),
        'tl.from("#cm-dad",{y:320,duration:0.4,ease:"power3.out"},0.2);',
        pop("#cm-cal", 0.3),
        'const c={v:1};tl.to(c,{v:20,duration:1.7,ease:"power1.in",onUpdate:()=>{document.querySelector("#cm-n").textContent=Math.round(c.v);}},0.4);',
        'tl.to("#cm-cal",{rotation:-6,duration:0.12,yoyo:true,repeat:9},0.4);',
        'tl.to("#cm-cal",{opacity:0,y:40,duration:0.25},2.4);',
        'tl.fromTo("#cm-shield",{scale:3,opacity:0,rotation:-20},{scale:1,opacity:1,rotation:0,duration:0.35,ease:"power4.in"},2.5);',
        'tl.to("#commute-disp",{x:10,duration:0.05,yoyo:true,repeat:5},2.85);',
        'tl.to("#cm-shield",{opacity:0,scale:0.7,duration:0.25},3.5);',
        pop("#cm-st", 3.0),
        swap("cd", "normal", "happy", 2.9),
    ]
    scene(sid, d, f"linear-gradient(180deg,#FFF1C9 0%,{CREAM} 70%)", ['<div class="dots"></div>', disp, cal, dad_html, sh, st, cap], js)


# 5 주말 가족 드라이브 → 「주말 원정대」
def s_weekend():
    sid, d = "weekend", 4.8
    disp, jd = display(sid, [("gn-card.jpg", [(0, (465, 760, 1.15), 0), (1.3, (465, 1060, 1.15), 1.2)], 0)])
    cap, j1 = caption(sid, "주말엔 가족이랑", "주말 가족 드라이브<br>203 km 강릉까지", None)
    st = sticker("wk-st", "「주말 원정대」 칭호 획득!", 1120, "m", 3)
    fam = (chr_box("wk-mom", mom("wm", ("normal", "happy")), 20, 1260, 330) +
           chr_box("wk-kid", kid("wc", ("normal", "happy")), 375, 1340, 330) +
           chr_box("wk-dad", dad("wd", ("normal", "happy")), 730, 1260, 330))
    js = j1 + jd + [
        pop("#weekend-disp", 0.0, 0.85, "power3.out", 0.3),
        'tl.from(["#wk-mom","#wk-kid","#wk-dad"],{y:330,duration:0.45,stagger:0.1,ease:"power3.out"},0.2);',
        swap("wm", "normal", "happy", 2.4), swap("wc", "normal", "happy", 2.4), swap("wd", "normal", "happy", 2.4),
        'tl.to("#wk-mom",{x:30,y:-30,duration:0.18,ease:"power2.out",yoyo:true,repeat:1},2.4);',
        'tl.to("#wk-dad",{x:-30,y:-30,duration:0.18,ease:"power2.out",yoyo:true,repeat:1},2.4);',
        'tl.to("#wk-kid",{y:-50,duration:0.18,ease:"power2.out",yoyo:true,repeat:1},2.4);',
        pop("#wk-st", 2.6),
    ]
    scene(sid, d, f"linear-gradient(180deg,#D3F4FF 0%,{CREAM} 70%)", ['<div class="dots"></div>', disp, fam, st, cap], js)


# 6 도감 → 다음 여행지
def s_dex():
    sid, d = "dex", 4.6
    disp, jd = display(sid, [("dex.jpg", [(0, FULL, 0), (0.4, (590, 700, 1.25), 1.2)], 0)])
    cap, j1 = caption(sid, "지역 도감", "우리 가족 이번 여행<br>어디 갈까?", None)
    bub = f'<div id="dx-bub" class="bubble" style="left:470px;top:1010px">충청도는 안 가 봤어!</div>'
    st = sticker("dx-st", "충청도로 고! 🚗".replace(" 🚗", ""), 1225, "y", -3)
    fam = (chr_box("dx-mom", mom("xm", ("normal", "sparkle")), 40, 1280, 330) +
           chr_box("dx-kid", kid("xc", ("normal", "sparkle")), 380, 1340, 330) +
           chr_box("dx-dad", dad("xd", ("normal", "happy")), 720, 1280, 330))
    js = j1 + jd + [
        pop("#dex-disp", 0.0, 0.85, "power3.out", 0.3),
        'tl.from(["#dx-mom","#dx-kid","#dx-dad"],{y:330,duration:0.45,stagger:0.1,ease:"power3.out"},0.2);',
        'tl.to(["#dx-mom-r","#dx-dad-r"],{rotation:6,duration:0.4,ease:"sine.inOut",yoyo:true,repeat:1},0.8);',
        pop("#dx-bub", 1.2),
        swap("xc", "normal", "sparkle", 1.7),
        swap("xm", "normal", "sparkle", 3.0), swap("xd", "normal", "happy", 3.0),
        'tl.to("#dx-bub",{opacity:0,duration:0.2},3.1);',
        pop("#dx-st", 3.0),
    ]
    scene(sid, d, f"linear-gradient(180deg,#E3F8EE 0%,{CREAM} 70%)", ['<div class="dots"></div>', disp, fam, bub, st, cap], js)


# 7 새 길 제안 (출발 전)
def s_detour():
    sid, d = "detour", 4.2
    disp, jd = display(sid, [("detour.jpg", [(0, FULL, 0), (0.4, (452, 1045, 1.3), 1.0)], 0)])
    cap, j1 = caption(sid, "출발 전, 정차 중", "조금만 돌아가면<br><b>처음 가 보는 동네</b>", None)
    st = sticker("dt-st", "누르면 바로 내비 안내", 1120, "", -3)
    dad_html = chr_box("dt-dad", dad("td", ("normal", "happy")), 640, 1230, 400)
    js = j1 + jd + [
        pop("#detour-disp", 0.0, 0.85, "power3.out", 0.3),
        'tl.from("#dt-dad",{y:330,duration:0.45,ease:"power3.out"},1.2);',
        swap("td", "normal", "happy", 1.7),
        'tl.fromTo("#dt-dad-r",{rotation:0},{rotation:8,duration:0.15,yoyo:true,repeat:3},1.8);',
        pop("#dt-st", 2.3),
    ]
    scene(sid, d, f"linear-gradient(180deg,#DDE9FF 0%,{CREAM} 70%)", ['<div class="dots"></div>', disp, dad_html, st, cap], js)


# 8 키즈모드: 지루한 아이 → 탐험대원
def s_kids():
    sid, d = "kids", 5.4
    disp, jd = display(sid, [("drive.jpg", [(0, (650, 300, 1.0), 0)], 0)])
    cap, j1 = caption(sid, "키즈모드 ON", "차만 타면 지루한 아이<br>→ <b>탐험대원 변신!</b>", None)
    bored = chr_box("kd-k1", kid("k1", ("yawn",)), 330, 1240, 420)
    hero = chr_box("kd-k2", kid("k2", ("sparkle", "happy"), hands=(("v", 70, 390),), explorer=True), 330, 1240, 420)
    flash = f'<div id="kd-flash" style="position:absolute;inset:0;background:#fff;opacity:0"></div>'
    stars = "".join(f'<div class="kd-star" style="position:absolute;left:{x}px;top:{y}px;width:70px"><svg viewBox="0 0 40 40"><path d="M20 2 L25 15 L38 16 L28 25 L31 38 L20 31 L9 38 L12 25 L2 16 L15 15 Z" fill="{YELLOW}" stroke="{INK}" stroke-width="3"/></svg></div>'
                    for x, y in ((250, 1160), (790, 1140), (220, 1380), (820, 1360), (520, 1090)))
    voice = (f'<div id="kd-voice" class="bubble" style="left:110px;top:{DY + DH - 210}px;background:{MINT};color:#fff;font-size:46px">'
             f'<div style="font-size:30px;opacity:0.9">앱 음성 안내 🔊</div>“짜잔! 처음 와 보는<br>문정1동이에요!”</div>').replace(" 🔊", "")
    st = sticker("kd-st", "「도현 탐험대」 출발!", 1120, "y", -4)
    js = j1 + jd + [
        pop("#kids-disp", 0.0, 0.85, "power3.out", 0.3),
        'tl.from("#kd-k1",{y:330,duration:0.4,ease:"power3.out"},0.2);',
        'tl.set("#kd-k2",{opacity:0},0);',
        'tl.to("#kd-flash",{opacity:1,duration:0.12},1.5);',
        'tl.set("#kd-k1",{opacity:0},1.62);tl.set("#kd-k2",{opacity:1},1.62);',
        'tl.to("#kd-flash",{opacity:0,duration:0.3},1.62);',
        'tl.fromTo(".kd-star",{scale:0,rotation:-90},{scale:1,rotation:0,duration:0.35,stagger:0.06,ease:"back.out(3)"},1.7);',
        bob("#kd-k2", 1.8, 7, 14, 0.25),
        pop("#kd-voice", 2.4),
        'tl.to("#kd-voice",{opacity:0,duration:0.2},4.8);',
        swap("k2", "sparkle", "happy", 2.4),
        pop("#kd-st", 3.9),
    ]
    scene(sid, d, f"linear-gradient(180deg,#FFE3DA 0%,{CREAM} 70%)", ['<div class="dots"></div>', disp, bored, hero, stars, voice, flash, st, cap], js)


# 9 도착 보상
def s_arrive():
    sid, d = "arrive", 4.4
    disp, jd = display(sid, [("ol-card.jpg", [(0, FULL, 0), (0.5, (463, 1060, 1.35), 0.9)], 0)])
    cap, j1 = caption(sid, "P단 + 문 열림 = 도착", "오늘은<br><b>함께 탐험했어요!</b>", None)
    flag = (f'<svg id="ar-flag" viewBox="0 0 120 200" style="position:absolute;left:860px;top:430px;width:140px">'
            f'<path d="M30 196 L30 14" stroke="{INK}" stroke-width="10" stroke-linecap="round"/><path d="M32 16 L112 40 L32 70 Z" fill="{CORAL}" stroke="{INK}" stroke-width="6" stroke-linejoin="round"/></svg>')
    st = sticker("ar-st", "처음 가 본 동네 +6", 1120, "m", 3)
    fam = (chr_box("ar-mom", mom("rm", ("happy",), hands=(("v", 70, 390),)), 20, 1270, 330) +
           chr_box("ar-kid", kid("rc", ("happy",), hands=(("v", 70, 400),), explorer=True), 375, 1340, 330) +
           chr_box("ar-dad", dad("rd", ("happy",), hands=(("v", 70, 380),)), 730, 1270, 330))
    js = j1 + jd + [
        pop("#arrive-disp", 0.0, 0.85, "power3.out", 0.3),
        'tl.fromTo("#ar-flag",{y:-260,opacity:0},{y:0,opacity:1,duration:0.35,ease:"power4.in"},0.3);',
        'tl.to("#ar-flag",{rotation:-8,transformOrigin:"20% 100%",duration:0.15,yoyo:true,repeat:3},0.65);',
        'tl.from(["#ar-mom","#ar-kid","#ar-dad"],{y:330,duration:0.4,stagger:0.08,ease:"back.out(1.6)"},1.3);',
        bob("#ar-kid", 1.8, 5, 12, 0.22),
        pop("#ar-st", 2.3),
    ]
    scene(sid, d, f"linear-gradient(180deg,#FFF1C9 0%,{CREAM} 70%)", ['<div class="dots"></div>', disp, flag, fam, st, cap], js)


# 10 엔딩
def s_end():
    sid, d = "end", 6.0
    path = "M-80 1080 C200 980 300 1160 540 1060 C760 970 860 1120 1160 1020"
    road = (f'<svg viewBox="0 0 1080 1920" style="position:absolute;inset:0;width:1080px;height:1920px">'
            f'<path id="en-trail" d="{path}" fill="none" stroke="{YELLOW}" stroke-width="34" stroke-linecap="round" stroke-dasharray="1400" stroke-dashoffset="1400"/></svg>')
    car = f'<div id="en-car" style="position:absolute;left:-340px;top:930px;width:300px">{car_svg()}</div>'
    body = (f'<div id="en-in" style="position:absolute;left:0;right:0;top:250px;text-align:center;color:#fff">'
            f'<img id="en-icon" src="assets/shots/icon.png" style="width:220px;height:220px;border-radius:52px;border:6px solid {INK};box-shadow:8px 10px 0 {INK}" alt="">'
            f'<div id="en-name" style="font-size:118px;font-weight:800;letter-spacing:-0.05em;margin-top:18px">부릉탐험대</div>'
            f'<div id="en-sub" style="font-size:50px;font-weight:800;letter-spacing:-0.04em;color:{YELLOW};margin-top:4px">운전이 나만의 탐험 지도가 된다</div></div>')
    ask = f'<div id="en-ask" class="bubble" style="left:170px;top:1110px;font-size:56px">당신의 칭호는? 댓글로! 💬</div>'.replace(" 💬", "")
    credit = (f'<div id="en-credit" style="position:absolute;left:40px;right:40px;top:1262px;text-align:center;font-size:28px;line-height:1.4;color:rgba(255,255,255,0.9)">'
              f'앱 화면은 Pleos Connect 에뮬레이터 시연 · 지도 © OpenStreetMap, OpenFreeMap<br>Music "Easy Lemon" Kevin MacLeod (incompetech.com), CC BY 4.0</div>')
    js = [
        'tl.from("#en-icon",{scale:0.3,rotation:-14,opacity:0,duration:0.5,ease:"back.out(2.2)"},0.1);',
        'tl.from(["#en-name","#en-sub"],{y:40,opacity:0,duration:0.4,stagger:0.12,ease:"power3.out"},0.35);',
        'tl.to("#en-trail",{attr:{"stroke-dashoffset":0},duration:2.2,ease:"none"},0.6);',
        'tl.to("#en-car",{x:1480,duration:2.4,ease:"none"},0.5);',
        'tl.to("#en-car",{y:-30,duration:0.3,ease:"sine.inOut",yoyo:true,repeat:7},0.5);',
        pop("#en-ask", 1.6),
        'tl.from("#en-credit",{opacity:0,duration:0.4},1.0);',
    ]
    scene(sid, d, f"linear-gradient(180deg,{MINT} 0%,{MINT_D} 100%)", [road, car, body, ask, credit], js)


for f in (s_hook, s_problem, s_appear, s_record, s_commute, s_weekend, s_dex, s_detour, s_kids, s_arrive, s_end):
    f()

t = 0.0
for sc in SCENES:
    sc["start"] = round(t, 2)
    t += sc["dur"] - XF
TOTAL = round(SCENES[-1]["start"] + SCENES[-1]["dur"], 2)


def comp_html(sc):
    sid, dur = sc["id"], sc["dur"]
    js = [f'tl.from("#{sid}-in",{{opacity:0,duration:{XF},ease:"none"}},0);'] if sc is not SCENES[0] else []
    js += sc["js"]
    return f"""<template>
  <style>{BASE_CSS}</style>
  <div id="root" data-composition-id="{sid}" data-width="{W}" data-height="{H}" data-start="0" data-duration="{dur}">
    <div id="{sid}-in" style="position:absolute;inset:0;">
      <div class="bg" style="background:{sc["bg"]}"></div>
      {sc["html"]}
    </div>
  </div>
  <script>
    const tl = gsap.timeline({{ paused: true }});
    {chr(10).join("    " + j for j in js).strip()}
    window.__timelines["{sid}"] = tl;
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
    lane = json.dumps({"version": 1, "lanes": [{"target": "volume", "points": [
        {"t": 0, "v": 0.0}, {"t": 0.25, "v": 0.68}, {"t": round(TOTAL - 1.6, 2), "v": 0.68}, {"t": round(TOTAL - 0.05, 2), "v": 0.0}]}]})
    audio = (f'      <audio id="music" class="clip" data-start="0" data-duration="{TOTAL}" data-track-index="9" data-volume="1" '
             f"data-automation='{lane}' data-media-start=\"0\" src=\"assets/music/easy-lemon.mp3\"></audio>")
    return f"""<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width={W}, height={H}" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ margin: 0; width: {W}px; height: {H}px; overflow: hidden; background: {CREAM}; }}
      #root {{ position: relative; width: 100%; height: 100%; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL}" data-width="{W}" data-height="{H}">
{chr(10).join(hosts)}
{audio}
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
    keep = {sc["id"] for sc in SCENES}
    for f in os.listdir(comp):
        if f.endswith(".html") and f[:-5] not in keep:
            os.remove(os.path.join(comp, f))
    for sc in SCENES:
        open(os.path.join(comp, sc["id"] + ".html"), "w", encoding="utf-8").write(comp_html(sc))
    open(os.path.join(HERE, "index.html"), "w", encoding="utf-8").write(index_html())
    for name, data in (("hyperframes.json", {"$schema": "https://hyperframes.heygen.com/schema/hyperframes.json",
                                             "paths": {"blocks": "compositions", "assets": "assets"}, "authoringSkill": "hyperframes"}),
                       ("meta.json", {"id": "burung-toon", "name": "burung-toon"})):
        open(os.path.join(HERE, name), "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2))
    print(f"{len(SCENES)} scenes, {TOTAL}s")


if __name__ == "__main__":
    main()
