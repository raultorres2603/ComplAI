#!/usr/bin/env bash
# localstack-init/init.sh
#
# Executed by LocalStack once it is ready (placed in /etc/localstack/init/ready.d/).
# Creates the S3 bucket used by ComplAI locally.
#
# LocalStack bundles the `awslocal` wrapper which pre-configures the endpoint
# and dummy credentials, so no extra flags are needed here.
#
# If you add more AWS resources (queues, parameter-store entries, …) put them
# here rather than in application code — infrastructure-as-code, even for local.

set -euo pipefail

if ! command -v awslocal &>/dev/null; then
  echo "[init] ERROR: 'awslocal' not found on PATH. Install with: pip install awscli-local" >&2
  exit 1
fi

BUCKET_NAME="complai-local"

echo "[init] Creating S3 bucket: ${BUCKET_NAME}"

# `mb` is idempotent when PERSISTENCE=1 keeps state between restarts, but
# awslocal will error on a duplicate bucket name, so guard with a check.
if awslocal s3api head-bucket --bucket "${BUCKET_NAME}" 2>/dev/null; then
  echo "[init] Bucket '${BUCKET_NAME}' already exists — skipping creation."
else
  awslocal s3 mb "s3://${BUCKET_NAME}"
  echo "[init] Bucket '${BUCKET_NAME}' created."
fi
