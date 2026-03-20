---
applyTo: "src/main/java/**/services/**/*.java"
---
When implementing or editing service-layer classes in this repository:

- Keep services focused on business logic, not HTTP or infrastructure concerns.
- Use constructor-based dependency injection only.
- Preserve multi-city awareness: always use city context from the authenticated request or SQS message when business logic is city-dependent.
- Prefer extending or reusing existing helpers, registries, and validation patterns (e.g., RAG helpers, Caffeine caches, OpenRouterErrorCode mapping).
- Return typed result records or enums for error/success, not broad exceptions; map errors to OpenRouterErrorCode where relevant.
- Ensure all changes are covered by unit tests (plain JUnit + Mockito for isolated logic; integration tests only if Micronaut context is required).
- Add or preserve structured, privacy-safe audit logging for significant business events or errors.
- If the service interacts with async flows (SQS, S3, AI), preserve existing async pipeline semantics and error reporting patterns.
