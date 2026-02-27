# AGENTS.md — ComplAI Repository Context

## Project Overview

**ComplAI** is a serverless AI assistant for residents of **El Prat de Llobregat** (Catalonia, Spain). It helps citizens ask local questions and draft formal complaints addressed to the City Hall (Ajuntament). The AI assistant is called **Gall Potablava** and responds in Catalan, Spanish, and English.

The application exposes a REST API deployed as an **AWS Lambda** behind **API Gateway v2 (HTTP API)**.

---

## Tech Stack

| Layer            | Technology                                                         |
|------------------|--------------------------------------------------------------------|
| Language         | Java 21                                                            |
| Framework        | Micronaut 4.10.7 (function runtime, DI, HTTP layer)               |
| Build            | Gradle (Groovy DSL) with Shadow JAR plugin                         |
| AI Backend       | OpenRouter API (`minimax/minimax-m2.5` model)                      |
| PDF Generation   | Apache PDFBox 2.0.29                                               |
| Caching          | Micronaut Caffeine Cache                                           |
| Serialization    | Jackson (micronaut-jackson-databind)                               |
| Runtime          | AWS Lambda (Java 21, SnapStart-ready, CRaC support)               |
| Infrastructure   | AWS CDK (TypeScript) — two stacks: `development` and `production`  |
| Local Dev        | AWS SAM CLI + LocalStack (Docker Compose)                          |
| Testing          | JUnit 5, Micronaut Test (`@MicronautTest`, `@MockBean`)           |
| Logging          | JUL (`java.util.logging`) bridged to Logback via SLF4J            |
| API Testing      | Bruno (E2E collection in `E2E ComplAI/`)                           |

---

## Project Structure

```
src/main/java/cat/complai/
├── App.java                          # Micronaut entry point
├── home/
│   ├── HomeController.java           # GET / — health-check / welcome endpoint
│   └── dto/HomeDto.java
├── http/
│   ├── HttpWrapper.java              # Singleton: HTTP client for OpenRouter API
│   └── dto/HttpDto.java              # Record: raw HTTP response from OpenRouter
└── openrouter/
    ├── controllers/
    │   ├── OpenRouterController.java # POST /complai/ask, POST /complai/redact
    │   └── dto/
    │       ├── AskRequest.java       # Inbound DTO for /ask
    │       └── RedactRequest.java    # Inbound DTO for /redact (text + format)
    ├── dto/
    │   ├── OpenRouterErrorCode.java  # Enum: NONE, VALIDATION, REFUSAL, UPSTREAM, TIMEOUT, INTERNAL
    │   ├── OpenRouterPublicDto.java  # Outbound DTO: what clients receive
    │   ├── OpenRouterResponseDto.java# Internal DTO: includes pdfData, statusCode
    │   └── OutputFormat.java         # Enum: JSON, PDF, AUTO — fromString returns null for unrecognised values
    ├── helpers/
    │   └── AiParsed.java             # Record: parses AI JSON header from model response
    └── interfaces/
        ├── IOpenRouterService.java   # Service interface: ask() + redactComplaint()
        └── services/
            └── OpenRouterServices.java # Singleton: orchestrates prompt → AI call → response/PDF
```

### Infrastructure

```
cdk/                    # AWS CDK (TypeScript) — Lambda + API Gateway v2 + IAM + CloudWatch
├── bin/cdk.ts          # App entry: two stacks (development, production)
├── lambda-stack.ts     # LambdaStack: Function, HttpApi, IAM Role, Log Group
└── package.json

sam/                    # Local development with SAM CLI + LocalStack
├── template.yaml       # SAM template mirroring CDK production config
├── docker-compose.yml  # LocalStack container
├── env.json            # Local environment overrides
└── start-local.sh      # Helper script to build & start locally
```

### Tests

```
src/test/java/cat/complai/
├── http/
│   └── HttpWrapperTest.java                          # Unit: mock HTTP server, URL normalization
└── openrouter/
    ├── controllers/
    │   ├── OpenRouterControllerTest.java              # Unit: controller with fake services
    │   └── OpenRouterControllerIntegrationTest.java   # Integration: @MicronautTest + @MockBean
    └── interfaces/services/
        └── OpenRouterServicesTest.java                # Unit: service logic, refusal detection, PDF
```

---

## Architecture & Key Patterns

### Layered Architecture

1. **Controller** (`OpenRouterController`) — HTTP boundary. Validates the `format` field at the boundary (rejects unsupported values like `"xml"` with a `400` before the service is called) and maps service-level `OpenRouterErrorCode` to HTTP status codes via an exhaustive `switch` expression.
2. **Service Interface** (`IOpenRouterService`) — contract. Two operations: `ask(String)` and `redactComplaint(String, OutputFormat)`.
3. **Service Implementation** (`OpenRouterServices`) — business logic. Builds prompts, calls the AI, detects refusals, parses AI metadata headers, generates PDFs.
4. **HTTP Wrapper** (`HttpWrapper`) — infrastructure. Sends async HTTP requests to OpenRouter, extracts the assistant message from the OpenAI-compatible JSON response.

### Dependency Injection

Micronaut compile-time DI. All beans are `@Singleton`. Constructor injection with `@Inject`. The controller depends on `IOpenRouterService` (interface), not the implementation.

### Error Handling Strategy

- Every service method returns `OpenRouterResponseDto` (never throws to the controller).
- `OpenRouterErrorCode` is the authoritative error signal (not the message string).
- The controller maps error codes to HTTP statuses: `VALIDATION → 400`, `REFUSAL → 422`, `TIMEOUT → 504`, `UPSTREAM → 502`, `INTERNAL → 500`.
- Unexpected exceptions in the controller are caught and returned as `500` with an `INTERNAL` error code.

