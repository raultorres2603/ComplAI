# ComplAI Developer Guide for AI Agents

This guide provides essential context for AI agents working on the ComplAI codebase, a serverless backend for the "Gall Potablava" citizen assistant.

## 1. Architecture Overview

ComplAI is a **Java 21 Micronaut application** designed to run as **AWS Lambda functions** (API Lambda + Worker Lambda). It serves as the intelligent backend for a chatbot integrated into the "Prat Espais" platform.

### Core Components
- **Entry Point:** `cat.complai.App` bootstraps the Micronaut application.
- **Controller Layer:** `cat.complai.openrouter.controllers.OpenRouterController` handles HTTP requests (`POST /complai/ask` and `POST /complai/redact`).
- **Service Layer:** `cat.complai.openrouter.services.OpenRouterServices` orchestrates the business logic:
    - Managing conversation history (cached in Caffeine).
    - Retrieving context via RAG (`ProcedureRagHelper`).
    - Communicating with the LLM via OpenRouter (`HttpWrapper`).
    - Always returns text — never produces PDF bytes. PDF generation is exclusively the worker Lambda's responsibility.
- **Async Complaint Flow:**
    - `cat.complai.sqs.SqsComplaintPublisher` — publishes `RedactSqsMessage` to SQS when a complete identity is provided with a PDF request. The controller returns `202 Accepted` immediately with a pre-signed `pdfUrl`.
    - `cat.complai.worker.RedactWorkerHandler` — SQS-triggered Lambda handler. Deserialises the message, calls the AI via `ComplaintLetterGenerator`, generates the PDF with `PdfGenerator`, and uploads it to S3 via `S3PdfUploader`.
    - `cat.complai.worker.ComplaintLetterGenerator` — orchestrates the AI call and PDF render for the worker. Uses `RedactPromptBuilder` for the prompt and `PdfGenerator` for the PDF bytes.
    - `cat.complai.openrouter.helpers.RedactPromptBuilder` — stateless helper that builds the AI prompt for complaint letter generation. Used by both the synchronous service path and the worker Lambda.
    - `cat.complai.s3.S3PdfUploader` — wraps `S3Client`. Uploads PDFs and generates pre-signed GET URLs (24h expiry).
- **Data Access (RAG):** `ProcedureRagHelper` uses **Apache Lucene** in-memory to index and search municipal procedures. Loads `procedures-<cityId>.json` from S3 when `PROCEDURES_BUCKET`/`PROCEDURES_REGION` env vars are set; the S3 key is always derived from the city as `procedures-<cityId>.json` (no `PROCEDURES_KEY` env var needed). Falls back to the classpath resource (`procedures-elprat.json`). The city is read from the caller's JWT `city` claim (set by `JwtAuthFilter` as the `city` request attribute) and threaded through the service layer to `ProcedureRagHelperRegistry.getForCity(cityId)`, which builds and caches one Lucene index per city per warm Lambda instance.
- **HTTP / Health:**
    - `cat.complai.home.HomeController` — `GET /` returns a `HomeDto` welcome response. No JWT required.
    - `cat.complai.home.HealthController` — `GET /health` returns a `HealthDto` with `status`, `version`, and `checks` (e.g. `openRouterApiKeyConfigured`). No JWT required.
