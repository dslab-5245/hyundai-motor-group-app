package io.drivesymphony.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.drivesymphony.app.audio.SymphonyService
import io.drivesymphony.app.ui.ComposerScreen
import io.drivesymphony.app.ui.DriveSymphonyTheme
import io.drivesymphony.app.ui.DsColors
import io.drivesymphony.app.ui.FlowScreen
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.director.Gear
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val serviceFlow = MutableStateFlow<SymphonyService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceFlow.value = (binder as? SymphonyService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) { serviceFlow.value = null }
    }

    private val pickTrack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { serviceFlow.value?.setRemixTrack(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, SymphonyService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        setContent {
            DriveSymphonyTheme {
                val service by serviceFlow.collectAsState()
                Root(service, onPickTrack = { pickTrack.launch(arrayOf("audio/*")) }, onShare = ::share)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun share(summary: TripSummary) {
        val text = buildString {
            append("🎧 ${summary.title}\n")
            append("평균 Flow ${(summary.averageFlow * 100).toInt()} · 최장 부드러운 구간 ${summary.bestStreakSec.toInt()}초 · 멜로디 ${summary.bloomSec.toInt()}초\n")
            append("${summary.genre.label} · ${summary.keyLabel} · ${summary.bpm.toInt()} BPM · ${summary.bars}마디\n")
            append("#DriveSymphony #PleosAppMarket")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "여정 공유"))
    }
}

@Composable
private fun Root(service: SymphonyService?, onPickTrack: () -> Unit, onShare: (TripSummary) -> Unit) {
    if (service == null) {
        Box(Modifier.fillMaxSize().background(DsColors.bg)) { Text("Drive Symphony 준비 중…", color = DsColors.muted, fontSize = 26.sp, modifier = Modifier.padding(40.dp)) }
        return
    }
    val engine by service.engineState.collectAsState()
    val ui by service.ui.collectAsState()
    // Driving gate: UX restrictions from the car, or (if the car library is missing) gear != PARK while moving.
    val parkedByGear = engine?.moving == false
    val driving = ui.restricted || (engine?.moving == true)
    var tab by remember { mutableStateOf(0) }

    if (driving) {
        FlowScreen(engine = engine, ui = ui, onToggle = service::toggle)
        return
    }
    Column(Modifier.fillMaxSize().background(DsColors.bg)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp)) {
            TextButton(onClick = { tab = 0 }) { Text("연주", fontSize = 22.sp, color = if (tab == 0) DsColors.text else DsColors.muted) }
            TextButton(onClick = { tab = 1 }) { Text("작곡실", fontSize = 22.sp, color = if (tab == 1) DsColors.text else DsColors.muted) }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) FlowScreen(engine = engine, ui = ui, onToggle = service::toggle)
            else ComposerScreen(
                engine = engine, ui = ui,
                onToggle = service::toggle,
                onGenre = service::setGenre,
                onFeedback = service::setFeedbackLevel,
                onDemo = { on, id -> service.setDemo(on, id) },
                onPickTrack = onPickTrack,
                onClearTrack = { service.setRemixTrack(null) },
                onShare = onShare,
            )
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = parkedByGear || Gear.PARK == Gear.PARK
}
