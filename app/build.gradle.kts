plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing and versioning come from the environment so no secrets live in the repo.
// CI sets these from GitHub secrets and the release tag; see .github/workflows/release.yml.
val releaseKeystore: String? = System.getenv("HAVEN_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val ciVersionName: String? = System.getenv("HAVEN_VERSION_NAME")?.takeIf { it.isNotBlank() }
val ciVersionCode: Int? = System.getenv("HAVEN_VERSION_CODE")?.toIntOrNull()

android {
    namespace = "app.haven.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.haven.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HAVEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HAVEN_KEY_ALIAS")
                keyPassword = System.getenv("HAVEN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without a keystore the release APK is left unsigned (fine for checking that it builds).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    dependenciesInfo {
        // Keeps the APK free of the encrypted dependency blob, which F-Droid and others reject.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
