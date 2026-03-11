# Async Complaint Letter Generation — Implementation Plan

## Implementation Status

| Phase | Description | Status |
|-------|-------------|--------|
| **1** | Infrastructure (CDK): S3, SQS, Worker Lambda, IAM | 🔴 Pending |
| **2** | Java Application Changes (DTOs, Publisher, Handler, Controller) | 🔴 Pending |
| **3** | Gradle Dependencies (SQS SDK) | 🔴 Pending |
| **4** | SAM Local Development | 🔴 Pending |
| **5** | Testing (Unit, Integration, E2E) | 🔴 Pending |
| **6** | Documentation Update | 🔴 Pending |

## Problem Statement

Today, when a user calls `POST /complai/redact` with complete identity data, the request **blocks synchronously** while:

1. The AI generates the letter text (~5–30 seconds).
2. PDFBox renders the letter into a PDF in-memory.
3. The PDF bytes are returned inline in the HTTP response.

This means the user waits for the full AI + PDF pipeline before getting any response. If the AI is slow or the Lambda is approaching its 60-second timeout, the experience degrades.

## Proposed Behaviour

**When identity is complete and the request is valid**, instead of generating the PDF synchronously:

1. The API Lambda validates the request (format, identity, anonymity, input length).
2. It publishes a message to an SQS queue containing all the data needed to generate the letter.
3. It responds **immediately** with `202 Accepted` and a JSON body containing:
   - `success: true`
   - `message`: A human-readable message (e.g., *"Your complaint letter is being created. It will be available shortly."*)
   - `pdfUrl`: The **pre-determined S3 URL** where the PDF will be uploaded once ready.

A **separate worker Lambda**, triggered by SQS, picks up the message, calls the AI, generates the PDF, and uploads it to an S3 bucket. The user can poll or check the link.

**When identity is incomplete** (the AI needs to ask for missing fields), the existing synchronous multi-turn flow stays unchanged — there is no PDF to generate yet, so there is nothing to offload.

---

## Architecture Overview

```
                                ┌──────────────────────┐
    POST /complai/redact  ────▸ │  API Lambda           │
    (identity complete)         │  (existing)           │
                                │                       │
                                │  1. Validate request  │
                                │  2. Send SQS message  │
                                │  3. Return 202 + URL  │
                                └───────────┬──────────┘
                                            │
                                            ▼
                                ┌──────────────────────┐
                                │  SQS Queue            │
                                │  complai-redact-*     │
                                └───────────┬──────────┘
                                            │
                                            ▼
                                ┌──────────────────────┐
                                │  Worker Lambda        │
                                │  (new)                │
                                │                       │
                                │  1. Call AI (prompt)   │
                                │  2. Parse AI response  │
                                │  3. Generate PDF      │
                                │  4. Upload to S3      │
                                └───────────┬──────────┘
                                            │
                                            ▼
                                ┌──────────────────────┐
                                │  S3 Bucket            │
                                │  complai-complaints-* │
                                │  (PDF files)          │
                                └──────────────────────┘
```

---

## Detailed Implementation Steps

### Phase 1: Infrastructure (CDK)

#### 1.1 — New S3 Bucket for Generated PDFs

**File:** `cdk/lambda-stack.ts`

- Create a new S3 bucket: `complai-complaints-<environment>`.
- Block all public access (same as the procedures bucket).
- Enable S3-managed encryption.
- Set a lifecycle rule: delete PDFs after **30 days** (complaints are ephemeral; the user should download them promptly).
- `removalPolicy`: `DESTROY` for development, `RETAIN` for production.

#### 1.2 — New SQS Queue

**File:** `cdk/lambda-stack.ts`

