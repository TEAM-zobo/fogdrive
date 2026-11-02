#!/usr/bin/env python3
"""Cuts the recorded sections into the demo video, with captions, title cards and levelled sound.

    python3 tools/demo/build_video.py      # -> tools/demo/out/fogdrive-demo-1440p.mp4 (+ 1080p)

Needs ffmpeg (brew install ffmpeg) and the sections from record_segments.py in tools/demo/out/raw.
Captions are drawn by captions.swift (macOS fonts), so ffmpeg needs no text filters. Every cut,
freeze and caption is in EDIT below: change the numbers and run again.
Times are seconds in the raw recording; caption times are seconds within the cut piece.
"""
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "tools", "demo", "out")
RAW = os.path.join(OUT, "raw")
BUILD = os.path.join(OUT, "build")
FPS = 30

TITLE = dict(kind="card", title="안개지도 FogDrive",
             sub="시동만 걸면, 달린 길의 안개가 걷히는 차량 전용 탐험 지도",
             foot="Pleos Connect 앱 · 현대자동차그룹 차량용 앱 개발 공모전 · 팀 zobo")
END = dict(kind="card", title="안개지도 FogDrive",
           sub="달린 만큼 걷히는 나만의 지도",
           foot="팀 zobo · Pleos Connect Emulator v2.1.0에서 녹화 · 지도 © OpenStreetMap contributors, OpenFreeMap")


def cap(t0, t1, title, sub=None, tag=None):
    return dict(t0=t0, t1=t1, title=title, sub=sub, tag=tag)


def voice(t0, t1, line):
    return cap(t0, t1, f"“{line}”", tag="🔊 음성 안내")


