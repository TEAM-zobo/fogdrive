# 시연 영상

접수양식 02-7 참고자료에 넣는 시연 영상(선택, 가산점)입니다. Pleos Connect 에뮬레이터 화면을 구간별로 자동 조작·녹화하고, 자막·타이틀을 넣어 하나로 이었습니다. 앱 음성 안내와 효과음이 그대로 들어 있습니다.

- 결과물(2026-10-25 제작, git 제외): `tools/demo/out/fogdrive-demo-1440p.mp4`(2560×1440, 3분 24초, 48 MB), `fogdrive-demo-1080p.mp4`(30 MB)
- 소리: 에뮬레이터 출력 그대로(음성 안내 = Pleos TextToSpeech, 효과음). 전체 음량 -17.7 LUFS
- 공개: YouTube **일부 공개**(링크가 있는 사람만). 채널 이름·프로필에 실명이 보이지 않게 팀 zobo 채널을 쓰거나, 소유자 이름이 보이지 않는 클라우드 링크를 씁니다
- 시연 데이터: 두 달 쓴 지도를 보여 주려고 **시연용 예시 기록**(앱의 시연 코스를 여러 번 오간 기록)을 불러와 찍었습니다. 영상 첫 자막에 "시연용 예시 기록"이라고 밝혀 둡니다. 시연 주행 자체는 녹화 중에 실제로 달린 것입니다

## 장면 구성 (3분 24초)

| # | 시간 | 화면 | 보여 주는 기능 | 자막(요지) |
| --- | --- | --- | --- | --- |
| 1 | 0:00 | 타이틀 | - | 안개지도 FogDrive · 시동만 걸면, 달린 길의 안개가 걷히는 차량 전용 탐험 지도 |
| 2 | 0:04 | 앱 실행 → 로딩 → 지도 | 실제 Pleos 에뮬레이터, 두 달 기록 | LIVE DEMO · 두 달 동안 쌓인 나의 안개지도(시연용 예시 기록) |
| 3 | 0:13 | 집 주변 확대 → 자주 다닌 길 끄고 켜기 → 축소 | **자주 다닌 길 색** 2·3·5·10·20·50번 단계 | 2번 파랑 → … → 50번 빨강 · 출퇴근길 52번 · 은평 22번 · 홍대 12번 |
| 4 | 0:32 | 시연 주행 시작(P단, 정지 화면 4초) | **탐험 우회 경로** 제안 | 1분 더 쓰면 안 가 본 동네를 지나요 |
| 5 | 0:38 | 주행 모드 37초 | 주행 모드(지도·회전 안내·제한속도), 빨간 출퇴근길 → **새 길 안개 걷힘**, 음성 안내, 스탬프·동승자 미션 알림 | 🔊 "처음 와 본 신사1동이에요" 외 3번 |
| 6 | 1:15 | 도착 26초 | 하차 지점(P단+도어), **이번 주행 요약**, 음성 요약 | 🔊 "주행을 마쳤어요. 이번 주행에서 안개 3.97제곱킬로미터를 걷었어요…" |
| 7 | 1:41 | 지도 축소 | 오늘 달린 길 + 두 달 기록, 2단계 안개, 안 가본 동네 추천 | 짙은 안개 / 옅은 안개 |
| 8 | 1:53 | 기록 | 면적·거리·하차·새 길 비율, 이번 주/이번 달 리포트, 주행 목록 → 경로 다시 보기 | 주행을 누르면 그 경로를 지도에서 |
| 9 | 2:12 | 기록 → 개척 타임랩스 | 타임랩스 | 첫 주행부터 오늘까지 |
| 10 | 2:26 | 도감 | 시·도 → 시·군·구 → 읍·면·동 달성률 | 지역 도감 |
| 11 | 2:35 | 도감: 중구 → 전국 → 챌린지 → 스탬프 | 동 개척 단계 배지, 챌린지 4종, 상황 스탬프 29종 | 50%에 닿으면 음성으로도 |
| 12 | 2:58 | 기록 → 내 안개지도 카드 | 공유 카드 | 공유용 이미지 |
| 13 | 3:06 | 설정 | SDK 연결 상태, 시연 모드, 음성·효과음·동기화 | Pleos SDK 연결 상태 |
| 14 | 3:19 | 엔딩 | - | 달린 만큼 걷히는 나만의 지도 · 팀 zobo |

자막은 화면 왼쪽 차량 패널(에뮬레이터의 차량 상태 영역) 위에만 띄워, 앱 화면을 가리지 않습니다. 음성 안내가 나오는 순간에는 🔊 자막으로 같은 문장을 보여 줍니다.

