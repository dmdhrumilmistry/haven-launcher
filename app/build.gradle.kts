import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the kotlin-android plugin is no longer
// applied here. The root build script still pins the Kotlin version on the classpath.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment so no secrets live in the repo.
// CI sets it from GitHub secrets; see .github/workflows/release.yml.
val releaseKeystore: String? = System.getenv("HAVEN_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.haven.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.haven.launcher"
        minSdk = 26
        targetSdk = 36
        // Bump both for every release (code = major*10000 + minor*100 + patch), and add
        // fastlane/metadata/android/en-US/changelogs/<versionCode>.txt. The release workflow checks
        // the tag matches. Kept as plain literals so F-Droid's update checker can read them.
        versionCode = 10300
        versionName = "1.3.0"
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
    // "standard" has no accessibility service and no notification listener. Google Play Protect
    // blocks sideloaded apps that declare either one in several countries, so this is the default
    // download. "full" adds double-tap to lock and notification badges, which need them; it's the
    // build for app stores, where that block doesn't apply.
    flavorDimensions += "features"
    productFlavors {
        create("standard") {
            dimension = "features"
            isDefault = true
            buildConfigField("boolean", "LOCK_GESTURE", "false")
            buildConfigField("boolean", "NOTIFICATION_BADGES", "false")
        }
        create("full") {
            dimension = "features"
            buildConfigField("boolean", "LOCK_GESTURE", "true")
            buildConfigField("boolean", "NOTIFICATION_BADGES", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // UI smoke tests run on the JVM with Robolectric: fast, no emulator needed in CI.
        unitTests.isIncludeAndroidResources = true
    }
    dependenciesInfo {
        // Keeps the APK free of the encrypted dependency blob, which F-Droid and others reject.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
