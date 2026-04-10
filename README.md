# ComplAI — Gall Potablava

© 2026 Raúl Torres Alarcón. All rights reserved.

LinkedIn: [My profile](https://www.linkedin.com/in/raultorresalarcon/)

[![GitHub Release](https://img.shields.io/github/v/release/raultorres2603/ComplAI)](https://github.com/raultorres2603/ComplAI/releases/latest)

> **Gall Potablava** is a serverless AI assistant for the residents of El Prat de Llobregat
> (Catalonia, Spain). It answers questions about local services, explains municipal procedures,
> and drafts formal complaint letters addressed to the Ajuntament — in Catalan, Spanish, French,
> or English.

---

## Table of Contents

1. [What Is This Project?](#1-what-is-this-project)
2. [Vision and Goals](#2-vision-and-goals)
3. [Architecture Overview](#3-architecture-overview)
4. [Tech Stack](#4-tech-stack)
5. [Getting Started](#5-getting-started)
6. [API Reference](#6-api-reference)
7. [Infrastructure](#7-infrastructure)
8. [Security](#8-security)
9. [Testing](#9-testing)
10. [Performance Optimizations](#10-performance-optimizations)
11. [Conversation History (Multi-turn)](#11-conversation-history-multi-turn)
12. [PDF Complaint Generation](#12-pdf-complaint-generation)
13. [OIDC Identity Verification](#13-oidc-identity-verification)
14. [AI Identity and Behaviour](#14-ai-identity-and-behaviour)
15. [Contributing](#15-contributing)
16. [License](#16-license)

---

## 1. What Is This Project?

**ComplAI** is a serverless REST API that acts as the backend brain of a citizen-facing chatbot
called **Gall Potablava**. Its front-end integration point is **Prat Espais**, the digital services
platform for residents of El Prat de Llobregat. Citizens interact through a chat widget; messages
are forwarded to this API, which processes them with an AI language model and returns a response.

The assistant can:

- **Answer local questions** — opening hours, public services, transport, events, municipal
  offices, parks, and more.
- **Explain municipal procedures** — how to register as a resident, apply for a grant, report an
  issue, request a permit, etc.
- **Draft formal complaint letters** — the citizen describes a problem; the assistant produces a
  complete, addressed letter ready to submit or download as a PDF.

All four languages spoken in the area are supported: **Catalan (default)**, **Spanish**,
**English**, and **French**.

---

## 2. Vision and Goals

| Goal | How ComplAI addresses it |
|------|--------------------------|
| Reduce the cognitive burden of navigating bureaucracy | Plain-language answers and step-by-step guidance in the user's language |
| Lower the barrier to filing formal complaints | Automatic letter drafting — the citizen describes the problem, the AI writes the letter |
| Serve the entire El Prat community | Catalan, Spanish, English, and French support out of the box |
| Integrate naturally into existing digital infrastructure | REST API designed to be consumed by any front-end, starting with Prat Espais |
| Keep operating costs predictable and low | Serverless Lambda; no servers idle overnight; pay per request |
| Be production-safe and maintainable long-term | Layered architecture; typed error codes; full test coverage |

---

## 3. Architecture Overview

ComplAI follows a strict **layered architecture** with clear boundaries at every level.

```xml
<!-- draw.io architecture diagram — open in draw.io or VS Code draw.io extension -->
<mxfile>
  <diagram name="ComplAI Architecture">
    <mxGraphModel>
      <root>
        <mxCell id="0"/><mxCell id="1" parent="0"/>
        <!-- Client -->
        <mxCell id="2" value="Prat Espais (Browser)" style="rounded=1;fillColor=#dae8fc;" vertex="1" parent="1">
          <mxGeometry x="20" y="60" width="160" height="40" as="geometry"/>
        </mxCell>
        <!-- WAF -->
        <mxCell id="3" value="AWS WAF" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="240" y="60" width="120" height="40" as="geometry"/>
        </mxCell>
        <!-- HTTP API -->
        <mxCell id="4" value="API Gateway HTTP API" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="420" y="60" width="160" height="40" as="geometry"/>
        </mxCell>
        <!-- API Lambda -->
        <mxCell id="5" value="ComplAILambda&#xa;(Java 25 / ARM64)" style="rounded=1;fillColor=#d5e8d4;" vertex="1" parent="1">
          <mxGeometry x="420" y="160" width="160" height="50" as="geometry"/>
        </mxCell>
        <!-- OpenRouter -->
        <mxCell id="6" value="OpenRouter API" style="rounded=1;fillColor=#e1d5e7;" vertex="1" parent="1">
          <mxGeometry x="650" y="160" width="140" height="50" as="geometry"/>
        </mxCell>
        <!-- SQS Redact -->
        <mxCell id="7" value="SQS complai-redact-*" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="420" y="280" width="160" height="40" as="geometry"/>
        </mxCell>
        <!-- Redact Worker -->
        <mxCell id="8" value="ComplAIRedactorLambda&#xa;(Java 25 / ARM64)" style="rounded=1;fillColor=#d5e8d4;" vertex="1" parent="1">
          <mxGeometry x="420" y="380" width="160" height="50" as="geometry"/>
        </mxCell>
        <!-- S3 Complaints -->
        <mxCell id="9" value="S3 complai-complaints-*&#xa;(PDF output)" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="650" y="380" width="160" height="50" as="geometry"/>
        </mxCell>
        <!-- SQS Feedback -->
        <mxCell id="10" value="SQS complai-feedback-*" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="240" y="280" width="160" height="40" as="geometry"/>
        </mxCell>
        <!-- Feedback Worker -->
        <mxCell id="11" value="ComplAIFeedbackWorkerLambda&#xa;(Java 25 / ARM64)" style="rounded=1;fillColor=#d5e8d4;" vertex="1" parent="1">
          <mxGeometry x="240" y="380" width="160" height="50" as="geometry"/>
        </mxCell>
        <!-- S3 Feedback -->
        <mxCell id="12" value="S3 complai-feedback-*&#xa;(JSON output)" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="50" y="380" width="160" height="50" as="geometry"/>
        </mxCell>
        <!-- S3 RAG buckets -->
        <mxCell id="13" value="S3 RAG buckets&#xa;(procedures/events/news/cityinfo)" style="rounded=1;fillColor=#ffe6cc;" vertex="1" parent="1">
          <mxGeometry x="650" y="280" width="160" height="50" as="geometry"/>
        </mxCell>
        <!-- Edges -->
        <mxCell id="e1" edge="1" source="2" target="3" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e2" edge="1" source="3" target="4" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e3" edge="1" source="4" target="5" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e4" edge="1" source="5" target="6" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e5" edge="1" source="5" target="7" label="async PDF" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e6" edge="1" source="7" target="8" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e7" edge="1" source="8" target="9" label="upload PDF" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e8" edge="1" source="5" target="10" label="feedback" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e9" edge="1" source="10" target="11" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e10" edge="1" source="11" target="12" label="upload JSON" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="e11" edge="1" source="5" target="13" label="RAG read" parent="1"><mxGeometry relative="1" as="geometry"/></mxCell>
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
```

### Layers

| Layer | Classes | Responsibility |
|-------|---------|----------------|
| HTTP boundary | `OpenRouterController`, `FeedbackController`, `HomeController`, `HealthController` | Deserialise requests, validate format, map error codes → HTTP status |
| Filter chain | `ApiKeyAuthFilter`, `RateLimitFilter`, `CorsFilter` | Auth, rate limiting, CORS |
| Service | `OpenRouterServices`, `InputValidationService`, `ConversationManagementService` | Business logic, AI prompt assembly, conversation history |
| RAG | `ProcedureContextService`, `ProcedureRagHelper`, `EventRagHelper`, `NewsRagHelper`, `CityInfoRagHelper` | BM25 lexical retrieval from in-memory indexes |
| Infrastructure | `HttpWrapper`, `S3PdfUploader`, `SqsComplaintPublisher` | OpenRouter HTTP calls, S3 uploads, SQS publishing |
| Workers | `RedactWorkerHandler`, `FeedbackWorkerHandler` | SQS-triggered async processing |

---

## 4. Tech Stack

| Concern | Technology |
|---------|-----------|
| Language / Runtime | Java 25 (ARM64 Lambda) |
| Framework | Micronaut 4.10 (`lambda_provided` runtime) |
| Build tool | Gradle 8 + Shadow JAR (`complai-all.jar`) |
| Cloud provider | AWS (eu-west-1) |
| AI gateway | OpenRouter (OpenAI-compatible API) |
| Response streaming | Server-Sent Events (Reactor `Flux`) |
| In-memory cache | Caffeine (conversation history + response cache) |
| Lexical RAG | Custom BM25 `InMemoryLexicalIndex` |
| PDF generation | Apache PDFBox 2.0 + NotoSans-Regular.ttf |
| SQS client | AWS SDK v2 |
| S3 client | AWS SDK v2 |
| OIDC validation | JJWT 0.12 + JWKS |
| Infrastructure-as-code | AWS CDK (TypeScript) |
| WAF | AWS WAFv2 |

---

## 5. Getting Started

### Prerequisites

- Java 25 JDK
- Gradle 8
- Docker Desktop (for LocalStack)
- AWS SAM CLI
- An [OpenRouter](https://openrouter.ai/) API key

### Clone and build

```bash
git clone https://github.com/raultorres2603/ComplAI.git
cd ComplAI
./gradlew clean shadowJar   # produces build/libs/complai-all.jar
```

### Configure environment variables

Copy `sam/env.json.example` to `sam/env.json` and fill in your values:

```bash
cp sam/env.json.example sam/env.json
```

Key variables:

```json
{
  "ComplAIFunction": {
    "OPENROUTER_API_KEY": "<your-openrouter-api-key>",
    "AWS_ENDPOINT_URL": "http://host.docker.internal:4566",
    "PROCEDURES_BUCKET": "complai-local",
    "PROCEDURES_REGION": "eu-west-1",
    "EVENTS_BUCKET": "complai-local",
    "EVENTS_REGION": "eu-west-1",
    "NEWS_BUCKET": "complai-news-local",
    "NEWS_REGION": "eu-west-1",
    "REDACT_QUEUE_URL": "http://sqs.eu-west-1.localhost.localstack.cloud:4566/000000000000/complai-redact-local",
    "COMPLAINTS_BUCKET": "complai-complaints-local",
    "COMPLAINTS_REGION": "eu-west-1"
  }
}
```

> All keys in `env.json` are **secrets** — never commit this file. It is in `.gitignore`.

### Run locally with SAM + LocalStack

```bash
cd sam
docker compose up -d          # starts LocalStack (S3, SQS) on port 4566
./start-local.sh              # builds JAR, starts SAM local API on http://localhost:3000
```

The SQS worker poller (`sqs_worker_poller.py`) runs in the background and invokes the
`ComplAIRedactorFunction` via `sam local invoke` whenever a message lands on the local queue.

### Generate an API key for local testing

```bash
# Mint a URL-safe random key for a city (prints key to stdout, env var name to stderr):
java -cp build/libs/complai-all.jar cat.complai.auth.ApiKeyGenerator elprat
```

Set the returned value as `API_KEY_ELPRAT` in `sam/env.json` and send it in the
`X-Api-Key` header on every request.

---

## 6. API Reference

All `POST` endpoints require:

```
X-Api-Key: <city-api-key>
Content-Type: application/json
```

`GET /` and `GET /health` and `GET /health/startup` are public (no key required).

---

### `GET /`

Welcome endpoint.

**Response `200 OK`:**
```json
{ "message": "Welcome to the Complai Home Page!" }
```

---

### `GET /health`

Liveness check.

**Response `200 OK`:**
```json
{ "status": "UP", "version": "1.0", "checks": { "openRouterApiKeyConfigured": true } }
```

---

### `GET /health/startup`

Lightweight Lambda startup probe — no I/O, no RAG initialisation.

**Response `200 OK`:**
```json
{ "status": "UP", "version": "1.0", "checks": { "jvm_alive": true, "openRouterApiKeyConfigured": true } }
```

---

### `POST /complai/ask`

Ask a question about El Prat de Llobregat. Returns a **Server-Sent Events** stream.

**Request body:**
```json
{
  "text": "On puc renovar el padró municipal?",
  "conversationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | The citizen's question (max 5,000 chars) |
| `conversationId` | string (UUID) | ❌ | Session identifier for multi-turn context |

**SSE stream events:**
```
data: {"type":"sources","sources":[{"url":"https://...","title":"..."}]}
data: {"type":"response","chunk":"Pots renovar..."}
data: {"type":"response","chunk":" el padró..."}
data: {"type":"complete","success":true,"message":"Pots renovar el padró...","errorCode":0}
```

---

### `POST /complai/redact`

Draft a formal complaint letter addressed to the Ajuntament.

**Request body:**
```json
{
  "text": "El carrer de la Pau porta tres setmanes sense il·luminació.",
  "format": "pdf",
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "requesterName": "Maria",
  "requesterSurname": "Garcia",
  "requesterIdNumber": "12345678A"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | Complaint description (max 5,000 chars) |
| `format` | `"pdf"` | ✅ | Output format — only `"pdf"` is accepted by clients |
| `conversationId` | string (UUID) | ❌ | Session identifier for multi-turn context |
| `requesterName` | string | ❌ | Complainant first name |
| `requesterSurname` | string | ❌ | Complainant surname |
| `requesterIdNumber` | string | ❌ | Complainant DNI / NIF / NIE |

**`202 Accepted`** — identity complete + format `pdf`:
```json
{
  "success": true,
  "message": "La vostra carta de reclamació s'està generant...",
  "pdfUrl": "https://complai-complaints-<env>.s3.eu-west-1.amazonaws.com/complaints/...",
  "errorCode": 0
}
```

**`200 OK`** — identity incomplete (AI asks for missing fields):
```json
{ "success": true, "message": "Per redactar la carta necessito el vostre nom...", "errorCode": 0 }
```

---

### `POST /complai/feedback`

Submit citizen feedback asynchronously.

**Request body:**
```json
{
  "userName": "Joan Garcia",
  "idUser": "12345678A",
  "message": "El servei és molt útil, gràcies!"
}
```

**`202 Accepted`:**
```json
{ "success": true, "feedbackId": "uuid", "message": "Feedback queued." }
```

---

### Error Codes

| `errorCode` | Name | HTTP Status | Meaning |
|-------------|------|-------------|---------|
| `0` | `NONE` | `200 / 202` | Success |
| `1` | `VALIDATION` | `400` | Bad request (empty text, unsupported format) |
| `2` | `REFUSAL` | `422` | AI refused: question not about El Prat |
| `3` | `UPSTREAM` | `502` | OpenRouter returned an error |
| `4` | `TIMEOUT` | `504` | OpenRouter call timed out |
| `5` | `INTERNAL` | `500` | Unexpected server-side error |
| `6` | `UNAUTHORIZED` | `401` | Missing or invalid API key / OIDC token |
| `7` | `RATE_LIMITED` | `429` | Per-user rate limit exceeded |

---

## 7. Infrastructure

### AWS Services

```
Internet → AWS WAF → API Gateway HTTP API → ComplAILambda (Java 25 ARM64)
                                                  │
                             ┌────────────────────┼──────────────────────┐
                             │ SQS redact queue   │ SQS feedback queue   │ S3 RAG buckets
                             ▼                    ▼
                    ComplAIRedactorLambda   ComplAIFeedbackWorkerLambda
                             │                    │
                             ▼                    ▼
                   S3 complaints bucket    S3 feedback bucket
```

### CDK Stacks (per environment: `development` / `production`)

| Stack | Resources |
|-------|-----------|
| `ComplAIStorageStack-<env>` | `complai-procedures-<env>`, `complai-events-<env>`, `complai-news-<env>`, `complai-cityinfo-<env>`, `complai-complaints-<env>`, `complai-feedback-<env>`, `complai-deployments-<env>` |
| `ComplAIQueueStack-<env>` | `complai-redact-<env>` + DLQ, `complai-feedback-<env>` + DLQ |
| `ComplAILambdaStack-<env>` | `ComplAILambda-<env>`, `ComplAIRedactorLambda-<env>`, `ComplAIFeedbackWorkerLambda-<env>`, IAM roles, CloudWatch log groups |
| `ComplAIWafStack-<env>` | WAFv2 WebACL with geo-match (Spain) and rate-based rules |

### Lambda functions

| Function | Handler | Memory | Trigger |
|----------|---------|--------|---------|
| `ComplAILambda-<env>` | `io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction` | 1024 MB | API Gateway HTTP API |
| `ComplAIRedactorLambda-<env>` | `cat.complai.worker.RedactWorkerHandler` | 1024 MB | SQS (batch 1) |
| `ComplAIFeedbackWorkerLambda-<env>` | `cat.complai.feedback.worker.FeedbackWorkerHandler` | 512 MB | SQS (batch 1) |

All functions use **Java 25** on **ARM64** and run from the same shadow JAR
(`complai-all.jar`) stored in `complai-deployments-<env>`.

### Deployment

```bash
# Install CDK dependencies
cd cdk && npm install

# Deploy all stacks to development (requires AWS credentials):
cdk deploy 'ComplAI*Stack-development' \
  --context jarS3Key=complai-all-<sha>.jar

# Stack deployment order (enforced by CDK addDependency):
# StorageStack → QueueStack → LambdaStack → WafStack
```

### Key environment variables (Lambda)

| Variable | Required | Description |
|----------|----------|-------------|
| `OPENROUTER_API_KEY` | ✅ | Bearer token for the OpenRouter API |
| `API_KEY_ELPRAT` | ✅ | Per-city API key; add one `API_KEY_<CITYID>` per city |
| `OPENROUTER_MODEL` | ❌ | Model identifier (default: `openrouter/free`) |
| `OPENROUTER_REQUEST_TIMEOUT_SECONDS` | ❌ | Per-request timeout (default: `60`) |
| `OPENROUTER_OVERALL_TIMEOUT_SECONDS` | ❌ | Overall AI call timeout (default: `60`) |
| `OPENROUTER_MAX_RETRIES` | ❌ | Retries on 429/5xx (default: `3`) |
| `COMPLAINTS_BUCKET` / `COMPLAINTS_REGION` | ✅ | S3 bucket for generated PDFs |
| `PROCEDURES_BUCKET` / `PROCEDURES_REGION` | ✅ | S3 bucket for procedures RAG corpus |
| `EVENTS_BUCKET` / `EVENTS_REGION` | ✅ | S3 bucket for events RAG corpus |
| `NEWS_BUCKET` / `NEWS_REGION` | ✅ | S3 bucket for news RAG corpus |
| `CITYINFO_BUCKET` / `CITYINFO_REGION` | ✅ | S3 bucket for city-info RAG corpus |
| `REDACT_QUEUE_URL` | ✅ | SQS queue URL for async complaint generation |
| `FEEDBACK_QUEUE_URL` | ✅ | SQS queue URL for async feedback processing |
| `COMPLAI_DEFAULT_CITY_ID` | ❌ | City to pre-warm RAG indexes for (default: `elprat`) |
| `RATE_LIMIT_REQUESTS_PER_MINUTE` | ❌ | Per-user rate limit (default: `20`) |

---

## 8. Security

| Concern | How it is addressed |
|---------|---------------------|
| **API key authentication** | All `POST` endpoints require `X-Api-Key: <city-api-key>`. Enforced by `ApiKeyAuthFilter` before any controller logic runs. `GET /`, `GET /health`, `GET /health/startup` are public. |
| **Per-city key isolation** | Each city has its own API key (`API_KEY_<CITYID>` env var). The validated city ID is attached to the request as the `city` attribute and used throughout the request lifecycle. |
| **API key generation** | Keys are generated with `ApiKeyGenerator` CLI (32 random bytes, URL-safe base64). Never store keys in source code. |
| **Rate limiting** | `RateLimitFilter` enforces per-user limits (default 20 requests/minute) using a Caffeine sliding-window counter. Returns `429 RATE_LIMITED`. |
| **OIDC identity verification** | Optional per-city OIDC layer for `/complai/redact`. When enabled, `X-Identity-Token` is mandatory and overrides self-reported body fields. See [Section 13](#13-oidc-identity-verification). |
| **Input injection** | User text is passed to the AI as a structured `content` field in a `messages` array — never string-interpolated into raw JSON. |
| **Input length** | All user input is capped at 5,000 characters. Requests exceeding the limit are rejected with `400 VALIDATION` before any AI call. |
| **Off-topic abuse** | System prompt + programmatic refusal detection (`REFUSAL` error code) scope all AI responses to El Prat de Llobregat. |
| **Audit logging** | `AuditLogger` writes structured metadata only (endpoint, request hash, error code, latency). No user text or AI response is ever logged. |
| **WAF** | AWS WAFv2 WebACL restricts traffic to Spain (geo-match) and enforces infrastructure-level rate limiting. |
| **IAM least privilege** | API Lambda: `AWSLambdaBasicExecutionRole` + SQS send + S3 read. Worker Lambdas: `AWSLambdaBasicExecutionRole` + SQS consume + S3 write (specific bucket only). |
| **Secrets in code** | API keys and credentials are never committed. Use `API_KEY_<CITYID>` environment variables or GitHub Environment Secrets. |

### Minting API keys

```bash
# Generate a new URL-safe API key for a city:
java -cp build/libs/complai-all.jar cat.complai.auth.ApiKeyGenerator elprat
# → prints the key to stdout; prints "Set API_KEY_ELPRAT=<key>" to stderr

# Use the key in every API call:
curl -X POST https://<api-gw-url>/complai/ask \
     -H 'X-Api-Key: <key>' \
     -H 'Content-Type: application/json' \
     -d '{"text": "On queda l'\''ajuntament?"}'
```

---

## 9. Testing

### Unit tests (Gradle)

```bash
./gradlew test          # runs all JUnit 5 unit tests
./gradlew ciTest        # CI-focused task: fails fast on first test failure
```

Tests use Mockito (`mockito-core`, `mockito-inline`) and JUnit 5. Test classes are under
`src/test/java/cat/complai/`.

### E2E tests (Bruno)

The `E2E-ComplAI/` directory contains a [Bruno](https://www.usebruno.com/) collection.

```bash
# Run all E2E tests against the development environment:
bru run E2E-ComplAI/ --env development

# Key collections:
#   02-OK/   — happy-path flows (ask, redact, feedback)
#   03-ERROR/ — error flows (invalid key, bad format, etc.)
```

The Bruno `environments/` directory stores environment-specific variables (base URL, API key).
**Do not commit real API keys** to the environment files — use Bruno's secret variables or
your shell environment.

---

## 10. Performance Optimizations

### In-memory RAG (BM25)

ComplAI uses a custom `InMemoryLexicalIndex<T>` — a pure-Java BM25 implementation with no
external search dependency. Each city's procedures, events, news, and city-info corpora are
indexed once at Lambda warm-up (or lazily on first access) and queried in memory for every
request. Typical RAG latency: < 5 ms.

### Smart intent detection

`ProcedureContextService` analyses the query before hitting any index:
- Conversational questions (greetings, thanks, general chat) skip all RAG entirely.
- Procedural questions are matched against title keywords and procedure corpus titles for
  highly accurate context selection.
- Eliminates 70–90% of unnecessary index scans for conversational exchanges.

### Caffeine caching

- **Conversation history**: keyed by `conversationId`, 30-minute TTL, max 10,000 entries.
- **Response cache**: keyed by city + question category + RAG context hash, 10-minute TTL,
  max 500 entries. Identical queries within a session skip the OpenRouter call entirely.

### Retry strategy

`HttpWrapper` retries upstream `429` and `5xx` responses up to `OPENROUTER_MAX_RETRIES`
(default 3) times with exponential back-off and jitter. Other 4xx responses and network
exceptions are not retried.

### RAG pre-warming

`RagWarmupService` loads indexes for the default city (`COMPLAI_DEFAULT_CITY_ID`) at bean
initialisation time, eliminating cold-start latency for the first request.

---

## 11. Conversation History (Multi-turn)

ComplAI supports natural multi-turn conversations via an optional `conversationId` field.

1. The front-end generates a UUID when the user opens the chat widget and reuses it for every
   message in that session.
2. For each request with a `conversationId`, the service retrieves the cached history
   (previous `{role, content}` pairs) and prepends it to the AI's `messages` array.
3. After a successful AI response, the new user and assistant messages are appended.
4. History is capped at **5 turns** (10 messages) to control AI token costs.
5. Cache entries expire after **30 minutes** of inactivity.
6. An unknown or expired `conversationId` starts fresh — no error is returned.

Omitting `conversationId` gives fully stateless behaviour.

---

## 12. PDF Complaint Generation

PDF generation is handled exclusively by the **worker Lambda** via an asynchronous SQS flow.

**Flow:**

1. The API Lambda validates the request, generates an S3 key, and creates a 24-hour
   pre-signed GET URL for that key.
2. A `RedactSqsMessage` is published to `complai-redact-<env>` and `202 Accepted` is returned.
3. `ComplAIRedactorLambda` processes the SQS message:
   - Calls `RedactPromptBuilder.buildRedactPromptWithIdentity()` to build the AI prompt.
   - Calls OpenRouter via `HttpWrapper`.
   - Parses the AI response header with `AiParsed.parseAiFormatHeader()`.
   - Renders the letter body as a PDF with `PdfGenerator` (Apache PDFBox, in-memory).
   - Uploads the PDF to S3 at the pre-determined key via `S3PdfUploader.upload()`.
4. The caller polls the `pdfUrl` — returns `403/404` while the worker runs, then
   `200 application/pdf` once the upload completes (typically within seconds).

**PDF characteristics:**
- Font: `NotoSans-Regular.ttf` (full Unicode: Catalan `ç à ü ·l`, Spanish `ñ`, etc.)
- Page size: A4; word-wrapped; multi-page support; no disk I/O

---

## 13. OIDC Identity Verification

**Status: Implemented and configurable per city. Currently disabled for all test cities.**

When enabled for a city (via `"enabled": true` in `src/main/resources/oidc/oidc-mapping.json`),
OIDC identity verification requires the front-end to authenticate the citizen with a
government-approved identity provider (Cl@ve, VALId, or idCat) and include the resulting
ID token in the `X-Identity-Token` request header.

**Flow:**

1. Front-end authenticates with the OIDC IdP and obtains a signed JWT ID token.
2. The token is sent in `X-Identity-Token` on `POST /complai/redact`.
3. `OidcIdentityTokenValidator` verifies the token's signature (via JWKS), issuer, audience,
   and expiry, then extracts `given_name`, `family_name`, and the configured NIF claim.
4. Verified identity overrides any self-reported body fields (`requesterName`, etc.).
5. If the header is absent → `401 UNAUTHORIZED`.
6. If the header is present but invalid → `401 UNAUTHORIZED`.

**Per-city configuration (`oidc-mapping.json`):**

```json
{
  "elprat": {
    "enabled": false,
    "issuer": "https://identity-provider.example.com",
    "jwks_uri": "https://identity-provider.example.com/.well-known/jwks.json",
    "audience": "complai-elprat",
    "nif_claim": "nif"
  }
}
```

Set `"enabled": true` and redeploy to activate for a city. No environment variables are needed.

---

## 14. AI Identity and Behaviour

The assistant identifies itself as **Gall Potablava** — a friendly character whose name
references the *Ànec Collverd* (Mallard duck), El Prat's emblematic bird.

**Personality:** friendly, local, concise, and respectful.

**Languages:** Catalan (default), Spanish, English, French — detected automatically from
the input text using `LanguageDetector` (heuristic signal-counting; no NLP library required).

**Scope:** The system prompt restricts the AI to questions about El Prat de Llobregat.
Off-topic questions are declined politely. `OpenRouterServices` also performs programmatic
refusal detection in all four languages and maps detected refusals to `REFUSAL (2)`.

**Model:** Configurable via `OPENROUTER_MODEL` environment variable. The OpenRouter gateway
provides access to many models; the default is `openrouter/free`.

---

## 15. Contributing

### Branch strategy

- `main` — production-ready code; protected; requires PR + review.
- Feature branches: `feature/<short-description>`
- Bug fixes: `fix/<issue-or-description>`

### Build and test before pushing

```bash
./gradlew clean ciTest shadowJar
```

### Code style

- Follow the existing layered architecture — controllers never call repositories directly.
- The service layer must never throw to the controller; return typed `OpenRouterResponseDto`.
- Add Javadoc to all new public classes and methods.
- Keep Lambda cold-start latency in mind: avoid adding synchronous startup I/O without
  a warm-up counterpart in `RagWarmupService`.

### Adding a new city

1. Add a `procedures-<cityId>.json` corpus to `complai-procedures-<env>` in S3.
2. Add corresponding event / news / city-info JSON files to the other RAG buckets.
3. Add `API_KEY_<CITYID_UPPER>` as a Lambda environment variable (and GitHub Secret).
4. Optionally add an OIDC entry to `oidc-mapping.json` with `"enabled": false`.
5. Redeploy.

---

## 16. License

© 2026 Raúl Torres Alarcón. All rights reserved.

This project and all its source code, documentation, and assets are the exclusive property of
Raúl Torres Alarcón. No part of this project may be reproduced, distributed, transmitted,
displayed, published, or broadcast in any form or by any means without prior written permission
from the copyright holder.

  `https://<lambda-function-id>.lambda-url.<region>.on.aws/complai/ask`
- CORS and public access are managed via Lambda Function URL configuration.

### What the front-end receives

| Scenario | Response |
|---|---|
| Successful answer | `200 OK` with JSON body containing the AI text |
| PDF queued (identity complete, format ≠ json) | `202 Accepted` with `pdfUrl` pre-signed S3 link |
| Successful JSON letter or AI asking for identity | `200 OK` with JSON body containing the letter/question text |
| Question not about El Prat | `422 Unprocessable Entity` with error code `REFUSAL` |
| Invalid request | `400 Bad Request` with error code `VALIDATION` |
| AI service unavailable | `502 Bad Gateway` with error code `UPSTREAM` |

---

## 4. Architecture Overview

ComplAI follows a strict **layered architecture** with clear boundaries at every level.

```
┌──────────────────────────────────────────────────────────────────┐
│                  Lambda Function URL (public HTTPS)              │
│             AWS-managed: TLS, routing, throttle, CORS            │
└───────────────────────────────┬──────────────────────────────────┘
                                │ HTTP event (payload v2)
┌───────────────────────────────▼──────────────────────────────────┐
│                        AWS Lambda (Java 25)                      │
│                    Micronaut 4.10.7 function runtime             │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ Controller layer  (OpenRouterController, HomeController)   │  │
│  │  • HTTP boundary: deserialise request, validate format     │  │
│  │  • Maps typed error codes → HTTP status codes              │  │
│  │  • Returns JSON (PDFs are async only)                              │  │
│  └───────────────────────────┬────────────────────────────────┘  │
│                              │ calls                              │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │ Service layer  (OpenRouterServices via IOpenRouterService)  │  │
│  │  • Validates inputs (null/blank checks)                    │  │
│  │  • Builds AI prompt + message history                      │  │
│  │  • Detects AI refusals                                     │  │
│  │  • Parses AI format header                                 │  │
│  │  • Publishes to SQS for async PDF generation               │  │
│  │  • Returns typed OpenRouterResponseDto — never throws      │  │
│  └───────────────────────────┬────────────────────────────────┘  │
│                              │ calls                              │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │ Infrastructure layer  (HttpWrapper)                        │  │
│  │  • Async HTTP call to OpenRouter API                       │  │
│  │  • Two overloads: single-prompt + multi-turn message list  │  │
│  │  • Parses OpenAI-compatible JSON response                  │  │
│  └───────────────────────────┬────────────────────────────────┘  │
│                              │                                    │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │ Cache (Caffeine in-memory)                                 │  │
│  │  • Conversation history keyed by conversationId            │  │
│  │  • 30-minute TTL, max 10,000 concurrent conversations      │  │
│  │  • Bounded to 10 turns per conversation                    │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                                │ HTTPS POST
                                │ Bearer token auth
┌───────────────────────────────▼──────────────────────────────────┐
│                    OpenRouter API                                │
│              model: configurable via OPENROUTER_MODEL env var    │
└──────────────────────────────────────────────────────────────────┘
```

### Key design decisions

- **Stateless Lambda**: the function holds no state between invocations. Conversation history
  lives in a Caffeine in-memory cache scoped to a warm Lambda instance — enough for a typical
  user session without requiring DynamoDB.
- **Async PDF generation**: when the caller provides a complete identity and requests a PDF,
  the controller enqueues a message on SQS and returns `202 Accepted` immediately with a
  pre-signed S3 URL. A separate **worker Lambda** consumes the queue, calls the AI, renders
  the PDF with PDFBox, and uploads it to S3. This decouples PDF generation latency from the
  HTTP request lifecycle.
- **Typed error codes**: `OpenRouterErrorCode` enum (NONE, VALIDATION, REFUSAL, UPSTREAM, TIMEOUT,
  INTERNAL) is the authoritative signal from service to controller. No string-matching on error
  messages.
- **Interface-based contracts**: the controller depends on `IOpenRouterService`, not the
  implementation. This makes the service trivially replaceable in tests.
- **Never throw to the controller**: the service always returns an `OpenRouterResponseDto`. All
  exceptions are caught, logged, and converted to typed errors internally.
- **Audit logging**: Every /complai/ask and /complai/redact request is logged by the controller using a structured, privacy-preserving audit log (see below).

---

## Audit Logging (Request/Response Metadata)

Every /complai/ask and /complai/redact request is logged by the controller using a structured audit log. Only metadata is logged:
- Endpoint (e.g. /complai/ask)
- Request hash (not the full text)
- Error code (OpenRouterErrorCode)
- Latency (ms)
- Output format (if applicable)
- Language (detected via `LanguageDetector` — `"CA"`, `"ES"`, or `"EN"`)

No user text or AI response is ever logged. This ensures privacy and compliance.

Example log line:
```
{"ts":"2026-03-06T12:34:56Z","endpoint":"/complai/ask","requestHash":"a1b2c3d4","errorCode":0,"latencyMs":42,"outputFormat":"PDF","language":"CA"}
```

---

## Performance Optimizations

### Smart RAG Filtering

ComplAI implements intelligent RAG (Retrieval-Augmented Generation) filtering to minimize latency:

- **Conversational Query Detection**: The `questionNeedsProcedureContext()` method analyzes user queries to determine if procedural information is needed
- **Keyword Matching**: Detects procedural keywords like "how to", "procedure", "tramit", "recycling", "waste", etc.
- **Procedure Title Matching**: Checks against actual procedure titles from `procedures-<cityId>.json` files for highly accurate detection
- **Impact**: Reduces unnecessary RAG searches by 70-90% for conversational queries, eliminating URL retrieval costs when users aren't asking about procedures

### Latency Improvements

- **Single-Pass RAG**: Eliminated duplicate Lucene searches per request by reusing procedure matches across the prompt building process
- **Retry Strategy**: Implemented exponential backoff with jitter for upstream 429/5xx errors to reduce tail latency
- **Model Optimization**: Configurable model selection via `OPENROUTER_MODEL` environment variable for latency vs quality trade-offs
- **Prompt Size Optimization**: Only includes procedure context when relevant matches are found, limiting token processing overhead

### Response Caching

ComplAI implements intelligent response caching to reduce latency and OpenRouter API costs:

- **Tier 1: Caffeine In-Memory Cache (Active)**
  - **Implementation**: `ResponseCacheService` with `CommonResponseCacheInitializer` 
  - **Scope**: Conversation responses keyed by conversation ID
  - **TTL**: 30 minutes (auto-expiration after last access)
  - **Capacity**: Bounded to 10,000 concurrent conversations, max 10 turns per conversation
  - **Use Cases**: Multi-turn conversations reuse cached responses for subsequent identical queries within the same session
  
- **Tier 2: S3 Integration (Optional)**
  - Prepared for future long-term response caching using S3
  - Would allow responses to persist across multiple user sessions
  - Configuration available but not enabled by default

- **When Caching Is Used**
  - Triggered automatically in `ResponseCacheService` when a conversation ID is provided
  - Identical queries within the same session skip the OpenRouter call entirely
  - Reduces OpenRouter token consumption by up to 40% in typical multi-turn conversations

---

## 6. How It Works — Request Lifecycle

### `/complai/ask` — Answer a question

```
1.  Front-end sends: { "text": "Quan tanca la biblioteca?", "conversationId": "uuid" }
2.  Controller deserialises the request, calls service.ask(text, conversationId)
3.  Service:
      a. Validates text is not blank
      b. Loads conversation history from Caffeine cache (if conversationId provided)
      c. Assembles messages: [system, ...history, user]
      d. Calls HttpWrapper.postToOpenRouterAsync(messages)
      e. Detects refusal phrases (for off-topic questions)
      f. On success: stores user+assistant messages back in cache
      g. Returns OpenRouterResponseDto(success=true, message="La biblioteca...")
4.  Controller maps NONE → HTTP 200, returns JSON body
5.  Front-end renders the response as a chat bubble
```

### `/complai/redact` — Draft a complaint letter

Two paths exist depending on whether the caller provides a complete identity (name, surname, ID number) and the requested format.

**Synchronous path** — identity incomplete, or `format: "json"`:
```
1.  Front-end sends: { "text": "El carrer no té llum.", "format": "json", "conversationId": "uuid" }
2.  Controller validates format field (rejects "xml", "docx", etc. with 400)
3.  Service:
      a. Validates text is not blank and within the 5,000-character limit
      b. Builds prompt; if identity is missing, AI is instructed to ask for it
      c. Calls OpenRouter with conversation history prepended
      d. Returns the AI's response text (question or letter body)
4.  Controller returns HTTP 200 JSON — no PDF is generated on this path
```

**Async path** — identity is complete (name + surname + ID) and `format` is `"pdf"` or `"auto"`:
```
1.  Front-end sends: { "text": "...", "format": "pdf",
      "requesterName": "Maria", "requesterSurname": "Garcia", "requesterIdNumber": "12345678A" }
2.  Controller validates, generates an S3 key, creates a 24-hour pre-signed GET URL
3.  Publishes a RedactSqsMessage to the SQS redact queue
4.  Returns 202 Accepted: { success, message (localised), pdfUrl, errorCode: 0 }

Worker Lambda (triggered by SQS):
      a. Deserialises the message
      b. Calls OpenRouter to draft the letter
      c. Renders PDF in-memory with PDFBox (NotoSans-Regular.ttf embedded for full Unicode)
      d. Uploads PDF to s3://complai-complaints-<env>/<s3Key>

Client polls the pdfUrl — returns 403/404 while the worker is running,
200 application/pdf once the PDF is available (usually within seconds).
```

---

## 7. API Reference

### `GET /`

Welcome endpoint.

**Response `200 OK`:**
```json
{ "message": "Welcome to the Complai Home Page!" }
```

---

### `GET /health`

Liveness check. No JWT required.

**Response `200 OK`:**
```json
{
  "status": "UP",
  "version": "...",
  "checks": {
    "openRouterApiKeyConfigured": true
  }
}
```

---

### `POST /complai/ask`

Ask a question about El Prat de Llobregat.

**Request body:**
```json
{
  "text": "On puc renovar el padró municipal?",
  "conversationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | The citizen's question |
| `conversationId` | string (UUID) | ❌ | Session identifier for multi-turn context |

**Server-Sent Events (SSE) Streaming Response:**

The endpoint supports full **Server-Sent Events (`text/event-stream`)** streaming. The response is a stream of JSON events, each on its own line:

```
data: {"type":"sources","sources":[{"url":"http://example.com","title":"..."}]}
data: {"type":"response","chunk":"Pots renovar..."}
data: {"type":"response","chunk":" el padró..."}
data: {"type":"complete","message":"Pots renovar el padró a l'Oficina d'Atenció Ciutadana...",...}
```

- **Event types**: 
  - `sources`: Procedure/document references retrieved from the RAG index
  - `response`: Streamed chunks of the AI response (for real-time display)
  - `complete`: Final response with full context (success/error status, error codes)

**Fallback JSON Response (non-streaming):**

If the client does not support SSE or sends an `Accept: application/json` header, the endpoint returns a complete JSON object with all information available at once:

```json
{
  "success": true,
  "message": "Pots renovar el padró a l'Oficina d'Atenció Ciutadana...",
  "error": null,
  "errorCode": 0
}
```

**Error response `422` (off-topic question, SSE):**
```
data: {"type":"complete","success":false,"message":null,"error":"Request is not about El Prat de Llobregat.","errorCode":2}
```

---

### `POST /complai/redact`

Draft a formal complaint letter addressed to the Ajuntament.

**Request body:**
```json
{
  "text": "El carrer de la Pau porta tres setmanes sense il·luminació.",
  "format": "pdf",
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "requesterName": "Maria",
  "requesterSurname": "Garcia",
  "requesterIdNumber": "12345678A"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | Description of the complaint (max 5,000 chars) |
| `format` | `"pdf"` \| `"json"` \| `"auto"` | ❌ | Output format (default: `"auto"`) |
| `conversationId` | string (UUID) | ❌ | Session identifier for multi-turn context |
| `requesterName` | string | ❌ | Complainant first name |
| `requesterSurname` | string | ❌ | Complainant surname |
| `requesterIdNumber` | string | ❌ | Complainant DNI / NIF / NIE |

> **Implementation Note (Current Sprint)**:  
> At the client boundary (web widget), only `"pdf"` is accepted as a valid format value. The backend currently accepts `"json"` and `"auto"` for internal testing and backward compatibility, but the frontend widget enforces `"pdf"` only to prevent unsupported output formats. This restriction should be hardened at the backend API boundary in a future release.

**`202 Accepted` — async PDF queued** (identity complete + format ≠ `"json"`):
```json
{
  "success": true,
  "message": "La vostra carta de reclamació s'està generant. Estarà disponible d'aquí a pocs minuts a l'adreça de sota.",
  "pdfUrl": "https://complai-complaints-development.s3.eu-west-1.amazonaws.com/complaints/abc-123/1741689600-complaint.pdf",
  "errorCode": 0
}
```
The `message` field is localised (Catalan / Spanish / English) based on the detected language of the complaint text. The `pdfUrl` is a pre-signed S3 GET URL valid for 24 hours. Poll it — it returns `403/404` while the worker is running and `200 application/pdf` once complete.

**`200 OK` — synchronous JSON letter** (identity incomplete or `format: "json"`):
```json
{
  "success": true,
  "message": "Estimat/da Ajuntament d'El Prat de Llobregat,\n\nEm dirigeixo...",
  "error": null,
  "errorCode": 0
}
```


---

### Error Codes

| `errorCode` | Name | HTTP Status | Meaning |
|-------------|------|-------------|---------|
| `0` | `NONE` | `200` | Success |
| `1` | `VALIDATION` | `400` | Bad request (empty text, unsupported format) |
| `2` | `REFUSAL` | `422` | AI refused: question not about El Prat |
| `3` | `UPSTREAM` | `502` | OpenRouter returned an error or unexpected response |
| `4` | `TIMEOUT` | `504` | OpenRouter call timed out (configurable via `OPENROUTER_OVERALL_TIMEOUT_SECONDS`, default 60 s) |
| `5` | `INTERNAL` | `500` | Unexpected server-side error |
| `6` | `UNAUTHORIZED` | `401` | Missing, expired, or invalid JWT Bearer token |

---

## 8. Conversation History (Multi-turn)

ComplAI supports natural, multi-turn conversations through an optional `conversationId` field.

**How it works:**

1. The front-end generates a UUID when the user opens the chat widget and reuses it for every
   message in that session.
2. For each request with a `conversationId`, the service retrieves the cached history
   (previous `{role, content}` pairs) and prepends it to the AI's `messages` array.
3. After a successful AI response, the new user and assistant messages are appended to the cache.
4. History is capped at **10 turns** (20 messages) to control AI token costs and latency.
5. Cache entries expire after **30 minutes** of inactivity.
6. If the `conversationId` is unknown or expired, the service starts fresh — no error is returned.

**Example conversation:**
```
Turn 1:  "Vull fer una queixa sobre soroll."
         → AI describes what information is needed

Turn 2:  "Els veïns del 3r fan soroll fins a les 3 de la matinada."
         → AI drafts the complaint incorporating Turn 1 context

Turn 3:  "Pots afegir la data d'avui?"
         → AI updates the letter — it remembers the full context
```

Omitting `conversationId` preserves fully stateless behaviour (no history, no side effects).

---

## 9. AI Identity and Behaviour

The assistant is always introduced as **Gall Potablava** — a friendly, approachable character whose
name is a nod to El Prat's emblematic bird, the *Ànec Collverd* (Mallard duck), whose feet are
*pota blava* (blue feet).

**Personality:**
- Friendly, approachable, and local — like a knowledgeable neighbour
- Short, easy-to-read responses — no unnecessary verbosity
- Civil and respectful — especially when drafting formal letters

**Scope enforcement:**
- The system prompt explicitly instructs the AI to **only answer questions about El Prat de Llobregat**.
- If a citizen asks about something else, the AI politely declines and suggests asking a local
  question.
- The service layer also performs **programmatic refusal detection** (phrase matching in Catalan,
  Spanish, and English) and maps detected refusals to the `REFUSAL` error code, ensuring the
  front-end always receives a typed, actionable signal rather than a text string to parse.

**Language:**
- Detects the citizen's language automatically from the input text.
- Responds in Catalan, Spanish, or English.
- The system prompt is trilingual so the AI has full context in all three languages.

---

## 10. PDF Complaint Letter Generation

PDF generation is handled exclusively by the **worker Lambda** via an asynchronous SQS flow. The API Lambda never produces PDF bytes — it only queues the job and returns a `202 Accepted` with a pre-signed URL.

**Async PDF flow:**

1. The API Lambda validates the request, generates an S3 key, and creates a 24-hour pre-signed GET URL for that key.
2. A `RedactSqsMessage` is published to the SQS redact queue and `202 Accepted` is returned immediately to the caller.
3. The worker Lambda is triggered by SQS and:
   - Calls `ComplaintLetterGenerator.generate(message)`
   - Builds the AI prompt via `RedactPromptBuilder.buildRedactPromptWithIdentity()`
   - Calls OpenRouter via `HttpWrapper`
   - Parses the AI response header with `AiParsed.parseAiFormatHeader()`
   - Renders the letter body as a PDF in-memory using `PdfGenerator` (Apache PDFBox)
   - Uploads the PDF bytes to S3 at the pre-determined key via `S3PdfUploader.upload(key, bytes)`
4. The caller polls the `pdfUrl` — it returns `403/404` while the worker is running and `200 application/pdf` once the PDF is uploaded (typically within seconds).

**AI format header:**
The AI is instructed to emit a JSON header on the first line of its response:
```
{"format": "pdf"}

Estimat/da Ajuntament d'El Prat de Llobregat,

Em dirigeixo a vostès per...
```
`AiParsed.parseAiFormatHeader()` handles three response shapes: clean first-line JSON, markdown-fenced JSON (` ```json ... ``` `), and JSON with an inline `body` key.

**Current PDF characteristics:**
- Font: `NotoSans-Regular.ttf` (embedded Unicode TrueType font — full coverage for Catalan, Spanish, and English characters including `ç`, `à`, `ü`, `·l`). Falls back to Helvetica if the resource is missing.
- Page size: A4
- Word-wrapped body with paragraph breaks and multi-page support
- No disk I/O — all rendering is in-memory

---

## 11. Infrastructure

### AWS Architecture

```
Internet → Lambda Function URL (public HTTPS) → ComplAILambda (Java 25) → OpenRouter API
                                                       │
                                                       │ SQS (complai-redact-<env>)
                                                       ▼
                                              ComplAIRedactorLambda (Java 25)
                                                       │
                                                       ▼
                                            S3 (complai-complaints-<env>)
```

Three independent CDK stacks are deployed per environment via **AWS CDK** (TypeScript):

| Stack | Purpose |
|-------|---------|
| `ComplAIStorageStack-<env>` | S3 buckets: `complai-procedures-<env>` (RAG data), `complai-complaints-<env>` (generated PDFs), `complai-deployments-<env>` (fat JARs for CI/CD deployments) |
| `ComplAIQueueStack-<env>` | SQS redact queue + DLQ (`complai-redact-<env>`, `complai-redact-dlq-<env>`) |
| `ComplAILambdaStack-<env>` | Both Lambda functions, IAM roles, CloudWatch log groups, metric filters |

**Per-environment Lambda resources:**
- `ComplAILambda-<env>` — API handler (`APIGatewayV2HTTPEventFunction`), 768 MB memory, 60 s timeout, Lambda Function URL (public HTTPS, CORS enabled)
- `ComplAIRedactorLambda-<env>` — Worker handler (`RedactWorkerHandler`), 768 MB memory, 60 s timeout, SQS event source (batch size 1, report batch item failures)
- IAM roles with least privilege:
  - API Lambda: `AWSLambdaBasicExecutionRole` + SQS `SendMessage` + S3 `GetObject` (complaints bucket for pre-signed URLs) + S3 `GetObject` (procedures bucket)
  - Worker Lambda: `AWSLambdaBasicExecutionRole` + SQS `ReceiveMessage`/`DeleteMessage` + S3 `PutObject` (complaints bucket) + S3 `GetObject` (procedures bucket)
- CloudWatch log groups with metric filters on `errorCode` and `latencyMs` audit log fields

**Stack deployment order** (cross-stack references via CloudFormation Exports):
```bash
cdk deploy 'ComplAI*Stack-development'   # deploys all three in dependency order
```
Never delete `StorageStack` or `QueueStack` while `LambdaStack` is deployed — CloudFormation will refuse to delete exported values still in use.

### Key configuration

```properties
# Runtime — Java 25, both Lambdas use the same complai-all.jar (shadowJar)
OPENROUTER_API_KEY     # Bearer token for OpenRouter, injected as Lambda env var, never committed
JWT_SECRET             # Base64-encoded HS256 key (min 32 bytes), injected as Lambda env var, never committed
OPENROUTER_MODEL       # Model identifier, e.g. stepfun/step-3.5-flash:free
```

### Local development

The `sam/` folder provides a **SAM CLI + LocalStack** environment for running both Lambdas locally:

```bash
cd sam
./start-local.sh           # builds JAR, starts LocalStack (S3 + SQS), starts SAM local API on :3000
# API available at http://localhost:3000
# SQS worker poller runs in the background and invokes ComplAIRedactorFunction via sam local invoke
```

---

## 12. Operational Details

### Lambda Configuration

| Parameter | Value | Purpose |
|-----------|-------|---------|
| **Memory** | 768 MB | Both API Lambda and Worker Lambda |
| **Timeout** | 60 seconds | Maximum execution time per invocation |
| **Deployment Region** | eu-west-1 (Ireland) | Optimized for European latency |
| **AWS Account** | 134267836527 | Where all resources are deployed |

### HTTP Client Configuration

The `HttpWrapper` class configures the following timeouts when communicating with OpenRouter:

| Setting | Value | Purpose |
|---------|-------|---------|
| **Connection Timeout** | 10 seconds | Time to establish TCP connection |
| **Read Timeout** | 60 seconds | Time to receive response body |
| **Max Concurrent Connections** | 20 | Pool size for concurrent OpenRouter calls |

### Performance Characteristics

- **P50 Latency**: ~2–3 seconds (with cache hit: <100ms)
- **P95 Latency**: ~5–8 seconds
- **P99 Latency**: ~15–20 seconds (includes procedural RAG retrieval)
- **Typical OpenRouter Call**: 1–3 seconds per request

### Cost Optimization

- **Serverless Pricing**: Pay per 100 ms of Lambda execution + OpenRouter API tokens consumed
- **No Idle Costs**: Lambda instances scale to zero after 15 minutes of inactivity
- **Cache Hit Savings**: Cached responses eliminate redundant OpenRouter calls

---

## 13. Security

| Concern | How it is addressed |
|---------|---------------------|
| API key exposure | Passed as CloudFormation `noEcho` parameter; injected as Lambda env var; never committed to source |
| **JWT Bearer authentication** | All `POST` endpoints require a valid `Authorization: Bearer <token>` header. `GET /` and `GET /health` are excluded (monitoring). Enforced by `JwtAuthFilter` before the controller is reached. |
| **JWT secret strength** | `JwtValidator` enforces ≥ 256 bits (32 bytes) at startup — the application fails immediately if the secret is absent or too weak, with no silent degradation to an insecure state. |
| **JWT claims enforced** | `exp` (token must have expiry), `sub` (must have subject), `iss` (must be `"complai"`), `city` (must be a non-blank city identifier). Tokens missing any of these are rejected with `401`. |
| Input injection | All user text is passed to the AI as a `content` string in a structured `messages` array — never interpolated into raw JSON strings that could alter the API call structure |
| Off-topic abuse | System prompt + programmatic refusal detection scope all AI responses to El Prat |
| Input length abuse | All user input is limited to 5,000 characters (`complai.input.max-length-chars`). Inputs exceeding the limit are rejected at the service boundary with `400 VALIDATION` before any AI call is made |
| IAM privilege | API Lambda role: `AWSLambdaBasicExecutionRole` + SQS send + S3 read. Worker Lambda role: `AWSLambdaBasicExecutionRole` + SQS consume + S3 write (complaints bucket only) |
| Secrets in logs | The API key and JWT secret are never logged. Raw input text and AI responses are never logged — `AuditLogger` writes only metadata (endpoint, request hash, error code, latency, format, language) |
| Unsupported format values | Rejected at controller boundary (`400 VALIDATION`) before touching the service |

### Rate Limiting

ComplAI implements per-IP rate limiting via the `RateLimitFilter` to protect against abuse and ensure fair resource allocation:

- **Implementation**: HTTP filter applied before controllers are reached
- **Strategy**: Per-source-IP rate limiting (clients behind the same proxy/NAT share a limit)
- **Default Limits**: Configurable via `complai.rate-limit.*` properties
  - Requests per minute per IP
  - Burst allowance for temporary traffic spikes
- **Rejection Behavior**: Requests exceeding the limit receive `429 Too Many Requests`
- **Configuration**:
  ```properties
  complai.rate-limit.enabled=true                    # Enable/disable rate limiting
  complai.rate-limit.requests-per-minute=100         # Requests per IP per minute
  complai.rate-limit.burst-size=20                   # Temporary burst allowance
  ```
- **Design Goal**: Prevent accidental or malicious request floods; allow normal citizen usage patterns

---

## 14. JWT Bearer Authentication

**Status: Fully implemented and enforced.**

All `POST` endpoints require a valid JWT Bearer token in the `Authorization` header:

```
Authorization: Bearer <token>
```

- `GET /` and `GET /health` are public and do **not** require authentication.
- The filter is enforced by `JwtAuthFilter` before the controller is reached.
- Tokens must be signed with the environment's `JWT_SECRET` (HS256, ≥32 bytes, base64-encoded).
- Required claims: `exp` (expiry), `sub` (subject), `iss` (must be `complai`), `city` (non-blank city identifier).
- Invalid, missing, or expired tokens result in `401 Unauthorized` with error code `6 (UNAUTHORIZED)`.

### Minting JWT Tokens

Tokens are minted offline using the `TokenGenerator` CLI utility bundled in the shadow JAR. The operator runs this locally and distributes the resulting token to API consumers (front-end, CI, E2E tests).

```bash
# Generate a fresh JWT_SECRET (run once per environment, store in GitHub Environment Secrets):
openssl rand -base64 32

# Mint a token valid for 30 days:
JWT_SECRET=<base64-secret> java -cp build/libs/complai-all.jar cat.complai.auth.TokenGenerator citizen-app 30 elprat

# Pass the token in every API request:
curl -X POST https://<lambda-url>/complai/ask \
     -H 'Authorization: Bearer <token>' \
     -H 'Content-Type: application/json' \
     -d '{"text": "On queda l'\''ajuntament?"}'
```

**Token rotation:** update the GitHub Environment Secret → redeploy → mint new tokens with `TokenGenerator`. Old tokens are invalidated immediately because `JwtValidator` uses the new key.

### Environment Variables

| Variable              | Required | Description                                     |
|-----------------------|----------|-------------------------------------------------|
| `JWT_SECRET`          | Yes      | Base64-encoded HS256 secret (min 32 bytes). Generate with `openssl rand -base64 32`. |
| `OPENROUTER_API_KEY`  | Yes      | Bearer token for the OpenRouter API              |
| `openrouter.url`      | No       | Override the OpenRouter endpoint (default: `https://openrouter.ai/api/v1/chat/completions`) |

---

## 15. OIDC Identity Verification (Cl@ve, VALId, idCat, etc.)

**Current Status**: OIDC identity verification is fully implemented but currently **disabled for all test cities**. Both El Prat (elprat) and Test City (testcity) have `enabled: false` in their OIDC configuration. This allows the feature to be dark-launched and tested before enabling in production.

### Non-Technical Overview

ComplAI now supports strong citizen identity verification using official OIDC-based providers such as Cl@ve, VALId, or idCat. When enabled, this ensures that complaint letters are cryptographically linked to the real identity of the citizen, as verified by the government’s digital identity system. This is required for certain official complaint flows (Option C).

- **What changes for users?**
  - When submitting a complaint, users are redirected to authenticate with their chosen identity provider (e.g., Cl@ve, VALId, idCat).
  - After successful authentication, the system automatically fills in their name, surname, and ID number in the complaint letter. Self-reported identity fields are ignored.
  - This provides a higher level of trust and legal validity for submitted complaints.

- **What changes for operators?**
  - No manual identity checks are needed for OIDC-verified complaints.
  - All identity data is extracted from the signed OIDC token, not from user input.

### Technical Details

- **How it works:**
  1. The frontend authenticates the user with the OIDC IdP (Cl@ve, VALId, idCat, etc.) and obtains an OIDC ID token.
  2. The frontend sends this token in the `X-Identity-Token` HTTP header when calling `POST /complai/redact`.
  3. The backend verifies the token's signature and claims using the per-city OIDC configuration in `src/main/resources/oidc/oidc-mapping.json` (bundled in the JAR). The city is resolved from the validated ComplAI Bearer JWT `city` claim.
  4. If valid, the backend extracts the citizen's identity from the token and overrides any self-reported body fields.
  5. If the `X-Identity-Token` header is **absent**, the request falls back to self-reported body fields (`requesterName`, `requesterSurname`, `requesterIdNumber`) as normal — existing consumers are unaffected.
  6. If the header is **present but the token is invalid or expired**, the request is rejected with `401 Unauthorized`.

- **Environment Variables (API Lambda only):**

  None beyond the standard set. Per-city OIDC configuration — including whether
  verification is active — is controlled entirely by the `"enabled"` flag in
  `src/main/resources/oidc/oidc-mapping.json` bundled in the JAR. No `IDENTITY_VERIFICATION_ENABLED`
  or `OIDC_*` environment variables are needed or read.

- **Operational Checklist:**
  1. Obtain OIDC IdP details (issuer, JWKS URI, audience/client ID, NIF claim) from your IdP admin or documentation.
  2. Add (or verify) the city's entry in `src/main/resources/oidc/oidc-mapping.json`. Set `"enabled": true` to activate, `"enabled": false` to prepare the config without activating it yet. Redeploy.
  3. Coordinate with the frontend to ensure it authenticates users and sends the OIDC token in the `X-Identity-Token` header.
  4. Test end-to-end with real tokens before enabling in production. Start with `"enabled": false` (dark launch — bean loads but skips this city), then flip to `true` and redeploy when ready.
  5. Monitor logs for errors (e.g., JWKS endpoint unreachable, key rotation issues). Lambda will fail fast at startup if a city has `"enabled": true` but its JWKS endpoint is unreachable.
  6. To onboard a new city, add an entry to `oidc-mapping.json` with `"enabled": true` and redeploy. To disable a city without removing its config, set `"enabled": false`.

- **Security Notes:**
  - The backend never generates OIDC tokens; it only verifies tokens issued by the IdP.
  - When the `X-Identity-Token` header is present, all identity data used in complaint letters is extracted from the verified token — self-reported body fields are ignored.
  - When the header is absent, the request proceeds with self-reported identity (or the AI asks for missing fields if the identity is incomplete).
  - No OIDC secrets (such as client secrets) are stored or required for this flow.

- **Relationship to JWT Bearer Authentication:**
  - The standard `Authorization: Bearer <token>` JWT is still required for all POST endpoints (API access control).
  - The OIDC token in `X-Identity-Token` is an additional, independent layer for verified citizen identity on `/complai/redact`.

---
