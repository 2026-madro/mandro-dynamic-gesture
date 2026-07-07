"""
serialize.py
============
학습된 sklearn MLPClassifier → 펌웨어 전송용 float32 바이너리.

레이어 순서: W0 b0 W1 b1 W2 b2  (model.coefs_ / model.intercepts_ 순서와 동일)
    W0: (132, 64) float32 → 33,792 bytes
    b0: (64,)     float32 →    256 bytes
    W1: (64, 64)  float32 → 16,384 bytes
    b1: (64,)     float32 →    256 bytes
    W2: (64, 6)   float32 →  1,536 bytes
    b2: (6,)      float32 →     24 bytes
    합계:                   52,248 bytes  (lib.config.WEIGHT_TOTAL_BYTES)

펌웨어 nn.cpp::loadFromSPIFFS()가 이 순서 그대로 읽으므로 순서를 바꾸면 안 됨.
"""

import numpy as np

from lib.config import WEIGHT_TOTAL_BYTES


def serialize_weights(model) -> bytes:
    buffers = []
    for W, b in zip(model.coefs_, model.intercepts_):
        buffers.append(W.astype(np.float32).tobytes())
        buffers.append(b.astype(np.float32).tobytes())
    result = b"".join(buffers)
    assert len(result) == WEIGHT_TOTAL_BYTES
    return result
