import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    id("dev.nucleusframework")
}

dependencies {
    implementation(nucleus.desktop.currentOs)
    implementation(libs.compose.material3)
}

nucleus.application {
    mainClass = "com.example.extralauncher.MainKt"

    additionalLaunchers {
        create("Cli") {
            mainClass = "com.example.extralauncher.CliKt"
            winConsole = true
        }
    }

    nativeDistributions {
        targetFormats(TargetFormat.AppImage, TargetFormat.Exe)
        appName = "Nucleus ExtraLauncher Demo"
        packageName = "NucleusExtraLauncherDemo"
        packageVersion = "1.0.0"
    }
}
