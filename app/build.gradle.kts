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

/** Release signing secrets (docs/10 §3); null when keystore.properties is absent. */
fun loadKeystoreProperties(): Map<String, String>? {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return null
    return f.readLines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
        val idx = t.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        t.substring(0, idx).trim() to t.substring(idx + 1).trim()
    }.toMap()
}

val keystoreProps = loadKeystoreProperties()

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
        // P3-5: instrumented tests write into the real app data (prefs,
        // notebooks). Wipe it per run so a re-run starts clean instead of
        // corrupting the previous state.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // docs/10 §3: keystore.properties is gitignored; without it the
            // release build fails at signing time rather than falling back to
            // the public debug key (audit P2-2).
            if (keystoreProps != null) {
                storeFile = file(keystoreProps["storeFile"]!!)
                storePassword = keystoreProps["storePassword"]
                keyAlias = keystoreProps["keyAlias"]
                keyPassword = keystoreProps["keyPassword"]
            }
        }
    }

    buildTypes {
        debug {
            // Dev key from .env — embedded in DEBUG builds only, so the app
            // works without typing the key on-device. Release stays clean.
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekApiKey\"")
        }
        release {
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
            // M6 / audit P2-2: real signing + minification (keep rules in
            // proguard-rules.pro cover kotlinx.serialization and PdfBox).
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
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

    lint {
        // "lint clean" is part of Done (AGENTS.md): new issues fail the
        // build. The baseline pins today's known warnings; regenerate it
        // deliberately (audit P2-3).
        abortOnError = true
        baseline = file("lint-baseline.xml")
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
