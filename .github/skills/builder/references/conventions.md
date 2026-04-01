# Code Conventions — ComplAI

## Dependency Injection

Always use constructor injection. Never inject on fields.

```java
@Singleton
public class MyService {

    private final SomeDependency dep;

    @Inject
    public MyService(SomeDependency dep) {
        this.dep = dep;
    }
}
```

Optional beans use `@Nullable` on the constructor parameter:
```java
@Inject
public OpenRouterController(..., @Nullable OidcIdentityTokenValidator identityTokenValidator) {
    this.identityTokenValidator = identityTokenValidator;
}
```

---

## Controllers

Pattern from `OpenRouterController`:

```java
@Controller("/my-path")
public class MyController {

    private final IMyService service;
    private final Logger logger = Logger.getLogger(MyController.class.getName());

    @Inject
    public MyController(IMyService service) {
        this.service = service;
    }

    @Post("/action")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> action(@Body MyRequest request, HttpRequest<?> httpRequest) {
        String cityId = httpRequest.getAttribute(JwtAuthFilter.CITY_ATTRIBUTE, String.class)
                .orElseThrow(() -> new IllegalStateException("city attribute missing"));
        logger.info(() -> "POST /my-path/action — city=" + cityId);
        long start = System.currentTimeMillis();
        try {
            // ... delegate to service
            return HttpResponse.ok(resultDto).contentType(MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            logger.log(Level.SEVERE, "POST /my-path/action failed — latencyMs=" + latency, e);
            return HttpResponse.serverError(errorDto).contentType(MediaType.APPLICATION_JSON);
        }
    }
}
```

Key points:
- Retrieve `cityId` from request attribute set by `JwtAuthFilter` — never trust user input for city
- Log entry with `city=` and relevant IDs at `INFO` level
- Log failures at `SEVERE` with latency
- Use `long start = System.currentTimeMillis()` at method start
- Return typed `HttpResponse<?>` — use `HttpResponse.ok()`, `HttpResponse.badRequest()`, `HttpResponse.serverError()`

---

## Services

Pattern from `OpenRouterServices` / `FeedbackPublisherService`:

```java
@Singleton
public class MyService implements IMyService {

    private final Logger logger = Logger.getLogger(MyService.class.getName());
    private final SomeDependency dep;

    @Inject
    public MyService(SomeDependency dep) {
        this.dep = dep;
    }

    @Override
    public MyResult doSomething(String input, String cityId) {
        // validate → build context → call dependency → return result
    }
}
```

---

## AWS Wrapper Classes (S3, SQS)

Pattern from `S3PdfUploader` / `SqsComplaintPublisher`:

```java
@Singleton
public class MyAwsWrapper {

    private final AwsClient client;
    private final String configValue;
    private final Logger logger = Logger.getLogger(MyAwsWrapper.class.getName());

    @Inject
    public MyAwsWrapper(
            @Value("${CONFIG_KEY:default-value}") String configValue,
            @Value("${AWS_ENDPOINT_URL:}") String endpointUrl) {
        this.configValue = configValue;
        // build client, optionally override endpoint for LocalStack
        AwsClientBuilder builder = AwsClient.builder();
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        this.client = builder.build();
    }

    // Protected no-arg constructor — lets tests subclass without real AWS init
    protected MyAwsWrapper() {
        this.client = null;
        this.configValue = null;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close client", e);
            }
        }
    }
}
```

Key points:
- `@Value("${KEY:default}")` on every config param — no hardcoded strings
- `endpointOverride` conditional on `AWS_ENDPOINT_URL` for LocalStack compatibility
- Protected no-arg constructor for test subclassing
- `@PreDestroy` closes any client that implements `AutoCloseable`

---

## Config Keys

Config is bound via `@Value("${KEY:default}")` or `@ConfigurationProperties`.

Look in `src/main/resources/application.properties` for existing keys. Add new keys there and use the same pattern.

---

## DTOs

- Request DTOs: in `<domain>.controllers.dto` (e.g. `AskRequest`)
- Response DTOs exposed to clients: in `<domain>.dto` with `Public` suffix (e.g. `OpenRouterPublicDto`)
- Internal result DTOs: in `<domain>.dto` (e.g. `OpenRouterResponseDto`)
- SQS message DTOs: in `<domain>.dto` or `sqs.dto`

---

## Error Codes

Use `OpenRouterErrorCode` for the AI/redact domain. For other domains, follow the `FeedbackErrorCode` enum pattern.

The HTTP status mapping used in `OpenRouterController.errorToHttpResponse()`:
| Error Code | HTTP Status |
|------------|-------------|
| `NONE` | 200 |
| `VALIDATION` | 400 |
| `UNAUTHORIZED` | 401 |
| `REFUSAL` | 422 |
| `TIMEOUT`, `UPSTREAM` | 502 |
| `INTERNAL` | 500 |

---

## Logging

```java
private final Logger logger = Logger.getLogger(ClassName.class.getName());

// Info — use lambda to defer string concatenation
logger.info(() -> "POST /path action — key=" + value + " city=" + cityId);

// Warning
logger.warning(() -> "Unexpected state — detail=" + detail);

// Severe with exception
logger.log(Level.SEVERE, "Operation failed — detail=" + detail, exception);
```

Never use `System.out.println`.

---

## Interfaces

Only create an interface when there is (or will be) more than one implementation, or when the interface is needed for test substitution (e.g. `IOpenRouterService`). Do not create interfaces for one-off helper classes.