- **HTTP Client:** `cat.complai.http.HttpWrapper` — `@Singleton` wrapper around Java's `HttpClient`. Calls OpenRouter. Holds the API key and model config. Has a protected no-arg constructor so tests can subclass it without DI.
- **Service Interface:** `cat.complai.openrouter.interfaces.IOpenRouterService` — the controller depends on this interface, not `OpenRouterServices` directly. Declares `ask`, `validateRedactInput`, and `redactComplaint`.
- **AI Response Parsing:** `cat.complai.openrouter.helpers.AiParsed` — `record` that parses AI reply format headers (3 shapes: clean first-line JSON, markdown-fenced JSON, inline `body` key). Used by `OpenRouterServices`.
- **Audit Logging:** `cat.complai.openrouter.helpers.AuditLogger` — writes one structured JSON log line per request (`ts`, `endpoint`, `requestHash`, `errorCode`, `latencyMs`, `outputFormat`, `language`). Never logs raw text or AI responses. CloudWatch metric filters in CDK/SAM parse this format.
- **Language Detection:** `cat.complai.openrouter.helpers.LanguageDetector` — stateless heuristic that returns `"CA"`, `"ES"`, or `"EN"` using signal-counting (Catalan markers checked first; `ñ` and Spanish stop-words second; English is the fallback). Call via `LanguageDetector.detect(text)`. Used by `OpenRouterController` to populate the `language` audit log field and to localise the `202 Accepted` response `message`. Default for null/blank input is `"CA"` (El Prat residents are predominantly Catalan speakers).
- **Operator Tools (not deployed endpoints):**
    - `cat.complai.auth.TokenGenerator` — CLI that mints HS256 JWT tokens. Run with `java -cp complai-all.jar cat.complai.auth.TokenGenerator <subject> <expiry-days> <city>`. Requires `JWT_SECRET` env var.
    - `cat.complai.scrapper.ProcedureScraper` — CLI that crawls a city's procedure website and uploads `procedures-<cityId>.json` to S3. City-specific crawl behaviour (base URL, CSS selectors, skip rules) is driven by `src/main/resources/scrapers/procedures-mapping-<cityId>.json`. Requires `PROCEDURES_BUCKET` env var. Trigger via the `scrape-upload-procedures.yml` GitHub Actions workflow or run standalone. Not part of the Lambda boot path.
    - `cat.complai.scrapper.ProcedureIndexLoader` — downloads `procedures-<cityId>.json` from S3 to a temp file. **No longer invoked in the Lambda boot path** — `ProcedureRagHelper` now handles S3 loading internally via `getProceduresInputStream()`. Retained as a standalone utility.
- **Infrastructure:** AWS CDK (`cdk/`) defines the infrastructure in three stacks. AWS SAM (`sam/`) is used for local emulation.

### Key Data Flows

#### 1. User Query (`/complai/ask`)
`Prat Espais` widget → `POST /complai/ask` (JSON) → `OpenRouterServices` → RAG context + conversation history → OpenRouter API → `200 OK` JSON.

#### 2. Complaint Redact — Synchronous Path
Used when identity is **incomplete** (AI must ask for missing fields) or format is `json`. Always returns text — never produces PDF bytes.

`POST /complai/redact` → controller validates → `OpenRouterServices.redactComplaint()` → AI prompt → `200 OK` (JSON text or `application/pdf` bytes inline).

#### 3. Complaint Redact — Async Path
Used when identity is **complete** (all three fields present) and format is `pdf` or `auto`.

```
POST /complai/redact (complete identity + PDF format)
  └─▶ controller validates
  └─▶ SqsComplaintPublisher.publish(RedactSqsMessage)
  └─▶ 202 Accepted { success, message, pdfUrl }

SQS queue (complai-redact-<env>)
  └─▶ RedactWorkerHandler
        ├─ ComplaintLetterGenerator (AI call + PDF bytes)
        └─ S3PdfUploader.upload(key, pdfBytes)
              └─▶ s3://complai-complaints-<env>/<key>
```

The `pdfUrl` in the `202` response is a pre-signed S3 GET URL (24h expiry) generated at request time. The URL returns `403/404` while the worker is still running, and `200 application/pdf` once the PDF is uploaded. Clients should poll with a reasonable backoff.

## 2. Developer Workflows

### Build & Test
- **Build Fat JAR:** `./gradlew clean shadowJar` (Creates `build/libs/complai-all.jar`).
- **Run Unit Tests:** `./gradlew test`.
- **Run CI Tests:** `./gradlew ciTest` — explicit CI task with detailed logging; fails the build on any test failure.
- **Local Execution (SAM):**
    - Ensure Docker is running.
    - Run `./sam/start-local.sh` (Builds shadow JAR, starts LocalStack via `docker compose`, starts the SAM API on port 3000, and launches `sam/sqs_worker_poller.py` in the background to poll the local SQS queue and invoke `ComplAIRedactorFunction` via `sam local invoke`).
    - LocalStack (started by `docker compose up -d` in `sam/`) provides S3 and SQS locally.
    - Test via `curl` or Bruno (see below).
- **E2E Testing:** Use **Bruno** locally. Collection located in `E2E-ComplAI/`.
    - Key requests: `E2E-ComplAI/02-OK/Ask to ComplAI.bru`, `E2E-ComplAI/02-OK/PDF - Redact a complaint.bru`.
- **Mint a JWT token (local/CI):**
  ```bash
  JWT_SECRET=$(openssl rand -base64 32) \
  java -cp build/libs/complai-all.jar cat.complai.auth.TokenGenerator citizen-app 30 elprat
  ```
  The third argument is the city identifier — it becomes the `city` claim in the token. The `city` argument is required; omitting it exits with an error. `JwtValidator` rejects tokens that lack the `city` claim with `401`.
