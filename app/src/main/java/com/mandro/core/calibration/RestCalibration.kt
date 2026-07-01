package com.mandro.core.calibration

import com.mandro.domain.model.EMG_CHANNELS
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestCalibration @Inject constructor() {

    @Volatile var baseline: FloatArray = FloatArray(EMG_CHANNELS)
        private set

    @Volatile var isCalibrated: Boolean = false
        private set

    fun setBaseline(samples: List<FloatArray>) {
        if (samples.isEmpty()) return
        val mean = FloatArray(EMG_CHANNELS) { ch ->
            samples.map { it[ch] }.average().toFloat()
        }
        baseline = mean
        isCalibrated = true
    }

    // 기준선에서 MARGIN만큼 덜 빼서 쉬는 상태에도 약간의 신호가 남도록 함
    // 너무 빡빡하게 빼면 rest≈0이 되어 "신호 없음" 판정 발생
    private val MARGIN = 20f

    fun apply(channels: FloatArray): FloatArray {
        if (!isCalibrated) return channels
        return FloatArray(EMG_CHANNELS) { ch ->
            (channels[ch] - (baseline[ch] - MARGIN).coerceAtLeast(0f)).coerceAtLeast(0f)
        }
    }

    fun reset() {
        baseline = FloatArray(EMG_CHANNELS)
        isCalibrated = false
    }
}