- Create a standard SQS queue: `complai-redact-<environment>`.
- Visibility timeout: **90 seconds** (must be ≥ 1.5× the worker Lambda timeout of 60s, per AWS best practices).
- Message retention: **4 hours** (short-lived; if a message isn't processed in 4h, the request is stale).
- Create a Dead Letter Queue (DLQ): `complai-redact-dlq-<environment>` with `maxReceiveCount: 3`. Failed messages land here for investigation.
- `removalPolicy`: `DESTROY` for development, `RETAIN` for production.

#### 1.3 — New Worker Lambda

**File:** `cdk/lambda-stack.ts`

- Create a new Lambda function: `ComplAIRedactorLambda-<environment>`.
- Same runtime (`JAVA_21`), same shadow JAR as the API Lambda.
- **Handler:** A new Micronaut-based SQS handler class (see Phase 2).
- Memory: **768 MB** (same as API Lambda — AI call + PDF generation have similar requirements).
- Timeout: **60 seconds** (matches the AI call timeout; the queue visibility timeout covers retries).
- Environment variables: same `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, `OPENROUTER_REQUEST_TIMEOUT_SECONDS`, `OPENROUTER_OVERALL_TIMEOUT_SECONDS`, plus:
  - `COMPLAINTS_BUCKET`: the complaints S3 bucket name.
  - `COMPLAINTS_REGION`: the stack region.
- Log group: new dedicated log group `/aws/lambda/ComplAIRedactorLambda-<environment>`.

#### 1.4 — IAM Permissions

- **API Lambda role:** Add `sqs:SendMessage` permission on the redact queue.
- **Worker Lambda role:** Add:
  - `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:GetQueueAttributes` on the redact queue.
  - `s3:PutObject` on the complaints bucket.
  - `AWSLambdaBasicExecutionRole` (managed policy).
- The worker Lambda must **not** have permissions it doesn't need (no S3 read on procedures bucket unless it needs RAG context — see open question).

#### 1.5 — SQS Event Source Mapping

- Wire the SQS queue as an event source for the worker Lambda.
- Batch size: **1** (each complaint is independent and takes ~30s; batching adds complexity for no gain).
- Report batch item failures: `true` (so partial batch failures don't re-process successful items).

#### 1.6 — Environment Variables for API Lambda

- Add `REDACT_QUEUE_URL` environment variable to the API Lambda, pointing to the SQS queue URL.
- Add `COMPLAINTS_BUCKET` environment variable to the API Lambda (needed to construct the pre-signed/public S3 URL in the response).

---

### Phase 2: Java Application Changes

#### 2.1 — New SQS Message DTO

**New file:** `src/main/java/cat/complai/sqs/dto/RedactSqsMessage.java`

A record containing all the data the worker needs:

```java
public record RedactSqsMessage(
    String complaintText,
    String requesterName,
    String requesterSurname,
    String requesterIdNumber,
    String s3Key,              // pre-determined S3 key for the PDF
    String conversationId      // optional, for conversation history context
) {}
```

Why a dedicated DTO: the SQS message schema is the contract between the API Lambda and the worker Lambda. It must be explicit, versioned, and decoupled from the HTTP request DTOs.

#### 2.2 — SQS Publisher (API Lambda side)

**New file:** `src/main/java/cat/complai/sqs/SqsComplaintPublisher.java`

- A `@Singleton` service that wraps the AWS SDK `SqsClient`.
- Constructor-injected with the queue URL (from `@Value("${REDACT_QUEUE_URL}")`).
- Single method: `publish(RedactSqsMessage message)` → serializes to JSON and calls `sqsClient.sendMessage(...)`.
- Returns `true`/`false` (or throws) to indicate publish success/failure.
- The API Lambda catches publish failures and returns `500 INTERNAL` — the user knows the request was not queued.

#### 2.3 — S3 Key Generation

**Where:** Inside the controller or a small helper.

The S3 key must be deterministic and unique. Suggested format:

```
complaints/<conversationId-or-uuid>/<timestamp>-complaint.pdf
```

- If `conversationId` is provided, use it as the folder prefix. This groups multi-turn complaints together.
- If not provided, generate a UUID.
- Include a timestamp prefix for chronological ordering within a conversation.

The full S3 URL returned to the user:

```
https://complai-complaints-<environment>.s3.eu-west-1.amazonaws.com/complaints/<id>/<ts>-complaint.pdf
```

> **Open Question:** Should this URL be a pre-signed URL (temporary, secure, ~1h expiry) or a permanent object URL that requires the bucket to allow specific read access? A pre-signed URL is safer (no public bucket policy), but it expires. See **Open Questions** section.

#### 2.4 — Modify `OpenRouterController.redact()`

**File:** `src/main/java/cat/complai/openrouter/controllers/OpenRouterController.java`

When identity is complete and format is PDF (or AUTO promoting to PDF):

1. Run the same validation logic (format, input length, anonymity).
2. **Do not call `service.redactComplaint()`.**
3. Generate the S3 key.
4. Build and publish the `RedactSqsMessage`.
5. Return `HttpResponse.status(HttpStatus.ACCEPTED)` with a new response DTO containing `success`, `message`, and `pdfUrl`.
6. If SQS publish fails, return `500 INTERNAL`.

When identity is **incomplete**, or format is `JSON`: keep the existing synchronous flow. JSON callers presumably want the text inline, not a file on S3.

#### 2.5 — New Response DTO for Accepted (202)

**New file:** `src/main/java/cat/complai/openrouter/dto/RedactAcceptedDto.java`

```java
@Introspected
public record RedactAcceptedDto(
    boolean success,
    String message,
    String pdfUrl,
    int errorCode
) {}
```

#### 2.6 — Worker Lambda Handler

**New file:** `src/main/java/cat/complai/worker/RedactWorkerHandler.java`

This is the SQS-triggered Lambda. Options:

- **Option A (Recommended):** Use `micronaut-function-aws` with a custom `RequestHandler<SQSEvent, SQSBatchResponse>`. This gives full access to the Micronaut DI context (HttpWrapper, PdfGenerator, etc.) without needing the full HTTP API proxy runtime.
- **Option B:** Use a plain AWS Lambda handler without Micronaut. Simpler cold start but loses DI.

**Recommended: Option A.**

The handler:

1. Deserializes the SQS message body into `RedactSqsMessage`.
2. Builds the AI prompt using the same `buildRedactPromptWithIdentity()` logic (extracted into a shared helper or called via the existing service).
3. Calls `HttpWrapper.postToOpenRouterAsync()` and waits for the response.
4. Parses the AI response (format header, letter body).
5. Generates the PDF using `PdfGenerator.generatePdf()`.
6. Uploads the PDF to S3 at the pre-determined key.
7. Returns success to SQS (message is deleted from the queue).

On failure:

- If the AI call fails (timeout, upstream error), the message remains in the queue and is retried (up to 3 times).
- After 3 failures, the message goes to the DLQ.
- The user's pre-determined URL will simply return a 404/403 if the PDF was never generated. The client should handle this gracefully (poll with retry, show "still processing" or "generation failed").

#### 2.7 — Extract Shared Prompt-Building Logic

**Refactoring:** The prompt-building methods in `OpenRouterServices` (`buildRedactPromptWithIdentity`, `getSystemMessage`, `buildProcedureContextBlock`) are currently private. The worker Lambda needs the same prompt logic.

Two clean approaches:

1. **Extract a `RedactPromptBuilder` helper class** — a stateless utility or `@Singleton` that both `OpenRouterServices` and `RedactWorkerHandler` can use. This avoids duplicating prompt templates.
2. **Expose the methods package-private** and keep them in `OpenRouterServices`, then inject `OpenRouterServices` into the worker handler. This is simpler but couples the worker to the full service class.

**Recommended: Option 1** — a dedicated `RedactPromptBuilder` class. Clean separation, testable in isolation.

#### 2.8 — S3 Upload Utility

**New file:** `src/main/java/cat/complai/s3/S3PdfUploader.java`

- A `@Singleton` wrapping `S3Client`.
- Single method: `upload(String bucketName, String key, byte[] pdfBytes)`.
- Sets `Content-Type: application/pdf` and `Content-Disposition: inline; filename="complaint.pdf"`.
- Throws on failure (the SQS handler catches and lets the message retry).

---

### Phase 3: Gradle Dependencies

**File:** `build.gradle`

- Add `software.amazon.awssdk:sqs:2.25.18` (matching the existing S3 SDK version).
- The S3 SDK is already present.

---

### Phase 4: SAM Local Development

**File:** `sam/template.yaml`

- Add the SQS queue resource.
- Add the worker Lambda resource.
- Wire the SQS event source.
- Update `docker-compose.yml` to create the SQS queue in LocalStack.

---

### Phase 5: Testing

#### 5.1 — Unit Tests

- **`SqsComplaintPublisherTest`**: Verify message serialization, error handling when SQS is unavailable.
- **`RedactWorkerHandlerTest`**: Provide a fake `HttpWrapper`, verify the full flow: deserialize → prompt → AI call → PDF → S3 upload. Test failure scenarios (AI refusal, timeout, empty body).
- **`RedactPromptBuilderTest`**: Verify prompt content with various identity combinations (already partially covered by `OpenRouterServicesTest`, move relevant tests).

#### 5.2 — Controller Tests

- Update `OpenRouterControllerTest` to verify:
  - `202 Accepted` response when identity is complete and format is PDF.
  - Response contains `pdfUrl` pointing to the correct S3 key.
  - `500` when SQS publish fails.
  - Synchronous flow unchanged for JSON format or incomplete identity.

#### 5.3 — Integration Tests

- Update `OpenRouterControllerIntegrationTest` with a mock `SqsComplaintPublisher`.

#### 5.4 — E2E (Bruno)

- Add new test cases:
  - `POST /complai/redact` with full identity + format `pdf` → expect `202` with `pdfUrl`.
  - Poll the `pdfUrl` and verify eventual `200` with `application/pdf` content.
- Update existing PDF tests that now expect `202` instead of `200`.

---

### Phase 6: Update AGENTS.md and Documentation

- Document the new async flow in the architecture section.
- Add the new SQS queue and worker Lambda to the project structure.
- Add the new environment variables.
- Update the API endpoint table with the `202` response.
- Add the new error scenarios (SQS publish failure).

---

## New API Response (Async Redact)

### `202 Accepted` — PDF Generation Queued

```json
{
  "success": true,
  "message": "Your complaint letter is being created. It will be available shortly at the URL below.",
  "pdfUrl": "https://complai-complaints-development.s3.eu-west-1.amazonaws.com/complaints/abc-123/1741689600-complaint.pdf",
  "errorCode": 0
}
```

### Unchanged Responses

| Scenario | Status | Notes |
|----------|--------|-------|
| Identity incomplete | `200` JSON | AI asks for missing fields (sync, unchanged) |
| Format = `json` | `200` JSON | Letter text returned inline (sync, unchanged) |
| Format = unsupported | `400` JSON | Validation error (sync, unchanged) |
| Empty/too long input | `400` JSON | Validation error (sync, unchanged) |
| Anonymous request | `400` JSON | Validation error (sync, unchanged) |
| SQS publish failure | `500` JSON | New: internal error |

---

## Open Questions

### 1. PDF URL: Pre-Signed vs. Public

**Pre-signed URL (recommended):**
- Secure by default; bucket stays fully private.
- URL expires (typically 1h–24h). The user must download the PDF before expiry.
- The API Lambda generates the pre-signed URL at request time using the S3 SDK.
- Downside: if the worker takes longer than expected, the URL might expire before the PDF exists. Mitigation: use a long expiry (e.g., 24h).

**CloudFront or public bucket policy:**
- Permanent URL, but requires careful access control.
- Adds CloudFront cost/complexity.

**Recommendation:** Start with pre-signed URLs (24h expiry). If users report issues, add a `GET /complai/complaint/<id>` endpoint that generates a fresh pre-signed URL on demand.

### 2. User Notification When PDF Is Ready

The initial implementation uses **polling**: the client checks the `pdfUrl` periodically. The URL returns `403/404` while the PDF is not yet uploaded, and `200` once it is.

Future enhancements (not in scope for v1):
- **WebSocket push** — notify the client when the PDF is ready.
- **Email notification** — if the user provides an email, send the PDF link via SES.

### 3. Should the Worker Lambda Reuse the Existing Shadow JAR?

**Yes.** Both Lambdas share the same codebase (same `complai-all.jar`). Only the handler class differs:
- API Lambda: `io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction::handleRequest`
- Worker Lambda: `cat.complai.worker.RedactWorkerHandler::handleRequest`

This avoids maintaining two separate builds. The JAR is ~30 MB; the unused classes are negligible.

### 4. Procedure RAG Context in the Worker

The existing prompt injects procedure context from `ProcedureRagHelper`. This helper loads `procedures.json` from S3 at startup. The worker Lambda needs the same context. Two options:

1. Grant the worker Lambda read access to the procedures bucket and reuse `ProcedureRagHelper` as-is.
2. Skip RAG context in the worker (the complaint text already describes the issue).

**Recommendation:** Grant read access and reuse. The AI produces better letters with context.

### 5. Conversation History in the Worker

The existing flow uses Caffeine cache (in-memory) for conversation history. The worker Lambda runs in a **separate process** and cannot access the API Lambda's cache.

For v1, the worker does **not** need conversation history. The SQS message contains the full complaint text and identity — everything the AI needs. The conversation history is only useful during the interactive multi-turn phase (identity collection), which stays synchronous.

If future requirements demand it, conversation history could be stored in DynamoDB.

---

## Migration & Backwards Compatibility

- **Breaking change for PDF clients:** Clients currently expecting a `200` with `application/pdf` body from `/complai/redact` will receive a `202` with a JSON body containing a URL instead. This is a **contract change**.
- **Non-breaking for JSON clients:** Clients using `format: "json"` see no change.
- **Mitigation:** Version the API (e.g., `/v2/complai/redact` for the async flow, keep `/complai/redact` synchronous) OR communicate the change to frontend consumers and update simultaneously.

**Recommendation:** Since this project has a tightly coupled frontend (or Bruno tests), update both simultaneously and document the change. No need for API versioning at this scale.

---

## Estimated Work Breakdown

| Task | Effort |
|------|--------|
| CDK: S3 bucket + SQS queue + DLQ | Small |
| CDK: Worker Lambda + IAM + event source | Medium |
| CDK: API Lambda env vars + SQS permissions | Small |
| Gradle: Add SQS SDK dependency | Trivial |
| Java: `RedactSqsMessage` DTO | Small |
| Java: `SqsComplaintPublisher` | Small |
| Java: `S3PdfUploader` | Small |
| Java: `RedactPromptBuilder` extraction | Medium |
| Java: `RedactWorkerHandler` | Medium |
| Java: Controller changes | Medium |
| Java: `RedactAcceptedDto` | Small |
| Tests: Unit tests for new components | Medium |
| Tests: Update controller/integration tests | Medium |
| Tests: Update Bruno E2E collection | Medium |
| SAM: Local development support | Small |
| Docs: Update AGENTS.md | Small |
| **Total** | **~3–5 days** |

---

## Summary

This plan introduces **SQS-based async PDF generation** while preserving the existing synchronous flow for:
- Identity collection (multi-turn Q&A with the AI).
- JSON-format responses (text inline).

The key architectural additions are:
1. An **SQS queue** as the decoupling boundary between request acceptance and PDF generation.
2. A **worker Lambda** that processes the queue, calls the AI, and uploads PDFs to S3.
3. An **S3 bucket** for generated complaint PDFs with a 30-day lifecycle.
4. A **202 Accepted** response with a pre-determined PDF URL so the user knows where to find their letter.

No new abstractions are introduced without purpose. The worker reuses the same shadow JAR, the same AI prompt logic, and the same PDF generator. The added complexity is justified by the user-facing latency improvement and the decoupling of the HTTP request lifecycle from the AI call duration.