- **Refresh procedures for a city:**
  ```bash
  PROCEDURES_BUCKET=complai-procedures-development \
  java -cp build/libs/complai-all.jar cat.complai.scrapper.ProcedureScraper elprat
  ```
  This crawls the city's website using `scrapers/procedures-mapping-elprat.json`, writes `procedures-elprat.json`, and uploads it to S3.
  Alternatively, trigger the `scrape-upload-procedures.yml` GitHub Actions workflow manually (Actions tab → "Scrape and Upload Procedures") with the desired `city` and `environment` inputs.

### Deployment
- **Infrastructure Code:** TypeScript CDK in `cdk/`.
- **Deploy all stacks for an environment:**
  ```bash
  cdk deploy 'ComplAI*Stack-development'   # or -production
  ```
  Stacks deployed per environment (requires AWS credentials):
    - `ComplAIStorageStack-<env>` — S3 buckets
    - `ComplAIQueueStack-<env>` — SQS redact queue + DLQ
    - `ComplAILambdaStack-<env>` — both Lambda functions, IAM roles, log groups
- **CI/CD:** `.github/workflows/deploy.yml` — triggered automatically on PRs (deploys to `development`) and manually via `workflow_dispatch` (choose environment and branch). Production deploys require master branch; a guard job enforces this. Uses GitHub Environments for secret isolation.
- **Automatic releases:** `.github/workflows/release.yml` — on every push to master, reads `version` from `build.gradle`, compares to the latest `vX.Y.Z` tag, and creates a GitHub Release + tag when the version is new and greater. No manual step required.

## 3. Project Conventions & Patterns

- **Framework:** **Micronaut 4.x**. Heavy use of `@Singleton`, `@Controller`, `@Inject`.
- **Language:** **Java 21**. Use `record` for DTOs and immutable data carriers.
- **DTOs:** Located in `*.dto` packages. Use strict typing. Request DTOs for the controller (`AskRequest`, `RedactRequest`) live in `openrouter.controllers.dto`; shared domain DTOs (`OutputFormat`, `ComplainantIdentity`, `OpenRouterErrorCode`) live in `openrouter.dto`.
- **Service Interface:** Always depend on `IOpenRouterService`, never on `OpenRouterServices` directly. The interface declares `ask`, `validateRedactInput`, and `redactComplaint`.
- **Error Handling:** Use `OpenRouterErrorCode` enum to map specific error conditions to standardised error codes and HTTP statuses. Codes: `NONE(0)`, `VALIDATION(1)`, `REFUSAL(2)`, `UPSTREAM(3)`, `TIMEOUT(4)`, `INTERNAL(5)`, `UNAUTHORIZED(6)`. `UNAUTHORIZED` is emitted by `JwtAuthFilter` before the controller is reached — the controller switch does not need a case for it.
- **Audit Logging:** Every `/ask` and `/redact` request must call `AuditLogger.log(...)` exactly once. The logger writes a single JSON line with `ts`, `endpoint`, `requestHash`, `errorCode`, `latencyMs`, `outputFormat`, `language`. **Never log raw input text, AI responses, or any PII** — use `AuditLogger.hashText(text)` for the `requestHash` field. CloudWatch metric filters in CDK/SAM parse the `errorCode` and `latencyMs` fields by name — do not rename them.
- **OutputFormat:** `cat.complai.openrouter.dto.OutputFormat` enum (`JSON | PDF | AUTO`). `OutputFormat.fromString()` returns `null` for unrecognised values (intentional sentinel — the controller rejects them with 400). `isSupportedClientFormat(f)` validates at the HTTP boundary.
- **ComplainantIdentity:** `cat.complai.openrouter.dto.ComplainantIdentity` record. Use `isComplete()` to gate the async path and `isPartiallyProvided()` to distinguish "nothing provided" from "partial". Never pass raw name/surname/id strings through service boundaries — always use this record.
- **Pending complaint cache:** `OpenRouterServices` keeps a `pendingComplaintCache` (Caffeine, 30-min TTL) keyed by `conversationId`. It stores the original complaint text when identity is incomplete on the first turn so it can be resumed when the user provides identity on a follow-up turn.
- **Conversation history cache:** `OpenRouterServices` also keeps a `conversationCache` (Caffeine, 30-min TTL, `maximumSize(10_000)`). Per-conversation history is capped at `MAX_HISTORY_TURNS = 10` turns before the oldest entries are dropped. Do not assume unbounded history.
- **Security:**
    - **JWT:** Requests must have a valid Bearer token (HS256). Validated by `JwtAuthFilter`.
    - **Exclusions:** `GET /` and `GET /health` bypass JWT validation — this is explicit in `JwtAuthFilter.isExcluded()`, not in configuration.
    - **Secrets:** `JWT_SECRET` and `OPENROUTER_API_KEY` are injected via environment variables (Lambda config).