# (kind, ...) pieces in order. "clip": raw section cut [a, b]; "still": frame of a section held for d s;
# "card": a title card for d s. gain: dB added to that piece's sound (UI sounds are quiet).
EDIT = [
    dict(kind="card", d=4.5, card=TITLE),
    dict(kind="clip", src="s02_launch", a=1.5, b=10.5, gain=26, caps=[
        cap(0.3, 4.4, "Pleos Connect 에뮬레이터에서 실제로 동작하는 화면", "Vehicle · NaviHelper · FusedLocation · TextToSpeech SDK", "LIVE DEMO"),
        cap(4.7, 9.0, "두 달 동안 출퇴근·나들이로 쌓인 나의 안개지도", "달린 길만 걷히고, 안 가 본 곳은 안개로 남아요 (시연용 예시 기록)"),
    ]),
    dict(kind="clip", src="s03_worn", a=1.0, b=19.5, gain=26, caps=[
        cap(0.6, 5.8, "자주 다닌 길은 색이 바뀌어요", "2번 파랑 → 5번 남색 → 10번 보라 → 20번 주황 → 50번 빨강"),
        cap(6.1, 9.4, "버튼 하나로 켜고 끄기", "지도·주행 화면 오른쪽 버튼"),
        cap(13.0, 18.5, "출퇴근길 52번 · 은평 나들이 22번 · 홍대·망원 12번", "내가 실제로 달린 길만 안개가 걷혀 있어요"),
    ]),
    dict(kind="clip", src="s04_drive", a=0.8, b=3.2, gain=22, caps=[]),
    dict(kind="still", src="s04_drive", at=3.2, d=4.0, caps=[
        cap(0.2, 4.0, "1분 더 쓰면 안 가 본 동네를 지나요", "내비 목적지를 보고 탐험 우회 경로 제안 → 누르면 경유지로 추가", "출발 전 · P단"),
    ]),
    dict(kind="clip", src="s04_drive", a=3.2, b=40.0, gain=22, caps=[
        cap(0.3, 5.4, "주행 중엔 지도 · 회전 안내 · 제한속도만", "조작 없이, 시동과 함께 자동으로 기록돼요", "주행 모드"),
        cap(5.7, 10.2, "빨간 출퇴근길을 벗어나 처음 가는 길로", "도로와 눈에 보이는 만큼(양쪽 약 45m)만 안개가 걷혀요"),
        voice(10.5, 13.8, "처음 와 본 신사1동이에요"),
        voice(14.3, 17.6, "처음 와 본 응암3동이에요"),
        cap(18.0, 24.4, "스탬프와 동승자 미션은 화면 알림으로", "창문 열기 → 바람길 개척 · 뒷좌석 동승자 → 이번 주행 미션", "차량 신호"),
        voice(24.9, 28.2, "처음 와 본 연희동이에요"),
        voice(31.7, 35.2, "처음 와 본 천연동이에요"),
    ]),
    dict(kind="clip", src="s04_drive", a=124.0, b=150.0, gain=22, caps=[
        cap(0.5, 6.5, "도착: P단 + 운전석 도어 열림 = 하차 지점", "내린 곳 주변은 더 넓게 걷히고 깃발이 꽂혀요", "도착"),
        cap(6.8, 13.6, "이번 주행 요약", "밝힌 면적 · 거리 · 새 길 비율 · 처음 가 본 동네와 그 동네 이야기"),
        cap(14.0, 25.8, "“주행을 마쳤어요. 이번 주행에서 안개 3.97제곱킬로미터를 걷었어요, 처음 가 본 동네는 40곳.”", tag="🔊 음성 안내"),
    ]),
    dict(kind="clip", src="s05_overview", a=0.5, b=12.0, gain=26, caps=[
        cap(0.4, 5.4, "오늘 달린 36 km가 지도에 더해졌어요"),
        cap(5.7, 11.4, "안 가 본 동네는 짙은 안개, 들어가 본 동네는 옅은 안개", "가장 가까운 안 가 본 동네를 추천하고 내비 안내까지"),
    ]),
    dict(kind="clip", src="s06_record", a=0.5, b=19.5, gain=26, caps=[
        cap(0.6, 6.5, "기록: 밝힌 면적 · 주행 거리 · 하차 지점 · 새 길 비율", "이번 주 · 이번 달 리포트로 지난주 · 지난달과 비교"),
        cap(6.8, 12.0, "주행마다 새로 밝힌 면적과 새 길 비율"),
        cap(12.8, 18.8, "주행을 누르면 그 경로를 지도에서 다시 보여 줘요"),
    ]),
    dict(kind="clip", src="s07_timelapse", a=0.5, b=14.5, gain=26, caps=[
        cap(0.5, 13.4, "개척 타임랩스", "첫 주행부터 오늘까지, 지도가 밝아진 과정을 다시 보기"),
    ]),
    dict(kind="clip", src="s08_dex", a=0.3, b=9.0, gain=26, caps=[
        cap(0.3, 8.5, "지역 도감: 시·도 → 시·군·구 → 읍·면·동", "한 칸이라도 지나간 지역만 열리고, 걷은 만큼 달성률이 올라가요"),
    ]),
    dict(kind="clip", src="s09_dex_district", a=0.4, b=23.5, gain=26, caps=[
        cap(0.5, 6.0, "동마다 개척 단계 배지", "50%에 닿으면 주행 중 음성으로도 알려 줘요"),
        cap(8.0, 12.4, "오늘 · 이번 주 · 이번 달 · 시즌 챌린지"),
        cap(12.8, 22.6, "차량 상황 스탬프 29종", "와이퍼 · 전조등 · 좌석 · 창문 · 선루프 · 주행 모드 · EV 충전 · 시간대 · 지역"),
    ]),
    dict(kind="clip", src="s10_share", a=0.5, b=8.5, gain=26, caps=[
        cap(0.5, 7.6, "내 안개지도 카드", "개척한 곳을 그린 공유용 이미지로 저장 · 공유"),
    ]),
    dict(kind="clip", src="s11_settings", a=0.5, b=13.5, gain=26, caps=[
        cap(0.5, 6.0, "Pleos SDK 연결 상태와 시연 모드", "Vehicle · AAOS 기어 · NaviHelper 연결, 서울·수도권·부산·강릉 시연 코스 17개"),
        cap(6.4, 12.6, "음성 안내 · 효과음 · 백업과 동기화", "위치 기록은 기기 안에만 저장돼요"),
    ]),
    dict(kind="card", d=5.0, card=END),
]


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("ffmpeg failed:\n" + " ".join(cmd) + "\n" + r.stderr[-3000:])


