plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "com.tangkikodo"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Make the JAR reproducible and depend on classes.
tasks.jar {
    archiveBaseName.set("kotlin-resolve")
    from("LICENSE") { into("") }
    manifest {
        attributes(mapOf(
            "Implementation-Title" to "kotlin-resolve",
            "Implementation-Version" to project.version,
        ))
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "kotlin-resolve"

            pom {
                name.set("kotlin-resolve")
                description.set("Entity-First data assembly framework for Kotlin (port of pydantic-resolve).")
                url.set("https://github.com/tangkikodo/kotlin-resolve")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit/")
                    }
                }
                developers {
                    developer {
                        id.set("tangkikodo")
                        name.set("tangkikodo")
                    }
                }
            }
        }
    }
}
