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

- [x] `lib/preprocessing.py`: `mandro-backend/app/ml/lib/preprocessing.py`에서 `BP_filter`, `compute_envelope` 복사
- [x] `lib/features/features.py`: `mandro-backend/app/ml/lib/features/features.py`에서 `extract_features`, `extract_features_tsd` 복사
- [x] `lib/pipeline.py`: `extract_features_pipeline`, `fit_scaler`, `apply_scaler` 복사
- [x] `lib/windowing.py`: `get_windows`, `get_action_windows`, `split_train_val` 복사
- [x] `lib/preprocessing.py`의 `preprocess_and_extract()`: `mandro-backend/app/services/training.py::_preprocess_and_extract` 로직 이식
- [x] `lib/training.py`의 `fit_local_model()`: `MLPClassifier` 학습 코드 작성 (docstring에 가이드 있음)
- [x] `lib/serialize.py`의 `serialize_weights()`: 가중치+스케일러 → 53,304바이트 바이너리 직렬화 (mean/std 264개 포함, NN 가중치만이면 52,248바이트)

## Phase 2 — Python 모듈 단독 검증 (Kotlin 붙이기 전)

- [x] PC에서 `training_local.run_training_local()`을 직접 호출해 실제 세션 데이터(`mandro-backend/storage/data/`)로 돌려보고, 출력 바이트 길이 53,304 확인
- [x] `bench_chaquopy.py`와 정확도 비교해서 이식 과정에서 실수 없었는지 검증
  - 세션1: ported acc=0.8774/f1=0.8805 vs bench acc=0.8774/f1=0.8805 (diff 0)
  - 세션2: ported acc=0.9216/f1=0.9171 vs bench acc=0.9216/f1=0.9171 (diff 0)
  - 완전 일치 — 이식 과정 로직 실수 없음

## Phase 3 — Kotlin ↔ Chaquopy 연동

- [x] `MandroApplication.onCreate()`에서 `Python.start(AndroidPlatform(this))` 초기화
- [x] `LocalTrainingRepository`/`LocalTrainingRepositoryImpl` 신설 — `EmgSerialization.kt`의 공용 `serializeBatch()`로 EMG/라벨 `ByteArray` 변환
- [x] `Dispatchers.IO`에서 `Python.getInstance().getModule("training_local").callAttr("run_training_local", ...)` 호출 → `ByteArray` 수신, 크기(53,304) 검증
- [x] `TrainingProgressViewModel`을 서버 업로드(`uploadAndTrain`) 대신 로컬 학습으로 교체, 결과를 `files/models/$userId/weights.bin`에 저장
- [ ] 실제 폰(에뮬레이터 아님)에서 학습 시간 실측 — Chaquopy는 폰 CPU로 도니까 PC 벤치마크(1~3.6초)와 다를 수 있음

## 서버 코드 전체 제거 (예정보다 앞당겨 완료)

로컬 전환 방향이 확정되면서 서버 의존 코드를 이번에 전부 걷어냄:

- [x] `UserRepositoryImpl` → 로컬 Room(`UserEntity`/`UserDao`)으로 전환, 유저 생성 시 UUID 로컬 생성
- [x] `EmgRepository`에서 `uploadTake`/`uploadAndTrain`/`getSessionHistory`/`downloadSessionFirmware`/`saveHeaderFiles`/`getLatestSession` 제거
- [x] `FirmwareViewModel` — 서버 세션 대신 로컬 `weights.bin` 파일 존재 여부로 판단
- [x] `CollectViewModel`의 랩마다 서버 업로드 호출 제거
- [x] History 화면(서버 전용, 로컬 대응 없음) 전체 삭제
- [x] `MandrApiService`/DTO/`NetworkModule`/Retrofit·OkHttp 의존성 제거

## Phase 4 — 가중치 전송 프로토콜 (USB + BLE) — 완료

방향 확정: **A안(LittleFS)**. `docs/FIRMWARE_PROTOCOL.md`의 모델 플래시 방식(9절, 펌웨어 통째 재컴파일)은 서버 기반 구방식 문서이고, 이 문서(`LOCAL_MIGRATION.md`)가 로컬 전환 후 신방식. `UsbRepositoryImpl.kt`에 있던 `MDRO`+`model.tflite`+`scaler.bin` 프로토콜은 옛날 코드였음 — 폐기.

