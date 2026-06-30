package com.mandro.presentation.ui.firmware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CheckState { PENDING, CHECKING, DONE }

data class FirmwareCheck(
    val label: String,
    val state: CheckState = CheckState.PENDING,
)

data class FirmwareUiState(
    val checks: List<FirmwareCheck> = listOf(
        FirmwareCheck("USB 케이블 연결됨"),
        FirmwareCheck("암밴드 감지됨"),
        FirmwareCheck("내 설정 준비됨"),
    ),
    val isUpdateEnabled: Boolean = false,
    val isUpdating: Boolean = false,
    val isDone: Boolean = false,
) {
    val allChecked: Boolean get() = checks.all { it.state == CheckState.DONE }
}

@HiltViewModel
class FirmwareViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(FirmwareUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // TODO: 실제 USB 연결 및 암밴드 감지 이벤트로 교체
        runFakeChecks()
    }

    private fun runFakeChecks() {
        viewModelScope.launch {
            delay(800)
            setCheckDone(0)
            delay(600)
            setCheckDone(1)
            delay(400)
            setCheckDone(2)
            _uiState.update { it.copy(isUpdateEnabled = true) }
        }
    }

    fun onStartUpdate() {
        // TODO: 실제 펌웨어 업데이트 로직으로 교체
        _uiState.update { it.copy(isUpdating = true) }
        viewModelScope.launch {
            delay(3000)
            _uiState.update { it.copy(isDone = true) }
        }
    }

    private fun setCheckDone(index: Int) {
        _uiState.update { state ->
            state.copy(checks = state.checks.mapIndexed { i, check ->
                if (i == index) check.copy(state = CheckState.DONE) else check
            })
        }
    }
}
