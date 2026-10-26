import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versionless: these three come from the root build script's `apply false` declarations, so
    // they are already on the inherited script classpath. Re-requesting them through
    // `alias(...)` would re-introduce a version, which Gradle rejects.
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    // Module-local plugins — the root never declares them, so their versions come from
    // gradle/libs.versions.toml.
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.bcv)
}

group = "nl.vintik"

// The published version comes from -PreleaseVersion, which the publish workflow derives from the
// git tag. Keeping it out of this file means the tag is the single source of truth: a tag can no
// longer trigger a release that publishes some other, stale version number. Shared with
// streaming-core so both modules release at one version from one tag.
version = providers.gradleProperty("releaseVersion").getOrElse("2.0.0-SNAPSHOT")

dependencies {
    // --- Streaming core ---
    // The only non-test project dependency. api-scoped so consumers of streaming-sse receive
    // streaming-core (and its transitive kotlinx-serialization-json) on their compile and runtime
    // classpath without declaring it. No AWS artifacts flow through here.
    api(project(":streaming-core"))

    // --- Testing ---
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    // Versionless by design: the launcher's version is constrained by junit-jupiter.
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ---- Toolchain & compilation: Java 21 (LTS, Lambda-standard) --------------------------------
// Deliberately below the example module's Java 25 and matching streaming-core: a library's
// bytecode target is a floor for every consumer, and java21 remains a supported Lambda runtime.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    // Every public declaration must state its visibility and return type — no accidental API.
    explicitApi()

    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.test {
    useJUnitPlatform {
        val excludeTags = project.findProperty("excludeTags") as? String
        if (!excludeTags.isNullOrBlank()) {
            excludeTags(excludeTags)
        }
    }
    systemProperty("net.bytebuddy.experimental", "true")
}

// ---- Coverage gate: 90% via koverVerify -----------------------------------------------------
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 90
                }
            }
        }
    }
}

// ---- Maven Central publishing ---------------------------------------------------------------
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("nl.vintik", "aws-lambda-streaming-sse", version.toString())

    pom {
        name = "AWS Lambda Streaming SSE"
        description = "Reusable SSE padding and dribble extension for the AWS Lambda / API Gateway streaming response protocol. PaddedSseWriter emits SSE data events with tiered SSE-comment padding (large head padding to break the runtime's internal buffer, smaller tail padding to keep the stream flowing) so small chunks reach the client progressively; an optional inter-chunk delay paces delivery. Depends only on streaming-core — no AWS artifacts."
        url = "https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm"
        licenses {
            // MIT, matching the repository LICENSE file — the authoritative source for the
            // licence value. The identifier here, LICENSE's first line and the README licence
            // badge are deliberately the same string.
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "elenavanengelenmaslova"
                name = "Elena van Engelen-Maslova"
                url = "https://github.com/elenavanengelenmaslova"
            }
        }
        scm {
            url = "https://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm"
            connection = "scm:git:git://github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm.git"
            developerConnection = "scm:git:ssh://git@github.com/elenavanengelenmaslova/aws-lambda-response-streaming-jvm.git"
        }
    }
}
