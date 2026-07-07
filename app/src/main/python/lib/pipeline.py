"""
pipeline.py
===========
mandro-backend/app/ml/lib/pipeline.py 에서 필요한 부분만 복사해오면 됨.
서버 원본은 LDA 관련 함수도 있는데(fit_lda 등) 로컬 파이프라인엔 필요 없음 —
extract_features_pipeline / fit_scaler / apply_scaler 만 이식.
"""

import numpy as np


def extract_features_pipeline(
    X: np.ndarray,
    sampling_frequency_EMG: int = 1200,
    mode: str = "concat",
    tsd_win_ms: int = 80,
    tsd_inc_ms: int = 40,
    tsd_lam: float = 0.1,
) -> np.ndarray:
    raise NotImplementedError  # mandro-backend에서 복사 (classic+tsd concat)


def fit_scaler(X_train: np.ndarray):
    raise NotImplementedError  # sklearn.preprocessing.StandardScaler


def apply_scaler(scaler, X: np.ndarray) -> np.ndarray:
    raise NotImplementedError
