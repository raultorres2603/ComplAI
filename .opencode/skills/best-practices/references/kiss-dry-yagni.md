# KISS, DRY, YAGNI & Other Principles — Deep Dive

A comprehensive reference for general software engineering principles beyond SOLID, with definitions, violation patterns, and decision heuristics.

---

## KISS — Keep It Simple, Stupid

**Definition**: Simplicity should be a key goal in every design. Favor the simplest solution that works.

**Why it matters**:
- Simple code is easier to understand, test, and maintain
- Complexity is the primary driver of bugs
- Simple solutions are easier for the team to adopt

**Violation signals**:
- Clever one-liners that require a comment to explain
- Deeply nested conditionals or loops
- Over-engineered abstractions for simple problems
- "I know what it does but I'm not sure why"

**Verification questions**:
- Can a new team member understand this in under 5 minutes?
- Is there a simpler way to achieve the same result?
- Am I solving the actual problem, or an imagined one?

**Practical guidance**:
- Start with the simplest working solution
- Refactor toward simplicity, not cleverness
- If a solution requires more than 3 levels of nesting, simplify
- Prefer explicit over implicit

---

## DRY — Don't Repeat Yourself

**Definition**: Every piece of knowledge in a system should have a single, authoritative representation.

**Why it matters**:
- Reduces inconsistency when requirements change
- Eliminates copy-paste bugs
- Makes the system easier to reason about

**Violation signals**:
- The same logic appearing in multiple files
- Configuration values hardcoded in multiple places
- Business rules duplicated across layers
- Similar but slightly different implementations of the same concept

**Verification questions**:
- If this business rule changes, how many places would I need to update?
- Is this duplication of logic or just duplication of structure?
- Is there a single source of truth for this piece of knowledge?

**Practical guidance**:
- Extract shared logic into functions, utilities, or shared modules
- Centralize configuration and constants
- Use shared types/interfaces to enforce consistency
- **Exception**: Don't over-abstract two slightly different cases — duplication is sometimes cheaper than the wrong abstraction

---

## YAGNI — You Aren't Gonna Need It

**Definition**: Don't implement something until it is actually needed. Build for today's requirements, not tomorrow's imagined ones.

**Why it matters**:
- Premature abstraction is expensive — you pay the cost of maintaining it even if you never use it
- Requirements change; today's "future-proofing" is often tomorrow's dead code
- Reduces scope and delivery time

**Violation signals**:
- Writing code for features that aren't in the current requirements
- Building "extensibility points" that no current consumer uses
- Adding configuration options for hypothetical use cases
- Defining interfaces for a single implementation with no clear future need

**Verification questions**:
- Is this feature in the current requirements?
- Do I have concrete evidence this will be needed?
- Am I solving a real problem or an imagined one?

**Practical guidance**:
- Implement the simplest thing that works now
- Apply the Rule of Three: only abstract after the third occurrence
- Defer design decisions until you have concrete evidence
- Delete code that isn't being used

---

## Separation of Concerns (SoC)

**Definition**: Each module or layer should handle exactly one aspect of the system. Separate business logic from presentation from infrastructure.

**Why it matters**:
- Changes to one concern don't ripple into others
- Each concern can be developed, tested, and evolved independently
- Makes the system easier to reason about

**Violation signals**:
- UI components that directly call the database
- Business logic mixed with HTTP handling
- Database schemas that encode business rules
- Tests that span multiple layers

**Verification questions**:
- Can I change the UI without touching business logic?
- Can I change the persistence layer without touching the domain?
- Are my concerns layered or tangled?

**Practical guidance**:
- Use clear layer boundaries (presentation → application → domain → infrastructure)
- Each layer should only depend on the layer directly below it
- Pass data between layers using DTOs or value objects
- Keep business rules in the domain layer, not in controllers or views

---

## Composition over Inheritance

**Definition**: Favor object composition over class inheritance to achieve code reuse.

**Why it matters**:
- Inheritance creates tight coupling between parent and child
- Composition allows behavior to be mixed and matched at runtime
- Reduces the fragility of deep inheritance hierarchies

