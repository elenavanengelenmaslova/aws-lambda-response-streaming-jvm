// Root build.gradle.kts — version catalog only. All build logic lives in subproject build files.
// Versions are the single source of truth; bump here and subprojects pick them up via rootProject.extra.

plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
    id("com.gradleup.shadow") version "9.0.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}

// ---- Dependency versions (single source of truth) -------------------------------------------
extra["awsLambdaCoreVersion"] = "1.4.0"
extra["awsLambdaEventsVersion"] = "3.16.1"
extra["awsSdkKotlinVersion"] = "1.6.59"
extra["kotlinxSerializationVersion"] = "1.9.0"
extra["kotlinLoggingVersion"] = "7.0.7"
extra["cracVersion"] = "1.5.0"

extra["junitVersion"] = "6.0.0"
extra["mockkVersion"] = "1.14.5"
extra["coroutinesVersion"] = "1.10.2"
extra["testcontainersVersion"] = "1.21.4"
// Floci — local AWS emulator used by the integration tests (replaces LocalStack). The 1.x line of
// the Testcontainers module builds against Testcontainers 1.21.4, matching the version above; the
// 2.x line requires Testcontainers 2.x. `flociImage` pins the emulator itself, since the module's
// no-arg constructor would otherwise track `floci/floci:latest`.
extra["flociTestcontainersVersion"] = "1.14.0"
extra["flociImage"] = "floci/floci:1.7.0"

// ---- Java example module (streaming-s3-example-java) ----------------------------------------
// The Java example uses the AWS SDK for Java v2 (not the Kotlin SDK), Jackson for request parsing,
// and Mockito for mocking — none of which the Kotlin module needs.
extra["awsSdkJavaVersion"] = "2.50.2"
extra["jacksonVersion"] = "2.22.1"
extra["mockitoVersion"] = "5.23.0"
