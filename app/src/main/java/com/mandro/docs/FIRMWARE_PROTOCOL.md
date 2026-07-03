# 펌웨어 프로토콜 명세 — Android 앱 개발용

> 소스 펌웨어: `firmware/esp32/exo_armband_hybrid_6clf/`  
> 이 문서는 Android BLE 연동 및 모델 플래시에 필요한 모든 스펙을 정리한다.

---

## 목차

1. [기기 식별](#1-기기-식별)
2. [BLE GATT 프로파일](#2-ble-gatt-프로파일)
3. [Characteristic 1 — raw EMG 스트림](#3-characteristic-1--raw-emg-스트림)
4. [Characteristic 2 — 추론 결과](#4-characteristic-2--추론-결과)
5. [타이밍](#5-타이밍)
6. [연결 파라미터](#6-연결-파라미터)
7. [채널-핀 매핑](#7-채널-핀-매핑)
8. [연결 끊김 동작](#8-연결-끊김-동작)
9. [모델 플래시 방식](#9-모델-플래시-방식)
10. [펌웨어 내부 추론 파이프라인](#10-펌웨어-내부-추론-파이프라인)
11. [백엔드 → 앱 → 암밴드 전체 흐름](#11-백엔드--앱--암밴드-전체-흐름)
12. [Android 연동 순서](#12-android-연동-순서)

---

## 1. 기기 식별

| 항목 | 값 |
|------|----|
| BLE 광고 이름 | `ESP32S3_FAST_BLE` |
| 제조사 VID (USB 감지용) | `0x303A` (Espressif) |
| 칩 | ESP32-S3 |

---

## 2. BLE GATT 프로파일

| 항목 | 값 |
|------|----|
| Service UUID | `12345678-1234-1234-1234-1234567890ab` |
| Characteristic UUID (raw EMG) | `abcd1234-5678-1234-5678-abcdef123456` |
| Characteristic UUID (추론 결과) | `abcd1234-5678-1234-5678-abcdef123457` |

> UUID 마지막 두 자리만 다름: `...56` = EMG, `...57` = 예측

**Characteristic 속성:**

| Characteristic | 속성 | CCCD |
|---|---|---|
| raw EMG (`...56`) | NOTIFY only | BLE2902 포함, `0x0001` 쓰기로 활성화 |
| 추론 결과 (`...57`) | READ + NOTIFY | BLE2902 포함, `0x0001` 쓰기로 활성화 |

---

## 3. Characteristic 1 — raw EMG 스트림

### 패킷 크기

```
SAMPLES_PER_PACKET = 20
N_CHANNELS         = 8
PACKET_SIZE        = 20 × 8 = 160 bytes
```

### 레이아웃 (row-major)

```
byte[0..7]    → sample 0, CH0~CH7
byte[8..15]   → sample 1, CH0~CH7
...
byte[152..159]→ sample 19, CH0~CH7

인덱스 공식: byte[샘플번호 × 8 + 채널번호]
```

### 값 타입 및 범위

| 항목 | 내용 |
|------|------|
| 타입 | `uint8` (부호 없는 1바이트) |
| 원본 ADC | 10-bit (0~1023) |
| 펌웨어 변환 | `val = ADC_read - 250`, 클리핑 `[0, 255]` |
| 유효 범위 | 0~255 |
| 엔디안 | 해당 없음 (1바이트) |

### Android 파싱 예시 (Kotlin)

```kotlin
fun parseEmgPacket(bytes: ByteArray): Array<IntArray> {
    // returns [20][8] — [sample_index][channel_index]
    val result = Array(20) { IntArray(8) }
    for (s in 0 until 20) {
        for (ch in 0 until 8) {
            result[s][ch] = bytes[s * 8 + ch].toInt() and 0xFF
        }
    }
    return result
}
```

> `and 0xFF` 필수 — Kotlin `Byte`는 signed (-128~127)이므로 uint8로 재해석해야 함.

### 용도

- 실시간 파형 시각화 (신호 탭 8채널 파형)
- 데이터 수집 화면에서 raw 데이터 버퍼링 후 서버 업로드

---

## 4. Characteristic 2 — 추론 결과

### 포맷

```
"classname|l0|l1|l2|l3|l4|l5"
```

| 필드 | 설명 | 예시 |
|------|------|------|
| classname | 최종 예측 제스처명 (3-vote cascade 적용) | `flexion` |
| l0~l5 | 각 클래스 softmax 확률 (소수점 3자리) | `0.020` |

### 실제 패킷 예시

```
"flexion|0.020|0.910|0.030|0.015|0.015|0.010"
```

### 클래스 인덱스 매핑

| 인덱스 | 클래스명 | 동작 |
|--------|----------|------|
| 0 | `rest` | 휴식 |
| 1 | `flexion` | 손목 아래로 구부리기 |
| 2 | `extension` | 손목 위로 젖히기 |
| 3 | `close` | 주먹 쥐기 |
| 4 | `supination` | 손바닥 위로 돌리기 |
| 5 | `pronation` | 손바닥 아래로 돌리기 |

### Android 파싱 예시 (Kotlin)

```kotlin
fun parsePrediction(bytes: ByteArray): Pair<String, FloatArray> {
    val msg = String(bytes, Charsets.UTF_8)
    val parts = msg.split("|")
    val className = parts[0]
    val logits = FloatArray(6) { i -> parts[i + 1].toFloat() }
    return Pair(className, logits)
}
```

### 발행 주기

64샘플마다 추론 1회 → **약 20Hz** (64 / 1281 Hz ≈ 50ms)

### 3-vote cascade

연속 3번의 추론 중 다수결로 최종 예측 결정. 노이즈성 순간 오분류 방지.

```
vote_threshold = 0.34 (= 1/3보다 조금 높음)
threshold 미달 시 직전 예측 유지
```

---

## 5. 타이밍

| 항목 | 값 |
|------|----|
| 샘플링 주기 | 781µs → **약 1281 Hz** |
| raw EMG Notify 주기 | 20샘플 × 781µs = 15.6ms → **약 64 Hz** |
| 추론 Notify 주기 | 64샘플 × 781µs = 50ms → **약 20 Hz** |
| MTU 요청 | 247 bytes (160바이트 패킷 여유 있음) |

---

## 6. 연결 파라미터

| 항목 | 값 |
|------|----|
| Connection interval min | `0x06` → 7.5ms |
| Connection interval max | `0x0C` → 15ms |
| Slave latency | 0 |

Android에서 `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` 호출 권장.

---

## 7. 채널-핀 매핑

| 채널 | ADC 핀 |
|------|--------|
| CH0 | GPIO 9 |
| CH1 | GPIO 10 |
| CH2 | GPIO 7 |
| CH3 | GPIO 8 |
| CH4 | GPIO 11 |
| CH5 | GPIO 12 |
| CH6 | GPIO 17 |
| CH7 | GPIO 18 |

패킷 내 CH0~CH7 순서는 위 핀 순서와 동일.

---

## 8. 연결 끊김 동작

- 펌웨어가 연결 끊김 시 `BLEDevice::startAdvertising()` 자동 재호출
- Android 앱은 재연결 시도 없이 BLE 스캔 화면(S04)으로 이동 (기능명세 F01 결정사항)

---

## 9. 모델 플래시 방식

### 개요

TFLite를 사용하지 않는다. 학습된 모델 가중치를 **C 헤더 파일로 변환**하여 펌웨어 소스코드에 포함시킨 뒤 ESP32에 플래시한다.

### 생성되는 헤더 파일 3종

| 파일 | 내용 | 생성 시점 |
|------|------|-----------|
| `MODEL.h` | 가중치/편향 float 배열, 토폴로지, 활성화 함수 코드 | 백엔드 학습 완료 후 |
| `means.h` | StandardScaler mean 132개 | 백엔드 학습 완료 후 |
| `stds.h` | StandardScaler std 132개 | 백엔드 학습 완료 후 |

### MODEL.h 구조

```c
// Auto-generated. Do not edit by hand.
// Topology: [132, 64, 64, 6]
// Activations: ['RELU', 'RELU', 'SOFTMAX']

const int MODEL_N_LAYERS = 4;
const int MODEL_TOPOLOGY[4] = { 132, 64, 64, 6 };
const int MODEL_ACTIVATIONS[3] = { ACT_RELU, ACT_RELU, ACT_SOFTMAX };
const float MODEL_WEIGHTS[...] = { ... };  // 132*64 + 64*64 + 64*6 = 13056개
const float MODEL_BIASES[...]  = { ... };  // 64 + 64 + 6 = 134개
```

### means.h / stds.h 구조

```c
// Auto-generated. Do not edit by hand.
const float STANDARDIZER_MEANS[132] = { 10.608f, 7.103f, ... };
const float STANDARDIZER_STDS[132]  = { 8.234f,  5.102f, ... };
```

### 플래시 흐름

```
[백엔드] 학습 완료
    ↓ HTTP
[Android] MODEL.h, means.h, stds.h 다운로드
    ↓ USB OTG
[암밴드] 헤더 파일 포함 펌웨어 바이너리 플래시
    ↓
[암밴드] 재부팅 후 새 모델로 추론 시작
```

> **주의:** 현재 백엔드는 `model.tflite`를 생성하고 있어 `MODEL.h` 생성 기능이 없다.  
> 학습 완료 후 `MODEL.h` / `means.h` / `stds.h`를 생성하는 export 기능을 백엔드에 추가해야 한다.

---

## 10. 펌웨어 내부 추론 파이프라인

암밴드가 BLE로 추론 결과를 쏘기 위해 내부적으로 수행하는 과정.

### 상수

```c
WINDOW_SIZE     = 128       // 추론 윈도우 샘플 수
INFER_HOP       = 64        // 추론 간격 (50% overlap)
N_CHANNEL       = 8
N_FEATURES      = 132       // 피처 벡터 차원
SAMPLING_FREQ   = 1200 Hz
ENVELOPE_KERNEL = 12        // moving average 커널 크기
```

### 처리 순서

```
getEMG() → circular buffer (128×8) 누적
    64샘플마다 runInference() 호출
        ↓
1. applyBandpass()
   - Butterworth 4차 BP 필터 (35~300 Hz)
   - Direct Form II Transposed (causal, Python lfilter와 동일)
   - 필터 계수: preprocessor.cpp의 bp_b[], bp_a[]
   - 초기 상태: lfilter_zi(b, a) * x[0]

2. applyRectify()
   - abs() 취하기

3. applyEnvelope()
   - 이동평균 (kernel=12), circular buffer 방식
   - 윈도우 시작마다 상태 리셋 (Python np.convolve와 동일)

4. extractClassicFeatures()  → 88차원
   [시간영역, 피처 기준 묶음, 인덱스 0..47]
     MAV   × 8ch (idx 0..7)
     MaxAV × 8ch (idx 8..15)
     STD   × 8ch (idx 16..23)
     RMS   × 8ch (idx 24..31)
     WL    × 8ch (idx 32..39)
     SSC   × 8ch (idx 40..47)
   [주파수영역, 채널 기준 묶음, 인덱스 48..87]
     ch0: MeanPow, TotalPow, MeanFreq, MedianFreq, PeakFreq (idx 48..52)
     ch1: 위와 동일 (idx 53..57)
     ...
     ch7: 위와 동일 (idx 83..87)

5. extractTSDFeatures()  → 44차원
   [공분산 상삼각, 인덱스 88..123]
     (0,0),(0,1),(0,2),...,(0,7),
     (1,1),(1,2),...,(1,7),
     ...
     (7,7) → 총 36개
   [채널별 에너지, 인덱스 124..131]
     ch0~ch7 → 8개
   TSD 서브윈도우: win=96샘플, inc=48샘플, lam=0.1
   np.cov 기준 ddof=1 (n-1로 나눔)

6. standardize()
   - (x / std) - (mean / std) 형태로 계산 (수치 안정성)
   - means.h의 STANDARDIZER_MEANS[132]
   - stds.h의 STANDARDIZER_STDS[132]

7. nn.predict()
   - Dense(132→64, ReLU) → Dense(64→64, ReLU) → Dense(64→6, Softmax)
   - 가중치: MODEL.h의 MODEL_WEIGHTS[], MODEL_BIASES[]

8. 3-vote cascade → BLE Notify (Characteristic ...57)
```

---

## 11. 백엔드 → 앱 → 암밴드 전체 흐름

```
──────────────── 최초 사용 (학습 + 플래시) ────────────────

[Android 앱]
  유저 생성 → POST /users
  BLE 연결 → raw EMG 수집 (Characteristic ...56)
  랩 완료 → POST /sessions/{user_id}/data (raw EMG binary 업로드)
  5랩 이상 → POST /sessions/{user_id}/train
  폴링    → GET  /sessions/{user_id}/status (2~3초 간격)
  학습 완료→ GET  /sessions/{user_id}/model?file=MODEL.h
           → GET  /sessions/{user_id}/model?file=means.h
           → GET  /sessions/{user_id}/model?file=stds.h
  USB OTG  → 암밴드에 헤더 포함 펌웨어 바이너리 플래시

──────────────── 일반 사용 (추론) ────────────────

[암밴드]
  BLE Notify → Characteristic ...56: raw EMG (160바이트, 64Hz)
  BLE Notify → Characteristic ...57: 추론 결과 (텍스트, 20Hz)

[Android 앱]
  ...56 수신 → 파형 시각화
  ...57 수신 → 제스처 분류 결과 표시
```

---

## 12. Android 연동 순서

### BLE 연결 절차

```
1. BLE 스캔 → "ESP32S3_FAST_BLE" 기기 찾기
2. connectGatt()
3. onConnectionStateChange() → requestMtu(247)
4. onMtuChanged() → discoverServices()
5. onServicesDiscovered():
   - getService("12345678-1234-1234-1234-1234567890ab")
   - getCharacteristic("...123456") → setCharacteristicNotification(true)
                                    → CCCD에 0x0001 쓰기
   - getCharacteristic("...123457") → setCharacteristicNotification(true)
                                    → CCCD에 0x0001 쓰기
6. requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
7. onCharacteristicChanged() 콜백으로 데이터 수신 시작
```

### 데이터 수집 절차 (학습용)

```
1. Characteristic ...56 수신 → 160바이트 파싱 → 샘플 버퍼에 누적
2. 동작별 레이블(0~5) 타임스탬프 기준으로 병렬 기록
3. 1랩(6동작 × 10초) 완료 시 → POST /sessions/{user_id}/data
   body: multipart
     - emg_data  : uint8 binary, shape (N, 8), row-major
     - labels    : int32 binary, shape (N,)
     - lap_count : int (이번 업로드의 랩 수)
     - gesture_set: "6cl"
4. 5랩 이상 누적 시 학습 가능
```

### 모델 플래시 절차 (USB OTG)

```
1. GET /sessions/{user_id}/model?file=MODEL.h   → 저장
2. GET /sessions/{user_id}/model?file=means.h   → 저장
3. GET /sessions/{user_id}/model?file=stds.h    → 저장
4. 다운받은 헤더 파일을 펌웨어 소스에 포함하여 빌드
5. USB OTG로 ESP32에 플래시
6. 암밴드 재부팅 → 새 모델로 추론 시작
```

> **⚠️ 현재 백엔드 미구현 사항:**  
> 백엔드가 현재 `model.tflite`와 `scaler.json`을 생성하고 있으나,  
> 실제 필요한 파일은 `MODEL.h` / `means.h` / `stds.h`다.  
> 백엔드의 `services/training.py`에 export 기능 추가가 필요하다.

---

## 부록 — 피처 벡터 132개 상세 인덱스

| 인덱스 | 피처 | 채널 |
|--------|------|------|
| 0~7 | MAV | ch0~ch7 |
| 8~15 | MaxAV | ch0~ch7 |
| 16~23 | STD | ch0~ch7 |
| 24~31 | RMS | ch0~ch7 |
| 32~39 | WL | ch0~ch7 |
| 40~47 | SSC | ch0~ch7 |
| 48~52 | MeanPow, TotalPow, MeanFreq, MedianFreq, PeakFreq | ch0 |
| 53~57 | 위와 동일 | ch1 |
| 58~62 | 위와 동일 | ch2 |
| 63~67 | 위와 동일 | ch3 |
| 68~72 | 위와 동일 | ch4 |
| 73~77 | 위와 동일 | ch5 |
| 78~82 | 위와 동일 | ch6 |
| 83~87 | 위와 동일 | ch7 |
| 88~123 | 공분산 상삼각 (i,j), i≤j | ch0~ch7 |
| 124~131 | 채널별 에너지 | ch0~ch7 |
