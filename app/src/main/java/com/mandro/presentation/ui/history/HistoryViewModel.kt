package com.mandro.presentation.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.TrainingSessionSummary
import com.mandro.domain.repository.EmgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val sessions: List<TrainingSessionSummary> = emptyList(),
    val isLoading: Boolean = true,
    val selectingSessionId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val emgRepository: EmgRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToFirmware = MutableSharedFlow<Unit>()
    val navigateToFirmware = _navigateToFirmware.asSharedFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId()
            if (userId == null) {
                _uiState.update { it.copy(isLoading = false, error = "사용자 정보를 찾을 수 없어요.") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, error = null) }
            emgRepository.getSessionHistory(userId)
                .onSuccess { sessions ->
                    _uiState.update {
                        it.copy(sessions = sessions.sortedByDescending { s -> s.trainedAt }, isLoading = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "히스토리를 불러오지 못했어요") }
                }
        }
    }

    fun onSessionSelected(sessionId: String) {
        viewModelScope.launch {
            val userId = userPreferences.getUserId() ?: return@launch
            _uiState.update { it.copy(selectingSessionId = sessionId, error = null) }
            emgRepository.downloadSessionFirmware(userId, sessionId)
                .onSuccess {
                    _uiState.update { it.copy(selectingSessionId = null) }
                    _navigateToFirmware.emit(Unit)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(selectingSessionId = null, error = e.message ?: "모델을 받아오지 못했어요")
                    }
                }
        }
    }
}
