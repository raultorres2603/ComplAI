# ComplAI AWS CDK Infrastructure

Infrastructure-as-Code for ComplAI using AWS CDK (TypeScript) and Gradle-based Java Micronaut backend.

## Overview

This CDK project defines the complete AWS infrastructure for ComplAI:
- **Compute**: Lambda functions for API and asynchronous workers (GraalVM native images from Micronaut 4 + Java 25)
- **API Gateway**: HTTP API (v2) with CORS, throttling, and Lambda integration
- **Messaging**: SQS queues for async complaint redaction and feedback processing
- **Storage**: S3 buckets for procedures, events, news, city info, complaints, feedback, and deployments
- **Email**: SES (Simple Email Service) for complaint-related email notifications
- **WAF**: AWS WAF for API protection
- **CDN**: CloudFront edge stack for static content

## Prerequisites

### AWS Account & CLI
- AWS CLI v2 installed and configured
- AWS account with appropriate permissions for CDK deployment
- CDK CLI: `npm install -g aws-cdk`

### Node.js & Dependencies
- Node.js 18+ (recommend 20 LTS)
- Dependencies installed: `npm install`

### Backend Code
- Java 25 and Gradle configured
- Build the native image ZIP: `./gradlew clean buildNativeLambda`
- ZIP location: `build/libs/complai-all-*.zip`

## Environment Setup

### GitHub Actions Secrets

The CDK stacks require these GitHub Actions secrets to be configured in your repository:

**Development environment (Development repository):**
- `OPENROUTER_API_KEY`: OpenRouter API key for AI responses (both dev and prod)
- `JWT_SECRET`: Base64-encoded HMAC-SHA256 key (≥ 256 bits) for signing session tokens (both dev and prod)
- `CLIENT_SECRET_HASH`: SHA-256 hex hash of the mobile client secret (both dev and prod)
- `COMPLAI_CORS_ALLOWED_ORIGIN`: CORS allowed origin for development (defaults to GitHub Pages URL)

**Production environment (Production repository/branch):**
- `OPENROUTER_API_KEY`: Same as development or different key if needed
- `JWT_SECRET`: Same as development or different key if needed
- `CLIENT_SECRET_HASH`: Same as development or different key if needed
- `COMPLAI_CORS_ALLOWED_ORIGIN`: CORS allowed origin for production

**Optional overrides (both environments):**
- `SES_FROM_EMAIL`: Verified SES sender email (e.g., `no-reply@example.com`) — set as a GitHub Environment Variable
- `OPENROUTER_MODEL`: Custom OpenRouter model (defaults to `openrouter/free`)
- `OPENROUTER_REQUEST_TIMEOUT_SECONDS`: Request timeout (defaults to `60`)
- `OPENROUTER_OVERALL_TIMEOUT_SECONDS`: Overall timeout (defaults to `60`)
- `OPENROUTER_MAX_RETRIES`: Retry count (defaults to `3`)
- `RESPONSE_CACHE_ENABLED`: Enable response caching (defaults to `true`)
- `RESPONSE_CACHE_TTL_MINUTES`: Cache TTL (defaults to `10`)
- `RESPONSE_CACHE_MAX_ENTRIES`: Cache size (defaults to `500`)
- `RATE_LIMIT_REQUESTS_PER_MINUTE`: Rate limit per minute (defaults to `20`)
- `TOKEN_RATE_LIMIT_REQUESTS_PER_MINUTE`: Per-IP rate limit on the token endpoint (defaults to `10`)
- `SESSION_TOKEN_LIFETIME_SECONDS`: Session token lifetime in seconds (defaults to `900`)
- `COMPLAI_DEFAULT_CITY_ID`: Default city (defaults to `elprat`)
- `HTTP_CLIENT_CONNECT_TIMEOUT`: HTTP connect timeout (defaults to `10s`)
- `HTTP_CLIENT_READ_TIMEOUT`: HTTP read timeout (defaults to `60s`)
- `HTTP_CLIENT_MAX_CONNECTIONS`: Max HTTP connections (defaults to `20`)

## SES Configuration

### Email Verification (Manual AWS Steps)

Before the first deployment, you must verify sender email addresses in AWS SES:

#### Development Sandbox Setup (30-45 minutes)

1. **Login to AWS Console** → Services → SES → Email Addresses
2. **Verify Sender Email**:
   - Click "Verify a New Email Address"
   - Enter the email address you set as `SES_FROM_EMAIL` in your GitHub Environment
   - AWS sends a verification email to that address
   - Click the verification link in the email
