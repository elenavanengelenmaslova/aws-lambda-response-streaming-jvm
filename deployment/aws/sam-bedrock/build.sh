#!/usr/bin/env bash
#
# build.sh - Build the Lambda shadow fat jar for the Bedrock streaming endpoint.
#
# Produces build/dist/bedrock-streaming-endpoint.jar, which the SAM template's CodeUri
# (../../../build/dist/) packages as the function artifact.
#
# Usage:
#   ./deployment/aws/sam-bedrock/build.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

echo "==> Building shadow fat jar (clean build) from ${REPO_ROOT}"
./gradlew clean :streaming-bedrock-example:build

echo "==> Fat jar produced in build/dist/:"
ls -1 build/dist/bedrock-streaming-endpoint.jar
