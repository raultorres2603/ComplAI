---
name: best-practices
description: "General software engineering best practices: SOLID, KISS, DRY, YAGNI, separation of concerns, composition over inheritance, fail-fast, and defensive programming. Applies to any language or framework."
compatibility: opencode
---

# Best Practices Skill

## Purpose

Enforce software engineering best practices (SOLID, KISS, DRY, YAGNI, and related principles) across all code — regardless of language, framework, or domain. This skill provides a shared quality language and a systematic checklist for writing, reviewing, and planning code.

## When to Activate

- Writing or reviewing code during implementation
- Planning architecture or component design
- Conducting code reviews
- Refactoring existing code
- Evaluating trade-offs between design options
- Any situation where code quality principles should be applied

## Core Principles

| Principle | One-liner |
|-----------|-----------|
| **S** — Single Responsibility | A module has exactly one reason to change |
| **O** — Open/Closed | Open for extension, closed for modification |
| **L** — Liskov Substitution | Subtypes must be substitutable for their base types |
| **I** — Interface Segregation | Many small, specific interfaces over one large general-purpose one |
| **D** — Dependency Inversion | Depend on abstractions, not concretions |
| **KISS** | Keep it simple — avoid unnecessary complexity |
| **DRY** | Don't repeat yourself — every piece of knowledge has a single authoritative representation |
| **YAGNI** | You aren't gonna need it — don't build for hypothetical requirements |
| **SoC** | Separation of Concerns — each module handles one aspect of the system |
| **Composition over Inheritance** | Prefer object composition to class inheritance |
| **Fail-Fast** | Validate early; fail with clear errors rather than silently producing incorrect results |
| **Defensive Programming** | Validate inputs at boundaries; never trust external data |

## Rules

1. **Prefer clarity over cleverness** — if a clever solution requires a comment to explain, simplify it
2. **Question every abstraction** — abstractions have a cost; only create one when there are at least two concrete use cases (Rule of Three)
3. **Delay decisions** — defer design decisions until you have concrete evidence (YAGNI)
4. **Minimize coupling** — modules should depend on the least amount of knowledge necessary
5. **Maximize cohesion** — related things belong together; unrelated things should be separate
6. **Validate at boundaries** — check inputs at API boundaries, user input points, and integration seams
7. **Make invalid states unrepresentable** — use types and data modeling to prevent bugs at compile time
8. **Name things explicitly** — a name should reveal intent without requiring the reader to look at the implementation

## Workflow

### During Planning

When designing architecture or breaking down a task:

1. Identify each responsibility — if a component has two reasons to change, split it
2. Define abstractions (interfaces/traits) before concretions — but only when you have a real use case
3. Ask: "Will I actually need this?" before adding features, configs, or abstractions (YAGNI)
4. Prefer composing existing building blocks over creating new inheritance hierarchies
5. Define clear boundaries between layers (presentation, domain, infrastructure)

### During Implementation

When writing code:

1. Apply SOLID — check that each class/function has one responsibility, depends on abstractions, and is substitutable
2. Apply KISS — when a solution feels complex, ask if there's a simpler alternative
3. Apply DRY — if the same logic appears in two places, extract it; but don't over-abstract two slightly different cases
4. Apply YAGNI — implement only what the current requirements demand
5. Validate inputs at public API boundaries — fail fast with meaningful error messages
6. Use composition to share behavior — avoid deep inheritance trees

### During Review

When reviewing code:

1. Check for single responsibility — does this class/module do one thing?
2. Check for unnecessary abstractions — is this abstraction justified by more than one use case?
3. Check for hidden coupling — does this module know too much about other modules?
4. Check for duplication — is the same knowledge expressed in multiple places?
5. Check for validation gaps — are external inputs validated at boundaries?
6. Check for YAGNI violations — is there code for features that don't exist yet?

## References

- **SOLID principles** — see `references/solid.md` for detailed descriptions, violation examples, and check questions
- **KISS, DRY, YAGNI, and more** — see `references/kiss-dry-yagni.md` for in-depth guidance and decision heuristics

**Remember**: Best practices are guidelines, not rigid rules. Apply judgment — context determines which principle takes priority when they conflict.
