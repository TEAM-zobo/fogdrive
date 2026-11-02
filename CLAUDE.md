# 부릉탐험대 (저장소 fogdrive)

Pleos Connect(Android Automotive OS, API 34 에뮬레이터) 앱. Kotlin + Compose, Gradle 8.10.2, AGP 8.8.2, JDK 17, compileSdk 35.
기능은 `README.md`(심사자용), 코드 구조는 `app/src/main/java/com/zobo/burung/` 패키지, 테스트 환경은 `docs/SETUP.md`, 에뮬레이터 QA는 `qa/README.md`, 공모전 제출 준비 현황·체크리스트·제출 이메일은 `docs/index.html` 한 곳에서 관리합니다(md로 따로 두지 않음). 제출 파일은 `docs/submission/`에 있습니다.
접수양식은 `docs/submission/application-form.docx` 하나입니다. 비공개 저장소라 제출용 개인정보(01 참가 정보, 제출 이메일 주소)도 저장소에 둡니다(`private/` 폴더는 쓰지 않음). 2026-11-02본은 Word에서 직접 고쳤고, 02 원고 `docs/submission/02-app-introduction.md`는 그 내용에 맞춰 두었습니다. 내용을 고치면 docx와 원고를 함께 고칩니다. `python3 tools/submission/build_form.py`는 원고로 양식을 다시 만들면서 Word의 서식·01 참가 정보를 덮어쓰므로 사용자가 요청할 때만 실행합니다. 02 본문, README, 제출 링크에는 성명·연락처·이메일·소속·계정 ID를 쓰지 않습니다(01과 `docs/index.html`의 제출 이메일에만).

## 테스트 환경 셋팅과 실행

"셋팅해줘", "실행해줘", "에뮬레이터에 띄워줘" 같은 요청은 `docs/SETUP.md`를 따릅니다. OS별 스크립트가 모든 단계를 자동으로 하고, 여러 번 실행해도 됩니다.

- macOS: `tools/setup/setup.sh <단계>`
- Windows: `powershell -ExecutionPolicy Bypass -File tools\setup\setup.ps1 <단계>` (스위치는 `-qa`, `-headless`)

순서:

1. `doctor`를 먼저 실행해서 빠진 항목만 확인합니다. 결과를 사용자에게 짧게 알립니다.
2. 빠진 게 있으면 `all`을 실행합니다(또는 빠진 단계만). `image`는 4–5GB(macOS 4.7GB, Windows 4.1GB)를 받아서 오래 걸리므로 백그라운드로 실행하고, 시작할 때 사용자에게 알립니다.
3. Windows에서 `doctor`가 하드웨어 가속을 `[없음]`으로 보여 주면, 하이퍼바이저 활성화(관리자 권한, 재부팅 필요)는 사용자에게 안내만 하고 직접 하지 않습니다.
4. `app`이 `[OK] 앱 실행 중`으로 끝나면 `adb -s <serial> exec-out screencap -p`로 화면을 캡처해 직접 보고 확인합니다. 지도·레벨 카드가 보이면 성공입니다. 화면이 검으면 기어가 P가 아닌 것입니다.

주의:

- 사용자가 이미 `emulator-5554`를 화면에 띄워 쓰고 있으면, 확인용 실행은 `boot --headless` + `FOG_SERIAL=emulator-5556 ... app`(QA 에뮬레이터)으로 하고 끝나면 `stop --headless`로 끕니다.
- `local.properties`, `keystore.properties`, `qa/.qa_password`는 git에 넣지 않습니다.
- 앱 화면은 P단에서만 보입니다. 차량 신호는 Virtual Console(`adb forward tcp:8088 tcp:8088`, `qa/fogqa/vconsole.py`)로 바꿉니다.

## 시연 영상·숏폼

"영상 만들어줘", "시연 영상 수정해줘" 같은 요청은 `docs/VIDEO.md`를 따릅니다. HyperFrames 스킬(`/hyperframes`)이 없으면 먼저 `tools/setup/setup.sh video`로 설치하고 Claude Code를 다시 열라고 안내합니다.

- 시연 영상은 `tools/demo/burung-demo`, 숏폼은 `tools/demo/burung-short`입니다. 카피·장면·카메라·음악 수정은 해당 영상의 `build.py`만 고치고 `python3 build.py` → `npx hyperframes@0.8.196 check` → `snapshot`으로 프레임을 직접 본 뒤 `render`합니다. `index.html`과 `compositions/`는 생성물이라 직접 고치지 않습니다. 숏폼 글자는 화면 위 168–1330 px 안에 둡니다.
- 소재(`assets/`)와 녹화본은 git에 없습니다. 다시 렌더링할 때는 녹화하지 말고 GitHub Release `burung-media-v1`(소재 zip)·`burung-demo-raw-v1`(녹화본)에서 받습니다(`gh` 로그인 필요, 방법은 `docs/VIDEO.md`).
- 녹화는 `boot --record`로 띄운 QA 에뮬레이터에서만 하고, 그동안 다른 에뮬레이터는 끕니다(같은 GPU를 써서 끊김).
- 소리는 들을 수 없으니 `ffmpeg -af ebur128`로 측정해 보고(-16~-18 LUFS), 들어 보지 않았다고 말합니다.

## 자주 쓰는 명령

```bash
./gradlew :app:assembleDebug         # Windows: gradlew.bat
./gradlew :app:testDebugUnitTest
cd qa && python3 -m fogqa run TC-01  # 에뮬레이터 TC (목록: python3 -m fogqa list)
```
