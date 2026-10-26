#!/usr/bin/env bash
# FogDrive test environment: Android SDK, Pleos Connect emulator, build, install, run.
# Every step is idempotent, so `all` can be re-run after a failure. See docs/SETUP.md.
#
#   tools/setup/setup.sh doctor            # check what is installed (changes nothing)
#   tools/setup/setup.sh sdk               # SDK packages + local.properties
#   tools/setup/setup.sh image             # Pleos Connect v2.1 system image (~4.7 GB download)
#   tools/setup/setup.sh avd [--qa]        # create the AVD (--qa: also the headless QA AVD)
#   tools/setup/setup.sh boot [--headless] # start the emulator and wait for boot
#   tools/setup/setup.sh app               # build the debug APK, install it and launch it
#   tools/setup/setup.sh test              # unit tests
#   tools/setup/setup.sh all [--headless]  # sdk → image → avd → boot → app
#   tools/setup/setup.sh stop [--headless] # shut the emulator down
#   tools/setup/setup.sh video             # demo/promo video tools: ffmpeg, Node.js 22+, HyperFrames skills
#   tools/setup/setup.sh boot --record     # QA emulator for recording: no window, sound on, clock at 11:00 KST
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AVD_NAME="${FOG_AVD:-Pleos_Connect_v2.1}"
QA_AVD_NAME="Pleos_Connect_QA"            # name fixed by qa/fogqa/device.py
SCREEN_SERIAL="emulator-5554"
QA_SERIAL="emulator-5556"
IMAGE_PKG_DIR="system-images/android-34/pleos_car_emulator"
HOME_LNG=126.89592                        # 고양시 향동 향기5로 66, start of every demo course
HOME_LAT=37.60852
PACKAGE="com.zobo.fogdrive"
CMDLINE_TOOLS_BUILD=11076708

case "$(uname -s)" in
  Darwin) OS=mac ;;
  Linux) OS=linux ;;
  *) echo "macOS/Linux 전용입니다. Windows는 docs/SETUP.md의 Android Studio 절차를 따르세요." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) ABI=arm64-v8a; IMAGE_XML=crp-arm64-sys-img.xml ;;
  *) ABI=x86_64; IMAGE_XML=crp-sys-img.xml ;;
esac
IMAGE_REPO="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x"

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok() { printf '  \033[32m[OK]\033[0m %s\n' "$*"; }
miss() { printf '  \033[31m[없음]\033[0m %s\n' "$*"; MISSING=1; }
die() { printf '\033[31m오류:\033[0m %s\n' "$*" >&2; exit 1; }

# --- locations --------------------------------------------------------------------------------

sdk_dir() {   # same order as Gradle: local.properties first
  if [ -f "$ROOT/local.properties" ]; then
    local d; d=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)
    if [ -n "$d" ]; then echo "$d"; return; fi
  fi
  if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return; fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
  if [ "$OS" = mac ]; then echo "$HOME/Library/Android/sdk"; else echo "$HOME/Android/Sdk"; fi
}
SDK="$(sdk_dir)"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

# JDK 17 (CI uses 17; AGP 8.8 also runs on 21). Picks 17 when it is installed.
java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME"; return; fi
  if [ "$OS" = mac ]; then
    /usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true
  else
    local j; j=$(command -v java || true)
    [ -n "$j" ] && dirname "$(dirname "$(readlink -f "$j")")"
  fi
}
java_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

# --- doctor -----------------------------------------------------------------------------------

