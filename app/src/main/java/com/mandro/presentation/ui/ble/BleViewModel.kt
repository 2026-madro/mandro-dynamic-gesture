package com.mandro.presentation.ui.ble

import androidx.lifecycle.ViewModel
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    fun onConnectClick(device: BleDevice) {
        _uiState.update { it.copy(bleState = BleState.Connecting(device)) }
    }

    fun onRescan() {
        _uiState.update { it.copy(bleState = BleState.Scanning) }
    }
}
