#!/usr/bin/env python3
"""Turns the app's adaptive launcher icon (Android vector drawables) into one SVG for the film.

    python3 tools/demo/promo/vector2svg.py > tools/demo/promo/assets/icon.svg

Handles what ic_launcher_background/foreground use: paths with fill or stroke, groups with
scale/pivot/translate, clip-path, and aapt linear/radial gradients (start/center/end or items).
"""
import os
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "app/src/main/res/drawable")
defs, n = [], [0]


def color(c):
    c = c.strip()
    if len(c) == 9:  # #AARRGGBB
        a = int(c[1:3], 16) / 255
        return f"#{c[3:]}", a
    return c, 1.0


def gradient(g):
    n[0] += 1
    gid = f"g{n[0]}"
    t = g.get(A + "type", "linear")
    stops = []
    items = g.findall("item")
    if items:
        for it in items:
            c, a = color(it.get(A + "color"))
            stops.append((float(it.get(A + "offset")), c, a))
    else:
        cs = [g.get(A + k) for k in ("startColor", "centerColor", "endColor")]
        cs = [c for c in cs if c]
        for i, c in enumerate(cs):
            cc, a = color(c)
            stops.append((i / (len(cs) - 1), cc, a))
    st = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a:.3f}"/>' for o, c, a in stops)
    if t == "radial":
        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{g.get(A+"centerX")}" '
                    f'cy="{g.get(A+"centerY")}" r="{g.get(A+"gradientRadius")}">{st}</radialGradient>')
    else:
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{g.get(A+"startX")}" '
                    f'y1="{g.get(A+"startY")}" x2="{g.get(A+"endX")}" y2="{g.get(A+"endY")}">{st}</linearGradient>')
    return f"url(#{gid})"


def path(p):
    attrs = {}
    for aattr in p.findall("{http://schemas.android.com/aapt}attr"):
        g = aattr.find("gradient")
        if g is not None:
            key = aattr.get("name").split(":")[-1]
            attrs[key] = gradient(g)
    fill = attrs.get("fillColor") or p.get(A + "fillColor")
    stroke = attrs.get("strokeColor") or p.get(A + "strokeColor")
    out = f'<path d="{p.get(A + "pathData")}"'
    if fill and not fill.startswith("url"):
        c, a = color(fill)
        out += f' fill="{c}" fill-opacity="{a:.3f}"'
    elif fill:
        out += f' fill="{fill}"'
    else:
        out += ' fill="none"'
    if stroke:
        if stroke.startswith("url"):
            out += f' stroke="{stroke}"'
        else:
            c, a = color(stroke)
            out += f' stroke="{c}" stroke-opacity="{a:.3f}"'
        out += f' stroke-width="{p.get(A + "strokeWidth", "1")}"'
        if p.get(A + "strokeLineCap"):
            out += f' stroke-linecap="{p.get(A + "strokeLineCap")}"'
        if p.get(A + "strokeLineJoin"):
            out += f' stroke-linejoin="{p.get(A + "strokeLineJoin")}"'
    if p.get(A + "fillAlpha"):
        out += f' opacity="{p.get(A + "fillAlpha")}"'
    return out + "/>"


def group(g):
    body = []
    clip = None
    for ch in g:
        tag = ch.tag
        if tag == "path":
            body.append(path(ch))
        elif tag == "group":
            body.append(group(ch))
        elif tag == "clip-path":
            n[0] += 1
            clip = f"c{n[0]}"
            defs.append(f'<clipPath id="{clip}"><path d="{ch.get(A + "pathData")}"/></clipPath>')
    if g.tag != "group":
        return "".join(body)
    px, py = float(g.get(A + "pivotX", 0)), float(g.get(A + "pivotY", 0))
    sx, sy = float(g.get(A + "scaleX", 1)), float(g.get(A + "scaleY", 1))
    tx, ty = float(g.get(A + "translateX", 0)), float(g.get(A + "translateY", 0))
    rot = float(g.get(A + "rotation", 0))
    tf = f"translate({tx + px},{ty + py}) rotate({rot}) scale({sx},{sy}) translate({-px},{-py})"
    inner = "".join(body)
    if clip:
        inner = f'<g clip-path="url(#{clip})">{inner}</g>'
    return f'<g transform="{tf}">{inner}</g>'


layers = [group(ET.parse(os.path.join(RES, f)).getroot()) for f in ("ic_launcher_background.xml", "ic_launcher_foreground.xml")]
print('<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="720" height="720">'
      f'<defs>{"".join(defs)}<clipPath id="mask"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath></defs>'
      f'<g clip-path="url(#mask)">{"".join(layers)}</g></svg>')
