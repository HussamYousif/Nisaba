import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    id("nisaba.roles")
}

/** Debug-only secrets from .env (never embedded in release, DESIGN D14). */
fun devKey(): String {
    val f = rootProject.file(".env")
    val fromFile = if (f.exists()) Properties().apply { f.reader().use(::load) }.getProperty("DEEPSEEK_API_KEY") else null
    return (fromFile ?: System.getenv("DEEPSEEK_API_KEY") ?: "").trim().removeSurrounding("\"")
}

android {
    namespace = "com.nisaba"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nisaba"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        debug { buildConfigField("String", "DEV_DEEPSEEK_API_KEY", "\"${devKey()}\"") }
        release {
            buildConfigField("String", "DEV_DEEPSEEK_API_KEY", "\"\"")
            isMinifyEnabled = false // enabled in M7
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true } // Android stubs return defaults (a View or a Log call in JVM tests)
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}
detekt { config.setFrom(rootProject.file("config/detekt.yml")); buildUponDefaultConfig = true }

dependencies {
    implementation(project(":infra"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onyxsdk.pen)
    implementation(libs.hiddenapibypass)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
