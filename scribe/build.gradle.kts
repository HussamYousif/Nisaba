plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    id("nisaba.roles")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
detekt { config.setFrom(rootProject.file("config/detekt.yml")); buildUponDefaultConfig = true }

dependencies {
    api(project(":domain"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
}
