package com.mandro.presentation.ui.training

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.repository.EmgRepository
import com.mandro.domain.repository.LocalTrainingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class StepState { PENDING, IN_PROGRESS, DONE }

data class TrainingStep(
    val label: String,
    val state: StepState = StepState.PENDING,
    val progress: Float? = null,   // 0f~1f, null이면 진행 바 없음
    val statusText: String = "대기 중",
)

data class TrainingProgressUiState(
    val steps: List<TrainingStep> = initialSteps(),
    val isDone: Boolean = false,
    val error: String? = null,
    val estimatedSeconds: Int = 60,
)

private fun initialSteps() = listOf(
    TrainingStep(label = "녹화 데이터 확인 중"),
    TrainingStep(label = "학습 데이터 준비 중"),
    TrainingStep(label = "내 동작 패턴을 분석하고 있어요"),
    TrainingStep(label = "거의 다 됐어요!"),
)

private const val WEIGHT_TOTAL_BYTES = 53_304 // NN 가중치(52,248) + StandardScaler mean/std(1,056)

@HiltViewModel
class TrainingProgressViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val emgRepository: EmgRepository,
    private val localTrainingRepository: LocalTrainingRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingProgressUiState())
    val uiState = _uiState.asStateFlow()

    init {
        startTraining()
    }

    // Chaquopy(sklearn)로 폰 안에서 직접 학습 — 서버 업로드 없음.
    // 가중치는 우선 로컬 파일로 저장해두고, BLE/USB 전송(Phase 4)이 붙으면 그걸로 전달.
    private fun startTraining() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId()
            if (userId == null) {
                _uiState.update { it.copy(error = "사용자 정보를 찾을 수 없어요. 다시 등록해 주세요.") }
                return@launch
            }

            setInProgress(0, "녹화 데이터 확인 중...")
            val batch = emgRepository.getBatch(userId)
            if (batch == null) {
                _uiState.update { it.copy(error = "녹화된 데이터를 찾을 수 없어요.") }
                return@launch
            }
            setDone(0, "완료")
            setInProgress(1, "학습 데이터 준비 중...")
            setDone(1, "준비 완료")
            setInProgress(2, "내 동작 패턴을 분석하고 있어요...")

            localTrainingRepository.trainLocally(batch)
                .onSuccess { weights ->
                    setDone(2, "분석 완료")
                    setInProgress(3, "마무리 중...")

                    saveWeights(userId, weights)
                    emgRepository.clearBatch(userId)

                    setDone(3, "완료!")
                    _uiState.update { it.copy(isDone = true) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "학습 중 오류가 발생했어요") }
                }
        }
    }

    private fun saveWeights(userId: String, weights: ByteArray) {
        require(weights.size == WEIGHT_TOTAL_BYTES) {
            "가중치 크기 이상: ${weights.size} bytes (expected $WEIGHT_TOTAL_BYTES)"
        }
        val dir = File(context.filesDir, "models/$userId").also { it.mkdirs() }
        File(dir, "weights.bin").writeBytes(weights)
    }

    private fun setInProgress(index: Int, status: String, progress: Float? = null) {
        _uiState.update { state ->
            state.copy(steps = state.steps.mapIndexed { i, step ->
                when (i) {
                    index -> step.copy(state = StepState.IN_PROGRESS, statusText = status, progress = progress)
                    else  -> step
                }
            })
        }
    }

    private fun setDone(index: Int, status: String) {
        _uiState.update { state ->
            state.copy(steps = state.steps.mapIndexed { i, step ->
                if (i == index) step.copy(state = StepState.DONE, statusText = status, progress = null) else step
            })
        }
    }
}
