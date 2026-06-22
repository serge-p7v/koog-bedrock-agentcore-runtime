plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "ai.koog"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.aws.sdk.bedrockruntime)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.sse)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(17)
}
