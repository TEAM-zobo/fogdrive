# FogDrive test environment for Windows: Android SDK, Pleos Connect emulator, build, install, run.
# Same steps as tools/setup/setup.sh (macOS/Linux). Every step is idempotent. See docs/SETUP.md.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup\setup.ps1 doctor
#   ... setup.ps1 sdk | image | avd [-qa] | boot [-headless] | app | test | all [-headless] | stop [-headless]
#   ... setup.ps1 video          # demo/promo video tools: ffmpeg, Node.js 22+, HyperFrames skills
#   ... setup.ps1 boot -record   # QA emulator for recording: no window, sound on, clock at 11:00 KST
#
# Saved as UTF-8 with BOM so Windows PowerShell 5.1 reads the Korean messages correctly.
param(
  [Parameter(Position = 0)] [string] $Command = "doctor",
  [switch] $Headless,
  [switch] $Qa,
  [switch] $Record
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$Root = (Resolve-Path "$PSScriptRoot\..\..").Path
$AvdName = if ($env:FOG_AVD) { $env:FOG_AVD } else { "Pleos_Connect_v2.1" }
$QaAvdName = "Pleos_Connect_QA"          # name fixed by qa/fogqa/device.py
$ScreenSerial = "emulator-5554"
$QaSerial = "emulator-5556"
$Abi = "x86_64"                          # Windows uses the Intel/AMD image
$ImageXml = "crp-sys-img.xml"
$ImageRepo = "https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x"
$ImagePkgDir = "system-images\android-34\pleos_car_emulator"
$HomeLng = "126.89592"                   # 고양시 향동 향기5로 66, start of every demo course
$HomeLat = "37.60852"
$Package = "com.zobo.fogdrive"
$CmdlineToolsBuild = "11076708"

function Say($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "  [OK] $m" -ForegroundColor Green }
function Miss($m) { Write-Host "  [없음] $m" -ForegroundColor Red; $script:Missing = $true }
function Die($m) { Write-Host "오류: $m" -ForegroundColor Red; exit 1 }

# --- locations --------------------------------------------------------------------------------

function Get-SdkDir {   # same order as Gradle: local.properties first
  $props = Join-Path $Root "local.properties"
  if (Test-Path $props) {
    $line = Get-Content $props | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
    if ($line) { return $line.Substring(8).Replace("\:", ":").Replace("\\", "\") }
  }
  if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
  if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
  return Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$Sdk = Get-SdkDir
$Adb = Join-Path $Sdk "platform-tools\adb.exe"
$Emulator = Join-Path $Sdk "emulator\emulator.exe"
$SdkManager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$AvdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE ".android\avd" }

# JDK 17+: JAVA_HOME, else the JDK bundled with Android Studio, else java on PATH.
function Get-JavaHome {
  $candidates = @($env:JAVA_HOME, "$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")
  foreach ($c in $candidates) { if ($c -and (Test-Path "$c\bin\java.exe")) { return $c } }
  $java = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($java) { return (Split-Path (Split-Path $java.Source)) }
  return $null
}
function Get-JavaMajor($jh) {
  $v = (& cmd /c "`"$jh\bin\java.exe`" -version 2>&1") -join " "
  if ($v -match 'version "(\d+)') { return [int]$Matches[1] }
  return 0
}

# Windows PowerShell 5.1 turns redirected native stderr into errors under "Stop", so relax it here.
function Invoke-Adb { $ErrorActionPreference = "Continue"; & $Adb @args 2>$null }
function Test-Booted($serial) { (Invoke-Adb -s $serial shell getprop sys.boot_completed) -match "^1" }

# --- doctor -----------------------------------------------------------------------------------

function Cmd-Doctor {
  $script:Missing = $false
  Say "환경 점검 (windows, $Abi)"
  $jh = Get-JavaHome
  if ($jh -and (Get-JavaMajor $jh) -ge 17) { Ok "JDK $(Get-JavaMajor $jh): $jh" }
  else { Miss "JDK 17 이상 — Android Studio 설치(내장 JDK) 또는 winget install EclipseAdoptium.Temurin.17.JDK" }
  $py = Get-Command python -ErrorAction SilentlyContinue
  if ($py) { Ok "python $((& python --version) -replace 'Python ','') (QA 도구용)" } else { Miss "python 3 (QA 도구용) — winget install Python.Python.3.12" }
  if (Test-Path $Sdk) { Ok "Android SDK: $Sdk" } else { Miss "Android SDK ($Sdk) — setup.ps1 sdk" }
  if (Test-Path $SdkManager) { Ok "cmdline-tools (sdkmanager)" } else { Miss "cmdline-tools — setup.ps1 sdk" }
  if (Test-Path $Adb) { Ok "platform-tools (adb)" } else { Miss "platform-tools — setup.ps1 sdk" }
  if (Test-Path $Emulator) {
    Ok "emulator"
    # The emulator needs a hypervisor (Windows Hypervisor Platform or AEHD).
    $ErrorActionPreference = "Continue"
    & $Emulator -accel-check *> $null
    $ErrorActionPreference = "Stop"
    if ($LASTEXITCODE -eq 0) { Ok "하드웨어 가속 사용 가능" }
    else { Miss "하드웨어 가속 — Windows 기능에서 'Windows 하이퍼바이저 플랫폼'을 켜고 재부팅 (BIOS 가상화도 켜야 함)" }
  } else { Miss "emulator — setup.ps1 sdk" }
  if (Test-Path "$Sdk\platforms\android-35\optional\android.car.jar") { Ok "platforms;android-35 (android.car 포함)" } else { Miss "platforms;android-35 — setup.ps1 sdk" }
  if (Test-Path "$Root\local.properties") { Ok "local.properties" } else { Miss "local.properties — setup.ps1 sdk" }
  if (Test-Path "$Sdk\$ImagePkgDir\$Abi\system.img") { Ok "Pleos Connect 시스템 이미지 ($Abi)" } else { Miss "Pleos Connect 시스템 이미지 — setup.ps1 image" }
  if (Test-Path "$AvdHome\$AvdName.ini") { Ok "AVD $AvdName" } else { Miss "AVD $AvdName — setup.ps1 avd" }
  if (Test-Path "$AvdHome\$QaAvdName.ini") { Ok "AVD $QaAvdName (QA 백그라운드 실행용, 선택)" } else { Write-Host "  [선택] AVD $QaAvdName — setup.ps1 avd -qa" }
  if (Test-Path $Adb) {
    $devs = @(Invoke-Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
    if ($devs.Count) { Ok "실행 중인 기기: $($devs -join ' ')" } else { Write-Host "  [정보] 실행 중인 에뮬레이터 없음 — setup.ps1 boot" }
    foreach ($s in $devs) {
      if (Invoke-Adb -s $s shell pm path $Package) { Ok "${s}: 앱 설치됨" } else { Write-Host "  [정보] ${s}: 앱 미설치 — setup.ps1 app" }
    }
  }
  if (-not $script:Missing) { Say "준비 완료" } else { Say "빠진 항목이 있습니다. 'setup.ps1 all'로 한 번에 설치할 수 있습니다." }
}

# --- sdk --------------------------------------------------------------------------------------

function Cmd-Sdk {
  Say "Android SDK: $Sdk"
  New-Item -ItemType Directory -Force $Sdk | Out-Null
  $jh = Get-JavaHome
  if (-not $jh) { Die "JDK가 없습니다. Android Studio 또는 JDK 17을 설치한 뒤 다시 실행하세요." }
  $env:JAVA_HOME = $jh
  if (-not (Test-Path $SdkManager)) {
    Say "cmdline-tools 설치"
    $tmp = Join-Path $env:TEMP "fogdrive-clt"
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $tmp | Out-Null
    & curl.exe -fL -o "$tmp\clt.zip" "https://dl.google.com/android/repository/commandlinetools-win-${CmdlineToolsBuild}_latest.zip"
    if ($LASTEXITCODE) { Die "cmdline-tools 다운로드 실패" }
    Expand-Archive "$tmp\clt.zip" $tmp
    New-Item -ItemType Directory -Force "$Sdk\cmdline-tools" | Out-Null
    Remove-Item -Recurse -Force "$Sdk\cmdline-tools\latest" -ErrorAction SilentlyContinue
    Move-Item "$tmp\cmdline-tools" "$Sdk\cmdline-tools\latest"
    Remove-Item -Recurse -Force $tmp
  }
  Say "SDK 패키지 설치 (라이선스 자동 동의)"
  $ErrorActionPreference = "Continue"   # sdkmanager prints warnings on stderr
  (1..30 | ForEach-Object { "y" }) | & $SdkManager "--sdk_root=$Sdk" --licenses | Out-Null
  & $SdkManager "--sdk_root=$Sdk" "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0"
  if ($LASTEXITCODE) { Die "sdkmanager 실패" }
  $props = Join-Path $Root "local.properties"
  if (-not (Test-Path $props)) {
    # local.properties is a Java properties file: escape ':' and '\'.
    $escaped = $Sdk.Replace("\", "\\").Replace(":", "\:")
    [IO.File]::WriteAllText($props, "sdk.dir=$escaped`n")
    Ok "local.properties 생성"
  }
}

# --- Pleos system image -----------------------------------------------------------------------

function Cmd-Image {
  $dest = "$Sdk\$ImagePkgDir\$Abi"
  if (Test-Path "$dest\system.img") { Ok "시스템 이미지 이미 설치됨: $dest"; return }
  Say "Pleos Connect 시스템 이미지 정보 ($ImageXml)"
  # The repository XML Android Studio reads (SDK Update Sites); url + sha1 of the archive.
  [xml]$xml = (& curl.exe -fsSL "$ImageRepo/$ImageXml") -join "`n"
  $complete = $xml.SelectSingleNode("//*[local-name()='complete']")
  $url = $complete.url; $sum = $complete.checksum; $size = [int64]$complete.size
  $cache = if ($env:FOG_CACHE) { $env:FOG_CACHE } else { Join-Path $env:LOCALAPPDATA "fogdrive" }
  New-Item -ItemType Directory -Force $cache | Out-Null
  $zip = Join-Path $cache (Split-Path $url -Leaf)
  $free = (Get-PSDrive (Split-Path $cache -Qualifier).TrimEnd(":")).Free
  if ($free -lt $size * 3) { Die "디스크 공간 부족: 약 $([math]::Ceiling($size * 3 / 1GB))GB 필요" }
  if (-not (Test-Path $zip) -or (Get-Item $zip).Length -ne $size) {
    Say "다운로드 $([math]::Round($size / 1MB))MB → $zip (중단돼도 이어받기)"
    & curl.exe -fL -C - -o $zip $url
    if ($LASTEXITCODE) { Die "다운로드 실패. 다시 실행하면 이어받습니다." }
  }
  Say "SHA-1 확인"
  if ((Get-FileHash -Algorithm SHA1 $zip).Hash.ToLower() -ne $sum) { Die "체크섬 불일치. $zip 을 지우고 다시 실행하세요." }
  Say "압축 해제 → $dest"
  $tmp = Join-Path $cache "unpack"
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  New-Item -ItemType Directory $tmp | Out-Null
  & tar.exe -xf $zip -C $tmp           # bsdtar (Windows 10+) handles 4 GB+ zips quickly
  if ($LASTEXITCODE) { Die "압축 해제 실패" }
  $img = Get-ChildItem $tmp -Recurse -Filter system.img -Depth 3 | Select-Object -First 1
  if (-not $img) { Die "압축 안에서 system.img를 찾지 못했습니다." }
  New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
  Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
  Move-Item $img.DirectoryName $dest
  Remove-Item -Recurse -Force $tmp
  Ok "설치 완료 (다운로드 파일은 $zip 에 남아 있습니다. 필요 없으면 지우세요)"
}

# --- AVD --------------------------------------------------------------------------------------

function Write-Avd($name, $ram) {
  $dir = Join-Path $AvdHome "$name.avd"
  if (Test-Path "$AvdHome\$name.ini") { Ok "AVD $name 이미 있음"; return }
  New-Item -ItemType Directory -Force $dir | Out-Null
  $utf8 = New-Object Text.UTF8Encoding($false)
  [IO.File]::WriteAllText("$AvdHome\$name.ini", "avd.ini.encoding=UTF-8`npath=$dir`npath.rel=avd\$name.avd`ntarget=android-34`n", $utf8)
  # Same settings as the Pleos guide (Cold boot, 4 GB RAM) on the 2560x1440 head-unit screen.
  $config = @"
AvdId=$name
avd.ini.displayname=$($name.Replace('_', ' '))
avd.ini.encoding=UTF-8
abi.type=$Abi
hw.cpu.arch=x86_64
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
image.sysdir.1=system-images\android-34\pleos_car_emulator\$Abi\
tag.id=android-automotive
tag.display=Automotive
PlayStore.enabled=false
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
fastboot.chosenSnapshotFile=
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
"@
  [IO.File]::WriteAllText("$dir\config.ini", ($config -replace "`r`n", "`n") + "`n", $utf8)
  Ok "AVD $name 생성 ($dir)"
}

function Cmd-Avd {
  if (-not (Test-Path "$Sdk\$ImagePkgDir\$Abi\system.img")) { Die "시스템 이미지가 없습니다. 먼저 setup.ps1 image" }
  New-Item -ItemType Directory -Force $AvdHome | Out-Null
  Write-Avd $AvdName 4096
  if ($Qa) { Write-Avd $QaAvdName 3072 }
}

# --- emulator ---------------------------------------------------------------------------------

function Cmd-Boot {
  $avd = $AvdName; $serial = $ScreenSerial; $vc = 8088
  if ($Headless -or $Record) { $avd = $QaAvdName; $serial = $QaSerial; $vc = 18088 }
  if (-not (Test-Path "$AvdHome\$avd.ini")) { Die "AVD $avd 가 없습니다. setup.ps1 avd$(if ($Headless -or $Record) { ' -qa' })" }
  Invoke-Adb start-server | Out-Null
  if (Test-Booted $serial) {
    Ok "$serial 이미 실행 중"
    if ($Record) { Write-Host "  [주의] 이미 켜진 에뮬레이터는 소리·시계 설정이 녹화용이 아닐 수 있습니다. setup.ps1 stop -record 후 다시 boot -record 하세요." }
  }
  else {
    $port = $serial.Split("-")[1]
    $log = Join-Path $env:TEMP "fogdrive-$avd.log"
    $emuArgs = @("-avd", $avd, "-port", $port, "-no-snapshot", "-no-boot-anim", "-gpu", "host")
    if ($Record) {
      # Recording: keep the sound; clock at 11:00 KST today so the map is in day mode.
      $emuArgs += @("-no-window", "-qemu", "-rtc", "base=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd'))T02:00:00")
    } elseif ($Headless) { $emuArgs += @("-no-window", "-no-audio") }
    Say "$avd 부팅 ($serial$(if ($Headless -or $Record) { ', 창 없음' })) · 로그 $log"
    Start-Process -FilePath $Emulator -ArgumentList $emuArgs -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $end = (Get-Date).AddMinutes(7)
    while (-not (Test-Booted $serial)) {
      if ((Get-Date) -gt $end) { Die "부팅 시간 초과(7분). 로그: $log" }
      Start-Sleep 5
    }
    Start-Sleep 5   # vehicle / navi services come up just after boot_completed
    Ok "부팅 완료"
  }
  if ($Record) { Invoke-Adb -s $serial shell settings put global auto_time 0 | Out-Null; Ok "시계: $(Invoke-Adb -s $serial shell date) (녹화용, 자동 시간 끔)" }
  Invoke-Adb -s $serial emu geo fix $HomeLng $HomeLat | Out-Null
  Invoke-Adb -s $serial forward "tcp:$vc" tcp:8088 | Out-Null
  Ok "위치: 향기5로 66 · Virtual Console: http://127.0.0.1:$vc/"
}

function Cmd-Stop {
  $serial = if ($Headless -or $Record) { $QaSerial } else { $ScreenSerial }
  if (Test-Booted $serial) { Invoke-Adb -s $serial emu kill | Out-Null; Ok "$serial 종료" } else { Ok "$serial 실행 중 아님" }
}

# --- build / run ------------------------------------------------------------------------------

function Invoke-Gradle {
  $jh = Get-JavaHome
  if (-not $jh) { Die "JDK가 없습니다." }
  $env:JAVA_HOME = $jh
  Push-Location $Root
  $ErrorActionPreference = "Continue"   # Gradle prints warnings on stderr
  try { & "$Root\gradlew.bat" @args; if ($LASTEXITCODE) { Die "Gradle 실패" } } finally { Pop-Location }
}

function Cmd-App {
  $serial = $env:FOG_SERIAL
  if (-not $serial) {
    if (Test-Booted $ScreenSerial) { $serial = $ScreenSerial }
    elseif (Test-Booted $QaSerial) { $serial = $QaSerial }
    else { Die "실행 중인 에뮬레이터가 없습니다. setup.ps1 boot" }
  }
  Say "디버그 APK 빌드"
  Invoke-Gradle :app:assembleDebug
  $apk = "$Root\app\build\outputs\apk\debug\app-debug.apk"
  Say "설치 → $serial (런타임 권한 자동 허용)"
  & $Adb -s $serial install -r -g $apk
  if ($LASTEXITCODE) { Die "설치 실패" }
  Say "실행"
  # AAOS runs apps as the current (non-system) user.
  Invoke-Adb -s $serial shell am start --user current -n "$Package/.ui.MainActivity" | Out-Null
  Start-Sleep 3
  if (Invoke-Adb -s $serial shell pidof $Package) { Ok "앱 실행 중 ($serial)" }
  else { Die "앱 프로세스가 없습니다. adb -s $serial logcat -b crash 로 확인하세요." }
}

function Cmd-Test {
  Say "단위 테스트"
  Invoke-Gradle :app:testDebugUnitTest
  Ok "리포트: app\build\reports\tests\testDebugUnitTest\index.html"
}

function Cmd-All {
  Cmd-Sdk
  Cmd-Image
  if ($Headless) { $script:Qa = $true }
  Cmd-Avd
  Cmd-Boot
  # -headless installs on the QA emulator it just booted, never on a screen emulator the user has open.
  if ($Headless) { $env:FOG_SERIAL = $QaSerial }
  Cmd-App
  Cmd-Doctor
}

function Cmd-Video {
  Say "영상 제작 도구 (docs\VIDEO.md)"
  $ErrorActionPreference = "Continue"
  if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements }
  $node = Get-Command node -ErrorAction SilentlyContinue
  if (-not $node -or [int]((& node -p "process.versions.node.split('.')[0]")) -lt 22) { winget install --id OpenJS.NodeJS.LTS -e --accept-source-agreements --accept-package-agreements }
  Write-Host "  새로 설치했다면 PowerShell을 다시 열어 PATH를 반영한 뒤 한 번 더 실행하세요."
  Push-Location "$Root\tools\demo\promo"
  try { & npx -y hyperframes@0.8.196 skills update | Out-Null; $code = $LASTEXITCODE } catch { $code = 1 } finally { Pop-Location }
  if ($code -ne 0) { Die "HyperFrames 설치 실패: Node.js 22+와 npx가 PATH에 있는지 확인하세요." }
  Ok "HyperFrames 0.8.196 + Claude Code 스킬"
}

switch ($Command) {
  "doctor" { Cmd-Doctor }
  "sdk" { Cmd-Sdk }
  "image" { Cmd-Image }
  "avd" { Cmd-Avd }
  "boot" { Cmd-Boot }
  "stop" { Cmd-Stop }
  "app" { Cmd-App }
  "test" { Cmd-Test }
  "all" { Cmd-All }
  "video" { Cmd-Video }
  default { Get-Content $PSCommandPath -TotalCount 5 | Select-Object -Skip 1; exit 1 }
}
