# ComplAI Developer Guide for AI Agents

This guide provides essential context for AI agents working on the ComplAI codebase, a serverless backend for the "Gall Potablava" citizen assistant.

## 1. Architecture Overview

ComplAI is a **Java 21 Micronaut application** designed to run as an **AWS Lambda function**. It serves as the intelligent backend for a chatbot integrated into the "Prat Espais" platform.

### Core Components
- **Entry Point:** `cat.complai.App` bootstraps the Micronaut application.
- **Controller Layer:** `cat.complai.openrouter.controllers.OpenRouterController` handles HTTP requests (primarily `POST /complai/ask` and `POST /complai/redact`).
- **Service Layer:** `cat.complai.openrouter.services.OpenRouterServices` orchestrates the business logic:
    - Managing conversation history (cached in Caffeine).
    - Retrieving context via RAG (`ProcedureRagHelper`).
    - Communicating with the LLM via OpenRouter (`HttpWrapper`, `OpenRouterClient`).
    - Generating PDF complaint letters (`PdfGenerator`).
- **Data Access (RAG):** `ProcedureRagHelper` uses **Apache Lucene** in-memory to index and search municipal procedures loaded from `src/main/resources/procedures.json`.
- **Infrastructure:** AWS CDK (`cdk/`) defines the infrastructure. AWS SAM (`sam/`) is used for local emulation.

### Key Data Flows
1. **User Query:** `Prat Espais` widget -> `POST /complai/ask` (JSON).
2. **Context Retrieval:** `OpenRouterServices` queries `ProcedureRagHelper` to find relevant procedures.
3. **LLM Interaction:** `OpenRouterServices` constructs a prompt (System Prompt + RAG Context + Conversation History) and calls OpenRouter API.
4. **Response:** The LLM response is parsed. If a complaint letter is generated, it's returned as a base64-encoded PDF or structured text.

## 2. Developer Workflows

### Build & Test
- **Build Fat JAR:** `./gradlew clean shadowJar` (Creates `build/libs/complai-all.jar`).
- **Run Unit Tests:** `./gradlew test`.
- **Local Execution (SAM):**
    - Ensure Docker is running.
    - Run `./sam/start-local.sh` (Builds and starts the Lambda locally on port 3000).
    - Test via `curl` or Bruno (see below).
- **E2E Testing:** Use **Bruno** locally. Collection located in `E2E-ComplAI/`.
    - Key request: `E2E-ComplAI/02-OK/Ask to ComplAI.bru`.

### Deployment
- **Infrastructure Code:** TypeScript CDK in `cdk/`.
- **Deploy:** `cdk deploy` (Requires AWS credentials).
    - Stacks: `ComplAILambdaStack-development` and `ComplAILambdaStack-production`.

## 3. Project Conventions & Patterns

- **Framework:** **Micronaut 4.x**. heavy use of `@Singleton`, `@Controller`, `@Inject`.
- **Language:** **Java 21**. Use `record` for DTOs and immutable data carriers.
- **DTOs:** Located in `*.dto` packages. Use strict typing.
- **Error Handling:** Use `OpenRouterErrorCode` enum to map specific error conditions to standardized error codes and HTTP statuses.
- **Security:**
    - **JWT:** Requests must have a valid Bearer token (HS256). Validated by `JwtAuthFilter`.
    - **Secrets:** `JWT_SECRET` and `OPENROUTER_API_KEY` are injected via environment variables (Lambda config).
- **PDF Generation:** Use `PdfGenerator` (Apache PDFBox). **Crucial:** `application.properties` registers `application/pdf` as a binary type to ensure correct base64 encoding by the Lambda runtime.

## 4. Key Files & Directories

- `src/main/resources/procedures.json`: The source of truth for RAG data.
- `src/main/java/cat/complai/openrouter/services/OpenRouterServices.java`: The "brain" of the application.
- `src/main/java/cat/complai/openrouter/helpers/PdfGenerator.java`: PDF creation logic.
- `sam/template.yaml`: SAM definition for local testing.
- `cdk/lib/lambda-stack.ts`: AWS infrastructure definition.

## 5. External Integrations

- **OpenRouter:** The AI model provider.
- **Prat Espais (Frontend):** The client application. Expects specific JSON formats defined in `OpenRouterPublicDto`.
- **AWS S3:** Used for fetching procedure updates (in `ProcedureIndexLoader`), though local resource is default.
- **AWS Lambda:** The runtime environment.

## 6. Common Pitfalls

- **Binary Response:** If PDFs return blank/corrupted, check `micronaut.function.binary-types` in `application.properties`.
- **Cold Starts:** `snapstart` is enabled in `build.gradle` (CRaC) to mitigate this.
- **Memory:** Lambda is configured with 768MB (see `sam/template.yaml`). Lucene index manages memory usage carefully.

