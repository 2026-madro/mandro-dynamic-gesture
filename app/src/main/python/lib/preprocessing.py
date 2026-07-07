"""
preprocessing.py
================
mandro-backend/app/ml/lib/preprocessing.py 에서 그대로 복사해오면 됨
(TensorFlow 의존성 없음 — numpy/scipy/pandas만 사용).

필요한 함수:
    BP_filter(df, lowcut, highcut, sampling_frequency_EMG, order, labels)
    compute_enveloppe(values, size)

이 파일에 추가로 구현할 것:
    preprocess_and_extract(raw_emg, labels) -> (X: np.ndarray, y: np.ndarray)
        mandro-backend/app/services/training.py 의 _preprocess_and_extract()를
        참고해서 이식. lib.pipeline.extract_features_pipeline, lib.windowing 사용.
"""

import numpy as np


def BP_filter(df, lowcut, highcut, sampling_frequency_EMG, order, labels):
    raise NotImplementedError  # mandro-backend에서 복사


def compute_enveloppe(values: np.ndarray, size: int) -> np.ndarray:
    raise NotImplementedError  # mandro-backend에서 복사


def preprocess_and_extract(raw_emg: np.ndarray, labels: np.ndarray):
    """
    raw EMG (uint8, N x 8) + labels (int32, N) → 피처 배열 (W, 132) + 레이블 (W,)
    mandro-backend/app/services/training.py::_preprocess_and_extract 참고.
    """
    raise NotImplementedError
