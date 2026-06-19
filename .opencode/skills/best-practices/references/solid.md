# SOLID Principles — Deep Dive

A comprehensive reference for each SOLID principle with definitions, violation patterns, and verification heuristics.

---

## Single Responsibility Principle (SRP)

**Definition**: A class, module, or function should have only one reason to change — meaning it encapsulates exactly one responsibility.

**Why it matters**:
- Changes to one concern don't ripple into unrelated code
- Easier to test, understand, and maintain
- Reduces merge conflicts when different concerns evolve independently

**Violation signals**:
- A class that handles business logic AND persistence AND presentation
- A function that parses input AND validates it AND transforms it AND saves it
- A module that imports from unrelated domains

**Verification questions**:
- Can you describe what this class does in one sentence without using "and"?
- If the requirements for X changed, would this class need to change?
- Can you imagine two different reasons this module might need to be modified?

**Practical guidance**:
- One class per domain concept
- One function per operation
- Keep files focused: if a file exceeds ~200 lines, consider whether it's doing too much

---

## Open/Closed Principle (OCP)

**Definition**: Software entities (modules, classes, functions) should be open for extension but closed for modification.

**Why it matters**:
- You can add new behavior without changing existing, tested code
- Reduces regression risk when requirements evolve
- Encourages plugin/strategy patterns

**Violation signals**:
- Adding a new feature requires modifying an existing class/function in multiple places
- Large switch/case or if/else chains that grow with every new type
- Need to modify core code to add edge cases

**Verification questions**:
- Can I add a new behavior without modifying existing source files?
- Is there a switch/case or if/else that grows with every new type?
- Can new behavior be added via configuration or extension points?

**Practical guidance**:
- Use interfaces/abstractions to define extension points
- Apply the Strategy pattern for swappable behaviors
- Prefer adding new files over modifying existing ones

---

## Liskov Substitution Principle (LSP)

**Definition**: Objects of a subtype should be usable wherever objects of the supertype are expected, without altering the correctness of the program.

**Why it matters**:
- Guarantees that polymorphism works as expected
- Prevents subtle bugs when substituting implementations
- Builds trust in abstractions

**Violation signals**:
- A subclass throws `NotImplementedException` for inherited methods
- A subclass changes the expected behavior of a base method (e.g., changes side effects)
- A subclass requires knowledge of its specific type to be used correctly
- Preconditions are strengthened or postconditions are weakened in subtypes

**Verification questions**:
- Can I replace the base class with any subclass without breaking callers?
- Does the subclass honor the base class contract (preconditions, postconditions, invariants)?
- Do any clients need `instanceof` or type checks to handle this subtype?

**Practical guidance**:
- Subtypes should add behavior, not restrict it
- Prefer composition over inheritance when substitution is difficult
- Test that all subtypes satisfy the base class contract

---

## Interface Segregation Principle (ISP)

**Definition**: Clients should not be forced to depend on interfaces they don't use. Prefer many small, role-specific interfaces over one large general-purpose interface.

**Why it matters**:
- Reduces coupling between unrelated consumers
- Changes to one client's needs don't ripple to others
- Makes implementations easier to write and test

**Violation signals**:
- An interface with methods that most implementers leave empty or throw
- A class that must implement methods it doesn't need
- Forced dependency on a "fat" interface

**Verification questions**:
- Does every method in this interface serve every implementer?
- Are there implementers that leave some methods empty or unimplemented?
- Would splitting this interface into smaller, role-specific contracts be clearer?

**Practical guidance**:
- Define interfaces based on what consumers need, not what providers offer
- Use role interfaces: one interface per client or use case
- Prefer `implements` for multiple small interfaces over one large one

---

## Dependency Inversion Principle (DIP)

**Definition**: High-level modules should not depend on low-level modules. Both should depend on abstractions. Abstractions should not depend on details — details should depend on abstractions.

**Why it matters**:
- High-level business logic is decoupled from infrastructure details
- Easier to swap implementations (e.g., different databases, APIs, UI frameworks)
- Easier to test with mocks

**Violation signals**:
- A domain class that imports a database driver or HTTP client directly
- Business logic that constructs its own dependencies
- A module that knows the concrete type of its collaborators

**Verification questions**:
- Does this high-level module depend on a concrete low-level module?
- Can I swap the implementation (e.g., database, API) without changing the high-level code?
- Are dependencies injected rather than created internally?

**Practical guidance**:
- Define interfaces in the domain/core layer; implement them in the infrastructure layer
- Use dependency injection (constructor injection preferred)
- Keep abstractions stable — they should change less frequently than their implementations

---

## Quick Reference Table

| Principle | Key Question | Red Flag |
|-----------|-------------|----------|
| SRP | Does it have one reason to change? | Multiple unrelated imports |
| OCP | Can I extend without modifying? | Growing switch/if-else chains |
| LSP | Can subtypes replace the base? | `NotImplementedException` in subclasses |
| ISP | Does every client use every method? | Empty method implementations |
| DIP | Does it depend on abstractions? | Direct dependency on concretions |
