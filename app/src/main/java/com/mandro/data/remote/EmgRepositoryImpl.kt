package com.mandro.data.remote

import android.content.Context
import android.util.Log
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

        api.uploadData(userId, emgPart, labelPart, lapCountBody, gestureSetBody)
        Log.d(TAG, "랩 업로드 완료: userId=$userId gesture=${take.gesture} windows=${take.windows.size}")
    }

    override suspend fun uploadAndTrain(
        userId: String,
        batch: RecordingBatch?,
        onProgress: (TrainingProgress) -> Unit,
    ): Result<TrainingSession> = runCatching {

        onProgress(TrainingProgress.CheckingData)

        // batch가 있으면 업로드, null이면 이미 랩마다 올라간 것으로 간주
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

            api.uploadData(userId, emgPart, labelPart, lapCountBody, gestureSetBody)
            Log.i(TAG, "업로드 완료")
        } else {
            Log.i(TAG, "업로드 건너뜀 (랩마다 전송 완료): userId=$userId")
        }

        // ── 학습 시작 ──────────────────────────────────────────
        api.startTraining(userId)
        onProgress(TrainingProgress.Building(0))

        // ── 4. 상태 폴링 ──────────────────────────────────────────
        var lastStatus = ""
        while (true) {
            delay(POLL_INTERVAL_MS)
            val statusResp = api.getTrainingStatus(userId)
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
        val dir = File(context.filesDir, "models/$userId").also { it.mkdirs() }
        val firmwareFile = File(dir, "firmware.bin")

        val bytes = api.downloadFirmware(userId).bytes()
        firmwareFile.writeBytes(bytes)
        Log.i(TAG, "firmware.bin 다운로드 완료: ${bytes.size} bytes → ${firmwareFile.absolutePath}")

        return TrainingSession(
            userId = userId,
            accuracy = 0f,
            gestureSet = GestureSet.SIX_CLASS,
            firmwarePath = firmwareFile.absolutePath,
        )
    }

    override suspend fun getLatestSession(userId: String): TrainingSession? {
        val firmwareFile = File(context.filesDir, "models/$userId/firmware.bin")
        if (!firmwareFile.exists()) return null
        return TrainingSession(
            userId = userId,
            accuracy = 0f,
            gestureSet = GestureSet.SIX_CLASS,
            firmwarePath = firmwareFile.absolutePath,
        )
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

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