**Violation signals**:
- Deep inheritance trees (3+ levels)
- Fragile base class problem — changes in parent break children
- Need to override methods just to customize one behavior
- Using inheritance for code reuse rather than true "is-a" relationships

**Verification questions**:
- Is this a true "is-a" relationship, or just code reuse?
- Would composition achieve the same result with less coupling?
- Can I understand this class without reading its parent?

**Practical guidance**:
- Prefer "has-a" over "is-a"
- Use composition to delegate behavior to collaborators
- Use inheritance only for genuine polymorphic relationships
- Consider interfaces + default implementations as an alternative

---

## Fail-Fast

**Definition**: Validate inputs and preconditions as early as possible. Fail with clear, meaningful errors rather than silently producing incorrect results.

**Why it matters**:
- Bugs are caught close to their source
- Clear error messages reduce debugging time
- Prevents cascading failures from corrupted state

**Violation signals**:
- Silent catch blocks that swallow errors
- Functions that accept invalid input and return partial/undefined results
- Deep call chains where a bad input only causes a failure layers away
- Generic error messages ("Something went wrong")

**Verification questions**:
- Are all public function inputs validated?
- Do error messages indicate what went wrong and where?
- Can invalid data propagate silently through the system?

**Practical guidance**:
- Validate at system boundaries (API endpoints, user input, external data)
- Use assertions for internal invariants
- Throw specific, descriptive errors
- Prefer crashing early over corrupting state

---

## Defensive Programming

**Definition**: Write code that anticipates and handles misuse, unexpected inputs, and edge cases. Never trust external data.

**Why it matters**:
- External systems, users, and APIs can always provide unexpected input
- Reduces the blast radius of failures
- Makes the system resilient to change

**Violation signals**:
- Assuming external data has a specific shape without validation
- Not handling null/undefined cases
- Relying on the "happy path" only
- Trusting third-party APIs to always return correct data

**Verification questions**:
- What happens if this function receives null, undefined, or an empty string?
- What happens if the external API returns an unexpected response?
- Are all edge cases handled at system boundaries?

**Practical guidance**:
- Validate external inputs at every boundary
- Handle missing or malformed data gracefully
- Use typed languages to enforce data shapes at compile time
- Write tests for edge cases and error paths
- Use null/undefined checks before accessing properties

---

## Decision Guide — When Principles Conflict

| Conflict | Recommendation |
|----------|---------------|
| **DRY vs. YAGNI** | Apply YAGNI first. Don't extract until you have 3+ occurrences (Rule of Three). Duplication is cheaper than the wrong abstraction. |
| **KISS vs. OCP** | Start simple. Add extension points only when you have a real, second use case. |
| **SOLID vs. KISS** | Full SOLID compliance can be complex. Apply SOLID at module boundaries; use simpler patterns within modules. |
| **Composition vs. DRY** | Composition often solves the DRY problem. If inheritance is used only for code reuse, switch to composition. |
| **Fail-Fast vs. Defensive** | Validate at boundaries (fail-fast); be defensive within the system (handle edge cases gracefully). |
| **SoC vs. KISS** | Strict separation can add boilerplate. Keep layers clean but avoid over-engineering for small projects. |

## Summary Checklist

- [ ] Does each module have a single responsibility? (SRP)
- [ ] Can new behavior be added without modifying existing code? (OCP)
- [ ] Are all subtypes substitutable for their base types? (LSP)
- [ ] Are interfaces small and focused on specific consumers? (ISP)
- [ ] Do high-level modules depend on abstractions? (DIP)
- [ ] Is the simplest possible solution being used? (KISS)
- [ ] Is every piece of knowledge represented exactly once? (DRY)
- [ ] Are we only building what's actually needed now? (YAGNI)
- [ ] Are concerns separated into distinct layers or modules? (SoC)
- [ ] Are we using composition instead of inheritance for code reuse? (Composition)
- [ ] Are inputs validated early with clear error messages? (Fail-Fast)
- [ ] Are external inputs validated and edge cases handled? (Defensive Programming)
