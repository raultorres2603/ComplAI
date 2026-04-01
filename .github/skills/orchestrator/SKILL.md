---
name: orchestrator
description: "Detailed orchestration workflow for the Orchestrator agent. Use when: running a multi-agent flow, delegating to planner/builder/documentator, applying retry policy after sub-agent failure, handling blocked agents, validating sub-agent outputs, synthesizing a final summary. Covers intake, delegation plan, per-agent output validation, retry logic, escalation rules, and final summary format."
argument-hint: "Describe what the user wants to achieve (e.g. 'Add a complaint export endpoint with tests and updated docs')"
---

# Orchestrator Skill — ComplAI Multi-Agent Workflow

## When to Use
- The request spans more than one specialist agent (e.g. plan + build, or build + document)
- You need a structured retry policy when a sub-agent fails or returns incomplete output
- You need clear escalation rules before involving the user

---

## Phase 0 — Intake & Clarification

Before touching any agent, fully understand the request.

1. Read the user's message in full.
2. Check every dimension below. If **any** is unclear, ask a targeted question — one question per ambiguity, grouped in a single message:

   | Dimension | Question to ask if unclear |
   |-----------|---------------------------|
   | Scope | Which module(s) / endpoint(s) / domain(s) are affected? |
   | Acceptance criteria | What is the definition of "done"? |
   | Tests | Are unit/integration tests required? |
   | Docs | Does the README or any other doc need updating? |
   | Infrastructure | Are AWS/CDK changes needed? |
   | Constraints | Any deadlines, backward-compatibility requirements, or things to avoid? |

3. Do **not** proceed to Phase 1 until every open question has an answer.
4. If the request is unambiguous on all dimensions, move directly to Phase 1 — never ask unnecessary questions.

---

## Phase 1 — Build the Delegation Plan

1. Determine which agents are needed and in what order. Use this decision table:

   | Condition | Agents to include |
   |-----------|------------------|
   | New feature or non-trivial change | `Explore` → `planner` → `builder` → `reviewer` |
   | Trivial bug fix / small addition where scope is obvious | `builder` → `reviewer` (skip `planner`) |
   | Documentation-only change | `documentator` only |
   | Code change + doc update | `planner` → `builder` → `reviewer` → `documentator` |
   | Need codebase facts before planning | Prepend `Explore` |

2. Write a `todo` list. Each item must be **one agent invocation**, named as:
   ```
   [AgentName] — <one-line description of what it must produce>
   ```
   Example:
   ```
   [Explore]      — Confirm existing complaint service location and method signatures
   [planner]      — Write task.md for the new PDF export endpoint
   [builder]      — Implement task.md (PDF export endpoint + tests)
   [reviewer]     — Validate builder output against task.md; emit PASS/FAIL report
   [documentator] — Update README with the new PDF export endpoint
   ```

3. Mark the first item **in-progress** before invoking any agent.

---

## Phase 2 — Delegate (with Retry Policy)

For each agent in the plan, follow this sub-loop:

### 2.1 Compose the Delegation Prompt

Every prompt sent to a sub-agent must include:
- **Role reminder**: what the agent is and what it must produce
- **Context handoff**: relevant output from prior agents (e.g. "`task.md` has been written — implement it")
- **Constraints**: do not push, do not deploy, do not delete data
- **Output contract**: exactly what the agent must return (e.g. "return a list of all files created/modified")

### 2.2 Invoke the Agent

Call the agent with the composed prompt.

### 2.3 Validate the Output

Immediately after the agent returns, check all of the following:

| Check | Pass condition |
|-------|---------------|
| Completeness | Every item the agent was asked to produce is present |
| Consistency | Output matches the original requirement and prior agents' outputs |
| No blocked state | Agent did not report being stuck or needing user input |
| No destructive actions | Agent did not push, deploy, drop data, or bypass safety checks |
| Correctness signal | For `builder`: build/test commands reported passing; for `planner`: `task.md` exists and has checkboxes; for `reviewer`: report ends with PASS or PASS WITH WARNINGS verdict; for `documentator`: the target doc section exists and is updated |

If **all checks pass**, mark the todo **completed** and post:
```
✅ [AgentName] done — <brief summary of what was produced>
```
Then move to the next agent.