## 다시 만드는 방법

macOS 기준입니다. 처음 한 번 `brew install ffmpeg`가 필요합니다.

```bash
# 0. 테스트 환경: docs/SETUP.md (QA 에뮬레이터 AVD가 있어야 함: tools/setup/setup.sh avd --qa)
#    녹화 중에는 다른 에뮬레이터를 끕니다(GPU를 나눠 써서 끊김). 소리를 녹음하려면 -no-audio 없이 띄웁니다.
~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_QA -no-window -port 5556 -no-snapshot -no-boot-anim -gpu host &

# 1. 시연용 예시 기록 만들기 (tools/demo/out/fogdrive-backup.json, 칸 10,746 · 주행 98 · 하차 49)
python3 tools/demo/make_demo_backup.py

# 2. 시연 시작 상태: 앱 설치·초기화 → 예시 기록 불러오기 → 집(향기5로 66)에 P단 정차
./gradlew :app:assembleDebug
python3 tools/demo/prepare_demo.py --apk app/build/outputs/apk/debug/app-debug.apk

# 3. 구간별 녹화 (tools/demo/out/raw/*.webm, 소리 포함). 한 구간만 다시: 구간 이름을 인자로
python3 tools/demo/record_segments.py
python3 tools/demo/record_segments.py s09_dex_district

# 4. 편집 (자르기 · 정지 화면 · 자막 · 타이틀 · 음량) → tools/demo/out/fogdrive-demo-*.mp4
python3 tools/demo/build_video.py
```

| 파일 | 하는 일 |
| --- | --- |
| [`tools/demo/make_demo_backup.py`](../../tools/demo/make_demo_backup.py) | 앱의 시연 코스 6개를 2–52번 오간 두 달 기록(칸·주행·경로·하차·스탬프)을 백업 파일로 생성. 녹화할 성수 코스는 98%가 안개로 남게 골랐고, 녹화 중 받을 스탬프(빗속·가족·바람길·함께한 미션)는 넣지 않음 |
| [`tools/demo/prepare_demo.py`](../../tools/demo/prepare_demo.py) | Virtual Console 초기화 → 앱 초기화 → 예시 기록을 앱 내부 폴더에 복사(`run-as`) → 설정 → 불러오기 |
| [`tools/demo/record_segments.py`](../../tools/demo/record_segments.py) | 구간 10개를 자동 조작하며 에뮬레이터 자체 녹화(`adb emu screenrecord`: 호스트 쪽 녹화라 앱이 느려지지 않고 소리까지 담김). 터치 위치 표시 켬 |
| [`tools/demo/build_video.py`](../../tools/demo/build_video.py) | 자르는 지점·정지 화면·자막이 모두 맨 위 `EDIT` 목록에 있음. 숫자만 고쳐 다시 실행 |
| [`tools/demo/captions.swift`](../../tools/demo/captions.swift) | 자막·타이틀 이미지(Apple SD Gothic Neo) |

## 고칠 때

- **자막 문구·시간:** `build_video.py`의 `EDIT`에서 `cap(시작, 끝, 제목, 부제, 태그)`를 고칩니다. 시간은 잘라 낸 조각 안의 초입니다.
- **자르는 지점:** 각 조각의 `a`, `b`는 원본 녹화의 초입니다. 원본 프레임 확인: `ffmpeg -ss 40 -i tools/demo/out/raw/s04_drive.webm -frames:v 1 frame.png`
- **음성 안내 시각:** 녹화마다 조금씩 다르므로, 주행 구간을 다시 찍으면 🔊 자막 시간을 다시 맞춥니다. 소리가 시작되는 시각: `ffmpeg -i tools/demo/out/raw/s04_drive.webm -af silencedetect=noise=-42dB:d=0.4 -f null -`
- **다시 찍을 때:** 주행(`s04_drive`) 뒤 구간들은 그 주행이 더해진 기록을 보여 주므로, 주행을 다시 찍으면 `prepare_demo.py`부터 순서대로 다시 찍습니다.

## 올리기 전 확인

- [ ] 처음부터 끝까지 재생해 소리·자막·화면이 맞는지 본다
- [ ] 화면·자막·파일 이름·채널 이름에 성명·이메일·계정 ID가 없다
- [ ] 링크를 로그인하지 않은 브라우저에서 재생해 봤다
- [ ] 링크를 접수양식 02-7(`02-app-introduction.md`의 【기입 필요: 시연 영상 링크】)과 [`email.md`](email.md), `private/email.txt`에 넣고 `python3 tools/submission/build_form.py`로 양식을 다시 만들었다
