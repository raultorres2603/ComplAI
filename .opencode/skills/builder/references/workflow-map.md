# Builder Agent — Skill Orchestration Map

## Available Skills Reference

| Skill | Purpose | When to Use |
|-------|---------|-------------|
| `planning` | Requirements analysis, task breakdown | Unclear requirements, new features, need structured plan |
| `implementation` | Code writing, testing, following conventions | Implementing features, fixes, refactoring |
| `review` | Validation against plan, test verification | After implementation, before merge |
| `documentation` | README and project docs | Updating documentation, API docs |
| `best-practices` | Enforce SOLID, KISS, DRY, YAGNI principles | Planning, implementation, review |

## Orchestration Patterns

### Feature Development
```
1. planning → 2. implementation → 3. review → 4. documentation
```

### Bug Fix
```
1. planning → 2. implementation → 3. review
```

### Documentation Only
```
1. documentation
```

### Refactoring
```
1. planning → 2. implementation → 3. review → 4. documentation (if needed)
```

## Cross-Cutting Skills

The `best-practices` skill runs **alongside** planning, implementation, and review — it is not a separate phase. The implementer, planner, and reviewer agents all load it automatically to enforce SOLID, KISS, DRY, and YAGNI principles during their respective workflows.

## Delegation Strategy

- **Explore subagent**: Use for large codebase research
- **General subagent**: Use for parallel independent tasks
- **Direct skill load**: Use when you know exactly which skill is needed