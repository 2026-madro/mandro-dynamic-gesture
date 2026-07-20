package com.mandro.presentation.ui.collect

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.core.calibration.RestCalibration
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.EmgWindow
import com.mandro.domain.model.GestureSet
import com.mandro.domain.model.RecordingTake
import com.mandro.domain.model.WINDOW_SIZE
import com.mandro.domain.repository.BleRepository
import com.mandro.domain.repository.EmgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val TOTAL_LAPS = 10
const val MIN_LAPS_TO_TRAIN = 5
private const val RECORD_DURATION_MS = 10000L
private const val COUNTDOWN_STEP_MS = 1000L

// 온셋/오프셋 큐 녹화 (RECOGNITION_IMPROVEMENT.md 5차 참고) — take 전체를 한 라벨로
// 묶는 대신, "지금 동작하세요" 큐를 여러 번 주고 그 활성 구간만 해당 동작 라벨,
// 나머지는 Rest로 윈도우별로 기록. Rest 랩 자체는 큐 없이 계속 Rest.
const val REPS_PER_TAKE = 5
private const val CYCLE_MS = RECORD_DURATION_MS / REPS_PER_TAKE  // 2000ms
// 큐(삐 소리) 이후 반응 시간을 감안해 살짝 늦게 활성 구간 시작
private const val REACTION_DELAY_MS = 150L
private const val ACTIVE_WINDOW_MS = 700L
private const val REST_GESTURE_NAME = "Rest"

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
    val bleState: BleState = BleState.Disconnected,
    val lapReviewPending: Boolean = false,  // 랩(6동작) 완료 — 재녹화/다음 랩 선택 대기 중
) {
    val canStartTraining: Boolean get() = currentLap > MIN_LAPS_TO_TRAIN
    val lapProgress: Float get() = (currentLap - 1).toFloat() / TOTAL_LAPS
    val currentGestureName: String get() = gestures.getOrElse(currentGestureIndex) { "" }
    val isDisconnected: Boolean get() = bleState !is BleState.Connected
}

