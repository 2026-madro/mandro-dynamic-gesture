package com.mandro.presentation.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.repository.EmgRepository
import com.mandro.domain.repository.TrainingProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    TrainingStep(label = "서버에 전송 중"),
    TrainingStep(label = "내 동작 패턴을 분석하고 있어요"),
    TrainingStep(label = "거의 다 됐어요!"),
)

@HiltViewModel
class TrainingProgressViewModel @Inject constructor(
    private val emgRepository: EmgRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingProgressUiState())
    val uiState = _uiState.asStateFlow()

    init {
        startTraining()
    }

    private fun startTraining() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId()
            if (userId == null) {
                _uiState.update { it.copy(error = "사용자 정보를 찾을 수 없어요. 다시 등록해 주세요.") }
                return@launch
            }

            // 데이터는 수집 중 랩마다 이미 서버에 전송됨 — 학습만 요청
            setDone(0, "완료")
            setInProgress(1, "학습 요청 중...")

            emgRepository.uploadAndTrain(userId, null) { progress ->
                when (progress) {
                    is TrainingProgress.CheckingData -> {}
                    is TrainingProgress.Building -> {
                        setDone(1, "전송 완료")
                        setInProgress(2, "분석 중... ${progress.percent}%", progress.percent / 100f)
                    }
                    is TrainingProgress.Analyzing -> {
                        setInProgress(2, "분석 중...")
                    }
                    is TrainingProgress.Finalizing -> {
                        setDone(2, "분석 완료")
                        setInProgress(3, "마무리 중...")
                    }
                    is TrainingProgress.Done -> {
                        setDone(3, "완료!")
                        _uiState.update { it.copy(isDone = true) }
                    }
                    is TrainingProgress.Failed -> {
                        _uiState.update { it.copy(error = progress.message) }
                    }
                }
            }.onSuccess {
                emgRepository.clearBatch(userId)
                _uiState.update { it.copy(isDone = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "학습 중 오류가 발생했어요") }
            }
        }
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
