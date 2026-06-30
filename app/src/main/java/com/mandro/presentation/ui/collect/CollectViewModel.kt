package com.mandro.presentation.ui.collect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.model.GestureSet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val TOTAL_LAPS = 10
const val MIN_LAPS_TO_TRAIN = 5
private const val RECORD_DURATION_MS = 10000L
private const val COUNTDOWN_STEP_MS = 1000L

sealed class CollectPhase {
    data class Countdown(val count: Int) : CollectPhase()
    object Recording : CollectPhase()
}

const val RECORD_SECONDS = (RECORD_DURATION_MS / 1000).toInt()

data class CollectUiState(
    val gestures: List<String> = GestureSet.SIX_CLASS.classes,
    val currentLap: Int = 1,
    val currentGestureIndex: Int = 0,
    val phase: CollectPhase = CollectPhase.Countdown(3),
    val recordingSecondsLeft: Int = RECORD_SECONDS,
    val isDone: Boolean = false,
) {
    val canStartTraining: Boolean get() = currentLap > MIN_LAPS_TO_TRAIN
    val lapProgress: Float get() = (currentLap - 1).toFloat() / TOTAL_LAPS
    val currentGestureName: String get() = gestures.getOrElse(currentGestureIndex) { "" }
}

@HiltViewModel
class CollectViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(CollectUiState())
    val uiState = _uiState.asStateFlow()

    init {
        startCountdown()
    }

    fun onStartTrainingEarly() {
        _uiState.update { it.copy(isDone = true) }
    }

    fun onDebugSkip() {
        _uiState.update { it.copy(isDone = true) }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (count in 3 downTo 1) {
                _uiState.update { it.copy(phase = CollectPhase.Countdown(count)) }
                delay(COUNTDOWN_STEP_MS)
            }
            _uiState.update { it.copy(phase = CollectPhase.Recording, recordingSecondsLeft = RECORD_SECONDS) }
            // TODO: 랩 완료 후 수집된 EMG 데이터 그래프 시각화 (데스크탑 앱의 랩별 신호 확인 기능)
            for (secondsLeft in RECORD_SECONDS - 1 downTo 0) {
                delay(COUNTDOWN_STEP_MS)
                _uiState.update { it.copy(recordingSecondsLeft = secondsLeft) }
            }
            onGestureDone()
        }
    }

    private fun onGestureDone() {
        val state = _uiState.value
        val nextGestureIndex = state.currentGestureIndex + 1

        if (nextGestureIndex < state.gestures.size) {
            // 같은 랩 내 다음 동작
            _uiState.update { it.copy(currentGestureIndex = nextGestureIndex) }
            startCountdown()
        } else {
            // 랩 완료
            val nextLap = state.currentLap + 1
            if (nextLap > TOTAL_LAPS) {
                _uiState.update { it.copy(isDone = true) }
            } else {
                _uiState.update { it.copy(currentLap = nextLap, currentGestureIndex = 0) }
                startCountdown()
            }
        }
    }
}

fun gestureNameKo(name: String) = when (name) {
    "Rest"       -> "손을 자연스럽게 펴서 쉬기"
    "Flexion"    -> "손목을 아래로 구부리기"
    "Extension"  -> "손목을 위로 젖히기"
    "Close"      -> "주먹 꽉 쥐기"
    "Supination" -> "손바닥이 위를 향하게 돌리기"
    "Pronation"  -> "손바닥이 아래를 향하게 돌리기"
    else         -> name
}
