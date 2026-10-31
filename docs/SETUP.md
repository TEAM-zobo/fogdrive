# 테스트 환경 셋팅

빈 PC에서 Pleos Connect 에뮬레이터를 깔고, 안개지도를 빌드·설치·실행하기까지의 절차입니다. macOS와 Windows를 나눠 설명합니다.

모든 단계는 스크립트 하나로 자동화되어 있습니다. 여러 번 실행해도 이미 된 단계는 건너뛰므로, 중간에 실패하면 원인을 고친 뒤 같은 명령을 다시 실행하면 됩니다.

| OS | 스크립트 | 에뮬레이터 이미지 |
| --- | --- | --- |
| macOS (Apple Silicon) | `tools/setup/setup.sh` | arm64-v8a |
| Windows 10/11 (Intel·AMD) | `tools/setup/setup.ps1` | x86_64 |

> Claude Code에 맡기려면 저장소를 연 뒤 이렇게 요청하면 됩니다: **"docs/SETUP.md대로 테스트 환경 셋팅하고 앱 실행해줘"**. 루트의 `CLAUDE.md`에 같은 절차가 요약되어 있습니다.

## 한눈에 보기

```
doctor → sdk → image → avd → boot → app        (all = sdk ~ app + doctor)
점검      SDK    Pleos     AVD    에뮬레이터  빌드·설치·실행
         패키지  이미지    생성    부팅
```

| 단계 | 하는 일 | 소요 |
| --- | --- | --- |
| `doctor` | 설치 상태 점검만 함(아무것도 바꾸지 않음) | 수 초 |
| `sdk` | cmdline-tools가 없으면 받고, `platform-tools`·`emulator`·`platforms;android-35`·`build-tools;35.0.0` 설치, `local.properties` 생성 | 수 분 |
| `image` | Pleos Connect v2.1.0 시스템 이미지 다운로드(macOS arm64 약 4.7GB, Windows x86_64 약 4.1GB, 이어받기·SHA-1 확인) 후 SDK에 설치 | 네트워크에 따라 10분~1시간 |
| `avd` | AVD `Pleos_Connect_v2.1` 생성(2560×1440, RAM 4GB, Cold boot, CPU 6코어 — PC 코어(Apple 실리콘은 성능 코어)가 8개 미만이면 4코어). 이미 있는 4코어 AVD는 6코어로 올림. `--qa`/`-qa`는 QA용 `Pleos_Connect_QA`도 생성 | 수 초 |
| `boot` | 에뮬레이터 부팅을 기다린 뒤 위치를 향기5로 66으로 옮기고 Virtual Console 포트(8088)를 연결 | 1~3분 |
| `app` | 디버그 APK 빌드 → 권한 허용 설치(`-g`) → 실행 → 프로세스 확인 | 첫 빌드 3~5분 |
| `test` | 단위 테스트 | 1분 |
| `stop` | 에뮬레이터 종료 | 수 초 |
| `video` | (선택) 시연·홍보 영상 도구: ffmpeg, Node.js 22+, HyperFrames 0.8.196과 Claude Code 스킬 설치. [VIDEO.md](VIDEO.md) | 수 분 |

`boot --record`/`boot -record`는 영상 녹화용입니다. QA 에뮬레이터를 창 없이, 소리를 켜고, 시계를 오전 11시(KST)로 맞춰 띄웁니다(지도가 주간 모드).

`--headless`/`-headless`를 붙이면 화면 없는 QA 에뮬레이터(`Pleos_Connect_QA`, `emulator-5556`, Virtual Console 포트 18088)로 같은 일을 합니다. 화면에 띄운 에뮬레이터와 데이터가 분리되어 서로 간섭하지 않습니다.

---

## macOS

### 0. 준비물

| 항목 | 설치 |
| --- | --- |
| Apple Silicon Mac, 여유 디스크 20GB 이상 | Intel Mac은 x86_64 이미지를 쓰며 스크립트가 자동으로 고릅니다 |
| JDK 17 | `brew install --cask temurin@17` (Android Studio가 있으면 내장 JDK도 됨) |
| Python 3 | macOS 기본 `python3` 또는 `brew install python` (QA 도구용, 표준 라이브러리만 사용) |
| Android Studio | 선택. 없어도 스크립트가 SDK를 `~/Library/Android/sdk`에 설치합니다 |

