# 시연 영상

접수양식 02-7 참고자료에 넣는 시연 영상(선택, 가산점)입니다. 공모전은 목업·프로토타입·화면 녹화를 모두 인정합니다. 만드는 방법 전체(도구 설치, 녹화, 편집, 렌더링, 수정, 라이선스)는 **[docs/VIDEO.md](../VIDEO.md)** 에 있습니다. 이 문서는 제출용으로 무엇을 담을지와 올리기 전 확인만 다룹니다.

## 지금 상태 (2026-11-02)

- **시연 영상 v1 (1분 14초, 1920×1080):** `tools/demo/burung-demo/`(HyperFrames)로 만들었습니다. 완성본과 녹화본은 GitHub Release `burung-demo-raw-v1`에 있습니다. 40대 아빠 A씨의 평일 등원·출근(「프로 출근러」)과 토요일 올림픽공원 나들이를 담았습니다.
- **v2 시나리오:** 시연 페르소나를 기획서 4장의 A씨 하나로 통일하면서, 서비스 개요 → 메뉴별 기능 안내(지도·시연 주행·도감·내 탐험·설정) → A씨의 일주일(등원·출근·토요일 나들이·강원도 가족 여행)로 이어지는 약 2분 30초 구성과 다른 PC에서 그대로 만드는 절차를 [`persona.md`](persona.md)에 정리했습니다(녹화 `tools/demo/burung-demo/record.py`). v1은 통일 전의 시연 기록(출퇴근만 한 4주)으로 찍어서, v2로 다시 찍을 때 이 구성을 따릅니다.
- `tools/demo/promo/renders/fogdrive-promo.mp4`(1분 46초)는 예전 앱 **안개지도** 화면이라 제출하지 않습니다.
- 세로 숏폼([`shortform.md`](shortform.md))은 접수양식 02-8에 따로 적는 것이고 시연 영상을 대신하지 않습니다.

## 담을 장면

[`persona.md`](persona.md)의 **6. 영상 구성**을 따릅니다. 시연 페르소나는 기획서 4장의 40대 아빠 A씨 하나이고, 지난 4주 기록·앱의 시연 코스·영상 장면이 모두 A씨의 일주일입니다. 화면은 실제 Pleos Connect 에뮬레이터 v2.1.0 녹화만 씁니다(지어낸 화면·숫자 없음). 지난 기록은 시연용 예시라고 마지막 화면에 표시합니다.

## 올리기 전 확인

- [ ] 처음부터 끝까지 재생해 소리·자막·화면이 맞는지 본다(음량은 `ffmpeg -af ebur128`로 -16~-18 LUFS인지 재고, 직접 들어 본다)
- [ ] 화면·자막·파일 이름·채널 이름에 성명·이메일·계정 ID가 없다
- [ ] 배경음악을 쓰면 영상 설명란에 출처를 적는다(예: Music: "Fretless" Kevin MacLeod (incompetech.com), Licensed under Creative Commons: By Attribution 4.0 License)
- [ ] YouTube **일부 공개**(링크가 있는 사람만)로 올리고, 실명이 보이지 않는 팀 zobo 채널을 쓴다
- [ ] 링크를 로그인하지 않은 브라우저에서 재생해 봤다
- [ ] 링크를 [`02-app-introduction.md`](02-app-introduction.md)의 【기입 필요: 부릉탐험대 시연 영상 링크】와 [`email.md`](email.md), `private/email.txt`에 넣고 `python3 tools/submission/build_form.py`로 양식을 다시 만들었다