cmd_doctor() {
  MISSING=0
  say "환경 점검 ($OS, $ABI)"
  local jh; jh=$(java_home)
  if [ -n "$jh" ] && [ "$(java_major "$jh")" -ge 17 ] 2>/dev/null; then ok "JDK $(java_major "$jh"): $jh"
  else miss "JDK 17 이상 — mac: brew install --cask temurin@17 / linux: apt install openjdk-17-jdk"; fi
  if command -v ffmpeg >/dev/null; then ok "ffmpeg (영상 제작용)"; else echo "  [선택] ffmpeg (영상 제작용) — setup.sh video"; fi
  if command -v node >/dev/null && [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 22 ]; then ok "Node.js $(node -v) (HyperFrames용)"
  else echo "  [선택] Node.js 22+ (HyperFrames 홍보 영상용) — setup.sh video"; fi
  if command -v python3 >/dev/null; then ok "python3 $(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])') (QA 도구용)"
  else miss "python3 (QA 도구용)"; fi
  [ -d "$SDK" ] && ok "Android SDK: $SDK" || miss "Android SDK ($SDK) — setup.sh sdk"
  [ -x "$SDKMANAGER" ] && ok "cmdline-tools (sdkmanager)" || miss "cmdline-tools — setup.sh sdk"
  [ -x "$ADB" ] && ok "platform-tools (adb)" || miss "platform-tools — setup.sh sdk"
  [ -x "$EMULATOR" ] && ok "emulator $("$EMULATOR" -version 2>/dev/null | sed -n 's/.*version \([0-9.]*\).*/\1/p' | head -1)" || miss "emulator — setup.sh sdk"
  [ -f "$SDK/platforms/android-35/optional/android.car.jar" ] && ok "platforms;android-35 (android.car 포함)" || miss "platforms;android-35 — setup.sh sdk"
  [ -f "$ROOT/local.properties" ] && ok "local.properties" || miss "local.properties — setup.sh sdk"
  [ -f "$SDK/$IMAGE_PKG_DIR/$ABI/system.img" ] && ok "Pleos Connect 시스템 이미지 ($ABI)" || miss "Pleos Connect 시스템 이미지 — setup.sh image"
  [ -f "$AVD_HOME/$AVD_NAME.ini" ] && ok "AVD $AVD_NAME" || miss "AVD $AVD_NAME — setup.sh avd"
  [ -f "$AVD_HOME/$QA_AVD_NAME.ini" ] && ok "AVD $QA_AVD_NAME (QA 백그라운드 실행용, 선택)" || echo "  [선택] AVD $QA_AVD_NAME — setup.sh avd --qa"
  if [ -x "$ADB" ]; then
    local devs; devs=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | tr '\n' ' ')
    [ -n "$devs" ] && ok "실행 중인 기기: $devs" || echo "  [정보] 실행 중인 에뮬레이터 없음 — setup.sh boot"
    for s in $devs; do
      if "$ADB" -s "$s" shell pm path "$PACKAGE" >/dev/null 2>&1; then ok "$s: 앱 설치됨"; else echo "  [정보] $s: 앱 미설치 — setup.sh app"; fi
    done
  fi
  [ "$MISSING" = 0 ] && say "준비 완료" || say "빠진 항목이 있습니다. 'tools/setup/setup.sh all'로 한 번에 설치할 수 있습니다."
}

# --- sdk --------------------------------------------------------------------------------------

cmd_sdk() {
  say "Android SDK: $SDK"
  mkdir -p "$SDK"
  if [ ! -x "$SDKMANAGER" ]; then
    say "cmdline-tools 설치"
    local tmp; tmp=$(mktemp -d)
    curl -fL --progress-bar -o "$tmp/clt.zip" \
      "https://dl.google.com/android/repository/commandlinetools-${OS}-${CMDLINE_TOOLS_BUILD}_latest.zip"
    unzip -q "$tmp/clt.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  local jh; jh=$(java_home)
  [ -n "$jh" ] || die "JDK가 없습니다. JDK 17을 설치한 뒤 다시 실행하세요."
  say "SDK 패키지 설치 (라이선스 자동 동의)"
  yes | JAVA_HOME="$jh" "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
  JAVA_HOME="$jh" "$SDKMANAGER" --sdk_root="$SDK" \
    "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0"
  if [ ! -f "$ROOT/local.properties" ]; then
    echo "sdk.dir=$SDK" > "$ROOT/local.properties"
    ok "local.properties 생성"
  fi
}

# --- Pleos system image -----------------------------------------------------------------------

