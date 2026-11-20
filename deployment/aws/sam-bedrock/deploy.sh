#!/usr/bin/env bash
#
# deploy.sh - Build and deploy the Bedrock streaming endpoint with AWS SAM.
#
# Steps:
#   1. Build the shadow fat jar (delegates to build.sh).
#   2. sam deploy - package the prebuilt jar and create/update the CloudFormation stack.
#
# The template's CodeUri points straight at the prebuilt shadow fat jar, so `sam build`
# is intentionally NOT run: it looks for a Gradle/Maven manifest next to the jar and fails
# with "Unable to find a supported build workflow for runtime 'java25'". `sam deploy`
# packages the referenced jar directly. See docs/log.md.
#
# PREREQUISITE - API Gateway account-level CloudWatch role (once per account and Region).
# The template's stage has an AccessLogSetting, and a REST API can only write CloudWatch logs
# when the account's cloudwatchRoleArn is set in the target Region. Check it with:
#
#   aws apigateway get-account --region <region> --query cloudwatchRoleArn
#
# If that is empty, create an IAM role trusted by apigateway.amazonaws.com, attach the
# AmazonAPIGatewayPushToCloudWatchLogs managed policy, and set its ARN as the account's
# cloudwatchRoleArn (console: API Gateway > Settings; or `aws apigateway update-account`).
# Without it the deploy can fail on the stage, or succeed with no access log written.
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

echo "==> sam deploy (packages the prebuilt jar; no sam build)"
sam deploy --config-file samconfig.toml "$@"
