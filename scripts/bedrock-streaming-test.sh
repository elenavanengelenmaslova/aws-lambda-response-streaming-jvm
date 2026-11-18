#!/usr/bin/env bash
#
# bedrock-streaming-test.sh — post-deploy test for the Bedrock SSE streaming endpoint.
#
# Proves that Nova Lite token deltas arrive at the client PROGRESSIVELY, token by token, over a real
# streamed HTTP response — and that the old padding workaround has not crept back in.
#
# Four checks, each guarding a failure mode that actually happened during development:
#
#   1. Real HTTP TTFB        — curl's %{time_starttransfer} must be a small fraction of total time.
#                              This is the check the previous version of this script could not make:
#                              it timed from the first line it read, so its "TTFB ~0 ms" was true by
#                              construction and the assertion could never fail. On the buffered path
#                              the real ratio is ~1.0; streaming measures ~0.02-0.05.
#   2. Progressive arrival   — tokens land in several distinct time buckets rather than one burst.
#   3. No padding inflation  — bytes on the wire must be close to the answer's own size. The padded
#                              workaround put 18 MB on the wire for a ~8 KB answer (~1 280x); this
#                              fails if anything reintroduces that.
#   4. Complete stream       — the terminal [DONE] sentinel is received, so a truncated response is
#                              not mistaken for a successful one.
#
# A failing check 1 with a passing check 4 is the signature of the agent not being loaded: the
# endpoint still answers correctly, but buffered. Check the function's JAVA_TOOL_OPTIONS for
# -javaagent and that CodeUri is the zip, not the bare jar.
#
# COST GUARD: this test invokes Amazon Bedrock, which costs money. It is OPT-IN and does nothing
# unless BEDROCK_TEST_ENABLED=true is set.
#
# Configuration via environment variables (resolved from CloudFormation outputs when unset):
#   STACK_NAME             CloudFormation stack name    (default: bedrock-streaming-endpoint)
#   AWS_REGION             AWS region                   (optional; uses CLI default)
#   ENDPOINT_URL           Base HTTPS URL, trailing /   (default: stack output)
#   API_KEY                x-api-key value              (default: resolved from stack output)
#   BEDROCK_TEST_ENABLED   Must be "true" to run        (default: unset -> skip)
#   PROMPT                 Prompt text                  (default: a long essay request)
#   MAX_TOKENS             Generation length            (default: 2000)
#   RUNS                   Sequential requests; the LAST one decides pass/fail (default: 2)
#   MIN_BUCKETS            Min distinct 500ms buckets    (default: 4)
#   MAX_TTFB_RATIO         Max TTFB as a fraction of total (default: 0.35)
#   MAX_INFLATION          Max bytes-on-wire / answer-bytes (default: 4.0)
#
# Exit codes:
#   0  checks passed, OR skipped because BEDROCK_TEST_ENABLED is not "true"
#   1  a check failed or the endpoint was unreachable
#
set -euo pipefail

STACK_NAME="${STACK_NAME:-bedrock-streaming-endpoint}"
BEDROCK_TEST_ENABLED="${BEDROCK_TEST_ENABLED:-}"
MAX_TOKENS="${MAX_TOKENS:-2000}"
RUNS="${RUNS:-2}"
MIN_BUCKETS="${MIN_BUCKETS:-4}"
MAX_TTFB_RATIO="${MAX_TTFB_RATIO:-0.35}"
MAX_INFLATION="${MAX_INFLATION:-4.0}"
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
  for tool in curl aws python3; do
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

# Reports whether the deployed function is actually configured for streaming, so a buffered result
# is diagnosed rather than just failed.
report_agent_config() {
  local function_name version options
  function_name="$(stack_output FunctionName)"
  [[ -n "$function_name" && "$function_name" != "None" ]] || return 0
  version="$(aws lambda get-alias --function-name "$function_name" --name live \
    "${region_args[@]}" --query 'FunctionVersion' --output text 2>/dev/null || true)"
  [[ -n "$version" ]] || return 0
  options="$(aws lambda get-function-configuration --function-name "${function_name}:${version}" \
    "${region_args[@]}" --query 'Environment.Variables.JAVA_TOOL_OPTIONS' --output text 2>/dev/null || true)"
  if [[ "$options" == *"-javaagent"* ]]; then
    info "alias 'live' is version $version with the streaming agent configured"
  else
    info "WARNING: alias 'live' (version $version) has no -javaagent in JAVA_TOOL_OPTIONS;"
    info "         the endpoint will answer correctly but BUFFERED. Note that SAM's"
    info "         AutoPublishAlias only publishes a new version when the CODE changes, so a"
    info "         config-only deploy can leave the alias on an older version."
  fi
}

