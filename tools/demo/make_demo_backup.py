#!/usr/bin/env python3
"""Builds a demo history for the video: two months of everyday driving around 향동, as a FogDrive backup.

    python3 tools/demo/make_demo_backup.py            # -> tools/demo/out/fogdrive-backup.json

The routes are the app's own demo courses (real road paths, app/src/main/java/.../demo/DemoCourses.kt),
driven out and back a set number of times, so the map shows every worn-road colour at once:

    상암 월드컵공원  출퇴근  52회 → 빨강 (50+)
    은평한옥마을     주말    22회 → 주황 (20+)
    홍대·망원        주말    12회 → 보라 (10+)
    일산 호수공원    저녁     6회 → 남색 (5+)
    김포공항         마중     4회 → 파랑 (3+)
    파주·김포 한 바퀴         2회 → 옅은 파랑 (2)

The video's first part drives 성수 주말 나들이 on a fresh install ("첫 시동"); its second part ("두 달
뒤") loads this record. So the record starts with that same 성수 drive (out only, two months ago, with
its stamps), and every other route stays clear of it.

The file is format 1 (JSON), which the app still imports (설정 → 데이터 동기화 → 불러오기).
It is a made-up record for the demo; the video says so in a caption.
Standard library only.
"""
import json
import math
import os
import re
import sys
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
COURSES = os.path.join(ROOT, "app/src/main/java/com/zobo/burung/demo/DemoCourses.kt")
OUT = os.path.join(ROOT, "tools", "demo", "out", "fogdrive-backup.json")

KST = timezone(timedelta(hours=9))
Z = 20
DRIVE_RADIUS = 1        # TripRecorder.DRIVE_RADIUS: cells around each driven cell
LANDING_RADIUS = 3      # TripRecorder.LANDING_RADIUS
TRACK_STEP_M = 20.0     # the app keeps a route point about every 20 m
SOURCE_DRIVE, SOURCE_LANDING = 0, 1
TODAY = datetime(2026, 10, 25, tzinfo=KST)   # the day the demo is recorded (a Sunday)

# (route object, label, round trips [count of one-way trips], kind) — counts are one-way trips.
PLAN = [
    ("SangamRoute", "상암 월드컵공원", 52, "commute"),
    ("EunpyeongRoute", "은평한옥마을", 22, "weekend"),
    ("MangwonRoute", "홍대·망원", 12, "weekend"),
    ("IlsanRoute", "일산 호수공원", 6, "evening"),
    ("GimpoRoute", "김포공항", 4, "evening"),
    ("PajuGimpoRoute", "파주·김포", 2, "weekend"),
]
# The first drive of all: the course the video's first part drives live, two months back.
FIRST = ("SEONGSU", "성수 주말 나들이")


def routes():
    src = open(COURSES, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"private object (\w+Route) \{\s*val points: List<Pair<Double, Double>> = listOf\((.*?)\n\s*\)", src, re.S):
        pts = [(float(a), float(b)) for a, b in re.findall(r"(-?\d+\.\d+) to (-?\d+\.\d+)", m.group(2))]
        out[m.group(1)] = pts
    seongsu = re.search(r"val SEONGSU_ROUTE: List<Pair<Double, Double>> = listOf\((.*?)\n\s*\)", src, re.S)
    out["SEONGSU"] = [(float(a), float(b)) for a, b in re.findall(r"(-?\d+\.\d+) to (-?\d+\.\d+)", seongsu.group(1))]
    return out


def cell_of(lat, lng):
    n = 1 << Z
    x = int((lng + 180.0) / 360.0 * n)
    r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n)
    return x, y


def pack(x, y):
    return (x << 32) | (y & 0xFFFFFFFF)


