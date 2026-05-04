---
name: planning
description: "Requirements analysis and planning workflow. Use when: the request is unclear, a new feature needs to be broken down, implementation steps need to be created, or acceptance criteria need to be defined. Produces a structured plan in session memory. Uses vscode/askQuestions to clarify ambiguities interactively."
argument-hint: "Describe the feature or requirement to plan"
compatibility: opencode
---

# Planning Skill

## Purpose

Produce a structured, fully defined plan and save it to session memory. This skill drives everything that happens before a single line of code is written.

<rules>
- Use #tool:vscode/askQuestions freely — never assume scope, acceptance criteria, or affected files
- The only write operation in this skill is saving to #tool:vscode/memory — do NOT edit source files
- Group all clarifying questions into a single #tool:vscode/askQuestions call per ambiguity round
- Do not proceed to Codebase Exploration until all blocking ambiguities from Requirements Analysis are resolved
- Do not proceed to Write Plan until all phases above it are complete
- Always show the plan to the user after saving — the memory file is not a substitute for displaying it
</rules>

<workflow>
## Phase 1 — Requirements Analysis

**Goal**: Turn fuzzy input into a precise, documented requirement set.

1. Restate the requirement in your own words in 2–4 sentences. Confirm scope (what is in, what is out).
2. Identify the requirement type:
   - New feature → new files + tests expected
   - Behaviour change → modifications + regression tests
   - Infrastructure change → config/stack changes
   - Bug fix → root-cause analysis before steps
   - Documentation-only → README or doc file changes
3. Use #tool:vscode/askQuestions for every unclear point. Group all questions in one call. **Do not continue until answers are received.**
4. Extract acceptance criteria — what conditions must be true for this to be "done"? These become the review checklist.

Quality check:
- [ ] Requirement restated unambiguously
- [ ] All ambiguities resolved via #tool:vscode/askQuestions
- [ ] Acceptance criteria listed

---

## Phase 2 — Codebase Exploration

**Goal**: Map the exact files and patterns affected by the requirement.

1. Delegate to the `Explore` subagent if the scope is complex or requires a broad codebase survey. Otherwise use targeted search.
2. Locate the entry point: handler, controller, or service closest to the requirement domain.
3. Trace the call chain from entry to storage/infrastructure layer.
4. Identify existing patterns to follow: DI style, error handling conventions, config binding, DTO patterns.
5. List every file that will be created or modified.
6. Flag risks: shared utilities, cross-cutting concerns, files likely to conflict across tasks.

Quality check:
- [ ] Entry point identified
- [ ] Full call chain mapped
- [ ] All affected files listed
- [ ] Existing patterns noted

---

## Phase 3 — Web Research (Conditional)

Trigger only when any of the following is true:
- A new library or SDK is being introduced
- A cloud service interaction pattern is needed
- A framework feature not visible in the codebase is required
- The requirement involves security, cryptography, or compliance

1. Search official documentation first.
2. Confirm version compatibility against the project's dependency manifest.
3. Record each reference URL in the plan's `## References` section.
4. Note the exact dependency string if a new library must be added.

Quality check:
- [ ] Authoritative source consulted (if triggered)
- [ ] Version compatibility confirmed
- [ ] Dependency string noted (if applicable)

---

## Phase 4 — Write Plan to Session Memory

Before writing, classify each planned task:
- **Independent** — no prerequisites; can start immediately
- **Dependent** — requires one or more other tasks; annotate with `**Requires**: Task N — <title>`

Save the plan to `/memories/session/plan.md` using #tool:vscode/memory with the structure below. Then **show the plan to the user in chat** — the memory file is for persistence, not display.

```markdown
# <Task Title>

> **Status**: 🟡 In Planning
> **Date**: <YYYY-MM-DD>

## Requirements Summary

<2–4 sentence restatement of what needs to be built and why>

## Clarifications Received

| # | Question asked | Answer received |
|---|---|---|
| 1 | <question text> | <user's answer> |

## Affected Areas

| Layer | File / Class | Change type |
|---|---|---|
| <Layer> | `<path/to/File>` | Create / Modify |

## Acceptance Criteria

- [ ] <Condition that must be true for this to be done>

## Independent Tasks

> Tasks here have no dependencies and **may be executed concurrently**.

### Task 1: <Title>

**Description**: <What this task accomplishes and why>

**Steps**:
- [ ] <Atomic step — name the exact file/class to touch>

**Progress**: 0 / N steps completed

## Dependent Tasks

> Tasks here depend on one or more prerequisites. Do not start until all listed prerequisites are complete.
> If none: `> None — all tasks in this plan are independent.`

### Task 2: <Title>

**Requires**: Task 1 — <Exact heading of Task 1>

**Description**: <What this task accomplishes and why>

**Steps**:
- [ ] <Atomic step>

**Progress**: 0 / N steps completed

## Testing Plan

- [ ] <What to test> — <expected behaviour>

## References

- <URL if Phase 3 was triggered>
```

Quality check:
- [ ] Plan saved to `/memories/session/plan.md` via #tool:vscode/memory
- [ ] Tasks classified as Independent or Dependent
- [ ] Dependent tasks carry `**Requires**:` annotation
- [ ] Acceptance criteria map to the requirement
- [ ] Testing plan covers every new behaviour

---

## Phase 5 — Present Plan to User

Show the plan in chat using this format, then wait for user approval or change requests:

```
## Plan Ready

**Summary**: <2 sentences describing what will be built>

**Tasks**:
- Task 1 — <title> (independent)
- Task 2 — <title> (depends on Task 1)

**Acceptance criteria**: N items defined.
```

On user feedback: revise plan, update `/memories/session/plan.md` via #tool:vscode/memory, and present the updated version. Repeat until explicit approval.
</workflow>
