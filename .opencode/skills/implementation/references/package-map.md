# Package Map — ComplAI

Root package: `cat.complai`

## Main Source (`src/main/java/cat/complai/`)

| Package | Purpose | Key Classes |
|---------|---------|-------------|
| `auth` | JWT validation, rate limiting, OIDC | `JwtAuthFilter`, `JwtValidator`, `OidcIdentityTokenValidator`, `RateLimitFilter` |
| `feedback` | User feedback flow (submit → SQS → S3) | `FeedbackController`, `FeedbackPublisherService`, `FeedbackWorkerHandler`, `S3FeedbackUploader` |
| `feedback.controllers.dto` | Feedback HTTP DTOs | `FeedbackRequest`, `FeedbackAcceptedDto` |
| `feedback.dto` | Feedback internal DTOs | `FeedbackResult`, `FeedbackSqsMessage`, `FeedbackErrorCode` |
| `home` | Health check + root endpoint | `HealthController`, `HomeController` |
| `http` | HTTP client, CORS filter | `HttpWrapper`, `OpenRouterClient`, `CorsFilter` |
| `http.dto` | HTTP DTOs | `HttpDto`, `OpenRouterStreamStartResult` |
| `openrouter.controllers` | Main AI endpoints | `OpenRouterController` |
| `openrouter.controllers.dto` | Request DTOs | `AskRequest`, `RedactRequest` |
| `openrouter.dto` | Response/result DTOs | `OpenRouterPublicDto`, `OpenRouterResponseDto`, `OpenRouterErrorCode`, `AskStreamResult`, `OutputFormat`, `ComplainantIdentity`, `Source` |
| `openrouter.dto.sse` | SSE event DTOs | `SseChunkEvent`, `SseDoneEvent`, `SseErrorEvent`, `SseSourcesEvent` |
| `openrouter.helpers` | AI prompt building, RAG, parsers | `RedactPromptBuilder`, `SseChunkParser`, `PdfGenerator`, `AuditLogger`, `LanguageDetector` |
| `openrouter.helpers.rag` | Lucene-based RAG index | `InMemoryLexicalIndex`, `IndexedDocument`, `SearchResult`, `LexicalScorer`, `TokenNormalizer` |
| `openrouter.interfaces` | Service interface | `IOpenRouterService` |
| `openrouter.services` | AI orchestration | `OpenRouterServices` |
| `openrouter.services.ai` | AI response processing | `AiResponseProcessingService` |
| `openrouter.services.cache` | Caffeine response cache | `ResponseCacheService`, `CommonResponseCacheInitializer` |
| `openrouter.services.conversation` | Conversation history | `ConversationManagementService` |
| `openrouter.services.procedure` | RAG context assembly | `ProcedureContextService` |
| `openrouter.services.validation` | Input validation | `InputValidationService` |
| `openrouter.cache` | Cache key/category models | `ResponseCacheKey`, `QuestionCategory`, `QuestionCategoryDetector` |
| `s3` | Complaint PDF upload | `S3PdfUploader` |
| `scrapper` | Web scrapers for city data | `CityInfoScraper`, `EventScraper`, `NewsScraper`, `ProcedureScraper` |
| `sqs` | SQS publisher | `SqsComplaintPublisher` |
| `sqs.dto` | SQS message DTOs | `RedactSqsMessage` |
| `worker` | Lambda worker handler | `RedactWorkerHandler`, `ComplaintLetterGenerator` |

## Test Source (`src/test/java/cat/complai/`)

Mirrors main source structure exactly. Each production class `Foo.java` has `FooTest.java` in the same sub-package.

## Resources

| File | Purpose |
|------|---------|
| `src/main/resources/application.properties` | Micronaut config (AWS, config keys) |
| `src/test/resources/application.properties` | Test overrides |
| `src/main/resources/procedures-elprat.json` | Procedure data for RAG |
| `src/main/resources/prompt-format-rules.properties` | AI prompt format rules |

## CDK Stacks (`cdk/`)

| File | AWS Resources |
|------|---------------|
| `cdk/queue-stack.ts` | SQS queues |
| `cdk/storage-stack.ts` | S3 buckets |
| `cdk/lambda-stack.ts` | Lambda functions |
| `cdk/bin/cdk.ts` | CDK app entry point |
