package com.mandro.core.util

import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.SAMPLING_FREQ
import com.mandro.domain.model.WINDOW_SIZE
import kotlin.math.*

/**
 * EMG 피처 추출기
 *
 * Python lib/features/features.py 포팅
 * Classic features (88차원) + TSD features (44차원) = 132차원
 *
 * 수치 검증 필수: Python extract_features_pipeline()과 동일한 입력에 동일한 출력이 나와야 함
 */
object EmgFeatureExtractor {

    // TSD 파라미터 (configs.py와 동일)
    private const val TSD_WIN_MS = 80
    private const val TSD_INC_MS = 40
    private const val TSD_LAMBDA = 0.1f

    /**
     * 메인 피처 추출 함수
     * @param window [WINDOW_SIZE][EMG_CHANNELS] 형태의 윈도우
     * @return 132차원 피처 벡터
     */
    fun extract(window: Array<FloatArray>): FloatArray {
        val classic = extractClassic(window)    // 88차원
        val tsd = extractTsd(window)            // 44차원
        return classic + tsd
    }

    // ── Classic Features (88차원 = 11 × 8채널) ────────────────

    private fun extractClassic(window: Array<FloatArray>): FloatArray {
        val mav   = computeMav(window)
        val maxav = computeMaxAv(window)
        val std   = computeStd(window)
        val rms   = computeRms(window)
        val wl    = computeWl(window)
        val ssc   = computeSsc(window)
        val freq  = computeFreqFeatures(window)

        return mav + maxav + std + rms + wl + ssc + freq
    }

    // MAV: Mean Absolute Value
    private fun computeMav(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            w.map { abs(it[ch]) }.average().toFloat()
        }

    // MaxAV: Maximum Absolute Value
    private fun computeMaxAv(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            w.maxOf { abs(it[ch]) }
        }

    // STD: Standard Deviation
    private fun computeStd(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            val values = w.map { it[ch] }
            val mean = values.average()
            sqrt(values.map { (it - mean).pow(2) }.average()).toFloat()
        }

    // RMS: Root Mean Square
    private fun computeRms(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            sqrt(w.map { it[ch].pow(2) }.average()).toFloat()
        }

    // WL: Waveform Length
    private fun computeWl(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            (1 until w.size).sumOf { abs(w[it][ch] - w[it - 1][ch]).toDouble() }.toFloat()
        }

    // SSC: Slope Sign Change
    private fun computeSsc(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            val diff = (1 until w.size).map { w[it][ch] - w[it - 1][ch] }
            (1 until diff.size).count { sign(diff[it]) != sign(diff[it - 1]) }.toFloat()
        }

    // 주파수 도메인 피처 (5종 × 8채널 = 40차원)
    private fun computeFreqFeatures(w: Array<FloatArray>): FloatArray {
        val result = FloatArray(5 * EMG_CHANNELS)
        val n = w.size
        val freqs = FloatArray(n / 2 + 1) { it * SAMPLING_FREQ.toFloat() / n }

        for (ch in 0 until EMG_CHANNELS) {
            val signal = FloatArray(n) { w[it][ch] }
            val power = fftPower(signal)

            val totalPower = power.sum() + 1e-12f
            val meanPower = power.average().toFloat()
            val meanFreq = (freqs.indices).sumOf { (freqs[it] * power[it]).toDouble() }.toFloat() / totalPower

            // Median frequency
            val cumsum = FloatArray(power.size)
            cumsum[0] = power[0]
            for (i in 1 until power.size) cumsum[i] = cumsum[i - 1] + power[i]
            val medIdx = cumsum.indexOfFirst { it >= totalPower / 2 }.coerceIn(0, freqs.size - 1)
            val medFreq = freqs[medIdx]

            val peakFreq = freqs[power.indices.maxByOrNull { power[it] } ?: 0]

            val base = ch * 5
            result[base + 0] = meanPower
            result[base + 1] = totalPower
            result[base + 2] = meanFreq
            result[base + 3] = medFreq
            result[base + 4] = peakFreq
        }
        return result
    }

    // ── TSD Features (44차원) ─────────────────────────────────

    private fun extractTsd(window: Array<FloatArray>): FloatArray {
        val winSamples = SAMPLING_FREQ * TSD_WIN_MS / 1000
        val incSamples = SAMPLING_FREQ * TSD_INC_MS / 1000
        val n = window.size

        val subFeatures = mutableListOf<FloatArray>()
        var start = 0
        while (start + winSamples <= n) {
            val sub = window.slice(start until start + winSamples).toTypedArray()
            subFeatures.add(computeTsd(sub))
            start += incSamples
        }

        return if (subFeatures.isEmpty()) {
            computeTsd(window)
        } else {
            // sub-window들의 평균
            FloatArray(subFeatures[0].size) { i ->
                subFeatures.map { it[i] }.average().toFloat()
            }
        }
    }

    // TSD: 공분산 상삼각 (36차원) + 채널별 에너지 (8차원) = 44차원
    private fun computeTsd(w: Array<FloatArray>): FloatArray {
        val cov = computeCovariance(w)          // [8][8]
        val energy = computeEnergy(w)           // [8]

        // 상삼각 요소 추출 (36개)
        val covFeats = mutableListOf<Float>()
        for (i in 0 until EMG_CHANNELS) {
            for (j in i until EMG_CHANNELS) {
                covFeats.add(cov[i][j])
            }
        }
        return (covFeats + energy.toList()).toFloatArray()
    }

    // 공분산 행렬 + regularization (λI)
    private fun computeCovariance(w: Array<FloatArray>): Array<FloatArray> {
        val n = w.size
        val means = FloatArray(EMG_CHANNELS) { ch -> w.map { it[ch] }.average().toFloat() }
        val cov = Array(EMG_CHANNELS) { FloatArray(EMG_CHANNELS) }

        for (i in 0 until EMG_CHANNELS) {
            for (j in 0 until EMG_CHANNELS) {
                cov[i][j] = w.sumOf {
                    ((it[i] - means[i]) * (it[j] - means[j])).toDouble()
                }.toFloat() / (n - 1)
                if (i == j) cov[i][j] += TSD_LAMBDA     // 정규화
            }
        }
        return cov
    }

    // 채널별 에너지 (mean squared)
    private fun computeEnergy(w: Array<FloatArray>): FloatArray =
        FloatArray(EMG_CHANNELS) { ch ->
            w.map { it[ch].pow(2) }.average().toFloat()
        }

    // ── FFT (단순 DFT 구현, 추후 FFTW 또는 KotlinDSP로 교체 가능) ─

    private fun fftPower(signal: FloatArray): FloatArray {
        val n = signal.size
        val halfN = n / 2 + 1
        val power = FloatArray(halfN)

        for (k in 0 until halfN) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = 2 * PI * k * t / n
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            power[k] = (re * re + im * im).toFloat()
        }
        return power
    }

    // ── 유틸 ─────────────────────────────────────────────────

    private operator fun FloatArray.plus(other: FloatArray): FloatArray =
        FloatArray(this.size + other.size).also {
            this.copyInto(it, 0)
            other.copyInto(it, this.size)
        }

    private fun Float.pow(n: Int): Float = this.toDouble().pow(n).toFloat()
    private fun sign(x: Float): Int = when {
        x > 0f  ->  1
        x < 0f  -> -1
        else    ->  0
    }
    private fun Iterable<Float>.average(): Double = this.map { it.toDouble() }.average()
}