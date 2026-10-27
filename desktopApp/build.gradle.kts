import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// FFmpeg native binaries (H.264 decoder) only for the OS we're building on, to keep the package small.
val nativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val cpu = if (arch == "aarch64" || arch == "arm64") "arm64" else "x86_64"
    when {
        os.contains("win") -> "windows-$cpu"
        os.contains("mac") -> "macosx-$cpu"
        else -> "linux-$cpu"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier(nativeClassifier) })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(nativeClassifier) })
}

compose.desktop {
    application {
        mainClass = "cordobot.pacificrim.tvshare.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Tvshare"
            packageVersion = "1.0.0"
            description = "Recibe en la PC lo que se ve en tu Android TV"
            vendor = "Cordobot"
            modules("java.instrument", "java.management", "java.prefs", "jdk.unsupported")
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Keep this fixed so new versions upgrade the installed app instead of duplicating it.
                upgradeUuid = "78eeb57b-1764-419c-8062-c738d30482bd"
            }
        }
    }
}

