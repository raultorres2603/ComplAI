#!/usr/bin/env bash
# localstack-init/init.sh
#
# Executed by LocalStack once it is ready (placed in /etc/localstack/init/ready.d/).
# Creates the S3 buckets and SQS queues used by ComplAI locally.
#
# LocalStack bundles the `awslocal` wrapper which pre-configures the endpoint
# and dummy credentials, so no extra flags are needed here.
#
# If you add more AWS resources (parameter-store entries, …) put them here
# rather than in application code — infrastructure-as-code, even for local.

set -euo pipefail

if ! command -v awslocal &>/dev/null; then
  echo "[init] ERROR: 'awslocal' not found on PATH. Install with: pip install awscli-local" >&2
  exit 1
fi

# ---- S3 buckets ----------------------------------------------------------------

create_bucket() {
  local bucket_name="$1"
  echo "[init] Creating S3 bucket: ${bucket_name}"
  if awslocal s3api head-bucket --bucket "${bucket_name}" 2>/dev/null; then
    echo "[init] Bucket '${bucket_name}' already exists — skipping creation."
  else
    awslocal s3 mb "s3://${bucket_name}"
    echo "[init] Bucket '${bucket_name}' created."
  fi
}

create_bucket "complai-local"
create_bucket "complai-complaints-local"

# ---- SQS queues ----------------------------------------------------------------

create_queue() {
  local queue_name="$1"
  echo "[init] Creating SQS queue: ${queue_name}"
  if awslocal sqs get-queue-url --queue-name "${queue_name}" 2>/dev/null; then
    echo "[init] Queue '${queue_name}' already exists — skipping creation."
  else
    awslocal sqs create-queue --queue-name "${queue_name}"
    echo "[init] Queue '${queue_name}' created."
  fi
}

create_queue "complai-redact-dlq-local"

# Create the main queue with a redrive policy pointing to the DLQ.
# LocalStack PERSISTENCE=1 keeps state across restarts; the idempotency guard above
# ensures we do not fail if the queue already exists from a previous run.
#
# Resolve the DLQ URL dynamically rather than using a hardcoded value: LocalStack v3
# switched to domain-style queue URLs by default, so a hard-coded http://localhost:4566/...
# URL causes get-queue-attributes to fail silently, which would drop the redrive policy.
DLQ_URL=$(awslocal sqs get-queue-url \
  --queue-name "complai-redact-dlq-local" \
  --query 'QueueUrl' --output text 2>/dev/null || echo "")

DLQ_ARN=""
if [ -n "${DLQ_URL}" ]; then
  DLQ_ARN=$(awslocal sqs get-queue-attributes \
    --queue-url "${DLQ_URL}" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' --output text 2>/dev/null || echo "")
fi
if [ -n "${DLQ_ARN}" ]; then
  # AWS CLI v2's shorthand notation cannot embed a JSON blob as an attribute value —
  # the " characters in the RedrivePolicy JSON cause a parse error.  Switch to JSON
  # format for --attributes and escape the inner RedrivePolicy string correctly.
  REDRIVE_POLICY_RAW="{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"3\"}"
  REDRIVE_POLICY_ESCAPED=$(printf '%s' "${REDRIVE_POLICY_RAW}" | sed 's/"/\\"/g')
  echo "[init] Creating SQS queue: complai-redact-local (with DLQ redrive)"
  if awslocal sqs get-queue-url --queue-name "complai-redact-local" 2>/dev/null; then
    echo "[init] Queue 'complai-redact-local' already exists — skipping creation."
  else
    awslocal sqs create-queue \
      --queue-name "complai-redact-local" \
      --attributes "{\"VisibilityTimeout\":\"90\",\"MessageRetentionPeriod\":\"14400\",\"RedrivePolicy\":\"${REDRIVE_POLICY_ESCAPED}\"}"
    echo "[init] Queue 'complai-redact-local' created."
  fi
else
  # DLQ ARN not resolvable (e.g. first boot with no persistence) — create without redrive.
  create_queue "complai-redact-local"
fi

echo "[init] LocalStack init complete."

