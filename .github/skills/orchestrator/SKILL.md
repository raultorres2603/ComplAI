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
   | New feature or non-trivial change | `planner` → `builder` → `reviewer` (planner uses `Explore` as needed) |
   | Trivial bug fix / small addition where scope is obvious | `builder` → `reviewer` (skip `planner`) |
   | Documentation-only change | `documentator` only |
   | Code change + doc update | `planner` → `builder` → `reviewer` → `documentator` (planner/builder use `Explore` as needed) |
   | Need codebase facts | `planner` handles via `Explore` internally; do not prepend outside the orchestrator |

1b. **Read task groups from `task.md`** (applies when a `planner` step precedes `builder` in the plan):
    - Read the `## Independent Tasks` and `## Dependent Tasks` sections.
    - Collect every independent task into a **concurrent tier**.
    - For each dependent task, parse its `**Requires**:` field and record directed edges
      (prerequisite task → dependent task).
    - Topologically sort dependent tasks; tasks at the same depth whose prerequisites are all
      satisfied form sub-concurrent tiers and may also be parallelized.
    - The todo list must contain one labeled `[builder:<Task N>]` and one labeled
      `[reviewer:<Task N>]` entry per task, ordered according to the tiers derived above.

2. Write a `todo` list. Each item must be **one agent invocation**, named as:
   ```
   [AgentName] — <one-line description of what it must produce>
   ```
   Example:
   ```
   [planner]              — Explore codebase and write task.md (with ## Independent Tasks / ## Dependent Tasks groups)
   [builder:Task 1]       — Implement Task 1 (independent — launches concurrently with other independent builders)
   [builder:Task 2]       — Implement Task 2 (independent — launches concurrently with other independent builders)
   [reviewer:Task 1]      — Validate Task 1 builder output against task.md § Task 1
   [reviewer:Task 2]      — Validate Task 2 builder output against task.md § Task 2
   [builder:Task 3]       — Implement Task 3 (requires Task 1 + Task 2 — starts only after both reviewers pass)
   [reviewer:Task 3]      — Validate Task 3 builder output against task.md § Task 3
   [documentator]         — Update documentation (runs once, after all reviewers across all tasks pass)
   ```

   Labels like `[builder:Task 1]` denote a builder instance scoped to a specific task. Each instance receives only its own task block extracted from `task.md`, not the full file.

3. Mark the first item **in-progress** before invoking any agent.

---

## Phase 2 — Delegate (with Retry Policy)

For each agent in the plan, follow this sub-loop:

### 2.0 — Task Group Scheduling

Before invoking any builder, use the task tiers derived in Phase 1 Step 1b:

1. **Launch all independent-tier builders simultaneously.** Each builder receives a scoped, self-contained prompt containing only its task's block extracted from `task.md` plus any prior-agent context relevant to that task.
2. **After each independent builder finishes**, immediately launch its dedicated reviewer (do not wait for sibling builders).
3. **Once all independent-tier reviewers have passed**, evaluate the dependency graph: any dependent task whose entire `**Requires**:` set is now complete becomes eligible. Launch those eligible dependent builders simultaneously (they form a sub-concurrent tier).
4. **After each dependent builder finishes**, immediately launch its dedicated reviewer.
5. **Repeat steps 3–4** until every dependent task is built and reviewed.
6. **Only after every builder + reviewer pair across all tiers has a PASS verdict**, invoke the `documentator` once.

If any reviewer returns FAIL, apply the Retry Policy to that specific builder only. Sibling builders and reviewers running in parallel are unaffected by a sibling's failure.

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
| Parallel builder isolation | No two simultaneously running builder instances modified the same file; if a collision is detected, halt and reclassify the affected task as dependent before retrying |

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

## Parallel Execution Rules

These rules govern simultaneous builder invocations:

| Rule | Detail |
|------|--------|
| File isolation | Each parallel builder must operate on a disjoint set of files. If two independent tasks would touch the same file, reclassify the later task as dependent (`requires:` the earlier) before launching. |
| Scoped prompts | Each builder instance receives only its own task block from `task.md`. Do not pass the full `task.md` to a parallel builder — extract and forward only the relevant section. |
| Independent reviewer verdicts | Each reviewer evaluates its builder's output in isolation. A FAIL for one task does not block reviewers running for other tasks. |
| Retry scope | A builder retry applies only to the failing task. Parallel sibling builders are unaffected. |
| Documentator gate | The documentator must wait for **all** reviewer verdicts (across all tiers) to be PASS before starting. One unresolved FAIL blocks the documentator. |

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
- **Parallelize only independent tasks** — builder instances for tasks under ## Independent Tasks in task.md may run simultaneously; all other sequencing rules apply (planner always finishes before any builder; documentator always runs after all reviewers pass)
- **Never skip validation** — always run §2.3 checks before marking an agent done
- **Never proceed past a failed escalation** — wait for user input before continuing