- **PDF Generation:** Use `PdfGenerator` (Apache PDFBox). **Crucial:** `application.properties` registers `application/pdf` as a binary type to ensure correct base64 encoding by the Lambda runtime.
- **PDF Unicode Font Embedding:** PDF generation now embeds `NotoSans-Regular.ttf` (see `src/main/resources/`). This ensures full Unicode coverage for Catalan, Spanish, and English characters in complaint letters. The previous Helvetica limitation is resolved.
- **Async boundary:** The SQS message schema (`RedactSqsMessage`) is the contract between the API Lambda and the worker Lambda. Treat it as a versioned API — changes must be backwards-compatible or deployed atomically.
- **Input Length Validation:** All user input is limited to 5000 characters (`complai.input.max-length-chars` in `application.properties`). Inputs exceeding this limit are rejected at the service boundary with a validation error.
- **HTTP Retry Policy:** `HttpWrapper` retries on `429` (rate-limited) and `5xx` responses only, up to `OPENROUTER_MAX_RETRIES` times (default 3). `4xx` responses other than `429` and network exceptions are **never retried**. The retry count is configurable; a value of `1` means no retry.
- **JWT activation guard:** Both `JwtAuthFilter` and `JwtValidator` carry `@Requires(property = "jwt.secret")`. They do not load in the worker Lambda (which has no `JWT_SECRET`). Do not add JWT logic to code paths shared by both Lambdas without this guard.
- **JWT required claims:** `JwtValidator` enforces `iss` = `"complai"` (hardcoded in `JwtValidator.EXPECTED_ISSUER`), `exp` (tokens without an expiry are rejected), `sub`, and `city`. Tokens missing any of these are rejected with `401`. Never change `EXPECTED_ISSUER` without minting new tokens for all consumers.

## 4. Key Files & Directories

