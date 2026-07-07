package com.mandro.data.remote

import android.content.Context
import android.util.Log
import com.mandro.data.local.UserPreferences
import com.mandro.data.local.db.RecordingTakeDao
import com.mandro.data.local.db.RecordingTakeEntity
import com.mandro.data.remote.api.MandrApiService
import com.mandro.domain.model.*
import com.mandro.domain.repository.EmgRepository
import com.mandro.domain.repository.TrainingProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmgRepositoryImpl"
private const val POLL_INTERVAL_MS = 2500L

// 서버 spec 기준 동작 인덱스
private val GESTURE_INDEX = mapOf(
    "Rest"       to 0,
    "Flexion"    to 1,
    "Extension"  to 2,
    "Close"      to 3,
    "Supination" to 4,
    "Pronation"  to 5,
)

@Singleton
class EmgRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: MandrApiService,
    private val takeDao: RecordingTakeDao,
    private val userPreferences: UserPreferences,
) : EmgRepository {

    override suspend fun saveTake(userId: String, take: RecordingTake) {
        takeDao.insert(RecordingTakeEntity.fromDomain(userId, take))
        Log.d(TAG, "take 저장: userId=$userId gesture=${take.gesture} windows=${take.windows.size}")
    }

    override suspend fun getBatch(userId: String): RecordingBatch? {
        val entities = takeDao.getByUserId(userId)
        if (entities.isEmpty()) return null
        val takes = entities.map { it.toDomain() }
        return RecordingBatch(
            userId = userId,
            gestureSet = GestureSet.SIX_CLASS,
            takes = takes.groupBy { it.gesture },
        )
    }

    override suspend fun clearBatch(userId: String) {
        takeDao.deleteByUserId(userId)
        Log.d(TAG, "배치 초기화: userId=$userId")
    }

    override suspend fun deleteTakesForLap(userId: String, takeIndex: Int) {
        takeDao.deleteByUserIdAndTakeIndex(userId, takeIndex)
        Log.d(TAG, "랩 재녹화 - 기존 take 삭제: userId=$userId takeIndex=$takeIndex")
    }

    override suspend fun uploadTake(userId: String, take: RecordingTake): Result<Unit> = runCatching {
        val gestureIdx = GESTURE_INDEX[take.gesture]
            ?: throw IllegalArgumentException("알 수 없는 제스처: ${take.gesture}")

        val totalSamples = take.windows.size * WINDOW_SIZE
        val emgBuf = ByteBuffer.allocate(totalSamples * EMG_CHANNELS)
        val labelBuf = ByteBuffer.allocate(totalSamples * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (window in take.windows) {
            for (sample in window.data) {
                for (ch in 0 until EMG_CHANNELS) {
                    emgBuf.put(sample[ch].toInt().coerceIn(0, 255).toByte())
                }
                labelBuf.putInt(gestureIdx)
            }
        }

        val emgPart = MultipartBody.Part.createFormData(
            name = "emg_data", filename = "emg.bin",
            body = emgBuf.array().toRequestBody("application/octet-stream".toMediaTypeOrNull()),
        )
        val labelPart = MultipartBody.Part.createFormData(
            name = "labels", filename = "labels.bin",
            body = labelBuf.array().toRequestBody("application/octet-stream".toMediaTypeOrNull()),
        )
        val lapCountBody = "1".toRequestBody("text/plain".toMediaTypeOrNull())
        val gestureSetBody = GestureSet.SIX_CLASS.apiKey.toRequestBody("text/plain".toMediaTypeOrNull())

        val session = api.uploadData(userId, emgPart, labelPart, lapCountBody, gestureSetBody)
        userPreferences.saveSessionId(session.id)
        Log.d(TAG, "랩 업로드 완료: userId=$userId gesture=${take.gesture} windows=${take.windows.size} sessionId=${session.id}")
    }

    override suspend fun uploadAndTrain(
        userId: String,
        batch: RecordingBatch?,
        onProgress: (TrainingProgress) -> Unit,
    ): Result<TrainingSession> = runCatching {

        onProgress(TrainingProgress.CheckingData)

        // batch가 있으면 업로드해서 그 응답의 session_id를 기억, null이면
        // 랩마다 전송하며 저장해둔 session_id를 재사용 (서버가 "최신 활성
        // 세션"을 잘못 추론해 다른 세션을 대상으로 학습하는 것을 방지)
        var sessionId = userPreferences.getSessionId()

        if (batch != null) {
            val (emgBytes, labelBytes) = serializeBatch(batch)
            val lapCount = batch.takes.values.maxOfOrNull { it.size } ?: 0
            Log.i(TAG, "업로드 시작: userId=$userId lapCount=$lapCount samples=${emgBytes.size / EMG_CHANNELS}")

            val emgPart = MultipartBody.Part.createFormData(
                name = "emg_data", filename = "emg.bin",
                body = emgBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
            )
            val labelPart = MultipartBody.Part.createFormData(
                name = "labels", filename = "labels.bin",
                body = labelBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
            )
            val lapCountBody = lapCount.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val gestureSetBody = batch.gestureSet.apiKey.toRequestBody("text/plain".toMediaTypeOrNull())

            val session = api.uploadData(userId, emgPart, labelPart, lapCountBody, gestureSetBody)
            sessionId = session.id
            userPreferences.saveSessionId(sessionId)
            Log.i(TAG, "업로드 완료: sessionId=$sessionId")
        } else {
            Log.i(TAG, "업로드 건너뜀 (랩마다 전송 완료): userId=$userId sessionId=$sessionId")
        }

        // ── 이미 학습이 끝난 세션이면 재학습 없이 바로 결과물 다운로드 ──
        if (sessionId != null) {
            val already = runCatching { api.getTrainingStatus(userId, sessionId) }.getOrNull()
            if (already?.status == "done") {
                Log.i(TAG, "이미 학습 완료된 세션(sessionId=$sessionId) — 재학습 건너뜀")
                onProgress(TrainingProgress.Finalizing)
                return@runCatching saveHeaderFiles(userId)
            }
        }

        // ── 학습 시작 (409 = 이미 진행 중 → 무시하고 폴링으로) ──
        val trainResp = api.startTraining(userId, sessionId)
        if (!trainResp.isSuccessful && trainResp.code() != 409) {
            throw RuntimeException("학습 시작 실패: ${trainResp.code()}")
        }
        trainResp.body()?.let {
            sessionId = it.id
            userPreferences.saveSessionId(it.id)
        }
        onProgress(TrainingProgress.Building(0))

        // ── 4. 상태 폴링 ──────────────────────────────────────────
        var lastStatus = ""
        while (true) {
            delay(POLL_INTERVAL_MS)
            val statusResp = api.getTrainingStatus(userId, sessionId)
            val progress = statusResp.progress

            if (statusResp.status != lastStatus) {
                Log.d(TAG, "학습 상태: ${statusResp.status} ($progress%)")
                lastStatus = statusResp.status
            }

            when (statusResp.status) {
                "queued"   -> onProgress(TrainingProgress.CheckingData)
                "training" -> onProgress(TrainingProgress.Building(progress))
                "done"     -> break
                "failed"   -> throw RuntimeException("서버 학습 실패")
            }
        }

        onProgress(TrainingProgress.Finalizing)

        Log.i(TAG, "학습 완료: userId=$userId")
        return@runCatching saveHeaderFiles(userId)
    }

    override suspend fun saveHeaderFiles(userId: String): TrainingSession {
        // 학습 직후 흐름 — uploadAndTrain에서 저장해둔 "지금 학습한 세션"을 사용
        val sessionId = userPreferences.getSessionId()
            ?: throw IllegalStateException("세션 정보가 없어요. 다시 학습해 주세요.")
        val dir = File(context.filesDir, "models/$userId/$sessionId").also { it.mkdirs() }
        val firmwareFile = File(dir, "firmware.bin")

        val bytes = api.downloadFirmware(userId, sessionId).bytes()
        firmwareFile.writeBytes(bytes)
        Log.i(TAG, "firmware.bin 다운로드 완료: ${bytes.size} bytes → ${firmwareFile.absolutePath}")

        return TrainingSession(
            userId = userId,
            sessionId = sessionId,
            accuracy = 0f,
            gestureSet = GestureSet.SIX_CLASS,
            firmwarePath = firmwareFile.absolutePath,
        )
    }

    override suspend fun getLatestSession(userId: String): TrainingSession? {
        // 히스토리에서 다른 세션을 선택했다면 그게 "최신 선택"으로 저장돼 있음
        val sessionId = userPreferences.getSessionId() ?: return null
        val firmwareFile = File(context.filesDir, "models/$userId/$sessionId/firmware.bin")
        if (!firmwareFile.exists()) return null
        return TrainingSession(
            userId = userId,
            sessionId = sessionId,
            accuracy = 0f,
            gestureSet = GestureSet.SIX_CLASS,
            firmwarePath = firmwareFile.absolutePath,
        )
    }

    override suspend fun getSessionHistory(userId: String): Result<List<TrainingSessionSummary>> = runCatching {
        api.listSessionHistory(userId)
            .filter { it.status == "done" }
            .map {
                TrainingSessionSummary(
                    sessionId = it.id,
                    lapCount = it.lapCount,
                    accuracy = it.valAccuracy,
                    trainedAt = it.trainedAt?.let(::parseIsoToEpochMillis) ?: 0L,
                )
            }
    }

    override suspend fun downloadSessionFirmware(
        userId: String, sessionId: String,
    ): Result<TrainingSession> = runCatching {
        val dir = File(context.filesDir, "models/$userId/$sessionId").also { it.mkdirs() }
        val firmwareFile = File(dir, "firmware.bin")

        val bytes = api.downloadFirmware(userId, sessionId).bytes()
        firmwareFile.writeBytes(bytes)
        Log.i(TAG, "히스토리 세션 firmware.bin 다운로드: sessionId=$sessionId ${bytes.size} bytes")

        // 이 세션을 "현재 선택된 모델"로 기억해서 FirmwareScreen이 이걸 찾도록 함
        userPreferences.saveSessionId(sessionId)

        TrainingSession(
            userId = userId,
            sessionId = sessionId,
            accuracy = 0f,
            gestureSet = GestureSet.SIX_CLASS,
            firmwarePath = firmwareFile.absolutePath,
        )
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    /**
     * 백엔드가 naive UTC(datetime.utcnow().isoformat())로 보내는 시각 문자열을
     * epoch millis로 변환. minSdk 24라 java.time 대신 SimpleDateFormat 사용.
     */
    private fun parseIsoToEpochMillis(iso: String): Long? = runCatching {
        val normalized = iso.replace(Regex("""(\.\d{3})\d*$"""), "$1")
            .let { if (it.contains('.')) it else "$it.000" }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        sdf.parse(normalized)?.time
    }.getOrNull()

    /**
     * RecordingBatch → (emg_data bytes, labels bytes)
     * emg_data: uint8, shape (N, 8) row-major
     * labels:   int32 LE, shape (N,)
     */
    private fun serializeBatch(batch: RecordingBatch): Pair<ByteArray, ByteArray> {
        // 전체 샘플 수 계산
        val allWindows = mutableListOf<Pair<EmgWindow, Int>>() // (window, gestureIdx)
        for ((gesture, takes) in batch.takes) {
            val gestureIdx = GESTURE_INDEX[gesture] ?: continue
            for (take in takes) {
                for (window in take.windows) {
                    allWindows.add(window to gestureIdx)
                }
            }
        }

        val totalSamples = allWindows.size * WINDOW_SIZE
        val emgBuf = ByteBuffer.allocate(totalSamples * EMG_CHANNELS)
        val labelBuf = ByteBuffer.allocate(totalSamples * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        for ((window, gestureIdx) in allWindows) {
            for (sample in window.data) {          // WINDOW_SIZE 행
                for (ch in 0 until EMG_CHANNELS) {
                    emgBuf.put(sample[ch].toInt().coerceIn(0, 255).toByte())
                }
                labelBuf.putInt(gestureIdx)
            }
        }

        return emgBuf.array() to labelBuf.array()
    }

}

private val GestureSet.apiKey: String
    get() = when (this) {
        GestureSet.SIX_CLASS  -> "6cl"
        GestureSet.FOUR_CLASS -> "4cl"
    }
