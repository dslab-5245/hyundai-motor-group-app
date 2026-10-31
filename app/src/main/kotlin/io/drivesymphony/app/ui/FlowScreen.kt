package io.drivesymphony.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.drivesymphony.app.audio.ServiceUiState
import io.drivesymphony.engine.EngineState
import io.drivesymphony.engine.director.DriveEvent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * HUD-style glance screen shown while driving. Deliberately static (Pleos UI guide: no videos, animated images or
 * auto-scrolling text while driving; Music/Audio apps may show album-art-level visuals): one ring that only redraws on a
 * discrete step change, three lines of text, one large play/pause target (>= 76 dp), no tweens, no scrolling.
 */
@Composable
fun FlowScreen(engine: EngineState?, ui: ServiceUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val accent = DsColors.accent(ui.genre)
    // Quantised, non-animated values: flow in 5-point steps, the penalty halo as on/off, no breathing.
    val flow = ((engine?.flow ?: 0.6f) * 20f).roundToInt() / 20f
    val pulse = if ((engine?.pulse ?: 0f) > 0.25f) 1f else 0f
    val breath = 1f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DsColors.bg, DsColors.surfaceHi)))
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        // Pleos screen model: an app gets one (narrow, tall) window in portrait or two windows side by side in landscape.
        if (maxWidth < 980.dp) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                FlowRingBlock(flow, pulse, accent, breath, size = 240.dp)
                Spacer(Modifier.height(24.dp))
                FlowLines(engine, ui, accent, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                PlayButton(ui, accent, onToggle, Modifier.size(120.dp))
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                FlowRingBlock(flow, pulse, accent, breath, size = 300.dp)
                Spacer(Modifier.width(40.dp))
                FlowLines(engine, ui, accent, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(24.dp))
                PlayButton(ui, accent, onToggle, Modifier.size(132.dp))
            }
        }
    }
}

@Composable
private fun FlowRingBlock(flow: Float, pulse: Float, accent: Color, breath: Float, size: Dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        FlowRing(flow = flow, pulse = pulse, accent = accent, breath = breath, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "${(flow * 100).toInt()}", fontSize = 84.sp, fontWeight = FontWeight.Light, color = DsColors.text, lineHeight = 88.sp)
            Text(text = "FLOW", fontSize = 20.sp, color = DsColors.muted, letterSpacing = 4.sp)
        }
    }
}

/** Three lines + layer dots: title, genre/chord/BPM, current event. Nothing scrolls, nothing animates. */
@Composable
private fun FlowLines(engine: EngineState?, ui: ServiceUiState, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(text = engine?.title ?: "준비 중", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = DsColors.text, maxLines = 1, lineHeight = 40.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(text = ui.genre.label, color = accent)
            Spacer(Modifier.width(12.dp))
            Text(text = engine?.let { "${it.chordLabel}  ·  ${it.bpm.toInt()} BPM" } ?: "", fontSize = 26.sp, color = DsColors.muted)
        }
        Spacer(Modifier.height(18.dp))
        EventLine(engine)
        Spacer(Modifier.height(18.dp))
        LayerDots(engine?.layers ?: emptyList(), accent)
        if (ui.demo) {
            Spacer(Modifier.height(14.dp))
            Chip(text = "DEMO · 시나리오 재생 중", color = DsColors.warn)
        } else if (!ui.vehicleLive) {
            Spacer(Modifier.height(14.dp))
            Chip(text = "차량 신호 대기 중", color = DsColors.muted)
        }
    }
}

/** The single large control on the glance screen (>= 76 dp touch target). */
@Composable
private fun PlayButton(ui: ServiceUiState, accent: Color, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onToggle,
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (ui.playing) DsColors.surfaceHi else accent, contentColor = if (ui.playing) DsColors.text else DsColors.bg),
    ) {
        Text(text = if (ui.playing) "❚❚" else "▶", fontSize = 40.sp)
    }
}

@Composable
fun FlowRing(flow: Float, pulse: Float, accent: Color, breath: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 26.dp.toPx()
        val inset = stroke / 2 + 6.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        // track
        drawArc(color = DsColors.surfaceHi, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        // dip glow (penalty pulse): a soft warm halo that fades in < 1 s
        if (pulse > 0.02f) {
            drawArc(color = DsColors.warn.copy(alpha = 0.35f * pulse), startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke * 1.6f, cap = StrokeCap.Round))
        }
        // flow arc
        val sweep = max(4f, 270f * flow)
        drawArc(
            brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.9f))),
            startAngle = 135f, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round), alpha = breath,
        )
    }
}

@Composable
private fun EventLine(engine: EngineState?) {
    val ev = engine?.lastEvent
    val age = engine?.lastEventAgeSec ?: 999f
    val visible = ev != null && age < 4f
    val graced = engine?.graced == true
    val color = when {
        !visible -> DsColors.muted
        ev!!.isPenalty && graced -> DsColors.good
        ev.isPenalty -> DsColors.warn
        else -> DsColors.good
    }
    val text = when {
        !visible -> engine?.let { if (it.streakSec > 20f) "부드럽게 ${it.streakSec.toInt()}초째" else "주행에 맞춰 연주 중" } ?: ""
        ev == DriveEvent.SEATBELT_OFF -> "안전벨트 미착용 · 매면 음악이 다시 열려요"
        ev!!.isPenalty && graced -> "${ev.label} · 긴급 회피로 봤어요, 잠깐 숨만 고를게요"
        ev.isPenalty -> "${ev.label} · 음악이 잠시 가라앉아요"
        ev == DriveEvent.SIGNALED_TURN -> "깜빡이 좋아요 · 레이어가 열려요"
        else -> ev.label
    }
    Text(text = text, fontSize = 26.sp, color = color, maxLines = 1)
}

@Composable
private fun LayerDots(layers: List<String>, accent: Color) {
    val all = listOf("pad" to "패드", "bass" to "베이스", "keys" to "키", "drums" to "드럼", "hats" to "하이햇", "melody" to "멜로디", "vinyl" to "바이닐", "rain" to "비", "drone" to "드론")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((id, label) in all) {
            val on = layers.contains(id)
            Box(
                modifier = Modifier
                    .background(if (on) accent.copy(alpha = 0.22f) else DsColors.surface, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(text = label, fontSize = 18.sp, color = if (on) accent else DsColors.muted) }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(modifier = Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = text, fontSize = 20.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
