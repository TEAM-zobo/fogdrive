#!/usr/bin/env bash
# Builds the promo film's (and the shortform's) assets.
#   tools/demo/promo/prepare_assets.sh            # recordings in tools/demo/out/raw → cut them; none → the release bundle
#   tools/demo/promo/prepare_assets.sh --release  # always the release bundle (the exact clips the committed films use)
# The bundle is GitHub Release demo-assets-v1 of TEAM-zobo/fogdrive (private: needs `gh auth login`): the cut
# shots, the music and the fonts. assets.sha256 pins every file, so the same commit renders the same film.
# Shots: H.264 2560x1440 30 fps with a keyframe every half second (fast seeking in the renderer).
# Fonts: Pretendard (SIL OFL 1.1). Music: "Fretless", Kevin MacLeod (incompetech.com), CC BY 4.0.
# Everything lands in tools/demo/promo/assets/ (git-ignored except icon.svg).
set -euo pipefail
cd "$(dirname "$0")"
RAW=../out/raw
A=assets
REPO=TEAM-zobo/fogdrive
BUNDLE=demo-assets-v1
mkdir -p "$A/shots" "$A/fonts" "$A/music" "$A/sfx"
VIDEO=(-r 30 -c:v libx264 -preset medium -crf 14 -g 15 -keyint_min 15 -pix_fmt yuv420p)

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

from_release() {
  command -v gh >/dev/null || { echo "gh(GitHub CLI)가 필요합니다: brew install gh / winget install GitHub.cli, 그다음 gh auth login" >&2; exit 1; }
  local tmp; tmp=$(mktemp -d)
  gh release download "$BUNDLE" --repo "$REPO" --pattern "fogdrive-$BUNDLE.zip" --dir "$tmp"
  unzip -q -o "$tmp/fogdrive-$BUNDLE.zip" -d "$A"
  rm -rf "$tmp"
  echo "shots, music, fonts ← release $BUNDLE"
}

recorded=1
for r in a2_first_map a3_first_drive a4_first_after b1_worn b2_record b3_timelapse b4_dex b5_share; do
  [ -f "$RAW/$r.webm" ] || recorded=0
done
if [ "${1:-}" = --release ] || [ $recorded = 0 ]; then
  from_release
else
shot() {  # name source start end [audio]
  local out="$A/shots/$1.mp4"
  local audio=(-an)
  # The emulator records quietly (voice peaks near -27 dBFS): lift it, keep peaks under -1 dBFS.
  [ "${5:-}" = audio ] && audio=(-af "volume=22dB,alimiter=limit=0.89:level=false" -c:a aac -b:a 192k -ar 48000)
  ffmpeg -v error -y -ss "$3" -to "$4" -i "$RAW/$2.webm" "${VIDEO[@]}" "${audio[@]}" -movflags +faststart "$out"
  echo "$1"
}

# Part 1: the first drive on a fresh install.
shot hook       a3_first_drive 3.4 12.4
shot fogwide    a2_first_map   0.5 10.5
# The detour card shows only while parked (~2 s): slowed to 0.35x and held, so it can be read.
ffmpeg -v error -y -ss 1.3 -to 3.4 -i "$RAW/a3_first_drive.webm" -vf "setpts=PTS/0.35,tpad=stop_mode=clone:stop_duration=1" \
  "${VIDEO[@]}" -an -movflags +faststart "$A/shots/detour.mp4"
echo detour
shot drive      a3_first_drive 26 40
# One whole voice line (starts 21.2 s, the next one at 25.9 s), soft edges, last frame held 1.5 s.
ffmpeg -v error -y -ss 20.6 -to 25.6 -i "$RAW/a3_first_drive.webm" -vf "tpad=stop_mode=clone:stop_duration=1.5" "${VIDEO[@]}" \
  -af "volume=22dB,alimiter=limit=0.89:level=false,afade=t=in:d=0.2,afade=t=out:st=4.7:d=0.3,apad=pad_dur=1.5" \
  -c:a aac -b:a 192k -ar 48000 -movflags +faststart "$A/shots/voice.mp4"
echo voice
shot signals    a3_first_drive 9.4 16.4
shot arrive     a3_first_drive 160 170
shot firstpath  a4_first_after 1.5 12.5
# Part 2: the same map after weeks of driving (the demo record).
shot worn       b1_worn        1.5 20.5
shot timelapse  b3_timelapse   0.5 15
shot record     b2_record      1.2 12   # from 1.2 s: the map shows until the record tab opens
shot dex        b4_dex         8.5 15.5
shot stamps     b4_dex         24 35.5
shot share      b5_share       1.6 8.5   # the shortform only: the share card is up from 1.6 s
fi

for w in Regular SemiBold Bold ExtraBold; do
  [ -f "$A/fonts/Pretendard-$w.woff2" ] || curl -fsSL -o "$A/fonts/Pretendard-$w.woff2" \
    "https://cdn.jsdelivr.net/npm/pretendard@1.3.9/dist/web/static/woff2/Pretendard-$w.woff2"
done
[ -f "$A/music/fretless.mp3" ] || curl -fsSL -o "$A/music/fretless.mp3" \
  "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Fretless.mp3"
# Sound effects from the media-use skill library (Pixabay license, no credit needed);
# installed by `tools/setup/setup.sh video`.
SFX_LIB="$HOME/.claude/skills/media-use/audio/assets/sfx"
for f in whoosh whoosh-short riser impact-bass-1 pop sparkle; do
  [ -f "$A/sfx/$f.mp3" ] || cp "$SFX_LIB/$f.mp3" "$A/sfx/$f.mp3"
done
# Via a temp file: a failed run must not leave the committed icon.svg empty.
python3 vector2svg.py > "$A/icon.svg.tmp"
mv "$A/icon.svg.tmp" "$A/icon.svg"
# Same inputs as the committed films? Fresh recordings always differ (re-record → update assets.sha256 and the bundle).
if sha256 -c --quiet assets.sha256; then
  echo "done: every asset matches assets.sha256"
else
  echo "done, but the files above differ from assets.sha256: the films will not match the committed renders" >&2
fi
