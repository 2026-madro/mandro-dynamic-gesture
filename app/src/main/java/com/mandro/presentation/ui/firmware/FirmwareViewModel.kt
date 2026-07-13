package com.mandro.presentation.ui.firmware

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.WeightTransferState
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FirmwareViewModel"

enum class CheckState { PENDING, CHECKING, DONE }

data class FirmwareCheck(
    val label: String,
    val state: CheckState = CheckState.PENDING,
)

data class FirmwareUiState(
    val checks: List<FirmwareCheck> = listOf(
        FirmwareCheck("암밴드 연결됨"),
        FirmwareCheck("내 설정 준비됨"),
    ),
    val isUpdateEnabled: Boolean = false,
    val isUpdating: Boolean = false,
    val isDone: Boolean = false,
    val error: String? = null,
) {
    val allChecked: Boolean get() = checks.all { it.state == CheckState.DONE }
}

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleRepository: BleRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FirmwareUiState())
    val uiState = _uiState.asStateFlow()

    private var weightsFile: File? = null

    init {
        observeBleState()
        observeWeightTransferState()
        checkModelReady()
    }

    // 암밴드 연결은 다른 화면(수집 화면 등)에서 이미 맺어져 있을 수 있음 —
    // BleManager가 싱글턴이라 여기서는 그 연결 상태를 관찰만 함.
    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                if (state is BleState.Connected) {
                    setCheckDone(0)
                    updateEnabled()
                }
            }
        }
    }

    private fun observeWeightTransferState() {
        viewModelScope.launch {
            bleRepository.weightTransferState.collect { state ->
                when (state) {
                    is WeightTransferState.Sending -> {
                        _uiState.update { it.copy(isUpdating = true) }
                    }
                    is WeightTransferState.Done -> {
                        _uiState.update { it.copy(isUpdating = false, isDone = true) }
                    }
                    is WeightTransferState.Error -> {
                        _uiState.update { it.copy(isUpdating = false, error = state.message) }
                        Log.e(TAG, "BLE 가중치 전송 오류: ${state.message}")
                    }
                    is WeightTransferState.Idle -> {}
                }
            }
        }
    }

    private fun checkModelReady() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId() ?: return@launch
            val file = File(context.filesDir, "models/$userId/weights.bin")
            if (file.exists()) {
                weightsFile = file
                setCheckDone(1)
                updateEnabled()
                Log.i(TAG, "weights.bin 준비 완료: ${file.absolutePath}")
            } else {
                _uiState.update { it.copy(error = "학습된 모델이 없어요. 학습을 먼저 진행해 주세요.") }
            }
        }
    }

    fun onStartUpdate() {
        val file = weightsFile ?: return
        _uiState.update { it.copy(isUpdating = true, error = null) }
        viewModelScope.launch {
            bleRepository.sendWeights(file.readBytes())
                .onFailure { e ->
                    _uiState.update { it.copy(isUpdating = false, error = e.message) }
                }
        }
    }

    private fun setCheckDone(index: Int) {
        _uiState.update { state ->
            state.copy(checks = state.checks.mapIndexed { i, check ->
                if (i == index) check.copy(state = CheckState.DONE) else check
            })
        }
    }

    private fun updateEnabled() {
        _uiState.update { it.copy(isUpdateEnabled = it.allChecked) }
    }
}
