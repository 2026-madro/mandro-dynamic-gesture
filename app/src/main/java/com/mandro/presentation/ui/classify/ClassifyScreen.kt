package com.mandro.presentation.ui.classify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleState
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.cos
import kotlin.math.sin

// 8채널 축 각도: 좌우 각 4개씩 대칭 배치 (단위: 라디안)
// Y축(좌우) 반전 적용됨: CH0~3(원래 오른쪽) → 왼쪽, CH4~7(원래 왼쪽) → 오른쪽
// (angle → 180° - angle 변환)
private val CHANNEL_ANGLES_DEG = floatArrayOf(-120f, -150f, 150f, 120f, 60f, 30f, -30f, -60f)
private val CHANNEL_ANGLES_RAD = CHANNEL_ANGLES_DEG.map { Math.toRadians(it.toDouble()).toFloat() }.toFloatArray()

// 채널 간 최소 각도 간격은 30° — 떨림으로 인한 각도 변화폭을 그 절반보다
// 확실히 작게 제한해서, 아무리 흔들려도 옆 채널 부채꼴을 침범하지 않게 함.
private val MAX_ANGLE_DEVIATION_RAD = Math.toRadians(10.0).toFloat()
// ClassifyViewModel.JITTER_CLAMP와 맞춰야 함 (거기서 이 범위로 클램프해서 넘어옴)
private const val JITTER_CLAMP = 0.3f

// intensity(0~1)를 화면에 그대로 그리면 세게 힘줘도 작게 보여서, 시각적으로만
// 증폭함 (실제 값 자체는 안 바꾸고 그리는 길이만 키움). 1.0에서 클램프해서
// 차트 밖으로 나가진 않게 함.
private const val INTENSITY_GAIN = 5f

@Composable
fun ClassifyScreen(
    viewModel: ClassifyViewModel = hiltViewModel(),
    onRelearn: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 가중치 전송 성공 후 암밴드가 재부팅하며 연결이 끊기는 경우를 감지해서
    // BleScan으로 돌려보냄 (WaveformScreen과 동일한 패턴). hasConnectedOnce로
    // "화면 진입 직후 아직 연결 확인 전"인 상태와 구분함.
    LaunchedEffect(uiState.bleState, uiState.hasConnectedOnce) {
        if (uiState.hasConnectedOnce && uiState.bleState is BleState.Disconnected) {
            onDisconnected()
        }
    }

    ClassifyContent(
        uiState = uiState,
        channelIntensity = viewModel.channelIntensity,
        channelJitter = viewModel.channelJitter,
        onRelearn = onRelearn,
    )
}

@Composable
private fun ClassifyContent(
    uiState: ClassifyUiState,
    channelIntensity: FloatArray,
    channelJitter: Array<FloatArray>,
    onRelearn: () -> Unit,
) {
    var frameCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            frameCount++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.DarkBg),
    ) {
        // 상단 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "동작 인식",
                style = MaterialTheme.typography.headlineSmall,
                color = MandroPalette.White,
                modifier = Modifier.weight(1f),
            )
            ConnectionBadge(bleState = uiState.bleState)
        }

        // 인식된 동작 카드
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MandroPalette.DarkSurf,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "지금 동작",
                    style = MaterialTheme.typography.labelMedium,
                    color = MandroPalette.Neutral500,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.gesture,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MandroPalette.White,
                )
                Text(
                    text = uiState.gestureKo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                )
            }
        }

        // 레이더 차트
        // weight(1f)+fillMaxWidth()로 남는 공간을 채우게 해놓고 마지막에
        // wrapContentSize()를 붙이면 서로 모순돼서(Canvas는 그릴 자식이
        // 없어 "내용물 크기"가 사실상 0) 캔버스 크기가 0에 가깝게 찌그러짐
        // — 값 계산은 맞는데 그려지는 선 길이가 0이 되는 원인이었음.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            @Suppress("UNUSED_EXPRESSION")
            frameCount

            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxRadius = minOf(size.width, size.height) * 0.42f

            // 기준 동심원 (3단계)
            for (i in 1..3) {
                drawCircle(
                    color = MandroPalette.DarkBorder,
                    radius = maxRadius * (i / 3f),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // 채널별 선 — 길이는 세기(channelIntensity, 느리게 스무딩됨),
            // 선의 흔들림은 떨림(channelJitter, 순간 편차 히스토리)으로 그려서
            // "세기"와 "노이즈"를 시각적으로 분리함. 떨림은 "수직 거리"가 아니라
            // "각도" 변화로 표현해서, 중심에서 멀어져도 옆 채널 부채꼴을 절대
            // 침범하지 않도록 함 (거리로 표현하면 멀어질수록 옆으로 크게 새어나감).
            CHANNEL_ANGLES_RAD.forEachIndexed { ch, angle ->
                val intensity = channelIntensity[ch].coerceIn(0f, 1f)
                if (intensity < 0.02f) return@forEachIndexed

                val length = maxRadius * (intensity * INTENSITY_GAIN).coerceIn(0f, 1f)
                val jitter = channelJitter[ch]
                val path = Path().apply {
                    moveTo(cx, cy)
                    for (i in jitter.indices) {
                        val t = (i + 1f) / jitter.size
                        val angleOffset = (jitter[i] / JITTER_CLAMP) * MAX_ANGLE_DEVIATION_RAD
                        val pointAngle = angle + angleOffset
                        val r = length * t
                        lineTo(cx + cos(pointAngle) * r, cy + sin(pointAngle) * r)
                    }
                }

                drawPath(
                    path = path,
                    color = MandroPalette.waveColors[ch],
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // 중심점
            drawCircle(
                color = MandroPalette.White,
                radius = 4.dp.toPx(),
                center = Offset(cx, cy),
            )
        }

        // 하단 재학습 버튼
        TextButton(
            onClick = onRelearn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = "인식이 잘 안 되나요?  →  다시 학습하기",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MandroPalette.Neutral500,
            )
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0F1420)
@Composable
private fun ClassifyPreview() {
    MandroTheme {
        ClassifyContent(
            uiState = ClassifyUiState(gesture = "Flexion", gestureKo = "손목을 아래로 구부리기"),
            channelIntensity = floatArrayOf(0.15f, 0.20f, 0.75f, 0.80f, 0.70f, 0.18f, 0.12f, 0.14f),
            channelJitter = Array(8) { floatArrayOf(0.02f, -0.03f, 0.01f, -0.02f, 0.03f, -0.01f) },
            onRelearn = {},
        )
    }
}
