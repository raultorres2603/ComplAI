# Test Patterns — ComplAI

## Test Naming Convention

```
methodName_condition_expectedResult
```

Examples:
- `ask_success_returns200`
- `redact_nullFormat_defaultsToPdfAndRoutes202`
- `upload_s3Fails_throwsRuntimeException`

---

## Controller Tests — No Mockito, Use Fake Inner Classes

Controllers take interfaces or extendable classes. Tests use hand-written `Fake*` inner classes.

Pattern from `OpenRouterControllerTest`:

```java
public class MyControllerTest {

    // --- Fakes for the service interface ---

    static class FakeServiceSuccess implements IMyService {
        @Override
        public MyResult doSomething(String input, String cityId) {
            return MyResult.ok("result");
        }
    }

    static class FakeServiceError implements IMyService {
        @Override
        public MyResult doSomething(String input, String cityId) {
            return MyResult.error(MyErrorCode.VALIDATION, "bad input");
        }
    }

    // --- Fakes for AWS wrappers (extend protected no-arg constructor) ---

    static final MyAwsWrapper ASSERT_NOT_CALLED = new MyAwsWrapper() {
        @Override
        public void doAwsThing(String key) {
            throw new AssertionError("Must not be called in this test");
        }
    };

    static final MyAwsWrapper NOOP = new MyAwsWrapper() {
        @Override
        public void doAwsThing(String key) { }
    };

    // --- Helper to build a request with city attribute ---

    private static HttpRequest<?> requestWithCity(String cityId) {
        MutableHttpRequest<?> req = HttpRequest.POST("/path", "");
        req.setAttribute(JwtAuthFilter.CITY_ATTRIBUTE, cityId);
        return req;
    }

    // --- Tests ---

    @Test
    void doSomething_success_returns200() {
        MyController controller = new MyController(new FakeServiceSuccess(), ASSERT_NOT_CALLED);
        HttpResponse<?> response = controller.action(new MyRequest("input"), requestWithCity("testcity"));
        assertEquals(HttpStatus.OK, response.status());
    }

    @Test
    void doSomething_validationError_returns400() {
        MyController controller = new MyController(new FakeServiceError(), ASSERT_NOT_CALLED);
        HttpResponse<?> response = controller.action(new MyRequest("bad"), requestWithCity("testcity"));
        assertEquals(HttpStatus.BAD_REQUEST, response.status());
        MyPublicDto body = (MyPublicDto) response.body();
        assertFalse(body.isSuccess());
    }
}
```

Key points:
- No `@MicronautTest` on controller unit tests — instantiate directly with `new`
- `static class Fake*` implements the service interface inline
- AWS wrapper fakes extend the protected no-arg constructor and override only the methods under test
- ASSERT_NOT_CALLED fakes throw `AssertionError` to catch unexpected calls
- Use `requestWithCity()` helper to set the `JwtAuthFilter.CITY_ATTRIBUTE`

---

## Service Tests — Mockito

```java
@ExtendWith(MockitoExtension.class)
public class MyServiceTest {

    @Mock
    private SomeDependency dep;

    @InjectMocks
    private MyService service;

    @Test
    void doSomething_validInput_returnsOk() {
        when(dep.call(any())).thenReturn("mocked-value");

        MyResult result = service.doSomething("input", "testcity");

        assertTrue(result.isSuccess());
        verify(dep).call("input");
    }

    @Test
    void doSomething_depThrows_returnsError() {
        when(dep.call(any())).thenThrow(new RuntimeException("downstream failure"));

        MyResult result = service.doSomething("input", "testcity");

        assertFalse(result.isSuccess());
        assertEquals(MyErrorCode.INTERNAL, result.getErrorCode());
    }
}
```

---

## AWS Wrapper Tests — Subclass the Protected Constructor

```java
public class MyAwsWrapperTest {

    private static class TestableWrapper extends MyAwsWrapper {
        // Uses the protected no-arg constructor — no real AWS client built
        final List<String> uploadedKeys = new ArrayList<>();

        @Override
        public void doAwsThing(String key) {
            uploadedKeys.add(key);
        }
    }

    @Test
    void doAwsThing_validKey_recordsKey() {
        TestableWrapper wrapper = new TestableWrapper();
        wrapper.doAwsThing("my/key");
        assertEquals(List.of("my/key"), wrapper.uploadedKeys);
    }
}
```

---

## Integration Tests — @MicronautTest

Use sparingly. Only when testing a real HTTP request through the Micronaut runtime.

```java
@MicronautTest
public class MyControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void healthCheck_returns200() {
        HttpResponse<String> response = client.toBlocking()
                .exchange(HttpRequest.GET("/health"), String.class);
        assertEquals(HttpStatus.OK, response.status());
    }
}
```

Requires `src/test/resources/application.properties` overrides for external dependencies (SQS, S3 endpoints, JWT config).

---

## Test Resource Files

| File | Purpose |
|------|---------|
| `src/test/resources/application.properties` | Test config overrides |
| `src/test/resources/events-testcity.json` | Fixture event data for RAG tests |
| `src/test/resources/procedures-testcity.json` | Fixture procedure data for RAG tests |

---

## Gradle Test Commands

Run a single test class:
```bash
./gradlew test --tests "cat.complai.openrouter.controllers.OpenRouterControllerTest"
```

Run a single test method:
```bash
./gradlew test --tests "cat.complai.openrouter.controllers.OpenRouterControllerTest.ask_success_returns200"
```

Run all tests:
```bash
./gradlew test
```

Run CI test suite (separate configuration):
```bash
./gradlew ciTest
```

Test reports: `build/reports/tests/test/index.html`
