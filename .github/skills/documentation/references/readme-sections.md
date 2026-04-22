# README Sections — Required Structure and Content Rules

Each section below is **required** unless marked `(conditional)`. Sections must appear in this order.

---

## 1. Title Block

```markdown
# <Project Name>

© <year> <Author>. All rights reserved.  
LinkedIn: [Profile](<url>)

Last version: [![GitHub Release](https://img.shields.io/github/v/release/<owner>/<repo>)](https://github.com/<owner>/<repo>/releases/latest)

> **<Branding Name>** — one-sentence plain-language summary of what the product does for end users.
```

Source: existing README title block + repo metadata from `settings.gradle` / `gradle.properties`.

---

## 2. Table of Contents

Auto-linked anchors to every section heading. Update every time a section is added or removed.

---

## 3. What Is This Project?

- 3–5 short paragraphs.
- Audience: citizen / product manager (no technical jargon).
- Cover: what the product does, who uses it, in what context, and what benefit it provides.
- List the three main capabilities as bullet points (answer local questions, explain procedures, draft complaints, etc.).
- Mention language support (Catalan / Spanish / English).

---

## 4. Vision and Goals

A Markdown table with two columns: **Goal** and **How ComplAI addresses it**.  
Derive goals from the README, `task.md`, or `.github/copilot-instructions.md`.

---

## 5. Architecture Overview

Two parts:

**5a. Draw.io diagram** (fenced `xml` block):
- Nodes: Frontend client → API Gateway (if present) → Lambda → Micronaut app → [SQS queues] → [S3 bucket] → OpenRouter AI.
- Use actual resource names from CDK stacks.
- Must be a complete, non-empty `.drawio` XML document.

**5b. Prose description** (after the diagram):
- One paragraph per layer: client, API, application logic, AWS services, AI.
- Reference actual CDK stack names (e.g., `ComplAiLambdaStack`).

---

## 6. Tech Stack

Markdown table with columns: **Layer**, **Technology**, **Version / Notes**.

Rows to include (add more if found):

| Layer | Technology | Notes |
|---|---|---|
| Language / Runtime | Java 25 | |
| Framework | Micronaut | version from `gradle.properties` |
| Build Tool | Gradle | version from `gradle/wrapper/gradle-wrapper.properties` |
| Cloud | AWS (Lambda, SQS, S3) | |
| IaC | AWS CDK (TypeScript) | |
| AI Integration | OpenRouter | model name from source |
| Search / RAG | In-memory lexical RAG (`InMemoryLexicalIndex`) | no external Lucene dependency |
| Caching | Caffeine | |
| PDF Generation | Apache PDFBox | |
| Auth | JJWT + Micronaut Security | |
| Testing | JUnit 5 + Mockito + Bruno | |

---

## 7. Getting Started

### Prerequisites

Bullet list: Java 25, Docker, AWS CLI, SAM CLI, Node.js (for CDK), Bruno.

### Clone & Configure

```bash
git clone https://github.com/<owner>/<repo>.git
cd <repo>
cp sam/env.json.example sam/env.json
# Edit sam/env.json with your values (see Environment Variables section)
```

### Run Locally

Exact commands from `sam/start-local.sh` and `sam/docker-compose.yml`:

```bash
# Start local AWS services
docker compose -f sam/docker-compose.yml up -d

# Start the Lambda / API locally
# <exact command from start-local.sh>
```

### Environment Variables

Markdown table: **Variable**, **Description**, **Example value**.  
Source: `sam/env.json.example` and `src/main/resources/application.properties` `${...}` placeholders.

---

## 8. API Reference

Markdown table: **Method**, **Path**, **Auth Required**, **Description**, **Request Body**, **Response**.

Source: controllers in `src/main/java/**` + Bruno collections in `E2E-ComplAI/`.

- List happy-path endpoints from `02-OK/` first.
- List error cases from `03-ERROR/` as a separate sub-table or footnote.
- Auth column: `JWT Bearer` / `None`.

---

## 9. Infrastructure

### AWS Resources

Markdown table: **Resource**, **Type**, **CDK Stack**, **Purpose**.  
Source: `cdk/lambda-stack.ts`, `cdk/queue-stack.ts`, `cdk/storage-stack.ts`.

### Deployment

```bash
cd cdk
npm install
npx cdk deploy --all
```

Add any environment-specific notes from `cdk/deployment-environment.ts`.

---

## 10. Security

- JWT Bearer auth: how tokens are validated (JJWT + Micronaut Security filter).
- Which endpoints are public (`@Secured(SecurityRule.IS_ANONYMOUS)`) vs. protected (`@Secured(SecurityRule.IS_AUTHENTICATED)`).
- Brief note on OIDC providers (full detail in section 15).

---

## 11. Testing

### Unit & Integration Tests

```bash
./gradlew test
```

### CI Tests (if separate task exists)

```bash
./gradlew ciTest
```

### E2E Tests (Bruno)

- Install Bruno CLI or open the Bruno desktop app.
- Import `E2E-ComplAI/bruno.json`.
- Select environment (`Local` or `Development`).
- Run the `02-OK` collection for happy-path validation.

---

## 12. Performance Optimizations

Two sub-sections derived from codebase search:

**Caffeine Cache**: what is cached (conversations, procedures, events), TTL/max-size if configured.  
**In-memory RAG**: what is indexed, how queries are formed (see `InMemoryLexicalIndex`, `LexicalScorer`, `TokenNormalizer`).

---

## 13. Conversation History (Multi-turn)

- How a `conversationId` is created and used across requests.
- Where history is stored (cache class / bean).
- TTL and eviction policy.

Source: cache-related classes and controllers.

---

## 14. PDF Complaint Generation

- What user input triggers it (endpoint + payload).
- How ComplAI builds the letter (PDFBox classes).
- Where the PDF is stored (S3 bucket name from CDK).
- How to retrieve or download it.

---

## 15. OIDC Identity Verification

- Supported identity providers: Cl@ve, VALId, idCat (confirm from config/code).
- Validation flow: token receipt → OIDC discovery → signature verification → claim extraction.
- Config keys from `application.properties`.

---

## 16. AI Identity and Behaviour

- Model used (from OpenRouter integration in source).
- Language detection and response language logic.
- System prompt strategy (summarise — do not paste the full prompt).
- Guardrails: what the assistant refuses to answer (if defined in source).

---

## 17. Contributing

- Branch strategy (feature branches off `master`).
- Code style: constructor injection only, Micronaut conventions, Javadoc on all public methods.
- How to use the Copilot agents:
  - `@planner` for planning a new feature.
  - `@builder` for implementing a planned feature.
  - `@documentator` for keeping this README up to date.
- PR guidelines: all tests must pass, `./gradlew test` green before opening PR.

---

## 18. License

Reproduce the exact license block from the existing `README.md` or `LICENSE` file. Do not paraphrase.