@HiltViewModel
class CollectViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val emgRepository: EmgRepository,
    private val userPreferences: UserPreferences,
    private val restCalibration: RestCalibration,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectUiState())
    val uiState = _uiState.asStateFlow()

    @Volatile var channelAmplitudes: FloatArray = FloatArray(EMG_CHANNELS)
        private set

    // 녹화 중 raw 샘플 버퍼 (캘리브레이션 적용 전 원본값)
    @Volatile private var isRecording = false
    private val recordingBuffer = mutableListOf<FloatArray>()
    // recordingBuffer와 항상 같은 길이로, 샘플별 실제 라벨(Rest 또는 큐 활성 시
    // 해당 동작명)을 병렬로 기록 — synchronized(recordingBuffer) 블록 안에서만 접근.
    private val recordingLabelBuffer = mutableListOf<String>()
    private var recordingStartElapsedMs = 0L

    // 큐(삐 소리 + 화면 플래시) 이벤트 — CollectScreen이 구독해서 사운드/애니메이션 재생
    private val _cueEvent = Channel<Unit>(Channel.BUFFERED)
    val cueEvent = _cueEvent.receiveAsFlow()

    init {
        bleRepository.setEmgEnabled(true)
        observeBleState()
        startCountdown()
        collectEmgStream()
    }

    override fun onCleared() {
        super.onCleared()
        bleRepository.setEmgEnabled(false)
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                _uiState.update { it.copy(bleState = state) }
            }
        }
    }

    // BLE가 연결 상태가 될 때까지 대기 — 녹화/카운트다운 도중 연결이 끊기면
    // 여기서 멈춰서 이후 단계가 진행되지 않도록 함 (최대 1초 지연 후 감지)
    private suspend fun waitUntilConnected() {
        bleRepository.bleState.first { it is BleState.Connected }
    }

    private fun collectEmgStream() {
        viewModelScope.launch {
            bleRepository.emgStream.collect { sample ->
                // ── 신호 세기 바 업데이트 ──────────────────────────────
                val prev = channelAmplitudes
                channelAmplitudes = FloatArray(EMG_CHANNELS) { ch ->
                    val raw = sample.channels[ch]
                    val normalized = if (restCalibration.isCalibrated) {
                        val baseline = restCalibration.baseline[ch]
                        val maxRange = (255f - baseline).coerceAtLeast(1f)
                        ((raw - baseline) / maxRange).coerceIn(0f, 1f)
                    } else {
                        (raw / 255f).coerceIn(0f, 1f)
                    }
                    maxOf(normalized, prev[ch] * 0.992f)
                }

                // ── 녹화 버퍼에 raw 샘플 적재 ─────────────────────────
                if (isRecording) {
                    val elapsedMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs
                    val label = labelForElapsed(elapsedMs, _uiState.value.currentGestureName)
                    synchronized(recordingBuffer) {
                        recordingBuffer.add(sample.channels.copyOf())
                        recordingLabelBuffer.add(label)
                    }
                }
            }
        }
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
                waitUntilConnected()
                _uiState.update { it.copy(phase = CollectPhase.Countdown(count)) }
                delay(COUNTDOWN_STEP_MS)
            }

            // 녹화 시작
            waitUntilConnected()
            synchronized(recordingBuffer) {
                recordingBuffer.clear()
                recordingLabelBuffer.clear()
            }
            recordingStartElapsedMs = SystemClock.elapsedRealtime()
            isRecording = true
            _uiState.update { it.copy(phase = CollectPhase.Recording, recordingSecondsLeft = RECORD_SECONDS) }
            scheduleCues(_uiState.value.currentGestureName)

            for (secondsLeft in RECORD_SECONDS - 1 downTo 0) {
                waitUntilConnected()
                delay(COUNTDOWN_STEP_MS)
                _uiState.update { it.copy(recordingSecondsLeft = secondsLeft) }
            }

            // 녹화 종료 → 저장
            isRecording = false
            onGestureDone()
        }
    }

    private fun onGestureDone() {
        val state = _uiState.value
        saveAndUploadCurrentTake(state)

        val nextGestureIndex = state.currentGestureIndex + 1
        if (nextGestureIndex < state.gestures.size) {
            _uiState.update { it.copy(currentGestureIndex = nextGestureIndex) }
            startCountdown()
        } else {
            // 랩(6동작) 전부 완료 — 바로 다음 랩으로 넘어가지 않고 사용자에게 확인
            _uiState.update { it.copy(lapReviewPending = true) }
        }
    }

    private fun saveAndUploadCurrentTake(state: CollectUiState) {
        // 녹화된 샘플 → EmgWindow 리스트로 변환 후 저장
        viewModelScope.launch {
            val userId = userPreferences.getUserId()
            if (userId != null) {
                val (samples, labels) = synchronized(recordingBuffer) {
                    recordingBuffer.toList() to recordingLabelBuffer.toList()
                }
                val windows = samplesToWindows(samples, labels)
                if (windows.isNotEmpty()) {
                    val take = RecordingTake(
                        gesture = state.currentGestureName,
                        takeIndex = state.currentLap - 1,
                        windows = windows,
                    )
                    emgRepository.saveTake(userId, take)
                }
            }
        }
    }

    /**
     * 지금 elapsed 시간이 큐 활성 구간(REACTION_DELAY_MS ~ +ACTIVE_WINDOW_MS)
     * 안이면 해당 동작 라벨, 아니면 Rest. Rest 랩 자체는 큐 없이 항상 Rest.
     */
    private fun labelForElapsed(elapsedMs: Long, gestureName: String): String {
        if (gestureName == REST_GESTURE_NAME) return REST_GESTURE_NAME
        val posInCycle = elapsedMs % CYCLE_MS
        return if (posInCycle in REACTION_DELAY_MS until (REACTION_DELAY_MS + ACTIVE_WINDOW_MS)) {
            gestureName
        } else {
            REST_GESTURE_NAME
        }
    }

    /** REPS_PER_TAKE번 큐(삐+플래시) 이벤트를 CYCLE_MS 간격으로 발행. Rest 랩은 큐 없음. */
    private fun scheduleCues(gestureName: String) {
        if (gestureName == REST_GESTURE_NAME) return
        viewModelScope.launch {
            repeat(REPS_PER_TAKE) { rep ->
                if (rep > 0) delay(CYCLE_MS)
                _cueEvent.send(Unit)
            }
        }
    }

    /** 방금 끝난 랩을 버리고 처음(1번째 동작)부터 다시 녹화 */
    fun onRedoLap() {
        val state = _uiState.value
        viewModelScope.launch {
            val userId = userPreferences.getUserId()
            if (userId != null) {
                emgRepository.deleteTakesForLap(userId, takeIndex = state.currentLap - 1)
            }
        }
        _uiState.update { it.copy(currentGestureIndex = 0, lapReviewPending = false) }
        startCountdown()
    }

    /** 방금 끝난 랩을 유지하고 다음 랩으로 진행 */
    fun onContinueToNextLap() {
        val state = _uiState.value
        val nextLap = state.currentLap + 1
        if (nextLap > TOTAL_LAPS) {
            _uiState.update { it.copy(lapReviewPending = false, isDone = true) }
        } else {
            _uiState.update { it.copy(currentLap = nextLap, currentGestureIndex = 0, lapReviewPending = false) }
            startCountdown()
        }
    }

    /**
     * raw 샘플 리스트를 WINDOW_SIZE 단위 EmgWindow로 변환, 윈도우별 라벨은 그 구간
     * 샘플 라벨의 다수결로 결정 (온셋/오프셋 경계에 걸친 윈도우는 이제 진짜로
     * 다수결이 의미를 가짐 — take 전체가 한 라벨이던 이전과 다른 부분).
     * 끝부분 나머지(< WINDOW_SIZE)는 버림.
     */
    private fun samplesToWindows(samples: List<FloatArray>, labels: List<String>): List<EmgWindow> {
        val windows = mutableListOf<EmgWindow>()
        var i = 0
        while (i + WINDOW_SIZE <= samples.size) {
            val data = Array(WINDOW_SIZE) { row -> samples[i + row] }
            val windowLabel = labels.subList(i, i + WINDOW_SIZE)
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
            windows.add(EmgWindow(data = data, label = windowLabel))
            i += WINDOW_SIZE
        }
        return windows
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