### 1. 한 번에 셋팅하고 실행

```bash
tools/setup/setup.sh doctor   # 무엇이 빠졌는지 확인
tools/setup/setup.sh all      # SDK → 이미지 → AVD → 부팅 → 빌드·설치·실행
```

마지막에 `[OK] 앱 실행 중 (emulator-5554)`가 나오고 에뮬레이터 창에 안개 지도가 보이면 끝입니다.

### 2. 단계별로 실행

```bash
tools/setup/setup.sh sdk
tools/setup/setup.sh image
tools/setup/setup.sh avd          # QA 백그라운드용까지: avd --qa
tools/setup/setup.sh boot         # 창 없이: boot --headless
tools/setup/setup.sh app
tools/setup/setup.sh test
tools/setup/setup.sh stop
```

### 3. 경로와 환경 변수

| 항목 | 기본값 | 바꾸려면 |
| --- | --- | --- |
| Android SDK | `~/Library/Android/sdk` | `local.properties`의 `sdk.dir`(우선) 또는 `ANDROID_HOME` |
| AVD | `~/.android/avd` | `ANDROID_AVD_HOME` |
| JDK | `/usr/libexec/java_home -v 17` | `JAVA_HOME` |
| 이미지 다운로드 캐시 | `~/.cache/fogdrive` | `FOG_CACHE` |
| AVD 이름 | `Pleos_Connect_v2.1` | `FOG_AVD` (Android Studio로 만든 AVD를 쓸 때) |
| `app` 대상 기기 | 실행 중인 `emulator-5554` → `emulator-5556` 순 | `FOG_SERIAL` |

---

## Windows

### 0. 준비물

| 항목 | 설치 |
| --- | --- |
| Windows 10/11 64비트(Intel·AMD), 여유 디스크 20GB 이상 | Windows on ARM은 Pleos 에뮬레이터가 지원하지 않습니다 |
| CPU 가상화 + 하이퍼바이저 | BIOS에서 VT-x/AMD-V(SVM)를 켜고, 관리자 PowerShell에서 `Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform` 실행 후 재부팅. 또는 SDK Manager → SDK Tools의 *Android Emulator hypervisor driver*(AEHD) 설치 |
| JDK 17 이상 | Android Studio를 설치하면 내장 JDK(`Android Studio\jbr`)를 자동으로 씁니다. 따로 깔려면 `winget install EclipseAdoptium.Temurin.17.JDK` |
| Python 3 | `winget install Python.Python.3.12` (QA 도구용) |
| Git for Windows | 저장소 받기용. 기본값(`core.autocrlf=true`)이어도 됩니다 |

`curl.exe`와 `tar.exe`는 Windows 10 1803 이후 기본으로 들어 있어 따로 설치할 필요가 없습니다.

### 1. 한 번에 셋팅하고 실행

PowerShell에서 저장소 폴더로 이동한 뒤:

```powershell
powershell -ExecutionPolicy Bypass -File tools\setup\setup.ps1 doctor
powershell -ExecutionPolicy Bypass -File tools\setup\setup.ps1 all
```

`-ExecutionPolicy Bypass`는 이 실행에만 적용되며, 시스템의 실행 정책은 바꾸지 않습니다. `doctor`가 "하드웨어 가속" 항목을 `[없음]`으로 보여 주면 위 준비물의 하이퍼바이저부터 켜세요. 이게 없으면 에뮬레이터가 뜨지 않습니다.

### 2. 단계별로 실행

```powershell
$s = "tools\setup\setup.ps1"
powershell -ExecutionPolicy Bypass -File $s sdk
powershell -ExecutionPolicy Bypass -File $s image
powershell -ExecutionPolicy Bypass -File $s avd            # QA 백그라운드용까지: avd -qa
powershell -ExecutionPolicy Bypass -File $s boot           # 창 없이: boot -headless
powershell -ExecutionPolicy Bypass -File $s app
powershell -ExecutionPolicy Bypass -File $s test
powershell -ExecutionPolicy Bypass -File $s stop
```

