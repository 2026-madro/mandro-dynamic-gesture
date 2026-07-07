"""
features.py
===========
mandro-backend/app/ml/lib/features/features.py 에서 그대로 복사해오면 됨
(TensorFlow 의존성 없음).

필요한 함수:
    extract_features(X, sampling_frequency_EMG)      -> classic 88차원
    extract_features_tsd(X, sampling_frequency_EMG, win_ms, inc_ms, lam) -> TSD 44차원
"""

import numpy as np


def extract_features(X: np.ndarray, sampling_frequency_EMG: int) -> np.ndarray:
    raise NotImplementedError  # mandro-backend에서 복사


def extract_features_tsd(
    X: np.ndarray,
    sampling_frequency_EMG: int,
    win_ms: int,
    inc_ms: int,
    lam: float,
) -> np.ndarray:
    raise NotImplementedError  # mandro-backend에서 복사
