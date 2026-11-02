plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

application {
    mainClass.set("io.drivesymphony.engine.tools.PreviewMain")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// Renders demo WAV files (per genre: safe vs. erratic driving) so the concept can be heard without a car.
// Usage: gradlew :engine:renderPreviews   -> writes to <repo>/previews/*.wav
tasks.register<JavaExec>("renderPreviews") {
    group = "drive-symphony"
    description = "Render preview WAVs for each genre and driving scenario."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.drivesymphony.engine.tools.PreviewMain")
    args(rootProject.layout.projectDirectory.dir("previews").asFile.absolutePath)
}

// Renders a 12-second WAV per sampled instrument of the bundled FluidR3 bank (scale + chords, drum pattern) and the
// synth EP playing the same phrase as a level reference.
// Usage: gradlew :engine:samplerDemo   -> writes to engine/build/sampler-demo/*.wav
tasks.register<JavaExec>("samplerDemo") {
    group = "drive-symphony"
    description = "Render demo WAVs for each sampled instrument in the bundled sample bank."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.drivesymphony.engine.tools.SamplerDemoMain")
    args(layout.buildDirectory.dir("sampler-demo").get().asFile.absolutePath)
}
