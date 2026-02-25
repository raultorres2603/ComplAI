# start-local.ps1
#
# Convenience script to build the shadow JAR, start LocalStack, and launch
# SAM CLI local API — run from the sam/ directory in PowerShell.
#
# Prerequisites (must already be installed and on PATH):
#   - Docker (with the Compose plugin)
#   - AWS SAM CLI
#   - Java 17 + Gradle wrapper in the project root
#
# Usage:
#   cd sam\
#   .\start-local.ps1
#
# The SAM local API will listen on http://127.0.0.1:3000 by default.
# Press Ctrl-C to stop SAM; then run `docker compose down` to stop LocalStack.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir '..')

# ---------------------------------------------------------------------------
# 1. Build the shadow JAR from the project root.
# ---------------------------------------------------------------------------
Write-Host '[start-local] Building shadow JAR...' -ForegroundColor Cyan
& "$ProjectRoot\gradlew.bat" --no-daemon -p "$ProjectRoot" clean shadowJar
if ($LASTEXITCODE -ne 0) {
    Write-Error '[start-local] Gradle build failed. Aborting.'
    exit 1
}
Write-Host '[start-local] Shadow JAR built.' -ForegroundColor Green

# ---------------------------------------------------------------------------
# 1b. Verify exactly one shadow JAR exists in build/libs/.
#     `clean` removes stale JARs; `shadowJar` produces only the fat JAR.
#     If somehow multiple JARs are present (e.g. someone ran `jar` separately),
#     fail fast here rather than letting SAM silently package the wrong one.
# ---------------------------------------------------------------------------
$LibsDir = Join-Path $ProjectRoot 'build\libs'
$ShadowJars = @(Get-ChildItem -Path $LibsDir -Filter '*-all.jar' -ErrorAction SilentlyContinue)

if ($ShadowJars.Count -eq 0) {
    Write-Error "[start-local] No shadow JAR (*-all.jar) found in $LibsDir after build. Aborting."
    exit 1
}
if ($ShadowJars.Count -gt 1) {
    Write-Error "[start-local] Multiple shadow JARs found in $LibsDir — cannot determine which to use: $($ShadowJars.Name -join ', '). Run clean and try again."
    exit 1
}
Write-Host "[start-local] Shadow JAR confirmed: $($ShadowJars[0].Name)" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 2. Start LocalStack (detached) and wait for it to be healthy.
# ---------------------------------------------------------------------------
Write-Host '[start-local] Starting LocalStack...' -ForegroundColor Cyan
docker compose -f "$ScriptDir\docker-compose.yml" up -d
if ($LASTEXITCODE -ne 0) {
    Write-Error '[start-local] docker compose up failed. Aborting.'
    exit 1
}

Write-Host '[start-local] Waiting for LocalStack to be healthy...' -ForegroundColor Cyan
$retries = 20
$healthy = $false
while ($retries -gt 0) {
    $status = docker inspect --format='{{.State.Health.Status}}' complai-localstack 2>$null
    if ($status -eq 'healthy') {
        $healthy = $true
        break
    }
    $retries--
    Start-Sleep -Seconds 3
}

if (-not $healthy) {
    Write-Error '[start-local] LocalStack did not become healthy in time. Aborting.'
    exit 1
}
Write-Host '[start-local] LocalStack is healthy.' -ForegroundColor Green

# ---------------------------------------------------------------------------
# 3. Start SAM CLI local API.
#    --env-vars          injects environment variables from env.json.
#    --warm-containers   keeps the JVM container alive between requests to
#                        avoid a cold start on every single local invocation.
# ---------------------------------------------------------------------------
Write-Host '[start-local] Starting SAM local API on http://127.0.0.1:3000 ...' -ForegroundColor Cyan
Write-Host '[start-local] Make sure env.json contains your OPENROUTER_API_KEY.' -ForegroundColor Yellow
Write-Host ''

sam local start-api `
    --template "$ScriptDir\template.yaml" `
    --env-vars "$ScriptDir\env.json" `
    --warm-containers EAGER `
    --host 127.0.0.1 `
    --port 3000

