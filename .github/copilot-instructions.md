# ComplAI - Project Instructions & Tech Stack

## Tech Stack
- **Backend Framework**: Java 25 with Micronaut
- **Build Tool**: Gradle
- **Infrastructure as Code (IaC)**: AWS CDK (TypeScript)
- **AWS Services**: SQS (Worker Poller, Complaint Publisher), S3 (PDF Uploader), Lambda
- **Testing**: JUnit 5, Mockito for Unit/Integration tests. Bruno (`.bru` files) for E2E HTTP testing.
- **External Integrations**: OpenRouter AI (for AI Response Processing), Web Scraping tools.
- **Core Libraries**: Lucene (RAG search), Caffeine (conversation cache), PDFBox (PDF generation), JJWT (JWT validation).

## Architecture & Patterns
- **Dependency Injection**: Use Micronaut's `@Singleton`, `@Controller`, and constructor-based injection.
- **Controllers**: Located in `cat.complai.*.controllers`. Handle HTTP requests and DTO mapping.
- **Services**: Contain business logic (e.g., `OpenRouterServices`, `AiResponseProcessingService`).
- **Workers**: SQS message handlers (`RedactWorkerHandler`) with batch failure reporting for retries.
- **Security**: JWT-based authentication (`JwtAuthFilter`, `JwtValidator`) plus optional per-city OIDC identity validation.
- **Multi-city**: Every user-facing feature must respect city context from JWT claim/attributes.
- **RAG Registry Pattern**: Use helper registries (e.g., `ProcedureRagHelperRegistry`, `EventRagHelperRegistry`) for per-city lazy loading and cache reuse.
- **Async Complaint Flow**: `POST /complai/redact` returns `202 Accepted` + presigned S3 URL after queueing SQS work; worker generates and uploads PDF.
- **Error Modeling**: Prefer typed results / DTO error codes (`OpenRouterErrorCode`, result records) over broad exception-driven control flow.

## Coding Standards
1. Use immutable DTOs (Records are preferred in Java 14+).
2. Follow strict separation of concerns: Controllers -> Services -> Repositories/External APIs.
3. Write Unit Tests for all core business logic and Integration tests for HTTP layers.
4. Update `cdk/` infrastructure files if new AWS resources (Queues, Buckets) are needed.
5. When extending ask/redact flows, preserve structured audit logging (`AuditLogger`) and privacy-safe logging practices.
6. Prefer plain JUnit + Mockito for isolated service tests; use `@MicronautTest` for HTTP/integration wiring tests.