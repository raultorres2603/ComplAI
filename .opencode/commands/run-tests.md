---
name: run-tests
description: "Run tests on this Micronaut Java project. Shows how to run all tests, CI-style tests, and specific test classes or methods."
argument-hint: "Optional: specify test class or method name"
compatibility: opencode
---

# Run Tests Command

This project uses Gradle for testing.

## Recommended Workflow

Before running any tests, always perform these three steps in order to ensure a clean state:

```bash
# Step 1 — Clean build artifacts (removes stale class files and cached test results)
./gradlew clean

# Step 2 — Compile and build the project (catches compilation errors early)
./gradlew build -x test

# Step 3 — Run the tests
./gradlew test        # standard test run
# or
./gradlew ciTest      # CI-style run with verbose failure output
```

All in one line:

```bash
./gradlew clean build -x test test
```

Or with CI-style verbose failures:

```bash
./gradlew clean build -x test ciTest
```

> **Note:** `clean` removes previous build outputs and cached test results, ensuring stale artifacts do not affect test outcomes. `build -x test` compiles main and test code *without* running tests yet, surfacing any compilation failures before the test execution phase.

## Run All Tests

```bash
./gradlew clean build -x test test
```

## CI-Style Test Run (Verbose Failures)

```bash
./gradlew clean build -x test ciTest
```

This runs tests with verbose output, showing detailed failure information. Use this before pushing code.

## Run a Single Test Class

```bash
./gradlew clean build -x test test --tests 'cat.complai.SomeTest'
```

Example:
```bash
./gradlew clean build -x test test --tests 'cat.complai.HomeControllerTest'
```

## Run a Single Test Method

```bash
./gradlew clean build -x test test --tests 'cat.complai.SomeTest.testMethodName'
```

Example:
```bash
./gradlew clean build -x test test --tests 'cat.complai.HomeControllerTest.testHome'
```

## Run Tests for a Nested Class

```bash
./gradlew clean build -x test test --tests 'cat.complai.SomeTest$NestedClass'
```

## Test Authentication

HTTP integration tests rely on `TestJwtSessionFilter` — a `@Replaces(JwtSessionAuthFilter.class)` bean active for all `@MicronautTest` classes. It validates an `Authorization: Bearer <JWT>` header using a hardcoded test signing key and reads `ENABLE_CITY_<CITYID>` from system properties/env vars to determine disabled cities.

Tests obtain a valid token with `TestJwtSessionFilter.createTestToken(cityId)` (expired tokens via `createExpiredTestToken(cityId)`) and send it as `Authorization: Bearer <token>`.

**Gotcha:** Every `@MicronautTest` HTTP integration test must send a Bearer token from `createTestToken(...)` — otherwise the request returns 401. GET `/`, `/health`, `/health/startup` are excluded from auth requirements. The token endpoint `POST /complai/auth/token` is also excluded from the filter and authenticated via the client secret instead.