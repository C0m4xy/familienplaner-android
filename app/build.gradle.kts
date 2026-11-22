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
    compileSdk = 35

    defaultConfig {
        applicationId = "de.comaxy.familienplaner"
        minSdk = 23          // Android 6.0
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

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
