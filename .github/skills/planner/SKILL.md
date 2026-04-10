---
name: planner
description: "Detailed planning workflow for the Planner agent. Use when: analyzing requirements, exploring codebase (via Explore agent), researching best practices, producing task.md, creating implementation plan, breaking down tasks, planning features, writing steps, requirement analysis."
argument-hint: "Describe the feature or requirement to plan"
---

# Planner Skill

## Purpose

Produce a structured, fully populated `task.md` at the workspace root from a given requirement. This skill defines every decision point, branching condition, and quality check the Planner agent must follow.

---

## Phase 1 — Requirements Analysis

**Goal**: Turn fuzzy input into a precise, documented requirement set.

### Steps

1. **Restate** the requirement in your own words in 2–4 sentences. Confirm scope (what is in, what is out).
2. **Identify requirement type**:
   - New feature → expect new classes + tests
   - Change to existing behaviour → expect modifications + regression tests
   - Infrastructure change → expect CDK stack changes
   - Bug fix → expect root-cause analysis before steps
3. **Surface ambiguities**: For every unclear point, **stop and ask the user** before proceeding. Do NOT assume — list each unclear point as a numbered question and wait for answers. Only continue to the next phases once all blocking ambiguities are resolved.
4. **Extract acceptance criteria**: What conditions must be true for this task to be "done"? List them — they become the testing plan.

### Quality Check

- [ ] Requirement is restated unambiguously
- [ ] All ambiguities were clarified with the user (no open questions remain)
- [ ] Acceptance criteria are listed

---

## Phase 2 — Codebase Exploration

**Goal**: Map the exact files and patterns affected by the requirement.

### Steps

1. **Decide whether to delegate to Explore**: If the requirement scope is complex or you need a thorough codebase survey to plan effectively, use the `Explore` agent to gather context. Otherwise, proceed with targeted searches in steps 2–5.
2. **Locate entry point**: Search for the controller, handler, or SQS listener closest to the requirement domain.
3. **Trace the call chain** down to repository / infrastructure layer:
   - Controller → Service → Repository / AWS client
4. **Identify existing patterns** to follow:
   - Constructor injection style (never field injection)
   - Error handling conventions (`@Error` handlers, exception types used)
   - Configuration binding (`@Value`, `@ConfigurationProperties`)
   - DTO / record patterns in use
5. **List affected files**: Every file that will be created or modified.
6. **Flag risks**: Shared utilities, cross-cutting concerns, or files touched by multiple features simultaneously.

### Search Strategy

| What to find | Tool hint |
|---|---|
| Controller for domain X | search `*Controller*` + domain keyword |
| Service interface/impl | search `*Service*` + domain keyword |
| Config keys | search `application.properties` for relevant prefix |
| Existing test patterns | read a nearby `*Test.java` file |
| CDK stacks | read files in `cdk/` |

### Quality Check

- [ ] Entry point file identified
- [ ] Full call chain mapped
- [ ] All affected files listed
- [ ] Existing patterns noted (DI, error handling, config style)

---

## Phase 3 — Web Research (Conditional)

**Trigger**: Run this phase only when any of the following is true:
- A new library or SDK is being introduced
- An AWS service interaction pattern is needed
- A Micronaut feature not obviously present in the codebase is required
- The requirement involves security, cryptography, or compliance

### Steps

1. Search official docs first (Micronaut, AWS SDK v2, Gradle).
2. Note the version in use (`gradle.properties`) before searching — avoid version mismatch.
3. Record each reference URL in the plan's `## References` section.
4. If a library needs to be added, note the Gradle dependency string exactly.

### Quality Check

- [ ] At least one authoritative source consulted (if phase was triggered)
- [ ] Version compatibility confirmed
- [ ] Gradle dependency string noted (if applicable)

---

## Phase 4 — Write `task.md`

Overwrite `task.md` at the workspace root entirely. Use the exact structure below.

### `task.md` Structure

