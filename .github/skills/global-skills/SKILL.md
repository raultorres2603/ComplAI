# Copilot Skills for ComplAI

## Skill: Create Micronaut Controller
**Trigger**: "Build a new controller for [Resource]"
**Action**:
Generate a Java class annotated with `@Controller("/resource")`. Inject required services via the constructor. Define HTTP methods (`@Get`, `@Post`) and consume/produce `MediaType.APPLICATION_JSON`.
When endpoint behavior is user-facing, enforce city-scoped logic from authenticated request context and preserve current JWT/OIDC behavior. Use typed DTO/error responses (`OpenRouterErrorCode`) and maintain privacy-safe `AuditLogger` usage.

## Skill: Implement AWS SQS Worker
**Trigger**: "Add a new SQS worker handler"
**Action**:
Create a new worker handler following `RedactWorkerHandler` patterns: safe message parsing, batch-item failure reporting for retries, and clear separation between queue handler and domain service.
If required, update CDK queue/lambda wiring and IAM permissions (`queue-stack.ts`, `lambda-stack.ts`) and ensure API/worker env vars stay aligned.

## Skill: Extend RAG by City
**Trigger**: "Add new city knowledge", "improve retrieval", "add procedure/event context"
**Action**:
Extend `ProcedureRagHelper` / `EventRagHelper` behavior via their registries (`ProcedureRagHelperRegistry`, `EventRagHelperRegistry`) rather than creating per-request helper instances.
Preserve lazy loading, per-city caching, and source deduplication order in context assembly.

## Skill: Write Unit Test
**Trigger**: "Test the new service"
**Action**:
Create a JUnit 5 test class. Use plain JUnit + Mockito `@ExtendWith(MockitoExtension.class)` for fast isolated services and `@MicronautTest` for HTTP/filter wiring.
Mock downstream dependencies like `S3PdfUploader`, `OpenRouterClient`, or publishers/helpers. For endpoints, assert both response payload and status mapping from `OpenRouterErrorCode`.
Run `./gradlew test` (and `./gradlew ciTest` when requested) and capture outcomes.

## Skill: Task Management
**Trigger**: "Update task.md"
**Action**:
Open `task.md`, parse the current unchecked steps `[ ]`, mark the just-completed step as `[x]`, and list the immediate next step to be worked on.

## Skill: Plan Security-Sensitive Endpoint Work
**Trigger**: "Add endpoint", "modify redact flow", "add identity validation"
**Action**:
In `task.md`, explicitly include: JWT path behavior, city scoping requirements, OIDC identity-token handling decision, audit logging impact, and unit/integration test tasks.