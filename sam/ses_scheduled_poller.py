#!/usr/bin/env python3
"""
ses_scheduled_poller.py

Invokes ComplAIScheduledReportFunction via `sam local invoke` on a cron-like
schedule, mirroring the EventBridge / CloudWatch Events trigger in real AWS.

Unlike SQS (which pushes to Lambda), a scheduled rule in SAM must be simulated
with a background loop that periodically calls the Lambda. This script does that.

Controlled by SES_REPORTING_ENABLED in env.json:
  - 'true'  → poll and invoke every 60 seconds
  - 'false' → skip silently

Usage (called by start-local.sh):
    python3 ses_scheduled_poller.py <template_path> <env_vars_path>
"""

import os
import subprocess
import sys
import tempfile
import time

# How often to re-invoke the Lambda locally (seconds).  In real AWS the
# EventBridge rule fires only once per week; locally we fire every 60 s so
# developers can exercise the path without waiting.
POLL_INTERVAL_SECONDS = 60

FUNCTION_NAME = "ComplAIScheduledReportFunction"
ENV_VAR_KEY = "SES_REPORTING_ENABLED"


def is_enabled(env_vars_path: str) -> bool:
    """Read env.json and return whether scheduled reporting is enabled."""
    try:
        import json
        with open(env_vars_path) as f:
            vars_ = json.load(f)
        # The top-level object maps parameter names to their per-environment values.
        # env.json uses a structure like: { "Parameters": { "KEY": "value" } }
        params = vars_.get("Parameters", vars_)  # handle both formats
        raw = params.get(ENV_VAR_KEY, "true").strip().lower()
        return raw not in ("false", "0", "no", "disabled")
    except Exception as exc:
        print(f"[ses-poller] WARNING: could not read {ENV_VAR_KEY} "
              f"from {env_vars_path} ({exc}) — defaulting to enabled.", file=sys.stderr)
        return True


def invoke_scheduled_report(template_path: str, env_vars_path: str) -> None:
    """Build a minimal ScheduledEvent and invoke the Lambda via sam local invoke."""
    scheduled_event = {
        "version": "0",
        "account": "000000000000",
        "region": "eu-west-1",
        "detail": {},
        "detail-type": "Scheduled Event",
        "source": "aws.events",
        "time": "2026-01-01T03:00:00Z",
        "id": "local-scheduled-invoke",
        "resources": [],
    }

    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False, prefix="complai-scheduled-event-"
    ) as f:
        import json
        json.dump(scheduled_event, f)
        event_path = f.name

    try:
        print("[ses-poller] Invoking ComplAIScheduledReportFunction...", flush=True)
        subprocess.run(
            [
                "sam",
                "local",
                "invoke",
                FUNCTION_NAME,
                "--template",
                template_path,
                "--env-vars",
                env_vars_path,
                "--event",
                event_path,
            ],
            check=False,
        )
    finally:
        os.unlink(event_path)


def main() -> None:
    if len(sys.argv) < 3:
        print(
            f"Usage: ses_scheduled_poller.py <template_path> <env_vars_path>",
            file=sys.stderr,
        )
        sys.exit(1)

    template_path = sys.argv[1]
    env_vars_path = sys.argv[2]

    if not is_enabled(env_vars_path):
        print(f"[ses-poller] {ENV_VAR_KEY}=false — scheduled report Lambda disabled. Exiting.", flush=True)
        sys.exit(0)

    print(f"[ses-poller] Starting — will invoke {FUNCTION_NAME} every {POLL_INTERVAL_SECONDS}s", flush=True)
    print("[ses-poller] Press Ctrl-C to stop.", flush=True)

    try:
        while True:
            invoke_scheduled_report(template_path, env_vars_path)
            time.sleep(POLL_INTERVAL_SECONDS)
    except KeyboardInterrupt:
        print("\n[ses-poller] Interrupted.", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[ses-poller] Interrupted.", flush=True)
        sys.exit(0)