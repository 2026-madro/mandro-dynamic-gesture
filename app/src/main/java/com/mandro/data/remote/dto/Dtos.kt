package com.mandro.data.remote.dto

import com.google.gson.annotations.SerializedName

// ── Users ──────────────────────────────────────────────────────

data class CreateUserRequest(
    @SerializedName("name") val name: String,
    @SerializedName("consent_required") val consentRequired: Boolean,
    @SerializedName("consent_research") val consentResearch: Boolean = false,
)

data class UserResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("consent_required") val consentRequired: Boolean,
    @SerializedName("consent_research") val consentResearch: Boolean,
    @SerializedName("created_at") val createdAt: String,
)

// ── Sessions ───────────────────────────────────────────────────

data class SessionResponse(
    @SerializedName("id") val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("lap_count") val lapCount: Int,
    @SerializedName("status") val status: String,   // idle|queued|training|done|failed
    @SerializedName("progress") val progress: Int,  // 0~100
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("trained_at") val trainedAt: String?,
    @SerializedName("val_accuracy") val valAccuracy: Float?,
)

data class TrainingStatusResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("status") val status: String,
    @SerializedName("progress") val progress: Int,
)

// ── Scaler ─────────────────────────────────────────────────────

data class ScalerResponse(
    @SerializedName("mean") val mean: List<Float>,
    @SerializedName("std") val std: List<Float>,
    @SerializedName("gestures") val gestures: List<String>,
)
