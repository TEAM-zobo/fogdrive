# 안개지도 (FogDrive)

시동만 걸면 달린 길의 안개가 걷히고, 아직 안 가본 동네는 안개로 남는 차량 전용 탐험 지도입니다.

Pleos Connect(Android Automotive OS) 앱이며, 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 팀 zobo.

![주행 중 안개가 걷히는 지도](docs/images/02-driving.jpg)

## APK

- Release APK: [`apk/zobo_FogDrive_release.apk`](apk/zobo_FogDrive_release.apk)
- 패키지: `com.zobo.fogdrive` · minSdk 28 · targetSdk 35 · ABI arm64-v8a, x86_64

## 주요 기능

| 기능 | 내용 | 사용 SDK/API |
| --- | --- | --- |
| 자동 안개 걷기 | 주행 경로를 약 30m 격자로 기록해 도로와 눈에 보이는 범위(도로 양쪽 약 45m)만 안개를 걷음 | Pleos FusedLocation (미지원 시 Android GPS) |
| 상륙 지점 | P단 전환 후 도어가 열린 곳 주변(약 210m)을 걷고 핀 표시 | AAOS `GEAR_SELECTION`, Pleos Vehicle Door |
| 첫 실행 지도 채우기 | 내비 최근 목적지·즐겨찾기 주변을 미리 걷음 | Pleos NaviHelper |
| 탐험 우회 경로 | 출발 전 목적지 근처의 안개 구역을 찾아 경유지로 추가 | Pleos NaviHelper `getDestinationInfo`, `addWaypoint` |
| 상황 스탬프 | 빗속·야간·가족 탐험·충전소 깃발 | Pleos Vehicle Wiper, Light, Seat, EvBattery |
| 기록·도감 | 개척 칸·면적·새 길 비율, 레벨, 월간 시즌 챌린지 | 앱 내부 계산 |

기록은 포그라운드 서비스가 맡아서 화면이 꺼져 있어도 계속됩니다. 액티비티는 distraction-optimized로 선언했고, 주행 중(CarUxRestrictions)에는 **주행 모드**로 바뀌어 지도·현재 위치·레벨·이번 주행 새 칸 수만 보여줍니다. 탭, 기록, 도감, 설정, 탐험 우회 제안은 정차 중에만 열립니다. 지도는 OpenFreeMap Positron 스타일에 한국어 라벨(`name:ko`)을 적용했습니다. 위치 기록은 기기 내부(Room DB)에만 저장하고 서버로 보내지 않습니다.

## 테스트 환경

| 항목 | 버전 |
| --- | --- |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64) |
| Pleos SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 |
| 빌드 | JDK 17 · Gradle 8.10.2 · AGP 8.8.2 · Kotlin 2.0.21 · compileSdk 35 |
| 지도 | MapLibre Android 11.13.5 + OpenFreeMap 타일 (© OpenStreetMap contributors) |

에뮬레이터 설치는 [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) 문서를 따릅니다. AVD는 Cold boot, RAM 4GB로 설정합니다. 지도 타일을 받으려면 에뮬레이터에 인터넷 연결이 필요합니다.

## 실행 방법

### APK 설치

```bash
adb install -r -g apk/zobo_FogDrive_release.apk
adb shell am start -n com.zobo.fogdrive/.ui.MainActivity
```

`-g`를 빼고 설치하면 첫 실행 때 위치·FusedLocation·알림 권한을 요청합니다. 권한을 허용하면 기록 서비스가 시작됩니다.

### 소스에서 빌드

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 단위 테스트
./gradlew :app:assembleRelease      # keystore.properties가 없으면 debug 키로 서명
```

Release 서명 정보는 저장소 밖의 `keystore.properties`(gitignore)에서 읽습니다.

## 시연 방법

### 1. 시연 모드 (권장)

앱의 **설정 → 시연 모드 → 시연 시작**을 누른 뒤 **지도** 탭으로 이동합니다. 성수역 → 서울숲 → 뚝섬한강공원(실제 도로, 약 5.2km)을 자동으로 달립니다.

1. 출발 전(P단): 탐험 우회 경로 카드가 나타납니다.
2. 주행 중: 안개가 걷히고, 와이퍼 구간에서 빗속 개척, 뒷좌석 동승자로 가족 탐험 스탬프를 얻습니다.
3. 도착: P단 + 운전석 도어 열림으로 상륙 지점이 기록됩니다.

시연이 끝나도 화면이 유지되며, **시연 중지**를 누르면 실제 위치로 돌아갑니다.

### 2. 실제 차량 신호로 시연 (Virtual Console)

```bash
adb forward tcp:8088 tcp:8088     # 브라우저에서 http://127.0.0.1:8088/
adb emu geo fix 127.0559 37.5446  # 위치 이동 (경도, 위도)
```

Virtual Console에서 기어(P/D), 도어, 와이퍼, 전조등, 좌석 상태를 바꾸면 앱이 그대로 반응합니다. 설정 탭의 **연결 상태**에서 각 SDK 연결과 현재 차량 상태를 확인할 수 있습니다.

## 구조

```
app/src/main/java/com/zobo/fogdrive/
  car/        Vehicle SDK·AAOS 신호, 위치 피드, NaviHelper 브리지
  record/     기록 규칙(TripRecorder, 단위 테스트), 포그라운드 서비스, 부팅 수신기
  data/       Room DB, 탐험 저장소
  explore/    탐험 우회 경로 계산, 레벨 규칙
  geo/        격자(웹 메르카토르 19레벨) 계산
  demo/       시연 모드 주행 스크립트
  ui/         Compose 화면, 안개 오버레이
```

## 문서

- [공모전 준비 가이드](docs/index.html): 일정, 제출물, 접수양식 작성법, 일자별 계획, 근거 링크