### AI Response Protocol (Redact Endpoint)

The `/complai/redact` endpoint instructs the AI model to emit a **JSON header on the first line** of its response (e.g., `{"format": "pdf"}`). `AiParsed.parseAiFormatHeader()` extracts this header.

**Graceful fallback when the header is missing:**
- If the client requested `AUTO` or `JSON` → the raw AI message is returned as a `200` JSON response (the user still gets their letter).
- If the client explicitly requested `PDF` → the service returns an `UPSTREAM` error because it cannot extract a clean letter body for PDF generation.

**Format validation at the controller level:**
- `OutputFormat.fromString()` returns `null` for unrecognised values (e.g., `"xml"`, `"docx"`).
- The controller checks `OutputFormat.isSupportedClientFormat()` and rejects unsupported values with a `400 VALIDATION` response and a clear message: only `pdf`, `json`, or `auto` are accepted. **PDF is the only supported document format.**

### PDF Generation

PDFBox generates PDFs in-memory. The `OpenRouterController` returns `application/pdf` with an explicit `Content-Length` header to prevent Netty connection issues.

---

## API Endpoints

| Method | Path              | Description                                      | Response                              |
|--------|-------------------|--------------------------------------------------|---------------------------------------|
| GET    | `/`               | Health check / welcome                           | `200` with `HomeDto`                  |
| POST   | `/complai/ask`    | Ask a local question about El Prat               | `200` JSON or error status            |
| POST   | `/complai/redact` | Draft a formal complaint letter (JSON or PDF)     | `200` JSON/PDF or error status        |

### Error Codes (numeric, in response body)

| Code | Name         | HTTP Status |
|------|--------------|-------------|
| 0    | NONE         | 200         |
| 1    | VALIDATION   | 400         |
| 2    | REFUSAL      | 422         |
| 3    | UPSTREAM     | 502         |
| 4    | TIMEOUT      | 504         |
| 5    | INTERNAL     | 500         |

---

## Build & Run

### Prerequisites

- Java 21 (toolchain-managed by Gradle)
- Docker (for local SAM/LocalStack development)

### Build

```bash
./gradlew clean shadowJar
# Produces build/libs/complai-all.jar
```

### Run Tests

```bash
./gradlew test          # Standard test suite
./gradlew ciTest        # CI-specific task with verbose logging
```

### Local Development (SAM + LocalStack)

```bash
cd sam
./start-local.sh
# Or manually:
#   ../gradlew clean shadowJar
#   docker compose up -d
#   sam local start-api --env-vars env.json
```

### Deploy (CDK)

```bash
cd cdk
npm install
npx cdk deploy ComplAILambdaStack-development --parameters OpenRouterApiKey=<key>
npx cdk deploy ComplAILambdaStack-production  --parameters OpenRouterApiKey=<key>
```

---

## Environment Variables

| Variable              | Required | Description                                     |
|-----------------------|----------|-------------------------------------------------|
| `OPENROUTER_API_KEY`  | Yes      | Bearer token for the OpenRouter API              |
| `openrouter.url`      | No       | Override the OpenRouter endpoint (default: `https://openrouter.ai/api/v1/chat/completions`) |

---

## Coding Conventions

- **Java 21** features are used (text blocks, records, switch expressions, pattern matching).
- **DTOs** are either records (`HttpDto`, `AiParsed`) or immutable classes with `final` fields and `@Introspected` for Micronaut serialization.
- **Logging** uses `java.util.logging.Logger` (one per class, named after the class).
- **No mocking frameworks** — tests use hand-written fakes (e.g., `FakeServiceSuccess`, `FakeServiceRefuse`) or Micronaut's `@MockBean` with subclassing.
- **Constructor injection** everywhere. No field injection.
- **Interface-based service contracts** — controllers depend on interfaces, not implementations.
- **Exhaustive switch expressions** for error-code-to-HTTP-status mapping (no default fall-through without a log).
- **Input validation** at both boundaries — the controller rejects unsupported `format` values before the service is called; the service validates null/blank text inputs and returns typed error DTOs, never throws.
- **Comments explain WHY**, not what (e.g., why `Content-Length` is set explicitly on PDF responses).

---

## Testing Approach

- **Unit tests** use fake implementations of service interfaces and lightweight HTTP servers (`com.sun.net.httpserver.HttpServer`) to mock external APIs.
- **Integration tests** use `@MicronautTest` with `@MockBean` to replace `HttpWrapper` and verify the full controller→service→response pipeline.
- **PDF tests** verify generation at the service level (not via HTTP) because Micronaut's embedded Netty server closes connections on binary responses.
- **Edge cases** covered: empty input, AI refusal detection, missing API keys, non-2xx upstream responses, timeout handling, malformed URLs, missing AI JSON header graceful fallback (AUTO/JSON succeed, PDF returns UPSTREAM error), unsupported client format values rejected at controller boundary.

---

## Security Notes

- The `OPENROUTER_API_KEY` is passed via CloudFormation parameter with `noEcho: true` and injected as a Lambda environment variable. It is **never committed** to the repository.
- `HttpWrapper` prefixes the key with `Bearer ` if not already present.
- The Lambda IAM role follows least-privilege (only `AWSLambdaBasicExecutionRole`).
- Input is validated at service boundaries; the AI model is scoped to El Prat topics only.

