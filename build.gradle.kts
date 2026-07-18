plugins {
    `java-library`
    `maven-publish`   // Phase 9: local repo today; Central rides csrbt-core's release
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

// Mirror the siblings: 17-target bytecode from whatever JDK runs Gradle (Gradle 9 needs 17+).
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // Resolved to the live sibling sources via the composite build in settings.gradle.kts
    // (smokehouse api-exposes csrbt-core and superbeefsort, so all three ride along).
    api("io.github.richeyworks:smokehouse:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // csrbt-core logs via log4j-api with no backend on the classpath; keep tests quiet.
    systemProperty("log4j2.loggerContextFactory",
            "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    systemProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
}

// Phase 9 (outer-ring ADR): make the ring locally installable — ./gradlew publishToMavenLocal.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "renderer"
            from(components["java"])
            pom {
                name = "Renderer"
                description = "A materialized-view engine folding SmokeHouse's tail into CSRBT-held aggregates — the fifth engine of the CSRBT ecosystem."
                url = "https://github.com/RicheyWorks/Renderer"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "RicheyWorks"
                        name = "Richmond"
                    }
                }
                scm {
                    url = "https://github.com/RicheyWorks/Renderer"
                    connection = "scm:git:https://github.com/RicheyWorks/Renderer.git"
                }
            }
        }
    }
}