### 3. 경로와 환경 변수

| 항목 | 기본값 | 바꾸려면 |
| --- | --- | --- |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | `local.properties`의 `sdk.dir`(우선) 또는 `ANDROID_HOME` |
| AVD | `%USERPROFILE%\.android\avd` | `ANDROID_AVD_HOME` |
| JDK | `JAVA_HOME` → Android Studio 내장 `jbr` → PATH의 `java` | `JAVA_HOME` |
| 이미지 다운로드 캐시 | `%LOCALAPPDATA%\fogdrive` | `FOG_CACHE` |
| AVD 이름 / 대상 기기 | macOS와 같음 | `FOG_AVD` / `FOG_SERIAL` |

Gradle은 `gradlew.bat`, adb는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`입니다. 아래 공통 명령의 `adb`는 PATH에 `platform-tools`를 넣었다고 가정합니다.

> 이 Windows 스크립트는 Windows 11(Windows PowerShell 5.1)에서 `sdk`부터 `app`까지 돌려 확인했습니다. 문제가 생기면 아래 "Android Studio로 직접 셋팅"으로 진행해도 결과는 같습니다.

---

## Android Studio로 직접 셋팅 (macOS·Windows 공통, 스크립트 대신)

[Pleos 공식 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)와 같은 절차입니다.

1. **Tools → SDK Manager → SDK Update Sites → +**
   - 이름: `Pleos Connect System Image`
   - URL
     - macOS: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
     - Windows: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms** 탭 → *Show Package Details* → Android API 34 아래 **Pleos Connect v2.1.0 APIs** 체크 → OK. 같은 탭에서 **Android 15 (API 35)**도 설치합니다(빌드용).
3. Android Studio를 재시작합니다.
4. **Tools → Device Manager → Create device → Automotive → Pleos Connect v2.1.0**
   - Advanced Settings: Default boot **Cold**, RAM **4 GB**
   - System image: API 34 **Pleos Connect v2.1.0 APIs**
5. AVD 이름이 `Pleos_Connect_v2.1`이 아니면 스크립트를 쓸 때 `FOG_AVD`에 그 이름을 넣습니다.
6. 에뮬레이터를 실행한 뒤 `setup.sh app` / `setup.ps1 app` 또는 Android Studio의 Run으로 `app` 모듈을 실행합니다.

---

## 실행 확인 (공통)

```bash
adb devices                                    # emulator-5554  device
adb shell pidof com.zobo.burung              # 숫자가 나오면 실행 중
adb exec-out screencap -p > screen.png         # 화면 캡처
curl -s http://127.0.0.1:8088/vehicle/state    # Virtual Console 응답(JSON)
```

- 화면 왼쪽은 Pleos 차량 패널, 오른쪽에 안개지도 앱이 뜹니다. 지도 위 레벨 카드와 짙은 안개가 보이면 정상입니다.
- 앱 화면이 검게 가려져 있으면 기어가 P가 아닌 것입니다. 에뮬레이터는 P단이 아니면(차속 0이어도) 앱 화면을 막습니다. Virtual Console에서 P로 바꾸세요.
- 지도 타일은 인터넷(OpenFreeMap)에서 받으므로 에뮬레이터에 네트워크가 있어야 합니다.

## 실행 후 해 볼 것

1. **시연 모드**: 앱의 설정 → 시연 모드 → 코스 선택 → 시연 시작 → 지도 탭. 차가 실제 도로를 따라 달리며 안개가 걷힙니다. 자세한 내용은 [README의 시연 방법](../README.md#시연-방법).
2. **Virtual Console**: 브라우저에서 `http://127.0.0.1:8088/`을 열고 기어·도어·창문·와이퍼·좌석을 바꿔 봅니다.
3. **위치 옮기기**: `adb emu geo fix <경도> <위도>` (예: `adb emu geo fix 127.0559 37.5446`). 집 위치로 되돌리기는 macOS `tools/emulator/set_home.sh`, Windows `adb emu geo fix 126.89592 37.60852`.
4. **단위 테스트**: `setup.sh test` / `setup.ps1 test`.
5. **QA 자동 테스트(TC 21개, 약 20분)**:
   ```bash
   cd qa
   python3 -m fogqa list                         # Windows: python -m fogqa list
   python3 -m fogqa run TC-01                    # 화면 에뮬레이터에서 하나만
   python3 -m fogqa run --headless --shutdown    # QA 에뮬레이터를 창 없이 띄워 전체 실행(먼저 avd --qa)
   ```
   리포트는 `qa/reports/<시각>/index.html`에 저장됩니다. 웹 대시보드는 `python3 -m fogqa serve --port 8770` 후 `http://127.0.0.1:8770` (비밀번호는 `qa/.qa_password`). 자세한 내용은 [`qa/README.md`](../qa/README.md).

