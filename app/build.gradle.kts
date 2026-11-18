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
        // Single source of truth for the version. versionCode = major * 10000 + minor * 100 + patch (0.1.0 -> 100, 1.2.3 -> 10203).
        // The release workflow requires the git tag (v<versionName>) to equal versionName.
        versionCode = 100
        versionName = "0.1.0"
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

    // Release signing. The key never lives in this repository. Its location and password come from, in this order:
    //  - environment variables SIGNING_KEYSTORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD (CI), or
    //  - Gradle properties WEBMIRROR_RELEASE_STORE_FILE, ..._STORE_PASSWORD, ..._KEY_ALIAS, ..._KEY_PASSWORD
    //    (e.g. in ~/.gradle/gradle.properties on the developer's machine).
    // Without them, release builds are simply left unsigned.
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() }
    val releaseStoreFile = signingValue("SIGNING_KEYSTORE_FILE", "WEBMIRROR_RELEASE_STORE_FILE")
    val releaseStorePassword = signingValue("SIGNING_STORE_PASSWORD", "WEBMIRROR_RELEASE_STORE_PASSWORD")
    if (releaseStoreFile != null && releaseStorePassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = signingValue("SIGNING_KEY_ALIAS", "WEBMIRROR_RELEASE_KEY_ALIAS") ?: "webmirror"
                keyPassword = signingValue("SIGNING_KEY_PASSWORD", "WEBMIRROR_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
