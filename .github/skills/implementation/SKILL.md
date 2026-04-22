---
name: implementation
description: "Code implementation workflow. Use when: writing new code, adding endpoints or services, modifying existing behaviour, writing tests, fixing bugs, adding infrastructure. Reads the plan from session memory and implements each step following project conventions."
argument-hint: "Point to the plan in session memory or describe what to implement"
---

# Implementation Skill

## Purpose

Implement every step from the plan in session memory, following project conventions, writing tests, and verifying the result.

<rules>
- Read `/memories/session/plan.md` via #tool:vscode/memory before writing any code — a plan must exist
- If no plan exists and the request is ambiguous, stop and trigger the planning skill first
- Load `.github/copilot-instructions.md` before writing project-specific code
- Load `references/conventions.md` and `references/package-map.md` if they exist in this skill's folder
- Never create a new file if an existing one in the same domain is a suitable fit
- Never invent abstractions not required by the current task
- Never skip or comment out failing tests — fix them before moving on
- Never deploy or push without explicit user confirmation
- Update `/memories/session/plan.md` via #tool:vscode/memory when all steps are complete
</rules>

<workflow>
## Phase 0 — Load the Plan

1. Read `/memories/session/plan.md` via #tool:vscode/memory.
2. If no plan is found and the request is not self-contained, stop: activate the **planning** skill first.
3. Extract every `- [ ]` step into a todo list. Mark items **in-progress** one at a time.

---

## Phase 1 — Announce & Track

1. Create the full todo list from the plan steps.
2. Post: `Starting implementation. N steps queued.`
3. After completing each step: `✅ Step N — <what changed> (<ClassName>, <method>)`
4. Mark the todo **completed** and the next **in-progress**.

---

## Phase 2 — Explore Before Writing

For every task step, read before you write:

1. Delegate to the `Explore` subagent if comprehensive codebase context is needed.
2. Otherwise, search for the nearest existing class in the same domain.
3. Read its constructor, field declarations, and error handling before writing anything new.
4. Only create a new file if no existing class is a suitable fit.

Project conventions are in `.github/copilot-instructions.md`. If `references/conventions.md` or `references/package-map.md` exist in this skill's folder, load them too.

---

## Phase 3 — Implement

Write code that:
- Matches the indentation and style of adjacent files
- Uses the project's DI pattern (see `.github/copilot-instructions.md`)
- Uses config binding for all values — no hardcoded strings
- Uses the same error types already in use at the same layer
- Adds routes/handlers inside the appropriate existing file when the domain matches

---

## Phase 4 — Write Tests

For every changed or new class:
- Create or update the corresponding test class
- Read a nearby test file before writing to match the project's test patterns
- Mock external dependencies (network clients, cloud SDKs)
- Name tests: `methodName_condition_expectedResult`
- Every new public method → at least one test; every new endpoint → success case + at least one error case

If `references/test-patterns.md` exists in this skill's folder, load it for language-specific examples.

---

## Phase 5 — Verify

After each step, run only the tests for the class you just changed. Use the project's test runner. Report the result.

If a test fails:
1. Read the failure output carefully.
2. Fix the source or the test — whichever is wrong.
3. Do NOT skip or comment out failing tests.

Run the full test suite after all steps are complete.

---

## Phase 6 — Infrastructure (if needed)

Edit the appropriate infrastructure file for any new cloud resources required. **Never deploy without explicit user approval.**

---

## Phase 7 — Update Plan in Memory

Update `/memories/session/plan.md` via #tool:vscode/memory:
- Set `**Status**: ✅ Complete`
- Mark all completed checkboxes

---

## Phase 8 — Final Summary

Post in this exact format:

```
## Implementation Complete

### Files Changed
| File | Action |
|------|--------|
| <path/to/File> | Created |
| <path/to/Other> | Modified |

### Tests Added / Updated
| Test Class | Cases Added |
|------------|-------------|
| <TestClass> | N |

### Out of Scope (follow-up)
- <anything explicitly not done>
```
</workflow>
```