- `src/main/resources/procedures-elprat.json`: Classpath fallback RAG data for El Prat (used when S3 env vars are absent, e.g. local tests).
- `src/main/resources/scrapers/procedures-mapping-elprat.json`: Scraper mapping for El Prat — defines the base URL, CSS selectors, field extraction rules, and skip conditions used by `ProcedureScraper`. Add a new `procedures-mapping-<cityId>.json` here to support a new city.
- `src/main/java/cat/complai/openrouter/services/OpenRouterServices.java`: Synchronous ask/redact orchestration.
- `src/main/java/cat/complai/openrouter/interfaces/IOpenRouterService.java`: Service interface — depend on this, not the concrete class.
- `src/main/java/cat/complai/openrouter/helpers/RedactPromptBuilder.java`: Shared AI prompt builder (used by sync service and async worker).
- `src/main/java/cat/complai/openrouter/helpers/AiParsed.java`: Parses AI reply format headers (3 shapes).
- `src/main/java/cat/complai/openrouter/helpers/AuditLogger.java`: Privacy-preserving structured audit log writer.
- `src/main/java/cat/complai/openrouter/helpers/LanguageDetector.java`: Heuristic language detector (CA/ES/EN). Stateless; call `LanguageDetector.detect(text)`.
- `src/main/java/cat/complai/openrouter/helpers/ProcedureRagHelper.java`: Per-city Lucene index builder. Takes a `cityId` constructor parameter.
- `src/main/java/cat/complai/openrouter/helpers/ProcedureRagHelperRegistry.java`: `@Singleton` lazy cache of `ProcedureRagHelper` instances keyed by cityId. Initialises a new helper on first request per city, then reuses it.
- `src/main/java/cat/complai/http/HttpWrapper.java`: OpenRouter HTTP client (`@Singleton`).
- `src/main/java/cat/complai/home/HealthController.java`: `GET /health` — no JWT required. `HealthDto` and `HealthService` are inner classes defined in this file.
- `src/main/java/cat/complai/auth/JwtAuthFilter.java`: JWT filter; explicit exclusion list for `/` and `/health`.
- `src/main/java/cat/complai/auth/JwtValidationResult.java`: `record` returned by `JwtValidator.validate()`. Carries `valid`, `subject`, `city`, and `failureReason`. The `city` field is always populated on success (never null).
- `src/main/java/cat/complai/auth/TokenGenerator.java`: Offline CLI to mint JWT tokens.
- `src/main/java/cat/complai/sqs/SqsComplaintPublisher.java`: Publishes `RedactSqsMessage` to SQS.
- `src/main/java/cat/complai/sqs/dto/RedactSqsMessage.java`: SQS message contract between API Lambda and worker Lambda.
- `src/main/java/cat/complai/worker/RedactWorkerHandler.java`: SQS-triggered worker Lambda entry point.
- `src/main/java/cat/complai/worker/ComplaintLetterGenerator.java`: AI-call + PDF-render orchestration for the worker.
- `src/main/java/cat/complai/s3/S3PdfUploader.java`: Uploads PDFs to S3 and generates pre-signed GET URLs.
- `src/main/java/cat/complai/scrapper/ProcedureScraper.java`: Generic city procedures scraper — reads `scrapers/procedures-mapping-<cityId>.json`, produces `procedures-<cityId>.json`, uploads to S3.
- `src/main/java/cat/complai/scrapper/ProcedureIndexLoader.java`: Downloads `procedures-<cityId>.json` from S3 at Lambda start.
- `cdk/deployment-environment.ts`: Shared `DeploymentEnvironment` type (`'development' | 'production'`).
- `cdk/storage-stack.ts`: S3 bucket definitions (procedures + complaints).
- `cdk/queue-stack.ts`: SQS queue + DLQ definitions.
- `cdk/lambda-stack.ts`: API Lambda + Worker Lambda + IAM + log groups + metric filters.
- `sam/template.yaml`: SAM definition for local testing (mirrors CDK infrastructure).
- `sam/docker-compose.yml`: LocalStack services (S3 + SQS) for local development.
- `sam/sqs_worker_poller.py`: Polls local SQS and invokes `ComplAIRedactorFunction` via `sam local invoke`. Started by `start-local.sh`.
- `sam/localstack-init/init.sh`: Creates local S3 buckets and SQS queues on first LocalStack startup.
- `.github/workflows/deploy.yml`: CI/CD pipeline. Triggers automatically on PRs (deploys to `development`) and manually (`workflow_dispatch`) for both environments. A guard job blocks production deploys from non-master branches.
- `.github/workflows/release.yml`: Automatic release creator. On every push to master, reads `version` from `build.gradle` and creates a GitHub Release + tag when the version is new and greater than the latest tag.
- `.github/workflows/scrape-upload-procedures.yml`: GitHub Actions workflow for manually triggering a city scrape. Inputs: `city` (e.g. `elprat`) and `environment` (`development` or `production`).

## 5. External Integrations

- **OpenRouter:** The AI model provider. Called by both the API Lambda (sync path) and the worker Lambda (async path).
- **Prat Espais (Frontend):** The client application. Expects specific JSON formats defined in `OpenRouterPublicDto` and `RedactAcceptedDto`.
- **AWS S3:**
    - `complai-procedures-<env>`: Stores `procedures-<cityId>.json` for RAG. Read by both Lambdas.
    - `complai-complaints-<env>`: Stores generated complaint PDFs. Written by the worker Lambda; read (pre-signed URL) by the API Lambda.
- **AWS SQS:** `complai-redact-<env>` decouples the HTTP request lifecycle from AI + PDF generation. DLQ: `complai-redact-dlq-<env>` (3 retries, 7-day retention).
- **AWS Lambda:** Two functions per environment — `ComplAILambda-<env>` (API) and `ComplAIRedactorLambda-<env>` (worker). Both run the same `complai-all.jar` with different handler classes.

## 6. Environment Variables

