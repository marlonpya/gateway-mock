plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "io.github.marlonpya"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

val ktor = "3.1.1"

dependencies {
    implementation("io.github.marlonpya:dukpt-kt:0.1.0")
    implementation("io.github.marlonpya:iso8583-kt:0.1.0")

    implementation("io.ktor:ktor-network:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.marlonpya.gateway.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
