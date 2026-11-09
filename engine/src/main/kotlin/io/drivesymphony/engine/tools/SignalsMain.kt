package io.drivesymphony.engine.tools

import io.drivesymphony.engine.SymphonyEngine
import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.scenario.Scenarios
import java.io.File

/**
 * Dumps a scenario's driving signals and the engine's flow/pulse per video frame as JSON, so the film's 3D cockpit
 * (video/ad) moves the wheel, pedals, stalk, wipers and cluster from exactly the numbers the app reacted to.
 *
 *   SignalsMain <out.json> [scenarioId=story] [fps=30]
 *
 * One object per frame: t, steer (deg), brake (0/1), signal (OFF/LEFT/RIGHT), wiper (0/1), cruise (0/1), gear,
 * speed (km/h), flow (0..1), pulse (0..1), event (name or null, only on the frame it fired), graced.
 * The engine is stepped with the same seed/genre as the preview renders; audio is not rendered (flow does not need it).
 */
object SignalsMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "signals.json" })
        val scenario = Scenarios.byId(args.getOrElse(1) { "story" })
        val fps = args.getOrElse(2) { "30" }.toInt()
        val dt = 1f / fps
        val first = scenario.signalsAt(0.0)
        val engine = SymphonyEngine(48000, Genre.LOFI, 20261030, hourOfDay = first.hourOfDay, roadName = first.roadName)
        val frames = (scenario.durationSec * fps).toInt()
        // engine.state is only published from render(): synthesise one video frame of audio per control tick, exactly
        // like the preview renderer, so flow, pulse, sags and events match what the app's audio thread produced
        val perFrame = 48000 / fps
        val block = FloatArray(perFrame * 2)
        val sb = StringBuilder()
        sb.append("{\"scenario\":\"").append(scenario.id).append("\",\"fps\":").append(fps)
            .append(",\"duration\":").append(scenario.durationSec).append(",\"frames\":[\n")
        for (i in 0 until frames) {
            val t = i.toDouble() / fps
            val s = scenario.signalsAt(t)
            engine.update(s, dt)
            engine.render(block, perFrame)
            val st = engine.state
            val fired = st.lastEvent != null && st.lastEventAgeSec < 1e-4f
            if (i > 0) sb.append(",\n")
            sb.append(String.format(java.util.Locale.ROOT,
                "{\"t\":%.4f,\"steer\":%.2f,\"brake\":%d,\"signal\":\"%s\",\"wiper\":%d,\"cruise\":%d,\"gear\":\"%s\",\"speed\":%.1f,\"flow\":%.4f,\"pulse\":%.4f,\"event\":%s,\"graced\":%s}",
                t, s.steeringAngleDeg, if (s.brake) 1 else 0, s.turnSignal.name,
                if (s.wiper.name != "OFF") 1 else 0, if (s.cruise.name == "ACTIVATED") 1 else 0, s.gear.name,
                s.speedKmh ?: 0f, st.flow, st.pulse,
                if (fired) "\"" + st.lastEvent!!.name + "\"" else "null", if (fired && st.graced) "true" else "false"))
        }
        sb.append("\n]}\n")
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        println("wrote ${out.absolutePath}: ${frames} frames @ $fps fps, scenario ${scenario.id} (${scenario.durationSec} s)")
    }
}
