# 숏폼 (선택)

접수양식 02-8에 링크를 적는 세로 영상입니다. 본상 가산점은 없고, 별도 특별상(1팀, 50만 원, 2027년 2월 행사 초청)이 있습니다.

- 프로젝트: `tools/demo/burung-short` (HyperFrames, 1080×1920, 30fps, 40.6초). 편집은 `build.py`만 고칩니다
- 결과물: `tools/demo/burung-short/renders/burung-shortform.mp4` (git 제외). **2026-11-02 기준 아직 렌더하지 않았습니다**
- 소재: `tools/demo/burung-short/assets/`(에뮬레이터 앱 화면 캡처, 주행 클립, 글꼴, 음악, 효과음)는 git에 없습니다. 이 폴더가 있는 컴퓨터에서 렌더합니다
- 가이드: 길이 30초–1분, 세로 9:16, Instagram 릴스 또는 YouTube Shorts에 게시. 필수 해시태그 `#HMG차량용앱개발공모전`을 포함해 **해시태그 총 2~3개**를 본문·설명란에 달아야 정상 참여로 인정(제안: `#PleosAppMarket` `#HMG앱공모전`). 근거: [숏폼 챌린지](https://appdevchallenge.auto-hmg.io/guide/shortform), [FAQ](https://appdevchallenge.auto-hmg.io/faq)
- 계정: 실명·개인 계정이 드러나지 않게 팀 zobo 계정으로 올립니다
- 예전 앱(안개지도) 숏폼 `tools/demo/shortform`(47초)은 지금 앱과 달라서 쓰지 않습니다

```bash
cd tools/demo/burung-short
python3 build.py
npx hyperframes@0.8.196 check
npx hyperframes@0.8.196 snapshot --at 1,4.5,8,12,16.5,21,25.5,30,34,38
npx hyperframes@0.8.196 render --quality delivery --video-frame-format png -o renders/burung-shortform.mp4
```

## 장면 (40.6초)

글자는 화면 위 168–1330 px 안에만 둡니다(그 아래는 플랫폼의 설명·버튼이 덮음).

| 시간 | 화면 | 자막 |
| --- | --- | --- |
| 0–4.6초 | 주행 지도 위로 글자가 쌓임 | 출퇴근, 근교 드라이브, 여행… 운전은 우리의 일상 |
| 4.2–8초 | 노란 화면 | 이걸 전부 기록으로 남길 순 없을까? |
| 7.8–12.4초 | 앱 아이콘과 이름 | 부릉탐험대 · 주행이 기록이 되고, 기록이 탐험이 되는 차량 앱 · 시동만 걸면 자동 기록 |
| 12–14초 | 주행 화면, 길이 열림 | 앱 안 열어도, 달린 길이 열려요 |
| 14–19초 | 최근 주행 → 내 탐험 칭호 | 매일 출퇴근하나요? → 출퇴근만 해도 칭호 GET! (프로 출근러 15/20) |
| 18.6–23.4초 | 지도의 탐험지 추천 카드 | 주말에 가족이랑 색다른 곳, 어디 없을까? → 내 주행 기록으로 새 목적지 추천 · 누르면 바로 내비 안내 |
| 23–28초 | 설정 키즈모드 → 내 탐험(키즈) | 등원길, 아이에겐 지루한 시간? NO! → 키즈모드 ON |
| 27.6–32.4초 | 도감 대한민국 지도 | 강원도는 가 봤는데 충청도는 한 번도 안 가 봤네 → 우리 가족 다음 여행지는 충청도로 고고! |
| 32–36.6초 | 도착 결산 카드 | 도착하면? → 레벨 업 · 새 칭호 · 새 스탬프 |
| 36.2–40.6초 | 앱 아이콘, 이름, 출처 | 시동을 걸면 시작되는 나만의 탐험 지도 · Pleos Connect 차량 앱 |

소리: 배경음악 "Fretless"(Kevin MacLeod, CC BY 4.0), 장면 전환에 효과음. 마지막 화면에 음악·지도 출처와 "화면은 에뮬레이터 시연"을 표시합니다. 해시태그는 영상 안에 없으므로 게시글 본문에 적습니다.

## 올리기 전 확인

- [ ] 렌더한 뒤 처음부터 끝까지 재생해 소리·자막·화면이 맞는지 본다(음량은 `ffmpeg -af ebur128`로 -16~-18 LUFS인지 재고, 직접 들어 본다)
- [ ] 휴대폰에서 업로드 화면으로 미리 보고, 자막이 플랫폼 버튼·설명에 가리지 않는지 본다
- [ ] 게시글 본문에 해시태그 3개(`#HMG차량용앱개발공모전 #PleosAppMarket #HMG앱공모전`)와 음악 출처를 적는다: Music: "Fretless" Kevin MacLeod (incompetech.com), Licensed under Creative Commons: By Attribution 4.0 License
- [ ] 화면·파일 이름·계정 이름에 성명·이메일·개인 계정 ID가 없다. 키즈모드 장면의 탐험대 이름('도현 탐험대')이 실제 가족 이름이면 다른 이름으로 바꾼다
- [ ] 링크를 [`02-app-introduction.md`](02-app-introduction.md)의 【기입 필요: 숏폼 링크】와 [`email.md`](email.md), `private/email.txt`에 넣고 `python3 tools/submission/build_form.py`로 양식을 다시 만든다