3. **Verify Test Recipients** (Sandbox only):
   - If your AWS SES account is in **Sandbox mode**, also verify email addresses that will receive test emails
   - Click "Verify a New Email Address" for each recipient (e.g., your personal email)
   - Complete verification for each
4. **Check SES Quota**:
   - Sandbox mode: 200 emails/24-hour period, 1 email/second
   - Test sending emails in the AWS Console to confirm setup

#### Production Setup (1-2 weeks, AWS review required)

1. **Request Sandbox Exit**:
   - Go to SES → Account Dashboard → Request Production Access
   - AWS will review your use case (typically 24 hours)
   - Approval grants unlimited recipient addresses and higher sending quota

2. **Verify Sender Domain** (recommended for production):
   - Click "Verify a New Domain"
   - Enter your sender domain (e.g., `notifications.example.com`)
   - Add the DKIM/SPF records provided to your DNS zone
   - AWS verifies DNS records (can take 1-2 hours)
   - Once verified, you can send from any email on that domain

3. **Setup Delivery Notifications** (recommended):
   - Configure SNS topics for Bounce, Complaint, and Delivery events
   - This allows monitoring failed sends and handling bounced addresses

### Sender Email Configuration

The sender email address is passed to Lambda via environment variables and must match the verified SES identity:

- Set `SES_FROM_EMAIL` as a GitHub Environment Variable (not a secret) in both the **development** and **production** environments
- The workflow automatically uses the correct value based on which environment you're deploying to

The IAM policy includes a condition that restricts sending to only the configured sender address, ensuring emails can only be sent from verified identities.

### SES Limitations & Considerations

**Sandbox Mode** (Development):
- Can send to verified recipient emails only
- 200 emails per 24-hour period
- 1 email per second (rate limit)
- Cannot send to arbitrary recipient addresses
- Free to use for testing

**Production Mode** (After sandbox exit approval):
- Can send to any recipient address
- 50,000 emails per 24-hour period by default (can request increase)
- 14 emails per second per thread (rate limit)
- Requires verification (domain DKIM or email verification)
- Standard AWS charges apply ($0.10 per 1,000 emails)

**Best Practices**:
1. Start in Sandbox for development/testing
2. Request production access well before production launch (AWS approval takes 24-48 hours)
3. Setup SNS bounce/complaint notifications to maintain sender reputation
4. Monitor bounce rates (keep <5%) and complaint rates (keep <0.1%)
5. Implement unsubscribe link in emails to comply with CAN-SPAM rules

## Deployment

### Local Development

Build and deploy to the AWS account:

```bash
# Install dependencies
npm install

# Synthesize CloudFormation templates
npm run cdk synth

# Deploy to development environment
npm run cdk deploy -- --context environment=development --require-approval never

# Or with specific stack
npm run cdk deploy -- ComplAILambdaStackDev --require-approval never
```

### GitHub Actions CI/CD

The GitHub Actions workflow handles:
1. Building the GraalVM native image ZIP (`./gradlew clean buildNativeLambda`)
2. Uploading the ZIP to S3 deployment bucket
3. Setting `DEPLOYMENT_JAR_KEY` so CDK uses `Code.fromBucket` (avoids bootstrap bucket staging)
4. Running CDK deploy with GitHub Actions secrets
5. Two separate deployments: Development and Production

GitHub Actions passes environment variables to CDK automatically:
- Secrets are injected as environment variables (e.g., `OPENROUTER_API_KEY`)
- Environment Variables (like `SES_FROM_EMAIL`) are passed via `vars.*`
- CDK reads them via `process.env.SES_FROM_EMAIL` in TypeScript

### Manual Deployment (For Testing)

To deploy locally with test values:

```bash
export SES_FROM_EMAIL="test@example.com"
export OPENROUTER_API_KEY="sk-..."
export JWT_SECRET="<base64-encoded-hmac-sha256-key>"
export CLIENT_SECRET_HASH="<sha256-hex-hash-of-client-secret>"

npm run cdk deploy -- --context environment=development
```

## Stacks

### LambdaStack
- API Lambda function (HTTP endpoints, GraalVM native image, 1024 MB)
- Redact worker Lambda (asynchronous PDF generation, GraalVM native image, 1024 MB)
- Feedback worker Lambda (asynchronous feedback collection, GraalVM native image, 256 MB)
- Ask worker Lambda (Telegram bot answers, GraalVM native image, 512 MB)
- Scheduled report Lambda (weekly statistics via SES, GraalVM native image, 512 MB)
- HTTP API Gateway with CORS
- IAM roles and policies (including SES permissions)
- CloudWatch log groups and metric filters

