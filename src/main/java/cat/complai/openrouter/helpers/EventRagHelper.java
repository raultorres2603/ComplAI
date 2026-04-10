package cat.complai.openrouter.helpers;

import cat.complai.openrouter.helpers.rag.DeterministicQueryExpansion;
import cat.complai.openrouter.helpers.rag.InMemoryLexicalIndex;
import cat.complai.openrouter.helpers.rag.RagJavaCalibration;
import cat.complai.openrouter.helpers.rag.SearchResult;
import cat.complai.openrouter.helpers.rag.TokenNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RAG helper for city events (cultural, civic, sports agenda).
 *
 * <p>Builds and queries an {@link InMemoryLexicalIndex} (BM25) over the events corpus for
 * a specific city. The corpus JSON is loaded from S3 (when {@code EVENTS_BUCKET} and
 * {@code EVENTS_REGION} environment variables are set) or from the classpath resource
 * {@code /events-<cityId>.json} (for local tests).
 *
 * <p>Returns the top-{@code MAX_RESULTS} events most relevant to the user's query,
 * including event type, date, time, location, and URL.
 */
public class EventRagHelper {

    /**
     * Represents a single city event with all retrievable fields.
     *
     * <p>Fields may be null when the source JSON does not include them for a given event.
     */
    public static class Event {
        public final String eventId;
        public final String title;
        public final String description;
        public final String eventType;
        public final String targetAudience;
        public final String date;
        public final String time;
        public final String location;
        public final String theme;
        public final String url;

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

    private static final int MAX_RESULTS = 3;
    private static final String ENV_EVENTS_BUCKET = "EVENTS_BUCKET";
    private static final String ENV_EVENTS_REGION = "EVENTS_REGION";
    private static final Logger logger = Logger.getLogger(EventRagHelper.class.getName());

    private final String cityId;
    private final List<Event> events;
    private final InMemoryLexicalIndex<Event> javaIndex;
    private final RagJavaCalibration.DomainSettings javaCalibration;

    /**
        * Builds an in-memory index for the given city's events.
     *
     * <p>
     * Events are loaded from S3 when {@code EVENTS_BUCKET} and
     * {@code EVENTS_REGION} are set; the S3 object key is always
     * {@code events-<cityId>.json}. Falls back to the classpath resource
     * {@code /events-<cityId>.json} when S3 env vars are absent (e.g. local tests).
     */
    public EventRagHelper(String cityId) throws IOException {
        this(cityId, RagJavaCalibration.event());
    }

    EventRagHelper(String cityId, RagJavaCalibration.DomainSettings javaCalibration) throws IOException {
        this.cityId = cityId;
        this.javaCalibration = javaCalibration;
        this.events = loadEvents();
        this.javaIndex = buildJavaIndex();
        logger.info(() -> "EventRagHelper initialised — city=" + cityId + " eventCount=" + events.size()
                + " engine=java");
    }

    private InputStream getEventsInputStream() throws IOException {
        String bucket = System.getenv(ENV_EVENTS_BUCKET);
        String region = System.getenv(ENV_EVENTS_REGION);
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        // The S3 key is always derived from the cityId — no env var override.
        // This ensures multi-city requests each load the correct file.
        String s3Key = "events-" + cityId + ".json";

        if (bucket != null && region != null) {
            software.amazon.awssdk.services.s3.S3ClientBuilder clientBuilder = software.amazon.awssdk.services.s3.S3Client
                    .builder()
                    .region(software.amazon.awssdk.regions.Region.of(region))
                    .credentialsProvider(
                            software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build());

            if (endpointUrl != null && !endpointUrl.isBlank()) {
                clientBuilder.endpointOverride(URI.create(endpointUrl));
            }

            try (software.amazon.awssdk.services.s3.S3Client s3 = clientBuilder.build()) {
                software.amazon.awssdk.services.s3.model.GetObjectRequest req = software.amazon.awssdk.services.s3.model.GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .build();
                logger.info(() -> "Loading events from S3 — bucket=" + bucket + " key=" + s3Key + " region=" + region);
                // getObjectAsBytes() reads all bytes into memory before the S3Client
                // (and its underlying HTTP connection) is closed by try-with-resources.
                // Using getObject() would return a stream backed by the HTTP connection,
                // which is closed by the time the caller tries to read it — a silent data loss.
                byte[] eventBytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(eventBytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load events from S3 — bucket=" + bucket
                        + " key=" + s3Key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }
        String resourcePath = "/events-" + cityId + ".json";
        logger.info("Loading events from classpath resource: " + resourcePath);
        InputStream is = EventRagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("events-" + cityId + ".json not found — "
                    + "add it to src/main/resources/ or configure EVENTS_BUCKET/EVENTS_REGION");
        }
        return is;
    }

    private List<Event> loadEvents() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getEventsInputStream()) {
            JsonNode root = mapper.readTree(is);
            List<Event> result = new ArrayList<>();
            for (JsonNode node : root.get("events")) {
                result.add(new Event(
                        node.path("eventId").asText(),
                        node.path("title").asText(),
                        node.path("description").asText(),
                        node.path("eventType").asText(),
                        node.path("targetAudience").asText(),
                        node.path("date").asText(),
                        node.path("time").asText(),
                        node.path("location").asText(),
                        node.path("theme").asText(),
                        node.path("url").asText()));
            }
            return result;
        }
    }

    private InMemoryLexicalIndex<Event> buildJavaIndex() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", javaCalibration.titleBoost());
        fieldWeights.put("description", javaCalibration.descriptionBoost());

        return InMemoryLexicalIndex.build(
                events,
                fieldWeights,
                event -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("title", event.title);
                    fields.put("description", event.description);
                    return fields;
                },
                event -> "CA",
                "CA");
    }

    public List<Event> search(String query) {
        if (query == null || query.isBlank())
            return Collections.emptyList();

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return Collections.emptyList();
        }

        return runJavaSearch(context, query.length());
    }

    private List<Event> runJavaSearch(QueryContext context, int rawQueryLength) {
        List<String> queryTokens = context.tokens();
        List<String> expandedTokens = DeterministicQueryExpansion.expandEventQueryTokens(
            queryTokens,
            javaCalibration.expansionEnabled());

        long startNanos = System.nanoTime();
        InMemoryLexicalIndex.SearchResponse<Event> response = javaIndex.search(
            expandedTokens,
                MAX_RESULTS,
            javaCalibration.absoluteFloor(),
            javaCalibration.relativeFloor(),
            context.detectedLanguage());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        List<Event> results = response.results().stream()
                .map(SearchResult::source)
                .toList();

        logger.fine(() -> "RAG SEARCH — type=EVENT cityId=" + cityId
                + " engine=java queryLength=" + rawQueryLength
                + " queryLanguage=" + context.detectedLanguage()
                + " resultCount=" + results.size()
                + " candidateCount=" + response.candidateCount()
                + " filteredCount=" + response.filteredCount()
                + " bestScore=" + response.bestScore()
                + " expansionEnabled=" + javaCalibration.expansionEnabled()
                + " absoluteFloor=" + response.absoluteFloor()
                + " relativeFloor=" + response.relativeFloor()
                + " appliedThreshold=" + response.appliedThreshold()
                + " latencyMs=" + latencyMs);
        return results;
    }

    /**
     * Returns all loaded events for title-based filtering.
     * Used by OpenRouterServices to optimize RAG searches.
     */
    public List<Event> getAllEvents() {
        return new ArrayList<>(events);
    }
}
