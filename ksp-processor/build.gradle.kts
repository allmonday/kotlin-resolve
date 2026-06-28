plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "com.tangkikodo"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("com.google.devtools.ksp:symbol-processing-api:2.0.21-1.0.25")
    compileOnly("com.tangkikodo:kotlin-resolve:0.1.0-SNAPSHOT")
    implementation("com.squareup:kotlinpoet:1.18.1")
    implementation("com.squareup:kotlinpoet-ksp:1.18.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.github.tschuchort:kotlin-compile-testing:0.4.1")
    testImplementation("com.github.tschuchort:kotlin-compile-testing-ksp:0.4.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "kotlin-resolve-ksp"
            pom {
                name.set("kotlin-resolve-ksp")
                description.set("KSP compiler plugin for kotlin-resolve: generates zero-reflection adapters.")
            }
        }
    }
}
