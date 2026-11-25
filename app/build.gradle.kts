import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Eigener Signierschlüssel (wird von build-apk.sh angelegt) – nötig, damit Updates
// über die bereits installierte App drüber installiert werden können.
val signingProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "de.comaxy.familienplaner"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.comaxy.familienplaner"
        minSdk = 34          // Android 14
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"
        // Datum des Builds (wird auf der Startseite angezeigt)
        buildConfigField("String", "BUILD_DATE",
            "\"" + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "\"")
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: verkleinert und verschleiert den Code, entfernt Ungenutztes
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Passkeys (WebAuthn) im eingebetteten Browser
    implementation("androidx.webkit:webkit:1.14.0")
}
