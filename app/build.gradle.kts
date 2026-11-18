plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.webmirror.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.webmirror.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // TODO: replace with the real GitHub "owner/repo" once the repository exists (404 is treated as "no update information").
        buildConfigField("String", "UPDATE_REPO", "\"danyk20/WebMirror\"")
    }

    flavorDimensions += "store"
    productFlavors {
        // APK in GitHub Releases; checks GitHub for updates and can install them itself.
        create("github") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"github\"")
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        // Google Play: updates come from Play; no self-update code path, no REQUEST_INSTALL_PACKAGES.
        create("play") {
            dimension = "store"
            buildConfigField("String", "STORE", "\"play\"")
            buildConfigField("boolean", "SELF_UPDATE", "false")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.zxing.core)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
}

// With product flavors AGP no longer creates this name; keep the documented command working.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the debug unit tests of every store flavor."
    dependsOn("testGithubDebugUnitTest", "testPlayDebugUnitTest")
}
