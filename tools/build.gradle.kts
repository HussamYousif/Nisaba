plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    application
    id("nisaba.roles")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
detekt { config.setFrom(rootProject.file("config/detekt.yml")); buildUponDefaultConfig = true }
application { mainClass.set("com.nisaba.tools.ToolsMainKt") }

dependencies {
    implementation(project(":scribe"))
    implementation(libs.okhttp)
    testImplementation(libs.kotlin.test)
}

// `./gradlew :tools:run --args=…` resolves paths (.env, captures/, --in files) from the repository root.
tasks.named<JavaExec>("run") { workingDir = rootDir }
