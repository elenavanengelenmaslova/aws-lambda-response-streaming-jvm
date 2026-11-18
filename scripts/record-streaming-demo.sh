#!/usr/bin/env bash
#
# record-streaming-demo.sh — records the Bedrock SSE demo GIF from the REAL deployed endpoint.
#
# Why this exists rather than `asciinema rec --command '<curl | sed | awk>'`: under a long single
# pipeline the PTY capture coalesced the output timing, so the recording showed one burst even
# though the same pipeline measured smooth progressive arrival when run directly. This reads the
# live stream itself and stamps each token at its true wall-clock arrival into an asciicast, so the
# frames carry the timing the client actually saw.
#
# COST GUARD: invokes Amazon Bedrock. Opt in with BEDROCK_TEST_ENABLED=true.
#
# Environment:
#   BEDROCK_TEST_ENABLED  must be "true"                  (default: unset -> skip)
#   STACK_NAME            CloudFormation stack            (default: bedrock-streaming-endpoint)
#   AWS_REGION            region                          (optional; uses CLI default)
#   PROMPT                prompt text                     (default: a short SSE question)
#   MAX_TOKENS            generation length               (default: 400, keeps the GIF short)
#   OUT_GIF               output path                      (default: docs/bedrock-streaming-demo-new.gif)
#   COLS / ROWS           terminal size for the render     (default: 100 / 20)
#   SPEED                 agg playback speed               (default: 1)
#
# Secret hygiene: the command shown on screen contains the literal strings $ENDPOINT_URL and
# $API_KEY, never their values, and the finished cast is scanned for the real key and the
# execute-api host before rendering. Rendering aborts if either appears.
#
set -euo pipefail

STACK_NAME="${STACK_NAME:-bedrock-streaming-endpoint}"
BEDROCK_TEST_ENABLED="${BEDROCK_TEST_ENABLED:-}"
PROMPT="${PROMPT:-Explain in three sentences why Server-Sent Events suit streaming LLM output.}"
MAX_TOKENS="${MAX_TOKENS:-400}"
OUT_GIF="${OUT_GIF:-docs/bedrock-streaming-demo-new.gif}"
COLS="${COLS:-100}"
ROWS="${ROWS:-20}"
SPEED="${SPEED:-1}"

AWS_REGION="${AWS_REGION:-}"
region_args=()
[[ -n "$AWS_REGION" ]] && region_args=(--region "$AWS_REGION")

info() { printf '[record] %s\n' "$*"; }
fail() { printf '[record] ERROR: %s\n' "$*" >&2; exit 1; }

if [[ "$BEDROCK_TEST_ENABLED" != "true" ]]; then
  info 'BEDROCK_TEST_ENABLED is not "true"; skipping (recording invokes paid Bedrock).'
  exit 0
fi

for tool in curl aws python3 agg; do
  command -v "$tool" >/dev/null 2>&1 || fail "missing required tool: $tool"
done

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

stack_output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" "${region_args[@]}" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text 2>/dev/null
}

