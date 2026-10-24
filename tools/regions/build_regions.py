"""Builds the region atlas (시·도 / 시·군·구 / 읍·면·동 boundaries) for the region dex and the fog.

    pip install "shapely>=2.1" numpy
    python3 tools/regions/build_regions.py [HangJeongDong_verYYYYMMDD.geojson]

Source: vuski/admdongkor 행정동 boundaries (CC BY 4.0, 원자료 통계청). Without an argument the
pinned version is downloaded. Every 행정동 is kept; the 일반구 of a 시 inside a 도 (수원시 장안구 …)
are merged into the 시, so the dex lists 수원시 once. 자치구 of 특별·광역시 stay.

Geometry is stored in zoom-20 tile units, the app's exploration grid, so a cell is located with a
plain point-in-polygon test on its centre. Neighbouring 동 are simplified together (coverage
simplification) so no gaps or overlaps open between them. `cells` is the area in tile units before
simplification, i.e. how many grid cells the 동 holds: the denominator of the achievement rate.

시·군·구 and 시·도 outlines are the unions of their 동, simplified again more coarsely (they are
drawn as boundary lines at lower zoom). Each outline carries a label point (pole of inaccessibility
of its largest part) where the map writes the region's name.

Output: app/src/main/assets/regions/kr_regions.bin, big-endian (java.io.DataInputStream):
    "FGRG" int version, UTF source
    int n, n x (UTF code, UTF name, OUTLINE)                       시·도
    int n, n x (UTF code, UTF name, int sido, OUTLINE)             시·군·구
    int n, n x (UTF code, UTF name, int sgg, long cells, OUTLINE)  읍·면·동
    OUTLINE = int bbox[4], int labelX, int labelY, int rings, rings x (int points, points x (int x, int y))
"""
import json
import math
import os
import re
import struct
import sys
import tempfile
import urllib.request

import numpy as np
import shapely
from shapely.geometry import MultiPolygon, Polygon, shape
from shapely.ops import polylabel, unary_union

VERSION = "ver20261001"
URL = f"https://raw.githubusercontent.com/vuski/admdongkor/master/{VERSION}/HangJeongDong_{VERSION}.geojson"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "regions", "kr_regions.bin")

Z = 20
FORMAT_VERSION = 2
SIMPLIFY = 1.0          # 동, tile units (~30 m, one cell)
SGG_SIMPLIFY = 2.0
SIDO_SIMPLIFY = 6.0
MIN_RING_CELLS = 20     # islets smaller than this (~0.02 km²) are left out of the lookup only
CITY_WITH_GU = re.compile(r"^(.+시)(.+구)$")


def to_tiles(lng, lat):
    """Web Mercator lng/lat -> zoom-20 tile units (same formula as geo/Tiles.kt)."""
    n = 1 << Z
    x = (np.asarray(lng) + 180.0) / 360.0 * n
    r = np.radians(np.clip(lat, -85.0511, 85.0511))
    y = (1.0 - np.log(np.tan(r) + 1.0 / np.cos(r)) / math.pi) / 2.0 * n
    return x, y


def polygons(geom):
    if isinstance(geom, Polygon):
        return [geom]
    if isinstance(geom, MultiPolygon):
        return list(geom.geoms)
    return [g for g in getattr(geom, "geoms", []) if isinstance(g, Polygon)]


def ring_coords(ring):
    return [(round(x), round(y)) for x, y in list(ring.coords)[:-1]]


def outline(geom, simplified):
    """bbox, label point and rings of one region; islets below MIN_RING_CELLS are dropped."""
    rings = []
    for poly in polygons(simplified):
        if poly.area < MIN_RING_CELLS:
            continue
        rings.append(ring_coords(poly.exterior))
        rings += [ring_coords(h) for h in poly.interiors if Polygon(h).area >= MIN_RING_CELLS]
    if not rings:  # made only of islets: keep its largest one
        rings = [ring_coords(max(polygons(simplified), key=lambda g: g.area).exterior)]
    xs = [x for r in rings for x, _ in r]
    ys = [y for r in rings for _, y in r]
    label = polylabel(max(polygons(geom), key=lambda g: g.area), tolerance=2.0)
    return (min(xs), min(ys), max(xs), max(ys)), (round(label.x), round(label.y)), rings


