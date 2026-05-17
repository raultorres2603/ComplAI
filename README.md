# ComplAI - Gall Potablava

[![GitHub Release](https://img.shields.io/github/v/release/raultorres2603/ComplAI)](https://github.com/raultorres2603/ComplAI/releases/latest)

Serverless AI backend for municipal assistance in El Prat de Llobregat: citizen Q&A, complaint drafting, and asynchronous PDF generation.

## Table of Contents
1. [Project Overview](#project-overview)
2. [Vision and Goals](#vision-and-goals)
3. [Tech Stack](#tech-stack)
4. [Architecture Overview](#architecture-overview)
5. [Implemented API Endpoints](#implemented-api-endpoints)
6. [Auth, Rate Limiting, and CORS](#auth-rate-limiting-and-cors)
7. [Integrations](#integrations)
8. [Getting Started (Local)](#getting-started-local)
9. [Testing](#testing)
10. [Infrastructure (CDK)](#infrastructure-cdk)
11. [Configuration](#configuration)
12. [Performance Optimizations](#performance-optimizations)
13. [Conversation History (Multi-turn)](#conversation-history-multi-turn)
14. [PDF Complaint Generation](#pdf-complaint-generation)
15. [AI Identity and Behaviour](#ai-identity-and-behaviour)
16. [Contributing](#contributing)
17. [License](#license)

## Project Overview
ComplAI is the backend of a public-service assistant for residents of El Prat de Llobregat.

Current capabilities:
- Municipal question answering.
- Complaint drafting workflows.
- Asynchronous complaint PDF generation.
- Asynchronous feedback ingestion.

The backend is built with Micronaut and deployed on AWS Lambda.

## Vision and Goals
- Reduce friction when citizens interact with municipal procedures.
- Provide multilingual assistance (Catalan, Spanish, English).
- Keep operations cost-efficient with serverless infrastructure.
- Keep responses grounded with local retrieval context.

## Tech Stack

| Area | Current implementation |
|---|---|---|
| Language / runtime | Java 25 |
| Framework | Micronaut 4.10.7 |
| Build tool | Gradle (Shadow JAR: complai-all.jar) |
| IaC | AWS CDK (TypeScript, cdk/) — 5 stacks |
| Cloud services | AWS Lambda, API Gateway HTTP API, SQS, S3, CloudWatch, CloudFront, WAF (production) |
| AI provider | OpenRouter (with circuit breaker pattern) |
| RAG | In-memory lexical retrieval (InMemoryLexicalIndex, no Lucene dependency) |
| Caching | Caffeine (conversation and response caches), CloudFront CDN (GET/OPTIONS) |
| PDF | Apache PDFBox |
| Auth / identity | API key auth (X-Api-Key) and OIDC ID token validation (X-Identity-Token) with JJWT |
| HTML parsing | Jsoup |
| Health checks | Deep health: S3, SQS, SES, RAG index, OpenRouter |
| Tests | JUnit 5, Mockito, Bruno E2E |

## Architecture Overview
Layering present in code:
- Controllers: HTTP boundary and status mapping.
- Services: orchestration, validation, AI calls, RAG composition, clarification, streaming, redact.
- Helpers: prompt building, language detection, parsing, PDF rendering.
- Infrastructure adapters: SQS publishers/handlers and S3 upload/signing.
- Caches: conversation state, short-lived response cache, circuit breaker state.

Current backend request and async-processing flow:

```mermaid
flowchart TD
	C[Clients\nWeb and API consumers]

	subgraph API[Micronaut API Lambda]
		F[Filters and Middleware\nApiKeyAuthFilter\nRateLimitFilter\nCorsFilter\nCityIdLoggingFilter]

		subgraph CTRL[Controllers]
			HC[HomeController and HealthController]
			ORC[OpenRouterController]
			FBC[FeedbackController]
		end

		subgraph SVC[Services]
			ORS[OpenRouterServices]
			ID[IntentDetector]
			RCB[RagContextBuilder]
			CS[ClarificationService]
			SO[StreamingOrchestrator]
			RO[RedactOrchestrator]
			IVS[InputValidationService]
			CMS[ConversationManagementService]
			ARS[AiResponseProcessingService]
			FPS[FeedbackPublisherService]
		end

		subgraph HLP[Helpers]
			RH[RAG helpers\nNewsRagHelper and others]
			RGW[RagWarmupService]
			PDFH[PdfGenerator]
			HCH[HealthCheckService]
		end

		subgraph INF[Infrastructure]
			CB["CircuitBreaker\n(multi-city)"]
			HW[HttpWrapper\nmanual retry + circuit breaker]
		end

		subgraph IDX[Cache and Index]
			CC[Caffeine conversation cache]
			RC[Response cache]
			ILI[InMemoryLexicalIndex]
		end
	end

	ORAPI[OpenRouter API]
	SQS[(AWS SQS queues)]
	WL[Worker Lambda\nRedactWorkerHandler and FeedbackProcessor]
	S3[(AWS S3)]

	C --> F
	F --> HC
	F --> ORC
	F --> FBC

	ORC --> IVS
	ORC --> ORS
	ORS --> ID
	ORS --> RCB
	ORS --> CS
	ORS --> SO
	ORS --> RO
	ORS --> ARS
	ORS --> CMS
	ID --> ILI
	RCB --> RH
	CS --> ID
	SO --> ID
	SO --> RCB
	SO --> CS
	CMS --> CC
	ARS --> RC
	HW --> CB
	HW --> ORAPI

	ORC --> SQS
	FBC --> FPS
	FPS --> SQS
	SQS --> WL
	WL --> ORAPI
	WL --> PDFH
	WL --> S3
	ORC --> S3
	RGW --> ILI
```

## Implemented API Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | / | Public | Home/welcome response |
| GET | /health | Public | Full health check |
| GET | /health/startup | Public | Lightweight startup health check |
| POST | /complai/ask | X-Api-Key | Ask municipal questions (SSE stream, JSON on early error) |
| POST | /complai/redact | X-Api-Key; X-Identity-Token only applies to OIDC-enabled cities | Complaint drafting and async PDF queueing |
| POST | /complai/feedback | X-Api-Key | Queue user feedback for async processing |

## Auth, Rate Limiting, and CORS
- API key enforcement: all POST routes require X-Api-Key.
- Public route exceptions: GET /, GET /health, GET /health/startup.
- OIDC on redact: X-Identity-Token is evaluated only for POST /complai/redact; if the city is OIDC-enabled in oidc/oidc-mapping.json, the token is required and validated.
- Rate limiting: per-user in-process limiter using Caffeine; excluded only on GET /, GET /health, GET /health/startup.
- CORS preflight: OPTIONS requests are allowed without API key. In local mode, CorsFilter handles preflight and adds CORS headers; in deployed environments, CORS is configured at infrastructure level.

## Integrations
- OpenRouter chat completions.
- AWS SQS for asynchronous complaint and feedback workflows.
- AWS S3 for corpora and generated artifacts.
- Apache PDFBox for PDF generation.
- In-memory lexical RAG.
- OIDC ID token validation via JJWT.

## Getting Started (Local)
Prerequisites:
- Java 25
- Docker
- AWS SAM CLI

```bash
cp sam/env.json.example sam/env.json
cd sam
./start-local.sh
```

Or use the VS Code task (Ctrl+Shift+B → "Local SAM") which runs the same script from the `sam/` directory automatically.

## Testing

Always run a clean build before tests to catch stale artifacts:

```bash
# Standard test run
./gradlew clean build -x test test

# CI-style run with verbose failure output (use before pushing)
./gradlew clean build -x test ciTest
```

E2E tests are in `E2E-ComplAI/` (Bruno collection).

## Infrastructure (CDK)

| Stack | Main resources |
|---|---|
| `ComplAIStorageStack-<env>` | S3 buckets (procedures, events, news, city-info, complaints, feedback, deployments) |
| `ComplAIQueueStack-<env>` | SQS queues and DLQs (redact, feedback) |
| `ComplAILambdaStack-<env>` | API Lambda, redact worker Lambda, feedback worker Lambda, scheduled report Lambda, HTTP API, metric filters, reserved concurrency |
| `ComplAIEdgeStack-production` | CloudFront distribution with WAF, geo-restriction (Spain), rate limiting, custom cache policy (60s TTL) |
| `ComplAIWafStack-production` | WAF web ACL for production API |

**Key infrastructure decisions:**

- **Reserved concurrency**: each Lambda function has a guaranteed pool (API: 50, redact worker: 20, feedback worker: 10, scheduled report: 1) to prevent traffic spikes from starving other functions.
- **CloudFront caching**: GET `/`, `/health`, `/health/startup` and OPTIONS preflight responses are cached for 60 seconds. POST requests bypass cache (CloudFront never caches them). The origin read timeout is set to 60 seconds to support long-running SSE streams.
- **Log retention**: all Lambda log groups retain logs for 1 year for audit compliance and statistics.

```bash
cd cdk
npm install
npm run build
npx cdk synth
npx cdk deploy 'ComplAI*Stack-development'
```

## Configuration

### Amazon SES (Simple Email Service)

ComplAI uses Amazon SES for sending complaint statistics reports and notifications.

**Configuration Bean**: `cat.complai.config.SesConfiguration` (`@ConfigurationProperties("aws.ses")`)

#### Multi-City SES (New)

ComplAI supports per-city statistics reports. Each city receives its own customized report via SES.

**Requirement**: Both `API_KEY_<CITYID>` AND `AWS_SES_TO_EMAIL_<CITYID>` must be set for a city to receive reports.

| Environment Variable | Example Value | Required For |
|---|---|---|
| `API_KEY_ELPRAT` | `sk-test-...` | Authentication |
| `AWS_SES_TO_EMAIL_ELPRAT` | `elprat@example.com` | Report delivery |
| `AWS_SES_FROM_EMAIL` | `noreply@elprathq.cat` | Always (sender) |
| `AWS_SES_REGION` | `eu-west-1` | Optional |

**Adding a New City** (e.g., "Barcelona"):
```bash
API_KEY_BARCELONA=sk-test-bcn-456
AWS_SES_TO_EMAIL_BARCELONA=barcelona@example.com
```

The `MultiCitySesService` automatically discovers configured cities at Lambda startup.

#### Legacy Configuration

The old single-recipient pattern (`AWS_SES_RECIPIENT_EMAIL`) is deprecated and ignored.

**Environment Variable Mapping** (application.properties):
```properties
aws.ses.from-email=${AWS_SES_FROM_EMAIL:}
aws.ses.region=${AWS_SES_REGION:eu-west-1}
aws.ses.to-email.elprat=${AWS_SES_TO_EMAIL_ELPRAT:}
```

**Pre-deployment Requirements**:
1. Verify the sender email in AWS SES console (sandbox or production mode)
2. For production, request production access quota increase (AWS approval required)
3. Ensure Lambda execution role has `ses:SendEmail` permission (configured in CDK lambda-stack.ts)

**Micronaut Configuration**:
- SES configuration is injected into `EmailService` via dependency injection
- Application startup will fail immediately if `AWS_SES_FROM_EMAIL` is missing or invalid
- Per-environment configuration is supported via separate environment variable values in GitHub Actions

**Local Testing**:
- Use test email values in `src/test/resources/application.properties`
- Mock SES client in unit tests (no real AWS calls)
- For SAM local testing, set environment variables in `sam/env.json`

**Validation**:
- Email validation performed at application startup via `@Email` annotation
- Invalid or missing sender email causes immediate startup failure (fail-fast)
- SES SDK errors are logged with context for troubleshooting

---

## Main E2E Flows

### 1. Ask Flow (Chat with AI)

```mermaid
sequenceDiagram
    participant U as User
    participant C as API Gateway
    participant F as ApiKeyAuthFilter
    participant S as OpenRouterService
    participant R as RAG (BM25)
    participant O as OpenRouter AI

    U->>C: POST /complai/ask<br/>X-Api-Key: test-integration-key-elprat
    C->>F: Validate API key
    F->>S: Extract city=elprat, pass request
    S->>R: Detect intent & retrieve context
    R-->>S: Procedures/Events/Gencat docs
    S->>O: Build prompt + context
    O-->>S: AI response
    S-->>U: SSE stream response
```

**Key Points:**
- City extracted from `X-Api-Key` header (e.g., `API_KEY_ELPRAT` → `elprat`)
- Intent detection: procedure, event, or gencat query
- RAG uses in-memory BM25 index (no external database)
- Response via Server-Sent Events (SSE)

---

### 2. Redact Flow (PDF Complaint)

```mermaid
sequenceDiagram
    participant U as User
    participant C as API Gateway
    participant P as SqsComplaintPublisher
    participant Q as SQS Queue
    participant W as Redactor Worker Lambda
    participant O as OpenRouter AI
    participant B as S3 (PDF)

    U->>C: POST /complai/redact<br/>identity: {name, NIF}
    C->>P: Queue request
    P->>Q: Send SQS message
    Q-->>U: 202 Accepted (async)
    Q->>W: Trigger Lambda
    W->>O: Request redaction
    O-->>W: Redacted text
    W->>B: Generate PDF + upload
    B-->>W: Return signed URL
    W-->>U: (via callback or polling)
```

**Key Points:**
- Identity validated via OIDC (for OIDC-enabled cities)
- Async via SQS - user gets immediate 202 response
- Worker Lambda generates PDF with PDFBox
- Signed S3 URL returned for download

---

### 3. Feedback Flow

```mermaid
sequenceDiagram
    participant U as User
    participant C as API Gateway
    participant P as SqsFeedbackPublisher
    participant Q as SQS Queue
    participant W as Feedback Worker Lambda

    U->>C: POST /complai/feedback<br/>idUser, idProcedure, rating
    C->>P: Queue request
    P->>Q: Send SQS message
    Q-->>U: 202 Accepted
    Q->>W: Trigger Lambda
    W->>W: Process & store feedback
```

---

### 4. SES Statistics Report Flow (Scheduled)

```mermaid
sequenceDiagram
    participant E as EventBridge/CW Events
    participant H as SesScheduledReportHandler
    participant M as MultiCitySesService
    participant S as StadisticsService
    participant L as CloudWatch Logs
    participant A as S3
    participant Y as SES

    E->>H: Cron: Monday 03:00
    H->>M: runReportsForAllCities()
    M->>M: discoverConfiguredCities()<br/>Scan env vars
    M->>M: For each city with<br/>API_KEY + SES_TO_EMAIL
    M->>S: generateStadisticsReport(cityId)
    S->>L: Query API calls/errors/latency
    S->>A: Query procedures/events/news
    L-->>S: Metrics data
    A-->>S: Metrics data
    S-->>M: Report data
    M->>Y: sendStadistics(recipient, cityId)
    Y-->>M: Email sent
    M-->>E: Summary: X succeeded, Y failed
```

**Key Points:**
- Scheduled via EventBridge (production) or CloudWatch (SAM local)
- `MultiCitySesService` discovers cities from environment variables
- Only cities with BOTH `API_KEY_<CITYID>` AND `AWS_SES_TO_EMAIL_<CITYID>` receive reports
- Each city gets its own customized report email

## Performance Optimizations
- **Conversation cache** with 30-minute TTL and configurable max turns (default 5).
- **Response cache** with privacy-preserving hash keys (cache keys contain only `cityId` + procedure/event hashes + question category — never user queries).
- **In-memory lexical RAG index** (BM25) — no external database dependency.
- **Common response pre-population** — top ~20 most common municipal queries can be cached at startup via `common-ai-requests.json`.
- **Async queue-based** complaint and feedback processing (SQS).
- **CloudFront CDN caching** — GET/OPTIONS responses cached for 60 seconds in production.
- **Circuit breaker** per-city/per-model to fail fast when OpenRouter error rate exceeds 50%, with automatic recovery after cooldown.

## Conversation History (Multi-turn)
Conversation context is stored by conversationId with a 30-minute TTL and configurable max turns (default 5).

## PDF Complaint Generation
When identity is complete, redact requests are queued to SQS and processed by a worker Lambda that generates a PDF with PDFBox and uploads to S3.

## AI Identity and Behaviour
OpenRouter model selection is configurable via OPENROUTER_MODEL. Language detection and context-aware prompt composition are implemented in service/helper layers.

## Contributing
Follow the existing layered architecture and run tests before proposing changes.

## License
Copyright (c) 2026 Raul Torres Alarcon. All Rights Reserved.

This source code is provided for reference only. No copying, reproduction, modification, distribution, or use is permitted without explicit written permission from the copyright holder.
