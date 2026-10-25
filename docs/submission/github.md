# GitHub 저장소 제출

시연 영상과 함께 가산점이 붙는 선택 제출물입니다. 공모전 「GitHub 저장소 제출 안내」와 FAQ의 요건을 이 저장소에 맞춰 정리했습니다.

## 요건과 현재 상태

| 요건 | 이 저장소 | 상태 |
| --- | --- | --- |
| 소스 코드 업로드 | `main` 브랜치 전체 | 완료 |
| release APK를 `apk/` 폴더에 | `apk/zobo_FogDrive_release.apk` (약 36MB, 100MB 제한 이내) | 완료, 제출 직전에 다시 빌드 |
| 파일명 규칙 `팀명_앱이름_release.apk` | `zobo_FogDrive_release.apk` | 완료 |
| README: 앱 이름·한 줄 소개 | [`README.md`](../../README.md) 첫 줄 | 완료 |
| README: 실행 방법 | README 「실행 방법」, 「시연 방법」 | 완료 |
| README: 테스트 환경·필요 설정 | README 「테스트 환경」, [`docs/SETUP.md`](../SETUP.md) (macOS·Windows) | 완료 |
| README: APK 위치 | README 「APK」 | 완료 |
| 비공개 저장소면 심사용 계정 초대 | 운영국이 계정을 공지하면 **읽기 권한**으로 초대(아래) | **공지 대기** |
| 초대 후 접근 확인 | 마감 전 심사용 계정에서 저장소·APK가 보이는지 | 공지 대기 |
| 계정정보 노출 금지 | 커밋 작성자 이름 `zobo`. 이메일이 개인 계정과 연결되어 있음(아래) | **확인 필요** |
| 저장소 주소의 계정 ID | `https://github.com/TEAM-zobo/fogdrive` (팀 조직 TEAM-zobo) | 완료 |

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

## 남은 확인: 커밋 작성자 계정 연결

커밋 작성자 이름은 `zobo`지만, 이메일이 개인 계정의 GitHub noreply 주소라서 GitHub 화면에서 커밋마다 개인 계정 아이디·프로필 사진이 연결되어 보입니다. 심사용 계정은 저장소에 들어오면 이것을 볼 수 있습니다.

- 앞으로의 커밋: 팀 계정(예: 이미 협업자인 `zobo-work`)의 noreply 이메일로 커밋하거나, 어느 계정에도 연결되지 않은 이메일로 설정합니다(`git config user.email <주소>`).
- 지난 커밋까지 바꾸려면 저장소 기록을 다시 써서 강제 푸시해야 합니다(되돌리기 어렵고, 같이 작업하는 사람 모두 다시 받아야 함). 필요하면 운영국에 커밋 작성자 노출이 문제 되는지 먼저 문의합니다.

## 제출 직전 할 일

```bash
git pull
./gradlew :app:testDebugUnitTest            # 단위 테스트
./gradlew :app:assembleRelease              # keystore.properties가 있으면 release 키로 서명
cp app/build/outputs/apk/release/app-release.apk apk/zobo_FogDrive_release.apk
git add apk/zobo_FogDrive_release.apk && git commit -m "release APK 갱신" && git push
```

- 새 APK를 에뮬레이터에 설치해 실행되는지 확인합니다: `adb install -r -g apk/zobo_FogDrive_release.apk`
- 심사용 계정은 반드시 **Read**로 초대합니다. 무료 조직의 비공개 저장소는 브랜치 보호가 없어서, Write로 초대하면 `main`에 바로 푸시할 수 있습니다.
- 제출한 상태를 태그로 남겨 둡니다: `git tag submission-1st && git push origin submission-1st`. 마감 뒤 바뀐 내용과 심사본을 구분할 수 있습니다.
