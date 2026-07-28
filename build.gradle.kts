import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.ksp)
}

group = "io.konektis"
version = "0.0.1"

// Pin both Java and Kotlin to JVM target 24. Kotlin 2.2.x can't emit bytecode newer than 24, so on
// a host JDK 25/26 compileJava would default to the host version and mismatch compileKotlin (24),
// failing the build. Emitting 24 keeps the two consistent while still building on a newer JDK.
java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_24
    }
}

application {
    mainClass = "io.konektis.ApplicationKt"
}

tasks {
    shadowJar {
        mergeServiceFiles()
    }

    register<JavaExec>("batteryWatchdogTest") {
        group = "tools"
        description = "Arm SMA battery Modbus control and poll, to probe the inverter watchdog."
        mainClass.set("io.konektis.tools.BatteryWatchdogTestKt")
        classpath = sourceSets["main"].runtimeClasspath
    }
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
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.network)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.modbus.tcp)
    implementation(libs.hivemq.mqtt.client)
    //implementation(libs.klogging.jvm)
    implementation(libs.bits)
    implementation(libs.endian)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.yaml)
    implementation(libs.klogging.sl4j)
    testImplementation("com.charleskorn.kaml:kaml:0.79.0")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.testcontainers)
    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)
}

configurations.all {
    exclude("ch.qos.logback")
}

tasks.withType<Test> {
    // MockK/ByteBuddy must self-attach to instrument final classes on JDK 21+; allow it explicitly.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Dnet.bytebuddy.experimental=true")
}
