# 택시장부

전기차 개인택시용 하루 장부 앱 (안드로이드).

- **영업 마감**: 정산 영수증을 찍으면 총합계·날짜를 자동으로 읽고, 계기판 누적거리·총주행·순주행을 키패드로 입력
- **충전**: 집 충전기 화면의 kWh 입력
- **월별 정리**: 합계, 전기요금, 순수입금 계산 / `2026년수입금내역서.xlsx` (월별 시트) 엑셀 내보내기

기록은 폰 안에만 저장됩니다 (이 저장소에는 개인 기록이 없습니다).

## 설치

[최신 APK 받기](../../releases/latest/download/taxi-ledger.apk) → 폰에서 열기 → "출처를 알 수 없는 앱" 허용 → 설치.
새 버전도 같은 방법으로 설치하면 기록은 그대로 유지됩니다.

## 구조

| 경로 | 내용 |
|---|---|
| `app/src/main/assets/index.html` | 화면 전체 (입력, 월별 표, 영수증 해석 `parseReceipt`, 엑셀 생성) |
| `app/src/main/java/kr/taxiledger/MainActivity.java` | 카메라 + ML Kit 한국어 글자 인식, 엑셀 저장, 백업 |
| `.github/workflows/build.yml` | `main` 에 올리면 APK를 만들어 Releases 에 게시 |

빌드에는 저장소 Secrets `KEYSTORE_B64`(서명 키 base64), `KS_PASS`(키 비밀번호)가 필요합니다.
서명 키를 잃어버리면 기존 앱 위에 업데이트할 수 없으니 따로 보관하세요.
