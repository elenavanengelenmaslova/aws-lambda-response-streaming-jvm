#!/usr/bin/env bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🚀 Setting up GitHub Actions OIDC for aws-lambda-response-streaming-jvm${NC}"
echo ""

# Defaults
DEFAULT_ORG="elenavanengelenmaslova"
DEFAULT_REPO="aws-lambda-response-streaming-jvm"
DEFAULT_REGION="eu-west-1"
STACK_NAME="streaming-example-github-oidc"
TEMPLATE_FILE="$(dirname "$0")/../deployment/aws/oidc/github-oidc-role.yaml"

# Non-interactive mode: --yes / -y on the command line, or OIDC_SETUP_NON_INTERACTIVE=1.
# In this mode every prompt is skipped and the defaults are used (including reusing an
# existing GitHub OIDC provider when one is found).
NON_INTERACTIVE=${OIDC_SETUP_NON_INTERACTIVE:-0}
for arg in "$@"; do
  case "$arg" in
    --yes | -y) NON_INTERACTIVE=1 ;;
  esac
done

# Get parameters
if [ "$NON_INTERACTIVE" = "1" ]; then
  GITHUB_ORG="$DEFAULT_ORG"
  GITHUB_REPO="$DEFAULT_REPO"
  AWS_REGION="$DEFAULT_REGION"
  echo -e "${YELLOW}Non-interactive mode: using defaults for org, repo and region.${NC}"
else
  read -r -p "Enter your GitHub organization/username [$DEFAULT_ORG]: " GITHUB_ORG
  GITHUB_ORG=${GITHUB_ORG:-$DEFAULT_ORG}

  read -r -p "Enter your GitHub repository name [$DEFAULT_REPO]: " GITHUB_REPO
  GITHUB_REPO=${GITHUB_REPO:-$DEFAULT_REPO}

  read -r -p "Enter AWS region [$DEFAULT_REGION]: " AWS_REGION
  AWS_REGION=${AWS_REGION:-$DEFAULT_REGION}
fi

# Derive the OIDC subject prefix from GitHub itself.
#
# The `sub` claim shape differs per repository: a repo moved onto GitHub's IMMUTABLE subject
# claims (by a rename or transfer) reports `repo:<owner>@<owner_id>/<repo>@<repo_id>`, while a
# legacy repo still reports `repo:<owner>/<repo>`. Hardcoding either one silently produces a
# trust policy that never matches, so ask GitHub instead of guessing.
#
# Set SUBJECT_PREFIX in the environment to override this (and to run without `gh`).
if [ -z "${SUBJECT_PREFIX:-}" ]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo -e "${RED}❌ The GitHub CLI (gh) was not found on PATH.${NC}"
    echo "   It is needed to read this repository's OIDC subject claim format:"
    echo "     gh api /repos/$GITHUB_ORG/$GITHUB_REPO/actions/oidc/customization/sub"
    echo "   Install gh (https://cli.github.com) and authenticate with 'gh auth login',"
    echo "   or set the SubjectPrefix value yourself and re-run, e.g.:"
    echo "     SUBJECT_PREFIX='repo:<owner>@<owner_id>/*@<repo_id>' $0"
    echo "     SUBJECT_PREFIX='repo:<owner>/<repo>' $0   # legacy, name-based claims"
    exit 1
  fi

  echo ""
  echo -e "${BLUE}🔎 Resolving OIDC subject prefix from GitHub...${NC}"

  SUB_CUSTOMIZATION=$(gh api "/repos/$GITHUB_ORG/$GITHUB_REPO/actions/oidc/customization/sub" \
    --jq '.use_immutable_subject, .sub_claim_prefix')
  USE_IMMUTABLE_SUBJECT=$(echo "$SUB_CUSTOMIZATION" | sed -n '1p')
  SUB_CLAIM_PREFIX=$(echo "$SUB_CUSTOMIZATION" | sed -n '2p')

  REPO_INFO=$(gh api "/repos/$GITHUB_ORG/$GITHUB_REPO" --jq '.owner.id, .id')
  OWNER_ID=$(echo "$REPO_INFO" | sed -n '1p')
  REPO_ID=$(echo "$REPO_INFO" | sed -n '2p')

  if [ "$USE_IMMUTABLE_SUBJECT" = "true" ]; then
    # Pin both immutable IDs, wildcard the mutable name segment deliberately so a future
    # rename does not break the trust policy.
    SUBJECT_PREFIX="repo:${GITHUB_ORG}@${OWNER_ID}/*@${REPO_ID}"
    echo "  Immutable subject claims: yes"
    echo "  GitHub reports prefix:    $SUB_CLAIM_PREFIX"
  else
    SUBJECT_PREFIX="repo:${GITHUB_ORG}/${GITHUB_REPO}"
    echo "  Immutable subject claims: no"
    echo -e "${YELLOW}  Note: this repository is still on the legacy name-based subject format.${NC}"
    echo -e "${YELLOW}  A rename or transfer will move it onto immutable claims — re-run this${NC}"
    echo -e "${YELLOW}  script afterwards to refresh the trust policy.${NC}"
  fi