### QueueStack
- SQS redaction queue (for async complaint processing)
- SQS feedback queue (for feedback collection)
- Queue visibility and retention configuration

### StorageStack
- S3 buckets: procedures, events, news, city info, complaints, feedback, deployments
- Bucket versioning, encryption, and lifecycle policies
- Bucket policies and CORS configuration

### EdgeStack
- CloudFront distribution for CDN (production only, us-east-1)
- AWS WAF rules (geo-blocking to Spain, rate limiting)
- Origin configurations and custom cache policies

## Monitoring & Debugging

### CloudWatch Logs

View logs from Lambda functions:

```bash
# Development API Lambda
aws logs tail /aws/lambda/ComplAILambda-development --follow

# Development Worker Lambda
aws logs tail /aws/lambda/ComplAIRedactorLambda-development --follow

# Production API Lambda
aws logs tail /aws/lambda/ComplAILambda-production --follow
```

### CloudWatch Metrics

Custom metrics are automatically emitted:
- `ComplAI/development/AuditErrorCount` - Errors per endpoint
- `ComplAI/development/AuditLatencyMs` - Request latency

View metrics in CloudWatch Dashboard or create custom dashboards.

### SES Monitoring

Monitor email sending in AWS Console:

```bash
# Check SES quota and sending stats
aws ses get-account-sending-enabled

# View bounce and complaint notifications (if SNS configured)
aws sns list-subscriptions-by-topic --topic-arn arn:aws:sns:REGION:ACCOUNT:ComplAI-SES-BOUNCES
```

## Troubleshooting

### CDK Deploy Fails - Native ZIP Not Found

**Error**: `build/libs not found` or placeholder warning during synth

**Solution**:
1. Build the native image: `./gradlew clean buildNativeLambda`
2. Verify ZIP exists: `ls build/libs/*-all.zip`
3. Deploy again: `npm run cdk deploy`

### Lambda Function Gets 403 Forbidden

**Error**: Lambda returns 403 when calling AWS services

**Solution**:
1. Check IAM role policies in AWS Console
2. Verify role has required actions for the service (e.g., `ses:SendEmail` for SES)
3. Verify resource ARNs match (e.g., `ses:FromAddress` condition matches sender email)
4. Redeploy: `npm run cdk deploy -- --force`

### SES Email Not Sending - Sender Not Verified

**Error**: `MessageRejected` from SES API

**Solution**:
1. Verify the sender email in AWS SES console
2. Check that `SES_FROM_EMAIL` environment variable matches verified email
3. If in Sandbox, verify recipient email too
4. Wait 1-2 minutes after verification, then retry

### SES Email Rate Limit Exceeded

**Error**: `Throttling` error from SES API

**Solution**:
- Sandbox: Limited to 1 email/second. Batch emails with delays.
- Production: Limited to 14 emails/second per thread.
- Add retry logic with exponential backoff in application code
- Request rate limit increase in AWS SES console

## Project Structure

```
cdk/
├── README.md                    # This file
├── bin/
│   └── cdk.ts                   # CDK app entry point
├── lambda-stack.ts              # Lambda, API Gateway, roles
├── queue-stack.ts               # SQS queues
├── storage-stack.ts             # S3 buckets
├── edge-stack.ts                # CloudFront distribution + WAF rules
├── deployment-environment.ts    # Environment type definition
├── cdk.json                     # CDK configuration
├── tsconfig.json                # TypeScript configuration
└── package.json                 # Node.js dependencies
```

## References

- [AWS CDK Documentation](https://docs.aws.amazon.com/cdk/)
- [AWS SES Documentation](https://docs.aws.amazon.com/ses/)
- [AWS Lambda Documentation](https://docs.aws.amazon.com/lambda/)
- [Micronaut AWS Lambda](https://guides.micronaut.io/latest/micronaut_lambda.html)
- [AWS CDK Best Practices](https://docs.aws.amazon.com/cdk/v2/guide/best-practices.html)

## Contributing

When modifying CDK stacks:
1. Update TypeScript files following existing patterns
2. Test locally: `npm run cdk synth` and `npm run cdk diff`
3. Document environment variables and their purpose
4. Update this README with new features or configuration changes
5. Add comments for non-obvious CDK constructs or policies
