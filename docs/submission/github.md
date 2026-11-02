# GitHub 저장소 제출

시연 영상과 함께 가산점이 붙는 선택 제출물입니다. 공모전 「GitHub 저장소 제출 안내」와 FAQ의 요건을 이 저장소에 맞춰 정리했습니다.

## 요건과 현재 상태

| 요건 | 이 저장소 | 상태 |
| --- | --- | --- |
| 소스 코드 업로드 | `main` 브랜치 전체(부릉탐험대, 2026-11-02 `design/pioneer-trail`을 합침) | 완료 |
| release APK를 `apk/` 폴더에 | `apk/zobo_Burung_release.apk` (약 40MB, 100MB 제한 이내) | 완료, 제출 직전에 다시 빌드 |
| 파일명 규칙 `팀명_앱이름_release.apk` | `zobo_Burung_release.apk` (앱 이름 부릉탐험대를 영문으로, Windows 경로 문제를 피하려고) | 완료 |
| README: 앱 이름·한 줄 소개 | [`README.md`](../../README.md) 첫 줄 | 완료 |
| README: 실행 방법 | README 「실행 방법」, 「시연 방법」 | 완료 |
| README: 테스트 환경·필요 설정 | README 「테스트 환경」, [`docs/SETUP.md`](../SETUP.md) (macOS·Windows) | 완료 |
| README: APK 위치 | README 「APK」 | 완료 |
| 비공개 저장소면 심사용 계정 초대 | 운영국이 계정을 공지하면 **읽기 권한**으로 초대(아래) | **공지 대기** |
| 초대 후 접근 확인 | 마감 전 심사용 계정에서 저장소·APK가 보이는지 | 공지 대기 |
| 계정정보 노출 금지 | 저장소 주소·README는 팀명 `zobo`만 사용 | 완료 |
| 저장소 주소의 계정 ID | `https://github.com/TEAM-zobo/fogdrive` (팀 조직 TEAM-zobo) | 완료 |

심사용 계정은 2026-11-02에 공모전 사이트(공지사항 3건, FAQ, 참가 가이드)를 다시 확인했을 때도 공지되지 않았습니다. 마감 전까지 공지가 없으면 운영사무국(`appdevchallenge@hyundai-partners.com`)에 문의합니다.

## 저장소 위치: TEAM-zobo 조직

저장소는 2026-10-25에 개인 계정에서 팀 조직 **TEAM-zobo**로 옮겼습니다. 주소에 개인 계정 ID가 보이지 않습니다.

- 제출 주소: `https://github.com/TEAM-zobo/fogdrive` (비공개)
- 예전 주소는 GitHub가 새 주소로 자동으로 넘겨 주지만, 접수양식·이메일에는 새 주소만 씁니다.
- 로컬 저장소 주소 바꾸기: `git remote set-url origin https://github.com/TEAM-zobo/fogdrive.git`

## 심사용 계정 초대 (읽기 권한)

조직의 무료 플랜 비공개 저장소는 브랜치 보호를 쓸 수 없습니다. 그래서 심사용 계정은 **읽기(Read) 권한 외부 협업자**로 초대해, 저장소를 바꿀 수 없게 합니다.

- 웹: 저장소 Settings → Collaborators and teams → Add people → 계정 입력 → 역할 **Read**
- 명령: `gh api -X PUT repos/TEAM-zobo/fogdrive/collaborators/<심사용 계정> -f permission=pull`
- 확인: `gh api repos/TEAM-zobo/fogdrive/invitations --jq '.[] | "\(.invitee.login) \(.permissions)"'` 에 `read`로 나오는지

## 제출 직전 할 일

```bash
git pull
./gradlew :app:testDebugUnitTest            # 단위 테스트
./gradlew :app:assembleRelease              # keystore.properties가 있으면 release 키로 서명
cp app/build/outputs/apk/release/app-release.apk apk/zobo_Burung_release.apk
git add apk/zobo_Burung_release.apk && git commit -m "release APK 갱신" && git push
```

- 새 APK를 에뮬레이터에 설치해 실행되는지 확인합니다: `adb install -r -g apk/zobo_Burung_release.apk`
- 심사용 계정은 반드시 **Read**로 초대합니다. 무료 조직의 비공개 저장소는 브랜치 보호가 없어서, Write로 초대하면 `main`에 바로 푸시할 수 있습니다.
- 제출한 상태를 태그로 남겨 둡니다: `git tag submission-1st && git push origin submission-1st`. 마감 뒤 바뀐 내용과 심사본을 구분할 수 있습니다.
