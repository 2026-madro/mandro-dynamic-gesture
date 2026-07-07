package com.mandro.presentation.ui.classify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.core.calibration.RestCalibration
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.InferenceResult
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClassifyUiState(
    val gesture: String = "—",
    val gestureKo: String = "암밴드 연결을 기다리는 중...",
    val bleState: BleState = BleState.Disconnected,
    val probabilities: Map<String, Float> = emptyMap(),
    val error: String? = null,
)

@HiltViewModel
class ClassifyViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val restCalibration: RestCalibration,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassifyUiState())
    val uiState = _uiState.asStateFlow()

    // 레이더 차트용 채널별 신호 세기 (0f~1f), Canvas가 직접 읽음
    val channelValues = FloatArray(EMG_CHANNELS)

    init {
        bleRepository.setEmgEnabled(true)
        observeBleState()
        observeInference()
        observeEmg()
    }

    override fun onCleared() {
        super.onCleared()
        bleRepository.setEmgEnabled(false)
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                _uiState.update { it.copy(bleState = state) }
                if (state is BleState.Connected) {
                    _uiState.update { it.copy(gestureKo = "동작을 인식할 준비가 됐어요") }
                }
            }
        }
    }

    /** 암밴드 BLE Characteristic ...57 에서 추론 결과 수신 */
    private fun observeInference() {
        viewModelScope.launch {
            bleRepository.inferenceStream.collect { result ->
                _uiState.update { it.copy(
                    gesture    = result.className,
                    gestureKo  = gestureNameKo(result.className),
                    probabilities = InferenceResult.GESTURE_NAMES
                        .zip(result.probabilities.toList())
                        .toMap(),
                ) }
            }
        }
    }

    /** raw EMG stream → 레이더 차트 채널 값 업데이트 */
    private fun observeEmg() {
        viewModelScope.launch {
            bleRepository.emgStream.collect { sample ->
                for (ch in 0 until EMG_CHANNELS) {
                    val raw = sample.channels[ch]
                    val normalized = if (restCalibration.isCalibrated) {
                        val baseline = restCalibration.baseline[ch]
                        ((raw - baseline) / (255f - baseline).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    } else {
                        (raw / 255f).coerceIn(0f, 1f)
                    }
                    channelValues[ch] += (normalized - channelValues[ch]) * 0.15f
                }
            }
        }
    }
}

private fun gestureNameKo(name: String) = when (name.lowercase()) {
    "rest"       -> "손을 자연스럽게 펴서 쉬기"
    "flexion"    -> "손목을 아래로 구부리기"
    "extension"  -> "손목을 위로 젖히기"
    "close"      -> "주먹 꽉 쥐기"
    "supination" -> "손바닥이 위를 향하게 돌리기"
    "pronation"  -> "손바닥이 아래를 향하게 돌리기"
    else         -> name
}
