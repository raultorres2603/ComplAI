---
name: review
description: "Implementation validation workflow. Use when: implementation is complete and needs to be verified against the plan, tests need to be confirmed passing, code quality needs auditing, or a pass/fail verdict is needed before documenting. Reads the plan from session memory."
argument-hint: "Describe what was implemented (e.g. 'Review the notification endpoint just implemented')"
---

# Review Skill

## Purpose

Validate that the implementation satisfies every acceptance criterion in the plan, passes all tests, and follows project conventions. Produce a structured PASS/FAIL report.

<rules>
- Read the plan from `/memories/session/plan.md` via #tool:vscode/memory before doing anything else
- Never edit source files — produce a report only
- Never fix code yourself — describe problems precisely so the implementation phase can be retried
- Never approve a failing build — a single red test is always FAIL, no exceptions
- Every gap must name the exact file, method, and expectation that was not met
- If no plan exists in session memory, report BLOCKED and stop
</rules>

<workflow>
## Phase 0 — Load Inputs

1. Read `/memories/session/plan.md` via #tool:vscode/memory. Extract every `- [ ]` acceptance criterion as the review checklist.
2. Read the implementation summary from context — list of files created/modified.
3. Read each changed source file before running any tests.

If no plan is found:
```
❌ BLOCKED — No plan found in session memory. Cannot validate without an acceptance checklist.
```

---

## Phase 1 — Acceptance Criteria Check

For every criterion in the plan, verify it is addressed in the implementation.

| Check | How to verify |
|-------|---------------|
| Each required file exists | Search for it by class name or path |
| Each required method/endpoint is present | Read the class and confirm the method signature |
| Each required test class exists | Search the test directory for the corresponding test file |
| No criterion left unimplemented | Map every `- [ ]` line to at least one changed file |

Record findings as a gap list. A gap is any criterion with no corresponding implementation evidence.

---

## Phase 2 — Code Quality Check

For each file the implementation created or modified, verify against project conventions from `.github/copilot-instructions.md`:

| Rule | What to check |
|------|--------------|
| Dependency injection style | Matches project conventions (e.g. constructor injection, no field injection) |
| No hardcoded config values | Config values use the project's binding mechanism, not literal strings |
| No speculative abstractions | No new interfaces, base classes, or helpers not required by the task |
| Existing error types used | No new exception classes where existing ones already cover the case |
| No pushed/deployed changes | No `git push`, deploy commands, or destructive operations in the session log |

Record each violation with: file path, rule violated, brief explanation.

---

## Phase 3 — Test Execution

Run the full test suite using the project's test runner. Capture the result:

- All tests pass → proceed to Phase 4
- Any test fails → record the failing test name and error message verbatim; this is an automatic **FAIL** — skip Phase 4 and go to Phase 5

If the build fails to compile, record the error and treat it as a test failure.

---

## Phase 4 — Coverage Adequacy Check

After a green build:

| Requirement | How to check |
|-------------|-------------|
| Every new public method has at least one test | Search test directory for a matching test class |
| Every new endpoint has a success test + at least one error case | Count test methods covering the endpoint |

Record any untested method as a coverage gap (not an automatic FAIL, but include in the report).

---

## Phase 5 — Emit the Validation Report

Always emit this report, whether passing or failing.

```
## Review — Validation Report

### Verdict: PASS | FAIL | PASS WITH WARNINGS

### Acceptance Criteria
- [x] <criterion> — implemented in <FileName>
- [ ] <criterion> — GAP: <why it is missing or incomplete>

### Code Quality
- ✅ No violations found
  OR
- ⚠️ <file> — <rule violated>: <explanation>

### Test Results
- ✅ All N tests passed
  OR
- ❌ N test(s) failed:
    - <TestClass#method>: <error message>

### Coverage Gaps
- ⚠️ <ClassName#method> — no test found
  OR
- ✅ All new public methods are covered

### Summary
<2–4 sentences: overall assessment, most critical gap (if any), recommended next action>
```

---

## Verdict Rules

| Condition | Verdict |
|-----------|---------|
| All criteria met, all tests pass, no quality violations | **PASS** |
| All criteria met, all tests pass, coverage gaps or minor warnings only | **PASS WITH WARNINGS** |
| Any criterion unmet, OR any test failing, OR any quality violation | **FAIL** |
</workflow>
