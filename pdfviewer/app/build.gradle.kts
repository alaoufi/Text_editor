plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uts.pdfviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uts.pdfviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.9"
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en", "ar")
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "uts12345"
                keyAlias = "uts"
                keyPassword = "uts12345"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    // Serves the bundled pdf.js assets + the opened PDF to the WebView locally.
    implementation("androidx.webkit:webkit:1.11.0")
    // Document scanner (camera capture, auto edge-crop, cleanup filters → PDF).
    // Delivered at runtime by Google Play services, so it adds little to the APK.
    // Used only when available; otherwise the built-in GMS-free scanner is used.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Reads photo EXIF orientation for the built-in scanner.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
}
