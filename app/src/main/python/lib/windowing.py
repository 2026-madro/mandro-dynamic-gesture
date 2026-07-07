"""
windowing.py
============
mandro-backend/app/ml/lib/windowing.py 에서 필요한 부분만 복사해오면 됨.

필요한 함수:
    get_windows(df, window_size)
    get_action_windows(window, action_col='Action')
    split_train_val(emg, labels, val_size, random_state)
"""

import numpy as np


def get_windows(df, window_size: int):
    raise NotImplementedError  # mandro-backend에서 복사


def get_action_windows(window, action_col: str = "Action") -> int:
    raise NotImplementedError  # mandro-backend에서 복사


def split_train_val(emg: np.ndarray, labels: np.ndarray, val_size: float, random_state: int):
    raise NotImplementedError  # mandro-backend에서 복사
