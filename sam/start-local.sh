#!/usr/bin/env bash
# start-local.sh
#
# Convenience script for Linux / macOS / WSL only.
# On Windows PowerShell use start-local.ps1 instead:
#   cd sam\
#   .\start-local.ps1
#
# Prerequisites (must already be installed and on PATH):
#   - Docker (with the Compose plugin or standalone docker-compose)
#   - AWS SAM CLI  (https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
#   - Java 17 + Gradle wrapper in the project root
#   - Python 3 with venv module
#
# Usage (Linux / macOS / WSL):
#   cd sam/
#   ./start-local.sh
#
# The SAM local API will listen on http://127.0.0.1:3000 by default.
# A background SQS worker poller will invoke ComplAIRedactorFunction via
# `sam local invoke` whenever a message arrives on complai-redact-local,
# mirroring the Lambda/SQS integration that runs automatically in real AWS.
#
# Press Ctrl-C to stop both processes.
# LocalStack keeps running — use `docker compose down` to shut it down.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENV_DIR="${SCRIPT_DIR}/venv"

# ---------------------------------------------------------------------------
# 0. Set up Python virtual environment and install dependencies.
# ---------------------------------------------------------------------------
if [ ! -d "${VENV_DIR}" ]; then
  echo "[start-local] Creating Python virtual environment..."
  python3 -m venv "${VENV_DIR}"
fi

echo "[start-local] Activating virtual environment..."
source "${VENV_DIR}/bin/activate"

echo "[start-local] Installing Python dependencies..."
pip install -q boto3

# Fail fast with a clear message if sam is not on PATH.
# On Windows, sam is installed as a .exe and will not be found here unless
# you are in WSL with the Windows PATH forwarded. Use start-local.ps1 instead.
if ! command -v sam &>/dev/null; then
  echo "[start-local] ERROR: 'sam' not found on PATH." >&2
  echo "[start-local] On Windows, run: .\\start-local.ps1  (in PowerShell)" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. Build the shadow JAR from the project root.
# ---------------------------------------------------------------------------
echo "[start-local] Building shadow JAR..."
"${PROJECT_ROOT}/gradlew" --no-daemon -p "${PROJECT_ROOT}" clean shadowJar
echo "[start-local] Shadow JAR built."

# ---------------------------------------------------------------------------
# 1b. Verify exactly one shadow JAR exists in build/libs/.
#     `clean` removes stale JARs; `shadowJar` produces only the fat JAR.
#     If somehow multiple JARs are present (e.g. someone ran `jar` separately),
#     fail fast here rather than letting SAM silently package the wrong one.
# ---------------------------------------------------------------------------
LIBS_DIR="${PROJECT_ROOT}/build/libs"
SHADOW_JAR_COUNT=$(find "${LIBS_DIR}" -maxdepth 1 -name '*-all.jar' 2>/dev/null | wc -l | tr -d ' ')

if [ "${SHADOW_JAR_COUNT}" -eq 0 ]; then
  echo "[start-local] ERROR: No shadow JAR (*-all.jar) found in ${LIBS_DIR} after build." >&2
  exit 1
fi
if [ "${SHADOW_JAR_COUNT}" -gt 1 ]; then
  echo "[start-local] ERROR: Multiple shadow JARs found in ${LIBS_DIR} — cannot determine which to use:" >&2
  find "${LIBS_DIR}" -maxdepth 1 -name '*-all.jar' | sed 's/^/  /' >&2
  echo "[start-local] Run clean and try again." >&2
  exit 1
fi
SHADOW_JAR=$(find "${LIBS_DIR}" -maxdepth 1 -name '*-all.jar')
echo "[start-local] Shadow JAR confirmed: $(basename "${SHADOW_JAR}")"

# ---------------------------------------------------------------------------
# 2. Start LocalStack (detached) and wait for it to be healthy.
# ---------------------------------------------------------------------------
echo "[start-local] Starting LocalStack..."
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" up -d

