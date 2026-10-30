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
