#!/usr/bin/env python3
"""녹화본(tools/demo/out/raw-burung/*.webm) → 시연 영상 장면 클립(assets/clips/*.mp4 + *.wav).

    python3 prepare_clips.py            # 녹화본이 없으면 GitHub Release burung-demo-raw-v1에서 받음 (gh 로그인 필요)

앱 영역(2560×1440 중 855,90부터 1705×1210)만 잘라 30 fps H.264로 만들고, 빠르게 감는 구간은 소리를 빼고
1배속 구간(음성 안내가 나오는 곳)만 앱 소리를 남깁니다. 소리는 같은 길이의 wav로 따로 둡니다(영상은 무음).
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RAW = os.path.join(ROOT, "tools", "demo", "out", "raw-burung")
OUT = os.path.join(HERE, "assets", "clips")
RELEASE = "burung-demo-raw-v2"   # the 2026-11-02 recording (record.py part1 + part2)
FF = shutil.which("ffmpeg") or r"D:\Tools\ffmpeg\ffmpeg-9.0.2-full_build\bin\ffmpeg.exe"
CROP = "crop=1705:1210:855:90"
APP_GAIN_DB = 21   # the emulator records the app's voice quietly (peaks about -27 dBFS)

# name: (recording, [(from s, to s, speed)]); speed 1 keeps the sound. Times are seconds into
# tools/demo/out/raw-burung/<recording>.webm (record.py). build.py maps its raw times through these.
CLIPS = {
    "boot": ("s_boot", [(3.0, 9.0, 1)]),
    "map": ("s_map", [(0.5, 8.0, 1.2)]),
    "howto": ("s_demo_howto", [(0.5, 11.5, 1.4)]),
    "kids": ("s_kids", [(0.5, 6.5, 1)]),
    "kinder": ("s_kinder", [(1.0, 27.0, 6), (27.0, 35.0, 1)]),
    "office": ("s_office", [(0.3, 2.8, 0.6), (2.8, 43.0, 10), (43.0, 50.5, 1)]),
    "trips": ("s_trips", [(0.5, 11.5, 1.4)]),
    "dex": ("s_dex", [(1.5, 14.0, 1.5)]),
    "exp": ("s_exp", [(0.5, 13.5, 1.6)]),
    "suggest": ("s_suggest", [(1.0, 13.5, 1.5)]),
    "olympic": ("s_olympic", [(0.5, 14.5, 7), (14.5, 18.5, 1), (18.5, 45.5, 8), (45.5, 53.5, 1)]),
    "gangneung": ("s_gangneung", [(1.0, 357.0, 45), (357.0, 363.0, 3), (363.0, 373.0, 1)]),
    "after": ("s_after", [(0.5, 13.5, 1.25)]),
    "settings": ("s_settings", [(0.5, 17.5, 1.8)]),
}


def fetch_raw():
    names = sorted({src for src, _ in CLIPS.values()})
    missing = [n for n in names if not os.path.exists(os.path.join(RAW, n + ".webm"))]
    if not missing:
        return
    os.makedirs(RAW, exist_ok=True)
    print("녹화본 받기:", RELEASE, missing, flush=True)
    subprocess.run(["gh", "release", "download", RELEASE, "--dir", RAW, "--clobber",
                    *sum((["--pattern", n + ".webm"] for n in missing), [])], check=True)


def build(name, src, segs):
    inp = os.path.join(RAW, src + ".webm")
    v, a, vl, al = [], [], [], []
    for i, (t0, t1, sp) in enumerate(segs):
        v.append(f"[0:v]trim={t0}:{t1},setpts=(PTS-STARTPTS)/{sp},{CROP},fps=30[v{i}]")
        dur = (t1 - t0) / sp
        if sp == 1:
            a.append(f"[0:a]atrim={t0}:{t1},asetpts=PTS-STARTPTS,aresample=48000,volume={APP_GAIN_DB}dB,"
                     f"afade=t=in:d=0.15,afade=t=out:st={max(0, dur - 0.2):.3f}:d=0.2[a{i}]")
        else:
            a.append(f"anullsrc=r=48000:cl=stereo,atrim=0:{dur:.3f}[a{i}]")
        vl.append(f"[v{i}]")
        al.append(f"[a{i}]")
    n = len(segs)
    fc = ";".join(v + a + [f"{''.join(vl)}concat=n={n}:v=1:a=0[v]", f"{''.join(al)}concat=n={n}:v=0:a=1,aformat=channel_layouts=stereo[a]"])
    mp4, wav = os.path.join(OUT, name + ".mp4"), os.path.join(OUT, name + ".wav")
    subprocess.run([FF, "-loglevel", "error", "-y", "-i", inp, "-filter_complex", fc,
                    "-map", "[v]", "-c:v", "libx264", "-preset", "slow", "-crf", "17", "-pix_fmt", "yuv420p", "-an", mp4,
                    "-map", "[a]", "-c:a", "pcm_s16le", wav], check=True)
    print(f"{name}: {sum((t1 - t0) / sp for t0, t1, sp in segs):.1f}s", flush=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    fetch_raw()
    for name, (src, segs) in CLIPS.items():
        if len(sys.argv) > 1 and name not in sys.argv[1:]:
            continue
        build(name, src, segs)


if __name__ == "__main__":
    main()
