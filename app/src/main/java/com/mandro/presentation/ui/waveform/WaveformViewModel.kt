package com.mandro.presentation.ui.waveform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.sin
import kotlin.random.Random

// 화면에 표시할 샘플 수 (버퍼 크기)
// TODO: 실제 BLE 샘플링 주파수(1200Hz)와 UI 갱신 주기(60fps)에 맞게 조정 필요
const val DISPLAY_SAMPLES = 200

data class WaveformUiState(
    val bleState: BleState = BleState.Disconnected,
    val visibleChannels: BooleanArray = BooleanArray(EMG_CHANNELS) { true },
    // TODO: 채널 수 4/6/8 전환 지원 시 activeChannels: Int = EMG_CHANNELS 추가
)

@HiltViewModel
class WaveformViewModel @Inject constructor(
    // TODO: BleRepository 주입 후 실제 emgStream 구독으로 교체
    // private val bleRepository: BleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WaveformUiState())
    val uiState = _uiState.asStateFlow()

    // 채널별 링버퍼 — 리컴포지션 없이 Canvas가 직접 읽음
    val buffers: Array<FloatArray> = Array(EMG_CHANNELS) { FloatArray(DISPLAY_SAMPLES) }
    private val writeIndex = IntArray(EMG_CHANNELS) { 0 }

    init {
        startFakeStream()
    }

    // TODO: 실제 BLE 연결 후 아래 함수를 BleRepository.emgStream 구독으로 교체
    // fun startRealStream() {
    //     viewModelScope.launch {
    //         bleRepository.emgStream.collect { sample ->
    //             if (!_uiState.value.isPaused) pushSample(sample.channels)
    //         }
    //     }
    // }

    private fun startFakeStream() {
        viewModelScope.launch {
            var t = 0.0
            while (true) {
                // 채널마다 주파수/위상이 다른 사인파 + 노이즈
                val sample = FloatArray(EMG_CHANNELS) { ch ->
                    val freq = 0.05 + ch * 0.015
                    val noise = (Random.nextFloat() - 0.5f) * 2000f
                    (sin(t * freq + ch) * 10000f + noise).toFloat()
                }
                pushSample(sample)
                t += 1.0
                delay(8L) // ~120fps 입력 시뮬레이션
            }
        }
    }

    fun pushSample(channels: FloatArray) {
        for (ch in 0 until EMG_CHANNELS) {
            val idx = writeIndex[ch]
            buffers[ch][idx] = channels[ch]
            writeIndex[ch] = (idx + 1) % DISPLAY_SAMPLES
        }
    }

    fun toggleChannel(ch: Int) {
        val current = _uiState.value.visibleChannels.copyOf()
        current[ch] = !current[ch]
        _uiState.value = _uiState.value.copy(visibleChannels = current)
    }
}
