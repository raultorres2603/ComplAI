---
name: builder
description: "Step-by-step implementation workflow for the ComplAI project. Use when: implementing task.md, writing Java/Micronaut code, adding controllers/services/AWS wrappers, writing JUnit 5 tests, adding CDK infrastructure, fixing bugs. Covers exact package layout, code patterns, test patterns, Gradle commands, and final summary format. Includes Explore agent for codebase context-gathering."
argument-hint: "Point to task.md or describe what to implement (e.g. 'Implement task.md' or 'Add the notification endpoint')"
---

# Builder Skill — ComplAI Implementation Workflow

## When to Use
- Implementing steps from `task.md` (the planner's output)
- Adding a new feature: endpoint, service, AWS integration
- Writing or fixing unit/integration tests
- Modifying CDK infrastructure stacks
- Fixing a bug described in a requirement

---

## Phase 0 — Load the Plan

1. Read `task.md` at the workspace root in full before touching any source file.
2. If no `task.md` is present, **stop immediately** and report: 'No task.md found. Ask the orchestrator to run the planner first.'
3. Extract every checkbox item (`- [ ]`) into a `todo` list. Mark items **in-progress** one at a time.

---

## Phase 1 — Announce & Track

Before writing a single line of code:

1. Create the full todo list from `task.md` checkboxes using the `todo` tool.
2. Post this opening message:
   ```
   Starting implementation. N steps queued.
   ```
3. After completing **each** step, post:
   ```
   ✅ Step N — <what changed> (<ClassName.java>, <method>)
   ```
   Then immediately mark the todo as **completed** and the next one as **in-progress**.

---

## Phase 2 — Explore Before Writing

For every task step, **read before you write**:

1. **Decide whether to delegate to Explore**: If you need fast, comprehensive codebase context (e.g., finding all handlers for a domain, understanding a complex service hierarchy), use the `Explore` agent. Otherwise, proceed with targeted searches in steps 2–3.
2. Locate the relevant class using search. Use the package map in [./references/package-map.md](./references/package-map.md) as a guide.
3. Read the constructors, field declarations, and existing error handling of the nearest neighbour class.
4. Only create a new file if no existing class fits. Follow the naming and packaging rules in [./references/conventions.md](./references/conventions.md).

---

## Phase 3 — Implement

Follow the code patterns documented in [./references/conventions.md](./references/conventions.md).

Quick rules:
- `@Inject` on constructor only — never on fields
- `@Singleton` for services and AWS wrappers
- `@Value("${CONFIG_KEY:default}")` for all config — no hardcoded strings
- `Logger.getLogger(ClassName.class.getName())` — one logger per class
- `@PreDestroy` on any method that closes an AWS client
- Protected no-arg constructor on AWS wrapper classes (enables subclassing in tests without real AWS init)

---

## Phase 4 — Write Tests

See [./references/test-patterns.md](./references/test-patterns.md) for complete examples.

Quick rules:
- Controller tests: use inner `Fake*` classes that implement the interface — no Mockito
- Service and helper tests: use `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`
- AWS wrapper tests: subclass the protected no-arg constructor
- Naming: `methodName_condition_expectedResult`
- Every new public method needs at least one test; every new endpoint needs a success + one error case

---

## Phase 5 — Verify

After each step, run only the tests for the class you just changed:
```bash
./gradlew test --tests "cat.complai.<package>.<ClassName>Test"
```

If a test fails:
1. Read the failure output carefully.
2. Fix the source or the test (whichever is wrong) before moving to the next step.
3. Do NOT skip or comment out failing tests.

To run the full suite:
```bash
./gradlew test
```

---

## Phase 6 — CDK / Infrastructure (if needed)

| Resource | File |
|----------|------|
| SQS queue | `cdk/queue-stack.ts` |
| S3 bucket | `cdk/storage-stack.ts` |
| Lambda function | `cdk/lambda-stack.ts` |

**Never run `cdk deploy`** without explicit user approval.

---

## Phase 7 — Final Summary

When every todo item is marked completed, post the summary in this exact format:

```
## Implementation Complete

### Files Changed
| File | Action |
|------|--------|
| src/main/java/cat/complai/.../Foo.java | Created |
| src/main/java/cat/complai/.../Bar.java | Modified |

### Tests Added / Updated
| Test Class | Cases Added |
|------------|-------------|
| cat.complai.../FooTest.java | 3 |
| cat.complai.../BarTest.java | 2 |

### Out of Scope (follow-up)
- <anything explicitly not done>
```

Use workspace-relative file paths as markdown links, e.g. [src/main/java/cat/complai/foo/Foo.java](src/main/java/cat/complai/foo/Foo.java).
