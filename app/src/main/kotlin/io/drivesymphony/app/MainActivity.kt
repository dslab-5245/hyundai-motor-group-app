package io.drivesymphony.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.drivesymphony.app.audio.SymphonyService
import io.drivesymphony.app.ui.ComposerScreen
import io.drivesymphony.app.ui.DriveSymphonyTheme
import io.drivesymphony.app.ui.DsColors
import io.drivesymphony.app.ui.FlowScreen
import io.drivesymphony.app.share.ShareCard
import io.drivesymphony.engine.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, SymphonyService::class.java)
        startService(svc)   // started + bound: the service outlives the activity while playing (Pleos media-app lifecycle rule)
        bindService(svc, connection, Context.BIND_AUTO_CREATE)
        forwardGleo(intent)
        // API 33+: the foreground media notification is only shown once the user grants POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            DriveSymphonyTheme {
                val service by serviceFlow.collectAsState()
                Root(service, onPickTrack = { pickTrack.launch(arrayOf("audio/*")) }, onShare = ::share)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardGleo(intent)
    }

    /** Gleo function calls may arrive at the activity (component "activity"); hand them to the service. */
    private fun forwardGleo(intent: Intent?) {
        val action = intent?.action ?: return
        if (action in GLEO_ACTIONS) startService(Intent(this, SymphonyService::class.java).setAction(action).putExtras(intent))
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    companion object {
        private val GLEO_ACTIONS = setOf(SymphonyService.GLEO_START, SymphonyService.GLEO_SET_GENRE, SymphonyService.GLEO_HOW_IS_MY_DRIVING, SymphonyService.GLEO_PAUSE)
    }

    private fun share(summary: TripSummary) {
        // image card first; plain text if the card cannot be written (e.g. no cache dir)
        // the 1080x1350 card is rendered off the main thread; the chooser opens when it is written
        lifecycleScope.launch {   // dies with the activity, so no chooser is launched from a finished activity
            val intent = withContext(Dispatchers.IO) {
                runCatching { ShareCard.shareIntent(this@MainActivity, summary) }.getOrElse {
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ShareCard.caption(summary))
                }
            }
            startActivity(Intent.createChooser(intent, "여정 공유"))
        }
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
    // Driving gate: UX restrictions from the car, or (if the car library is missing) the engine's moving flag (gear D / speed).
    // EngineState is published from the audio thread, so its moving flag is only trusted while playing.
    val driving = ui.restricted || (ui.playing && engine?.moving == true)
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
                onAutoStart = service::setAutoStart,
            )
        }
    }
}
