package com.mandro.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.EmgWindow
import com.mandro.domain.model.RecordingTake
import com.mandro.domain.model.WINDOW_SIZE

/**
 * BLE로 수집된 EMG 녹화 데이터 1 take.
 *
 * samplesBlob: uint8 flat array, shape (sampleCount × EMG_CHANNELS)
 *   - FloatArray 값이 0~255 범위이므로 byte로 손실 없이 저장 가능
 *   - 100 windows × 128 샘플 × 8ch = 102,400 bytes ≈ 100 KB per take
 *
 * windowLabels: 윈도우별 실제 라벨을 콤마로 이어붙인 문자열 (예: "Rest,Rest,Flexion,...").
 *   동적 모션(온셋/오프셋 큐) 녹화 시 take 전체가 하나의 gesture가 아니라, 큐 활성
 *   구간만 그 동작 라벨이고 나머지는 Rest이므로 윈도우 단위로 따로 저장해야 함
 *   (RECOGNITION_IMPROVEMENT.md 참고 — take 단일 라벨이 라벨 오염의 원인이었음).
 *   빈 문자열이면(과거 데이터) 모든 윈도우가 take의 gesture를 그대로 씀.
 */
@Entity(tableName = "recording_takes")
data class RecordingTakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val gesture: String,
    val takeIndex: Int,
    val recordedAt: Long,
    val sampleCount: Int,               // 총 샘플 수 (= windows * WINDOW_SIZE)
    val samplesBlob: ByteArray,         // sampleCount * EMG_CHANNELS bytes
    val windowLabels: String = "",      // 윈도우별 라벨, 콤마 구분
) {
    fun toDomain(): RecordingTake {
        val labels = if (windowLabels.isEmpty()) emptyList() else windowLabels.split(",")
        val windows = mutableListOf<EmgWindow>()
        var offset = 0
        var windowIndex = 0
        while (offset + WINDOW_SIZE * EMG_CHANNELS <= samplesBlob.size) {
            val data = Array(WINDOW_SIZE) { row ->
                FloatArray(EMG_CHANNELS) { ch ->
                    (samplesBlob[offset + row * EMG_CHANNELS + ch].toInt() and 0xFF).toFloat()
                }
            }
            val label = labels.getOrNull(windowIndex) ?: gesture
            windows.add(EmgWindow(data = data, label = label))
            offset += WINDOW_SIZE * EMG_CHANNELS
            windowIndex++
        }
        return RecordingTake(
            gesture = gesture,
            takeIndex = takeIndex,
            windows = windows,
            recordedAt = recordedAt,
        )
    }

    companion object {
        fun fromDomain(userId: String, take: RecordingTake): RecordingTakeEntity {
            val allSamples = take.windows.flatMap { window -> window.data.toList() }
            val blob = ByteArray(allSamples.size * EMG_CHANNELS) { i ->
                val sample = allSamples[i / EMG_CHANNELS]
                val ch     = i % EMG_CHANNELS
                sample[ch].toInt().coerceIn(0, 255).toByte()
            }
            return RecordingTakeEntity(
                userId      = userId,
                gesture     = take.gesture,
                takeIndex   = take.takeIndex,
                recordedAt  = take.recordedAt,
                sampleCount = allSamples.size,
                samplesBlob = blob,
                windowLabels = take.windows.joinToString(",") { it.label ?: take.gesture },
            )
        }
    }
}
