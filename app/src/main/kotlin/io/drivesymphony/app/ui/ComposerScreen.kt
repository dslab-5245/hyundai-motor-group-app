package io.drivesymphony.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.drivesymphony.app.audio.PlayMode
import io.drivesymphony.app.audio.ServiceUiState
import io.drivesymphony.engine.EngineState
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.math.sin

/** Parked screen: full visualizer, trip composition summary, genre / feedback / demo / remix controls. */
@Composable
fun ComposerScreen(
    engine: EngineState?,
    ui: ServiceUiState,
    onToggle: () -> Unit,
    onGenre: (Genre) -> Unit,
    onFeedback: (Int) -> Unit,
    onDemo: (Boolean, String) -> Unit,
    onPickTrack: () -> Unit,
    onClearTrack: () -> Unit,
    onShare: (TripSummary) -> Unit,
    onAutoStart: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = DsColors.accent(ui.genre)
    Row(modifier = modifier.fillMaxSize().background(DsColors.bg).padding(32.dp)) {
        // ---- left: now playing + visualizer ------------------------------------------------------
        Column(modifier = Modifier.weight(1.2f)) {
            Text(text = engine?.title ?: "Drive Symphony", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = DsColors.text, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(
                text = engine?.let { "${it.keyLabel} · ${it.chordLabel} → ${it.nextChordLabel} · ${it.bpm.toInt()} BPM · ${it.bar}마디" } ?: "",
                fontSize = 22.sp, color = DsColors.muted,
            )
            Spacer(Modifier.height(18.dp))
            Visualizer(engine, accent, modifier = Modifier.fillMaxWidth().height(180.dp))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = onToggle, modifier = Modifier.height(72.dp).width(200.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = DsColors.bg)) {
                    Text(if (ui.playing) "일시정지" else "연주 시작", fontSize = 24.sp)
                }
                for (g in Genre.values()) {
                    val selected = g == ui.genre
                    // tighter padding + no wrapping: in the 2-pane shell the row is exactly one column wide and 'Ambient' used to break
                    OutlinedButton(onClick = { onGenre(g) }, modifier = Modifier.height(72.dp), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) DsColors.accent(g).copy(alpha = 0.22f) else Color.Transparent, contentColor = if (selected) DsColors.accent(g) else DsColors.muted)) {
                        Text(g.label, fontSize = 22.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            // mode card: the two driver modes the concept defines
            Card {
                Text(text = "모드", fontSize = 22.sp, color = DsColors.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeButton("음악 만들기", "주행이 곡을 생성", ui.mode == PlayMode.GENERATIVE, accent, Modifier.weight(1f)) { if (ui.mode == PlayMode.REMIX) onClearTrack() }
                    ModeButton("기존 음악 변주하기", ui.remixTrackTitle ?: "내 음원 선택 (USB·로컬)", ui.mode == PlayMode.REMIX, accent, Modifier.weight(1f)) { onPickTrack() }
                }
                ui.remixError?.let { Spacer(Modifier.height(6.dp)); Text(text = "재생 오류: $it", fontSize = 18.sp, color = DsColors.warn) }
                if (ui.mode == PlayMode.REMIX) {
                    Spacer(Modifier.height(6.dp))
                    Text(text = "같은 Flow 규칙으로 로우패스·워블·스테레오 폭이 변합니다 (스트리밍 앱 음원은 지원하지 않음)", fontSize = 17.sp, color = DsColors.muted)
                }
            }
        }

        Spacer(Modifier.width(28.dp))

        // ---- right: summary + settings -----------------------------------------------------------
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val summary = ui.lastSummary
            Card {
                Text(text = if (summary != null) "지난 여정의 곡" else "이번 여정", fontSize = 22.sp, color = DsColors.muted)
                Spacer(Modifier.height(6.dp))
                if (summary != null) {
                    Text(text = summary.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = DsColors.text, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    StatRow("평균 Flow", "${(summary.averageFlow * 100).toInt()}")
                    StatRow("최장 부드러운 구간", "${summary.bestStreakSec.toInt()}초")
                    StatRow("멜로디가 열린 시간", "${summary.bloomSec.toInt()}초")
                    StatRow("연주 길이", "${(summary.durationSec / 60).toInt()}분 ${(summary.durationSec % 60).toInt()}초 · ${summary.bars}마디")
                    val penalties = summary.eventCounts.filterKeys { it.isPenalty }
                    StatRow("음악이 가라앉은 순간", if (penalties.isEmpty()) "없음" else penalties.entries.joinToString(", ") { "${it.key.label} ${it.value}" })
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { onShare(summary) }, modifier = Modifier.height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = DsColors.surfaceHi, contentColor = DsColors.text)) { Text("여정 요약 공유", fontSize = 20.sp) }
                } else {
                    Text(text = engine?.let { "Flow ${(it.flow * 100).toInt()} · ${if (it.streakSec > 0) "부드럽게 ${it.streakSec.toInt()}초째" else "대기 중"}" } ?: "-", fontSize = 24.sp, color = DsColors.text)
                    Text(text = "주차(P)하면 이번 여정의 곡이 요약됩니다.", fontSize = 18.sp, color = DsColors.muted)
                }
            }
            Card {
                Text(text = "음악 반응 세기", fontSize = 22.sp, color = DsColors.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((i, label) in listOf("부드럽게", "기본", "강하게").withIndex()) {
                        val sel = ui.feedbackLevel == i
                        OutlinedButton(onClick = { onFeedback(i) }, modifier = Modifier.height(60.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (sel) accent.copy(alpha = 0.22f) else Color.Transparent, contentColor = if (sel) accent else DsColors.muted)) { Text(label, fontSize = 20.sp) }
                    }
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text = "출발하면 자동 연주", fontSize = 22.sp, color = DsColors.text)
                        Text(text = "기어 D로 움직이기 시작하면 연주를 시작합니다 (기본 꺼짐)", fontSize = 18.sp, color = DsColors.muted)
                    }
                    Switch(checked = ui.autoStart, onCheckedChange = onAutoStart)
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text = "데모 시나리오", fontSize = 22.sp, color = DsColors.text)
                        Text(text = "차량 신호 없이 스크립트 주행으로 시연", fontSize = 18.sp, color = DsColors.muted)
                    }
                    Switch(checked = ui.demo, onCheckedChange = { onDemo(it, ui.demoScenarioId) })
                }
                if (ui.demo) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (s in Scenarios.all) {
                            val sel = ui.demoScenarioId == s.id
                            OutlinedButton(onClick = { onDemo(true, s.id) }, modifier = Modifier.height(56.dp),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (sel) accent.copy(alpha = 0.22f) else Color.Transparent, contentColor = if (sel) accent else DsColors.muted)) { Text(s.label, fontSize = 18.sp) }
                        }
                    }
                }
            }
            Card {
                Text(text = "신호 상태", fontSize = 22.sp, color = DsColors.muted)
                Spacer(Modifier.height(6.dp))
                StatRow("Vehicle SDK", if (ui.demo) "데모 중 (시나리오가 신호를 대신함)" else if (ui.vehicleLive) "수신 중" else "대기")
                StatRow("NaviHelper", if (ui.demo) "데모 중" else if (ui.naviLive) "수신 중" else "내비 미실행")
                StatRow("주행 제한(UX)", if (ui.restricted) "주행 중 화면" else "정차 화면")
                StatRow("뒷좌석", if (ui.rearSeat) "탑승 → 강도 '약'" else "비어 있음")
                StatRow("오디오 부하", if (ui.playing) "1코어의 %.1f%%".format(ui.engineLoad * 100) + (if (ui.light) " · 라이트 프로파일" else "") else "대기")
            }
            if (ui.history.isNotEmpty()) Card {
                Text(text = "여정의 곡 기록", fontSize = 22.sp, color = DsColors.muted)
                Spacer(Modifier.height(6.dp))
                for (t in ui.history.take(5)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(text = t.title, fontSize = 20.sp, color = DsColors.text, maxLines = 1)
                            Text(
                                text = "Flow ${(t.averageFlow * 100).toInt()} · ${t.durationSec / 60}분 ${t.durationSec % 60}초 · ${t.bars}마디 · 가라앉음 ${t.penalties}회",
                                fontSize = 16.sp, color = DsColors.muted, maxLines = 1,
                            )
                        }
                        Chip(text = t.genre.label, color = DsColors.accent(t.genre))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(title: String, subtitle: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(84.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) accent.copy(alpha = 0.22f) else Color.Transparent, contentColor = if (selected) accent else DsColors.muted),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, fontSize = 16.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(DsColors.surface, RoundedCornerShape(24.dp)).padding(20.dp)) { content() }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(text = label, fontSize = 19.sp, color = DsColors.muted, modifier = Modifier.width(190.dp))
        Text(text = value, fontSize = 19.sp, color = DsColors.text)
    }
}

/**
 * Lightweight visualizer (no FFT): 24 bars whose heights come from the engine's musical state
 * (density, bloom, pulse, beat phase) so it is cheap and only shown while parked.
 */
@Composable
fun Visualizer(engine: EngineState?, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(DsColors.surface, RoundedCornerShape(24.dp))) {
        val n = 24
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (n + 1)) / n
        val density = engine?.density ?: 1f
        val bloom = engine?.bloom ?: 0f
        val pulse = engine?.pulse ?: 0f
        val beat = engine?.beatInBar ?: 0f
        val flow = engine?.flow ?: 0.6f
        for (i in 0 until n) {
            val x = gap + i * (w + gap)
            val phase = beat * Math.PI.toFloat() * 2f
            val shape = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase + i * 0.55f)) * (0.3f + density / 3f)
            val hi = if (i > n * 0.6f) bloom else 1f
            val h = (size.height - gap * 2) * (shape * hi * (0.4f + 0.6f * flow)) * (1f - 0.5f * pulse)
            drawRoundRect(color = accent.copy(alpha = 0.35f + 0.65f * (i.toFloat() / n)), topLeft = Offset(x, size.height - gap - h), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
        }
    }
}
