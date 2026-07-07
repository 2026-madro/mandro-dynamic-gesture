"""
config.py
=========
mandro-backend/app/config.py 와 반드시 값이 일치해야 함 (펌웨어/서버/앱 3곳 동기화).
"""

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

GESTURES = ["rest", "flexion", "extension", "close", "supination", "pronation"]
N_CLASSES = len(GESTURES)

VAL_RATIO = 0.20
RANDOM_STATE = 42

WEIGHT_MAGIC = 0xDEADBEEF
WEIGHT_TOTAL_BYTES = 52_248
