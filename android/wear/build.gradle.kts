plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gpstelemetry.wear"
    compileSdk = 34

    defaultConfig {
        // Stesso applicationId dell'app telefono: requisito del Data Layer
        // (le due app devono condividere package e firma per comunicare).
        applicationId = "com.gpstelemetry"
        minSdk = 30 // Wear OS 3 (Galaxy Watch 4 e successivi, Pixel Watch, ...)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Health Services: frequenza cardiaca dal sensore dell'orologio
    implementation("androidx.health:health-services-client:1.0.0-rc02")
    // Data Layer: invio dei campioni al telefono
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
