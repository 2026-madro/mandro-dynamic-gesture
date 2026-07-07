package com.mandro.presentation.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.TrainingSessionSummary
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onSessionChosen: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.navigateToFirmware.collect { onSessionChosen() }
    }

    HistoryContent(
        uiState = uiState,
        onBack = onBack,
        onSessionClick = viewModel::onSessionSelected,
    )
}

@Composable
private fun HistoryContent(
    uiState: HistoryUiState,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
) {
    Scaffold(
        containerColor = MandroPalette.Neutral50,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 4.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로",
                        tint = MandroPalette.Neutral700,
                    )
                }
                Text(
                    text = "학습 히스토리",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MandroPalette.Neutral900,
                )
            }
        },
    ) { padding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MandroPalette.Primary600)
            }

            uiState.sessions.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = uiState.error ?: "아직 완료된 학습이 없어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(uiState.sessions) { session ->
                    SessionCard(
                        session = session,
                        isSelecting = uiState.selectingSessionId == session.sessionId,
                        onClick = { onSessionClick(session.sessionId) },
                    )
                }
                if (uiState.error != null) {
                    item {
                        Text(
                            text = uiState.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MandroPalette.Neutral500,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: TrainingSessionSummary,
    isSelecting: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !isSelecting,
        shape = RoundedCornerShape(16.dp),
        color = MandroPalette.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTrainedAt(session.trainedAt),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MandroPalette.Neutral900,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("${session.lapCount}랩")
                        append("  ·  ")
                        append(
                            session.accuracy?.let { "정확도 ${(it * 100).toInt()}%" }
                                ?: "정확도 정보 없음"
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MandroPalette.Neutral500,
                )
            }
            if (isSelecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MandroPalette.Primary600,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

private fun formatTrainedAt(epochMillis: Long): String {
    if (epochMillis <= 0L) return "학습일시 알 수 없음"
    val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

// ── 프리뷰 ────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun HistoryPreview() {
    MandroTheme {
        HistoryContent(
            uiState = HistoryUiState(
                isLoading = false,
                sessions = listOf(
                    TrainingSessionSummary("s1", 56, 0.92f, System.currentTimeMillis()),
                    TrainingSessionSummary("s2", 30, null, System.currentTimeMillis() - 86_400_000),
                ),
            ),
            onBack = {},
            onSessionClick = {},
        )
    }
}
