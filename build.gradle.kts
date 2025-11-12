plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "io.konektis"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.rate.limiting)
    implementation(libs.ktor.server.websockets)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.h2)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.netty)
//    implementation(libs.log4j.api)
//    implementation(libs.log4j.core)
//    implementation(libs.log4j.slf4j.impl)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.modbus.tcp)
    implementation(libs.klogging.jvm)
    implementation(libs.bits)
    implementation(libs.endian)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.yaml)
    implementation(libs.klogging.sl4j)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
