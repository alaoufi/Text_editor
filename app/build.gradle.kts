plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uts.editor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uts.editor"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "2.11"
        vectorDrawables { useSupportLibrary = true }
        // Keep app lightweight: only ship the resources we use.
        resourceConfigurations += listOf("en", "ar")
        // Ship native OCR libs only for real-phone ABIs (drops x86/x86_64),
        // roughly halving the APK size added by Tesseract.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            // Prefer credentials injected via the environment (CI secrets / a
            // local keystore kept out of source control) so production builds are
            // signed with a private key. Fall back to the bundled self-signed dev
            // key only when no environment key is provided, so local and CI builds
            // still produce an installable APK out of the box.
            val envStorePath = System.getenv("RELEASE_STORE_FILE")
            val envStoreFile = envStorePath?.let { file(it) }
            if (envStoreFile != null && envStoreFile.exists()) {
                storeFile = envStoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            } else {
                // Self-signed dev key: lets local/CI builds produce an installable,
                // shrunk release APK. NOT for Play Store distribution.
                val ksFile = rootProject.file("release.keystore")
                if (ksFile.exists()) {
                    storeFile = ksFile
                    storePassword = "uts12345"
                    keyAlias = "uts"
                    keyPassword = "uts12345"
                }
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
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Lightweight charset detection (Mozilla universalchardet, maintained fork).
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")

    // PDF text extraction (Apache PDFBox, Android port) for opening PDFs as text.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // On-device OCR (Tesseract) for extracting editable text from scanned PDFs.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