| Variable | Used by | Description |
|---|---|---|
| `OPENROUTER_API_KEY` | Both Lambdas | OpenRouter authentication key |
| `OPENROUTER_MODEL` | Both Lambdas | Model identifier (e.g. `stepfun/step-3.5-flash:free`) |
| `OPENROUTER_REQUEST_TIMEOUT_SECONDS` | Both Lambdas | Per-request HTTP timeout |
| `OPENROUTER_OVERALL_TIMEOUT_SECONDS` | Both Lambdas | Total operation timeout |
| `OPENROUTER_MAX_RETRIES` | Both Lambdas | Max retries for `429`/`5xx` responses (default `3`; set to `1` to disable retries) |
| `JWT_SECRET` | API Lambda | Base64-encoded HS256 secret for JWT validation |
| `PROCEDURES_BUCKET` | Both Lambdas | S3 bucket name for `procedures-<cityId>.json` |
| `PROCEDURES_REGION` | Both Lambdas | AWS region of the procedures bucket |
| `REDACT_QUEUE_URL` | API Lambda | SQS queue URL for publishing async redact messages |
| `COMPLAINTS_BUCKET` | Both Lambdas | S3 bucket name where generated PDFs are stored |
| `COMPLAINTS_REGION` | Both Lambdas | AWS region of the complaints bucket |
| `AWS_ENDPOINT_URL` | Both Lambdas | Optional LocalStack endpoint override for S3 and SQS. Leave empty (or unset) in production. |

## 7. API Endpoints

| Endpoint | Method | Request | Response | Notes |
|---|---|---|---|---|
| `/` | GET | — | `200` `HomeDto` | No JWT required |
| `/health` | GET | — | `200` `HealthDto` (`status`, `version`, `checks`) | No JWT required |
| `/complai/ask` | POST | `{ text, conversationId }` | `200` `OpenRouterPublicDto` | Always synchronous |
| `/complai/redact` | POST | `{ text, format, conversationId, requesterName?, requesterSurname?, requesterIdNumber? }` | `202` `RedactAcceptedDto` **or** `200` JSON/PDF | 202 when identity complete + format≠json; 200 otherwise |

### `202 Accepted` — Async PDF Queued

The `message` field is localised by `LanguageDetector.detect(text)` — it is returned in Catalan, Spanish, or English depending on the detected language of the complaint text.

```json
{
  "success": true,
  "message": "La vostra carta de reclamació s'està generant. Estarà disponible d'aquí a pocs minuts a l'adreça de sota.",
  "pdfUrl": "https://complai-complaints-development.s3.eu-west-1.amazonaws.com/complaints/abc-123/1741689600-complaint.pdf",
  "errorCode": 0
}
```

## 8. Common Pitfalls

- **Binary Response:** If PDFs return blank/corrupted on the synchronous path, check `micronaut.function.binary-types` in `application.properties`.
- **PDF Unicode Support:** PDF output now uses an embedded Unicode font (`NotoSans-Regular.ttf`). If you see missing or garbled characters, verify the font file is present in `src/main/resources/` and not corrupted.
- **Input Length Exceeded:** Requests with input text longer than 5000 characters are rejected with a validation error. This is enforced by the service layer and configured in `application.properties`.
- **Cold Starts:** `snapstart` is enabled in `build.gradle` (CRaC) to mitigate this. Applies to both Lambdas.
- **Memory:** Both Lambdas are configured with 768MB. Lucene index and PDFBox both hold data in-heap; stay within this budget.
- **SQS Visibility Timeout:** The worker Lambda timeout (60s) must always be ≤ the queue's visibility timeout (90s). If you increase the Lambda timeout, increase the queue's visibility timeout first.
- **Pre-signed URL expiry:** The `pdfUrl` in a `202` response expires in 24h. If a client stores the URL and retrieves it later, it may receive `403 Forbidden`. Generate a fresh URL via a new request if needed.
- **Cross-stack references:** CDK generates CloudFormation Exports/Imports when the `LambdaStack` references resources from `StorageStack` or `QueueStack`. Never delete `StorageStack` or `QueueStack` while `LambdaStack` is deployed — CloudFormation will refuse to delete an exported value that is still in use.
- **UNAUTHORIZED errorCode:** `OpenRouterErrorCode.UNAUTHORIZED(6)` is set by `JwtAuthFilter` before the controller runs. The controller's `errorToHttpResponse` switch has no case for it by design — do not add one.
- **JWT issuer:** `JwtValidator` enforces `iss` = `"complai"` (hardcoded in `EXPECTED_ISSUER`). Tokens from other issuers are rejected with `401` even if the signature is valid. `TokenGenerator` always sets this issuer — do not bypass it.
- **Audit log field names are load-bearing:** `AuditLogger` writes `errorCode`, `latencyMs`, and `endpoint` as JSON field names. CloudWatch metric filters in both CDK (`lambda-stack.ts`) and SAM (`template.yaml`) match these exact names. Renaming them will silently break all CloudWatch metrics.
