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
    // 이 화면에서 한 번이라도 Connected를 본 적 있는지 — 화면 진입 직후의
    // "아직 연결 확인 전" 상태와 "연결됐다가 끊긴" 상태를 구분하기 위함.
    // 가중치 전송 성공 후 암밴드가 재부팅하면서 연결이 끊기는 경우를 감지해서
    // BleScan 화면으로 돌려보내는 데 씀 (ClassifyScreen의 onDisconnected).
    val hasConnectedOnce: Boolean = false,
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

    // 레이더 차트용 — "세기"(느리게 스무딩된 값, 선 길이를 결정)와
    // "떨림"(세기 대비 순간 편차 히스토리, 선의 흔들림/파형을 결정)을 분리.
    // 하나의 값으로 합쳐서 쓰면 노이즈 때문에 길이가 계속 요동쳐서 세기를
    // 읽기 어려웠음 — Canvas가 둘 다 직접 읽음.
    val channelIntensity = FloatArray(EMG_CHANNELS)
    val channelJitter = Array(EMG_CHANNELS) { FloatArray(JITTER_HISTORY_SIZE) }
    private var jitterWriteIdx = 0

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
                _uiState.update {
                    it.copy(
                        bleState = state,
                        hasConnectedOnce = it.hasConnectedOnce || state is BleState.Connected,
                        gestureKo = when {
                            state is BleState.Connected -> "동작을 인식할 준비가 됐어요"
                            state is BleState.Disconnected && it.hasConnectedOnce ->
                                "암밴드 연결이 끊겼어요. 다시 연결해 주세요."
                            else -> it.gestureKo
                        },
                    )
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

    /** raw EMG stream → 레이더 차트 채널 값(세기+떨림) 업데이트 */
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
                    // 세기: 느리게 스무딩 — 이게 선 길이. 노이즈에 덜 흔들리게
                    // 기존(0.15)보다 훨씬 느린 비율을 씀.
                    channelIntensity[ch] += (normalized - channelIntensity[ch]) * INTENSITY_SMOOTHING
                    // 떨림: 세기(안정된 평균)에서 순간값이 얼마나 벗어났는지 —
                    // 이 편차를 링버퍼에 기록해서 선을 파형처럼 흔들리게 그리는 데 씀.
                    channelJitter[ch][jitterWriteIdx % JITTER_HISTORY_SIZE] =
                        (normalized - channelIntensity[ch]).coerceIn(-JITTER_CLAMP, JITTER_CLAMP)
                }
                jitterWriteIdx++
            }
        }
    }

    companion object {
        private const val INTENSITY_SMOOTHING = 0.03f
        private const val JITTER_HISTORY_SIZE = 6
        private const val JITTER_CLAMP = 0.3f
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
