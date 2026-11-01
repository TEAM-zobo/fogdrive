#!/usr/bin/env python3
"""Builds the demo record of the persona: a driver in their 30s living in 위례 (송파꿈에그린 위례24단지) who drives to
현대자동차 본사 (서초구 양재) on weekdays, to 코스트코 양재점 on Saturday mornings, out of town at
weekends, and once a quarter to 강릉. Four weeks are on record; 강릉 is left for the live demo.

    python3 tools/demo/persona/fetch_routes.py          # once: road paths -> routes.json
    python3 tools/demo/persona/make_persona_backup.py [--today 2026-11-01]
        -> tools/demo/out/persona-backup.json  (설정 → 데이터 동기화 → 불러오기)

Why these numbers (see docs/submission/burung/persona.md): a car in Korea runs ~38 km a day
(한국교통안전공단, 2024: 38.7 km); 집 ↔ 회사 is ~12 km each way; a week here is ~270 km.

Stamps follow the app's rules (explore/Expedition.kt, record/RecordingService.kt): 동 and 시·도 are
counted from the region atlas, a milestone is stamped at the end of the trip that reaches it.
It is a made-up record for demos. Standard library only.
"""
import argparse
import json
import math
import os
import struct
from datetime import date, datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
ROUTES = os.path.join(HERE, "routes.json")
ATLAS = os.path.join(ROOT, "app", "src", "main", "assets", "regions", "kr_regions.bin")
OUT = os.path.join(ROOT, "tools", "demo", "out", "persona-backup.json")

KST = timezone(timedelta(hours=9))
Z = 20
DRIVE_RADIUS = 1      # TripRecorder.DRIVE_RADIUS
LANDING_RADIUS = 3    # TripRecorder.LANDING_RADIUS
TRACK_STEP_M = 20.0
SOURCE_DRIVE, SOURCE_LANDING = 0, 1
WEEKS = 4
# Weekday holidays in the period: no commute (추석 9/24·9/25, 개천절 대체 10/5, 한글날 10/9).
HOLIDAYS = {date(2026, 9, 24), date(2026, 9, 25), date(2026, 10, 5), date(2026, 10, 9)}
# 추석 holidays, spent at the parents' (no outings those days).
CHUSEOK = {date(2026, 9, 24), date(2026, 9, 25), date(2026, 9, 26)}
# Saturday afternoons: nearby; Sundays: farther. Both with the spouse aboard (함께 탐험).
SATURDAY = ["namhansan", "everland", "starfield", "suwon"]
SUNDAY = ["dumulmeori", "hwadam", "mulgarden", "songdo"]
LABELS = {
    "hq": "현대자동차 본사", "costco": "코스트코 양재점", "suwon": "수원 화성행궁", "dumulmeori": "양평 두물머리",
    "everland": "용인 에버랜드", "songdo": "인천 송도 센트럴파크", "hwadam": "광주 화담숲", "namhansan": "남한산성", "parents": "부모님 댁 (고양 일산)", "starfield": "하남 스타필드", "mulgarden": "남양주 물의정원",
}


# ---- region atlas (format of tools/regions/build_regions.py) ----

class Atlas:
    def __init__(self, path):
        b = open(path, "rb").read()
        self.b, self.p = b, 4
        self.i()
        self.utf()
        self.sido = [(self.utf(), self.utf(), self.outline()) for _ in range(self.i())]
        self.sgg = []
        for _ in range(self.i()):
            code, name = self.utf(), self.utf()
            self.sgg.append((code, name, self.i(), self.outline(rings=False)))
        self.dong = []
        for _ in range(self.i()):
            code, name, sgg = self.utf(), self.utf(), self.i()
            self.p += 8
            self.dong.append((code, name, sgg, self.outline()))
        # Bucket index of 동 by bbox, 2^12 tiles a bucket.
        self.buckets = {}
        for k, d in enumerate(self.dong):
            x0, y0, x1, y1 = d[3][0]
            for bx in range(x0 >> 12, (x1 >> 12) + 1):
                for by in range(y0 >> 12, (y1 >> 12) + 1):
                    self.buckets.setdefault((bx, by), []).append(k)

    def i(self):
        v = struct.unpack_from(">i", self.b, self.p)[0]
        self.p += 4
        return v

    def utf(self):
        n = struct.unpack_from(">H", self.b, self.p)[0]
        s = self.b[self.p + 2:self.p + 2 + n].decode("utf-8")
        self.p += 2 + n
        return s

    def outline(self, rings=True):
        bbox = [self.i() for _ in range(4)]
        self.p += 8
        out = []
        for _ in range(self.i()):
            n = self.i()
            pts = struct.unpack_from(f">{2 * n}i", self.b, self.p)
            self.p += 8 * n
            if rings:
                out.append(pts)
        return bbox, out

    def dong_at(self, x, y):
        """Index of the 동 holding the cell (x, y) (its centre), or None."""
        cx, cy = x + 0.5, y + 0.5
        for k in self.buckets.get((x >> 12, y >> 12), ()):
            (x0, y0, x1, y1), rings = self.dong[k][3]
            if not (x0 <= cx <= x1 and y0 <= cy <= y1):
                continue
            inside = False
            for r in rings:
                n = len(r) // 2
                j = n - 1
                for i in range(n):
                    xi, yi, xj, yj = r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1]
                    if (yi > cy) != (yj > cy) and cx < (xj - xi) * (cy - yi) / (yj - yi) + xi:
                        inside = not inside
                    j = i
            if inside:
                return k
        return None


