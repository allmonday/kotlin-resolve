plugins {
    kotlin("jvm") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.25"
    application
}

group = "com.tangkikodo"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.tangkikodo:kotlin-resolve:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    ksp("com.tangkikodo:kotlin-resolve-ksp:0.1.0-SNAPSHOT")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.tangkikodo.kotlinresolve.bench.MainKt")
}
