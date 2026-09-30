import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    implementation(project(":streaming-core"))

    // --- AWS Lambda runtime contracts ---
    implementation(libs.aws.lambda.core)

    // --- AWS SDK for Kotlin (NOT the Java SDK) ---
    implementation(libs.aws.sdk.kotlin.s3)

    // --- Logging ---
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.slf4j.simple)

    // --- Coroutines (StreamHandler uses runBlocking) ---
    implementation(libs.kotlinx.coroutines.core)

    // --- Serialization (RequestParser and JsonRequestResolver use kotlinx-serialization) ---
    implementation(libs.kotlinx.serialization.json)

    // --- CRaC priming hook for SnapStart ---
    implementation(libs.crac)

    // --- Testing ---
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.integration.testing)
    // Lambda + API Gateway control planes, used only by FlociLambdaApiGatewayIntegrationTest to
    // deploy the shadow jar into the emulator and front it with a REST API. Not shipped.
    testImplementation(libs.aws.sdk.kotlin.lambda)
    testImplementation(libs.aws.sdk.kotlin.apigateway)
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
    useJUnitPlatform {
        val excludeTags = project.findProperty("excludeTags") as? String
        if (!excludeTags.isNullOrBlank()) {
            excludeTags(excludeTags)
        }
    }
    systemProperty("net.bytebuddy.experimental", "true")
    // Emulator image pin for the integration tests. The image name is not a Maven coordinate, so it
    // stays here; only the tag comes from the version catalog.
    systemProperty("floci.image", "floci/floci:${libs.versions.flociImage.get()}")

    // FlociLambdaApiGatewayIntegrationTest deploys the shadow jar into the emulated Lambda, so the
    // jar has to exist on disk. The path is passed via a CommandLineArgumentProvider (not a plain
    // systemProperty) because `org.gradle.configuration-cache=true` is on and the archive location
    // must be resolved lazily at execution time.
    val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(fatJar).withPropertyName("lambdaFatJar")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dstreaming.fatJar=${fatJar.get().asFile.absolutePath}")
        },
    )
    // Only pay for building the fat jar when the integration tests will actually run. CI passes
    // -PexcludeTags=integration, so its unit-test job stays as fast as it was.
    if ((project.findProperty("excludeTags") as? String)?.contains("integration") != true) {
        dependsOn(tasks.shadowJar)
    }
}

// ---- Fat jar for Lambda deployment (Shadow) -------------------------------------------------
tasks.shadowJar {
    archiveFileName.set("streaming-endpoint.jar")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// ---- Coverage gate: 80% via koverVerify -----------------------------------------------------
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
