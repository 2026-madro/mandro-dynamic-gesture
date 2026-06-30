package com.mandro.presentation.ui.classify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.sin

data class ClassifyUiState(
    val gesture: String = "Rest",
    val gestureKo: String = "손을 자연스럽게 펴서 쉬기",
    val bleState: BleState = BleState.Connected(
        BleDevice("Mandro-Mock", "00:00:00:00:00:00", -50)
    ),
)

@HiltViewModel
class ClassifyViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ClassifyUiState())
    val uiState = _uiState.asStateFlow()

    // 8채널 값 (0f~1f), awaitFrame() 루프에서 Canvas가 직접 읽음
    val channelValues = FloatArray(8)

    private val gestures = listOf(
        Triple("Rest",       "손을 자연스럽게 펴서 쉬기",        floatArrayOf(0.08f, 0.07f, 0.09f, 0.08f, 0.07f, 0.09f, 0.08f, 0.07f)),
        Triple("Close",      "주먹 꽉 쥐기",                    floatArrayOf(0.90f, 0.85f, 0.88f, 0.92f, 0.87f, 0.91f, 0.86f, 0.89f)),
        Triple("Flexion",    "손목을 아래로 구부리기",            floatArrayOf(0.15f, 0.20f, 0.75f, 0.80f, 0.70f, 0.18f, 0.12f, 0.14f)),
        Triple("Extension",  "손목을 위로 젖히기",               floatArrayOf(0.18f, 0.72f, 0.78f, 0.16f, 0.14f, 0.70f, 0.75f, 0.20f)),
        Triple("Supination", "손바닥이 위를 향하게 돌리기",       floatArrayOf(0.60f, 0.65f, 0.20f, 0.15f, 0.18f, 0.22f, 0.62f, 0.68f)),
        Triple("Pronation",  "손바닥이 아래를 향하게 돌리기",     floatArrayOf(0.18f, 0.20f, 0.22f, 0.62f, 0.65f, 0.60f, 0.18f, 0.16f)),
    )

    init {
        // TODO: 실제 BLE 스트림 + 모델 추론 결과로 교체
        runFakeClassify()
    }

    private fun runFakeClassify() {
        viewModelScope.launch {
            var t = 0.0
            var gestureIndex = 0
            var gestureTimer = 0

            while (true) {
                val target = gestures[gestureIndex]
                val targetValues = target.third

                // 목표 값으로 부드럽게 수렴 + 미세 노이즈
                for (ch in 0 until 8) {
                    val noise = (sin(t * 13.7 + ch * 2.3) * 0.04).toFloat()
                    channelValues[ch] += (targetValues[ch] + noise - channelValues[ch]) * 0.12f
                    channelValues[ch] = channelValues[ch].coerceIn(0f, 1f)
                }

                _uiState.update { it.copy(gesture = target.first, gestureKo = target.second) }

                t += 0.016
                gestureTimer++
                if (gestureTimer > 180) { // ~3초마다 동작 전환
                    gestureTimer = 0
                    gestureIndex = (gestureIndex + 1) % gestures.size
                }

                delay(16L) // ~60fps
            }
        }
    }
}
