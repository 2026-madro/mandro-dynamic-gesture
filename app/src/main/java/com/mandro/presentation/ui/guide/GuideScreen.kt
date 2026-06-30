package com.mandro.presentation.ui.guide

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.components.MandroPrimaryButton
import com.mandro.presentation.components.MandroSecondaryButton
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

@Composable
fun GuideScreen(
    viewModel: GuideViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onStartRecord: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GuideContent(
        uiState = uiState,
        onBack = onBack,
        onPrev = viewModel::onPrev,
        onNext = viewModel::onNext,
        onStartRecord = onStartRecord,
    )
}

@Composable
private fun GuideContent(
    uiState: GuideUiState,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStartRecord: () -> Unit,
) {
    Scaffold(
        containerColor = MandroPalette.Neutral50,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 140.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 상단 헤더
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
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
                        text = "${uiState.currentIndex + 1} / ${uiState.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MandroPalette.Neutral500,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    // 헤더 대칭을 위한 빈 공간
                    Spacer(Modifier.width(48.dp))
                }

                // 동작명 + 진행 바
                AnimatedContent(
                    targetState = uiState.currentIndex,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInHorizontally { it } + fadeIn() togetherWith
                                slideOutHorizontally { -it } + fadeOut()
                        } else {
                            slideInHorizontally { -it } + fadeIn() togetherWith
                                slideOutHorizontally { it } + fadeOut()
                        }
                    },
                    label = "gesture_anim",
                ) { index ->
                    val guide = GESTURE_GUIDES[uiState.gestures[index]]!!
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Text(
                            text = guide.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MandroPalette.Neutral900,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = guide.nameKo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MandroPalette.Neutral500,
                        )
                        Spacer(Modifier.height(12.dp))

                        // 진행 바
                        LinearProgressIndicator(
                            progress = { (index + 1).toFloat() / uiState.total },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MandroPalette.Primary600,
                            trackColor = MandroPalette.Neutral100,
                        )

                        Spacer(Modifier.height(24.dp))

                        // 일러스트 영역
                        // TODO: Lottie 파일 준비 후 아래 Box를 LottieAnimation으로 교체
                        // 교체 예시:
                        // val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(guide.lottieRes))
                        // val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
                        // LottieAnimation(composition, { progress }, modifier = Modifier.fillMaxWidth().height(220.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .background(MandroPalette.Neutral100, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "동작 일러스트",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MandroPalette.Neutral300,
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        // 이렇게 해보세요
                        Text(
                            text = "이렇게 해보세요",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MandroPalette.Neutral900,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = guide.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MandroPalette.Neutral700,
                        )

                        // 주의사항 카드
                        if (guide.caution != null) {
                            Spacer(Modifier.height(16.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MandroPalette.Warning100,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "▲",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MandroPalette.Warning600,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = guide.caution,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = MandroPalette.Warning600,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 하단 고정 버튼
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MandroPalette.Neutral50)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.isLast) {
                    // 마지막 동작 → 녹화 시작
                    MandroPrimaryButton(
                        text = "동작 녹화 시작하기",
                        onClick = onStartRecord,
                    )
                } else {
                    // 중간 동작 → 다음으로
                    MandroPrimaryButton(
                        text = "다음",
                        onClick = onNext,
                    )
                }
                if (uiState.currentIndex > 0) {
                    MandroSecondaryButton(
                        text = "이전 동작 다시 보기",
                        onClick = onPrev,
                    )
                }
            }
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun GuidePreview_Flexion() {
    MandroTheme {
        GuideContent(
            uiState = GuideUiState(currentIndex = 1),
            onBack = {},
            onPrev = {},
            onNext = {},
            onStartRecord = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun GuidePreview_Last() {
    MandroTheme {
        GuideContent(
            uiState = GuideUiState(currentIndex = 5),
            onBack = {},
            onPrev = {},
            onNext = {},
            onStartRecord = {},
        )
    }
}