cmd_image() {
  local dest="$SDK/$IMAGE_PKG_DIR/$ABI"
  if [ -f "$dest/system.img" ]; then ok "시스템 이미지 이미 설치됨: $dest"; return; fi
  say "Pleos Connect 시스템 이미지 정보 ($IMAGE_XML)"
  # The repository XML Android Studio reads (SDK Update Sites); url + sha1 of the archive.
  local meta url sum size
  meta=$(curl -fsSL "$IMAGE_REPO/$IMAGE_XML" | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
c = next(e for e in root.iter() if e.tag.endswith("complete"))
print(c.findtext("url"), c.findtext("checksum"), c.findtext("size"))')
  read -r url sum size <<<"$meta"
  local cache="${FOG_CACHE:-$HOME/.cache/fogdrive}"; mkdir -p "$cache"
  local zip="$cache/$(basename "$url")"
  local need_kb=$(( size / 1024 * 3 ))     # archive + unpacked copy, with headroom
  local free_kb; free_kb=$(df -k "$cache" | awk 'NR==2 {print $4}')
  [ "$free_kb" -gt "$need_kb" ] || die "디스크 공간 부족: 약 $(( need_kb / 1024 / 1024 ))GB 필요"
  if [ ! -f "$zip" ] || [ "$(stat -f%z "$zip" 2>/dev/null || stat -c%s "$zip")" != "$size" ]; then
    say "다운로드 $(( size / 1024 / 1024 ))MB → $zip (중단돼도 이어받기)"
    curl -fL -C - --progress-bar -o "$zip" "$url"
  fi
  say "SHA-1 확인"
  [ "$(shasum -a 1 "$zip" | awk '{print $1}')" = "$sum" ] || die "체크섬 불일치. $zip 을 지우고 다시 실행하세요."
  say "압축 해제 → $dest"
  local tmp; tmp=$(mktemp -d "$cache/unpack.XXXX")
  unzip -q "$zip" -d "$tmp"
  local src; src=$(dirname "$(find "$tmp" -name system.img -maxdepth 3 | head -1)")
  [ -f "$src/system.img" ] || die "압축 안에서 system.img를 찾지 못했습니다."
  mkdir -p "$(dirname "$dest")"
  rm -rf "$dest"
  mv "$src" "$dest"
  rm -rf "$tmp"
  ok "설치 완료 (다운로드 파일은 $zip 에 남아 있습니다. 필요 없으면 지우세요)"
}

# --- AVD --------------------------------------------------------------------------------------

write_avd() {   # name ram_mb
  local name=$1 ram=$2 dir="$AVD_HOME/$1.avd"
  if [ -f "$AVD_HOME/$name.ini" ]; then ok "AVD $name 이미 있음"; return; fi
  mkdir -p "$dir"
  cat > "$AVD_HOME/$name.ini" <<EOF
avd.ini.encoding=UTF-8
path=$dir
path.rel=avd/$name.avd
target=android-34
EOF
  # Same settings as the Pleos guide (Cold boot, 4 GB RAM) on the 2560x1440 head-unit screen.
  local arch=arm64; [ "$ABI" = x86_64 ] && arch=x86_64
  cat > "$dir/config.ini" <<EOF
AvdId=$name
avd.ini.displayname=${name//_/ }
avd.ini.encoding=UTF-8
abi.type=$ABI
hw.cpu.arch=$arch
hw.cpu.ncore=4
hw.ramSize=$ram
vm.heapSize=512
disk.dataPartition.size=8G
hw.device.manufacturer=42dot
hw.device.name=Pleos_Connect_v2_0
hw.lcd.width=2560
hw.lcd.height=1440
hw.lcd.density=160
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.gps=yes
hw.audioInput=yes
hw.initialOrientation=landscape
image.sysdir.1=$IMAGE_PKG_DIR/$ABI/
tag.id=android-automotive
tag.display=Automotive
PlayStore.enabled=false
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
fastboot.chosenSnapshotFile=
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
EOF
  ok "AVD $name 생성 ($dir)"
}

cmd_avd() {
  [ -f "$SDK/$IMAGE_PKG_DIR/$ABI/system.img" ] || die "시스템 이미지가 없습니다. 먼저 setup.sh image"
  mkdir -p "$AVD_HOME"
  write_avd "$AVD_NAME" 4096
  [ "${1:-}" = "--qa" ] && write_avd "$QA_AVD_NAME" 3072
  return 0
}

# --- emulator ---------------------------------------------------------------------------------

booted() { [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

cmd_boot() {
  local headless=0 record=0 avd=$AVD_NAME serial=$SCREEN_SERIAL vc=8088
  if [ "${1:-}" = "--headless" ] || [ "${1:-}" = "--record" ]; then headless=1; avd=$QA_AVD_NAME; serial=$QA_SERIAL; vc=18088; fi
  [ "${1:-}" = "--record" ] && record=1
  [ -f "$AVD_HOME/$avd.ini" ] || die "AVD $avd 가 없습니다. setup.sh avd$([ $headless = 1 ] && echo ' --qa')"
  "$ADB" start-server >/dev/null 2>&1
  if booted "$serial"; then
    [ $record = 1 ] && echo "  [주의] 이미 켜진 에뮬레이터는 소리·시계가 녹화용이 아닐 수 있습니다. setup.sh stop --record 후 다시 boot --record 하세요."
    ok "$serial 이미 실행 중"
  else
    local port=${serial#emulator-} log="${TMPDIR:-/tmp}/fogdrive-$avd.log"
    local args=(-avd "$avd" -port "$port" -no-snapshot -no-boot-anim -gpu host)
    if [ $record = 1 ]; then
      # Recording: keep the sound, and start the clock at 11:00 KST today so the map is in day mode
      # (the app follows the real sun at the car's position, and adb may not set the time).
      args+=(-no-window -qemu -rtc "base=$(date -u +%Y-%m-%d)T02:00:00")
    elif [ $headless = 1 ]; then
      args+=(-no-window -no-audio)
    fi
    say "$avd 부팅 ($serial$([ $headless = 1 ] && echo ', 창 없음')) · 로그 $log"
    nohup "$EMULATOR" "${args[@]}" >"$log" 2>&1 &
    local end=$(( $(date +%s) + 420 ))
    until booted "$serial"; do
      [ "$(date +%s)" -lt "$end" ] || die "부팅 시간 초과(7분). 로그: $log"
      sleep 5
    done
    sleep 5   # vehicle / navi services come up just after boot_completed
    ok "부팅 완료"
  fi
  # Network time would pull the recording clock back to now.
  [ $record = 1 ] && "$ADB" -s "$serial" shell settings put global auto_time 0 && ok "시계: $("$ADB" -s "$serial" shell date | tr -d '\r') (녹화용, 자동 시간 끔)"
  "$ADB" -s "$serial" emu geo fix "$HOME_LNG" "$HOME_LAT" >/dev/null
  "$ADB" -s "$serial" forward "tcp:$vc" tcp:8088 >/dev/null
  ok "위치: 향기5로 66 · Virtual Console: http://127.0.0.1:$vc/"
}

cmd_stop() {
  local serial=$SCREEN_SERIAL; { [ "${1:-}" = "--headless" ] || [ "${1:-}" = "--record" ]; } && serial=$QA_SERIAL
  "$ADB" -s "$serial" emu kill >/dev/null 2>&1 && ok "$serial 종료" || ok "$serial 실행 중 아님"
}

# --- build / run ------------------------------------------------------------------------------

gradle() {
  local jh; jh=$(java_home)
  [ -n "$jh" ] || die "JDK가 없습니다."
  (cd "$ROOT" && JAVA_HOME="$jh" ./gradlew "$@")
}

cmd_app() {
  local serial=${FOG_SERIAL:-}
  if [ -z "$serial" ]; then
    if booted "$SCREEN_SERIAL"; then serial=$SCREEN_SERIAL
    elif booted "$QA_SERIAL"; then serial=$QA_SERIAL
    else die "실행 중인 에뮬레이터가 없습니다. setup.sh boot"; fi
  fi
  say "디버그 APK 빌드"
  gradle :app:assembleDebug
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  say "설치 → $serial (런타임 권한 자동 허용)"
  "$ADB" -s "$serial" install -r -g "$apk"
  say "실행"
  # AAOS runs apps as the current (non-system) user.
  "$ADB" -s "$serial" shell am start --user current -n "$PACKAGE/.ui.MainActivity" >/dev/null
  # Right after a cold boot the first start can take well over 3 s.
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 3
    "$ADB" -s "$serial" shell pidof "$PACKAGE" >/dev/null && break
  done
  if "$ADB" -s "$serial" shell pidof "$PACKAGE" >/dev/null; then ok "앱 실행 중 ($serial)"
  else die "앱 프로세스가 없습니다. adb -s $serial logcat -b crash 로 확인하세요."; fi
}

cmd_test() {
  say "단위 테스트"
  gradle :app:testDebugUnitTest
  ok "리포트: app/build/reports/tests/testDebugUnitTest/index.html"
}

# --- video tools ----------------------------------------------------------------------------

cmd_video() {
  say "영상 제작 도구 (docs/VIDEO.md)"
  if ! command -v ffmpeg >/dev/null; then
    [ "$OS" = mac ] && command -v brew >/dev/null || die "ffmpeg가 없습니다. https://ffmpeg.org 에서 설치하세요."
    brew install ffmpeg
  fi
  ok "$(ffmpeg -version | head -1 | cut -d' ' -f1-3)"
  if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
    [ "$OS" = mac ] && command -v brew >/dev/null || die "Node.js 22 이상이 필요합니다. https://nodejs.org"
    brew install node@22 && brew link --overwrite --force node@22
    [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 22 ] || die "PATH의 node가 아직 $(node -v)입니다(nvm 등). Node.js 22 이상을 먼저 쓰도록 바꾸세요."
  fi
  ok "Node.js $(node -v)"
  # The CLI version the promo project pins (tools/demo/promo/package.json), and its agent skills.
  (cd "$ROOT/tools/demo/promo" && npx -y hyperframes@0.8.196 --version >/dev/null && npx -y hyperframes@0.8.196 skills update >/dev/null)
  ok "HyperFrames 0.8.196 + Claude Code 스킬(hyperframes, hyperframes-cli, hyperframes-registry, media-use)"
}

cmd_all() {
  cmd_sdk
  cmd_image
  if [ "${1:-}" = "--headless" ]; then cmd_avd --qa; else cmd_avd; fi
  cmd_boot "${1:-}"
  # --headless installs on the QA emulator it just booted, never on a screen emulator the user has open.
  if [ "${1:-}" = "--headless" ]; then FOG_SERIAL=$QA_SERIAL cmd_app; else cmd_app; fi
  cmd_doctor
}

cmd=${1:-doctor}; shift || true
case "$cmd" in
  doctor|sdk|image|avd|boot|stop|app|test|all|video) "cmd_$cmd" "$@" ;;
  *) sed -n '2,16p' "$0"; exit 1 ;;
esac
