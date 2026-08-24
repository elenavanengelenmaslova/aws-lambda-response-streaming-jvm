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
    implementation("com.amazonaws:aws-lambda-java-core:${rootProject.extra["awsLambdaCoreVersion"]}")

    // --- AWS SDK for Kotlin (NOT the Java SDK) ---
    implementation("aws.sdk.kotlin:s3:${rootProject.extra["awsSdkKotlinVersion"]}")

    // --- Logging ---
    implementation("io.github.oshai:kotlin-logging-jvm:${rootProject.extra["kotlinLoggingVersion"]}")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // --- Coroutines (StreamHandler uses runBlocking) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.extra["coroutinesVersion"]}")

    // --- Serialization (RequestParser and JsonRequestResolver use kotlinx-serialization) ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["kotlinxSerializationVersion"]}")

    // --- CRaC priming hook for SnapStart ---
    implementation("org.crac:crac:${rootProject.extra["cracVersion"]}")

    // --- Testing ---
    testImplementation("org.junit.jupiter:junit-jupiter:${rootProject.extra["junitVersion"]}")
    testImplementation("io.mockk:mockk:${rootProject.extra["mockkVersion"]}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.extra["coroutinesVersion"]}")
    testImplementation("org.testcontainers:testcontainers:${rootProject.extra["testcontainersVersion"]}")
    testImplementation("org.testcontainers:junit-jupiter:${rootProject.extra["testcontainersVersion"]}")
    testImplementation("io.floci:testcontainers-floci:${rootProject.extra["flociTestcontainersVersion"]}")
    // Lambda + API Gateway control planes, used only by FlociLambdaApiGatewayIntegrationTest to
    // deploy the shadow jar into the emulator and front it with a REST API. Not shipped.
    testImplementation("aws.sdk.kotlin:lambda:${rootProject.extra["awsSdkKotlinVersion"]}")
    testImplementation("aws.sdk.kotlin:apigateway:${rootProject.extra["awsSdkKotlinVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    // Emulator image pin for the integration tests, from the root version catalog.
    systemProperty("floci.image", rootProject.extra["flociImage"] as String)

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