class Writer:
    def __init__(self):
        self.buf = bytearray()

    def int(self, v):
        self.buf += struct.pack(">i", v)

    def long(self, v):
        self.buf += struct.pack(">q", v)

    def outline(self, o):
        bbox, label, rings = o
        for v in (*bbox, *label, len(rings)):
            self.int(v)
        for r in rings:
            self.int(len(r))
            for x, y in r:
                self.int(x)
                self.int(y)
        return sum(len(r) for r in rings)

    def utf(self, s):
        b = s.encode("utf-8")
        self.buf += struct.pack(">H", len(b)) + b


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    if src is None:
        src = os.path.join(tempfile.mkdtemp(), os.path.basename(URL))
        print("downloading", URL)
        urllib.request.urlretrieve(URL, src)
    with open(src, encoding="utf-8") as f:
        features = json.load(f)["features"]

    sido_names, sgg_names, dongs = {}, {}, []
    for feat in features:
        p = feat["properties"]
        sido_names[p["sido"]] = p["sidonm"]
        name, code = p["sggnm"], p["sgg"]
        m = CITY_WITH_GU.match(name)
        if m and not p["sidonm"].endswith("시"):
            name, code = m.group(1), code[:4] + "0"
        sgg_names[(p["sido"], code)] = name
        geom = shapely.transform(shape(feat["geometry"]), to_tiles, interleaved=False).buffer(0)
        dong = p["adm_nm"].split(" ")[-1]
        dongs.append({"sido": p["sido"], "sgg": code, "code": p["adm_cd2"], "name": dong, "geom": geom})

    dongs.sort(key=lambda d: d["code"])
    sidos = sorted(sido_names)
    sggs = sorted(sgg_names)
    sgg_geoms = [unary_union([d["geom"] for d in dongs if (d["sido"], d["sgg"]) == key]).buffer(0) for key in sggs]
    sido_geoms = [unary_union([g for key, g in zip(sggs, sgg_geoms) if key[0] == c]).buffer(0) for c in sidos]
    dong_simple = shapely.coverage_simplify(np.array([d["geom"] for d in dongs]), SIMPLIFY)
    sgg_simple = shapely.coverage_simplify(np.array(sgg_geoms), SGG_SIMPLIFY)
    sido_simple = shapely.coverage_simplify(np.array(sido_geoms), SIDO_SIMPLIFY)

    w = Writer()
    w.buf += b"FGRG"
    w.int(FORMAT_VERSION)
    w.utf(f"admdongkor {VERSION} (CC BY 4.0, 원자료 통계청)")
    points = 0
    w.int(len(sidos))
    for c, geom, simple in zip(sidos, sido_geoms, sido_simple):
        w.utf(c)
        w.utf(sido_names[c])
        points += w.outline(outline(geom, simple))
    w.int(len(sggs))
    for key, geom, simple in zip(sggs, sgg_geoms, sgg_simple):
        w.utf(key[1])
        w.utf(sgg_names[key])
        w.int(sidos.index(key[0]))
        points += w.outline(outline(geom, simple))
    w.int(len(dongs))
    for d, simple in zip(dongs, dong_simple):
        w.utf(d["code"])
        w.utf(d["name"])
        w.int(sggs.index((d["sido"], d["sgg"])))
        w.long(round(d["geom"].area))
        points += w.outline(outline(d["geom"], simple))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(w.buf)
    print(f"{len(sidos)} 시·도, {len(sggs)} 시·군·구, {len(dongs)} 읍·면·동, {points} points, "
          f"{len(w.buf) // 1024} KB -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
