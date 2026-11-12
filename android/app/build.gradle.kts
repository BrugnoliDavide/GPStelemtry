plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gpstelemetry.app"
    compileSdk = 34

    defaultConfig {
        // NOTA: stesso applicationId del modulo wear — requisito del Data Layer
        // (le due app devono condividere package e firma per comunicare).
        applicationId = "com.gpstelemetry"
        minSdk = 26
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
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Mappe OpenStreetMap (nessuna chiave API richiesta)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Posizione fusa ad alta precisione
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Data Layer: ricezione frequenza cardiaca dallo smartwatch Wear OS
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
