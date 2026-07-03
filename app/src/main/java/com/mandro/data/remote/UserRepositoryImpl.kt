package com.mandro.data.remote

import com.mandro.data.local.UserPreferences
import com.mandro.data.remote.api.MandrApiService
import com.mandro.data.remote.dto.CreateUserRequest
import com.mandro.domain.model.GestureSet
import com.mandro.domain.model.User
import com.mandro.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val api: MandrApiService,
    private val prefs: UserPreferences,
) : UserRepository {

    override fun getUsers(): Flow<List<User>> = flow {
        emit(api.listUsers().map { it.toDomain() })
    }

    override suspend fun getUserById(id: String): User? =
        runCatching { api.getUser(id).toDomain() }.getOrNull()

    override suspend fun createUser(name: String, researchConsent: Boolean): User {
        val response = api.createUser(
            CreateUserRequest(
                name = name,
                consentRequired = true,
                consentResearch = researchConsent,
            )
        )
        prefs.saveUserId(response.id)
        return response.toDomain()
    }

    override suspend fun updateUser(user: User) = Unit // 서버 미지원

    override suspend fun deleteUser(id: String) = Unit // 서버 미지원

    private fun com.mandro.data.remote.dto.UserResponse.toDomain() = User(
        id = id,
        name = name,
        createdAt = System.currentTimeMillis(), // 서버는 ISO string, domain은 Long
        hasModel = false,
        lastModelPath = null,
        lastAccuracy = null,
        gestureSet = GestureSet.SIX_CLASS,
        researchConsent = consentResearch,
    )
}
