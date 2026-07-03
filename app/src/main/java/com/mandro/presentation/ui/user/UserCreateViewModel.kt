package com.mandro.presentation.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserCreateUiState(
    val name: String = "",
    val consentPrivacy: Boolean = false,
    val consentResearch: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isStartEnabled: Boolean
        get() = name.isNotBlank() && consentPrivacy && !isLoading
}

@HiltViewModel
class UserCreateViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserCreateUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateNext = Channel<Unit>(Channel.BUFFERED)
    val navigateNext = _navigateNext.receiveAsFlow()

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, error = null) }
    }

    fun onConsentPrivacyChange(checked: Boolean) {
        _uiState.update { it.copy(consentPrivacy = checked) }
    }

    fun onConsentResearchChange(checked: Boolean) {
        _uiState.update { it.copy(consentResearch = checked) }
    }

    fun onStartClick() {
        val state = _uiState.value
        if (!state.isStartEnabled) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                userRepository.createUser(
                    name = state.name.trim(),
                    researchConsent = state.consentResearch,
                )
            }.onSuccess {
                _navigateNext.send(Unit)
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "오류가 발생했어요") }
            }
        }
    }
}
