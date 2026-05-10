---
name: run-tests
description: "Run tests on this Micronaut Java project. Shows how to run all tests, CI-style tests, and specific test classes or methods."
argument-hint: "Optional: specify test class or method name"
compatibility: opencode
---

# Run Tests Command

This project uses Gradle for testing. Here are the available test commands:

## Run All Tests

```bash
./gradlew test
```

## CI-Style Test Run (Verbose Failures)

```bash
./gradlew ciTest
```

This runs tests with verbose output, showing detailed failure information. Use this before pushing code.

## Run a Single Test Class

```bash
./gradlew test --tests 'cat.complai.SomeTest'
```

Example:
```bash
./gradlew test --tests 'cat.complai.HomeControllerTest'
```

## Run a Single Test Method

```bash
./gradlew test --tests 'cat.complai.SomeTest.testMethodName'
```

Example:
```bash
./gradlew test --tests 'cat.complai.HomeControllerTest.testHome'
```

## Run Tests for a Nested Class

```bash
./gradlew test --tests 'cat.complai.SomeTest$NestedClass'
```

## Test Authentication

When running `@MicronautTest` HTTP integration tests, you must include an `X-Api-Key` header with one of these test keys:

| Key | City |
|---|---|
| `test-integration-key-elprat` | elprat |
| `test-integration-key-testcity` | testcity |
| `test-api-key-feedback` | elprat |
| `test-integration-key-elprat-htmlsources` | testcity |

**Note:** The production `ApiKeyAuthFilter` is disabled in test mode (`api.key.enabled=false` in `src/test/resources/application.properties`). It is replaced by `TestApiKeyFilter` which accepts these test keys.

**Gotcha:** Every `@MicronautTest` HTTP integration test must include an `X-Api-Key` header with one of these keys — otherwise the request returns 401. GET `/`, `/health`, `/health/startup` are excluded from auth requirements.