# ---- geometry ----

def cell_of(lat, lng):
    n = 1 << Z
    x = int((lng + 180.0) / 360.0 * n)
    r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n)
    return x, y


def pack(x, y):
    return (x << 32) | (y & 0xFFFFFFFF)


def dist_m(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (*a, *b))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def densify(pts, step):
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        n = max(1, int(dist_m(a, b) // step))
        out += [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(1, n + 1)]
    return out


def swath(pts, radius):
    cells = set()
    for lat, lng in densify(pts, 5.0):
        x, y = cell_of(lat, lng)
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                cells.add((x + dx, y + dy))
    return cells


def length_m(pts):
    return sum(dist_m(a, b) for a, b in zip(pts, pts[1:]))


# ---- the four weeks ----

def plan(today):
    """(start datetime, place, outbound, passenger) per one-way trip, oldest first."""
    first = today - timedelta(days=today.weekday() + 7 * (WEEKS - 1))   # Monday of the first of the four weeks
    trips = []
    sat_i = sun_i = 0
    d = first
    while d < today:
        wd = d.weekday()
        at = lambda h, m: datetime(d.year, d.month, d.day, h, m, tzinfo=KST)
        jitter = (d.toordinal() * 7) % 11
        if d in CHUSEOK:
            # 추석: to the parents' in 일산 on the first day, back on the last (with the spouse).
            if d == min(CHUSEOK):
                trips.append((at(9, 30), "parents", True, True))
            if d == max(CHUSEOK):
                trips.append((at(15, 0), "parents", False, True))
        elif wd < 5 and d not in HOLIDAYS:
            trips += [(at(7, 50 + jitter % 9), "hq", True, False), (at(18, 35 + jitter), "hq", False, False)]
        elif wd == 5:
            trips += [(at(10, 20 + jitter), "costco", True, True), (at(12, 5 + jitter), "costco", False, True)]
            p = SATURDAY[sat_i % len(SATURDAY)]
            sat_i += 1
            trips += [(at(14, 30), p, True, True), (at(18, 10), p, False, True)]
        elif wd == 6:
            p = SUNDAY[sun_i % len(SUNDAY)]
            sun_i += 1
            trips += [(at(9, 40), p, True, True), (at(17, 20), p, False, True)]
        d += timedelta(days=1)
    return trips


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--today", help="demo day (the 강릉 trip), YYYY-MM-DD; default: today")
    a = ap.parse_args()
    today = date.fromisoformat(a.today) if a.today else datetime.now(KST).date()
    data = json.load(open(ROUTES, encoding="utf-8"))
    home = tuple(data["places"]["home"])
    routes = {k: [tuple(p) for p in v] for k, v in data["routes"].items()}
    atlas = Atlas(ATLAS)

    cells = {}  # packed -> [firstSeenAt, tripId, source, visits]
    dong_of = {}
    trips, landings, stamps, track = [], [], [], []
    have = set()
    seen_dongs, seen_sggs, seen_sidos = set(), set(), set()
    trip_days = []
    km = 0.0

    def stamp(code, at, tid, where):
        if code in have:
            return
        have.add(code)
        stamps.append([len(stamps) + 1, code, at, tid, where[0], where[1]])

    for tid, (start, place, outbound, passenger) in enumerate(plan(today), start=1):
        route = routes[place] if outbound else list(reversed(routes[place]))
        meters = length_m(route)
        speed = 45 if place == "hq" else 55          # km/h, door to door
        start_ms = int(start.timestamp() * 1000)
        end_ms = start_ms + int(meters / 1000 / speed * 3_600_000)
        driven = swath(route, DRIVE_RADIUS)
        new_cells = 0
        for x, y in driven:
            c = pack(x, y)
            if c in cells:
                cells[c][3] += 1
            else:
                cells[c] = [start_ms, tid, SOURCE_DRIVE, 1]
                new_cells += 1
        end = route[-1]
        if outbound:
            landings.append([len(landings) + 1, end[0], end[1], end_ms, tid])
            ex, ey = cell_of(*end)
            for dx in range(-LANDING_RADIUS, LANDING_RADIUS + 1):
                for dy in range(-LANDING_RADIUS, LANDING_RADIUS + 1):
                    c = pack(ex + dx, ey + dy)
                    if c not in cells:
                        cells[c] = [end_ms, tid, SOURCE_LANDING, 1]
                        new_cells += 1
        new_ratio = min(1.0, new_cells / max(1, len(driven)))
        trips.append([tid, start_ms, end_ms, new_cells, round(meters, 1), round(meters * new_ratio, 1)])
        dense = densify(route, TRACK_STEP_M)
        for k, (lat, lng) in enumerate(dense):
            track.append([len(track) + 1, tid, round(lat, 6), round(lng, 6), start_ms + (end_ms - start_ms) * k // max(1, len(dense) - 1)])

        # Regions entered on this trip: the 동 of every cell it opened, as the app counts them
        # (ExploreRepository.stampProgress reads the 동 of each cell not seeded from the navigation).
        if tid == 1:
            # The start's 시·군·구 / 시·도 are known before the first drive (no 새 도시 for home).
            k = atlas.dong_at(*cell_of(*home))
            seen_sggs.add(atlas.dong[k][2])
            seen_sidos.add(atlas.sgg[atlas.dong[k][2]][2])
        new_district = None
        for c, v in cells.items():
            if v[1] != tid:
                continue
            k = atlas.dong_at(c >> 32, c & 0xFFFFFFFF)
            if k is None or k in seen_dongs:
                continue
            seen_dongs.add(k)
            sgg = atlas.dong[k][2]
            if sgg not in seen_sggs:
                seen_sggs.add(sgg)
                new_district = new_district or end
            seen_sidos.add(atlas.sgg[sgg][2])

        # Stamps as the app gives them, at this trip.
        km += meters / 1000
        trip_days.append(start.date())
        if tid == 1:
            stamp("FIRST_DRIVE", start_ms, tid, route[0])
        if new_district:
            stamp("NEW_DISTRICT", start_ms + 600_000, tid, new_district)
        if start.weekday() >= 5 and new_ratio > 0.3:
            stamp("WEEKEND", end_ms, tid, end)
        if passenger and meters >= 300:
            stamp("CREW_MISSION", end_ms, tid, end)
        wk = lambda d: ((d - date(1970, 1, 1)).days + 3) // 7
        this_week = {d for d in trip_days if wk(d) == wk(start.date())}
        if len(this_week) >= 3:
            stamp("WEEK_THREE", end_ms, tid, end)
        weeks = {wk(d) for d in trip_days}
        w, streak = wk(start.date()), 0
        while w in weeks:
            streak += 1
            w -= 1
        if streak >= 4:
            stamp("STREAK_4W", end_ms, tid, end)
        if km >= 1000:
            stamp("KM_1000", end_ms, tid, end)
        if len(seen_dongs) >= 10:
            stamp("DONG_10", end_ms, tid, end)
        if len(seen_dongs) >= 100:
            stamp("DONG_100", end_ms, tid, end)
        if len(seen_sidos) >= 3:
            stamp("PROVINCE_3", end_ms, tid, end)

    out = {
        "format": "fogdrive-backup",
        "version": 1,
        "exportedAt": int(datetime(today.year, today.month, today.day, 7, 0, tzinfo=KST).timestamp() * 1000),
        "cells": [[c, *v] for c, v in sorted(cells.items())],
        "trips": trips,
        "landings": landings,
        "stamps": stamps,
        "track": track,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"), ensure_ascii=False)

    days = today.weekday() + 7 * (WEEKS - 1)
    print(f"{os.path.relpath(OUT, ROOT)}: {datetime.fromtimestamp(trips[0][1] / 1000, KST):%m/%d} ~ "
          f"{datetime.fromtimestamp(trips[-1][2] / 1000, KST):%m/%d}, 주행 {len(trips)}건 {km:,.0f} km "
          f"(하루 평균 {km / days:.1f} km), 하차 {len(landings)}곳, 칸 {len(cells):,}")
    print(f"시·도 {len(seen_sidos)} · 시·군·구 {len(seen_sggs)} · 읍·면·동 {len(seen_dongs)}")
    print("스탬프", len(stamps), ":", ", ".join(s[1] for s in stamps))


if __name__ == "__main__":
    main()
