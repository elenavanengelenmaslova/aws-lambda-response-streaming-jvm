#!/usr/bin/env bash
#
# record-streaming-demo.sh — records the Bedrock SSE demo from the REAL deployed endpoint, as a GIF
# for the repository and an MP4 for AWS Builder Center / YouTube.
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
#   OUT_GIF               GIF output path, "" to skip      (default: docs/bedrock-streaming-demo-new.gif)
#   OUT_MP4               MP4 output path, "" to skip      (default: docs/bedrock-streaming-demo.mp4)
#   OUT_CAST              keep the asciicast here          (optional; lets you re-render for free)
#   CAST_IN               re-render this cast instead of recording (no Bedrock call, no AWS calls)
#   COLS / ROWS           terminal size for the render     (default: 100 / 20)
#   FONT_SIZE             agg font size, drives pixel size (default: 16)
#   VIDEO_HEIGHT          MP4 height; width pads to 16:9   (default: 1080)
#   PAD_COLOR             MP4 letterbox colour             (default: 0x282A36, agg's dracula bg)
#   SPEED                 agg playback speed               (default: 1)
#   IDLE_LIMIT            agg idle-time cap, seconds       (default: 1)
#   LAST_FRAME            how long the final frame holds   (default: 1.5)
#
# The MP4 is what AWS Builder Center and YouTube need, since Builder Center does not take a GIF.
# Rendering goes through agg either way: cast -> GIF -> H.264. Keep the cast (OUT_CAST) and you can
# re-render at any size or frame rate without invoking Bedrock again.
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
OUT_GIF="${OUT_GIF-docs/bedrock-streaming-demo-new.gif}"
OUT_MP4="${OUT_MP4-docs/bedrock-streaming-demo.mp4}"
OUT_CAST="${OUT_CAST-}"
CAST_IN="${CAST_IN-}"
COLS="${COLS:-100}"
ROWS="${ROWS:-20}"
FONT_SIZE="${FONT_SIZE:-16}"
VIDEO_HEIGHT="${VIDEO_HEIGHT:-1080}"
PAD_COLOR="${PAD_COLOR:-0x282A36}"
SPEED="${SPEED:-1}"
IDLE_LIMIT="${IDLE_LIMIT:-1}"
LAST_FRAME="${LAST_FRAME:-1.5}"

AWS_REGION="${AWS_REGION:-}"
region_args=()
[[ -n "$AWS_REGION" ]] && region_args=(--region "$AWS_REGION")

info() { printf '[record] %s\n' "$*"; }
fail() { printf '[record] ERROR: %s\n' "$*" >&2; exit 1; }

# Re-rendering an existing cast touches neither Bedrock nor AWS, so the cost guard does not apply.
if [[ -z "$CAST_IN" && "$BEDROCK_TEST_ENABLED" != "true" ]]; then
  info 'BEDROCK_TEST_ENABLED is not "true"; skipping (recording invokes paid Bedrock).'
  exit 0
fi

[[ -n "$OUT_GIF" || -n "$OUT_MP4" ]] || fail "nothing to render: set OUT_GIF and/or OUT_MP4"

for tool in curl aws python3 agg; do
  command -v "$tool" >/dev/null 2>&1 || fail "missing required tool: $tool"
done
[[ -z "$OUT_MP4" ]] || command -v ffmpeg >/dev/null 2>&1 || fail "OUT_MP4 needs ffmpeg on PATH"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

stack_output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" "${region_args[@]}" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text 2>/dev/null
}

if [[ -n "$CAST_IN" ]]; then
  [[ -s "$CAST_IN" ]] || fail "CAST_IN does not exist or is empty: $CAST_IN"
  CAST="$CAST_IN"
  info "re-rendering $CAST_IN (no endpoint call)"
else

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

fi  # end of record-vs-reuse

if [[ -n "$OUT_CAST" && "$OUT_CAST" != "$CAST" ]]; then
  mkdir -p "$(dirname "$OUT_CAST")"
  cp "$CAST" "$OUT_CAST"
  info "cast kept at $OUT_CAST (re-render from it without calling Bedrock again)"
fi

# agg is the only renderer, so the GIF is always produced; the MP4 is encoded from it. 30 fps is
# agg's cap and plenty for a terminal, and it keeps the token arrivals distinguishable.
RENDERED_GIF="$WORK_DIR/demo.gif"
info "rendering with agg (font-size=$FONT_SIZE, ${COLS}x${ROWS}, speed=$SPEED)"
agg --speed "$SPEED" --idle-time-limit "$IDLE_LIMIT" --last-frame-duration "$LAST_FRAME" \
  --fps-cap 30 --font-size "$FONT_SIZE" --cols "$COLS" --rows "$ROWS" \
  "$CAST" "$RENDERED_GIF" >/dev/null 2>&1 \
  || fail "agg failed to render"

frames="$(ffprobe -v error -count_frames -select_streams v:0 \
  -show_entries stream=nb_read_frames -of csv=p=0 "$RENDERED_GIF" 2>/dev/null || echo '?')"
info "rendered $frames frames at $(ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height -of csv=p=0:s=x "$RENDERED_GIF" 2>/dev/null || echo '?')"

if [[ -n "$OUT_GIF" ]]; then
  mkdir -p "$(dirname "$OUT_GIF")"
  cp "$RENDERED_GIF" "$OUT_GIF"
  info "wrote $OUT_GIF ($(du -h "$OUT_GIF" | cut -f1 | tr -d ' '))"
fi

if [[ -n "$OUT_MP4" ]]; then
  mkdir -p "$(dirname "$OUT_MP4")"
  # Scaled to VIDEO_HEIGHT and padded to 16:9 in the terminal's own background colour, so YouTube
  # gets a standard frame instead of letterboxing it in black. yuv420p + faststart for players that
  # will not touch anything else.
  info "encoding $OUT_MP4 (H.264, ${VIDEO_HEIGHT}p, 16:9)"
  ffmpeg -y -hide_banner -loglevel error -i "$RENDERED_GIF" \
    -vf "fps=30,scale=-2:${VIDEO_HEIGHT}:flags=lanczos,pad=ceil(ih*16/9/2)*2:ih:(ow-iw)/2:0:color=${PAD_COLOR}" \
    -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 18 -preset slow \
    -movflags +faststart -an "$OUT_MP4" \
    || fail "ffmpeg failed to encode $OUT_MP4"
  info "wrote $OUT_MP4 ($(du -h "$OUT_MP4" | cut -f1 | tr -d ' '), $(ffprobe -v error \
    -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$OUT_MP4"), \
$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT_MP4")s)"
fi

info "done"
