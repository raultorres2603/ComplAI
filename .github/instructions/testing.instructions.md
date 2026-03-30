---
description: how to create/update/remove unit tests in the codebase
applyTo: **/src/**/*.test.ts

---
# Testing Instructions
This instruction file provides guidelines on how to create, update, and remove unit tests in the ComplAI codebase. It covers best practices for writing effective tests, organizing test files, and ensuring that tests are properly maintained as the codebase evolves. Use this instruction when you need to add new tests, update existing ones, or remove outdated tests to ensure that the codebase remains robust and well-tested.
## Best Practices
- Follow the existing test structure and naming conventions in the codebase to maintain consistency.
- Write tests that are focused on a single functionality or behavior to ensure clarity and maintainability.
- Use descriptive test names that clearly indicate what the test is verifying.
- When updating tests, ensure that they reflect the current behavior of the code and remove any assertions that are no longer relevant.
- When removing tests, ensure that they are truly obsolete and that their functionality is covered by other tests to avoid gaps in test coverage.
- Regularly run the test suite to catch any issues early and ensure that all tests are passing before committing changes.