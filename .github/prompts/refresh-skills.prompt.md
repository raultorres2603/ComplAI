---
agent: orchestrator
description: "Audit and patch all skill files, agent files, and copilot-instructions.md when the codebase has drifted. Run this after major changes to tech stack, package structure, AWS infrastructure, or project conventions."
---

# Refresh Skills — ComplAI

You are performing a **skills audit and patch**. Your job is to detect drift between the project's Copilot instruction files and the actual current state of the codebase, then apply the minimum necessary edits to bring them in sync.

You do **not** rewrite files from scratch. You **only** patch what is stale or missing.

---

## Step 1 — Snapshot the Current Codebase

Collect ground truth from these sources in parallel:

### Tech Stack & Dependencies
- Read `build.gradle` — extract all `implementation` and `testImplementation` dependencies, Java version, plugins.
- Read `gradle.properties` — note any version pins or flags.

### Package Structure
- Read `src/main/java/cat/complai/` recursively — list every package and the public classes within it.
- Read `src/test/java/cat/complai/` recursively — list every test class.

### Infrastructure
- Read `cdk/lambda-stack.ts`, `cdk/queue-stack.ts`, `cdk/storage-stack.ts`, `cdk/bin/cdk.ts` — extract stack resources, env vars, runtimes.

### Resources & Config
- Read `src/main/resources/application.properties` — note all config keys and integrations.
- List `src/main/resources/` — identify any new data files (JSON, properties).

### Agent & Skill Files (current state)
Read every file below so you know what they currently say:
- `.github/copilot-instructions.md`
- `.github/agents/builder.agent.md`
- `.github/agents/planner.agent.md`
- `.github/agents/reviewer.agent.md`
- `.github/agents/documentator.agent.md`
- `.github/agents/orchestrator.agent.md`
- `.github/skills/builder/SKILL.md`
- `.github/skills/builder/references/package-map.md`
- `.github/skills/builder/references/conventions.md`
- `.github/skills/builder/references/test-patterns.md`
- `.github/skills/planner/SKILL.md`
- `.github/skills/reviewer/SKILL.md`
- `.github/skills/documentator/SKILL.md`
- `.github/skills/documentator/references/source-map.md`
- `.github/skills/documentator/references/readme-sections.md`
- `.github/skills/orchestrator/SKILL.md`

---

## Step 2 — Identify Drift

Compare ground truth (Step 1) against what the skill/instruction files currently say. Build a drift list:

| File | Drift type | Detail |
|------|------------|--------|
| … | NEW_PACKAGE / REMOVED_PACKAGE / NEW_DEP / REMOVED_DEP / NEW_RESOURCE / CHANGED_INFRA / STALE_CONVENTION | … |

Drift types to look for:
- **NEW_PACKAGE** — a package exists in `src/` that is not listed in `package-map.md`.
- **REMOVED_PACKAGE** — a package is listed in `package-map.md` but no longer exists in `src/`.
- **NEW_DEP** — a library in `build.gradle` is not mentioned anywhere in skill/instruction files where relevant.
- **REMOVED_DEP** — a library mentioned in skill/instruction files is no longer in `build.gradle`.
- **NEW_RESOURCE** — a new `.json` or `.properties` file in `src/main/resources/` not reflected in `package-map.md` or `source-map.md`.
- **CHANGED_INFRA** — CDK stacks define resources (queues, buckets, lambdas) not reflected in `copilot-instructions.md` or `source-map.md`.
- **STALE_CONVENTION** — a code convention described in `conventions.md` or an agent file contradicts the actual patterns found in `src/`.

If the drift list is **empty**, stop here and report: "No drift detected — all skill and instruction files are up to date."

---

## Step 3 — Patch

For each item in the drift list, apply the minimum targeted edit to the appropriate file:

### `package-map.md` patches
- Add a new row to the relevant table for each **NEW_PACKAGE**.
- Remove or mark deprecated rows for each **REMOVED_PACKAGE**.
- Update the "Key Classes" column if class names changed.

### `copilot-instructions.md` patches
- Update the **Tech Stack** section if a library was added or removed (`build.gradle` is the source of truth).
- Do not change the Code Style section unless a convention actually changed in `src/`.

### Agent file patches (`.github/agents/*.agent.md`)
- Update the **Tech Stack** bullet list in any agent file that lists libraries, if those libraries changed.
- Update tool lists only if the agent's workflow changed.

### `source-map.md` patches
- Add new CDK resources or `src/main/resources/` files.
- Remove entries that no longer exist.

### `conventions.md` and `test-patterns.md` patches
- Update only if you found a concrete, repeated pattern in `src/` that contradicts what is written.
- Do not add opinions — only document what the code demonstrably does.

---

## Step 4 — Report

After all patches are applied, output a structured report:

```
## Skills Refresh Report — <date>

### Drift Found
- <file>: <drift type> — <brief detail>
- …

### Patches Applied
- <file>: <what changed in one line>
- …

### No Changes Needed
- <file>: unchanged
- …
```

If you skipped any drift item because you were uncertain, list it under a **Needs Manual Review** section with a short explanation.
