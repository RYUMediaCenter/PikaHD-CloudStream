rootProject.name = "PikaHDCloudStream"

val disabled = emptyList<String>()
rootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    if (dir.name !in disabled && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}
