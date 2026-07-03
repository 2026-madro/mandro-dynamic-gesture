package com.mandro.domain.repository

import com.mandro.domain.model.*
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUsers(): Flow<List<User>>
    suspend fun getUserById(id: String): User?
    suspend fun createUser(name: String, researchConsent: Boolean): User
    suspend fun updateUser(user: User)
    suspend fun deleteUser(id: String)
}

interface EmgRepository {
    // 녹화 데이터 로컬 저장
    suspend fun saveTake(userId: String, take: RecordingTake)
    suspend fun getBatch(userId: String): RecordingBatch?
    suspend fun clearBatch(userId: String)

    // 서버 학습 요청
    suspend fun uploadAndTrain(
        userId: String,
        batch: RecordingBatch,
        onProgress: (TrainingProgress) -> Unit,
    ): Result<TrainingSession>

    // 모델 로컬 저장
    suspend fun saveModel(userId: String, modelBytes: ByteArray, scalerBytes: ByteArray): TrainingSession
    suspend fun getLatestSession(userId: String): TrainingSession?
}

interface BleRepository {
    val bleState: Flow<BleState>
    val emgStream: Flow<EmgSample>
    val inferenceStream: Flow<InferenceResult>  // BLE Characteristic ...57

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(device: BleDevice)
    suspend fun disconnect()
}

interface UsbRepository {
    val usbState: Flow<UsbState>
    suspend fun flash(modelBytes: ByteArray, scalerBytes: ByteArray): Result<Unit>
}

// 학습 진행 상태
sealed class TrainingProgress {
    object CheckingData : TrainingProgress()        // ① 녹화 데이터 확인 중
    data class Building(val percent: Int) : TrainingProgress()  // ② 설정을 만들고 있어요
    object Analyzing : TrainingProgress()           // ③ 내 동작 패턴을 분석하고 있어요
    object Finalizing : TrainingProgress()          // ④ 거의 다 됐어요!
    data class Done(val accuracy: Float) : TrainingProgress()
    data class Failed(val message: String) : TrainingProgress()
}

