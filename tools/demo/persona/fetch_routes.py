#!/usr/bin/env python3
"""Fetches the persona's road paths from the public OSRM server (© OpenStreetMap contributors)
and keeps them, simplified, in routes.json next to this file. Run again only to change a place.

    python3 tools/demo/persona/fetch_routes.py                    # every route
    python3 tools/demo/persona/fetch_routes.py kinder olympic     # only these (the rest kept as is)
"""
import json
import math
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "routes.json")

# (lat, lng) of the persona's places. 집: 송파꿈에그린 위례24단지 (송파구 위례광장로 121, 1,810세대: 위례에서 가장 큰 단지);
# 회사: 현대자동차 본사 (서초구 헌릉로 12), saved as 회사 in the navigation.
PLACES = {
    "home": (37.47588, 127.13960),
    "hq": (37.46360, 127.04230),
    "costco": (37.46196, 127.03617),
    "suwon": (37.28220, 127.01438),
    "dumulmeori": (37.53400, 127.31600),
    "everland": (37.29388, 127.20484),
    "songdo": (37.39410, 126.63228),
    "hwadam": (37.34198, 127.28858),
    "namhansan": (37.47784, 127.18285),
    "starfield": (37.54520, 127.22360),
    "mulgarden": (37.59350, 127.31100),
    "parents": (37.65800, 126.77200),
    "gangneung": (37.80420, 128.90622),
    # Demo video (40대 아빠 A씨): a kindergarten in 거여2동 on the way to work, and 올림픽공원 (평화의문) on Saturday.
    "kinder": (37.48800, 127.14600),
    "olympic": (37.52030, 127.11570),
}
# Routes that do not leave from home: name -> (from, to).
LEGS = {"kinder-hq": ("kinder", "hq")}
ROUTES = ["hq", "costco", "suwon", "dumulmeori", "everland", "songdo", "hwadam", "namhansan", "starfield", "mulgarden", "parents", "gangneung",
          "kinder", "kinder-hq", "olympic"]
TOLERANCE_M = 12.0


def dist(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (*a, *b))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def simplify(pts, tol):
    """Douglas–Peucker in local metres."""
    lat0 = math.radians(pts[0][0])
    xy = [(p[1] * 111_320 * math.cos(lat0), p[0] * 110_540) for p in pts]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = xy[a]
        bx, by = xy[b]
        L = math.hypot(bx - ax, by - ay) or 1.0
        best, bi = 0.0, -1
        for i in range(a + 1, b):
            d = abs((by - ay) * (xy[i][0] - ax) - (bx - ax) * (xy[i][1] - ay)) / L
            if d > best:
                best, bi = d, i
        if best > tol:
            keep[bi] = True
            stack += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]


def route(a, b):
    url = ("https://router.project-osrm.org/route/v1/driving/"
           f"{a[1]},{a[0]};{b[1]},{b[0]}?overview=full&geometries=geojson")
    req = urllib.request.Request(url, headers={"User-Agent": "FogDrive-demo/0.1 (team zobo)"})
    data = json.load(urllib.request.urlopen(req, timeout=60))
    coords = data["routes"][0]["geometry"]["coordinates"]
    return [(round(lat, 5), round(lng, 5)) for lng, lat in coords], data["routes"][0]["distance"]


def main():
    only = sys.argv[1:]
    out = {"places": PLACES, "routes": {}}
    if only:
        with open(OUT, encoding="utf-8") as f:
            out["routes"] = json.load(f)["routes"]
    for name in ROUTES:
        if only and name not in only:
            continue
        a, b = (PLACES[k] for k in LEGS.get(name, ("home", name)))
        pts, meters = route(a, b)
        s = simplify(pts, TOLERANCE_M)
        out["routes"][name] = s
        print(f"{name}: {meters / 1000:.1f} km, {len(pts)} → {len(s)} points, straight {dist(a, b) / 1000:.1f} km")
        time.sleep(1.5)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)


if __name__ == "__main__":
    main()
