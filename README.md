# ComplAI — Gall Potablava · Project Overview

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
12. [Local Development](#12-local-development)
13. [Testing Strategy](#13-testing-strategy)
14. [CI/CD Pipeline](#14-cicd-pipeline)
15. [Feature Proposals and Roadmap](#15-feature-proposals-and-roadmap)

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

The chat widget makes standard `POST` requests to the ComplAI API Gateway endpoint. Two flows exist:

1. **Conversational questions** (`POST /complai/ask`) — the citizen types a question; the widget
   sends it and displays the AI response as a new chat bubble.
2. **Complaint drafting** (`POST /complai/redact`) — the citizen describes their problem; the
   widget sends it with `"format": "pdf"` or `"format": "json"` and either renders the letter
   in-chat or triggers a PDF download.

Both calls can include an optional `conversationId` (a UUID the front-end generates per session)
so the AI remembers the context of the conversation. This is what allows natural multi-turn
exchanges: *"Can you add my neighbour's name to the letter?"* works because the previous turn is
remembered.

### What the front-end receives

| Scenario | Response |
|---|---|
| Successful answer | `200 OK` with JSON body containing the AI text |
| Successful PDF letter | `200 OK` with `Content-Type: application/pdf` binary body |
| Question not about El Prat | `422 Unprocessable Entity` with error code `REFUSAL` |
| Invalid request | `400 Bad Request` with error code `VALIDATION` |
| AI service unavailable | `502 Bad Gateway` with error code `UPSTREAM` |

---

## 4. Architecture Overview

ComplAI follows a strict **layered architecture** with clear boundaries at every level.

```
┌──────────────────────────────────────────────────────────────────┐
│                     API Gateway v2 (HTTP API)                    │
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
│  │  • Generates PDFs with PDFBox                              │  │
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
│                    OpenRouter API                                 │
│               model: minimax/minimax-m2.5                        │
└──────────────────────────────────────────────────────────────────┘
```

### Key design decisions

- **Stateless Lambda**: the function holds no state between invocations. Conversation history
  lives in a Caffeine in-memory cache scoped to a warm Lambda instance — enough for a typical
  user session without requiring DynamoDB.
- **Typed error codes**: `OpenRouterErrorCode` enum (NONE, VALIDATION, REFUSAL, UPSTREAM, TIMEOUT,
  INTERNAL) is the authoritative signal from service to controller. No string-matching on error
  messages.
- **Interface-based contracts**: the controller depends on `IOpenRouterService`, not the
  implementation. This makes the service trivially replaceable in tests.
- **Never throw to the controller**: the service always returns an `OpenRouterResponseDto`. All
  exceptions are caught, logged, and converted to typed errors internally.

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

```
1.  Front-end sends: { "text": "El carrer no té llum.", "format": "pdf", "conversationId": "uuid" }
2.  Controller validates format field (rejects "xml", "docx", etc. with 400 before touching service)
3.  Service:
      a. Validates text is not blank
      b. Builds prompt instructing AI to output a JSON header line + letter body
      c. Calls OpenRouter with conversation history prepended
      d. On non-success result (refusal, error): returns immediately — no PDF attempted
      e. Parses AI JSON header (e.g. {"format": "pdf"}) from first response line
      f. If header missing and format is PDF → returns UPSTREAM error
      g. If header missing and format is AUTO/JSON → returns raw text gracefully
      h. If format is PDF → generates PDF in-memory with PDFBox
      i. Returns OpenRouterResponseDto with pdfData byte[]
4.  Controller detects pdfData present → returns HTTP 200 application/pdf with Content-Length
5.  Front-end triggers browser PDF download
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
  "conversationId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ✅ | Description of the complaint |
| `format` | `"pdf"` \| `"json"` \| `"auto"` | ❌ | Output format (default: `"auto"`) |
| `conversationId` | string (UUID) | ❌ | Session identifier for multi-turn context |

**Successful JSON response `200 OK`** (`format: "json"` or `"auto"`):
```json
{
  "success": true,
  "message": "Estimat/da Ajuntament d'El Prat de Llobregat,\n\nEm dirigeixo...",
  "error": null,
  "errorCode": 0
}
```

**Successful PDF response `200 OK`** (`format: "pdf"`):
```
Content-Type: application/pdf
Content-Length: 12345

<binary PDF data>
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

When the citizen requests a PDF (or when the AI decides PDF is appropriate in `auto` mode), the
service generates a formal letter document in-memory using **Apache PDFBox**.

**PDF generation flow:**

1. The AI is instructed to emit a JSON header on the first line of its response:
   ```
   {"format": "pdf"}
   
   Estimat/da Ajuntament d'El Prat de Llobregat,
   
   Em dirigeixo a vostès per...
   ```
2. `AiParsed.parseAiFormatHeader()` extracts the header and the clean letter body.
3. PDFBox renders the body as a multi-page LETTER-format document with word-wrap and
   paragraph spacing.
4. The PDF bytes are returned in-memory — no disk I/O, no temp files.
5. The controller sets `Content-Type: application/pdf` and an explicit `Content-Length` header.
   The latter is required to prevent Netty from closing the connection before the client reads
   the full binary response.

**Current PDF characteristics:**
- Font: Helvetica / Helvetica Bold
- Page size: LETTER
- Title: "Redacted complaint"
- Word-wrapped body with paragraph breaks and multi-page support

> ⚠️ Known limitation: `PDType1Font.HELVETICA` has incomplete Unicode coverage. Catalan-specific
> characters such as `ç`, `à`, `ü`, and `·l` may not render correctly. Embedding a TrueType/OpenType
> font is planned (see Roadmap).

---

## 10. Infrastructure

### AWS Architecture

```
Internet → API Gateway v2 (HTTP API) → Lambda (Java 21 / SnapStart) → OpenRouter API
```

Two independent stacks deployed via **AWS CDK** (TypeScript):

| Stack | Purpose |
|-------|---------|
| `ComplAILambdaStack-development` | Pre-production. Used by CI on every PR and manual deploys. |
| `ComplAILambdaStack-production` | Live environment. Only deployable from `master`, requires manual approval. |

**Per-stack AWS resources:**
- `AWS::Lambda::Function` (Java 21, SnapStart enabled, 512 MB memory, 30 s timeout)
- `AWS::ApiGatewayV2::Api` + `$default` stage with `$default` route
- `AWS::IAM::Role` with least-privilege (`AWSLambdaBasicExecutionRole` only)
- `AWS::Logs::LogGroup` with 30-day retention

### Key configuration

```properties
# Runtime — Java 21 SnapStart
OPENROUTER_API_KEY   # Bearer token, injected as Lambda env var, never committed
openrouter.url       # OpenRouter endpoint (overridable for local/test)
```

### Local development

The `sam/` folder provides a **SAM CLI + LocalStack** environment for running the Lambda locally:

```bash
cd sam
./start-local.sh           # builds JAR, starts LocalStack, starts SAM local API
# API available at http://localhost:3000
```

---

## 11. Security

| Concern | How it is addressed |
|---------|---------------------|
| API key exposure | Passed as CloudFormation `noEcho` parameter; injected as Lambda env var; never committed to source |
| Input injection | All user text is passed to the AI as a `content` string in a structured `messages` array — never interpolated into raw JSON strings that could alter the API call structure |
| Off-topic abuse | System prompt + programmatic refusal detection scope all AI responses to El Prat |
| Input length abuse | Null/blank validation at service boundary. Length limits are on the roadmap (see Feature #8) |
| IAM privilege | Lambda role has `AWSLambdaBasicExecutionRole` only — no S3, no DynamoDB, no secrets manager |
| Secrets in logs | The API key is never logged. Refusal messages and AI text are logged at fine/info level only |
| Unsupported format values | Rejected at controller boundary (`400 VALIDATION`) before touching the service |

---

## 12. Local Development

### Prerequisites

- Java 21
- Docker (for LocalStack)
- AWS SAM CLI
- Node.js 18+ (for CDK)

### Build and test

```bash
# Compile and package
./gradlew clean shadowJar

# Run all tests
./gradlew test

# Run with verbose logging (CI mode)
./gradlew ciTest
```

### Run locally with SAM

```bash
cd sam
cp env.json.example env.json   # fill in your OPENROUTER_API_KEY
./start-local.sh
# Test with curl:
curl -X POST http://localhost:3000/complai/ask \
     -H 'Content-Type: application/json' \
     -d '{"text": "On queda l'\''ajuntament?"}'
```

### E2E tests (Bruno)

```bash
npm install -g @usebruno/cli
cd E2E-ComplAI
bru run . -r --env Development --reporter-json report/results.json
```

---

## 13. Testing Strategy

ComplAI has three layers of tests, each with a distinct purpose:

### Unit tests

- **`OpenRouterControllerTest`** — tests the controller with hand-written fake service
  implementations (`FakeServiceSuccess`, `FakeServiceRefuse`). No HTTP, no Micronaut, no DI.
  Covers: correct DTO mapping, error code to HTTP status translation, unsupported format rejection,
  unexpected exception handling.

- **`OpenRouterServicesTest`** — tests service logic directly: refusal detection phrases (in CA,
  ES, EN), PDF byte generation, blank input rejection, missing AI header graceful fallback.

- **`HttpWrapperTest`** — tests the HTTP infrastructure with a real `HttpServer` (JDK built-in).
  Covers: successful response parsing, non-2xx error handling, URL normalisation, missing API key
  handling.

### Integration tests

- **`OpenRouterControllerIntegrationTest`** — starts the full Micronaut application with
  `@MicronautTest`. A `@Factory` inner class replaces `HttpWrapper` with a mock that routes
  scenarios based on the last user message content. The mock overrides the multi-turn
  `postToOpenRouterAsync(List<Map<String, Object>>)` overload that the service actually calls.

### E2E tests (Bruno)

- HTTP-level tests against the live development API or a local SAM instance.
- Covers: multi-turn conversation (create conversationId → ask → follow-up), error handling
  (off-topic question, unsupported format), and PDF generation.
- Run automatically in CI after every development deployment.

### Design principle

> *If something is hard to test, the design is probably wrong.*

All external dependencies (`HttpWrapper`) are injected via constructor. All service methods return
typed DTOs, never throw. No static calls, no `System.exit`, no global state. This makes every
component independently testable with plain Java.

---

## 14. CI/CD Pipeline

The GitHub Actions workflow (`deploy.yml`) follows a **build once, deploy many** model.

```
┌─────────────────────────────────────────────────────┐
│  Trigger: Pull Request (auto) or workflow_dispatch  │
└────────────────────────┬────────────────────────────┘
                         │
              ┌──────────▼─────────┐
              │  guard             │ Blocks production from non-master
              └──────────┬─────────┘
                         │
              ┌──────────▼─────────┐
              │  build             │ ./gradlew clean shadowJar test
              │                    │ Uploads complai-all.jar artifact
              └──────────┬─────────┘
                         │
              ┌──────────▼─────────┐
              │  deploy            │ AWS CDK deploy (dev or prod)
              │                    │ OIDC → IAM (no long-lived keys)
              └──────────┬─────────┘
                         │
            ┌────────────┘  (only when env=development)
            │
  ┌─────────▼──────────┐
  │  e2e               │ bru run . --env Development
  │                    │ Uploads results.json artifact
  └────────────────────┘
```

**Key safety rules:**
- Production deploys require manual approval via GitHub Environment reviewers.
- Production can only be deployed from the `master` branch (enforced by the `guard` job).
- Pull requests always deploy to `development` only — never to production.
- The E2E suite must pass before the workflow is considered successful.

---

## 15. Feature Proposals and Roadmap


After a thorough analysis of the codebase, architecture, and project purpose, here are feature proposals organized by impact and feasibility. Each one respects the existing patterns: layered architecture, interface-based services, typed error codes, constructor injection, immutable DTOs, and the "boring & reliable" philosophy.

---

### 1. 🔄 Conversation History (Multi-turn Context) **[DONE]**

**Status:** Implemented. Conversation history with `conversationId` is now supported and maintained in the Caffeine cache, following the proposal below.

**Problem:** Today, every `/complai/ask` and `/complai/redact` call is stateless — a single prompt with no memory. A citizen asking follow-up questions ("Can you add my address to the letter?", "What about the noise complaint I mentioned?") starts from scratch every time.

**Proposal:**
- Accept an optional `conversationId` (UUID) in request bodies.
- Maintain a short-lived, bounded conversation history in the Caffeine cache (already in your stack). The key is the `conversationId`; the value is a list of `{role, content}` message pairs (capped at ~10 turns / ~4K tokens to control costs and latency).
- When a `conversationId` is present, prepend the cached history to the OpenRouter `messages` array.
- If the conversation is expired or unknown, start fresh (no error — graceful degradation).

**Why it fits:**
- Uses existing Caffeine cache dependency (no new infra).
- Stateless at the Lambda level — cache lives for the duration of a warm instance, which is enough for a typical user session. No DynamoDB needed.
- Service interface change is additive: `ask(String question, String conversationId)`.

**Files affected:** `IOpenRouterService`, `OpenRouterServices`, `AskRequest`, `RedactRequest`, `OpenRouterController`, `HttpWrapper` (pass full message list).

---

### 2. 📊 Request Rate Limiting

**Problem:** The API is publicly exposed behind API Gateway with no throttling. A single user (or bot) could exhaust the OpenRouter API quota or spike AWS costs.

**Proposal:**
- **Option A (Infra-level, preferred):** Add throttling configuration on the API Gateway `$default` stage in CDK. API Gateway v2 supports `ThrottleSettings` (rate + burst) on `CfnStage`. Zero code changes, just CDK.
- **Option B (App-level, complementary):** Add a lightweight in-memory rate limiter (Caffeine-based, keyed by source IP from `X-Forwarded-For`) in a Micronaut `@Filter`. Return `429 Too Many Requests` with a `Retry-After` header. Add `RATE_LIMITED(6)` to `OpenRouterErrorCode`.

**Why it fits:**
- Option A is the boring, correct solution — infra concern handled at the infra layer.
- Option B gives finer-grained control and a clear error code for clients.

**Files affected (Option A):** `lambda-stack.ts` only. **(Option B):** new `RateLimitFilter.java`, `OpenRouterErrorCode`.

---

### 3. 🩺 Structured Health Check Endpoint

**Problem:** The current `GET /` returns a static welcome message. It tells you nothing about whether the system can actually serve requests (e.g., is the API key configured? Can we reach OpenRouter?).

**Proposal:**
- Create `GET /health` returning a structured response:
  ```json
  {
    "status": "UP",
    "version": "0.1",
    "checks": {
      "openRouterApiKeyConfigured": true,
      "openRouterReachable": true
    }
  }
  ```
- `openRouterApiKeyConfigured`: boolean check that the key is non-blank (never logs/exposes the key).
- `openRouterReachable`: optional shallow ping (HEAD or GET to OpenRouter base URL with a short timeout). Can be cached for 60s to avoid hammering the upstream.
- Keep `GET /` as-is for backward compatibility.

**Why it fits:**
- Standard operational practice. Useful for monitoring, AWS health checks, and debugging deployment issues.
- No new dependencies. Simple, explicit, testable.

**Files affected:** New `HealthController.java`, `HealthDto.java`. Optionally a `HealthService` if the OpenRouter reachability check is included.

---

### 4. 🌐 Explicit Language Selection

**Problem:** The system prompt instructs the AI to respond in Catalan, Spanish, or English, but the *user* has no way to explicitly request a language. The AI guesses based on the input language, which can be wrong (e.g., a Catalan speaker writing in Spanish might still want the complaint letter in Catalan).

**Proposal:**
- Add an optional `language` field to `AskRequest` and `RedactRequest` — enum with values `CA` (Catalan), `ES` (Spanish), `EN` (English), or `null` (auto-detect, current behavior).
- Append a language instruction to the prompt: *"Respond in Catalan"* / *"Responde en español"* / *"Respond in English"*.
- `IOpenRouterService.ask(String question, Language language)` and `redactComplaint(String complaint, OutputFormat format, Language language)`.

**Why it fits:**
- Directly serves citizen needs — official complaints to the Ajuntament should be in the language the citizen intends.
- Additive, backward-compatible (null = current behavior).
- Trivial to implement: one new enum, one prompt line.

**Files affected:** New `Language.java` enum, `AskRequest`, `RedactRequest`, `IOpenRouterService`, `OpenRouterServices`, `OpenRouterController`.

---

### 5. 📋 Complaint Templates / Categories

**Problem:** Citizens must describe their complaint from scratch. Many complaints fall into common categories (noise, cleanliness, public infrastructure, parking, public transport, etc.). Pre-categorized prompts would produce better, more targeted letters.

**Proposal:**
- Add a `GET /complai/categories` endpoint returning a list of complaint categories with IDs, display names (in CA/ES/EN), and a brief description.
- Add an optional `category` field to `RedactRequest`. When provided, the prompt to the AI includes additional context: *"This is a complaint about: [noise / public infrastructure / …]. Structure the letter accordingly."*
- Categories are a static enum or config — no database needed.

**Why it fits:**
- Improves UX and letter quality significantly.
- Zero new dependencies. A simple enum + controller.
- Backward-compatible: omitting `category` preserves current behavior.

**Files affected:** New `ComplaintCategory.java` enum, new `CategoryController.java`, `RedactRequest`, `OpenRouterServices`.

---

### 6. 📝 Request/Response Audit Logging

**Problem:** There is no record of what citizens asked or what the AI produced. For a public-facing civic tool, this is important for:
- Debugging bad AI responses.
- Detecting misuse / abuse.
- Understanding usage patterns.

**Proposal:**
- Log a structured audit record (JSON) for every `/ask` and `/redact` request:
    - Timestamp, endpoint, request hash (not the full text — privacy), response error code, response latency, output format, language.
    - **Never log the full question or AI response** (privacy). Only metadata.
- Use the existing SLF4J/Logback pipeline → CloudWatch Logs. The access logs in API Gateway cover HTTP-level details; this covers application-level semantics.
- Introduce a small `AuditLogger` utility class that writes structured JSON log lines.

**Why it fits:**
- Operational necessity for a public service.
- No new dependencies (Logback is already there).
- Keeps privacy by design — only metadata.

**Files affected:** New `AuditLogger.java`, called from `OpenRouterController`.

---

### 7. ⏱️ Configurable Timeout

**Problem:** The timeout for the OpenRouter call is hardcoded at 30 seconds (`future.get(30, TimeUnit.SECONDS)` in the service) and 20 seconds (`HttpRequest.timeout(Duration.ofSeconds(20))` in `HttpWrapper`). These are not configurable without a code change.

**Proposal:**
- Externalize both values to `application.properties` with sensible defaults:
  ```properties
  complai.openrouter.request-timeout-seconds=20
  complai.openrouter.overall-timeout-seconds=30
  ```
- Inject via `@Value` in `HttpWrapper` and `OpenRouterServices`.
- Validate at startup that `request-timeout < overall-timeout` (fail fast).

**Why it fits:**
- Standard practice. Different environments (dev vs. prod) may need different values.
- Trivial change, prevents future "we need to redeploy to change a timeout" scenarios.

**Files affected:** `HttpWrapper`, `OpenRouterServices`, `application.properties`.

---

### 8. 🛡️ Input Sanitization / Length Limits

**Problem:** Currently, the only input validation is `null`/blank checks. There are no length limits on the `text` field. A malicious or careless user could send a 1MB complaint, which:
- Gets forwarded to OpenRouter (cost + potential rejection).
- Could cause OOM in PDF generation.
- Could exceed Lambda's 6MB response limit.

**Proposal:**
- Add a maximum input length (e.g., 5,000 characters — enough for any reasonable complaint, short enough to keep costs sane).
- Return `VALIDATION` error code with a clear message when exceeded.
- Configurable via `application.properties`: `complai.input.max-length-chars=5000`.

**Why it fits:**
- Defense in depth. Non-negotiable for a public API.
- Trivial to implement at the service boundary.

**Files affected:** `OpenRouterServices` (validation in `ask()` and `redactComplaint()`), `application.properties`.

---

### 9. 📄 PDF Styling and Branding

**Problem:** The current PDF is functional but plain — Helvetica font, no header, no footer, no branding. For a formal complaint letter to the Ajuntament, a professional appearance matters.

**Proposal:**
- Add a proper letter header: "Ajuntament d'El Prat de Llobregat" + date.
- Add sender placeholder fields: `[Nom i cognoms]`, `[Adreça]`, `[DNI/NIE]`.
- Add a footer: page number, "Generat per Gall Potablava — complai.cat".
- Use a cleaner font (embed a TTF or use a standard PDF font with better Unicode/Catalan support — `PDType1Font.HELVETICA` doesn't handle accented characters like `ç`, `ü`, `à` well).
- Extract PDF generation into its own `PdfGenerator` class (single responsibility — the service class is already 340 lines).

**Why it fits:**
- Directly improves the core value proposition.
- Extracting PDF logic reduces `OpenRouterServices` complexity.
- Unicode font support is a correctness issue — Catalan text *will* have characters that break with `PDType1Font`.

**Files affected:** New `PdfGenerator.java`, `OpenRouterServices` (delegates to it).

---

### 10. 🔁 Retry with Backoff for OpenRouter Calls

**Problem:** If OpenRouter returns a transient error (429, 503), the system immediately fails. For a serverless function that may only get one shot, a single retry with backoff could dramatically improve reliability.

**Proposal:**
- Add a simple retry (1 retry, 2-second backoff) for transient HTTP status codes (429, 502, 503) in `HttpWrapper.postToOpenRouterAsync()`.
- Configurable: `complai.openrouter.max-retries=1`.
- Log each retry attempt.
- Ensure total time stays under the Lambda timeout (30s).

**Why it fits:**
- Standard resilience pattern. OpenRouter rate limits are real.
- One retry is simple, predictable, and dramatically reduces transient failure rates.
- No new dependencies — `CompletableFuture` chain with a `thenCompose` retry.

**Files affected:** `HttpWrapper`, `application.properties`.

---

### 11. 🏛️ Prat Espais Procedure Integration (RAG)

**Problem:** Today, Gall Potablava answers questions based only on the general knowledge of the
underlying AI model. It has no access to the real, up-to-date catalogue of municipal procedures
published at [tramits.pratespais.com](https://tramits.pratespais.com/Ciutadania/). When a citizen
asks *"Com puc empadronar-me?"* or *"Quins documents necessito per demanar una llicència d'obres?"*,
the AI can only guess — it cannot cite the actual requirements, deadlines, fees, or links from
Prat Espais.

This is the single most impactful gap in the product. Without authoritative procedure data, the
assistant is a general chatbot, not a civic tool.

---

### Proposed Architecture: Retrieval-Augmented Generation (RAG)

The proven pattern for grounding an LLM in domain-specific, frequently-changing data is **RAG**
(Retrieval-Augmented Generation). Instead of fine-tuning the model or stuffing all procedures into
the system prompt, we:

1. **Ingest** — periodically scrape or export the procedure catalogue from Prat Espais.
2. **Index** — chunk the text, compute embeddings, and store them in a vector database.
3. **Retrieve** — at query time, search the vector index for the most relevant procedure chunks.
4. **Augment** — inject those chunks into the AI prompt as context, so the model answers based on
   real data.

```
                                                ┌─────────────────────────┐
                                                │   Prat Espais Website   │
                                                │ tramits.pratespais.com  │
                                                └────────────┬────────────┘
                                                             │
                                          ┌──────────────────▼──────────────────┐
                                          │  1. INGESTION (offline, scheduled)  │
                                          │     Scraper / API export            │
                                          │     → clean & chunk text            │
                                          │     → compute embeddings            │
                                          │     → store in vector DB            │
                                          └──────────────────┬──────────────────┘
                                                             │
                                                             ▼
┌───────────────┐    ┌───────────────────┐    ┌──────────────────────────────┐
│  Citizen      │    │  ComplAI Lambda    │    │  Vector Database             │
│  "Com puc     │───▶│                   │───▶│  (e.g. OpenSearch Serverless │
│  empadronar-  │    │  2. RETRIEVE      │    │   or Pinecone or pgvector)   │
│  me?"         │    │     embed query   │◀───│                              │
│               │    │     search index  │    │  Returns top-K chunks:       │
│               │    │                   │    │  "Empadronament: cal portar  │
│               │    │  3. AUGMENT       │    │   DNI, contracte de lloguer, │
│               │    │     inject chunks │    │   certificat de convivència" │
│               │    │     into prompt   │    └──────────────────────────────┘
│               │    │                   │
│               │    │  4. GENERATE      │
│               │◀───│     call OpenRouter│───▶ OpenRouter API
│               │    │     with context  │
└───────────────┘    └───────────────────┘
```

---

### Step 1: Ingestion — Getting the Procedure Data

The procedure catalogue at `tramits.pratespais.com/Ciutadania/` contains structured pages with:
- Procedure name / title
- Description and purpose
- Requirements (documents, eligibility)
- Steps to follow
- Fees / taxes
- Where to go (office, online link)
- Deadlines / processing times
- Related regulations

**Three viable approaches, from simplest to most robust:**

#### Option A: Static scraping + scheduled refresh (simplest, recommended to start)

- A standalone script (Python or Java CLI) scrapes the procedure pages, extracts the text content,
  and produces a set of JSON/Markdown documents — one per procedure.
- Run on a schedule (e.g. weekly via GitHub Actions cron, or an AWS EventBridge rule triggering
  a lightweight Lambda).
- Output is stored in S3 as the canonical procedure corpus.
- A second step reads the corpus, chunks each procedure into ~500-token passages, computes
  embeddings (using OpenRouter's embedding endpoint or a dedicated embedding model), and upserts
  them into the vector database.

**Why start here:**
- No dependency on Prat Espais exposing an API.
- Procedures change infrequently (weeks/months) — weekly refresh is more than enough.
- A simple Python `BeautifulSoup` scraper is ~100 lines. Nothing clever, easy to debug.

#### Option B: Prat Espais provides a structured API or data export

- If the Prat Espais team can export the catalogue as structured data (JSON, CSV, or an API
  endpoint), ingestion becomes trivial — no scraping, no HTML parsing, no fragility.
- **This is the preferred long-term solution.** Scraping is always brittle; a stable API is not.
- The rest of the pipeline (chunking → embedding → vector DB) remains identical.

#### Option C: Manual curation with version control

- The procedure data is maintained as Markdown files in the repository (e.g. `procedures/`).
- Each file follows a strict template: title, description, requirements, steps, fees, links.
- Embeddings are recomputed on every CI build (or on file change).
- **Trade-off:** requires manual updates, but gives total control over content quality and
  correctness. Good as a bootstrap while negotiating API access.

---

### Step 2: Chunking and Embedding

Each procedure document is split into semantically coherent chunks (~300–500 tokens each):

```
Procedure: "Empadronament"
  → Chunk 1: Title + description + purpose
  → Chunk 2: Requirements (documents needed)
  → Chunk 3: Steps to follow + where to go
  → Chunk 4: Fees + deadlines + links
```

Each chunk is embedded using a text embedding model. Options:

| Embedding model | Notes |
|-----------------|-------|
| OpenRouter embedding endpoint | Same provider as the chat model; simplest integration |
| `text-embedding-3-small` (OpenAI) | High quality, low cost, widely used |
| Local model (e.g. `all-MiniLM-L6-v2`) | Free, runs in the ingestion script; no API call needed |

Metadata stored alongside each embedding:
- `procedureId` (slug or ID from the source)
- `procedureTitle`
- `chunkIndex`
- `sourceUrl` (link back to the Prat Espais page)
- `lastUpdated` (timestamp of last scrape)

---

### Step 3: Vector Database — Where to Store Embeddings

| Option | Pros | Cons | Cost |
|--------|------|------|------|
| **Amazon OpenSearch Serverless** | AWS-native, serverless, scales to zero collection units | Minimum cost ~$350/month for a serverless collection | High for this use case |
| **Pinecone (free tier)** | Managed, generous free tier (100K vectors), simple API | External dependency, vendor lock-in | Free for small scale |
| **PostgreSQL + pgvector (RDS/Aurora)** | Standard SQL, mature, self-hosted or RDS | Requires a running DB instance | ~$15/month (db.t4g.micro) |
| **SQLite + sqlite-vss (embedded)** | Zero infrastructure, file on S3, loaded into Lambda | Limited scale, cold-start penalty loading the file | $0 (S3 storage only) |
| **JSON file on S3 + brute-force cosine similarity** | Zero infrastructure, trivially simple | Only viable for <1,000 chunks; O(n) search | $0 |

**Recommendation for ComplAI's scale:**

The Prat Espais procedure catalogue likely has **50–200 procedures**. At ~4 chunks per procedure,
that is **200–800 vectors**. This is tiny.

For this scale, the **simplest viable option** is:

> **A pre-computed JSON file on S3** containing all chunks with their embeddings. The Lambda
> downloads the file on cold start (cached in memory for warm invocations), computes the cosine
> similarity of the user's query embedding against all stored embeddings, and returns the top-K
> most relevant chunks.

This eliminates an entire infrastructure component (no vector DB), keeps the architecture
serverless and boring, and can be upgraded to a real vector DB later if the corpus grows past
~5,000 chunks.

**Pre-computed index file structure:**
```json
{
  "model": "text-embedding-3-small",
  "generatedAt": "2026-03-01T10:00:00Z",
  "chunks": [
    {
      "procedureId": "empadronament",
      "procedureTitle": "Empadronament",
      "chunkIndex": 0,
      "text": "L'empadronament és la inscripció al padró municipal...",
      "sourceUrl": "https://tramits.pratespais.com/Ciutadania/Empadronament",
      "embedding": [0.0123, -0.0456, 0.0789]
    }
  ]
}
```

---

### Step 4: Query-Time Retrieval and Prompt Augmentation

When a citizen asks a question, the service:

1. **Embeds the query** — calls the embedding model to convert the user's question into a vector.
2. **Searches the index** — computes cosine similarity against all stored chunks, selects the
   top 3–5 most relevant ones.
3. **Augments the prompt** — injects the retrieved chunks into the system message or as a
   dedicated `context` block before the user message.

**Modified message array:**
```json
[
  { "role": "system", "content": "<existing system prompt>" },
  { "role": "system", "content": "CONTEXT FROM PRAT ESPAIS PROCEDURES:\n\n---\nProcedure: Empadronament\nSource: https://tramits.pratespais.com/...\n\nL'empadronament és la inscripció al padró municipal. Cal portar DNI, contracte de lloguer...\n---\n\nUse this context to answer the user's question. If the context is relevant, cite the procedure name and provide the source link. If the context does not help, answer based on your general knowledge about El Prat." },
  { "role": "user", "content": "Com puc empadronar-me?" }
]
```

**Key design rules for the augmented prompt:**
- Always instruct the model to **prefer the retrieved context** over its own knowledge.
- Always include the **source URL** so the citizen can go to the official page.
- Always tell the model: *"If the context does not answer the question, say so honestly rather
  than inventing an answer."*
- Never stuff more than ~2,000 tokens of context — keep the prompt lean, costs low, and
  latency predictable.

---

### Impact on Existing Code

| Component | Change |
|-----------|--------|
| **New: `ProcedureIndexLoader`** | Singleton. On first call (or cold start), downloads the pre-computed index JSON from S3. Caches in memory. |
| **New: `ProcedureRetriever`** | Singleton. Given a query string, embeds it, runs cosine similarity against the index, returns top-K chunks. |
| **New: `EmbeddingClient`** | Singleton. Calls the embedding API (OpenRouter or OpenAI) to embed a query string. |
| **`OpenRouterServices`** | `ask()` and `redactComplaint()` call `ProcedureRetriever` before building the messages array. Inject retrieved chunks as a context system message. |
| **`IOpenRouterService`** | No interface change — retrieval is an internal concern of the service, not exposed to the controller. |
| **`OpenRouterController`** | No change. |
| **Infrastructure (CDK)** | Lambda needs `s3:GetObject` permission for the index bucket. New S3 bucket for the index file. |
| **New: ingestion script** | Standalone CLI (Python or Java) that scrapes, chunks, embeds, and writes the index JSON to S3. |

---

### What the Citizen Experience Looks Like After Integration

**Before (today):**
```
Citizen: "Quins documents necessito per empadronar-me?"
AI:      "Generalment necessites el DNI i un justificant de domicili..."
         (generic, possibly inaccurate, no source)
```

**After (with RAG):**
```
Citizen: "Quins documents necessito per empadronar-me?"
AI:      "Per empadronar-te a El Prat de Llobregat necessites:
          1. DNI, NIE o passaport original
          2. Contracte de lloguer o escriptura de propietat
          3. Certificat de convivència (si escau)

          Pots fer el tràmit presencialment a l'Oficina d'Atenció
          Ciutadana o en línia a Prat Espais.

          Més informació: https://tramits.pratespais.com/Ciutadania/Empadronament"
```

---

### Implementation Phases

| Phase | What | Effort | Dependency |
|-------|------|--------|------------|
| **Phase 1: Manual corpus** | Curate 10–20 key procedures as Markdown files in the repo. Embed them locally. Load as a JSON file bundled in the JAR (no S3 yet). Wire into `ask()`. | 🟢 Small (1–2 days) | None |
| **Phase 2: S3 index + scraper** | Build the scraper for `tramits.pratespais.com`. Automate embedding. Store index JSON on S3. Lambda loads from S3 on cold start. | 🟡 Medium (3–5 days) | S3 bucket, embedding API key |
| **Phase 3: Scheduled refresh** | GitHub Actions cron or EventBridge rule triggers the scraper weekly. Index is regenerated and uploaded to S3 automatically. | 🟢 Small (1 day) | Phase 2 |
| **Phase 4: API-based ingestion** | If/when Prat Espais provides a structured API, replace the scraper with an API client. Everything else stays the same. | 🟢 Small (swap) | Prat Espais API |

**Recommendation:** Start with **Phase 1**. It proves the entire RAG pipeline end-to-end with
zero infrastructure, and citizens immediately get better answers for the most common procedures.
Phase 2 follows naturally once the concept is validated.

---

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Scraping breaks when Prat Espais redesigns their site | Phase 4 (API) eliminates this. Until then, the scraper is monitored and the weekly job alerts on failure. |
| Stale data (procedure changes between scrapes) | Weekly refresh is sufficient for municipal procedures. Critical changes can trigger a manual re-scrape. |
| Embedding API costs | At 200–800 chunks, embedding cost is negligible (<$0.01/run with `text-embedding-3-small`). Query-time embedding is one call per user question (~$0.00001). |
| Irrelevant context injected into prompt | Cosine similarity threshold (e.g. >0.75) filters out low-relevance chunks. If nothing passes the threshold, no context is injected — the AI answers from general knowledge. |
| Index file too large for Lambda memory | At 800 chunks × ~1,536 floats × 4 bytes ≈ ~5 MB. Well within Lambda's 512 MB. |
| Privacy: user queries sent to embedding API | The query is just the citizen's question text — same text already sent to OpenRouter. No additional privacy exposure. |

---

### Priority Recommendation

| Priority | Feature | Effort | Impact |
|----------|---------|--------|--------|
| **P0** | #8 Input Length Limits | 🟢 Small | 🔴 Security/Cost |
| **P0** | #7 Configurable Timeout | 🟢 Small | 🟡 Operability |
| **P1** | #11 Prat Espais Procedure Integration (RAG) — Phase 1 | 🟡 Medium | 🔴 Core Value Proposition |
| **P1** | #9 PDF Extraction + Unicode Fix | 🟡 Medium | 🔴 Correctness (Catalan chars) |
| **P1** | #2 Rate Limiting (CDK) | 🟢 Small | 🔴 Security/Cost |
| **P1** | #3 Health Check | 🟢 Small | 🟡 Operability |
| **P2** | #11 Prat Espais Procedure Integration (RAG) — Phase 2–3 | 🟡 Medium | 🔴 Core Value Proposition |
| **P2** | #4 Language Selection | 🟢 Small | 🟡 UX |
| **P2** | #6 Audit Logging | 🟡 Medium | 🟡 Operability |
| **P2** | #10 Retry with Backoff | 🟡 Medium | 🟡 Reliability |
| **P3** | #5 Complaint Categories | 🟡 Medium | 🟡 UX |
| **P3** | #1 Conversation History | 🟡 Medium | 🟡 UX |

**Start with P0** — they're small, non-controversial, and fix real gaps. Then tackle **#11 Phase 1**
(manual corpus RAG) — this is the feature that transforms ComplAI from a generic chatbot into a
real civic tool. Phase 1 requires no infrastructure and can be shipped in 1–2 days. Continue with
P1 for the PDF Unicode fix and rate limiting.

---

*Each proposal is designed to be implemented independently, with no cross-dependencies, following the existing architecture and patterns.*
