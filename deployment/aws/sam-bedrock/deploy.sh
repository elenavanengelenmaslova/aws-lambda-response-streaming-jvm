#!/usr/bin/env bash
#
# deploy.sh - Build and deploy the Bedrock streaming endpoint with AWS SAM.
#
# Steps:
#   1. Build the shadow fat jar (delegates to build.sh).
#   2. sam build  - assemble the deployment artifact from template.yaml.
#   3. sam deploy - create/update the CloudFormation stack.
#
# Configuration (stack name, region, capabilities) comes from samconfig.toml in this
# directory. Extra args are forwarded to `sam deploy`, e.g.:
#   ./deployment/aws/sam-bedrock/deploy.sh --guided
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building the Lambda fat jar"
"${SCRIPT_DIR}/build.sh"

cd "${SCRIPT_DIR}"

echo "==> sam build"
sam build --template-file template.yaml

echo "==> sam deploy"
sam deploy --config-file samconfig.toml "$@"
