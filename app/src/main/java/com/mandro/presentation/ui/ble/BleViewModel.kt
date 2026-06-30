package com.mandro.presentation.ui.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.BuildConfig
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BleUiState(
    val bleState: BleState = BleState.Scanning,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class BleViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState = _uiState.asStateFlow()

    init {
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                delay(3000L)
                _uiState.update {
                    it.copy(
                        bleState = BleState.DevicesFound(
                            listOf(
                                BleDevice("EMG-Sensor-A4F2", "00:11:22:33:44:55", -55),
                                BleDevice("EMG-Sensor-B3C1", "00:11:22:33:44:66", -82),
                            )
                        )
                    )
                }
            }
        }
    }

    fun onConnectClick(device: BleDevice) {
        _uiState.update { it.copy(bleState = BleState.Connecting(device)) }
    }

    fun onRescan() {
        _uiState.update { it.copy(bleState = BleState.Scanning) }
    }
}
