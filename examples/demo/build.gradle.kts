plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.tangkikodo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenLocal()  // pulls com.tangkikodo:kotlin-resolve:0.1.0-SNAPSHOT
    mavenCentral()
}

dependencies {
    implementation("com.tangkikodo:kotlin-resolve:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("demo.MainKt")
}
