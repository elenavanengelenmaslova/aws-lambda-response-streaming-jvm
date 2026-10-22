#!/usr/bin/env bash
#
# bedrock-streaming-test.sh — Post-deploy progressive-arrival test for the Bedrock
# streaming endpoint. Mirrors scripts/pipeline-streaming-test.sh.
#
# Proves that, with tiered SSE-comment padding, Bedrock (Nova Lite) token deltas arrive
# PROGRESSIVELY at the client through API Gateway REST (not as a single terminal burst):
#
#   1. First byte arrives early (TTFB well below total time).
#   2. Tokens arrive in multiple distinct time buckets over the generation (> 1 wave).
#   3. A terminal sentinel ([DONE]) is received, proving a complete stream.
#
# COST GUARD: this test invokes Amazon Bedrock, which costs money. It is OPT-IN and does
# nothing unless BEDROCK_TEST_ENABLED=true is set, mirroring the repo's opt-in convention.
#
# Configuration via environment variables (resolved from CloudFormation outputs when unset):
#   STACK_NAME             CloudFormation stack name   (default: bedrock-streaming-endpoint)
#   AWS_REGION             AWS region                  (optional; uses CLI default)
#   ENDPOINT_URL           Base HTTPS URL, trailing /  (default: stack output)
#   API_KEY                x-api-key value             (default: resolved from stack output ApiKeyId)
#   BEDROCK_TEST_ENABLED   Must be "true" to run       (default: unset -> skip)
#   PROMPT                 Prompt text                 (default: a long essay request)
#   MAX_TOKENS             Generation length           (default: 2000 — long enough to spread)
#   MIN_BUCKETS            Min distinct 500ms buckets   (default: 3)
#
# Exit codes:
#   0  checks passed, OR skipped because BEDROCK_TEST_ENABLED is not "true"
#   1  a check failed or the endpoint was unreachable
#
set -euo pipefail

STACK_NAME="${STACK_NAME:-bedrock-streaming-endpoint}"
BEDROCK_TEST_ENABLED="${BEDROCK_TEST_ENABLED:-}"
MAX_TOKENS="${MAX_TOKENS:-2000}"
MIN_BUCKETS="${MIN_BUCKETS:-3}"
PROMPT="${PROMPT:-Write a detailed 1500-word essay about the history and future of cloud computing, in flowing prose with several sections.}"

AWS_REGION="${AWS_REGION:-}"
region_args=()
if [[ -n "$AWS_REGION" ]]; then
  region_args=(--region "$AWS_REGION")
fi

info() { printf '[bedrock-streaming] %s\n' "$*"; }
pass() { printf '[bedrock-streaming] PASS: %s\n' "$*"; }
fail() { printf '[bedrock-streaming] ERROR: %s\n' "$*" >&2; exit 1; }

# --- Opt-in cost guard -------------------------------------------------------------------
if [[ "$BEDROCK_TEST_ENABLED" != "true" ]]; then
  info "BEDROCK_TEST_ENABLED is not \"true\"; skipping (this test invokes paid Bedrock). Set BEDROCK_TEST_ENABLED=true to run."
  exit 0
fi

require_tools() {
  local missing=()
  for tool in curl aws python3 awk; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
  done
  (( ${#missing[@]} == 0 )) || fail "missing required tool(s): ${missing[*]}"
}

stack_output() {
  aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" "${region_args[@]}" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" \
    --output text 2>/dev/null
}

resolve_config() {
  ENDPOINT_URL="${ENDPOINT_URL:-$(stack_output StreamingEndpointUrl)}"
  [[ -n "${ENDPOINT_URL:-}" && "$ENDPOINT_URL" != "None" ]] \
    || fail "could not determine ENDPOINT_URL (set it or deploy stack '$STACK_NAME')"
  [[ "$ENDPOINT_URL" == https://* ]] || fail "ENDPOINT_URL must be HTTPS: $ENDPOINT_URL"

  if [[ -z "${API_KEY:-}" ]]; then
    local api_key_id
    api_key_id="$(stack_output ApiKeyId)"
    if [[ -n "$api_key_id" && "$api_key_id" != "None" ]]; then
      API_KEY="$(aws apigateway get-api-key --api-key "$api_key_id" --include-value \
        "${region_args[@]}" --query 'value' --output text 2>/dev/null || true)"
    fi
  fi
  [[ -n "${API_KEY:-}" ]] || fail "could not resolve API_KEY (set API_KEY or deploy the stack)"
  [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::add-mask::${API_KEY}"
  info "resolved endpoint and API key (masked)"
}

# Streams the endpoint and measures per-token arrival, asserting progressive arrival
# (TTFB < 50% of total, >= MIN_BUCKETS distinct 500ms buckets, and the [DONE] sentinel seen).
measure() {
  local url="${ENDPOINT_URL%/}/stream" body
  export PROMPT MAX_TOKENS
  body="$(python3 -c 'import json,os;print(json.dumps({"prompt":os.environ["PROMPT"],"maxTokens":int(os.environ["MAX_TOKENS"])}))')"

  info "warmup request (discarded)"
  curl -sN --http1.1 -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
    --max-time 240 -o /dev/null "$url" -d "$body" || true

  info "measured request (streaming)"
  local result
  result="$(curl -sN --http1.1 -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
    --max-time 240 "$url" -d "$body" \
    | MIN_BUCKETS="$MIN_BUCKETS" python3 -c '
import sys,time,os
min_buckets=int(os.environ["MIN_BUCKETS"])
base=None;first=None;last=None;times=[];done=False
for raw in iter(sys.stdin.buffer.readline, b""):
    now=time.monotonic()
    if base is None: base=now
    line=raw.decode("utf-8","replace")
    if line.startswith("data: "):
        payload=line[6:].rstrip("\n")
        if payload=="[DONE]":
            done=True; continue
        if first is None: first=now
        last=now; times.append(now)
if first is None:
    print("FAIL no_data"); sys.exit(0)
ttf=(first-base)*1000.0
total=(last-base)*1000.0
buckets=len({int(((t-base)*1000.0)//500) for t in times})
ratio = ttf/total if total>0 else 1.0
ok = (ratio < 0.5) and (buckets >= min_buckets) and done
verdict = "OK" if ok else "FAIL"
print(verdict + f" events={len(times)} ttf_ms={ttf:.0f} total_ms={total:.0f} buckets={buckets} done={done} ratio={ratio:.2f}")
')"
  info "result: $result"
  case "$result" in
    OK*) pass "progressive arrival confirmed ($result)";;
    *)   fail "progressive arrival NOT confirmed ($result)";;
  esac
}

main() {
  info "============================================="
  info "Bedrock Streaming — Progressive Arrival Test"
  info "============================================="
  require_tools
  resolve_config
  measure
  info "============================================="
  info "Bedrock streaming test passed"
  info "============================================="
}

main
