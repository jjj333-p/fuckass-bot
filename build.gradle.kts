plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}


group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal() // Tells Gradle to check your hidden ~/.m2 folder first!
    mavenCentral()
}

val ktor_version: String by project

dependencies {
    implementation("tigase.halcyon:halcyon-core:2.0.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}