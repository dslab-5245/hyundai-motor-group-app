package io.drivesymphony.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.drivesymphony.app.audio.PlayMode
import io.drivesymphony.app.audio.ServiceUiState
import io.drivesymphony.engine.EngineState
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.math.sin

/**
 * Parked screen. Left: now playing, visualizer, transport + genre, mode. Right (scrolls): the trip's composition,
 * response strength, settings, signal status, history. Every block is a [Panel]; every choice is a [Segmented].
 */
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
    onDemoTrack: () -> Unit,
    onShare: (TripSummary) -> Unit,
    onAutoStart: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize().background(DsColors.bg).padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 28.dp)) {
        // ---- left: now playing + visualizer + transport + mode ---------------------------------------
        Column(modifier = Modifier.weight(1.2f)) {
            Text(text = engine?.title ?: "Drive Symphony", fontSize = 36.sp, fontWeight = FontWeight.Medium, color = DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                text = engine?.let { "${it.keyLabel}  ·  ${it.chordLabel} → ${it.nextChordLabel}  ·  ${it.bpm.toInt()} BPM  ·  ${it.bar}마디" } ?: "주행이 곡을 만듭니다",
                fontSize = 22.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Visualizer(engine, modifier = Modifier.fillMaxWidth().height(160.dp))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(if (ui.playing) "일시정지" else "연주 시작", onToggle, Modifier.width(200.dp), primary = true, height = 72.dp, fontSize = 24)
                Segmented(Genre.values().map { it.label }, Genre.values().indexOf(ui.genre), { onGenre(Genre.values()[it]) }, Modifier.weight(1f), height = 72.dp)
            }
            Spacer(Modifier.height(18.dp))
            // mode: the two driver modes the concept defines
            Panel {
                SectionLabel("모드")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeTile("음악 만들기", "주행이 곡을 생성", ui.mode == PlayMode.GENERATIVE, Modifier.weight(1f)) { if (ui.mode == PlayMode.REMIX) onClearTrack() }
                    ModeTile("기존 음악 변주하기", ui.remixTrackTitle ?: "내 음원 선택 (USB · 로컬)", ui.mode == PlayMode.REMIX, Modifier.weight(1f)) { onPickTrack() }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "▶  샘플 곡으로 체험하기  ·  Bach, Goldberg Aria (CC0)",
                    fontSize = 19.sp, color = DsColors.accent, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDemoTrack() }.padding(vertical = 6.dp),
                )
                ui.remixError?.let { Spacer(Modifier.height(4.dp)); Text(text = "재생 오류: $it", fontSize = 17.sp, color = DsColors.warn, maxLines = 2) }
                if (ui.mode == PlayMode.REMIX) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = "같은 Flow 규칙으로 로우패스 · 워블 · 스테레오 폭이 변합니다 (스트리밍 앱 음원은 지원하지 않음)", fontSize = 17.sp, color = DsColors.muted, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.width(28.dp))

        // ---- right: composition + settings ---------------------------------------------------------
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val summary = ui.lastSummary
            Panel {
                SectionLabel(if (summary != null) "지난 여정의 곡" else "이번 여정")
                Spacer(Modifier.height(8.dp))
                if (summary != null) {
                    Text(text = summary.title, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    StatRow("평균 Flow", "${(summary.averageFlow * 100).toInt()}")
                    StatRow("최장 부드러운 구간", "${summary.bestStreakSec.toInt()}초")
                    StatRow("멜로디가 열린 시간", "${summary.bloomSec.toInt()}초")
                    StatRow("연주 길이", "${(summary.durationSec / 60).toInt()}분 ${(summary.durationSec % 60).toInt()}초  ·  ${summary.bars}마디")
                    val penalties = summary.eventCounts.filterKeys { it.isPenalty }
                    StatRow("음악이 가라앉은 순간", if (penalties.isEmpty()) "없음" else penalties.entries.joinToString(", ") { "${it.key.label} ${it.value}" })
                    Spacer(Modifier.height(14.dp))
                    PillButton("여정 요약 공유", { onShare(summary) }, height = 60.dp, fontSize = 20)
                } else {
                    Text(text = engine?.let { "Flow ${(it.flow * 100).toInt()}  ·  ${if (it.streakSec > 0) "부드럽게 ${it.streakSec.toInt()}초째" else "대기 중"}" } ?: "-", fontSize = 24.sp, color = DsColors.text)
                    Spacer(Modifier.height(4.dp))
                    Text(text = "주차(P)하면 이번 여정의 곡이 요약됩니다.", fontSize = 17.sp, color = DsColors.muted)
                }
            }
            Panel {
                SectionLabel("음악 반응 세기")
                Spacer(Modifier.height(12.dp))
                Segmented(listOf("부드럽게", "기본", "강하게"), ui.feedbackLevel, onFeedback, Modifier.fillMaxWidth(), height = 58.dp, fontSize = 20)
            }
            Panel(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                    SettingRow("출발하면 자동 연주", "기어 D로 움직이기 시작하면 연주를 시작합니다", ui.autoStart, onAutoStart)
                    Hairline()
                    SettingRow("데모 시나리오", "차량 신호 없이 스크립트 주행으로 시연", ui.demo) { onDemo(it, ui.demoScenarioId) }
                    if (ui.demo) {
                        val ids = Scenarios.all.map { it.id }
                        Segmented(Scenarios.all.map { it.label }, ids.indexOf(ui.demoScenarioId), { onDemo(true, ids[it]) }, Modifier.fillMaxWidth().padding(bottom = 14.dp), height = 54.dp, fontSize = 18)
                    }
                }
            }
            Panel {
                SectionLabel("신호 상태")
                Spacer(Modifier.height(8.dp))
                StatRow("Vehicle SDK", if (ui.demo) "데모 중 · 시나리오 신호" else if (ui.vehicleLive) "수신 중" else "대기")
                StatRow("NaviHelper", if (ui.demo) "데모 중" else if (ui.naviLive) "수신 중" else "내비 미실행")
                StatRow("주행 제한(UX)", if (ui.restricted) "주행 중 화면" else "정차 화면")
                StatRow("뒷좌석", if (ui.rearSeat) "탑승 → 강도 '약'" else "비어 있음")
                StatRow("오디오 부하", if (ui.playing) "1코어의 %.1f%%".format(ui.engineLoad * 100) + (if (ui.light) " · 라이트 프로파일" else "") else "대기")
            }
            if (ui.history.isNotEmpty()) Panel {
                SectionLabel("여정의 곡 기록")
                Spacer(Modifier.height(6.dp))
                for ((i, t) in ui.history.take(5).withIndex()) {
                    if (i > 0) Hairline()
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(text = t.title, fontSize = 20.sp, color = DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "Flow ${(t.averageFlow * 100).toInt()}  ·  ${t.durationSec / 60}분 ${t.durationSec % 60}초  ·  ${t.bars}마디  ·  가라앉음 ${t.penalties}회",
                                fontSize = 16.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Tag(t.genre.label, DsColors.muted, fontSize = 18)
                    }
                }
            }
        }
    }
}

