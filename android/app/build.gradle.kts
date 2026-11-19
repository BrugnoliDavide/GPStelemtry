import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * sherpa-onnx (motore della voce neurale Piper, offline). Non è su Maven
 * Central: l'AAR ufficiale si scarica dalle release GitHub del progetto la
 * prima volta che si compila e se ne verifica l'impronta SHA-256, così il
 * binario (38 MB) non finisce nel repository.
 */
val sherpaVersion = "1.13.8"
val sherpaSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256")
    .digest(f.readBytes()).joinToString("") { "%02x".format(it) }

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/" +
        "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
    logger.lifecycle("Scarico sherpa-onnx $sherpaVersion…")
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.parentFile, sherpaAar.name + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    check(sha256(tmp) == sherpaSha256) { "Impronta SHA-256 di sherpa-onnx non valida" }
    tmp.renameTo(sherpaAar)
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Telefoni ARM (64 e 32 bit) ed emulatori x86_64: escluso x86 per peso.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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

    // Test su JVM con Robolectric (rendering delle schermate senza emulatore).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    // Voce al pilota neurale (Piper) e decompressione del modello vocale (.tar.bz2)
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.26.2")
    // Server della community: HTTP (anche PATCH) e coda dei giri in background.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Prova della voce neurale su dispositivo/emulatore (motore nativo).
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Test del contratto contro il server vero (RealServerContractTest):
//   ./gradlew :app:testDebugUnitTest -PgpsTestServer=http://127.0.0.1:18080/v1
tasks.withType<Test>().configureEach {
    (project.findProperty("gpsTestServer") as String?)?.let { systemProperty("gpsTestServer", it) }
}
