# AGENTS.md — ComplAI

Micronaut 4 (Java 25) → AWS Lambda, deployed via CDK. Municipal AI assistant backend for El Prat de Llobregat.

## Exact commands

| What | Command |
|---|---|
| Run all tests | `./gradlew test` |
| CI-style test run (verbose failures) | `./gradlew ciTest` |
| Build deployable fat JAR | `./gradlew clean shadowJar` → `build/libs/complai-all.jar` |
| Run single test | `./gradlew test --tests 'cat.complai.SomeTest'` |
| Run single test method | `./gradlew test --tests 'cat.complai.SomeTest.testMethod'` |
| Run tests for one nested class | `./gradlew test --tests 'cat.complai.SomeTest$NestedClass'` |
| Local dev (SAM + LocalStack) | `cp sam/env.json.example sam/env.json && cd sam && ./start-local.sh` |
| CDK deploy (manual) | `cd cdk && npm install && npm run build && npx cdk deploy 'ComplAI*Stack-development'` |

## Test auth

`api.key.enabled=false` in `src/test/resources/application.properties` disables the production `ApiKeyAuthFilter`. It is replaced by `TestApiKeyFilter` which accepts these test keys:

| Key | City |
|---|---|
| `test-integration-key-elprat` | elprat |
| `test-integration-key-testcity` | testcity |
| `test-api-key-feedback` | elprat |
| `test-integration-key-elprat-htmlsources` | testcity |

**Gotcha:** Every `@MicronautTest` HTTP integration test must include an `X-Api-Key` header with one of these keys — otherwise the request returns 401. GET `/`, `/health`, `/health/startup` are excluded.

## Controller endpoint prefixes

Every controller is mounted under its `@Controller` base path — the full URL is the concatenation of the class-level prefix and method-level path:

| Controller | Base | Key endpoint |
|---|---|---|
| `HomeController` | `/` | `GET /` |
| `HealthController` | `/` | `GET /health`, `GET /health/startup` |
| `OpenRouterController` | `/complai` | `POST /complai/ask`, `POST /complai/redact` |
| `FeedbackController` | `/complai` | `POST /complai/feedback` |
| `SesController` | `/complai/ses` | `GET /complai/ses/stadistics` |

**Common pitfall:** Do not omit the `/complai` prefix when writing or calling endpoints in tests.

## Architecture layering

```
src/main/java/cat/complai/
  controllers/   ← HTTP boundary, status mapping only
  services/      ← orchestration, AI calls, RAG, validation
  helpers/       ← prompt building, language detection, parsing, PDF rendering, RAG
  utilities/     ← SQS publishers, S3 upload, auth filters, caches, HTTP wrapper, scrapers
  dto/           ← request/response DTOs
  exceptions/    ← typed exceptions
  config/        ← Micronaut config beans
```

No database. All retrieval is in-memory lexical (BM25) via `InMemoryLexicalIndex`. Conversation state uses Caffeine caches (TTL 30 min, max 5 turns).

## Key behavioural facts

- **SSE streaming** on `POST /complai/ask` — returns `Publisher<String>` (Reactor). Micronaut Reactor is required for SSE on `@Client` interfaces.
- **Retry logic** is manual in `HttpWrapper.sendWithRetryMicronaut()` — Micronaut HTTP client retry is explicitly disabled (`micronaut.http.client.retry-enabled=false`) to avoid stacked retries that would hit the 60 s Lambda timeout.
- **PDF responses** require `micronaut.function.binary-types.additional-types=application/pdf` so the Lambda runtime base64-encodes the body. Without this, PDFs are corrupted.
- **CORS** is handled by `CorsFilter` locally only (`complai.local-cors-enabled=true` in test). In production, infrastructure (CloudFront / API Gateway) handles CORS.
- **OIDC** validation is city-specific, driven by `src/main/resources/oidc/oidc-mapping.json`. Only active for cities with `"enabled": true`.
- **Response caching** uses privacy-preserving hash keys (cityId + procedure hash + event hash + question category — never user queries).

## Async worker flow

Redact requests and feedback are queued to SQS. A separate worker Lambda processes them:
1. Reads SQS message
2. Calls OpenRouter for AI response
3. Generates PDF via PDFBox (redact only)
4. Uploads to S3, returns signed URL

The `sam/start-local.sh` script runs a Python poller (`sqs_worker_poller.py`) to mimic SQS→Lambda triggering locally.

## Deployment

- **CI:** On every PR → auto-deploy to `development`. Manual `workflow_dispatch` → choose `development` or `production`.
- **Production deploy** requires: (1) branch must be `master` (enforced by guard job), (2) manual approval in GitHub Environment.
- **JAR key** in S3 includes the short git SHA (`complai-all-<sha8>.jar`) — this is how CloudFormation detects code changes.
- **Release:** Push to `master` with a bumped `version` in `build.gradle` → `release.yml` auto-creates tag + GitHub Release.
- **Stacks:** Storage → Queue → Lambda → Edge (production only, CloudFront+WAF).

## CI / PR rules

- Run `./gradlew ciTest` before pushing. All tests must pass.
- Bruno E2E collection lives in `E2E-ComplAI/`.
