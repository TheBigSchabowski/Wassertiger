plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

// Release key lives outside the repo; without it the release APK stays unsigned.
val releaseKey = file("${System.getProperty("user.home")}/.android/wassertiger-release.jks")
val releasePass = file("${System.getProperty("user.home")}/.android/wassertiger-release.pass")

android {
    namespace = "com.example.wassertiger"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.wassertiger"
        // turnScreenOn / showWhenLocked on activities need API 27
        minSdk = 27
        targetSdk = 36
        versionCode = 10
        versionName = libs.versions.app.get()
    }

    signingConfigs {
        if (releaseKey.exists() && releasePass.exists()) {
            create("release") {
                val pass = releasePass.readText().trim()
                storeFile = releaseKey
                storePassword = pass
                keyAlias = "wassertiger"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Home-screen widget lists on every API level (RemoteCollectionItems is native only from 31).
    implementation(libs.androidx.core.remoteviews)

    // Startup smoke test (./gradlew :androidApp:testDebugUnitTest), runs without a device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.11.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.2")
}
