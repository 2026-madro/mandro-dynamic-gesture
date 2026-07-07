"""
training_local.py
==================
Chaquopy를 통해 Kotlin에서 호출되는 로컬 학습 엔트리 포인트.

포팅 대상 (mandro-backend/app/ml/lib/):
    preprocessing.py        → BP_filter, compute_enveloppe
    features/features.py    → extract_features, extract_features_tsd
    pipeline.py              → extract_features_pipeline, fit_scaler
    windowing.py             → get_windows, get_action_windows, split_train_val

서버(TensorFlow) → 로컬(sklearn) 교체 지점: models.py의 Dense(64,64,6) 구조를
MLPClassifier(hidden_layer_sizes=(64, 64), ...)로 교체. (mandro-backend와의
TF vs sklearn 비교 벤치마크 결과: 정확도 -1~3.4%p, 학습 시간 대폭 단축)

가중치 직렬화 순서는 펌웨어(nn.cpp loadFromSPIFFS)와 반드시 일치해야 함:
    W0(132x64) b0(64) W1(64x64) b1(64) W2(64x6) b2(6) = 52,248 bytes
"""

import numpy as np

# ── 상수 (mandro-backend/app/config.py 와 반드시 일치) ────────────────────────
SAMPLING_FREQ = 1200
WINDOW_SIZE = 128
N_CHANNELS = 8
LOWCUT = 35.0
HIGHCUT = 300.0
FILTER_ORDER = 4
KERNEL_RATIO = 0.10
DELAY_SAMPLES = int(SAMPLING_FREQ * 0.5)
FEATURE_MODE = "concat"
TSD_WIN_MS = 80
TSD_INC_MS = 40
TSD_LAM = 0.1
VAL_RATIO = 0.20
RANDOM_STATE = 42
N_CLASSES = 6  # rest, flexion, extension, close, supination, pronation

WEIGHT_MAGIC = 0xDEADBEEF
WEIGHT_TOTAL_BYTES = 52_248


def run_training_local(emg_bytes: bytes, labels_bytes: bytes) -> bytes:
    """
    Kotlin에서 호출하는 최상위 진입점.

    Parameters
    ----------
    emg_bytes : bytes
        raw EMG, uint8 flat array (N * N_CHANNELS,) row-major
    labels_bytes : bytes
        int32 flat array (N,)

    Returns
    -------
    bytes
        직렬화된 가중치 바이너리 (52,248 bytes, 매직넘버/CRC 미포함).
        BLE/USB 전송 프로토콜에 얹기 전 단계의 payload.
    """
    raw_emg = np.frombuffer(emg_bytes, dtype=np.uint8).reshape(-1, N_CHANNELS)
    labels = np.frombuffer(labels_bytes, dtype=np.int32)

    X, y = _preprocess_and_extract(raw_emg, labels)
    model, scaler = _fit_model(X, y)

    return serialize_weights(model)


def _preprocess_and_extract(raw_emg: np.ndarray, labels: np.ndarray):
    """
    TODO: mandro-backend/app/services/training.py의 _preprocess_and_extract() 이식.
    BP_filter → abs → envelope → windowing → extract_features_pipeline(mode="concat")
    반환: (X: (W, 132) float32, y: (W,) int32)
    """
    raise NotImplementedError


def _fit_model(X: np.ndarray, y: np.ndarray):
    """
    TODO: StandardScaler.fit_transform → split_train_val(random_state=42)
    → MLPClassifier(hidden_layer_sizes=(64, 64), activation="relu", solver="adam",
                     max_iter=200, early_stopping=True, n_iter_no_change=10,
                     validation_fraction=0.15, random_state=RANDOM_STATE).fit(...)
    반환: (model, scaler)
    """
    raise NotImplementedError


def serialize_weights(model) -> bytes:
    """
    sklearn MLPClassifier → float32 바이너리.
    레이어 순서: W0 b0 W1 b1 W2 b2 (model.coefs_ / model.intercepts_ 순서와 동일)

    TODO: LOCAL_MIGRATION.md의 serialize_weights() 초안 참고.
    마지막에 len(result) == WEIGHT_TOTAL_BYTES 검증할 것.
    """
    raise NotImplementedError
