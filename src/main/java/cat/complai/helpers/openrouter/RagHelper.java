package cat.complai.helpers.openrouter;

import cat.complai.helpers.openrouter.rag.DeterministicQueryExpansion;
import cat.complai.helpers.openrouter.rag.InMemoryLexicalIndex;
import cat.complai.helpers.openrouter.rag.RagJavaCalibration;
import cat.complai.helpers.openrouter.rag.SearchResult;
import cat.complai.helpers.openrouter.rag.TokenNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic in-memory lexical RAG helper that abstracts loading domain data from S3
 * (with classpath fallback), building a {@link InMemoryLexicalIndex}, and executing
 * BM25-scored search with deterministic query expansion.
 *
 * <p>Use the static factory methods ({@link #forEvents(String)}, {@link #forNews(String)},
 * {@link #forCityInfo(String)}, {@link #forProcedures(String)}) to obtain a pre-configured
 * instance for each supported domain.
 *
 * <p>Domain model classes ({@link Event}, {@link News}, {@link CityInfo}, {@link Procedure})
 * are public static inner classes of this type and are shared across all domains.
 *
 * @param <T> the domain type stored in the index
 */
public class RagHelper<T> {

    // =========================================================================
    // Domain model classes
    // =========================================================================

    /**
     * A single event loaded from the JSON knowledge base.
     */
    public static class Event {
        /** Unique identifier for this event. */
        public final String eventId;
        /** Event title. */
        public final String title;
        /** Event description. */
        public final String description;
        /** Event type (e.g. "concert", "exhibition"). */
        public final String eventType;
        /** Target audience (e.g. "families", "adults"). */
        public final String targetAudience;
        /** Event date. */
        public final String date;
        /** Event time. */
        public final String time;
        /** Event location. */
        public final String location;
        /** Event theme or category. */
        public final String theme;
        /** Canonical URL of the event page. */
        public final String url;

        /**
         * Constructs an {@code Event} entry.
         *
         * @param eventId        unique identifier
         * @param title          event title
         * @param description    event description
         * @param eventType      type of event
         * @param targetAudience intended audience
         * @param date           event date
         * @param time           event time
         * @param location       event location
         * @param theme          event theme or category
         * @param url            canonical URL
         */
        public Event(String eventId, String title, String description, String eventType,
                String targetAudience, String date, String time, String location,
                String theme, String url) {
            this.eventId = eventId;
            this.title = title;
            this.description = description;
            this.eventType = eventType;
            this.targetAudience = targetAudience;
            this.date = date;
            this.time = time;
            this.location = location;
            this.theme = theme;
            this.url = url;
        }
    }

    /**
     * A single news article loaded from the JSON knowledge base.
     */
    public static class News {
        /** Unique identifier for this news article. */
        public final String newsId;
        /** Article headline. */
        public final String title;
        /** Short summary of the article. */
        public final String summary;
        /** Full body text of the article. */
        public final String body;
        /** ISO-8601 publication timestamp. */
        public final String publishedAt;
        /** Comma-separated categories. */
        public final String categories;
        /** Author name, if available. */
        public final String author;
        /** Canonical URL of the article. */
        public final String url;

        /**
         * Constructs a {@code News} article entry.
         *
         * @param newsId      unique identifier
         * @param title       article headline
         * @param summary     short summary
         * @param body        full body text
         * @param publishedAt publication date/time
         * @param categories  comma-separated categories
         * @param author      author name
         * @param url         canonical URL
         */
        public News(String newsId, String title, String summary, String body, String publishedAt,
                String categories, String author, String url) {
            this.newsId = newsId;
            this.title = title;
            this.summary = summary;
            this.body = body;
            this.publishedAt = publishedAt;
            this.categories = categories;
            this.author = author;
            this.url = url;
        }
    }

    /**
     * A single city-information document loaded from the JSON knowledge base.
     */
    public static class CityInfo {
        /** Unique identifier for this city-info entry. */
        public final String cityInfoId;
        /** Theme or category of the page (e.g. "tourism", "services"). */
        public final String theme;
        /** Page title. */
        public final String title;
        /** Short summary of the page content. */
        public final String summary;
        /** Full body text of the page. */
        public final String body;
        /** Breadcrumb navigation path. */
        public final String breadcrumbs;
        /** Canonical URL of the source page. */
        public final String url;

        /**
         * Constructs a {@code CityInfo} entry.
         *
         * @param cityInfoId  unique identifier
         * @param theme       page theme or category
         * @param title       page title
         * @param summary     short summary
         * @param body        full body text
         * @param breadcrumbs breadcrumb navigation path
         * @param url         canonical URL
         */
        public CityInfo(String cityInfoId, String theme, String title, String summary,
                String body, String breadcrumbs, String url) {
            this.cityInfoId = cityInfoId;
            this.theme = theme;
            this.title = title;
            this.summary = summary;
            this.body = body;
            this.breadcrumbs = breadcrumbs;
            this.url = url;
        }
    }

    /**
     * A single municipal procedure loaded from the JSON knowledge base.
     */
    public static class Procedure {
        /** Unique identifier for this procedure. */
        public final String procedureId;
        /** Procedure title. */
        public final String title;
        /** Short description of the procedure. */
        public final String description;
        /** Requirements to fulfil before starting the procedure. */
        public final String requirements;
        /** Step-by-step instructions. */
        public final String steps;
        /** Canonical URL of the procedure page. */
        public final String url;

        /**
         * Constructs a {@code Procedure} entry.
         *
         * @param procedureId unique identifier
         * @param title       procedure title
         * @param description short description
         * @param requirements pre-requisites
         * @param steps       step-by-step instructions
         * @param url         canonical URL
         */
        public Procedure(String procedureId, String title, String description, String requirements, String steps,
                String url) {
            this.procedureId = procedureId;
            this.title = title;
            this.description = description;
            this.requirements = requirements;
            this.steps = steps;
            this.url = url;
        }
    }

    // =========================================================================
    // Domain configuration
    // =========================================================================

    /**
     * Controls which query-expansion dictionary is applied at search time.
     */
    public enum ExpansionStrategy {
        /** Expands using event-specific aliases (e.g. "kids" → "children", "family"). */
        EVENT,
        /** Expands using procedure-specific aliases (e.g. "license" → "licencia"). */
        PROCEDURE
    }

    /**
     * Immutable record that describes how a domain's data is loaded, indexed,
     * and searched. Instances are created by the static factory helpers inside
     * {@link RagHelper}.
     *
     * @param <T> the domain type
     */
    public record RagDomainConfig<T>(
            String bucketEnvVar,
            String regionEnvVar,
            String s3KeyPrefix,
            String jsonRootField,
            String logDomainLabel,
            String logHelperName,
            String logSeparator,
            String logItemLabel,
            String logItemCountLabel,
            RagJavaCalibration.DomainSettings calibration,
            Map<String, Double> fieldWeights,
            Function<JsonNode, T> deserializer,
            Function<T, Map<String, String>> fieldExtractor,
            ExpansionStrategy expansionStrategy,
            boolean useLanguageAwareSearch,
            BiFunction<List<T>, QueryContext, List<T>> resultPostProcessor) {
    }

    // =========================================================================
    // News-specific post-filter constants (used in forNews() configuration)
    // =========================================================================

    private static final Set<String> NEWS_INTENT_TOKENS = Set.of(
            "news", "latest", "recent", "current", "affairs", "actuality", "actualitat", "actualidad",
            "noticies", "noticias", "ultimes", "ultimas", "happening", "happenings");

    private static final Set<String> GENERIC_QUERY_TOKENS = Set.of(
            "any", "about", "city", "municipal", "local", "today", "week", "weekend", "tell", "update",
            "updates");

    // =========================================================================
    // Core fields
    // =========================================================================

    private static final int MAX_RESULTS = 3;
    private static final Logger logger = Logger.getLogger(RagHelper.class.getName());

    private final String cityId;
    private final List<T> items;
    private final InMemoryLexicalIndex<T> javaIndex;
    private final RagDomainConfig<T> config;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Constructs a helper for the given city using the supplied domain configuration.
     *
     * <p>Data is loaded from S3 when {@code bucketEnvVar} and {@code regionEnvVar}
     * environment variables are set; otherwise falls back to the classpath resource
     * {@code /<s3KeyPrefix><cityId>.json}. Loading failures are handled gracefully:
     * a warning is logged and an empty dataset is used so the application can still
     * start and serve requests.
     *
     * <p>This constructor is {@code protected} to allow anonymous subclassing in tests.
     *
     * @param cityId the city identifier
     * @param config the domain-specific configuration
     */
    protected RagHelper(String cityId, RagDomainConfig<T> config) {
        this.cityId = cityId;
        this.config = config;
        this.items = loadItemsSafely();
        this.javaIndex = buildIndex();
        logger.info(() -> config.logHelperName() + " initialised" + config.logSeparator()
                + "city=" + cityId + " " + config.logItemCountLabel() + "=" + items.size()
                + " engine=java");
    }

    // =========================================================================
    // Static factory methods
    // =========================================================================

    /**
     * Creates a pre-configured {@code RagHelper} for city events.
     *
     * <p>Loads data from S3 when {@code EVENTS_BUCKET} and {@code EVENTS_REGION} are set,
     * or falls back to {@code /events-<cityId>.json} on the classpath.
     *
     * @param cityId the city identifier
     * @return a ready-to-search event helper
     */
    public static RagHelper<Event> forEvents(String cityId) {
        return new RagHelper<>(cityId, eventDomainConfig());
    }

    /**
     * Creates a pre-configured {@code RagHelper} for city news articles.
     *
     * <p>Loads data from S3 when {@code NEWS_BUCKET} and {@code NEWS_REGION} are set,
     * or falls back to {@code /news-<cityId>.json} on the classpath.
     *
     * @param cityId the city identifier
     * @return a ready-to-search news helper
     */
    public static RagHelper<News> forNews(String cityId) {
        return new RagHelper<>(cityId, newsDomainConfig());
    }

    /**
     * Creates a pre-configured {@code RagHelper} for city-information pages.
     *
     * <p>Loads data from S3 when {@code CITYINFO_BUCKET} and {@code CITYINFO_REGION} are set,
     * or falls back to {@code /cityinfo-<cityId>.json} on the classpath.
     *
     * @param cityId the city identifier
     * @return a ready-to-search city-info helper
     */
    public static RagHelper<CityInfo> forCityInfo(String cityId) {
        return new RagHelper<>(cityId, cityInfoDomainConfig());
    }

    /**
     * Creates a pre-configured {@code RagHelper} for municipal procedures.
     *
     * <p>Loads data from S3 when {@code PROCEDURES_BUCKET} and {@code PROCEDURES_REGION} are set,
     * or falls back to {@code /procedures-<cityId>.json} on the classpath.
     *
     * @param cityId the city identifier
     * @return a ready-to-search procedure helper
     */
    public static RagHelper<Procedure> forProcedures(String cityId) {
        return new RagHelper<>(cityId, procedureDomainConfig());
    }

    // =========================================================================
    // Public domain-config accessors (for test subclassing)
    // =========================================================================

    /**
     * Returns the domain configuration for procedures.
     *
     * <p>Exposed as public to allow anonymous subclassing in tests that need to
     * override {@link #search(String)} while still constructing via the canonical config.
     *
     * @return the procedure domain configuration
     */
    public static RagDomainConfig<Procedure> procedureDomainConfig() {
        RagJavaCalibration.DomainSettings cal = RagJavaCalibration.procedure();
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("title", cal.titleBoost());
        weights.put("description", cal.descriptionBoost());
        return new RagDomainConfig<>(
                "PROCEDURES_BUCKET",
                "PROCEDURES_REGION",
                "procedures-",
                "procedures",
                "PROCEDURE",
                "ProcedureRagHelper",
                " \u2014 ",
                "procedures",
                "procedureCount",
                cal,
                Collections.unmodifiableMap(weights),
                node -> new Procedure(
                        node.path("procedureId").asText(),
                        node.path("title").asText(),
                        node.path("description").asText(),
                        node.path("requirements").asText(),
                        node.path("steps").asText(),
                        node.path("url").asText()),
                item -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("title", item.title);
                    fields.put("description", item.description);
                    return fields;
                },
                ExpansionStrategy.PROCEDURE,
                true,
                (results, ctx) -> results);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Searches the index for the given query and returns up to 3 best-matching items.
     *
     * <p>The query is preprocessed (language detection, stop-word filtering, normalisation)
     * before BM25 retrieval. An optional domain-specific post-filter may be applied
     * (e.g. content-token filtering for news).
     *
     * @param query the natural-language search query; {@code null} or blank returns empty
     * @return an unmodifiable list of matching domain objects, ordered by relevance
     */
    public List<T> search(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return Collections.emptyList();
        }

        List<T> results = runSearch(context, query.length()).results().stream()
                .map(SearchResult::source)
                .toList();

        return config.resultPostProcessor().apply(results, context);
    }

    /**
     * Returns all loaded domain items, unfiltered.
     *
     * <p>Used by services to build title-based detection indexes.
     *
     * @return a mutable copy of all items loaded at construction time
     */
    public List<T> getAll() {
        return new ArrayList<>(items);
    }

    /**
     * Searches the index and returns the full scored response, including metadata
     * (candidate count, best score, applied thresholds).
     *
     * <p>Used by ambiguity-detection logic that needs score metadata in addition
     * to the raw results.
     *
     * @param query the natural-language search query; {@code null} or blank returns empty
     * @return the full {@link InMemoryLexicalIndex.SearchResponse}
     */
    public InMemoryLexicalIndex.SearchResponse<T> searchWithScores(String query) {
        if (query == null || query.isBlank()) {
            return InMemoryLexicalIndex.SearchResponse.empty(
                    config.calibration().absoluteFloor(), config.calibration().relativeFloor());
        }

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return InMemoryLexicalIndex.SearchResponse.empty(
                    config.calibration().absoluteFloor(), config.calibration().relativeFloor());
        }

        return runSearch(context, query.length());
    }

    // =========================================================================
    // Internal — data loading
    // =========================================================================

    private InputStream getInputStream() throws IOException {
        String bucket = System.getenv(config.bucketEnvVar());
        String region = System.getenv(config.regionEnvVar());
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        String s3Key = config.s3KeyPrefix() + cityId + ".json";

        if (bucket != null && !bucket.isBlank() && region != null && !region.isBlank()) {
            software.amazon.awssdk.services.s3.S3ClientBuilder clientBuilder =
                    software.amazon.awssdk.services.s3.S3Client.builder()
                            .region(software.amazon.awssdk.regions.Region.of(region))
                            .credentialsProvider(
                                    software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder()
                                            .build());

            if (endpointUrl != null && !endpointUrl.isBlank()) {
                clientBuilder.endpointOverride(URI.create(endpointUrl));
            }

            try (software.amazon.awssdk.services.s3.S3Client s3 = clientBuilder.build()) {
                software.amazon.awssdk.services.s3.model.GetObjectRequest req =
                        software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(s3Key)
                                .build();
                logger.info(() -> "Loading " + config.logItemLabel() + " from S3" + config.logSeparator()
                        + "bucket=" + bucket + " key=" + s3Key + " region=" + region);
                // getObjectAsBytes() reads all bytes into memory before the S3Client
                // (and its underlying HTTP connection) is closed by try-with-resources.
                byte[] bytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(bytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load " + config.logItemLabel() + " from S3"
                        + config.logSeparator() + "bucket=" + bucket + " key=" + s3Key
                        + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }

        String resourcePath = "/" + config.s3KeyPrefix() + cityId + ".json";
        logger.info("Loading " + config.logItemLabel() + " from classpath resource: " + resourcePath);
        InputStream is = RagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException(config.s3KeyPrefix() + cityId + ".json not found" + config.logSeparator()
                    + "add it to src/main/resources/ or configure "
                    + config.bucketEnvVar() + "/" + config.regionEnvVar());
        }
        return is;
    }

    private List<T> loadItemsSafely() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getInputStream()) {
            JsonNode root = mapper.readTree(is);
            JsonNode arrayNode = root.path(config.jsonRootField());
            if (!arrayNode.isArray()) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            for (JsonNode node : arrayNode) {
                result.add(config.deserializer().apply(node));
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load " + config.logItemLabel() + " for city=" + cityId
                    + "; using empty dataset: " + e.getMessage(), e);
            return List.of();
        }
    }

    // =========================================================================
    // Internal — index construction
    // =========================================================================

    private InMemoryLexicalIndex<T> buildIndex() {
        // Always build with language awareness (language extractor returns "CA" for all items).
        // For domains where language-aware search is disabled, null is passed as queryLanguage
        // at search time, which skips language boosting entirely.
        return InMemoryLexicalIndex.build(
                items,
                config.fieldWeights(),
                config.fieldExtractor(),
                item -> "CA",
                "CA");
    }

    // =========================================================================
    // Internal — search execution
    // =========================================================================

    private InMemoryLexicalIndex.SearchResponse<T> runSearch(QueryContext context, int rawQueryLength) {
        List<String> queryTokens = context.tokens();
        List<String> expandedTokens = switch (config.expansionStrategy()) {
            case EVENT -> DeterministicQueryExpansion.expandEventQueryTokens(
                    queryTokens, config.calibration().expansionEnabled());
            case PROCEDURE -> DeterministicQueryExpansion.expandProcedureQueryTokens(
                    queryTokens, config.calibration().expansionEnabled());
        };

        String queryLanguage = config.useLanguageAwareSearch() ? context.detectedLanguage() : null;

        long startNanos = System.nanoTime();
        InMemoryLexicalIndex.SearchResponse<T> response = javaIndex.search(
                expandedTokens,
                MAX_RESULTS,
                config.calibration().absoluteFloor(),
                config.calibration().relativeFloor(),
                queryLanguage);
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        final String queryLanguageLog = queryLanguage;
        final int resultCount = response.results().size();
        logger.fine(() -> {
            StringBuilder sb = new StringBuilder("RAG SEARCH")
                    .append(config.logSeparator())
                    .append("type=").append(config.logDomainLabel())
                    .append(" cityId=").append(cityId)
                    .append(" engine=java")
                    .append(" queryLength=").append(rawQueryLength);
            if (config.useLanguageAwareSearch()) {
                sb.append(" queryLanguage=").append(queryLanguageLog);
            }
            sb.append(" resultCount=").append(resultCount)
                    .append(" candidateCount=").append(response.candidateCount())
                    .append(" filteredCount=").append(response.filteredCount())
                    .append(" bestScore=").append(response.bestScore())
                    .append(" expansionEnabled=").append(config.calibration().expansionEnabled())
                    .append(" absoluteFloor=").append(response.absoluteFloor())
                    .append(" relativeFloor=").append(response.relativeFloor())
                    .append(" appliedThreshold=").append(response.appliedThreshold())
                    .append(" latencyMs=").append(latencyMs);
            return sb.toString();
        });

        return response;
    }

    // =========================================================================
    // Domain-config builders (public for test subclassing)
    // =========================================================================

    /**
     * Returns the domain configuration for events.
     *
     * <p>Exposed as public to allow anonymous subclassing in tests.
     *
     * @return the event domain configuration
     */
    public static RagDomainConfig<Event> eventDomainConfig() {
        RagJavaCalibration.DomainSettings cal = RagJavaCalibration.event();
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("title", cal.titleBoost());
        weights.put("description", cal.descriptionBoost());
        return new RagDomainConfig<>(
                "EVENTS_BUCKET",
                "EVENTS_REGION",
                "events-",
                "events",
                "EVENT",
                "EventRagHelper",
                " \u2014 ",
                "events",
                "eventCount",
                cal,
                Collections.unmodifiableMap(weights),
                node -> new Event(
                        node.path("eventId").asText(),
                        node.path("title").asText(),
                        node.path("description").asText(),
                        node.path("eventType").asText(),
                        node.path("targetAudience").asText(),
                        node.path("date").asText(),
                        node.path("time").asText(),
                        node.path("location").asText(),
                        node.path("theme").asText(),
                        node.path("url").asText()),
                item -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("title", item.title);
                    fields.put("description", item.description);
                    return fields;
                },
                ExpansionStrategy.EVENT,
                true,
                (results, ctx) -> results);
    }

    /**
     * Returns the domain configuration for news articles.
     *
     * <p>Exposed as public to allow anonymous subclassing in tests.
     *
     * @return the news domain configuration
     */
    public static RagDomainConfig<News> newsDomainConfig() {
        RagJavaCalibration.DomainSettings cal = RagJavaCalibration.event();
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("title", cal.titleBoost());
        weights.put("summary", cal.descriptionBoost());
        weights.put("body", cal.descriptionBoost());
        weights.put("categories", 0.7d);
        return new RagDomainConfig<>(
                "NEWS_BUCKET",
                "NEWS_REGION",
                "news-",
                "news",
                "NEWS",
                "NewsRagHelper",
                " - ",
                "news",
                "newsCount",
                cal,
                Collections.unmodifiableMap(weights),
                node -> new News(
                        node.path("newsId").asText(),
                        node.path("title").asText(),
                        node.path("summary").asText(),
                        node.path("body").asText(),
                        node.path("publishedAt").asText(),
                        readCategories(node.path("categories")),
                        node.path("author").asText(),
                        node.path("url").asText()),
                item -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("title", item.title);
                    fields.put("summary", item.summary);
                    fields.put("body", item.body);
                    fields.put("categories", item.categories);
                    return fields;
                },
                ExpansionStrategy.EVENT,
                false,
                (rankedResults, ctx) -> {
                    if (rankedResults.isEmpty()) {
                        return rankedResults;
                    }
                    List<String> contentTokens = extractNewsContentTokens(
                            String.join(" ", ctx.tokens()));
                    if (contentTokens.isEmpty()) {
                        return rankedResults;
                    }
                    return rankedResults.stream()
                            .filter(item -> matchesNewsContentToken(item, contentTokens))
                            .toList();
                });
    }

    /**
     * Returns the domain configuration for city-information pages.
     *
     * <p>Exposed as public to allow anonymous subclassing in tests.
     *
     * @return the city-info domain configuration
     */
    public static RagDomainConfig<CityInfo> cityInfoDomainConfig() {
        RagJavaCalibration.DomainSettings cal = RagJavaCalibration.procedure();
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("title", cal.titleBoost());
        weights.put("summary", cal.descriptionBoost());
        weights.put("body", cal.descriptionBoost());
        weights.put("breadcrumbs", 0.6d);
        return new RagDomainConfig<>(
                "CITYINFO_BUCKET",
                "CITYINFO_REGION",
                "cityinfo-",
                "cityInfo",
                "CITYINFO",
                "CityInfoRagHelper",
                " - ",
                "city info",
                "cityInfoCount",
                cal,
                Collections.unmodifiableMap(weights),
                node -> new CityInfo(
                        node.path("cityInfoId").asText(),
                        node.path("theme").asText(),
                        node.path("title").asText(),
                        node.path("summary").asText(),
                        node.path("body").asText(),
                        node.path("breadcrumbs").asText(),
                        node.path("url").asText()),
                item -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("title", item.title);
                    fields.put("summary", item.summary);
                    fields.put("body", item.body);
                    fields.put("breadcrumbs", item.breadcrumbs);
                    return fields;
                },
                ExpansionStrategy.PROCEDURE,
                false,
                (results, ctx) -> results);
    }

    // =========================================================================
    // News post-filter helpers
    // =========================================================================

    private static List<String> extractNewsContentTokens(String cleanedQuery) {
        return TokenNormalizer.tokenize(cleanedQuery).stream()
                .filter(token -> !NEWS_INTENT_TOKENS.contains(token))
                .filter(token -> !GENERIC_QUERY_TOKENS.contains(token))
                .filter(token -> token.length() >= 3)
                .toList();
    }

    private static boolean matchesNewsContentToken(News item, List<String> contentTokens) {
        StringBuilder sb = new StringBuilder();
        appendNewsField(sb, item.title);
        appendNewsField(sb, item.summary);
        appendNewsField(sb, item.body);
        appendNewsField(sb, item.categories);
        appendNewsField(sb, item.author);
        String normalizedContent = TokenNormalizer.normalizeForSearch(sb.toString());
        for (String token : contentTokens) {
            if (normalizedContent.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void appendNewsField(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value);
        }
    }

    private static String readCategories(JsonNode categoriesNode) {
        if (categoriesNode == null || categoriesNode.isMissingNode() || categoriesNode.isNull()) {
            return "";
        }
        if (categoriesNode.isArray()) {
            List<String> categories = new ArrayList<>();
            for (JsonNode category : categoriesNode) {
                String value = category.asText("");
                if (!value.isBlank()) {
                    categories.add(value);
                }
            }
            return String.join(", ", categories);
        }
        return categoriesNode.asText("");
    }
}
