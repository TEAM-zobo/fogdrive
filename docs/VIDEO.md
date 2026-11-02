# 시연 영상·숏폼 만들기

부릉탐험대 시연 영상(접수양식 02-7)과 세로 숏폼(02-8)을 만드는 과정입니다. Pleos Connect 에뮬레이터에서 앱을 실제로 돌려 녹화하고, [HyperFrames](https://github.com/heygen-com/hyperframes)(HTML로 영상을 만드는 오픈소스 도구, Apache-2.0)로 자막·카메라·음악을 입혀 렌더링합니다.

| 영상 | 프로젝트 | 결과물 (git 제외) | 완성본 보관 |
| --- | --- | --- | --- |
| 시연 영상 (1920×1080, 1분 14초) | `tools/demo/burung-demo` | `renders/burung-demo-final.mp4` | Release [`burung-demo-raw-v1`](https://github.com/TEAM-zobo/fogdrive/releases/tag/burung-demo-raw-v1)의 `burung-demo.mp4` |
| 숏폼 (1080×1920, 40.6초) | `tools/demo/burung-short` | `renders/burung-shortform.mp4` | Release [`burung-media-v1`](https://github.com/TEAM-zobo/fogdrive/releases/tag/burung-media-v1)의 `burung-shortform.mp4` |

- 편집은 각 프로젝트의 `build.py`만 고칩니다. `index.html`과 `compositions/`는 `build.py`가 만드는 결과라 직접 고치지 않습니다.
- 소재(`assets/`)와 녹화본은 git에 없고 GitHub Release에 있습니다. 비공개 저장소라 [GitHub CLI](https://cli.github.com)(`gh auth login`) 로그인이 필요합니다.
- 제출 현황(업로드·링크)은 [공모전 준비 가이드](index.html)에서 관리합니다.

> Claude Code에 맡길 때: "docs/VIDEO.md대로 시연 영상 다시 렌더링해줘", "숏폼 자막 바꿔줘"처럼 요청하면 됩니다.

## 1. 준비 (한 번)

테스트 환경([SETUP.md](SETUP.md))이 먼저 있어야 합니다.

| 할 일 | macOS | Windows |
| --- | --- | --- |
| 테스트 환경 | `tools/setup/setup.sh all` | `setup.ps1 all` |
| QA 에뮬레이터 AVD (녹화용) | `tools/setup/setup.sh avd --qa` | `setup.ps1 avd -qa` |
| 영상 도구 | `tools/setup/setup.sh video` | `setup.ps1 video` |

`video` 단계가 하는 일:

- **ffmpeg 설치:** 녹화본 자르기와 인코딩, 음량 측정에 씁니다. macOS는 Homebrew, Windows는 winget으로 설치합니다.
- **Node.js 22 이상 설치:** HyperFrames를 돌리는 데 필요합니다.
- **HyperFrames 준비:** 고정 버전 CLI 0.8.196을 받고, Claude Code용 스킬 4개(`hyperframes`, `hyperframes-cli`, `hyperframes-registry`, `media-use`)를 `~/.claude/skills`에 설치합니다. 설치 뒤 Claude Code를 다시 열어야 스킬이 보입니다.

## 2. 소재 받기 (녹화 없이 다시 렌더링할 때)

녹화하지 않고 지금 영상을 다시 렌더링하거나 자막·음악만 고칠 때는 Release의 소재를 받습니다.

```bash
# 녹화용 QA 에뮬레이터: 창 없음, 소리 켬, 시계를 오늘 오전 11시(KST)로 → 지도가 주간 모드
tools/setup/setup.sh boot --record          # Windows: setup.ps1 boot -record

# 앱 빌드
./gradlew :app:assembleDebug

# 1부 "첫 시동": 앱 데이터 초기화, 앱은 닫아 둔 채로 → 첫 실행부터 녹화
python3 tools/demo/prepare_demo.py --fresh --apk app/build/outputs/apk/debug/app-debug.apk
python3 tools/demo/record_segments.py part1

# 2부 "쌓인 기록": 시연 페르소나(40대 아빠 A씨)의 4주 기록(docs/submission/persona.md)을 불러온 뒤 녹화
python3 tools/demo/persona/make_persona_backup.py
python3 tools/demo/prepare_demo.py
python3 tools/demo/record_segments.py part2

tools/setup/setup.sh stop --record
```

- 효과음(`assets/sfx/`)은 Pixabay 라이선스상 따로 재배포할 수 없어 묶음에 없습니다. `video` 단계로 설치한 media-use 스킬 라이브러리의 효과음을 `assets/sfx/`에 복사해 씁니다.
- 시연 영상의 장면 클립은 녹화본에서 다시 만들 수도 있습니다: `python3 tools/demo/burung-demo/prepare_clips.py` (녹화본이 없으면 Release `burung-demo-raw-v1`에서 받음, 약 1.3GB).

## 3. 렌더링

**시연 영상**

```bash
cd tools/demo/burung-demo
python3 build.py
npx hyperframes@0.8.196 check
npx hyperframes@0.8.196 snapshot --at 2,6,11,20,30,36,42,50,58,65,71
npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-demo.mp4
ffmpeg -i renders/burung-demo.mp4 -c:v copy -af "volume=3.6dB,alimiter=limit=0.85:level=disabled" -c:a aac -b:a 192k renders/burung-demo-final.mp4
```

- 시나리오(접수양식 4. 주요 사용 시나리오, 40대 아빠 A씨): 키즈모드 ON → 유치원 등원(하차 깃발·함께 탐험) → 20번째 출근(「프로 출근러」) → 토요일 도감·추천 → 올림픽공원 나들이(처음 가는 동네 음성·주말 드라이브) → 내 탐험.
- 화면 오른쪽은 에뮬레이터 실동작 녹화, 왼쪽은 장면 설명·사용 데이터(Pleos SDK)·앱 음성 자막입니다.

| 파일 | 역할 |
| --- | --- |
| `build.py` | **편집은 여기서.** 장면 목록(`SCENES`): 쓰는 클립, 카메라(`cam`: 원본 좌표와 배율), 자막(`cap`), 제목(`title`), 숫자 카드(`stat`). 색·글꼴·전환(0.6초 크로스페이드)·음악 볼륨 곡선·효과음 목록(`SFX`)도 여기 있습니다 |
| `prepare_assets.sh` | 녹화본의 어느 구간을 어떤 클립으로 자를지(숏폼에만 쓰는 `share` 포함). 우회 제안 카드 0.35배속, 음성 문장 한 줄(+22dB, 앞뒤 페이드) |
| `vector2svg.py` | 앱 런처 아이콘(Android vector XML) → `assets/icon.svg` |
| `SPEC.md`, `STORYLINES.md` | 장면별 기획, 대안 스토리라인 2개 |
| `index.html`, `compositions/` | `build.py`가 만드는 결과(직접 고치지 않음) |

자주 하는 수정:

- **카피:** `build.py`의 `cap=dict(h="제목", s="부제")`와 `title=dict(lines=[...])`를 고칩니다. 브랜드·챕터·엔딩 문구는 `brand_scene`, `claim_scene`, `end_scene` 안에 있습니다.
- **확대:** `cam=[(시각, (원본 x, 원본 y, 배율), 이동 시간)]`를 고칩니다. 원본 좌표는 2560×1440 녹화 기준입니다. `APP = (1760, 690, 1.2)`가 앱 지도 화면 전체를 꽉 채우는 기본 구도입니다.
- **장면 순서·길이:** `scene("id", 시작 초, 길이, ...)`를 고칩니다. 장면은 2초 단위로 맞추고, 다음 장면과 0.6초 겹치며 크로스페이드됩니다.
- **임팩트:** 제목 착지는 `slam_words()`, 파랑 플러드는 `brand_scene`·`stat` 카드·`claim_scene`, 몽타주 휩은 `whip=True` 장면입니다.
- **효과음:** `SFX = [(파일, 시작 초, 볼륨, ...)]`에 한 줄씩. 파일은 `assets/sfx/`에 있고 `prepare_assets.sh`가 media-use 스킬 라이브러리에서 복사합니다. 효과음을 늘리면 렌더 뒤 피크가 -1 dBFS를 넘지 않는지 봅니다.
- **다시 녹화했다면:** 새 녹화본은 화면 타이밍이 조금씩 달라서 `prepare_assets.sh`의 자르는 구간과 `build.py`의 카메라를 다시 맞춰야 합니다. 확정되면 `shasum -a 256 assets/shots/*.mp4 assets/music/fretless.mp3 assets/fonts/Pretendard-*.woff2 assets/sfx/*.mp3 > assets.sha256`으로 체크섬을 갱신하고, 클립·음악·글꼴을 zip으로 묶어 새 Release(`demo-assets-v2`)로 올린 뒤 스크립트의 `BUNDLE`을 바꿉니다.
- **음악:** 다른 곡을 쓰려면 `assets/music/`에 넣고 `index_html()`의 `src`와 볼륨 곡선(`duck`)을 바꿉니다. 곡마다 기본 음량이 달라서 렌더 뒤 전체 음량을 확인합니다(-16~-18 LUFS). 엔딩 화면의 출처 표기도 같이 바꿉니다.

## 4. 라이선스와 표기

엔딩 화면 아래쪽에 음악·지도 출처를 이미 넣어 두었습니다.

| 소재 | 라이선스 | 표기 |
| --- | --- | --- |
| 배경음악 "Fretless" (Kevin MacLeod, incompetech.com) | CC BY 4.0 | 엔딩 화면 하단 |
| 글꼴 Pretendard | SIL OFL 1.1 | 필요 없음 |
| 효과음 (media-use 스킬 라이브러리, Pixabay) | Pixabay Content License | 필요 없음. 파일만 따로 재배포할 수 없어 Release 묶음에는 넣지 않음 |
| 지도 타일 | OpenStreetMap contributors (ODbL), OpenFreeMap | 엔딩 화면 하단 + 앱 화면 자체 표기 |
| HyperFrames | Apache-2.0 | 필요 없음 |

## 5. 숏폼

접수양식 02-8 숏폼(선택)은 세로 영상입니다. 제출 조건과 장면은 [submission/shortform.md](submission/shortform.md)에 있습니다.

**부릉탐험대 캐릭터 숏폼 (제출용):** `tools/demo/burung-toon`. 애니메이션 캐릭터가 앱을 소개하는 버전으로, 만드는 법·장면·소재는 [submission/shortform.md](submission/shortform.md)에 있고 앱 화면은 git에 있는 `docs/images/burung/`을 써서 어느 컴퓨터에서나 만들 수 있습니다.

**이전 버전:** `tools/demo/burung-short` (40.6초). 에뮬레이터 앱 화면 캡처와 주행 클립(`assets/`, git 제외)을 소재로 쓰고, 편집은 `build.py`만 고칩니다.

```bash
cd tools/demo/burung-short
python3 build.py
npx hyperframes@0.8.196 check
npx hyperframes@0.8.196 snapshot --at 1,4.5,8,12,16.5,21,25.5,30,34,38
npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-shortform.mp4
```

- 글자는 휴대폰 화면 위 168–1330 px 안에만 둡니다. 그 아래는 플랫폼의 설명·버튼이 덮습니다.
- 첫 1초 안에 큰 글자 훅, 소리 없이도 읽히는 자막, 장면 4–5초, 마지막에 댓글 유도 질문.
- 필수 해시태그 `#HMG차량용앱개발공모전`은 영상이 아니라 게시글 본문에 적습니다.

렌더링이 끝나면 음량을 잽니다. 소리는 들어 볼 수 없으니 측정값으로 확인하고(-16~-18 LUFS, 피크 -1 dBFS 이하), 사람이 한 번 직접 들어 봅니다.

```bash
ffmpeg -i renders/<파일>.mp4 -af ebur128=peak=true -f null - 2>&1 | tail -12
```

## 4. 다시 녹화할 때

```bash
tools/setup/setup.sh boot --record          # 녹화용 QA 에뮬레이터: 창 없음, 소리 켬, 시계 오전 11시(주간 지도)
./gradlew :app:assembleDebug
python3 tools/demo/persona/make_persona_backup.py --today 2026-11-02 --weeks 6 --commute-only --commutes 19
python3 tools/demo/prepare_demo.py          # 앱 초기화 → 시연 페르소나 기록 불러오기 → 집(위례)에 주차
# 녹화: adb emu screenrecord start --time-limit 600 tools/demo/out/raw-burung/<구간>.webm  …  adb emu screenrecord stop
tools/setup/setup.sh stop --record
```

- **다른 에뮬레이터는 끕니다.** 같은 GPU를 나눠 써서 화면이 끊깁니다.
- **녹화는 `adb emu screenrecord`로 합니다.** 에뮬레이터가 호스트 쪽에서 녹화해 앱이 느려지지 않고 소리까지 담깁니다. 기기 안의 `adb shell screenrecord`는 화면을 끊기게 해서 쓰지 않습니다.
- 녹화본은 `tools/demo/out/raw-burung/*.webm`(2560×1440, 30fps)에 두고, `prepare_clips.py`의 구간 표를 새 녹화에 맞춥니다.
- 시연 페르소나 기록은 실제로 달린 기록이 아니라 시연용 데이터입니다. 집·회사·즐겨찾기는 에뮬레이터 내비에 저장해 둡니다([persona.md](submission/persona.md)).
- 녹화를 바꿨으면 소재를 새 Release(`burung-media-v2`, `burung-demo-raw-v2`)로 올리고 이 문서의 이름을 바꿉니다.

## 5. 라이선스와 표기

엔딩 화면 아래쪽에 음악·지도 출처를 넣어 두었습니다. 게시글·영상 설명란에도 음악 출처를 적습니다.

| 소재 | 라이선스 | 표기 |
| --- | --- | --- |
| 배경음악 "Think About You" (Square a Saw, Jamendo) | CC BY 3.0 | 엔딩 화면 하단, 게시글 설명란 |
| 글꼴 Pretendard | SIL OFL 1.1 | 필요 없음 |
| 효과음 (media-use 스킬 라이브러리, Pixabay) | Pixabay Content License | 필요 없음. 파일만 따로 재배포하지 않음 |
| 지도 타일 | OpenStreetMap contributors (ODbL), OpenFreeMap | 엔딩 화면 하단 + 앱 화면 자체 표기 |
| HyperFrames | Apache-2.0 | 필요 없음 |

## 6. 문제 해결

| 증상 | 원인·해결 |
| --- | --- |
| `gh release download`가 not found | 비공개 저장소입니다. `brew install gh`(Windows `winget install GitHub.cli`) 후 `gh auth login`으로 TEAM-zobo 저장소에 권한 있는 계정으로 로그인합니다 |
| 지도가 야간 모드(어두움)로 찍힘 | 앱은 차 위치의 실제 일출·일몰을 따릅니다. `boot --record`로 띄우면 시계가 오전 11시입니다 |
| `prepare_demo.py`에서 "copy failed" | release 빌드는 디버그 접근(`run-as`)이 막혀 있어 기록을 넣을 수 없습니다. debug APK로 녹화합니다(화면은 같음) |
| 녹화본 소리가 작음 | 에뮬레이터 출력이 원래 작습니다(음성 최대 약 -27 dBFS). `prepare_clips.py`가 앱 소리를 +21dB 올리고, 렌더 뒤 마지막 `ffmpeg` 줄로 전체 음량을 맞춥니다 |
| 장면 id를 바꾼 뒤 `check`가 실패 | `python3 build.py`를 다시 실행했는지, 장면 id와 파일 이름이 같은지 확인합니다 |
