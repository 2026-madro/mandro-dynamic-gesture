package com.mandro.data.remote.api

import com.mandro.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface MandrApiService {

    // ── Users ───────────────────────────────────────────────────

    @GET("users")
    suspend fun listUsers(): List<UserResponse>

    @POST("users")
    suspend fun createUser(@Body request: CreateUserRequest): UserResponse

    @GET("users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserResponse

    // ── Sessions ────────────────────────────────────────────────

    /**
     * raw EMG + 레이블 업로드.
     * emg_data: uint8 binary, shape (N, 8) row-major
     * labels:   int32 binary, shape (N,)
     */
    @Multipart
    @POST("sessions/{userId}/data")
    suspend fun uploadData(
        @Path("userId") userId: String,
        @Part emgData: MultipartBody.Part,
        @Part labels: MultipartBody.Part,
        @Part("lap_count") lapCount: RequestBody,
        @Part("gesture_set") gestureSet: RequestBody,
    ): SessionResponse

    @POST("sessions/{userId}/train")
    suspend fun startTraining(@Path("userId") userId: String): SessionResponse

    /** 앱은 2~3초 간격으로 폴링 */
    @GET("sessions/{userId}/status")
    suspend fun getTrainingStatus(@Path("userId") userId: String): TrainingStatusResponse

    /** 학습 완료 후 플래시할 firmware.bin 다운로드 */
    @Streaming
    @GET("sessions/{userId}/firmware")
    suspend fun downloadFirmware(@Path("userId") userId: String): ResponseBody

    // ── Health ──────────────────────────────────────────────────

    @GET("health")
    suspend fun health(): Any
}
