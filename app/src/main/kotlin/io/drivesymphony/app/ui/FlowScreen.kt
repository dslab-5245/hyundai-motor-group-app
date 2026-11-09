package io.drivesymphony.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.drivesymphony.app.audio.PlayMode
import io.drivesymphony.app.audio.ServiceUiState
import io.drivesymphony.engine.EngineState
import io.drivesymphony.engine.director.DriveEvent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Glance screen shown while driving. Deliberately static (Pleos UI guide: no videos, animated images or auto-scrolling
 * text while driving; Music/Audio apps may show album-art-level visuals): one ring that only redraws on a discrete step
 * change, a title, a meta line, one status line, a nine-segment layer meter, one large play/pause target (>= 76 dp).
 * No tweens, no scrolling, nothing below 22 sp.
 */
@Composable
fun FlowScreen(engine: EngineState?, ui: ServiceUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    // Quantised, non-animated values: flow in 5-point steps, the dip halo as on/off, no breathing.
    val flow = ((engine?.flow ?: 0.6f) * 20f).roundToInt() / 20f
    val pulse = (engine?.pulse ?: 0f) > 0.25f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(DsColors.bg)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        // Pleos screen model: an app gets one (narrow, tall) window in portrait or two windows side by side in landscape.
        if (maxWidth < 980.dp) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                FlowRingBlock(flow, pulse, size = 240.dp)
                Spacer(Modifier.height(24.dp))
                FlowLines(engine, ui, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                PlayButton(ui.playing, onToggle, Modifier.size(120.dp))
            }
        } else {
            // the ring stays at the same place and size as in the demo film's layout (RING in video/ad/src/layout.ts)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                FlowRingBlock(flow, pulse, size = 300.dp)
                Spacer(Modifier.width(40.dp))
                FlowLines(engine, ui, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(24.dp))
                PlayButton(ui.playing, onToggle, Modifier.size(132.dp))
            }
        }
    }
}

@Composable
private fun FlowRingBlock(flow: Float, pulse: Boolean, size: Dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        FlowRing(flow = flow, pulse = pulse, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "${(flow * 100).toInt()}", fontSize = 84.sp, fontWeight = FontWeight.Light, color = DsColors.text, lineHeight = 88.sp)
            Text(text = "FLOW", fontSize = 20.sp, color = DsColors.muted, letterSpacing = 5.sp)
        }
    }
}

/** Title, meta, status, layer meter, tags. Nothing scrolls, nothing animates. */
@Composable
private fun FlowLines(engine: EngineState?, ui: ServiceUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        val remix = ui.mode == PlayMode.REMIX
        Text(
            text = if (remix) (ui.remixTrackTitle ?: "내 음원 변주") else (engine?.title ?: "준비 중"),
            fontSize = 36.sp, fontWeight = FontWeight.Medium, color = DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 42.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (remix) "변주  ·  내 음원을 같은 주행 규칙으로" else engine?.let { "${ui.genre.label}  ·  ${it.chordLabel}  ·  ${it.bpm.toInt()} BPM" } ?: ui.genre.label,
            fontSize = 24.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(26.dp))
        StatusLine(engine)
        Spacer(Modifier.height(26.dp))
        if (remix) {
            Text(text = "원곡 · 로우패스 · 테이프 새그 · 스테레오 폭이 주행을 따릅니다", fontSize = 22.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            LayerMeter(engine?.layers ?: emptyList())
        }
        val tags = buildList {
            if (ui.recording) add("● REC" to DsColors.rec)   // developer recording tap: also the sync mark for demo videos
            if (ui.demo) add("DEMO  ·  시나리오 재생" to DsColors.warn)
            else if (!ui.vehicleLive) add("차량 신호 대기" to DsColors.muted)
        }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { for ((t, c) in tags) Tag(t, c) }
        }
    }
}

/** One line + a dot: what the music is doing right now and why. */
@Composable
private fun StatusLine(engine: EngineState?) {
    val ev = engine?.lastEvent
    val age = engine?.lastEventAgeSec ?: 999f
    val visible = ev != null && age < 4f
    val graced = engine?.graced == true
    val streak = engine?.streakSec ?: 0f
    val color = when {
        !visible -> if (streak > 20f) DsColors.good else DsColors.muted
        ev!!.isPenalty && graced -> DsColors.good
        ev.isPenalty -> DsColors.warn
        else -> DsColors.good
    }
    val text = when {
        !visible -> engine?.let { if (streak > 20f) "부드럽게 ${streak.toInt()}초째" else "주행에 맞춰 연주 중" } ?: ""
        ev == DriveEvent.SEATBELT_OFF -> "안전벨트 미착용 · 매면 음악이 다시 열려요"
        ev!!.isPenalty && graced -> "${ev.label} · 긴급 회피로 봤어요, 잠깐 숨만 고를게요"
        ev.isPenalty -> "${ev.label} · 음악이 잠시 가라앉아요"
        ev == DriveEvent.SIGNALED_TURN -> "깜빡이 좋아요 · 레이어가 열려요"
        else -> ev.label
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(14.dp))
        Text(text = text, fontSize = 26.sp, color = if (visible || streak > 20f) color else DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The single large control on the glance screen (>= 76 dp touch target); the glyph is drawn, not a font symbol. */
@Composable
private fun PlayButton(playing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (playing) DsColors.surface else DsColors.accent)
            .border(1.dp, if (playing) DsColors.line else DsColors.accent, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = if (playing) DsColors.text else DsColors.bg
        Canvas(Modifier.size(40.dp)) {
            if (playing) {
                val w = size.width * 0.26f
                val gap = size.width * 0.18f
                val x0 = (size.width - 2 * w - gap) / 2
                drawRoundRect(glyph, topLeft = Offset(x0, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(w / 3))
                drawRoundRect(glyph, topLeft = Offset(x0 + w + gap, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(w / 3))
            } else {
                val p = Path().apply {
                    moveTo(size.width * 0.14f, 0f)
                    lineTo(size.width * 0.98f, size.height / 2)
                    lineTo(size.width * 0.14f, size.height)
                    close()
                }
                drawPath(p, glyph)
            }
        }
    }
}

@Composable
fun FlowRing(flow: Float, pulse: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 18.dp.toPx()
        val inset = stroke / 2 + 10.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        // track
        drawArc(color = DsColors.surfaceHi, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        // dip halo (penalty pulse): a soft amber glow, on or off
        if (pulse) {
            drawArc(color = DsColors.warn.copy(alpha = 0.16f), startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke * 1.7f, cap = StrokeCap.Round))
        }
        // flow arc
        val sweep = max(4f, 270f * flow)
        drawArc(color = if (pulse) DsColors.warn else DsColors.accent, startAngle = 135f, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Nine segments, one per layer, lit when the layer is playing; the names of the lit ones on the line below. */
@Composable
private fun LayerMeter(layers: List<String>) {
    val all = listOf("pad" to "패드", "bass" to "베이스", "keys" to "키", "drums" to "드럼", "hats" to "하이햇", "melody" to "멜로디", "vinyl" to "바이닐", "rain" to "비", "drone" to "드론")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, _) in all) {
            val on = layers.contains(id)
            Box(Modifier.weight(1f).height(6.dp).background(if (on) DsColors.accent else DsColors.surfaceHi, RoundedCornerShape(3.dp)))
        }
    }
    Spacer(Modifier.height(12.dp))
    val names = all.filter { layers.contains(it.first) }.joinToString("  ·  ") { it.second }
    Text(text = if (names.isEmpty()) "레이어가 쉬고 있어요" else names, fontSize = 22.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