echo "[start-local] Waiting for LocalStack to be healthy..."
RETRIES=20
until docker inspect --format='{{.State.Health.Status}}' complai-localstack 2>/dev/null | grep -q "healthy"; do
  RETRIES=$((RETRIES - 1))
  if [ "$RETRIES" -le 0 ]; then
    echo "[start-local] ERROR: LocalStack did not become healthy in time." >&2
    exit 1
  fi
  sleep 3
done
echo "[start-local] LocalStack is healthy."

# ---------------------------------------------------------------------------
# 2b. Wait for the init script to have created the SQS queue.
#     LocalStack's health check passes as soon as the HTTP gateway is up,
#     which is before the ready.d init scripts finish executing.  If SAM
#     receives a request before the queue exists we get QueueDoesNotExistException.
#     We poll via docker exec so we don't need the aws CLI on the host.
# ---------------------------------------------------------------------------
echo "[start-local] Waiting for SQS queue 'complai-redact-local' to be ready..."
QUEUE_RETRIES=20
until docker exec complai-localstack \
    awslocal sqs get-queue-url --queue-name complai-redact-local \
    --output text 2>/dev/null | grep -q "complai-redact-local"; do
  QUEUE_RETRIES=$((QUEUE_RETRIES - 1))
  if [ "${QUEUE_RETRIES}" -le 0 ]; then
    echo "[start-local] ERROR: SQS queue 'complai-redact-local' was not created in time." >&2
    echo "[start-local] Check LocalStack init logs:" >&2
    echo "[start-local]   docker compose -f ${SCRIPT_DIR}/docker-compose.yml logs localstack" >&2
    exit 1
  fi
  sleep 2
done
echo "[start-local] SQS queue is ready."

# ---------------------------------------------------------------------------
# 3. SQS worker poller.
#
#    sam local start-api handles only HTTP/API Gateway events — it never polls
#    SQS.  sqs_worker_poller.py closes that gap by long-polling
#    complai-redact-local via boto3 (directly against LocalStack) and invoking
#    ComplAIRedactorFunction via `sam local invoke` for each message, mirroring
#    the Lambda/SQS integration that runs automatically in real AWS.
#
#    A standalone Python script is used instead of an inline bash function
#    because a bash background subshell inherits set -euo pipefail and any
#    subtle failure (empty receive-message response, docker exec quirk) kills
#    the subshell silently.  Python handles errors explicitly and keeps running.
#
#    --warm-containers LAZY is used for the API below so that EAGER mode does
#    not pre-start the worker container and conflict with sam local invoke.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 4. Start the API Lambda and the SQS worker poller as background processes.
# ---------------------------------------------------------------------------
echo "[start-local] Starting SAM local API on http://127.0.0.1:3000 ..."
echo "[start-local] Make sure env.json contains your OPENROUTER_API_KEY."
echo ""

sam local start-api \
  --template "${SCRIPT_DIR}/template.yaml" \
  --env-vars "${SCRIPT_DIR}/env.json" \
  --warm-containers LAZY \
  --host 127.0.0.1 \
  --port 3000 &
SAM_API_PID=$!

python3 "${SCRIPT_DIR}/sqs_worker_poller.py" \
  "${SCRIPT_DIR}/template.yaml" \
  "${SCRIPT_DIR}/env.json" &
SQS_WORKER_PID=$!

# ---------------------------------------------------------------------------
# 5. Clean up both background processes on Ctrl-C / SIGTERM.
# ---------------------------------------------------------------------------
cleanup() {
  echo ""
  echo "[start-local] Shutting down SAM API and SQS worker poller..."
  kill "${SAM_API_PID}" "${SQS_WORKER_PID}" 2>/dev/null || true
  wait "${SAM_API_PID}" "${SQS_WORKER_PID}" 2>/dev/null || true
  echo "[start-local] Stopped. LocalStack is still running — run 'docker compose down' to stop it."
  exit 0
}
trap cleanup INT TERM

wait "${SAM_API_PID}"
