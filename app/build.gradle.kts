plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.attey.governor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.attey.governor"
        // Rooted devices skew old. 26 covers Android 8+ without giving up modern APIs.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Left unsigned on purpose: shipping a debug-signed release would let
            // anyone with the public debug key push an update over it. Sign with
            // your own key, or just use the debug build.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        lintConfig = file("lint.xml")
    }
    packaging {
        resources.excludes += "DebugProbesKt.bin"
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Last stable Compose line that builds with API 36 / AGP 8. Compose 1.12
    // raises its own compile SDK to 37 and requires an AGP 9 toolchain.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Lifecycle 2.11 is compiled against API 37 and requires AGP 9.2.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
