---
name: documentator
description: "Detailed documentation workflow for the Documentator agent. Use when: writing README, updating README, generating project documentation, documenting architecture, documenting API, documenting infrastructure, documenting codebase, producing technical and non-technical documentation."
argument-hint: "Describe what to document (e.g. 'Update the README' or 'Regenerate full README from scratch')"
---

# Documentator Skill — ComplAI Documentation Workflow

## Purpose

Produce or update `README.md` at the workspace root. This skill defines every phase, decision point, reference source, and quality check the Documentator agent must follow, plus the exact success/failure report it must return.

---

## Phase 1 — Scope Assessment

**Goal**: Decide whether this is a full regeneration or a targeted update.

### Steps

1. **Read the user's request** carefully. Classify the scope:
   - `FULL` — user asks to regenerate, rewrite, or create the README from scratch.
   - `PARTIAL` — user names specific sections to update (e.g., "update the API section").
2. **Read the existing `README.md`** from top to bottom. Record:
   - Which sections already exist (by heading).
   - Which sections are outdated, stale, or inconsistent with what you already know.
   - Which sections are missing entirely.
3. For a `PARTIAL` scope, restrict changes to the named sections only. Do not touch anything else.

### Quality Check

- [ ] Scope is classified as `FULL` or `PARTIAL`
- [ ] Existing README is read in full
- [ ] Delta list (outdated / missing / unchanged sections) is recorded

---

## Phase 2 — Codebase Reference Gathering

**Goal**: Collect all facts from the repository before writing a single line.

Consult sources in this order. Use the detailed source map in [./references/source-map.md](./references/source-map.md).

### 2.1 Infrastructure (CDK)

Read every file in `cdk/`:
- `cdk/lambda-stack.ts` → Lambda function names, memory, runtime, env vars, event sources.
- `cdk/queue-stack.ts` → SQS queue names, visibility timeout, DLQ config.
- `cdk/storage-stack.ts` → S3 bucket names, lifecycle rules, access policies.
- `cdk/bin/cdk.ts` → Stack composition and region.
- `cdk/deployment-environment.ts` → Environment variables and account/region bindings.

Record: every AWS resource name, type, and purpose.

### 2.2 Application Layer (Java / Micronaut)

Read `src/main/java/**`:
- **Controllers** → HTTP method, path, auth annotation (`@Secured`), request/response types.
- **Services** → Business logic summary per public method.
- **Repositories / AWS clients** → Data access patterns, external calls.
- **Domain / DTOs / Records** → Field names and types for request/response shapes.
- **Config** → `src/main/resources/application.properties` → every `${ENV_VAR}` referenced.

### 2.3 Build & Runtime

- `build.gradle` → dependencies, shadow JAR config, test tasks.
- `gradle.properties` → runtime flags, Micronaut version.
- `settings.gradle` → project name.
- `micronaut-cli.yml` → declared features.

### 2.4 Local Development (SAM)

- `sam/template.yaml` → Lambda function definitions, event sources.
- `sam/docker-compose.yml` → local services and ports.
- `sam/env.json.example` → required environment variables with example values.
- `sam/start-local.sh` → exact commands to start the local stack.

### 2.5 E2E Tests (Bruno)

Read every `.bru` file under `E2E-ComplAI/`:
- Extract: method, URL path, headers (especially `Authorization`), body shape.
- Distinguish `02-OK/` (happy path) from `03-ERROR/` (error cases) — both are needed for the API Reference section.
- Read `environments/Local.bru` and `environments/Development.bru` for base URLs and env vars.

### 2.6 Security & Auth

- Search `src/main/java/**` for `@Secured`, `JwtValidator`, `OidcConfiguration`, JWT filter classes.
- Read `src/main/resources/application.properties` for `micronaut.security.*` keys.
- Note which endpoints are public vs. protected.

### 2.7 AI Integration

- Search for `OpenRouter`, `ChatCompletion`, `AiService`, or model-name strings in `src/main/java/**`.
- Note the model used, language detection logic, and prompt assembly location.

### 2.8 Performance & Caching

- Search for `Caffeine`, `@Cacheable`, `CacheManager` in `src/main/java/**`.
- Search for `InMemoryLexicalIndex`, `LexicalScorer`, `TokenNormalizer` (in-memory lexical RAG — no Lucene dependency) in `src/main/java/**`.
- Note what is cached and for how long.

### Quality Check

- [ ] All CDK stacks read; AWS resource table populated
- [ ] All controllers read; endpoint list compiled
- [ ] `env.json.example` read; env var list compiled
- [ ] All Bruno `.bru` files read; API surface confirmed
- [ ] Security annotations and config read
- [ ] AI model and integration point identified
- [ ] Caching and RAG patterns identified

