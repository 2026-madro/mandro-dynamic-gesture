package com.mandro.presentation.ui.firmware

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.repository.EmgRepository
import com.mandro.domain.repository.UsbRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "FirmwareViewModel"

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
    val error: String? = null,
) {
    val allChecked: Boolean get() = checks.all { it.state == CheckState.DONE }
}

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    private val emgRepository: EmgRepository,
    private val usbRepository: UsbRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FirmwareUiState())
    val uiState = _uiState.asStateFlow()

    private var modelBytes: ByteArray? = null
    private var scalerBytes: ByteArray? = null

    init {
        observeUsbState()
        checkModelReady()
    }

    private fun observeUsbState() {
        viewModelScope.launch {
            usbRepository.usbState.collect { state ->
                when (state) {
                    is com.mandro.domain.model.UsbState.DeviceDetected -> {
                        setCheckDone(0)
                        setCheckDone(1)
                        updateEnabled()
                    }
                    is com.mandro.domain.model.UsbState.Flashing -> {
                        _uiState.update { it.copy(isUpdating = true) }
                    }
                    is com.mandro.domain.model.UsbState.Done -> {
                        _uiState.update { it.copy(isDone = true) }
                    }
                    is com.mandro.domain.model.UsbState.Error -> {
                        _uiState.update { it.copy(isUpdating = false, error = state.message) }
                        Log.e(TAG, "USB 오류: ${state.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun checkModelReady() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId() ?: return@launch
            val session = emgRepository.getLatestSession(userId)

            if (session != null && File(session.modelPath).exists()) {
                modelBytes  = File(session.modelPath).readBytes()
                scalerBytes = File(session.scalerPath).readBytes()
                setCheckDone(2)
                updateEnabled()
                Log.i(TAG, "모델 준비 완료: ${session.modelPath}")
            } else {
                _uiState.update { it.copy(error = "학습된 모델이 없어요. 학습을 먼저 진행해 주세요.") }
            }
        }
    }

    fun onStartUpdate() {
        val model  = modelBytes  ?: return
        val scaler = scalerBytes ?: return

        _uiState.update { it.copy(isUpdating = true, error = null) }
        viewModelScope.launch {
            usbRepository.flash(model, scaler)
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
