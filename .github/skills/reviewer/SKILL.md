---
name: reviewer
description: "Validation workflow for the Reviewer agent. Use when: reviewing builder output against task.md, checking acceptance criteria, verifying tests pass, auditing implementation completeness, producing a pass/fail report for the orchestrator. Covers the full review checklist, verdict format, and gap-reporting rules."
argument-hint: "Describe what was implemented and where task.md is (e.g. 'Review the PDF export endpoint from task.md')"
---

# Reviewer Skill — ComplAI Implementation Validation Workflow

## When to Use
- The `builder` agent has finished and the `orchestrator` needs a quality gate before proceeding
- A human or orchestrator wants confirmation that an implementation satisfies `task.md`
- The orchestrator is deciding whether to retry the builder or move on to `documentator`

---

## Phase 0 — Load Inputs

Before doing anything else, read all of the following:

1. **`task.md`** at the workspace root — extract every checkbox item (`- [ ]` or `- [x]`) as the acceptance checklist.
2. **The builder's output summary** (provided in the delegation prompt) — list of files created/modified.
3. **Related source files** — read each file the builder touched before running any tests.

If `task.md` does not exist, report immediately:
```
❌ BLOCKED — task.md not found. Cannot validate without an acceptance checklist.
```
and stop.

---

## Phase 1 — Structural Completeness Check

For every checkbox item in `task.md`, verify it is addressed in the implementation.

| Check | How to verify |
|-------|---------------|
| Each required file exists | Search for it by class name or path |
| Each required method/endpoint is present | Read the class and confirm the method signature |
| Each required test class exists | Search `src/test/` for the corresponding test file |
| No task item left unimplemented | Map every `- [ ]` line to at least one changed file |

Record findings as a gap list. A gap is any task item that has no corresponding implementation evidence.

---

## Phase 2 — Code Quality Check

For each file the builder created or modified, verify the following against project conventions:

| Rule | Violation signal |
|------|-----------------|
| Constructor injection only | `@Inject` on a field (not a constructor parameter) |
| No hardcoded config values | String literals where a `@Value` or `@ConfigurationProperties` binding should be used |
| No speculative abstractions | New interfaces, base classes, or helpers that are not referenced by the task |
| Existing error types used | New exception classes introduced when existing ones cover the case |
| No pushed/deployed changes | Any `git push`, `cdk deploy`, or `./gradlew publish` in the builder's execution log |

Record each violation with: file path, line reference (if readable), rule violated, brief explanation.

---

## Phase 3 — Test Execution

Run the full test suite:

```
./gradlew test
```

Capture the result:
- **All tests pass** → proceed to Phase 4
- **Any test fails** → record the failing test name, class, and error message verbatim; this is an automatic FAIL verdict — skip Phase 4 and go directly to Phase 5

If the build itself fails to compile, record the compiler error and treat it as a test failure.

---

## Phase 4 — Coverage Adequacy Check

After a green build, verify test coverage for new code:

| Requirement | How to check |
|-------------|-------------|
| Every new public service method has at least one unit test | Search `src/test/` for a test class matching the service class name |
| Every new controller endpoint has a success test + at least one error/edge-case test | Read the test class and count test methods covering the endpoint |
| AWS wrapper classes are tested via the protected no-arg constructor pattern | Confirm the test subclasses the wrapper without triggering real AWS init |

Record any untested method as a coverage gap (not an automatic FAIL, but must be included in the report).

---

## Phase 5 — Emit the Validation Report

Always emit this report, whether passing or failing.

```
## Reviewer Validation Report

### Verdict: PASS | FAIL | PASS WITH WARNINGS

### Acceptance Checklist
- [x] <task item> — implemented in <ClassName.java>
- [ ] <task item> — GAP: <why it is missing or incomplete>
(repeat for every task.md checkbox)

### Code Quality
- ✅ No violations found
  OR
- ⚠️ <file>:<reference> — <rule violated>: <explanation>
(list all violations, or "No violations found")

### Test Results
- ✅ All N tests passed
  OR
- ❌ N test(s) failed:
    - <TestClassName#methodName>: <error message>

### Coverage Gaps
- ⚠️ <ClassName#methodName> — no test found
  OR
- ✅ All new public methods are covered

### Summary
<2-4 sentences: overall assessment, most critical gap (if any), and recommended next action>
```

---

## Verdict Rules

| Condition | Verdict |
|-----------|---------|
| All checklist items implemented, all tests pass, no quality violations | **PASS** |
| All checklist items implemented, all tests pass, coverage gaps or minor quality warnings only | **PASS WITH WARNINGS** |
| Any checklist item unimplemented, OR any test failing, OR any quality violation that contradicts a project constraint | **FAIL** |

### What the Orchestrator does with the verdict

| Verdict | Orchestrator action |
|---------|---------------------|
| PASS | Mark builder step completed, continue to next agent |
| PASS WITH WARNINGS | Mark builder step completed with caveats noted in summary |
| FAIL | Trigger builder retry using gaps/failures from the report as the corrective prompt |

---

## Constraints

- **Never edit files** — produce a report only
- **Never fix the code yourself** — describe the problem precisely and let the builder fix it
- **Never approve a failing build** — a single red test is always FAIL, no exceptions
- **Be specific** — vague gap descriptions ("tests are missing") are not actionable; always name the exact class and method
