package com.mandro.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_USER_ID = stringPreferencesKey("user_id")
    private val KEY_SESSION_ID = stringPreferencesKey("session_id")

    val userId: Flow<String?> = context.dataStore.data.map { it[KEY_USER_ID] }

    suspend fun getUserId(): String? = userId.firstOrNull()

    suspend fun saveUserId(id: String) {
        context.dataStore.edit { it[KEY_USER_ID] = id }
    }

    // 업로드/학습 요청에 실제로 쓰인 세션을 기억해서, 재요청 시 서버가
    // "최신 활성 세션"을 잘못 추론하지 않도록 항상 같은 세션을 가리키게 한다.
    suspend fun getSessionId(): String? = context.dataStore.data.map { it[KEY_SESSION_ID] }.firstOrNull()

    suspend fun saveSessionId(id: String) {
        context.dataStore.edit { it[KEY_SESSION_ID] = id }
    }

    suspend fun clear() {
        context.dataStore.edit {
            it.remove(KEY_USER_ID)
            it.remove(KEY_SESSION_ID)
        }
    }
}
