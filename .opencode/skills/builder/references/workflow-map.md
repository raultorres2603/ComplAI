# Builder Agent — Skill Orchestration Map

## Available Skills Reference

| Skill | Purpose | When to Use |
|-------|---------|-------------|
| `planning` | Requirements analysis, task breakdown | Unclear requirements, new features, need structured plan |
| `implementation` | Code writing, testing, following conventions | Implementing features, fixes, refactoring |
| `review` | Validation against plan, test verification | After implementation, before merge |
| `documentation` | README and project docs | Updating documentation, API docs |

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

## Delegation Strategy

- **Explore subagent**: Use for large codebase research
- **General subagent**: Use for parallel independent tasks
- **Direct skill load**: Use when you know exactly which skill is needed