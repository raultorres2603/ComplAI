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
# Utility function: Check if a command exists on PATH.
# ---------------------------------------------------------------------------
check_command_exists() {
  local cmd="$1"
  local friendly_name="${2:-$cmd}"
  if ! command -v "$cmd" &>/dev/null; then
    echo "[start-local] ERROR: Required command '$friendly_name' not found on PATH." >&2
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Utility function: Check Python 3 installation and version.
# ---------------------------------------------------------------------------
check_python3() {
  if ! check_command_exists "python3"; then
    echo "[start-local] ERROR: Python 3 is not installed or not on PATH." >&2
    echo "[start-local] On Ubuntu/Debian, install it with:" >&2
    echo "[start-local]   sudo apt-get update && sudo apt-get install -y python3 python3-venv" >&2
    return 1
  fi

  # Check Python version (must be at least 3.8)
  local py_version
  py_version=$(python3 --version 2>&1 | awk '{print $2}')
  local major_minor="${py_version%.*}"
  local min_version="3.8"

  if ! python3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" 2>/dev/null; then
    echo "[start-local] ERROR: Python version $py_version is too old. Minimum required: $min_version" >&2
    echo "[start-local] On Ubuntu/Debian, upgrade with:" >&2
    echo "[start-local]   sudo apt-get install -y python3" >&2
    return 1
  fi

  echo "[start-local] ✓ Python 3 ($py_version) is installed."
  return 0
}

# ---------------------------------------------------------------------------
# Utility function: Check if Docker and docker-compose are available.
# ---------------------------------------------------------------------------
check_docker() {
  if ! check_command_exists "docker"; then
    echo "[start-local] ERROR: Docker is not installed or not on PATH." >&2
    echo "[start-local] On Ubuntu/Debian, install it with:" >&2
    echo "[start-local]   sudo apt-get update && sudo apt-get install -y docker.io docker-compose" >&2
    echo "[start-local] Then add your user to the docker group:" >&2
    echo "[start-local]   sudo usermod -aG docker \$USER && newgrp docker" >&2
    return 1
  fi
  echo "[start-local] ✓ Docker is installed."

  # Check for docker-compose (either as plugin or standalone)
  if docker compose version &>/dev/null; then
    echo "[start-local] ✓ Docker Compose (plugin) is available."
  elif command -v docker-compose &>/dev/null; then
    echo "[start-local] ✓ Docker Compose (standalone) is available."
  else
    echo "[start-local] ERROR: Docker Compose is not installed or not available as a plugin." >&2
    echo "[start-local] On Ubuntu/Debian, install it with:" >&2
    echo "[start-local]   sudo apt-get install -y docker-compose" >&2
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Utility function: Check if Java/Gradle is available.
# ---------------------------------------------------------------------------
check_gradle() {
  if [ ! -f "${PROJECT_ROOT}/gradlew" ]; then
    echo "[start-local] ERROR: Gradle wrapper not found at ${PROJECT_ROOT}/gradlew" >&2
    echo "[start-local] Make sure you are running this script from the sam/ directory of the ComplAI project." >&2
    return 1
  fi
  echo "[start-local] ✓ Gradle wrapper is available."
  return 0
}

# ---------------------------------------------------------------------------
# 0. Validate all prerequisites before starting.
# ---------------------------------------------------------------------------
echo "[start-local] Checking prerequisites..."
check_python3 || exit 1
check_docker || exit 1
check_gradle || exit 1
check_command_exists "sam" "AWS SAM CLI" || {
  echo "[start-local] On Windows, run: .\\start-local.ps1  (in PowerShell)" >&2
  exit 1
}
echo "[start-local] ✓ AWS SAM CLI is installed."
echo "[start-local] All prerequisites satisfied."
echo ""

# ---------------------------------------------------------------------------
# 1. Set up Python virtual environment and install dependencies.
# ---------------------------------------------------------------------------
# Check if venv directory exists AND has critical files (pip, python3)
if [ ! -d "${VENV_DIR}" ] || [ ! -f "${VENV_DIR}/bin/pip" ] || [ ! -f "${VENV_DIR}/bin/python3" ]; then
  if [ -d "${VENV_DIR}" ]; then
    echo "[start-local] Virtual environment directory exists but is incomplete/corrupted. Removing and recreating..."
    rm -rf "${VENV_DIR}"
  else
    echo "[start-local] Creating Python virtual environment..."
  fi
  
  if ! python3 -m venv "${VENV_DIR}" 2>/dev/null; then
    echo "[start-local] ERROR: Failed to create Python virtual environment at ${VENV_DIR}" >&2
    echo "[start-local] This may be due to missing venv module. On Ubuntu/Debian, install it with:" >&2
    echo "[start-local]   sudo apt-get install -y python3-venv" >&2
    exit 1
  fi
  echo "[start-local] ✓ Virtual environment created."
else
  echo "[start-local] ✓ Virtual environment already exists and is valid."
fi

echo "[start-local] Installing Python dependencies..."
if ! "${VENV_DIR}/bin/pip" install boto3; then
  echo "[start-local] ERROR: Failed to install Python dependencies." >&2
  exit 1
fi
echo "[start-local] ✓ Python dependencies installed."

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

"${VENV_DIR}/bin/python3" "${SCRIPT_DIR}/sqs_worker_poller.py" \
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
