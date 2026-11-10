package io.drivesymphony.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The few shapes every screen is built from: a panel, a section label, a segmented control, a pill button, a tag,
 * a label/value row and a switch row. One radius family (20 / 16 / 12), one hairline, one accent.
 */

private val PanelShape = RoundedCornerShape(20.dp)
private val ControlShape = RoundedCornerShape(16.dp)
private val TagShape = RoundedCornerShape(12.dp)

/** A surface with a hairline, the only container the parked screen uses. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 22.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DsColors.surface, PanelShape)
            .border(1.dp, DsColors.line, PanelShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text = text, fontSize = 18.sp, color = DsColors.muted, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(DsColors.line))
}

/** Equal-width segments in one outlined track; the selected one is tinted with the accent. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    fontSize: Int = 22,
) {
    Row(
        modifier = modifier
            .height(height)
            .border(1.dp, DsColors.line, ControlShape)
            .clip(ControlShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((i, label) in options.withIndex()) {
            val sel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(TagShape)
                    .background(if (sel) DsColors.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (sel) DsColors.accent else DsColors.muted,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** Primary (accent fill) or quiet (raised surface) pill button. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, height: Dp = 64.dp, fontSize: Int = 22) {
    Box(
        modifier = modifier
            .height(height)
            .clip(ControlShape)
            .background(if (primary) DsColors.accent else DsColors.surfaceHi)
            .border(1.dp, if (primary) DsColors.accent else DsColors.line, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = fontSize.sp, fontWeight = FontWeight.Medium, color = if (primary) DsColors.bg else DsColors.text, maxLines = 1, softWrap = false)
    }
}

/** A small status mark (REC, DEMO, waiting for signals, genre of a past trip). */
@Composable
fun Tag(text: String, color: Color, fontSize: Int = 22) {
    Box(modifier = Modifier.background(color.copy(alpha = 0.14f), TagShape).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(text = text, fontSize = fontSize.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

/** Korean wraps between phrases, never inside a word ("급제동" must not become "급 / 제동"). */
val PhraseBreak = TextStyle(lineBreak = LineBreak(strategy = LineBreak.Strategy.HighQuality, strictness = LineBreak.Strictness.Normal, wordBreak = LineBreak.WordBreak.Phrase))

@Composable
fun StatRow(label: String, value: String, labelWidth: Dp = 190.dp) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(text = label, fontSize = 19.sp, color = DsColors.muted, modifier = Modifier.width(labelWidth))
        Text(text = value, fontSize = 19.sp, color = DsColors.text, modifier = Modifier.weight(1f), style = PhraseBreak, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = DsColors.bg, checkedTrackColor = DsColors.accent, checkedBorderColor = DsColors.accent,
            uncheckedThumbColor = DsColors.muted, uncheckedTrackColor = DsColors.surfaceHi, uncheckedBorderColor = DsColors.line,
        ),
    )
}

/** Title + one-line description on the left, a switch on the right. */
@Composable
fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 22.sp, color = DsColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 17.sp, color = DsColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        DsSwitch(checked, onCheckedChange)
    }
}