def dist_m(a, b):
    lat1, lng1 = map(math.radians, a)
    lat2, lng2 = map(math.radians, b)
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def densify(pts, step):
    """Points every [step] metres along the polyline."""
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        d = dist_m(a, b)
        n = max(1, int(d // step))
        for i in range(1, n + 1):
            t = i / n
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out


def swath(pts, radius):
    cells = set()
    for lat, lng in densify(pts, 5.0):
        x, y = cell_of(lat, lng)
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                cells.add(pack(x + dx, y + dy))
    return cells


def length_m(pts):
    return sum(dist_m(a, b) for a, b in zip(pts, pts[1:]))


def schedule(kind, count):
    """Start times for [count] one-way trips over the last eight weeks, newest last."""
    days = [TODAY - timedelta(days=d) for d in range(56, 0, -1)]
    if kind == "commute":
        slots = [(d, h, m) for d in days if d.weekday() < 5 for (h, m) in ((8, 10), (18, 40))]
    elif kind == "weekend":
        slots = [(d, h, m) for d in days if d.weekday() >= 5 for (h, m) in ((10, 30), (16, 50))]
    else:
        slots = [(d, h, m) for d in days if d.weekday() in (2, 4) for (h, m) in ((19, 20), (21, 40))]
    # Spread [count] trips (in out-and-back pairs) evenly, ending with the most recent slots.
    pairs = count // 2
    step = max(1, (len(slots) // 2) // max(1, pairs))
    chosen = []
    for i in range(pairs):
        k = len(slots) - 2 - i * 2 * step
        if k < 0:
            break
        chosen += [slots[k], slots[k + 1]]
    return sorted(datetime(d.year, d.month, d.day, h, m, tzinfo=KST) for d, h, m in chosen)


def main():
    all_routes = routes()
    trips_plan = []
    for name, label, count, kind in PLAN:
        route = all_routes[name]
        for i, start in enumerate(schedule(kind, count)):
            outbound = i % 2 == 0
            trips_plan.append((start, label, route if outbound else list(reversed(route)), outbound))
    trips_plan.sort(key=lambda t: t[0])
    first_at = trips_plan[0][0].replace(hour=10, minute=5) - timedelta(days=1)
    trips_plan.insert(0, (first_at, FIRST[1], all_routes[FIRST[0]], True))

    cells = {}            # cell -> [firstSeenAt, tripId, source, visits]
    trips, landings, stamps, track = [], [], [], []
    stamp_seen = set()

    def stamp(code, at, trip_id, lat, lng):
        stamps.append([len(stamps) + 1, code, at, trip_id, lat, lng])
        stamp_seen.add(code)

    for trip_id, (start, label, route, outbound) in enumerate(trips_plan, start=1):
        meters = length_m(route)
        minutes = meters / 1000 / 35 * 60          # about 35 km/h in town
        start_ms = int(start.timestamp() * 1000)
        end_ms = start_ms + int(minutes * 60_000)
        new_cells, driven = 0, swath(route, DRIVE_RADIUS)
        for c in driven:
            if c in cells:
                cells[c][3] += 1
            else:
                cells[c] = [start_ms, trip_id, SOURCE_DRIVE, 1]
                new_cells += 1
        # Drop-off point at the far end of each outbound trip (and the area around it).
        end = route[-1]
        if outbound:
            landings.append([len(landings) + 1, end[0], end[1], end_ms, trip_id])
            ex, ey = cell_of(*end)
            for dx in range(-LANDING_RADIUS, LANDING_RADIUS + 1):
                for dy in range(-LANDING_RADIUS, LANDING_RADIUS + 1):
                    c = pack(ex + dx, ey + dy)
                    if c not in cells:
                        cells[c] = [end_ms, trip_id, SOURCE_LANDING, 1]
                        new_cells += 1
        new_ratio = new_cells / max(1, len(driven))
        trips.append([trip_id, start_ms, end_ms, new_cells, round(meters, 1), round(meters * min(1.0, new_ratio), 1)])
        dense = densify(route, TRACK_STEP_M)
        for k, (lat, lng) in enumerate(dense):
            track.append([len(track) + 1, trip_id, round(lat, 7), round(lng, 7), start_ms + int((end_ms - start_ms) * k / max(1, len(dense) - 1))])
        # Stamps a real two months would have given.
        s_lat, s_lng = route[0]
        if trip_id == 1:
            # What the 성수 drive gives (the video's first part): window, rain, rear seat, mission.
            for code in ("FIRST_DRIVE", "OPEN_AIR", "RAIN", "FAMILY", "CREW_MISSION", "DONG_HOPPER", "NEW_DISTRICT", "PROVINCE_CROSS"):
                stamp(code, start_ms + 60_000 * len(stamps), trip_id, s_lat, s_lng)
        if start.weekday() < 5 and start.hour < 10 and "COMMUTE" not in stamp_seen:
            stamp("COMMUTE", start_ms, trip_id, s_lat, s_lng)
        if start.weekday() >= 5 and "WEEKEND" not in stamp_seen:
            stamp("WEEKEND", start_ms, trip_id, s_lat, s_lng)
        if start.hour >= 21 and "NIGHT" not in stamp_seen:
            stamp("NIGHT", start_ms, trip_id, s_lat, s_lng)
        if start.hour == 16 and "SUNSET" not in stamp_seen:
            stamp("SUNSET", end_ms, trip_id, end[0], end[1])
        if outbound and "ARRIVAL" not in stamp_seen and label == "은평한옥마을":
            stamp("ARRIVAL", end_ms, trip_id, end[0], end[1])
        if not outbound and "HOMECOMING" not in stamp_seen:
            stamp("HOMECOMING", end_ms, trip_id, end[0], end[1])
        if label == "상암 월드컵공원" and "PROVINCE_CROSS" not in stamp_seen:
            stamp("PROVINCE_CROSS", start_ms + 300_000, trip_id, route[len(route) // 2][0], route[len(route) // 2][1])
        if label == "은평한옥마을" and "NEW_DISTRICT" not in stamp_seen:
            stamp("NEW_DISTRICT", start_ms + 240_000, trip_id, route[len(route) // 3][0], route[len(route) // 3][1])
        if meters >= 30_000 and "LONG_DRIVE" not in stamp_seen:
            stamp("LONG_DRIVE", end_ms, trip_id, end[0], end[1])
        if label == "파주·김포" and "DONG_HOPPER" not in stamp_seen:
            stamp("DONG_HOPPER", end_ms, trip_id, end[0], end[1])

    data = {
        "format": "fogdrive-backup",
        "version": 1,
        "exportedAt": int(TODAY.timestamp() * 1000),
        "cells": [[c, *v] for c, v in sorted(cells.items())],
        "trips": trips,
        "landings": landings,
        "stamps": stamps,
        "track": track,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))

    # What the map will show.
    levels = [(50, "빨강"), (20, "주황"), (10, "보라"), (5, "남색"), (3, "파랑"), (2, "옅은 파랑")]
    counts = {name: 0 for _, name in levels}
    for v in cells.values():
        for n, name in levels:
            if v[3] >= n:
                counts[name] += 1
                break
    print(f"{os.path.relpath(OUT, ROOT)}: 칸 {len(cells):,} · 주행 {len(trips)} · 하차 {len(landings)} · 스탬프 {len(stamps)}종 · 경로 점 {len(track):,}")
    print("자주 다닌 길 칸 수: " + ", ".join(f"{k} {v:,}" for k, v in counts.items()))
    first, last = trips[0][1], trips[-1][2]
    fmt = lambda ms: datetime.fromtimestamp(ms / 1000, KST).strftime("%m/%d %H:%M")
    print(f"기간: {fmt(first)} ~ {fmt(last)}")


if __name__ == "__main__":
    sys.exit(main())
