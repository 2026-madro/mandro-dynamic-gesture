package com.mandro.presentation.ui.waveform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

const val DISPLAY_SAMPLES = 200

data class WaveformUiState(
    val bleState: BleState = BleState.Disconnected,
    val visibleChannels: BooleanArray = BooleanArray(EMG_CHANNELS) { true },
)

@HiltViewModel
class WaveformViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WaveformUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToBleScan = Channel<Unit>(Channel.BUFFERED)
    val navigateToBleScan = _navigateToBleScan.receiveAsFlow()

    // 채널별 링버퍼 — 리컴포지션 없이 Canvas가 직접 읽음
    val buffers: Array<FloatArray> = Array(EMG_CHANNELS) { FloatArray(DISPLAY_SAMPLES) }
    private val writeIndex = IntArray(EMG_CHANNELS) { 0 }

    init {
        observeBleState()
        collectEmgStream()
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                _uiState.value = _uiState.value.copy(bleState = state)
                if (state is BleState.Disconnected) {
                    _navigateToBleScan.send(Unit)
                }
            }
        }
    }

    private fun collectEmgStream() {
        viewModelScope.launch {
            bleRepository.emgStream.collect { sample ->
                pushSample(sample.channels)
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
