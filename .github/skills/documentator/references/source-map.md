# Source Map — Where to Find Every Fact

This file tells the Documentator exactly which files to read for each category of information. Always read the listed files before writing the corresponding README section.

---

## Infrastructure (Section 9 → also feeds Section 5, 14)

| File | What to extract |
|---|---|
| `cdk/lambda-stack.ts` | Lambda function names, runtime, memory, timeout, event sources (SQS ARNs), environment variables passed to the function |
| `cdk/queue-stack.ts` | SQS queue names (standard + DLQ), visibility timeout, message retention, `maxReceiveCount` |
| `cdk/storage-stack.ts` | S3 bucket names, versioning, lifecycle rules, public access block settings |
| `cdk/bin/cdk.ts` | Which stacks are instantiated, region, account bindings, stack dependencies |
| `cdk/deployment-environment.ts` | Environment variable names injected at deploy time, account/region resolution |
| `cdk/package.json` | CDK library version |

---

## Application Layer (Sections 8, 10, 12–16)

| Pattern to search | What to extract |
|---|---|
| `*Controller*.java` | `@Controller("<path>")`, each method: `@Get/@Post/@Put/@Delete`, path, `@Secured` annotation, `@Body` / `@QueryValue` params, return type |
| `*Service*.java` | Public method signatures, injected dependencies, business logic summary |
| `*Repository*.java` or `*Client*.java` | External calls: SQS send/receive, S3 put/get, HTTP client targets |
| `*Dto*.java` or records | Field names and types used in request/response bodies |
| `*Cache*.java` or `@Cacheable` usages | What is cached, cache name, TTL if set |
| `*Lucene*.java` or `IndexSearcher` usages | Index location, query logic, result mapping |
| `*Pdf*.java` or `PDFBox` usages | What triggers PDF creation, where result is stored |
| `*Jwt*.java` or `JwtValidator` usages | Token validation flow, claim extraction |
| `*Oidc*.java` or `OidcConfiguration` usages | Supported providers, discovery URL config keys |
| `*OpenRouter*.java` or `*AiService*.java` | Model name, API endpoint, prompt construction |

---

## Configuration (Sections 7, 10, 15, 16)

| File | What to extract |
|---|---|
| `src/main/resources/application.properties` | All `${ENV_VAR_NAME:default}` placeholders → env var names + defaults |
| `sam/env.json.example` | Example values for every env var used locally |
| `gradle.properties` | `micronautVersion`, JVM args, Gradle flags |
| `settings.gradle` | `rootProject.name` (the official project name) |
| `micronaut-cli.yml` | Declared Micronaut features |

---

## Local Development (Section 7)

| File | What to extract |
|---|---|
| `sam/template.yaml` | Lambda function definitions, SQS event source config, environment variable bindings |
| `sam/docker-compose.yml` | Service names, image names, port mappings, volume mounts |
| `sam/start-local.sh` | Exact shell commands to start the local stack |
| `sam/start-local.ps1` | Windows equivalent (note differences if any) |
| `sam/env.json` (if present) | Actual local values (do NOT print secrets; reference file path only) |

---

## E2E / API Surface (Section 8)

| Path | What to extract |
|---|---|
| `E2E-ComplAI/02-OK/*.bru` | Happy-path: method, URL path, headers, body shape, expected status |
| `E2E-ComplAI/03-ERROR/*.bru` | Error cases: triggering condition, expected error response |
| `E2E-ComplAI/environments/Local.bru` | Base URL, env var usage for local runs |
| `E2E-ComplAI/environments/Development.bru` | Base URL for dev environment |
| `E2E-ComplAI/collection.bru` | Collection-level auth or headers applied to all requests |

---

## Build & Dependencies (Section 6)

| File | What to extract |
|---|---|
| `build.gradle` | `dependencies { }` block → library names and versions; `shadowJar` config; test task names |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle wrapper version |

---

## Security (Section 10)

Search patterns across `src/main/java/**`:

| Search term | What it reveals |
|---|---|
| `@Secured` | Per-endpoint auth requirements |
| `SecurityRule.IS_ANONYMOUS` | Public endpoints |
| `SecurityRule.IS_AUTHENTICATED` | Protected endpoints |
| `JwtValidator` | JWT validation implementation |
| `micronaut.security` | Security config keys in `application.properties` |
| `@TokenValidator` | Custom token validation beans |

---

## AI Behaviour (Section 16)

Search patterns across `src/main/java/**`:

| Search term | What it reveals |
|---|---|
| `openrouter` (case-insensitive) | API base URL, model name |
| `system prompt` / `systemPrompt` / `SYSTEM` | Prompt construction location |
| `Locale` / `language` / `detectLang` | Language detection logic |
| `guardrail` / `refuse` / `notAllowed` | Content restriction logic |

---

## Conversation History (Section 13)

Search patterns across `src/main/java/**`:

| Search term | What it reveals |
|---|---|
| `conversationId` | Where ID is created, stored, and retrieved |
| `ConversationCache` / `@Cacheable("conversation")` | Cache bean, TTL, eviction |
| `MessageHistory` / `ChatHistory` | Data structure for multi-turn context |

---

## Notes

- **Priority order**: CDK stacks → Controllers → Bruno collections → Config files. CDK is ground truth for infrastructure; Bruno is ground truth for the API surface.
- **When in conflict**: prefer what the code actually does over what the existing README says.
- **Secrets**: never include actual secret values in the README. Reference variable names only (as they appear in `env.json.example`).
