package com.mandro.data.remote.api

import com.mandro.data.remote.dto.*
import okhttp3.MultipartBody
import retrofit2.http.*

interface MandrApiService {

    @POST("users")
    suspend fun createUser(@Body request: CreateUserRequest): UserResponse

    @GET("users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserResponse

    @DELETE("users/{userId}/research-data")
    suspend fun deleteResearchData(@Path("userId") userId: String)

    @DELETE("users/{userId}")
    suspend fun deleteUser(@Path("userId") userId: String)

    @Multipart
    @POST("sessions/{userId}/data")
    suspend fun uploadData(
        @Path("userId") userId: String,
        @Part files: List<MultipartBody.Part>,
        @Part("gesture_set") gestureSet: String,
    ): UploadResponse

    @POST("sessions/{userId}/train")
    suspend fun requestTraining(
        @Path("userId") userId: String,
        @Body request: TrainRequest,
    ): TrainResponse

    @GET("sessions/{userId}/status")
    suspend fun getTrainingStatus(
        @Path("userId") userId: String,
    ): TrainingStatusResponse

    @GET("sessions/{userId}/model")
    suspend fun downloadModel(
        @Path("userId") userId: String,
    ): ModelResponse
}
