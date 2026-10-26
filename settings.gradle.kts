rootProject.name = "aws-lambda-streaming-jvm-runtime"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("streaming-core")
include("streaming-sse")
include("streaming-s3-example")
include("streaming-s3-example-java")
include("streaming-bedrock-example")