# Streams the endpoint once and prints one line of parsed metrics for the assertions below.
measure_once() {
  local url="${ENDPOINT_URL%/}/stream" body timing_file result
  body="$(PROMPT="$PROMPT" MAX_TOKENS="$MAX_TOKENS" python3 -c \
    'import json,os;print(json.dumps({"prompt":os.environ["PROMPT"],"maxTokens":int(os.environ["MAX_TOKENS"])}))')"

  timing_file="$(mktemp)"
  # curl writes its own timings to stderr, which the reader separates from the SSE body on stdout.
  result="$(curl -sN --http1.1 -H "x-api-key: $API_KEY" -H "Content-Type: application/json" \
    --max-time 240 \
    -w '%{stderr}CURL http_code=%{http_code} ttfb_s=%{time_starttransfer} total_s=%{time_total} bytes=%{size_download} api_request_id=%header{x-amzn-requestid}\n' \
    "$url" -d "$body" 2>"$timing_file" \
    | MIN_BUCKETS="$MIN_BUCKETS" python3 -c '
import os, re, sys, time

min_buckets = int(os.environ["MIN_BUCKETS"])
base = first = last = None
times = []
answer_chars = 0
done = False

for raw in iter(sys.stdin.buffer.readline, b""):
    now = time.monotonic()
    line = raw.decode("utf-8", "replace")
    if base is None:
        base = now
    if line.startswith("data: "):
        payload = line[6:].rstrip("\n")
        if payload == "[DONE]":
            done = True
            continue
        if first is None:
            first = now
        last = now
        times.append(now)
        answer_chars += len(payload)

if first is None:
    print("tokens=0 answer_chars=0 spread_ms=0 buckets=0 done=%s" % done)
    sys.exit(0)

spread = (last - first) * 1000.0
buckets = len({int(((t - base) * 1000.0) // 500) for t in times})
print(f"tokens={len(times)} answer_chars={answer_chars} spread_ms={spread:.0f} "
      f"buckets={buckets} done={done}")
')" || { rm -f "$timing_file"; fail "endpoint unreachable or the stream failed"; }

  local curl_line
  curl_line="$(grep '^CURL ' "$timing_file" | tail -n1 || true)"
  rm -f "$timing_file"
  printf '%s %s\n' "$result" "${curl_line#CURL }"
}

assert_streaming() {
  local metrics="$1"
  info "  metrics: $metrics"

  METRICS="$metrics" MAX_TTFB_RATIO="$MAX_TTFB_RATIO" MIN_BUCKETS="$MIN_BUCKETS" \
    MAX_INFLATION="$MAX_INFLATION" python3 -c '
import os, re, sys

fields = dict(re.findall(r"(\w+)=(\S+)", os.environ["METRICS"]))
problems = []

def number(name, default=0.0):
    try:
        return float(fields.get(name, default))
    except ValueError:
        return default

http_code = fields.get("http_code")
tokens = int(number("tokens"))
answer_chars = int(number("answer_chars"))
wire_bytes = int(number("bytes"))
ttfb = number("ttfb_s")
total = number("total_s")
buckets = int(number("buckets"))
done = fields.get("done", "False") == "True"

max_ratio = float(os.environ["MAX_TTFB_RATIO"])
min_buckets = int(os.environ["MIN_BUCKETS"])
max_inflation = float(os.environ["MAX_INFLATION"])

if http_code != "200":
    problems.append(f"HTTP {http_code}, expected 200")
if tokens == 0:
    problems.append("no tokens received")

# 1. Real TTFB. On the buffered path this ratio is ~1.0 because nothing leaves the sandbox
#    until the handler returns.
ratio = ttfb / total if total > 0 else 1.0
if ratio >= max_ratio:
    problems.append(
        f"TTFB {ttfb:.3f}s is {ratio:.0%} of total {total:.3f}s (limit {max_ratio:.0%}) "
        "- the response looks BUFFERED, not streamed"
    )

# 2. Progressive arrival across the generation.
if buckets < min_buckets:
    problems.append(f"tokens arrived in {buckets} time bucket(s), expected >= {min_buckets}")

# 3. Padding regression guard.
if answer_chars > 0:
    inflation = wire_bytes / answer_chars
    if inflation > max_inflation:
        problems.append(
            f"{wire_bytes} bytes on the wire for a {answer_chars}-char answer "
            f"({inflation:.1f}x, limit {max_inflation:.1f}x) - SSE padding may have returned"
        )
else:
    inflation = 0.0

# 4. Complete stream.
if not done:
    problems.append("terminal [DONE] sentinel not received - the stream was truncated")

if problems:
    for problem in problems:
        print(f"FAIL {problem}")
    sys.exit(1)

print(
    f"OK tokens={tokens} ttfb={ttfb:.3f}s ({ratio:.1%} of {total:.3f}s) "
    f"buckets={buckets} wire={wire_bytes}B inflation={inflation:.1f}x"
)
' || return 1
}

main() {
  info "============================================="
  info "Bedrock SSE Streaming - Post-Deploy Test"
  info "============================================="
  require_tools
  resolve_config
  report_agent_config

  info "warmup request (discarded, avoids a cold start in the measured run)"
  measure_once >/dev/null || true

  local metrics="" run
  for (( run = 1; run <= RUNS; run++ )); do
    info "request $run/$RUNS (maxTokens=$MAX_TOKENS, no padding)"
    metrics="$(measure_once)"
    assert_streaming "$metrics" || info "  request $run did not satisfy the checks"
    sleep 2
  done

  # The last request decides pass/fail, matching the previous script's contract.
  if assert_streaming "$metrics" >/dev/null; then
    pass "progressive token streaming confirmed"
    assert_streaming "$metrics" | sed 's/^/[bedrock-streaming] /'
  else
    assert_streaming "$metrics" | sed 's/^/[bedrock-streaming] /' || true
    fail "progressive token streaming NOT confirmed"
  fi

  info "============================================="
  info "Bedrock streaming test passed"
  info "============================================="
}

main