## 문제 해결

| 증상 | 원인·해결 |
| --- | --- |
| `image`에서 체크섬 불일치 | 다운로드가 깨졌습니다. 캐시의 이미지 파일(macOS `crp_car_arm64_emulator.zip`, Windows `crp_car_emulator.zip`)을 지우고 다시 실행 |
| `image`가 중간에 끊김 | 같은 명령을 다시 실행하면 이어받습니다 |
| `boot`가 7분 넘게 안 끝남 | 로그(`$TMPDIR/fogdrive-<AVD>.log`, Windows `%TEMP%\fogdrive-<AVD>.log`) 확인. Windows는 대부분 하이퍼바이저 미설정 |
| `emulator-5554`가 다른 에뮬레이터 | 다른 AVD가 먼저 5554를 잡고 있습니다. 끄고 다시 `boot`하거나 `FOG_SERIAL`로 대상 지정 |
| Gradle `SDK location not found` | `local.properties`가 없습니다. `sdk` 단계를 실행하거나 `sdk.dir=`을 직접 적기 |
| Gradle이 `ai.pleos.playground` 의존성을 못 받음 | `https://nexus-playground.pleos.ai`에 접속할 수 있어야 합니다(사내망·프록시 확인) |
| 앱은 떴는데 화면이 검정 | 기어가 P가 아님 → Virtual Console에서 P |
| 에뮬레이터가 갑자기 느림, `app` 설치가 몇 분 걸림, 소리가 안 남 | 이미지의 Pleos 차량 서비스(DDS)가 `10.0.2.15`에 묶이는데, 이 주소는 에뮬레이터의 모바일 데이터 연결이 성공해야 eth0에 생깁니다. 연결이 실패한 부팅에서는 서비스가 5초마다 죽고 다시 떠서 시스템이 과부하됩니다. `boot`가 이를 감지해 한 번 다시 켜고, `doctor`가 `차량 서비스` 줄로 보여 줍니다. 그래도 남으면 `stop` 후 `boot`. 비행기 모드를 껐다 켜는 것으로는 돌아오지 않습니다 |
| Windows에서 에뮬레이터 소리가 안 남 | 소리는 에뮬레이터를 켤 때의 Windows 기본 출력 장치로 나갑니다. 출력 장치를 바꿨으면 에뮬레이터를 다시 켜세요 |
| 위치가 바다·엉뚱한 곳 | Cold boot마다 위치가 초기화됩니다. `boot`를 다시 실행하거나 `geo fix` |
| 내비 경로 안내가 안 됨 | 에뮬레이터 내비는 GPS 체크섬 문제로 경로 안내를 못 합니다. 시연 모드가 회전 안내·도착 이벤트를 대신 만듭니다 |
| Virtual Console 값 변경이 무시됨 | 콘솔은 브라우저 세션이 살아 있을 때만 쓰기를 받습니다. 브라우저로 페이지를 열어 두거나 QA 도구(`fogqa`)를 쓰세요 |
| Release 빌드 서명 | `keystore.properties`(git 제외)가 없으면 debug 키로 서명됩니다. 테스트에는 디버그 빌드면 충분합니다 |
