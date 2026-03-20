---
applyTo: "src/main/java/**/controllers/**/*.java"
---
When implementing or editing controllers in this repository:

- Keep controllers thin: map HTTP request/response and delegate business logic to services.
- Use constructor-based dependency injection only.
- Preserve existing JWT and city-context behavior; do not bypass request-authenticated city scoping.
- If endpoint requires verified citizen identity, follow existing OIDC identity-token patterns.
- Return typed DTOs and status mappings consistent with existing OpenRouterErrorCode usage.
- Add or preserve structured, privacy-safe audit logging where endpoint behavior changes.
- If CORS/preflight behavior can be affected, verify compatibility with existing CorsFilter/JwtAuthFilter flow.
- Add tests: unit tests for service behavior and integration tests for HTTP/security wiring where relevant.
