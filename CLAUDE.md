# FogDrive (안개지도)

Pleos Connect(Android Automotive OS, API 34 에뮬레이터) 앱. Kotlin + Compose, Gradle 8.10.2, AGP 8.8.2, JDK 17, compileSdk 35.
기능·구조는 `README.md`, 테스트 환경은 `docs/SETUP.md`, 에뮬레이터 QA는 `qa/README.md`에 있습니다.

## 테스트 환경 셋팅과 실행

"셋팅해줘", "실행해줘", "에뮬레이터에 띄워줘" 같은 요청은 `docs/SETUP.md`를 따릅니다. OS별 스크립트가 모든 단계를 자동으로 하고, 여러 번 실행해도 됩니다.

- macOS: `tools/setup/setup.sh <단계>`
- Windows: `powershell -ExecutionPolicy Bypass -File tools\setup\setup.ps1 <단계>` (스위치는 `-qa`, `-headless`)

순서:

1. `doctor`를 먼저 실행해서 빠진 항목만 확인합니다. 결과를 사용자에게 짧게 알립니다.
2. 빠진 게 있으면 `all`을 실행합니다(또는 빠진 단계만). `image`는 약 4.7GB를 받아서 오래 걸리므로 백그라운드로 실행하고, 시작할 때 사용자에게 알립니다.
3. Windows에서 `doctor`가 하드웨어 가속을 `[없음]`으로 보여 주면, 하이퍼바이저 활성화(관리자 권한, 재부팅 필요)는 사용자에게 안내만 하고 직접 하지 않습니다.
4. `app`이 `[OK] 앱 실행 중`으로 끝나면 `adb -s <serial> exec-out screencap -p`로 화면을 캡처해 직접 보고 확인합니다. 지도·레벨 카드가 보이면 성공입니다. 화면이 검으면 기어가 P가 아닌 것입니다.

주의:

- 사용자가 이미 `emulator-5554`를 화면에 띄워 쓰고 있으면, 확인용 실행은 `boot --headless` + `FOG_SERIAL=emulator-5556 ... app`(QA 에뮬레이터)으로 하고 끝나면 `stop --headless`로 끕니다.
- `local.properties`, `keystore.properties`, `qa/.qa_password`는 git에 넣지 않습니다.
- 앱 화면은 P단에서만 보입니다. 차량 신호는 Virtual Console(`adb forward tcp:8088 tcp:8088`, `qa/fogqa/vconsole.py`)로 바꿉니다.

## 자주 쓰는 명령

```bash
./gradlew :app:assembleDebug         # Windows: gradlew.bat
./gradlew :app:testDebugUnitTest
cd qa && python3 -m fogqa run TC-01  # 에뮬레이터 TC (목록: python3 -m fogqa list)
```
