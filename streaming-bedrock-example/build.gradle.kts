import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    implementation(project(":streaming-core"))
    implementation(project(":streaming-sse"))

    // --- AWS Lambda runtime contracts ---
    implementation(libs.aws.lambda.core)

    // --- AWS SDK for Kotlin (NOT the Java SDK) — Bedrock Runtime ---
    implementation(libs.aws.sdk.kotlin.bedrockruntime)

    // --- Logging ---
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.slf4j.simple)

    // --- Coroutines (BedrockStreamHandler uses runBlocking; the SDK streams a Flow) ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Serialization (request parsing + Nova request/delta JSON) ---
    implementation(libs.kotlinx.serialization.json)

    // --- CRaC priming hook for SnapStart ---
    implementation(libs.crac)

    // --- Testing ---
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ---- Toolchain & compilation: Java 25 -------------------------------------------------------
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("net.bytebuddy.experimental", "true")
}

// ---- Fat jar for Lambda deployment (Shadow) -------------------------------------------------
tasks.shadowJar {
    archiveFileName.set("bedrock-streaming-endpoint.jar")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    mergeServiceFiles()
    // Makes this same fat jar usable as the streaming Java agent, so deployment needs a dependency
    // and one JAVA_TOOL_OPTIONS flag rather than a wrapper script or a Lambda layer. Without the
    // agent, AWS's invocation loop posts a second response for an invocation the handler already
    // streamed, and the runtime dies with Runtime.ExitError. See the streaming-sse README.
    manifest {
        attributes(
            "Premain-Class" to "nl.vintik.lambda.streaming.sse.StreamingAgent",
            "Can-Retransform-Classes" to "true",
        )
    }
}

// The deployment package is a ZIP, not a bare jar: -javaagent needs a real jar FILE on disk, and
// Lambda extracts a bare-jar CodeUri's *contents* loose into /var/task, leaving nothing to point
// at. lib/<jar> is the standard Java Lambda zip layout and the CustomerClassLoader picks it up.
val lambdaZip = tasks.register<Zip>("lambdaZip") {
    archiveFileName.set("bedrock-streaming-endpoint.zip")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    from(tasks.shadowJar) { into("lib") }
}

tasks.build {
    dependsOn(tasks.shadowJar, lambdaZip)
}

// ---- Coverage gate: 80% via koverVerify (matches streaming-s3-example) ----------------------
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 80
                }
            }
        }
    }
}