def render_images():
    """All caption PNGs and title cards in one swift run."""
    spec, n = [], 0
    icon = os.path.join(BUILD, "icon.png")
    if not os.path.exists(icon):
        run(["ffmpeg", "-v", "error", "-y", "-ss", "3.4", "-i", os.path.join(RAW, "s02_launch.webm"), "-frames:v", "1",
             "-vf", "crop=168:168:1609:514", icon])
    for i, p in enumerate(EDIT):
        if p["kind"] == "card":
            p["png"] = os.path.join(BUILD, f"card{i:02d}.png")
            spec.append(dict(file=p["png"], icon=icon, **p["card"]))
        for c in p.get("caps", []):
            c["png"] = os.path.join(BUILD, f"cap{n:03d}.png")
            n += 1
            spec.append(dict(file=c["png"], kind="lower", title=c["title"], sub=c["sub"], tag=c["tag"]))
    path = os.path.join(BUILD, "captions.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(spec, f, ensure_ascii=False)
    run(["swift", os.path.join(ROOT, "tools", "demo", "captions.swift"), path])


ENC = ["-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", "-r", str(FPS),
       "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"]


def overlay_chain(base, caps, first_input):
    """Filter text laying each caption PNG over [base], fading in and out."""
    parts, label = [], base
    for k, c in enumerate(caps):
        i = first_input + k
        fade = 0.25
        parts.append(f"[{i}:v]format=rgba,fade=in:st={c['t0']}:d={fade}:alpha=1,"
                     f"fade=out:st={c['t1'] - fade}:d={fade}:alpha=1[c{k}]")
        nxt = f"v{k}"
        parts.append(f"[{label}][c{k}]overlay=0:0:enable='between(t,{c['t0']},{c['t1']})'[{nxt}]")
        label = nxt
    return parts, label


def piece(i, p):
    out = os.path.join(BUILD, f"p{i:02d}.mp4")
    caps = p.get("caps", [])
    cap_inputs = []
    for c in caps:
        cap_inputs += ["-loop", "1", "-framerate", str(FPS), "-i", c["png"]]
    if p["kind"] == "card":
        d = p["d"]
        cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", str(FPS), "-t", str(d), "-i", p["png"],
               "-f", "lavfi", "-t", str(d), "-i", "anullsrc=r=48000:cl=stereo",
               "-vf", f"fade=in:st=0:d=0.6,fade=out:st={d - 0.6}:d=0.6", "-shortest", *ENC, out]
        run(cmd)
        return out
    src = os.path.join(RAW, p["src"] + ".webm")
    if p["kind"] == "still":
        d = p["d"]
        frame = os.path.join(BUILD, f"still{i:02d}.png")
        run(["ffmpeg", "-v", "error", "-y", "-ss", str(p["at"]), "-i", src, "-frames:v", "1", frame])
        inputs = ["-loop", "1", "-framerate", str(FPS), "-t", str(d), "-i", frame,
                  "-f", "lavfi", "-t", str(d), "-i", "anullsrc=r=48000:cl=stereo"]
        chain, label = overlay_chain("0:v", caps, 2)
        graph = ";".join(chain + [f"[{label}]format=yuv420p[vout]"]) if chain else "[0:v]format=yuv420p[vout]"
        run(["ffmpeg", "-v", "error", "-y", *inputs, *cap_inputs, "-filter_complex", graph,
             "-map", "[vout]", "-map", "1:a", "-t", str(d), *ENC, out])
        return out
    a, b = p["a"], p["b"]
    d = b - a
    inputs = ["-ss", str(a), "-t", str(d), "-i", src]
    chain, label = overlay_chain("base", caps, 1)
    vf = [f"[0:v]fps={FPS},setpts=PTS-STARTPTS,fade=in:st=0:d=0.2,fade=out:st={d - 0.2}:d=0.2[base]"] + chain
    vf.append(f"[{label}]format=yuv420p[vout]")
    # Emulator sound is quiet: lift it, keep peaks under -1 dBFS, fade the cut edges.
    vf.append(f"[0:a]aresample=48000,asetpts=PTS-STARTPTS,volume={p['gain']}dB,alimiter=limit=0.89:level=false,"
              f"afade=in:st=0:d=0.15,afade=out:st={d - 0.2}:d=0.2,apad[aout]")
    run(["ffmpeg", "-v", "error", "-y", *inputs, *cap_inputs, "-filter_complex", ";".join(vf),
         "-map", "[vout]", "-map", "[aout]", "-t", str(d), *ENC, out])
    return out


def main():
    os.makedirs(BUILD, exist_ok=True)
    missing = [p["src"] for p in EDIT if "src" in p and not os.path.exists(os.path.join(RAW, p["src"] + ".webm"))]
    if missing:
        sys.exit("missing recordings: " + ", ".join(sorted(set(missing))))
    render_images()
    pieces = []
    for i, p in enumerate(EDIT):
        pieces.append(piece(i, p))
        print(f"piece {i:02d} {p.get('src', p['kind'])}")
    lst = os.path.join(BUILD, "pieces.txt")
    with open(lst, "w") as f:
        f.writelines(f"file '{x}'\n" for x in pieces)
    master = os.path.join(OUT, "fogdrive-demo-1440p.mp4")
    run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", master])
    hd = os.path.join(OUT, "fogdrive-demo-1080p.mp4")
    run(["ffmpeg", "-v", "error", "-y", "-i", master, "-vf", "scale=1920:1080:flags=lanczos", "-c:v", "libx264",
         "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", hd])
    dur = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", master],
                         capture_output=True, text=True).stdout.strip()
    print(f"{os.path.relpath(master, ROOT)} ({float(dur):.1f} s), {os.path.relpath(hd, ROOT)}")


if __name__ == "__main__":
    main()