If **any check fails**, apply the Retry Policy below.

---

## Retry Policy

### When to Retry

Retry when the agent's failure is **fixable with a better prompt** — not when it requires user input or reflects a fundamentally wrong plan.

| Failure type | Action |
|--------------|--------|
| Incomplete output (missed a step, skipped a file) | Retry with targeted gap-filling prompt (see §Retry Prompt) |
| Output inconsistent with requirement | Retry with explicit correction and expected output re-stated |
| Agent reported confusion or asked a question | Clarify in the retry prompt; do not ask the user unless it's unresolvable |
| Agent hit a tool error (file not found, build failed) | Retry with corrected context; include the error message verbatim |
| `reviewer` returned FAIL verdict | Retry the **builder** (not the reviewer) — use the reviewer's gap list verbatim as the PROBLEM field in the retry prompt; then re-run the reviewer after the builder finishes |
| Agent performed a destructive action | **Do not retry** — escalate to user immediately (see §Escalation) |

### Retry Limit

- **Maximum 2 retries** per agent per delegation step.
- After 2 failed retries, escalate to the user (see §Escalation).

### Retry Prompt Template

```
The previous attempt by [AgentName] was incomplete or incorrect. Here is what went wrong:

  PROBLEM: <exact description of what is missing or wrong>
  EXPECTED: <what a correct output looks like>
  CONTEXT: <any additional context or correction>

Please retry. Requirements have not changed:
  <re-state the original delegation prompt in full>
```

### Retry Tracking

- Keep the todo item marked **in-progress** for the duration of all retries.
- After each retry, re-run all output validation checks from §2.3.
- If retry 1 passes, mark completed. If retry 1 fails, apply retry 2 with the retry prompt updated to include both the original problem AND the new problem from retry 1.

---

## Escalation Rules

Escalate to the user (stop and ask) when:

1. **Retry limit reached** — 2 retries exhausted without a passing output.
2. **Ambiguous requirement** — a problem emerges mid-flow that cannot be resolved with codebase context alone.
3. **Destructive action attempted** — an agent tried to push, deploy, drop data, or run `rm -rf` / `git reset --hard` / `git push --force`.
4. **Conflicting instructions** — agent output contradicts an explicit user constraint.
5. **Missing dependency** — a required file or service does not exist and creating it is outside the current agent's scope.

Escalation message format:
```
⚠️ Escalation needed — [AgentName] (attempt N/2)

PROBLEM: <what went wrong>
LAST OUTPUT: <summary of what the agent returned>
QUESTION: <the specific thing you need the user to decide or provide>
```

Do not continue the delegation plan until the user responds.

---

## Phase 3 — Inter-Agent Context Handoff

When passing context from one agent to the next, always include:

- **From `Explore`**: the specific facts found (file paths, class names, method signatures) — not the full raw output
- **From `planner`**: confirm `task.md` has been written and list the top-level task headings
- **From `builder`**: list of all files created/modified and any caveats noted during implementation
- **From `reviewer`**: the full PASS/FAIL verdict and, on FAIL, the exact gap list to feed into the builder retry prompt
- **From `documentator`**: list of doc sections updated

Never pass raw, unfiltered agent output forward. Summarize the relevant facts only.

---

## Phase 4 — Final Summary

Once all agents have completed (and all todos are marked completed), post a single consolidated summary using this structure:

```
## Summary

### What was planned
- [task.md sections or key decisions]

### What was implemented
- Files created: [list]
- Files modified: [list]
- Endpoints / methods added: [list]
- Tests written: [list]

### What was documented
- [README sections or docs updated]

### Open items / caveats
- [any follow-ups, known gaps, or things the user should verify]
```

If a section is not applicable (e.g. no documentation was updated), omit it.

---

## Constraints (Never Violate)

- **Never write code** — delegate to `builder`
- **Never edit files** — delegate to the appropriate agent
- **Never run shell commands** — delegate to `builder` or `Explore`
- **Never parallelize dependent agents** — `builder` requires `planner`'s `task.md`
- **Never skip validation** — always run §2.3 checks before marking an agent done
- **Never proceed past a failed escalation** — wait for user input before continuing
