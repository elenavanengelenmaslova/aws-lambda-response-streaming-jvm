import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow")
    // Coverage: JaCoCo, not Kover. Kover only instruments modules that apply a Kotlin plugin, so on
    // this pure-Java module it would pass vacuously. Same reasoning as streaming-s3-example-java.
    // The 80% gate, report wiring and kover* aliases are defined below.
    jacoco
}

dependencies {
    // --- The libraries under test: consumed from plain Java ---
    implementation(project(":streaming-core"))
    implementation(project(":streaming-sse"))

    // --- AWS Lambda runtime contracts (RequestStreamHandler, Context) ---
    implementation(libs.aws.lambda.core)

    // --- AWS SDK for Java v2 (asynchronous Bedrock Runtime), versions come from the BOM ---
    implementation(platform(libs.aws.sdk.java.bom))
    implementation(libs.aws.sdk.java.bedrockruntime)
    implementation(libs.aws.sdk.java.netty.nio)

    // --- JSON: Jackson parses the proxy event and builds the Nova request body ---
    implementation(libs.jackson.databind)

    // --- Logging ---
    implementation(libs.slf4j.simple)

    // --- CRaC priming hook for SnapStart ---
    implementation(libs.crac)

    // kotlin-stdlib is deliberately NOT declared (Req 1.6). If compilation ever proves it necessary,
    // record why in docs/log.md: that is evidence for Req 2.

    // --- Testing: JUnit Jupiter + Mockito ---
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    // Versionless by design: the launcher's version is constrained by junit-jupiter.
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ---- Footprint (task 6.3): this module builds only an async client, so the SDK's default
// synchronous HTTP client is unused. See docs/log.md for the measured size.
configurations.configureEach {
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
}

// ---- Toolchain: Java 25, consuming the Java-21-compiled libraries ---------------------------
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// ---- Fat jar for Lambda deployment (Shadow) -------------------------------------------------
// Names are distinct from the Kotlin Bedrock example's bedrock-streaming-endpoint.{jar,zip} so both
// build into build/dist without overwriting each other.
tasks.shadowJar {
    archiveFileName.set("bedrock-streaming-endpoint-java.jar")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    mergeServiceFiles()
    // The same fat jar doubles as the streaming Java agent (loaded via -javaagent in
    // JAVA_TOOL_OPTIONS). Without it the runtime posts a duplicate response and dies with
    // Runtime.ExitError. See the streaming-sse README.
    manifest {
        attributes(
            "Premain-Class" to "nl.vintik.lambda.streaming.sse.StreamingAgent",
            "Can-Retransform-Classes" to "true",
        )
    }
}

// The deployment package is a ZIP, not a bare jar: -javaagent needs a real jar FILE on disk, and
// Lambda extracts a bare-jar CodeUri's contents loose into /var/task. lib/<jar> is the standard
// Java Lambda zip layout.
val lambdaZip = tasks.register<Zip>("lambdaZip") {
    archiveFileName.set("bedrock-streaming-endpoint-java.zip")
    destinationDirectory.set(file("${rootDir}/build/dist"))
    from(tasks.shadowJar) { into("lib") }
}

// Fails the build when the deployment zip is missing or lacks lib/<jar>. Without that entry the
// -javaagent path in JAVA_TOOL_OPTIONS points at nothing and the JVM aborts at startup.
val verifyLambdaZip = tasks.register("verifyLambdaZip") {
    group = "verification"
    description = "Fails when the Lambda zip is absent or has no lib/bedrock-streaming-endpoint-java.jar entry."
    dependsOn(lambdaZip)
    val zipFile = lambdaZip.flatMap { it.archiveFile }
    val expectedEntry = "lib/bedrock-streaming-endpoint-java.jar"
    doLast {
        val zip = zipFile.get().asFile
        check(zip.isFile) { "Lambda zip not found: $zip" }
        ZipFile(zip).use { z ->
            check(z.getEntry(expectedEntry) != null) {
                "Lambda zip $zip has no '$expectedEntry' entry"
            }
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar, lambdaZip, verifyLambdaZip)
}

tasks.test {
    useJUnitPlatform {
        val excludeTags = project.findProperty("excludeTags") as? String
        if (!excludeTags.isNullOrBlank()) {
            excludeTags(excludeTags)
        }
    }

    // Never reuse a cached run for THIS task, deliberately, and not for test correctness: the
    // JaCoCo agent stamps its execution data with wall-clock times, the report carries them into
    // the XML, and Codecov rejects an upload whose report is more than 12 hours old. A cache hit
    // on `test` restores exec data from whenever it was first produced, so the upload would be
    // rejected. See docs/log.md.
    //
    // `upToDateWhen` alone is not enough: a cacheable task with up-to-date checks disabled is still
    // restored FROM-CACHE, so the cache has to be opted out of as well.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// ---- Coverage gate: 80% line coverage via JaCoCo --------------------------------------------
// Version from gradle/libs.versions.toml (0.8.14 is the first release supporting Java 25 class files).
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// ---- Compatibility aliases: keep the repo-wide `kover*` command surface working --------------
tasks.register("koverHtmlReport") {
    group = "verification"
    description = "Alias for jacocoTestReport (HTML coverage). Kept for repo-wide command/CI compatibility; this pure-Java module uses JaCoCo, not Kover."
    dependsOn(tasks.jacocoTestReport)
}

tasks.register("koverVerify") {
    group = "verification"
    description = "Alias for jacocoTestCoverageVerification (80% line gate). Kept for repo-wide command/CI compatibility; this pure-Java module uses JaCoCo, not Kover."
    dependsOn(tasks.jacocoTestCoverageVerification)
}
