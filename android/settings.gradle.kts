pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GPSTelemetry"

// Il modulo core (logica condivisa) è Kotlin puro e compilabile ovunque.
include(":core")

// I moduli Android richiedono l'SDK (Android Studio crea local.properties).
if (System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(settingsDir, "local.properties").exists()
) {
    include(":app")
    include(":wear")
}
