#!/usr/bin/env bash
#
# build.sh - Build the Lambda shadow fat jar for the Bedrock streaming endpoint.
#
# Produces build/dist/bedrock-streaming-endpoint.zip, which the SAM template's CodeUri
# (../../../build/dist/) packages as the function artifact.
#
# A ZIP rather than a bare jar: the function jar doubles as a Java agent (-javaagent in the
# template), and -javaagent needs a real jar FILE, which a bare-jar CodeUri does not leave behind.
# The zip carries lib/<fat jar>, the standard Java Lambda layout.
#
# Usage:
#   ./deployment/aws/sam-bedrock/build.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

echo "==> Building the deployment zip (clean build) from ${REPO_ROOT}"
./gradlew clean :streaming-bedrock-example:build

echo "==> Deployment package produced in build/dist/:"
ls -1 build/dist/bedrock-streaming-endpoint.zip build/dist/bedrock-streaming-endpoint.jar
