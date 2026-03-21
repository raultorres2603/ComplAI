package cat.complai.openrouter.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventRagHelper {
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

    private static final String[] SEARCH_FIELDS = { "title", "description", "eventType", "targetAudience", "location",
            "theme" };
    private static final int MAX_RESULTS = 3;
    private static final String ENV_EVENTS_BUCKET = "EVENTS_BUCKET";
    private static final String ENV_EVENTS_REGION = "EVENTS_REGION";
    private static final Logger logger = Logger.getLogger(EventRagHelper.class.getName());

    private final String cityId;
    private final List<Event> events;
    private final ByteBuffersDirectory ramDirectory;
    private final Analyzer analyzer;

    /**
     * Builds an in-memory Lucene index for the given city's events.
     *
     * <p>
     * Events are loaded from S3 when {@code EVENTS_BUCKET} and
     * {@code EVENTS_REGION} are set; the S3 object key is always
     * {@code events-<cityId>.json}. Falls back to the classpath resource
     * {@code /events-<cityId>.json} when S3 env vars are absent (e.g. local tests).
     */
    public EventRagHelper(String cityId) throws IOException {
        this.cityId = cityId;
        this.events = loadEvents();
        this.analyzer = new StandardAnalyzer();
        this.ramDirectory = new ByteBuffersDirectory();
        buildIndex();
        logger.info(() -> "EventRagHelper initialised — city=" + cityId + " eventCount=" + events.size());
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
        InputStream is = getEventsInputStream();
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

    private void buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(ramDirectory, config)) {
            for (Event e : events) {
                Document doc = new Document();
                doc.add(new StringField("eventId", e.eventId, Field.Store.YES));
                doc.add(new TextField("title", e.title, Field.Store.YES));
                doc.add(new TextField("description", e.description, Field.Store.YES));
                doc.add(new TextField("eventType", e.eventType, Field.Store.YES));
                doc.add(new TextField("targetAudience", e.targetAudience, Field.Store.YES));
                doc.add(new TextField("date", e.date, Field.Store.YES));
                doc.add(new TextField("time", e.time, Field.Store.YES));
                doc.add(new TextField("location", e.location, Field.Store.YES));
                doc.add(new TextField("theme", e.theme, Field.Store.YES));
                doc.add(new StringField("url", e.url, Field.Store.YES));
                writer.addDocument(doc);
            }
        }
    }

    public List<Event> search(String query) {
        if (query == null || query.isBlank())
            return Collections.emptyList();
        List<Event> results = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(ramDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer);
            Query luceneQuery = parser.parse(query); // No QueryParserUtil.escape
            TopDocs topDocs = searcher.search(luceneQuery, MAX_RESULTS);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                results.add(new Event(
                        doc.get("eventId"),
                        doc.get("title"),
                        doc.get("description"),
                        doc.get("eventType"),
                        doc.get("targetAudience"),
                        doc.get("date"),
                        doc.get("time"),
                        doc.get("location"),
                        doc.get("theme"),
                        doc.get("url")));
            }
            logger.fine(() -> "RAG SEARCH — type=EVENT cityId=" + cityId + " queryLength=" + query.length() 
                    + " resultCount=" + results.size() + " maxRequested=" + MAX_RESULTS);
        } catch (IOException | ParseException e) {
            logger.log(Level.WARNING, "Event RAG search failed — queryLength=" + query.length()
                    + " error=" + e.getMessage(), e);
        }
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
