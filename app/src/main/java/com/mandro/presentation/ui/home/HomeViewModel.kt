package com.mandro.presentation.ui.home

import androidx.lifecycle.ViewModel
import com.mandro.domain.model.BleState
import com.mandro.domain.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val users: List<User> = emptyList(),
    val bleState: BleState = BleState.Disconnected,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()
}
