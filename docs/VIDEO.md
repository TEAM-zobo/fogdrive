# 시연·홍보 영상 만들기

공모전 시연 영상(접수양식 02-7)을 만드는 전체 과정입니다. Pleos Connect 에뮬레이터에서 앱을 자동으로 조작하며 구간별로 녹화하고, [HyperFrames](https://github.com/heygen-com/hyperframes)(HTML로 영상을 만드는 오픈소스 도구, Apache-2.0)로 자막·카메라 워크·음악을 입혀 1분 46초 홍보 영상으로 렌더링합니다.

- 결과물: `tools/demo/promo/renders/fogdrive-promo.mp4` (1920×1080, 30fps, 약 44MB, git 제외)
- 구성: 데이터 없는 앱으로 첫 주행(안개가 걷힘) → 그 지도가 쌓인 모습(자주 다닌 길 색, 타임랩스, 리포트, 도감, 스탬프)으로 자연스럽게 이어짐. 배경음악 "Fretless"(밝은 기타·글로켄슈필). 임팩트 연출: 제목이 쾅 착지, 브랜드 파랑이 화면을 덮는 로고·숫자·마무리, 몽타주 휩 전환, 효과음
- 장면별 카피·화면은 [`tools/demo/promo/SPEC.md`](../tools/demo/promo/SPEC.md), 고른 스토리라인은 [`STORYLINES.md`](../tools/demo/promo/STORYLINES.md)
- 시간: 처음부터 끝까지 약 30분(녹화 10분, 렌더링 4분, 나머지는 준비)

> Claude Code에 맡길 때: "docs/VIDEO.md대로 홍보 영상 다시 만들어줘". 카피나 장면만 바꿀 때는 `tools/demo/promo/build.py`를 고치라고 하면 됩니다.

## 1. 준비 (한 번)

테스트 환경([SETUP.md](SETUP.md))이 먼저 있어야 합니다. 녹화는 화면 없는 QA 에뮬레이터를 쓰므로 그 AVD도 만듭니다.

| 할 일 | macOS | Windows |
| --- | --- | --- |
| 테스트 환경 | `tools/setup/setup.sh all` | `setup.ps1 all` |
| QA 에뮬레이터 AVD | `tools/setup/setup.sh avd --qa` | `setup.ps1 avd -qa` |
| 영상 도구 | `tools/setup/setup.sh video` | `setup.ps1 video` |

`video` 단계가 하는 일:

- **ffmpeg 설치:** 녹화본 자르기와 인코딩에 씁니다. macOS는 Homebrew, Windows는 winget으로 설치합니다.
- **Node.js 22 이상 설치:** HyperFrames를 돌리는 데 필요합니다.
- **HyperFrames 준비:** 이 프로젝트가 고정해 둔 CLI 0.8.196을 받고, Claude Code용 스킬 4개(`hyperframes`, `hyperframes-cli`, `hyperframes-registry`, `media-use`)를 `~/.claude/skills`에 설치합니다. 설치 뒤 Claude Code를 다시 열어야 스킬이 보입니다.

`setup.sh doctor`(Windows `setup.ps1 doctor`)는 ffmpeg와 Node.js가 있는지도 보여 줍니다.

## 2. 녹화

```bash
# 녹화용 QA 에뮬레이터: 창 없음, 소리 켬, 시계를 오늘 오전 11시(KST)로 → 지도가 주간 모드
tools/setup/setup.sh boot --record          # Windows: setup.ps1 boot -record

# 앱 빌드
./gradlew :app:assembleDebug

# 1부 "첫 시동": 앱 데이터 초기화, 앱은 닫아 둔 채로 → 첫 실행부터 녹화
python3 tools/demo/prepare_demo.py --fresh --apk app/build/outputs/apk/debug/app-debug.apk
python3 tools/demo/record_segments.py part1

# 2부 "쌓인 기록": 시연 페르소나 4주 기록(docs/submission/burung/persona.md)을 불러온 뒤 녹화
python3 tools/demo/persona/make_persona_backup.py
python3 tools/demo/prepare_demo.py
python3 tools/demo/record_segments.py part2

tools/setup/setup.sh stop --record
```

- 녹화본은 `tools/demo/out/raw/*.webm`에 저장됩니다(2560×1440, 30fps, 앱 음성 안내·효과음 포함).
- 한 구간만 다시 찍으려면 구간 이름을 넘깁니다. 예: `python3 tools/demo/record_segments.py b4_dex`

| 구간 | 내용 | 길이 |
| --- | --- | --- |
| `a1_first_launch` | 첫 실행: 로딩 → 튜토리얼 5장 → 안개뿐인 지도 | 26초 |
| `a2_first_map` | 지도 축소: 거의 전부 안개 | 11초 |
| `a3_first_drive` | 첫 시연 주행(성수, 80 km/h): 우회 제안 → 안개 걷힘 → 음성 → 스탬프 → 도착·요약 | 175초 |
| `a4_first_after` | 요약 닫고 축소: 길 하나가 밝힌 지도 | 13초 |
| `a5_first_record` | 첫 주행 뒤 기록·도감 | 17초 |
| `b1_worn` | 자주 다닌 길 색, 끄고 켜기, 축소 | 21초 |
| `b2_record` | 기록: 주간/월간 리포트, 주행 경로 다시 보기 | 20초 |
| `b3_timelapse` | 개척 타임랩스 | 16초 |
| `b4_dex` | 도감: 전국 → 서울 → 중구 동 배지 → 챌린지 → 스탬프 | 36초 |
| `b5_share` | 내 안개지도 카드 | 9초 |
| `b6_settings` | 설정: SDK 연결 상태 | 14초 |

녹화할 때 지켜야 할 것:

- **다른 에뮬레이터는 끕니다.** 같은 GPU를 나눠 써서 화면이 끊깁니다.
- **녹화는 `adb emu screenrecord`로 합니다.** 에뮬레이터가 호스트 쪽에서 녹화하므로 앱이 느려지지 않고 소리까지 담깁니다. 기기 안에서 도는 `adb shell screenrecord`는 화면을 끊기게 해서 쓰지 않습니다.
- **2부 예시 기록은 실제로 달린 기록이 아닙니다.** 앱의 시연 코스를 2~52번 오간 것으로 만든 시연용 기록이고, 1부의 성수 주행이 첫 기록으로 들어 있습니다. 엔딩 화면 하단에 "누적 기록은 시연용 예시"라고 표시합니다.
- **녹화 시계:** 2부 녹화가 끝날 때까지 시계는 오전 11시대에 머뭅니다. 녹화용 부팅은 자동 시간을 끕니다. 평소 테스트에서 실제 시간이 필요하면 `adb -s emulator-5556 shell settings put global auto_time 1`로 되돌립니다.

## 3. 편집과 렌더링

> **녹화 없이 바로 렌더링할 수 있습니다.** 지금 영상에 쓴 장면 클립·배경음악·글꼴은 GitHub Release [`demo-assets-v1`](https://github.com/TEAM-zobo/fogdrive/releases/tag/demo-assets-v1)(약 120MB)에 있습니다. 녹화본(`tools/demo/out/raw`, 2.6GB)은 git에 없으므로, 새 컴퓨터에서는 2절 녹화를 건너뛰고 아래 명령부터 실행하면 됩니다. `prepare_assets.sh`가 녹화본이 없으면 Release를 받습니다. 비공개 저장소라 [GitHub CLI](https://cli.github.com)(`gh`) 로그인이 필요합니다(`gh auth login`).

```bash
cd tools/demo/promo
./prepare_assets.sh                 # 녹화본 → 장면 클립(assets/shots). 녹화본이 없으면 Release에서 받음. 글꼴·배경음악·효과음·앱 아이콘도 준비
python3 build.py                    # 장면 목록 → index.html + compositions/*.html
npx hyperframes@0.8.196 check       # 오류 0개인지
npx hyperframes@0.8.196 snapshot --at 3,23,53,68,98      # 원하는 시각의 프레임 PNG (.hyperframes/artifacts/snapshots)
npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/fogdrive-promo.mp4
```

- `prepare_assets.sh`는 끝에서 모든 소재를 `assets.sha256`과 비교합니다. "every asset matches"가 나오면 커밋된 영상과 같은 소재입니다. 2026-10-26에 녹화본 없는 새 클론에서 Release로 숏폼을 렌더링해 비교했더니 소리는 비트 단위로 같고, 화면은 1,410프레임 중 101프레임에서만 눈에 안 보이는 차이(가장 큰 차이도 PSNR 52.8dB)가 있었습니다.
- 녹화본이 있어도 Release 소재로 렌더링하려면 `./prepare_assets.sh --release`.
- 렌더링은 약 4분 걸립니다(M 시리즈 Mac 기준).
- 미리 보며 고치려면 `npx hyperframes@0.8.196 preview`를 실행합니다. 브라우저 편집기(Studio)가 열리고, HyperFrames 데스크톱 앱으로도 열 수 있습니다.

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

접수양식 02-8 숏폼(선택)은 같은 녹화본으로 만드는 세로 영상입니다. 제출 조건과 장면은 [submission/shortform.md](submission/shortform.md)에 있습니다.

- 결과물: `tools/demo/shortform/renders/fogdrive-shortform.mp4` (1080×1920, 30fps, 47초, git 제외)
- 소재: 홍보 영상의 `assets/`(장면 클립, 글꼴, 음악, 효과음, 아이콘)를 그대로 씁니다. `build.py`가 `tools/demo/shortform/assets`를 `../promo/assets`로 연결합니다(심볼릭 링크를 만들 수 없는 Windows에서는 복사).

```bash
tools/demo/promo/prepare_assets.sh          # 홍보 영상과 같은 준비(이미 했으면 생략, 녹화본이 없으면 Release에서 받음)
cd tools/demo/shortform
python3 build.py                            # 장면 목록 → index.html + compositions/*.html
npx hyperframes@0.8.196 check
npx hyperframes@0.8.196 snapshot --at 0,12,22,26,31,37,44
npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/fogdrive-shortform.mp4
```

- **편집은 `tools/demo/shortform/build.py`에서.** 장면 목록, 카메라, 자막, 효과음이 있고 색·글꼴·글자 효과는 홍보 영상의 `build.py`에서 가져옵니다.
- **세로 구도:** 카메라 `(원본 x, 원본 y, 배율)`의 기본은 `TALL = (1680, 700, 1.5)`(차 주변 720×1280 원본 픽셀)입니다. `ms=초`는 클립을 그만큼 건너뛰고 시작합니다.
- **글자 위치:** 휴대폰 화면 위 168–1330 px 안에만 둡니다. 그 아래는 플랫폼의 설명·버튼이 덮습니다. 첫 프레임(썸네일)에 질문 제목이 이미 떠 있어야 합니다.
- **해시태그:** `HASHTAG`에 있고 마지막 화면에 나옵니다. 게시글 본문에도 적습니다.

## 6. 문제 해결

| 증상 | 원인·해결 |
| --- | --- |
| `prepare_assets.sh`가 "gh(GitHub CLI)가 필요합니다" 또는 release not found | 녹화본이 없어 Release를 받으려는 것입니다. `brew install gh`(Windows `winget install GitHub.cli`) 후 `gh auth login`으로 TEAM-zobo 저장소에 권한 있는 계정으로 로그인합니다 |
| "differ from assets.sha256" 경고 | 소재가 커밋된 영상과 다릅니다. 다시 녹화했다면 정상(위 「다시 녹화했다면」). 아니라면 효과음 스킬이 업데이트된 경우가 많습니다: `assets/sfx`를 지우고 다시 실행하거나, 이전 효과음 파일을 구합니다 |
| 지도가 야간 모드(어두움)로 찍힘 | 앱은 차 위치의 실제 일출·일몰을 따릅니다. `boot --record`로 띄우면 시계가 오전 11시입니다. adb로는 시간을 바꿀 수 없어서(권한 없음) 부팅할 때 `-qemu -rtc base=...T02:00:00`으로 정합니다 |
| `prepare_demo.py`에서 "불러올 파일이 없어요" | AAOS에서는 adb가 운전자 계정의 외부 앱 폴더에 쓸 수 없습니다. 스크립트는 `run-as`로 앱 내부 폴더에 복사하고, 앱의 불러오기도 그 폴더를 봅니다(`FogRuntime.importCandidates`). 최신 디버그 APK인지 확인하세요 |
| 녹화본 소리가 작음 | 에뮬레이터 출력이 원래 작습니다(음성 최대 -27 dBFS). `prepare_assets.sh`가 음성 클립을 +22dB 올리고, 렌더 뒤 전체 -17~-20 LUFS, 피크 -1 dBFS 이하면 정상입니다(현재 -19.4 LUFS, 피크 -1.9) |
| 음성이 중간에 잘림 | 클립 경계가 문장 중간입니다. `ffmpeg -i tools/demo/out/raw/a3_first_drive.webm -af silencedetect=noise=-42dB:d=0.4 -f null -`로 문장 시작 시각을 보고 `prepare_assets.sh`의 voice 구간을 문장 하나가 다 들어가게 고칩니다 |
| 장면 번호를 바꾼 뒤 `check`가 실패 | `python3 build.py`를 다시 실행했는지, 장면 id와 파일 이름이 같은지 확인합니다 |
| 에뮬레이터가 다른 세션·QA 테스트와 겹침 | 녹화 중에는 다른 에뮬레이터와 QA 도구를 쓰지 않습니다 |

## 예전 방식 (참고)

`tools/demo/build_video.py`와 `captions.swift`는 녹화본을 ffmpeg로만 이어 붙이던 첫 버전입니다. 자막은 왼쪽 패널에 들어가고, 화면 줌과 배경음악은 없습니다. 지금은 HyperFrames 홍보 영상을 씁니다.