```markdown
# <Task Title>

> **Status**: 🟡 In Planning  
> **Date**: <YYYY-MM-DD>  
> **Branch suggestion**: <feature/short-slug>

---

## Requirements Summary

<2–4 sentence restatement of what needs to be built and why>

---

## Clarifications Received

| # | Question asked | Answer received |
|---|---|---|
| 1 | <question text> | <user's answer> |

---

## Affected Areas

| Layer | File / Class | Change type |
|---|---|---|
| Controller | `src/main/java/.../XController.java` | Create / Modify |
| Service | `src/main/java/.../XService.java` | Create / Modify |
| Repository | `src/main/java/.../XRepository.java` | Create / Modify |
| Config | `src/main/resources/application.properties` | Modify |
| CDK | `cdk/xxx-stack.ts` | Create / Modify |
| Tests | `src/test/java/.../XTest.java` | Create / Modify |

---

## Tasks

### Task 1: <Title>

**Description**: <What this task accomplishes and why it exists>

**Steps**:
- [ ] <Atomic step — name the exact file/class to touch>
- [ ] <Next step>
- [ ] <...>

**Progress**: 0 / N steps completed

---

### Task 2: <Title>

**Description**: <What this task accomplishes and why it exists>

**Steps**:
- [ ] <Atomic step>
- [ ] <...>

**Progress**: 0 / N steps completed

---

## Testing Plan

### Unit Tests
- [ ] <Class to test> — <what behaviour to assert>

### Integration Tests
- [ ] <Scenario> — <expected outcome>

### E2E (Bruno)
- [ ] <`.bru` file to create or update> — <endpoint + scenario>

---

## Open Questions / Blockers

| # | Question / Blocker | Owner | Blocking task(s) |
|---|---|---|---|
| 1 | <question or blocker> | Dev / PM / Infra | Task N |

> If none: write "None — plan is unblocked."

---

## References

- [<Title>](<URL>)
```

### Mermaid Diagram Requirement

If the task involves creating or updating **Architecture Overview** documentation or any system architecture planning:
- A Mermaid diagram (flowchart or architecture graph) **MUST** be included in the `task.md`.
- The diagram must render correctly in GitHub Markdown.
- It must represent **actual** codebase structure (layers: Controllers → Services → Repositories, AWS integrations, data flows, etc.).
- No unverified or speculative components.
- Diagrams ensure clarity for both implementation and documentation agents.

### Rules for writing tasks

- Each task maps to **one concern** (one layer, one domain concept).
- Steps must be **atomic**: one file, one method, one config key per checkbox.
- Step text format: `verb + what + where` — e.g., `Add validateToken() method to JwtService.java`.
- Do NOT group unrelated changes into one task.
- Ordering must respect dependencies: if Task 2 uses something from Task 1, Task 1 comes first.

### Progress field

Set `Progress` to `0 / N steps completed` when first writing the plan. The implementing agent will update this field as work proceeds.

---

## Phase 5 — Output Summary to User

After `task.md` is written, respond with exactly this format (do not skip any section):

```
## Plan Written ✓

**Requirement:** <one-line restatement>

**Tasks planned:** <N> — <comma-separated task titles>

**Total steps:** <count across all tasks>

**Blockers / Open Questions:**
- <item>   ← or "None — plan is unblocked."

**Clarifications received:**
- <item>   ← or "None required"

**Next step:** task.md is ready at the workspace root. Hand off to an implementation agent to execute the tasks in order.
```

---

## Constraints (Non-Negotiable)

- **No source edits.** Never create or modify `.java`, `.ts`, `.gradle`, or any resource file.
- **No code generation.** Code snippets in `task.md` are illustrative only.
- **No shell commands.** Do not run builds, tests, or terminal commands.
- **Clarify before planning.** If anything is ambiguous, ask the user before writing `task.md`. Do NOT proceed with unresolved unclear points.
- **Always overwrite `task.md` completely.** Do not append partial updates.
