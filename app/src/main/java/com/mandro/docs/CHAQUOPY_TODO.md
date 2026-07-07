# Chaquopy 로컬 학습 전환 — TODO

`LOCAL_MIGRATION.md` 로드맵 중 "1단계 — Chaquopy 실험"이 끝난 뒤의 실행 체크리스트.

---

## 완료

- [x] Chaquopy 실현 가능성 확인 — numpy/scipy/scikit-learn이 Python 3.10용 wheel로 Chaquopy 저장소에 존재 (scipy/scikit-learn은 cp310까지만 지원, 3.11+ 없음)
- [x] APK 용량 실측 — arm64-v8a 단일 ABI 빌드 기준 **81.5MB** (Chaquopy 넣기 전 대비 큰 폭 증가, 감수하기로 결정)
- [x] TF vs sklearn 학습 품질/속도 벤치마크 — 실제 수집 데이터 2세션 기준
  - 세션1: TF acc 0.9112/F1 0.9080 vs sklearn acc 0.8774/F1 0.8805
  - 세션2: TF acc 0.9323/F1 0.9192 vs sklearn acc 0.9216/F1 0.9171
  - 학습 시간: sklearn이 PC 기준 5~12배 빠름
  - 벤치마크 스크립트: 세션 스크래치패드 `bench_chaquopy.py` (필요 시 재실행 가능)
- [x] `build.gradle.kts` / `app/build.gradle.kts`에 Chaquopy 플러그인 설정 (Python 3.10, arm64-v8a 필터, numpy/scipy/scikit-learn pip install)
- [x] Python 모듈 골격 생성 — `app/src/main/python/training_local.py` + `lib/`(config, preprocessing, pipeline, windowing, training, serialize, features/features.py)

---

## Phase 1 — Python 학습 파이프라인 완성

- [ ] `lib/preprocessing.py`: `mandro-backend/app/ml/lib/preprocessing.py`에서 `BP_filter`, `compute_enveloppe` 복사
- [ ] `lib/features/features.py`: `mandro-backend/app/ml/lib/features/features.py`에서 `extract_features`, `extract_features_tsd` 복사
- [ ] `lib/pipeline.py`: `extract_features_pipeline`, `fit_scaler`, `apply_scaler` 복사
- [ ] `lib/windowing.py`: `get_windows`, `get_action_windows`, `split_train_val` 복사
- [ ] `lib/preprocessing.py`의 `preprocess_and_extract()`: `mandro-backend/app/services/training.py::_preprocess_and_extract` 로직 이식
- [ ] `lib/training.py`의 `fit_local_model()`: `MLPClassifier` 학습 코드 작성 (docstring에 가이드 있음)
- [ ] `lib/serialize.py`의 `serialize_weights()`: 가중치 → 52,248바이트 바이너리 직렬화

## Phase 2 — Python 모듈 단독 검증 (Kotlin 붙이기 전)

- [ ] PC에서 `training_local.run_training_local()`을 직접 호출해 실제 세션 데이터(`mandro-backend/storage/data/`)로 돌려보고, 출력 바이트 길이 52,248 확인
- [ ] `bench_chaquopy.py`와 정확도 비교해서 이식 과정에서 실수 없었는지 검증

## Phase 3 — Kotlin ↔ Chaquopy 연동

- [ ] `Application` 클래스(또는 Hilt 모듈)에서 `Python.start(AndroidPlatform(context))` 초기화
- [ ] `CollectViewModel`(또는 새 `LocalTrainingRepository`)에서 Room DB에 쌓인 EMG 데이터를 `ByteArray`/`IntArray`로 변환
- [ ] `Dispatchers.IO`에서 `Python.getInstance().getModule("training_local").callAttr("run_training_local", emgBytes, labelsBytes)` 호출 → `ByteArray` 수신
- [ ] 실제 폰(에뮬레이터 아님)에서 학습 시간 실측 — Chaquopy는 폰 CPU로 도니까 PC 벤치마크(1~3.6초)와 다를 수 있음

## Phase 4 — 가중치 전송 프로토콜 (BLE/USB)

- [ ] `[4B 매직넘버 0xDEADBEEF][4B 길이][52,248B 가중치][4B CRC32]` 패킷 구성 코드
- [ ] BLE Write characteristic 또는 USB Serial 중 어느 쪽으로 보낼지 결정
- [ ] BLE라면 MTU 244바이트 청크 분할 전송 (~262개 패킷)

## Phase 5 — 펌웨어 쪽 (상대방 담당 확인 필요)

- [ ] `MODEL.h`에서 가중치 배열 제거, 토폴로지만 유지
- [ ] `nn.cpp`에 `loadFromSPIFFS()` 구현
- [ ] `.ino`에 가중치 수신 모드(매직넘버 감지 → SPIFFS 저장 → 재부팅) 추가
- [ ] 더미 가중치로 수신→저장→로드 단독 테스트

## Phase 6 — 통합 테스트

- [ ] BLE 수집 → 로컬 학습 → 가중치 전송 → 펌웨어 재부팅 → 추론까지 전체 플로우 실기기로 확인
- [ ] 실측 학습 정확도가 기존 서버 학습 대비 사용자가 체감할 만큼 떨어지는지 확인 (Phase 2 벤치마크 기준 세션별 1~3.4%p 차이 — 실사용에서 문제없는 수준인지 재확인)
