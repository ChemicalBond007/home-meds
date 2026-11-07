plugins {
    alias(libs.plugins.android.application)
}

// The app is a shell around the deployed site. For local development, pass
// -PhomeMedsBaseUrl=http://127.0.0.1:5173 and run `adb reverse tcp:5173 tcp:5173`.
val baseUrl = providers.gradleProperty("homeMedsBaseUrl").getOrElse("https://your-domain.example.com").trimEnd('/')
require(Regex("^(https://[A-Za-z0-9.-]+(:\\d+)?|http://(127\\.0\\.0\\.1|localhost)(:\\d+)?)$").matches(baseUrl)) {
    "homeMedsBaseUrl must be an https origin, or a local http origin for development"
}

android {
    namespace = "com.homemeds.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.homemeds.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("zh-rCN")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
