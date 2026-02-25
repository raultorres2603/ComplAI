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
#
# Usage (Linux / macOS / WSL):
#   cd sam/
#   ./start-local.sh
#
# The SAM local API will listen on http://127.0.0.1:3000 by default.
# Press Ctrl-C to stop SAM; then run `docker compose down` to stop LocalStack.

set -euo pipefail

# Fail fast with a clear message if sam is not on PATH.
# On Windows, sam is installed as a .exe and will not be found here unless
# you are in WSL with the Windows PATH forwarded. Use start-local.ps1 instead.
if ! command -v sam &>/dev/null; then
  echo "[start-local] ERROR: 'sam' not found on PATH." >&2
  echo "[start-local] On Windows, run: .\\start-local.ps1  (in PowerShell)" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

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
# 3. Start SAM CLI local API.
#    --env-vars  injects environment variables (including OPENROUTER_API_KEY)
#                from env.json — edit that file before running.
#    --warm-containers EAGER  keeps the JVM container alive between requests
#                             so cold-start latency only hits the first call.
# ---------------------------------------------------------------------------
echo "[start-local] Starting SAM local API on http://127.0.0.1:3000 ..."
echo "[start-local] Make sure env.json contains your OPENROUTER_API_KEY."
echo ""

sam local start-api \
  --template "${SCRIPT_DIR}/template.yaml" \
  --env-vars "${SCRIPT_DIR}/env.json" \
  --warm-containers EAGER \
  --host 127.0.0.1 \
  --port 3000

