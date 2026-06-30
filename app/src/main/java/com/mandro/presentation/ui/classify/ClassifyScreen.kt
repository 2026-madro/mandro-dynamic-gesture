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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.cos
import kotlin.math.sin

// 8채널 축 각도: 좌우 각 4개씩 대칭 배치 (단위: 라디안)
// 오른쪽: -150°, -120°, -60°, -30° / 왼쪽: 150°, 120°, 60°, 30°  (12시 기준 시계방향)
private val CHANNEL_ANGLES_DEG = floatArrayOf(-60f, -30f, 30f, 60f, 120f, 150f, -150f, -120f)
private val CHANNEL_ANGLES_RAD = CHANNEL_ANGLES_DEG.map { Math.toRadians(it.toDouble()).toFloat() }.toFloatArray()

@Composable
fun ClassifyScreen(
    viewModel: ClassifyViewModel = hiltViewModel(),
    onRelearn: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ClassifyContent(
        uiState = uiState,
        channelValues = viewModel.channelValues,
        onRelearn = onRelearn,
    )
}

@Composable
private fun ClassifyContent(
    uiState: ClassifyUiState,
    channelValues: FloatArray,
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
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // frameCount 읽어서 매 프레임 재드로잉 트리거
                .then(Modifier.wrapContentSize()),
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
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }

            // 채널별 선
            CHANNEL_ANGLES_RAD.forEachIndexed { ch, angle ->
                val value = channelValues[ch].coerceIn(0f, 1f)
                if (value < 0.02f) return@forEachIndexed

                val endX = cx + cos(angle) * maxRadius * value
                val endY = cy + sin(angle) * maxRadius * value

                drawLine(
                    color = MandroPalette.waveColors[ch],
                    start = Offset(cx, cy),
                    end = Offset(endX, endY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
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
            channelValues = floatArrayOf(0.15f, 0.20f, 0.75f, 0.80f, 0.70f, 0.18f, 0.12f, 0.14f),
            onRelearn = {},
        )
    }
}