- [x] `serialize_weights(model, scaler)`로 스케일러(mean/std 264개, 1,056바이트) 포함하도록 수정 — NN 가중치만으로는 펌웨어가 입력 정규화를 못 함
- [x] `UsbRepositoryImpl.kt` 재작성: `[4B 매직넘버 0xDEADBEEF][4B 길이][53,304B 가중치+스케일러][4B CRC32]` 단일 페이로드 프로토콜, `flash(weightsBytes: ByteArray)` 시그니처로 단순화
- [x] `FirmwareViewModel`/`LocalTrainingRepositoryImpl`/`TrainingProgressViewModel`의 바이트 상수 53,304로 갱신
- [x] BLE 경유 전송 구현 — `BleManager.kt::sendWeights()`. USB와 동일한 페이로드를 MTU 기준 244바이트씩(~219개) 순차 Write Request로 전송, 신규 Characteristic(`...58`, WRITE+NOTIFY)의 NOTIFY로 `OK:WEIGHTS`/`ERR:*` 응답 대기 (10초 타임아웃). 상세 스펙: `docs/FIRMWARE_PROTOCOL.md` 4-1절
- [x] `WeightTransferState`(Idle/Sending/Done/Error) 신설 — `BleRepository`/`BleRepositoryImpl`/`MockBleRepository`에 배선
- [ ] UI 연결 미완료 — `FirmwareScreen`에서 USB/BLE 둘 중 선택하는 화면은 아직 없음 (지금은 `usbRepository.flash()`만 호출)

## Phase 5 — 펌웨어 쪽 (상대방 담당)

- [ ] `MODEL.h`에서 가중치 배열 제거, 토폴로지 상수만 유지
- [ ] `nn.cpp`에 `loadFromLittleFS()` 구현 (W0 b0 W1 b1 W2 b2 means stds 순서로 읽기, 총 13,326 floats)
- [ ] `.ino`에 LittleFS 초기화 + USB 수신 모드(매직넘버 0xDEADBEEF 감지 → LittleFS 저장 → 재부팅) 추가
- [ ] **신규**: BLE GATT에 Characteristic `abcd1234-5678-1234-5678-abcdef123458` (WRITE+NOTIFY) 추가 — 스펙은 `docs/FIRMWARE_PROTOCOL.md` 4-1절 참고.
  - 매직넘버/CRC **검증** 로직은 USB와 재사용 가능하지만, **수신/재조립 로직은 별도 구현 필요**. USB Serial은 연속 스트림이라 그냥 읽으면 되지만, BLE는 Android(`BleManager.kt::sendWeights()`)가 **244바이트 단위(~219개 Write Request)**로 쪼개서 보내므로, 펌웨어는 각 Write를 순서대로 누적 버퍼에 이어붙이다가 헤더에 명시된 총 길이(53,304B)만큼 다 모이면 그때 매직넘버/CRC 검증하는 상태 머신이 필요함
  - 청크 크기는 Android 쪽에서 이미 244바이트(MTU 247 - ATT 헤더 3바이트)로 고정 구현됨 — 펌웨어에서 별도로 청크 크기를 정할 필요 없이, 그 크기의 Write를 받아들이는 버퍼만 준비하면 됨
- [ ] **신규(안전장치)**: 가중치 파일은 임시 파일(예: `/weights.tmp`)에 먼저 쓰고, CRC 검증까지 통과한 뒤에만 `/weights.bin`으로 교체(rename). 전송 도중 BLE 연결 끊김/USB 뽑힘 등으로 실패해도 기존에 쓰던 가중치 파일이 훼손되지 않도록 함 (CRC 불일치 시 임시 파일만 삭제, 기존 `/weights.bin`은 그대로 유지)
- [ ] 더미 가중치(53,304바이트)로 수신→저장→로드 단독 테스트 (USB, BLE 둘 다) — LittleFS 자체의 53KB 쓰기/읽기 무결성은 `C:/esp32-littlefs-test/`에서 별도 검증 완료 (`README.md` 참고), 이번엔 실제 전송 경로(USB/BLE) 포함해서 재확인
- [ ] `partitions.csv`에 LittleFS 파티션 최소 256KB 확보 (실측: 53,304B 저장 시 LittleFS `사용 중` 65,536B로 나옴 — 블록 정렬로 파일 크기보다 약간 더 잡힘. 256KB면 충분한 여유)

## Phase 6 — 통합 테스트

- [ ] BLE 수집 → 로컬 학습 → USB로 가중치 전송 → 펌웨어 재부팅 → 추론까지 전체 플로우 실기기로 확인
- [ ] 실측 학습 정확도가 기존 서버 학습 대비 사용자가 체감할 만큼 떨어지는지 확인 (Phase 2 벤치마크 기준 세션별 1~3.4%p 차이 — 실사용에서 문제없는 수준인지 재확인)