---

## Phase 3 — Write the README

Follow the section template in [./references/readme-sections.md](./references/readme-sections.md) exactly.

### Core Rules

- **Never invent**. Every fact must come from Phase 2. If a value is not found, write `N/A — not found in codebase` and continue.
- **Never leave placeholders**. A section without real content is omitted entirely, not stubbed.
- **Scope discipline**. For `PARTIAL` runs, only edit the target sections. Preserve all other content byte-for-byte.
- **Diagrams**. Architecture diagrams must be draw.io format: embed raw `.drawio` XML in a fenced `xml` code block.
- **Commands**. All shell commands go in fenced `bash` code blocks. Every command must be directly runnable — no `<placeholders>` left in them.
- **Tables**. Use Markdown tables for: tech stack, AWS resources, API endpoints, env vars.
- **Line budget**. Target ≤ 700 lines. If the content genuinely requires more, note it in the final report.

### Tone by Audience

| Section | Tone |
|---|---|
| What Is This Project? · Vision and Goals | Plain language, no acronyms, no jargon |
| All other sections | Precise technical language |

---

## Phase 4 — Validation

Before saving, self-review the drafted README:

1. **Links**: Every `#anchor` in the Table of Contents resolves to an actual heading below.
2. **Commands**: Every `bash` snippet is syntactically correct and uses values from `env.json.example` — no invented values.
3. **AWS names**: Every resource name matches what was read in CDK stacks.
4. **Endpoints**: Every path matches a controller mapping or Bruno collection entry.
5. **Diagram**: The `.drawio` XML block is well-formed and contains actual nodes (not an empty diagram).
6. **No stale content**: Remove or correct, not comment out, any outdated information.
7. **Security scan** — scan every line of the drafted README for the following and remove any match before saving:

   | Forbidden content | Action |
   |---|---|
   | API keys, tokens, passwords, or secrets (any literal value) | Remove; reference the env var name only |
   | AWS account IDs | Omit or replace with `<your-account-id>` |
   | Internal AWS ARNs containing account IDs | Remove the account ID segment |
   | Private / internal URLs or IP addresses | Omit entirely |
   | Any value read from `sam/env.json` (the real local file, not the example) | Replace with the corresponding value from `sam/env.json.example` |
   | Hardcoded passwords or connection strings | Reference the variable name, not the value |

   Log every redaction in the Final Report.

### Quality Check

- [ ] All ToC anchors resolve
- [ ] All commands verified against source files
- [ ] All AWS resource names verified against CDK stacks
- [ ] All endpoint paths verified against controllers and Bruno
- [ ] Diagram XML is non-empty and well-formed
- [ ] No invented values remain
- [ ] Security scan passed — no secrets, credentials, or private values present

---

## Phase 5 — Save and Report

1. Write the final content to `README.md` at the workspace root.
2. Immediately output the **final report** (see format below). Do not omit it.

---

## Final Report Format

The final message after every run MUST follow this exact structure:

```
## Documentator — Run Report

**Scope**: FULL | PARTIAL (<section names>)
**Outcome**: SUCCESS | PARTIAL SUCCESS | FAILURE

### Changes Made
| Section | Action |
|---|---|
| <Section Name> | Created / Updated / Unchanged / Removed |
...

### Gaps (sections with incomplete data)
| Section | Missing Information | Recommended Action |
|---|---|---|
| <Section Name> | <what was missing> | <what user should provide> |
...
(Write "None" if no gaps.)

### Line Count
README.md: <N> lines (<over/under> 700-line target)

### Validation
- [ ] ToC anchors: OK | FAIL (<detail>)
- [ ] Commands verified: OK | FAIL (<detail>)
- [ ] AWS names verified: OK | FAIL (<detail>)
- [ ] Endpoints verified: OK | FAIL (<detail>)
- [ ] Diagram well-formed: OK | FAIL (<detail>)
- [ ] Security scan: OK | REDACTIONS MADE (<list what was removed>)

### Security Redactions
<List each value removed and why, or write "None".>
```

**SUCCESS** = all validation checks passed, no gaps, security scan clean.
**PARTIAL SUCCESS** = saved successfully but one or more gaps exist, a non-critical validation item failed, or security redactions were made.
**FAILURE** = README was NOT saved (e.g., a required source file could not be read, a critical validation error was found, or a secret was detected and could not be safely redacted without losing meaningful content). Explain the blocker clearly.
