# ComplAI — Gall Potablava · Project Overview

© 2026 Raúl Torres Alarcón. All rights reserved.

LinkedIn: [My profile](https://www.linkedin.com/in/raultorresalarcon/)

Last version: [![GitHub Release](https://img.shields.io/github/v/release/raultorres2603/ComplAI)](https://github.com/raultorres2603/ComplAI/releases/latest)

> **Gall Potablava** is an AI assistant for the residents of El Prat de Llobregat (Catalonia, Spain).
> It helps citizens ask questions about local services, understand municipal procedures, and draft
> formal complaints addressed to the City Hall (Ajuntament d'El Prat de Llobregat).
>
> Version `0.9.6` — Java 25 · Micronaut 4.10.7 · AWS Lambda (ARM64) · API Gateway HTTP API

---

## Table of Contents

1. [What Is This Project?](#1-what-is-this-project)
2. [Vision and Goals](#2-vision-and-goals)
3. [Tech Stack](#3-tech-stack)
4. [Integration with Prat Espais](#4-integration-with-prat-espais)
5. [Architecture Overview](#5-architecture-overview)
6. [Getting Started (Local Dev)](#6-getting-started-local-dev)
7. [API Reference](#7-api-reference)
8. [Conversation History (Multi-turn)](#8-conversation-history-multi-turn)
9. [AI Identity and Behaviour](#9-ai-identity-and-behaviour)
10. [Performance Optimizations](#10-performance-optimizations)
11. [PDF Complaint Letter Generation](#11-pdf-complaint-letter-generation)
12. [Infrastructure](#12-infrastructure)
13. [Security and Authentication](#13-security-and-authentication)
14. [OIDC Identity Verification (Cl@ve, VALId, idCat, etc.)](#14-oidc-identity-verification-clave-valid-idcat-etc)
15. [Testing](#15-testing)
16. [Audit Logging](#16-audit-logging)
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

## 3. Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language / Runtime** | Java 25 |
| **Framework** | Micronaut 4.10.7 (`lambda_provided` runtime) |
| **Build** | Gradle 8 + Shadow JAR (`complai-all.jar`) |
| **Cloud Provider** | AWS (eu-west-1) |
| **Compute** | AWS Lambda (ARM64, 1 024 MB, Java 25 runtime) |
| **API Layer** | AWS API Gateway HTTP API (payload format v2) |
| **WAF** | AWS WAF (production only — geo-restrict to Spain + rate limiting) |
| **Messaging** | AWS SQS (redact queue, feedback queue — each with DLQ) |
| **Storage** | AWS S3 (procedures, events, news, city-info, complaints, feedback, deployments) |
| **AI** | OpenRouter API (model configurable via `OPENROUTER_MODEL`) |
| **PDF generation** | Apache PDFBox 2.0 (NotoSans TrueType, in-memory) |
| **Caching** | Caffeine (conversation history + response cache) |
| **Auth** | API Key (`X-Api-Key` header) per city + optional OIDC identity token for complaints |
| **JWT / OIDC** | JJWT 0.12 (OIDC `X-Identity-Token` verification — Cl@ve, VALId, idCat) |
| **HTML parsing** | Jsoup 1.17 |
| **IaC** | AWS CDK v2 (TypeScript) |
| **Local dev** | AWS SAM CLI + LocalStack 3 (Docker) |
| **Testing** | JUnit 5 + Mockito 5; Bruno (`.bru`) for E2E |

---

## 4. Integration with Prat Espais

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

The chat widget makes standard `POST` requests to the ComplAI API Gateway endpoint. Two flows exist:

1. **Conversational questions** (`POST /complai/ask`) — the citizen types a question; the widget
   sends it and displays the AI response as a new chat bubble (streamed via Server-Sent Events).
2. **Complaint drafting** (`POST /complai/redact`) — the citizen describes their problem;
   the widget sends it with `"format": "pdf"` and polls the returned pre-signed URL.

All calls require an `X-Api-Key` header. Both can include an optional `conversationId`
(a UUID the front-end generates per session) so the AI remembers conversation context. This
explains why *"Can you add my neighbour's name to the letter?"* works on the second turn.

**API base URL:**
`https://<api-gateway-id>.execute-api.<region>.amazonaws.com/$default`

CORS is configured on the API Gateway; allowed origins are set via `COMPLAI_CORS_ALLOWED_ORIGIN`.

### What the front-end receives

| Scenario | Response |
|---|---|
| Successful answer (SSE) | `200 OK` — `text/event-stream` with progressive response chunks |
| PDF queued (identity complete) | `202 Accepted` with `pdfUrl` pre-signed S3 link |
| Question not about El Prat | `422 Unprocessable Entity` with error code `REFUSAL` |
| Invalid request | `400 Bad Request` with error code `VALIDATION` |
| Missing or invalid API key | `401 Unauthorized` with error code `UNAUTHORIZED` |
| AI service unavailable | `502 Bad Gateway` with error code `UPSTREAM` |

---

## 5. Architecture Overview

ComplAI follows a strict **layered architecture** with clear boundaries at every level.

```
┌──────────────────────────────────────────────────────────────────┐
│          API Gateway HTTP API  (public HTTPS, CORS, throttle)    │
│       WAF WebACL attached in production (geo + rate limit)       │
└───────────────────────────────┬──────────────────────────────────┘
                                │ HTTP event (payload format v2)
┌───────────────────────────────▼──────────────────────────────────┐
│               ComplAILambda  (Java 25, ARM64, 1 024 MB)          │
│                    Micronaut 4.10.7 function runtime             │
│                                                                  │
│  ApiKeyAuthFilter → RateLimitFilter → Controller                 │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ Controller layer  (OpenRouterController, HomeController,   │  │
│  │                    HealthController, FeedbackController)   │  │
│  │  • HTTP boundary: deserialise request, validate format     │  │
│  │  • Maps typed error codes → HTTP status codes              │  │
│  │  • Returns JSON or SSE stream (PDFs are async only)        │  │
│  └───────────────────────────┬────────────────────────────────┘  │
│                              │ calls                              │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │ Service layer  (OpenRouterServices via IOpenRouterService)  │  │
│  │  • Validates inputs (null/blank checks, max 5 000 chars)   │  │
│  │  • RAG context enrichment (procedures, events, news)       │  │
│  │  • Builds AI prompt + message history                      │  │
│  │  • Detects AI refusals + streams SSE chunks                │  │
│  │  • Publishes to SQS for async PDF/feedback generation      │  │
│  └───────────────────────────┬────────────────────────────────┘  │
│                              │                                    │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │ HttpWrapper  (Micronaut HTTP client, OpenAI-compatible SSE) │  │
│  └───────────────────────────┬────────────────────────────────┘  │
│                              │                                    │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │ Caffeine Cache                                             │  │
│  │  • Conversation history: TTL 30 min, max 5 turns           │  │
│  │  • Response cache: TTL 10 min, max 500 entries (privacy)   │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
          │ SQS (complai-redact-<env>)      │ SQS (complai-feedback-<env>)
          ▼                                ▼
 ComplAIRedactorLambda              ComplAIFeedbackWorkerLambda
 (ARM64, 1024 MB)                   (ARM64, 1024 MB)
          │                                │
          ▼ s3:PutObject                   ▼ s3:PutObject
 complai-complaints-<env>          complai-feedback-<env>
          ▲
          │ s3:GetObject (pre-signed URL signer)
   ComplAILambda (API)

                               │ HTTPS POST (streaming)
                ┌──────────────▼─────────────┐
                │        OpenRouter API       │
                │  model: OPENROUTER_MODEL    │
                └────────────────────────────┘
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

- **Conversation Cache (Caffeine)**
  - **Scope**: Conversation history keyed by `conversationId`
  - **TTL**: 30 minutes of inactivity, max **5 turns** (10 messages) per conversation
  - On a cache hit the service skips the OpenRouter call and returns the cached response immediately

- **Response Cache (Caffeine)**
  - **Implementation**: `ResponseCacheService` — privacy-first cache, key is `cityId + contextHash + questionCategory` (no user text stored)
  - **TTL**: 10 minutes, max 500 entries (LRU eviction)
  - **Feature flag**: `RESPONSE_CACHE_ENABLED` (default `true`)
  - **Use Cases**: Identical factual queries (e.g., "opening hours of the library") served from cache without calling OpenRouter
  
- **Tier 2: S3 Integration**
  - Prepared for future long-term response caching (cross-session persistence)
  - Not enabled by default

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

> **Authentication**: All `POST` endpoints require `X-Api-Key: <key>` in the request header.
> `GET /`, `GET /health`, and `GET /health/startup` are public and do not require a key.

### `GET /`

Welcome endpoint.

**Response `200 OK`:**
```json
{ "message": "Welcome to the Complai Home Page!" }
```

---

### `GET /health`

Full liveness check — triggers RAG index initialisation if not yet done.

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

### `GET /health/startup`

Lightweight startup probe — does **not** trigger RAG initialisation. Intended for Lambda warmup checks.

**Response `200 OK`:** same shape as `GET /health`.

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

If the client sends `Accept: application/json`, the endpoint returns a complete JSON object:

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
