package com.mandro.data.remote.dto

import com.google.gson.annotations.SerializedName

data class CreateUserRequest(
    @SerializedName("name") val name: String,
    @SerializedName("research_consent") val researchConsent: Boolean,
)

data class UserResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("research_consent") val researchConsent: Boolean,
    @SerializedName("created_at") val createdAt: Long,
)

data class UploadResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("file_count") val fileCount: Int,
)

data class TrainRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("gesture_set") val gestureSet: String,
)

data class TrainResponse(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("status") val status: String,
)

data class TrainingStatusResponse(
    @SerializedName("status") val status: String,          // "checking" | "building" | "analyzing" | "finalizing" | "done" | "failed"
    @SerializedName("progress") val progress: Int?,        // 0 ~ 100
    @SerializedName("accuracy") val accuracy: Float?,
    @SerializedName("error") val error: String?,
)

data class ModelResponse(
    @SerializedName("model_base64") val modelBase64: String,
    @SerializedName("scaler_base64") val scalerBase64: String,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("gesture_set") val gestureSet: String,
)
