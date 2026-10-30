// Root build file. Plugins are applied per module (see engine/ and app/).
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
