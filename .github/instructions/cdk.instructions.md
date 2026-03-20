---
applyTo: "cdk/**/*.ts"
---
When implementing or editing CDK resources in this repository:

- Keep API Lambda and worker Lambda responsibilities explicit and separated.
- For queue changes, update queue creation, subscription/event source mapping, and IAM permissions together.
- Keep environment variables synchronized between CDK stacks and application runtime expectations.
- Preserve existing async complaint pipeline semantics (publish to SQS, worker processes, PDF stored in S3).
- Use least-privilege IAM grants for SQS/S3 access.
- Document in task.md when infrastructure changes require application code or configuration updates.
