# mandro-dynamic-gesture

프로젝트 전체 개요, 아키텍처, 진화 과정, 트러블슈팅 히스토리는
**[`HANDOVER.md`](HANDOVER.md)** 참고.

세부 설계/프로토콜 문서는 [`app/src/main/java/com/mandro/docs/`](app/src/main/java/com/mandro/docs/)에 있음
(`HANDOVER.md`의 "문서 지도" 섹션에 각 문서 설명 정리돼 있음).

## 실행 환경 (실기기)

- **Android 7.0 (API 24) 이상**, BLE(Bluetooth Low Energy) 지원 기기 필수
  — 암밴드와 BLE로 통신하는 게 핵심 기능이라 `AndroidManifest.xml`에
  `android.hardware.bluetooth_le`가 `required="true"`로 박혀 있음
- 네이티브 라이브러리는 **arm64-v8a 전용**으로 빌드됨(`abiFilters`) — 대부분의
  실기기는 arm64라 문제없지만, 에뮬레이터 사용 시 arm64-v8a 시스템 이미지가
  필요함 (x86_64 순정 이미지는 네이티브 라이브러리 로드 실패 가능)
- 어차피 에뮬레이터는 실제 BLE 하드웨어가 없어서 암밴드 연결 자체가 안 됨 —
  BLE 관련 기능(연결/녹화/추론) 테스트는 실기기 필요

## 개발 스택 / 빌드 환경

| 항목 | 버전 |
|---|---|
| JDK | 21 (Android Studio 번들 JBR 사용 권장) |
| Gradle | 9.4.1 (wrapper) |
| AGP (Android Gradle Plugin) | 8.7.3 |
| Kotlin | 2.1.0 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |

**주요 라이브러리**: Jetpack Compose(BOM 2024.12.01), Hilt 2.52, Room 2.6.1,
TensorFlow Lite 2.16.1, Chaquopy 17.0.0(온디바이스 Python 학습), Lottie 6.6.0

터미널에서 직접 `gradlew` 실행 시 `JAVA_HOME`을 새 터미널마다 다시 잡아줘야
함(자세한 명령은 `HANDOVER.md` §9 참고).

## `docs/archive/` 폴더

`docs/` 바로 아래에 있는 문서는 전부 지금도 유효한 것들이고, **더 이상 최신이
아니게 된 문서는 삭제하지 않고 [`docs/archive/`](app/src/main/java/com/mandro/docs/archive/)로
옮겨서 보존**함 — 왜 그렇게 결정했는지, 뭐가 바뀌었는지 같은 히스토리가 나중에
필요할 수 있어서. 예: 서버 기반 구조였던 시절의 README, 로컬 전환 **사전**
설계안, 기능 구현 **전** 설계 논의 문서 등. 각 archive 파일 맨 위에 왜
구버전이 됐는지와 최신 내용이 어디로 옮겨갔는지 안내가 붙어있음 — 자세한
목록은 `HANDOVER.md` §10("문서 지도") 참고.
