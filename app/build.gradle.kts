plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.veenstra.discgolfscore"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.veenstra.discgolfscore"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
        }
        named("test") {
            kotlin.directories += "src/test/kotlin"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.input)
    debugImplementation(libs.androidx.wear.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json, not android.jar's stub — see CloudBackup.kt's class doc and
    // CLOUD_SAVES.md section 5 item 4. Production code uses the framework's built-in org.json;
    // this is test-only on purpose.
    testImplementation(libs.org.json)
}
