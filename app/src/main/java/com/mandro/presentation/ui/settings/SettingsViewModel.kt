package com.mandro.presentation.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    // 홈으로 나가면서 이번 유저의 BLE 연결을 끊어, 다음 유저가 스캔할 때
    // 이전 연결이 살아있는 채로 남는 걸 방지한다.
    fun onGoHome(onDone: () -> Unit) {
        viewModelScope.launch {
            bleRepository.disconnect()
            onDone()
        }
    }
}