ENDPOINT_URL="${ENDPOINT_URL:-$(stack_output StreamingEndpointUrl)}"
[[ -n "${ENDPOINT_URL:-}" && "$ENDPOINT_URL" == https://* ]] \
  || fail "could not resolve an HTTPS ENDPOINT_URL from stack '$STACK_NAME'"

if [[ -z "${API_KEY:-}" ]]; then
  key_id="$(stack_output ApiKeyId)"
  [[ -n "$key_id" && "$key_id" != "None" ]] || fail "could not resolve ApiKeyId"
  API_KEY="$(aws apigateway get-api-key --api-key "$key_id" --include-value "${region_args[@]}" \
    --query 'value' --output text 2>/dev/null || true)"
fi
[[ -n "${API_KEY:-}" ]] || fail "could not resolve API_KEY"
info "endpoint and API key resolved (masked)"

# Confirm the deployed alias actually streams, so a buffered endpoint is never recorded as if it
# streamed. A missing -javaagent means the response is correct but sent only after the handler ends.
function_name="$(stack_output FunctionName)"
if [[ -n "$function_name" && "$function_name" != "None" ]]; then
  version="$(aws lambda get-alias --function-name "$function_name" --name live \
    "${region_args[@]}" --query 'FunctionVersion' --output text 2>/dev/null || true)"
  options="$(aws lambda get-function-configuration --function-name "${function_name}:${version}" \
    "${region_args[@]}" --query 'Environment.Variables.JAVA_TOOL_OPTIONS' --output text 2>/dev/null || true)"
  [[ "$options" == *"-javaagent"* ]] \
    || fail "alias 'live' (version $version) has no -javaagent: this endpoint would NOT stream"
  info "alias 'live' is version $version with the streaming agent"
fi

info "warmup request (discarded, keeps a cold start out of the recording)"
curl -sN --http1.1 --max-time 120 -H "x-api-key: $API_KEY" -H 'Content-Type: application/json' \
  "${ENDPOINT_URL%/}/stream" -d '{"prompt":"hi","maxTokens":16}' -o /dev/null || true
sleep 2

CAST="$WORK_DIR/demo.cast"
info "recording from the live endpoint (maxTokens=$MAX_TOKENS)"

# The reader consumes the SSE stream and renders exactly what the on-screen pipeline would show:
# strip the "data: " prefix and concatenate, with no newline added per event. Each write is stamped
# at its real arrival time.
API_KEY="$API_KEY" ENDPOINT_URL="$ENDPOINT_URL" PROMPT="$PROMPT" MAX_TOKENS="$MAX_TOKENS" \
COLS="$COLS" ROWS="$ROWS" CAST="$CAST" python3 <<'PY'
import json, os, subprocess, time

cols, rows = int(os.environ["COLS"]), int(os.environ["ROWS"])
endpoint = os.environ["ENDPOINT_URL"].rstrip("/")
prompt, max_tokens = os.environ["PROMPT"], int(os.environ["MAX_TOKENS"])

# Shown on screen. Uses the literal variable names, so no secret is ever rendered. The real prompt
# is shown — it is not a secret, and a viewer should see what was asked.
shown = (
    "curl -N --http1.1 -H \"x-api-key: $API_KEY\" -H 'Content-Type: application/json' \\\n"
    f"  \"$ENDPOINT_URL/stream\" \\\n"
    f"  -d '{{\"prompt\":\"{prompt}\",\"maxTokens\":{max_tokens}}}' \\\n"
    "  | sed -u -n 's/^data: //p' | awk '{printf \"%s\",$0; fflush()}'"
)

events = []
# A short pause before "pressing enter", so the command is readable in the first frames.
events.append([0.0, "o", "$ " + shown.replace("\n", "\r\n") + "\r\n"])
request_at = 1.2

body = json.dumps({"prompt": prompt, "maxTokens": max_tokens})
argv = [
    "curl", "-sN", "--http1.1", "--max-time", "240",
    "-H", f"x-api-key: {os.environ['API_KEY']}",
    "-H", "Content-Type: application/json",
    f"{endpoint}/stream", "-d", body,
]

started = time.monotonic()
first = None
proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
assert proc.stdout is not None
for raw in iter(proc.stdout.readline, b""):
    line = raw.decode("utf-8", "replace")
    if not line.startswith("data: "):
        continue
    payload = line[6:].rstrip("\n")
    now = time.monotonic() - started
    if first is None:
        first = now
    events.append([round(request_at + now, 4), "o", payload])
proc.wait()

events.append([round(request_at + (time.monotonic() - started) + 0.6, 4), "o", "\r\n$ "])

header = {
    "version": 2,
    "width": cols,
    "height": rows,
    "timestamp": int(time.time()),
    "env": {"SHELL": "/bin/zsh", "TERM": "xterm-256color"},
}
with open(os.environ["CAST"], "w", encoding="utf-8") as out:
    out.write(json.dumps(header) + "\n")
    for event in events:
        out.write(json.dumps(event) + "\n")

tokens = len(events) - 2
print(f"[record] captured {tokens} events; first token at {first:.3f}s, "
      f"last at {events[-2][0] - request_at:.3f}s after the request")
PY

[[ -s "$CAST" ]] || fail "no cast produced"

# Secret scan before anything is rendered or written outside the work dir.
host="${ENDPOINT_URL#https://}"; host="${host%%/*}"
if grep -qF "$API_KEY" "$CAST"; then fail "the API key appears in the cast; refusing to render"; fi
if grep -qF "$host" "$CAST"; then fail "the execute-api host appears in the cast; refusing to render"; fi
info "secret scan clean (no API key, no execute-api host in the cast)"

mkdir -p "$(dirname "$OUT_GIF")"
info "rendering $OUT_GIF with agg (speed=$SPEED)"
agg --speed "$SPEED" --idle-time-limit 2 --font-size 16 --cols "$COLS" --rows "$ROWS" \
  "$CAST" "$OUT_GIF" >/dev/null 2>&1 || fail "agg failed to render"

info "done: $OUT_GIF ($(du -h "$OUT_GIF" | cut -f1))"
