package com.mandro.presentation.ui.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
    val estimatedSeconds: Int = 30,
    val isDone: Boolean = false,
)

private fun initialSteps() = listOf(
    TrainingStep(label = "녹화 데이터 확인 중"),
    TrainingStep(label = "설정을 만들고 있어요"),
    TrainingStep(label = "내 동작 패턴을 분석하고 있어요"),
    TrainingStep(label = "거의 다 됐어요!"),
)

@HiltViewModel
class TrainingProgressViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingProgressUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // TODO: 실제 서버 업로드 및 학습 진행 상태로 교체
        runFakeProgress()
    }

    private fun runFakeProgress() {
        viewModelScope.launch {
            // Step 1: 녹화 데이터 확인
            setInProgress(0, "확인 중...")
            delay(1500)
            setDone(0, "완료")

            // Step 2: 업로드 (진행 바 포함)
            setInProgress(1, "전송 중...", progress = 0f)
            for (p in 1..100 step 3) {
                delay(80)
                updateProgress(1, p / 100f, "전송 중... ${p}%")
            }
            setDone(1, "전송 완료")

            // Step 3: 분석
            setInProgress(2, "분석 중...")
            delay(4000)
            setDone(2, "분석 완료")

            // Step 4: 완료
            setInProgress(3, "마무리 중...")
            delay(1500)
            setDone(3, "완료!")

            delay(800)
            _uiState.update { it.copy(isDone = true) }
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

    private fun updateProgress(index: Int, progress: Float, status: String) {
        _uiState.update { state ->
            state.copy(steps = state.steps.mapIndexed { i, step ->
                if (i == index) step.copy(progress = progress, statusText = status) else step
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