/** One of the two driver modes: a tile with a title and a one-line description, outlined in the accent when chosen. */
@Composable
private fun ModeTile(title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .height(84.dp)
            .clip(shape)
            .background(if (selected) DsColors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) DsColors.accent else DsColors.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Medium, color = if (selected) DsColors.accent else DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 16.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Lightweight visualizer (no FFT): 24 bars whose heights come from the engine's musical state
 * (density, bloom, pulse, beat phase) so it is cheap and only shown while parked.
 */
@Composable
fun Visualizer(engine: EngineState?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Canvas(modifier = modifier.background(DsColors.surface, shape).border(1.dp, DsColors.line, shape).padding(16.dp)) {
        val n = 24
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val density = engine?.density ?: 1f
        val bloom = engine?.bloom ?: 0f
        val pulse = engine?.pulse ?: 0f
        val beat = engine?.beatInBar ?: 0f
        val flow = engine?.flow ?: 0.6f
        for (i in 0 until n) {
            val x = i * (w + gap)
            val phase = beat * Math.PI.toFloat() * 2f
            val shapeF = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase + i * 0.55f)) * (0.3f + density / 3f)
            val hi = if (i > n * 0.6f) bloom else 1f
            val raw = size.height * (shapeF * hi * (0.4f + 0.6f * flow)) * (1f - 0.5f * pulse)
            // a closed layer (melody not yet bloomed) is a dim stub, not a bright dot
            val closed = raw < w * 1.5f
            val h = if (closed) w * 1.5f else raw
            val color = if (closed) DsColors.line else DsColors.accent.copy(alpha = 0.30f + 0.70f * (i.toFloat() / n))
            drawRoundRect(color = color, topLeft = Offset(x, size.height - h), size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2))
        }
    }
}
