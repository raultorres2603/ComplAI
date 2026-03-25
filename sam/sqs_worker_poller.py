#!/usr/bin/env python3
"""
sqs_worker_poller.py

Polls both the complai-redact-local and complai-feedback-local SQS queues on LocalStack
and invokes the corresponding Lambda functions via `sam local invoke` for each message,
mirroring the Lambda/SQS integration that runs automatically in real AWS.

Usage (called by start-local.sh):
    python3 sqs_worker_poller.py <template_path> <env_vars_path>

sam local start-api handles only HTTP/API Gateway events — it never polls SQS.
This script fills that gap for local development, using threading to poll multiple queues.
"""

import json
import os
import subprocess
import sys
import tempfile
import threading
import time

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

QUEUES = [
    {"name": "complai-redact-local", "function": "ComplAIRedactorFunction"},
    {"name": "complai-feedback-local", "function": "ComplAIFeedbackWorkerFunction"},
]

REGION = "eu-west-1"
LOCALSTACK_ENDPOINT = "http://localhost:4566"
QUEUE_ARN_PREFIX = f"arn:aws:sqs:{REGION}:000000000000"

# Long-poll window: blocks up to this many seconds when the queue is empty,
# so the loop does not busy-spin.  Must be <= 20 (SQS maximum).
LONG_POLL_SECONDS = 5


def build_sqs_event(message: dict, queue_name: str) -> dict:
    """Wrap a raw SQS message in the Lambda event envelope the handler expects."""
    return {
        "Records": [
            {
                "messageId": message["MessageId"],
                "receiptHandle": message["ReceiptHandle"],
                "body": message["Body"],
                "attributes": {},
                "messageAttributes": {},
                "md5OfBody": message.get("MD5OfBody", ""),
                "eventSource": "aws:sqs",
                "eventSourceARN": f"{QUEUE_ARN_PREFIX}:{queue_name}",
                "awsRegion": REGION,
            }
        ]
    }


def invoke_worker(
    template_path: str, env_vars_path: str, event: dict, function_name: str
) -> None:
    """Invoke the specified Lambda function via sam local invoke with the given SQS event."""
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False, prefix="complai-sqs-event-"
    ) as f:
        json.dump(event, f)
        event_path = f.name

    try:
        subprocess.run(
            [
                "sam",
                "local",
                "invoke",
                function_name,
                "--template",
                template_path,
                "--env-vars",
                env_vars_path,
                "--event",
                event_path,
            ],
            check=False,  # don't raise on non-zero — log and continue polling
        )
    finally:
        os.unlink(event_path)


def poll_queue(
    queue_name: str,
    function_name: str,
    template_path: str,
    env_vars_path: str,
) -> None:
    """Poll a single queue and invoke the corresponding Lambda function."""
    sqs = boto3.client(
        "sqs",
        region_name=REGION,
        endpoint_url=LOCALSTACK_ENDPOINT,
        # LocalStack accepts any credentials locally.
        aws_access_key_id="test",
        aws_secret_access_key="test",
        config=Config(retries={"max_attempts": 0}),
    )

    try:
        queue_url = sqs.get_queue_url(QueueName=queue_name)["QueueUrl"]
    except (BotoCoreError, ClientError) as exc:
        print(
            f"[worker] ERROR: Could not resolve queue URL for '{queue_name}': {exc}",
            file=sys.stderr,
            flush=True,
        )
        return

    print(
        f"[worker] Polling SQS queue: {queue_url} (function: {function_name})",
        flush=True,
    )

    while True:
        try:
            response = sqs.receive_message(
                QueueUrl=queue_url,
                MaxNumberOfMessages=1,
                WaitTimeSeconds=LONG_POLL_SECONDS,
            )
        except KeyboardInterrupt:
            print(
                f"\n[worker] Interrupted on queue {queue_name}.",
                flush=True,
            )
            return
        except (BotoCoreError, ClientError) as exc:
            print(
                f"[worker] ERROR receiving message from {queue_name}: {exc}",
                file=sys.stderr,
                flush=True,
            )
            time.sleep(2)
            continue

        messages = response.get("Messages", [])
        if not messages:
            continue

        message = messages[0]
        print(
            f"[worker] Message received on {queue_name} — invoking {function_name}...",
            flush=True,
        )

        event = build_sqs_event(message, queue_name)
        invoke_worker(template_path, env_vars_path, event, function_name)

        # Delete the message after invocation.  In real AWS the Lambda/SQS
        # integration deletes on success and lets visibility timeout expire on
        # failure (→ DLQ).  Locally we always delete to prevent re-delivery loops.
        try:
            sqs.delete_message(
                QueueUrl=queue_url,
                ReceiptHandle=message["ReceiptHandle"],
            )
            print(f"[worker] Message deleted from {queue_name}.", flush=True)
        except (BotoCoreError, ClientError) as exc:
            print(
                f"[worker] WARNING: could not delete message from {queue_name}: {exc}",
                file=sys.stderr,
                flush=True,
            )


def main() -> None:
    if len(sys.argv) < 3:
        print(
            "Usage: sqs_worker_poller.py <template_path> <env_vars_path>",
            file=sys.stderr,
        )
        sys.exit(1)

    template_path = sys.argv[1]
    env_vars_path = sys.argv[2]

    # Start a thread for each queue
    threads = []
    for queue_config in QUEUES:
        queue_name = queue_config["name"]
        function_name = queue_config["function"]

        thread = threading.Thread(
            target=poll_queue,
            args=(queue_name, function_name, template_path, env_vars_path),
            daemon=True,
        )
        thread.start()
        threads.append(thread)

    # Keep main thread alive
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[worker] Interrupted.", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[worker] Interrupted.", flush=True)
        sys.exit(0)

