---
name: documentation
description: "Documentation workflow. Use when: writing or updating README, generating project documentation, documenting architecture, documenting API endpoints, documenting infrastructure, producing technical or non-technical documentation."
argument-hint: "Describe what to document (e.g. 'Update the README with the new API endpoints' or 'Regenerate the full README')"
---

# Documentation Skill

## Purpose

Produce or update `README.md` (or another specified doc file). Every fact comes from the codebase — nothing is invented.

> **Security — non-negotiable**: Documentation is public. Never include secrets, credentials, API keys, tokens, private URLs, internal IPs, account IDs, or private ARNs. Reference environment variable names only. When in doubt, omit.

<rules>
- Use #tool:vscode/askQuestions if the scope (FULL vs. PARTIAL, target sections) is unclear before starting
- Never invent details — every fact must come from Phase 2 codebase research; if a value is not found, write `N/A — not found in codebase`
- Never leave placeholder sections — a section without real content is omitted entirely
- For PARTIAL scope, only edit the named sections; preserve all other content byte-for-byte
- Run a security scan before saving — remove any secret, private URL, account ID, or internal ARN
- Never edit any file other than the target documentation file
</rules>

<workflow>
## Phase 1 — Scope Assessment

1. Use #tool:vscode/askQuestions if the scope or target file is unclear.
2. Classify the scope from the user's request:
   - `FULL` — regenerate or create from scratch
   - `PARTIAL` — update only named sections
3. Read the existing document top to bottom. Record: which sections exist, which are outdated, which are missing.
4. For `PARTIAL`, restrict all changes to the named sections only.

Quality check:
- [ ] Scope classified as `FULL` or `PARTIAL`
- [ ] Existing document read in full
- [ ] Delta list (outdated / missing / unchanged) recorded

---

## Phase 2 — Codebase Reference Gathering

Collect all facts before writing a single line. If a project-specific source map exists at `./references/source-map.md`, use it as the primary guide.

### 2.1 Infrastructure / Cloud
Read infrastructure config files (CDK stacks, Terraform, serverless). Record every cloud resource: name, type, purpose.

### 2.2 Application Layer
Read source files:
- Entry points (controllers, handlers, routes) → method, path, auth, request/response types
- Services / business logic → public method summaries
- Data access / external clients → data patterns, external calls
- Configuration → all env vars referenced

### 2.3 Build & Runtime
Read the build descriptor (`build.gradle`, `package.json`, etc.): dependencies, build commands, test commands.

### 2.4 Local Development
Read local dev setup files (Docker Compose, SAM, scripts):
- How to run locally
- Required env vars — use the `.example` file, never the real env file
- Exposed ports and services

### 2.5 API Surface
Read integration/E2E test files (Bruno `.bru`, Postman, OpenAPI) to confirm the real API surface:
- Endpoints, methods, auth requirements, request/response shapes, error cases

### 2.6 Security & Auth
Search for auth annotations, JWT filters, OIDC config, and security middleware. Note public vs. protected endpoints.

Quality check:
- [ ] All infrastructure resources recorded
- [ ] All endpoints compiled from source + tests
- [ ] All env vars compiled from the `.example` file
- [ ] Auth and security patterns identified

---

## Phase 3 — Write the Documentation

If a project-specific section template exists at `./references/readme-sections.md`, follow it. Otherwise use this default structure:

1. **Project title, badge row, one-line summary**
2. **Table of Contents**
3. **What Is This Project?** — plain language, no jargon
4. **Architecture Overview** — diagram (draw.io XML in fenced `xml`, or Mermaid in fenced `mermaid`) + prose
5. **Tech Stack** — concise table
6. **Getting Started** — prerequisites, clone, configure env, run locally
7. **API Reference** — all endpoints (method, path, auth, request/response)
8. **Infrastructure** — all cloud resources, deployment commands
9. **Security** — auth flow, protected vs. public endpoints
10. **Testing** — how to run unit, integration, and E2E tests

Add or remove sections based on what the project actually contains. Never add a section with no real content.

Core rules:
- **Never invent** — if a value is not found, write `N/A — not found in codebase`
- **No placeholders** — omit sections without real content
- **Diagrams** — draw.io XML (fenced `xml`) or Mermaid (fenced `mermaid`); no empty diagrams
- **Commands** — fenced `bash` blocks; every command must be directly runnable
- **Tables** — use for tech stack, cloud resources, API endpoints, env vars

---

## Phase 4 — Validation

Before saving, self-review:

1. All Table of Contents anchors resolve to actual headings
2. All commands verified against source files and use values from the `.example` file
3. All resource names match infrastructure files
4. All endpoint paths match controller mappings or test collection entries
5. Any diagram is non-empty and well-formed
6. Security scan: remove any secret, private URL, account ID, or internal ARN

Quality check:
- [ ] All ToC anchors resolve
- [ ] Commands verified against source
- [ ] Resource names verified against infrastructure files
- [ ] Endpoints verified against controllers and tests
- [ ] Security scan passed — no secrets or private values present

---

## Phase 5 — Save and Report

1. Write the final content to the documentation file.
2. Output the run report:

```
## Documentation — Run Report

**Scope**: FULL | PARTIAL (<section names>)
**Outcome**: SUCCESS | PARTIAL SUCCESS | FAILURE

### Changes Made
| Section | Action |
|---|---|
| <Section Name> | Created / Updated / Unchanged / Removed |

### Gaps (sections with incomplete data)
| Section | Missing Information | Recommended Action |
|---|---|---|
| <Section Name> | <what was missing> | <what to provide> |
```
</workflow>
