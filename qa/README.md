# 안개지도 QA

Pleos Connect Emulator에서 앱을 실제 SDK 경로로 검증하는 도구입니다. Python 3 표준 라이브러리만 씁니다.

- 차량 신호: Virtual Console HTTP API (`adb forward tcp:8088 tcp:8088`)
- 위치: `adb emu geo fix`
- 화면 판독: UIAutomator

## 웹 대시보드

- 주소: https://drive.guseh1604.synology.me (Synology 리버스 프록시 → 이 Mac `:8097`)
- 로그인: 사용자 `zobo`, 비밀번호는 `qa/.qa_password` (처음 실행할 때 생성, git 제외)
- 기능: 실시간 화면(클릭=탭, 드래그=스와이프), 기어·도어·와이퍼·전조등·좌석·배터리·차속 조작, 시연 경로 재생, 앱 실행·초기화·빌드 설치, 에뮬레이터 시작, TC 선택 실행과 리포트

```bash
qa/scripts/service.sh restart   # LaunchAgent(com.zobo.fogdrive.qa)로 상시 실행
cd qa && python3 -m fogqa serve --port 8770   # 개발용 로컬 실행
```

## 테스트 케이스

TC 정의는 [`fogqa/cases.py`](fogqa/cases.py) 한 곳에 있고, 문서 [`docs/test-cases.html`](../docs/test-cases.html)도 여기서 생성합니다.

```bash
cd qa
python3 -m fogqa list            # TC 목록
python3 -m fogqa run             # 전체 실행 (약 6분)
python3 -m fogqa run TC-03 TC-05 # 일부 실행
python3 -m fogqa tc-doc          # docs/test-cases.html 갱신
```

리포트는 `qa/reports/<시각>/index.html`에 스크린샷과 함께 저장됩니다(git 제외). 각 TC는 앱 데이터 초기화와 차량 상태 초기화에서 시작하므로 순서와 무관합니다.

## 주의

- 에뮬레이터가 켜져 있고 앱이 설치되어 있어야 합니다(대시보드의 앱 탭에서 빌드·설치 가능).
- TC 실행 중에는 대시보드의 수동 조작이 막힙니다.
- 이 에뮬레이터는 user 빌드라 `cmd car_service inject-vhal-event`를 쓸 수 없어 Virtual Console API를 사용합니다.
- 에뮬레이터는 P단이 아니면(차속 0이어도) 앱 화면을 막습니다. TC는 항상 P단으로 바꾼 뒤 화면을 읽습니다.
- 주행 중 차단 화면은 UI 트리에 보이지 않아 화면 밝기(raw screencap)로 판정합니다.
- Virtual Console의 좌석 탑승 값은 SDK에 반대로 전달됩니다(에뮬레이터 2.1.0). `VConsole.seat()`가 보정합니다.
