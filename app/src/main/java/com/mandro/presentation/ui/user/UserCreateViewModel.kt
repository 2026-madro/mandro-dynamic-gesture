package com.mandro.presentation.ui.user

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class UserCreateUiState(
    val name: String = "",
    val consentPrivacy: Boolean = false,
    val consentResearch: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isStartEnabled: Boolean
        get() = name.isNotBlank() && consentPrivacy
}

@HiltViewModel
class UserCreateViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(UserCreateUiState())
    val uiState = _uiState.asStateFlow()

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, error = null) }
    }

    fun onConsentPrivacyChange(checked: Boolean) {
        _uiState.update { it.copy(consentPrivacy = checked) }
    }

    fun onConsentResearchChange(checked: Boolean) {
        _uiState.update { it.copy(consentResearch = checked) }
    }
}
