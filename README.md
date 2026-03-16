# ComplAI — Gall Potablava · Project Overview

© 2026 Raúl Torres Alarcón. All rights reserved.

LinkedIn: [My profile](https://www.linkedin.com/in/raultorresalarcon/)

Last version: [![GitHub Release](https://img.shields.io/github/v/release/raultorres2603/ComplAI)](https://github.com/raultorres2603/ComplAI/releases/latest)

> **Gall Potablava** is an AI assistant for the residents of El Prat de Llobregat (Catalonia, Spain).
> It helps citizens ask questions about local services, understand municipal procedures, and draft
> formal complaints addressed to the City Hall (Ajuntament d'El Prat de Llobregat).

---

## Table of Contents

1. [What Is This Project?](#1-what-is-this-project)
2. [Vision and Goals](#2-vision-and-goals)
3. [Integration with Prat Espais](#3-integration-with-prat-espais)
4. [Architecture Overview](#4-architecture-overview)
5. [How It Works — Request Lifecycle](#5-how-it-works--request-lifecycle)
6. [API Reference](#6-api-reference)
7. [Conversation History (Multi-turn)](#7-conversation-history-multi-turn)
8. [AI Identity and Behaviour](#8-ai-identity-and-behaviour)
9. [PDF Complaint Letter Generation](#9-pdf-complaint-letter-generation)
10. [Infrastructure](#10-infrastructure)
11. [Security](#11-security)
12. [JWT Bearer Authentication](#12-jwt-bearer-authentication)
13. [OIDC Identity Verification (Cl@ve, VALId, idCat, etc.)](#13-oidc-identity-verification-clave-valid-idcat-etc)
---

## 1. What Is This Project?

**ComplAI** is a serverless REST API that acts as the backend "brain" of a citizen-facing chatbot.
Its front-end integration point is **Prat Espais**, the digital services platform for residents of
El Prat de Llobregat. Citizens interact through a chat widget embedded in the Prat Espais web
application; those messages are forwarded to this API, which processes them with an AI language
model and returns a response.

The assistant can:

- **Answer local questions** — opening hours, public services, transport, events, municipal
  offices, bins, parks, etc.
- **Explain municipal procedures** — how to register as a resident, apply for a grant, report a
  pothole, request a permit, etc.
- **Help carry out procedures** — guided step-by-step assistance so citizens can start or complete
  their request with the Ajuntament.
- **Draft formal complaint letters** — given a description of a problem, the assistant produces a
  complete, formal letter addressed to the Ajuntament ready to print, submit digitally, or download
  as a PDF.

All three languages spoken in the area are supported: **Catalan (default)**, **Spanish**, and
**English**.

---

## 2. Vision and Goals

| Goal | How ComplAI addresses it |
|------|--------------------------|
| Reduce the cognitive burden for citizens navigating bureaucracy | Plain-language answers and step-by-step guidance in the user's language |
| Lower the barrier to filing formal complaints | Automatic letter drafting — the citizen describes the problem, the AI writes the letter |
| Serve the entire El Prat community | Catalan, Spanish, and English support out of the box |
| Integrate naturally into existing digital infrastructure | REST API designed to be consumed by any front-end, starting with Prat Espais |
| Keep operating costs predictable and low | Serverless Lambda; no servers idle overnight; pay per request |
| Be production-safe and maintainable long-term | Boring, layered architecture; typed error codes; full test coverage |

---

## 3. Integration with Prat Espais

**Prat Espais** is the citizen-facing digital platform for El Prat de Llobregat. ComplAI is designed
to be embedded in it as a **chat widget** — a floating conversation panel that citizens open from
any page of the portal.

```
┌─────────────────────────────────────────────┐
│             Prat Espais (Web App)            │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │  📋 Tràmits   🗺️ Mapa   📅 Agenda  │   │
│  └──────────────────────────────────────┘   │
│                                              │
│                         ┌─────────────────┐ │
│                         │  💬 Gall        │ │
│                         │   Potablava     │ │
│                         │                 │ │
│                         │ Citizen: "Com   │ │
│                         │  puc registrar  │ │
│                         │  el meu gos?"   │ │
│                         │                 │ │
│                         │ AI: "Per        │ │
│                         │  registrar el   │ │
│                         │  teu gos..."    │ │
│                         └─────────────────┘ │
└─────────────────────────────────────────────┘
```

### How the front-end calls the API

The chat widget makes standard `POST` requests to the ComplAI Lambda Function URL endpoint. Two flows exist:

1. **Conversational questions** (`POST /complai/ask`) — the citizen types a question; the widget
   sends it and displays the AI response as a new chat bubble.
2. **Complaint drafting** (`POST /complai/redact`) — the citizen describes their problem; the
   widget sends it with `"format": "pdf"` or `"format": "json"` and either renders the letter
   in-chat or triggers a PDF download.

Both calls can include an optional `conversationId` (a UUID the front-end generates per session)
so the AI remembers the context of the conversation. This is what allows natural multi-turn
exchanges: *"Can you add my neighbour's name to the letter?"* works because the previous turn is
remembered.

**Endpoint:**
- The public endpoint is the Lambda Function URL, e.g.:
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
│                        AWS Lambda (Java 21)                      │
│                    Micronaut 4 function runtime                  │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ Controller layer  (OpenRouterController, HomeController)   │  │
│  │  • HTTP boundary: deserialise request, validate format     │  │
│  │  • Maps typed error codes → HTTP status codes              │  │
│  │  • Returns JSON or PDF binary                              │  │
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
│               model: stepfun/step-3.5-flash:free (default)      │
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

## 5. How It Works — Request Lifecycle

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

## 6. API Reference

### `GET /`

Health check and welcome endpoint.

**Response `200 OK`:**
```json
{ "message": "Welcome to the Complai Home Page!" }
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

**Successful response `200 OK`:**
```json
{
  "success": true,
  "message": "Pots renovar el padró a l'Oficina d'Atenció Ciutadana...",
  "error": null,
  "errorCode": 0
}
```

**Error response `422` (off-topic question):**
```json
{
  "success": false,
  "message": null,
  "error": "Request is not about El Prat de Llobregat.",
  "errorCode": 2
}
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
| `4` | `TIMEOUT` | `504` | OpenRouter call timed out (>30 seconds) |
| `5` | `INTERNAL` | `500` | Unexpected server-side error |
| `6` | `UNAUTHORIZED` | `401` | Missing, expired, or invalid JWT Bearer token |

---

## 7. Conversation History (Multi-turn)

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

## 8. AI Identity and Behaviour

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

## 9. PDF Complaint Letter Generation

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

## 10. Infrastructure

### AWS Architecture

```
Internet → Lambda Function URL (public HTTPS) → ComplAILambda (Java 21) → OpenRouter API
                                                       │
                                                       │ SQS (complai-redact-<env>)
                                                       ▼
                                              ComplAIRedactorLambda (Java 21)
                                                       │
                                                       ▼
                                            S3 (complai-complaints-<env>)
```

Three independent CDK stacks are deployed per environment via **AWS CDK** (TypeScript):

| Stack | Purpose |
|-------|---------|
| `ComplAIStorageStack-<env>` | S3 buckets: `complai-procedures-<env>` (RAG data) and `complai-complaints-<env>` (generated PDFs) |
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
# Runtime — Java 21, both Lambdas use the same complai-all.jar (shadowJar)
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

## 11. Security

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
| Secrets in logs | The API key and JWT secret are never logged. Refusal messages and AI text are logged at fine/info level only |
| Unsupported format values | Rejected at controller boundary (`400 VALIDATION`) before touching the service |

---

## 12. JWT Bearer Authentication

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
- The filter is tested in both integration and E2E tests. See [Testing Strategy](#14-testing-strategy).

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

## 13. OIDC Identity Verification (Cl@ve, VALId, idCat, etc.)

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
  3. The backend verifies the token’s signature and claims using the configured OIDC environment variables.
  4. If valid, the backend extracts the citizen’s identity from the token and overrides any self-reported fields.
  5. If the token is missing, invalid, or expired, the request is rejected with a `401 Unauthorized` error.

- **Environment Variables (API Lambda only):**

  | Variable                    | Required | Secret? | Description                                                      |
  |-----------------------------|----------|---------|------------------------------------------------------------------|
  | `IDENTITY_VERIFICATION_ENABLED` | Yes      | No      | Feature flag to enable OIDC identity verification                |
  | `OIDC_ISSUER_URL`           | Yes      | No      | OIDC issuer URL (e.g. https://identitats.aoc.cat)                |
  | `OIDC_JWKS_URI`             | Yes      | No      | JWKS endpoint for public keys                                    |
  | `OIDC_AUDIENCE`             | Yes      | No*     | Client ID registered with the IdP                                |
  | `OIDC_NIF_CLAIM`            | No       | No      | Claim name for NIF/NIE (default: `sub`)                          |

  *Client ID is not a secret, but do not publish it widely.

- **Operational Checklist:**
  1. Obtain OIDC IdP details (issuer, JWKS URI, audience/client ID, NIF claim) from your IdP admin or documentation.
  2. Set the above environment variables on the API Lambda (not the worker Lambda).
  3. Coordinate with the frontend to ensure it authenticates users and sends the OIDC token in the `X-Identity-Token` header.
  4. Test end-to-end with real tokens before enabling in production. Start with `IDENTITY_VERIFICATION_ENABLED=false` (dark launch), then flip to `true` when ready.
  5. Monitor logs for errors (e.g., JWKS endpoint unreachable, key rotation issues). Lambda will fail fast if misconfigured.
  6. No backend code changes are needed; all logic is feature-flagged and fully tested.

- **Security Notes:**
  - The backend never generates OIDC tokens; it only verifies tokens issued by the IdP.
  - All identity data used in complaint letters is extracted from the verified token.
  - If the OIDC token is missing or invalid, the request is rejected. No fallback to self-reported identity is allowed when verification is enabled.
  - No OIDC secrets (such as client secrets) are stored or required for this flow.

- **Relationship to JWT Bearer Authentication:**
  - The standard `Authorization: Bearer <token>` JWT is still required for all POST endpoints (API access control).
  - The OIDC token in `X-Identity-Token` is an additional, independent layer for verified citizen identity on `/complai/redact`.

---
