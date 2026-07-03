package com.mandro.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.User
import com.mandro.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val users: List<User> = emptyList(),
    val bleState: BleState = BleState.Disconnected,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToMain = Channel<Unit>(Channel.BUFFERED)
    val navigateToMain = _navigateToMain.receiveAsFlow()

    init {
        loadUsers()
    }

    private fun loadUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            userRepository.getUsers().collect { users ->
                _uiState.update { it.copy(users = users, isLoading = false) }
            }
        }
    }

    fun onUserSelected(user: User) {
        viewModelScope.launch {
            userPreferences.saveUserId(user.id)
            _navigateToMain.send(Unit)
        }
    }
}
