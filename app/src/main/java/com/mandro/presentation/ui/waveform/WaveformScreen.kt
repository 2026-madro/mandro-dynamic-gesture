package com.mandro.presentation.ui.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.sin

@Composable
fun WaveformScreen(
    viewModel: WaveformViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WaveformContent(
        uiState = uiState,
        buffers = viewModel.buffers,
        onToggleChannel = viewModel::toggleChannel,
    )
}

@Composable
private fun WaveformContent(
    uiState: WaveformUiState,
    buffers: Array<FloatArray>,
    onToggleChannel: (Int) -> Unit,
) {
    // 매 프레임마다 Canvas를 강제 갱신 — 리컴포지션 없이 드로잉만 반복
    // 이 방식이 StateFlow collect보다 레이턴시가 낮음
    var frameCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            frameCount++
        }
    }

    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.DarkBg),
    ) {
        // 상단 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "신호 모니터",
                style = MaterialTheme.typography.headlineSmall,
                color = MandroPalette.White,
                modifier = Modifier.weight(1f),
            )
            ConnectionBadge(bleState = uiState.bleState)
        }

        // 채널 파형 목록
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // TODO: 채널 수 4/6/8 전환 시 EMG_CHANNELS → uiState.activeChannels로 교체
            repeat(EMG_CHANNELS) { ch ->
                ChannelRow(
                    ch = ch,
                    buffer = buffers[ch],
                    color = MandroPalette.waveColors[ch],
                    isVisible = uiState.visibleChannels[ch],
                    frameCount = frameCount,
                    textMeasurer = textMeasurer,
                    onToggle = { onToggleChannel(ch) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

    }
}

@Composable
private fun ChannelRow(
    ch: Int,
    buffer: FloatArray,
    color: Color,
    isVisible: Boolean,
    frameCount: Long,
    textMeasurer: TextMeasurer,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 채널 라벨 (탭하면 on/off)
        Box(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight()
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CH$ch",
                style = TextStyle(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isVisible) color else MandroPalette.DarkBorder,
                ),
            )
        }

        // 파형 Canvas — frameCount 읽어서 매 프레임 갱신 트리거
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MandroPalette.DarkSurf, RoundedCornerShape(4.dp)),
        ) {
            @Suppress("UNUSED_EXPRESSION")
            frameCount // 이 값을 읽어야 프레임마다 리드로우 발생

            if (isVisible) {
                drawWaveform(
                    buffer = buffer,
                    color = color,
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
    }
}

private fun DrawScope.drawWaveform(
    buffer: FloatArray,
    color: Color,
    strokeWidth: Float,
) {
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val n = buffer.size

    // int16 범위(-32768~32767)를 화면 높이의 ±45%로 정규화
    // TODO: 실제 펌웨어 출력 범위에 맞게 scale 조정 필요
    val scale = (h * 0.45f) / 32768f

    val path = Path()
    var started = false

    for (i in 0 until n) {
        val x = (i.toFloat() / (n - 1)) * w
        val y = midY - buffer[i] * scale

        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth),
    )

    // 중앙 기준선
    drawLine(
        color = MandroPalette.DarkBorder,
        start = Offset(0f, midY),
        end = Offset(w, midY),
        strokeWidth = 0.5.dp.toPx(),
    )
}


// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun WaveformPreview() {
    MandroTheme {
        // 프리뷰용 더미 버퍼
        val dummyBuffers = Array(EMG_CHANNELS) { ch ->
            FloatArray(DISPLAY_SAMPLES) { i ->
                (sin(i * 0.05 + ch) * 10000f + (Math.random() * 2000 - 1000)).toFloat()
            }
        }
        WaveformContent(
            uiState = WaveformUiState(bleState = BleState.Connected(
                com.mandro.domain.model.BleDevice("EMG-Sensor-A4F2", "00:11:22:33:44:55", -55)
            )),
            buffers = dummyBuffers,
            onToggleChannel = {},
        )
    }
}
