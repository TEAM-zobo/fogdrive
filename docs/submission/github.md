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
| 비공개 저장소면 심사용 계정 초대 | 운영국이 계정을 공지하면 Settings → Collaborators로 초대 | **공지 대기** |
| 초대 후 접근 확인 | 마감 전 심사용 계정에서 저장소·APK가 보이는지 | 공지 대기 |
| 계정정보 노출 금지 | 커밋 작성자 `zobo` + GitHub noreply 이메일 | 완료 |
| 저장소 주소의 계정 ID | 주소에 개인 계정 ID가 보임 | **결정 필요** (아래) |

## 결정 필요: 저장소 주소의 계정 ID

양식은 공유 링크와 첨부자료에 계정정보가 드러나지 않게 하라고 합니다. 지금 저장소 주소에는 개인 GitHub 계정 ID가 들어 있습니다. 제출 전에 둘 중 하나를 정합니다.

1. **GitHub Organization으로 이전(권장):** 팀명 기반 조직(예: `zobo-team`)을 만들고 Settings → Transfer로 저장소를 옮깁니다. 이전 주소는 자동으로 새 주소로 넘어가지만, 접수양식·이메일에는 새 주소를 씁니다. 로컬은 `git remote set-url origin <새 주소>`.
2. **운영국에 문의:** 10/28 온라인 설명회나 문의 메일로 계정 ID 노출이 "계정정보 노출"에 해당하는지 확인하고 답에 따릅니다.

## 제출 직전 할 일

```bash
git pull
./gradlew :app:testDebugUnitTest            # 단위 테스트
./gradlew :app:assembleRelease              # keystore.properties가 있으면 release 키로 서명
cp app/build/outputs/apk/release/app-release.apk apk/zobo_FogDrive_release.apk
git add apk/zobo_FogDrive_release.apk && git commit -m "release APK 갱신" && git push
```

- 새 APK를 에뮬레이터에 설치해 실행되는지 확인합니다: `adb install -r -g apk/zobo_FogDrive_release.apk`
- Collaborator로 초대한 계정은 **쓰기 권한**도 받습니다. 초대 전에 `main` 브랜치 보호(Settings → Branches)를 켜 둡니다.
- 제출한 상태를 태그로 남겨 둡니다: `git tag submission-1st && git push origin submission-1st`. 마감 뒤 바뀐 내용과 심사본을 구분할 수 있습니다.
