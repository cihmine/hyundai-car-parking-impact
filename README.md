# 주차 충격 확인 (가칭)

주차 중 생긴 충격 의심 이벤트를 시간·대략적인 구역과 함께 기록하고, 다시 탔을 때 확인할 일을 알려 주는 앱입니다. Pleos Connect용으로 개발합니다.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작 · 시제품

**이 시제품은 실제 차량에서 충격을 감지하지 않습니다.** 충격 입력은 모두 가상 센서 시나리오(`demo/scenarios/`)이고, 화면 상단에 항상 "차량 연동 시뮬레이션"을 표시합니다. 판정은 '충격 의심·구역 추정'이며 파손 여부·가해자·책임을 판단하지 않습니다.

## 무엇이 실제로 동작하나

| 기능 | 상태 | 입력 출처 |
|---|---|---|
| 구역 추정(좌앞·우앞·좌뒤·우뒤·판단 어려움) | 실제 규칙 코드로 계산 | 가상 센서 시나리오 |
| 형식 오류·약한 신호 거르기, 이유 기록 | 실제 | 가상 센서 시나리오 |
| 같은 이벤트 중복 차단(eventId) | 실제(재시작·삭제 뒤에도 유지) | — |
| 기록 저장·앱 재시작 복구 | 실제(기기 안 JSON 파일, 원자적 쓰기) | — |
| 재탑승 요약 화면·음성 안내 | 실제(Pleos TTS, 없으면 기기 TTS) | 재탑승은 시연 버튼 |
| 확인 완료·메모·삭제, 주행 중 상세 제한 | 실제 | 차량 상태는 시연 패널(모의) |
| 실제 충격 센서·주차 상태·도어 신호 연결 | 미구현 — 공개 SDK 로 원시 신호 접근 미확인 | — |

자세한 범위는 [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md), 검증 결과는 [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md).

## 구조

```
core/            순수 Kotlin — 입력 형식(Impact.kt), 구역 추정(RegionEstimator), 주차 세션·기록·중복 처리(ParkingMonitor), 문구(Texts)
core/src/test/   계획서 검증 사례 P-T01~P-T10 단위 테스트(시나리오 파일을 그대로 읽음)
app/             Compose 화면, 저장(FileMonitorStore), 음성 안내(Pleos/기기 TTS), 시연 패널
demo/scenarios/  가상 센서 이벤트 JSON 7개(앱에도 그대로 들어감)
```

구역 추정 규칙(시연용 값, `EstimatorConfig`): 모든 채널이 0.4 미만이면 기록 안 함 → 채널 누락이면 '정보 부족' → 자기 채널 + 이웃 채널×0.25 점수에서 1·2위 차이가 20% 미만이면 '판단 어려움', 40% 이상이면 '근거 충분'. 센서 도착 시간차로 위치를 계산하지 않습니다.

## 실행

요구 사항: Android Studio(JDK 17 이상), Android SDK 36

```
./gradlew :core:test            # 단위 테스트
./gradlew :app:assembleRelease  # 서명 키(~/.android-keys/carvoicegame.properties)가 있으면 서명된 APK
```

테스트 환경: Pleos Connect v2.1.0 에뮬레이터(API 34, Automotive). 시연 순서: 시연 패널 '주차 시작' → 시나리오 주입 → (앱 강제 종료 후 다시 열기) → '재탑승' → 요약·음성 → 기록 확인.

기록은 기기 안에만 저장되며 앱을 지우거나 데이터를 초기화하면 사라집니다. 위치·VIN·사진은 저장하지 않습니다.

## APK

릴리스 APK는 팀명·앱 이름이 정해지면 `apk/` 폴더에 공식 파일명으로 둡니다.

## 문서

| 파일 | 내용 |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | 개발 계획서 — 구현 범위(P0/P1), 사용자 흐름, 상태·데이터 모델, 검증 사례 |
| [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) | 기능별 구현 상태와 한계 |
| [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) | 검증 사례별 결과 |
| [`docs/COMMON.md`](docs/COMMON.md) | 세 앱 공통 개발·빌드·검증 원칙, 일정, release 절차 |
| [`docs/SUBMISSION.md`](docs/SUBMISSION.md) | 1차 제출·가산점 준비 체크리스트 |
