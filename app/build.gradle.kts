plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/** Minimal .env loader (KEY=VALUE lines, # comments, optional quotes). */
fun loadDotEnv(file: java.io.File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
        val idx = t.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val k = t.substring(0, idx).trim()
        var v = t.substring(idx + 1).trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
        }
        k to v
    }.toMap()
}

val dotEnv = loadDotEnv(rootProject.file(".env"))
val deepseekApiKey = (dotEnv["DEEPSEEK_API_KEY"] ?: System.getenv("DEEPSEEK_API_KEY") ?: "")
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.nisaba"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nisaba"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Dev key from .env — embedded in DEBUG builds only, so the app
            // works without typing the key on-device. Release stays clean.
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekApiKey\"")
        }
        release {
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
            // Proper signing + minification land in M6; debug-signed for device iteration.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.pdfbox.android)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)

    testImplementation(libs.junit)
}