else
  echo ""
  echo -e "${YELLOW}Using SUBJECT_PREFIX from the environment (skipping GitHub lookup).${NC}"
fi

# Check if you already have a GitHub OIDC provider in this account
EXISTING_OIDC=""
OIDC_ARN=$(aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[?ends_with(Arn, `token.actions.githubusercontent.com`)].Arn' --output text 2>/dev/null || true)
if [ -n "$OIDC_ARN" ] && [ "$OIDC_ARN" != "None" ]; then
  echo ""
  echo -e "${YELLOW}Found existing GitHub OIDC provider: $OIDC_ARN${NC}"
  if [ "$NON_INTERACTIVE" = "1" ]; then
    EXISTING_OIDC="$OIDC_ARN"
  else
    read -r -p "Reuse this provider? (Y/n): " REUSE
    if [[ ! $REUSE =~ ^[Nn]$ ]]; then
      EXISTING_OIDC="$OIDC_ARN"
    fi
  fi
fi

echo ""
echo -e "${YELLOW}📋 Configuration:${NC}"
echo "  GitHub Org/User: $GITHUB_ORG"
echo "  GitHub Repository: $GITHUB_REPO"
echo "  AWS Region: $AWS_REGION"
echo "  Stack Name: $STACK_NAME"
echo "  Template: $TEMPLATE_FILE"
echo "  Subject Prefix: $SUBJECT_PREFIX"
echo "  Trusted sub patterns:"
echo "    $SUBJECT_PREFIX:ref:refs/heads/main"
echo "    $SUBJECT_PREFIX:ref:refs/heads/feature/*"
echo "    $SUBJECT_PREFIX:environment:*"
if [ -n "$EXISTING_OIDC" ]; then
  echo "  Reusing OIDC Provider: $EXISTING_OIDC"
fi
echo ""

if [ "$NON_INTERACTIVE" != "1" ]; then
  read -r -p "Continue with deployment? (y/N): " CONFIRM
  if [[ ! $CONFIRM =~ ^[Yy]$ ]]; then
    echo "Deployment cancelled."
    exit 0
  fi
fi

echo ""
echo -e "${BLUE}🔧 Deploying OIDC CloudFormation stack...${NC}"

# Build parameter overrides
PARAMS="GitHubOrg=$GITHUB_ORG GitHubRepo=$GITHUB_REPO SubjectPrefix=$SUBJECT_PREFIX"
if [ -n "$EXISTING_OIDC" ]; then
  PARAMS="$PARAMS ExistingOIDCProviderArn=$EXISTING_OIDC"
fi

# Deploy the CloudFormation stack.
# $PARAMS is intentionally unquoted: word-splitting is what turns it into separate
# Key=Value arguments for --parameter-overrides.
# shellcheck disable=SC2086
if ! aws cloudformation deploy \
  --template-file "$TEMPLATE_FILE" \
  --stack-name "$STACK_NAME" \
  --parameter-overrides $PARAMS \
  --capabilities CAPABILITY_NAMED_IAM \
  --region "$AWS_REGION"; then
  echo -e "${RED}❌ OIDC setup failed. Check the error messages above.${NC}"
  exit 1
fi

echo ""
echo -e "${GREEN}✅ OIDC setup completed successfully!${NC}"
echo ""

# Get outputs
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ROLE_ARN=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" \
  --query 'Stacks[0].Outputs[?OutputKey==`GitHubActionsRoleArn`].OutputValue' \
  --output text)
ROLE_NAME=$(echo "$ROLE_ARN" | awk -F'/' '{print $NF}')

echo -e "${YELLOW}📝 Next Steps:${NC}"
echo ""
echo "1. Add this SECRET to your GitHub repository settings:"
echo "   Name:  AWS_ACCOUNT_ID"
echo "   Value: $ACCOUNT_ID"
echo ""
echo "2. Add this VARIABLE to your GitHub repository settings:"
echo "   Name:  OIDC_ROLE_NAME"
echo "   Value: $ROLE_NAME"
echo ""
echo "3. Your GitHub Actions workflows will use this role:"
echo "   Role ARN: $ROLE_ARN"
echo ""
echo -e "${GREEN}🎉 You're ready to deploy via GitHub Actions!${NC}"
echo ""
echo -e "${BLUE}💡 To deploy manually:${NC}"
echo "   cd deployment/aws/sam"
echo "   ./deploy.sh"
echo ""
