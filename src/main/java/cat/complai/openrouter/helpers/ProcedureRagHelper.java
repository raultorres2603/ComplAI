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
 * RAG helper for city procedures (municipal tramits).
 *
 * <p>Builds and queries an {@link InMemoryLexicalIndex} (BM25) over the procedure corpus
 * for a specific city. The corpus JSON is loaded from S3 (when {@code PROCEDURES_BUCKET}
 * and {@code PROCEDURES_REGION} environment variables are set) or from the classpath
 * resource {@code /procedures-<cityId>.json} (for local tests).
 *
 * <p>Returns the top-{@code MAX_RESULTS} procedures most relevant to the user's query,
 * including their title, description, requirements, steps, and URL.
 */
public class ProcedureRagHelper {

    /**
     * Represents a single municipal procedure with all retrievable fields.
     *
     * <p>Fields may be null when the source JSON does not include them for a given procedure.
     */
    public static class Procedure {
        public final String procedureId;
        public final String title;
        public final String description;
        public final String requirements;
        public final String steps;
        public final String url;

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

    private static final int MAX_RESULTS = 3;
    private static final String ENV_PROCEDURES_BUCKET = "PROCEDURES_BUCKET";
    private static final String ENV_PROCEDURES_REGION = "PROCEDURES_REGION";
    private static final Logger logger = Logger.getLogger(ProcedureRagHelper.class.getName());

    private final String cityId;
    private final List<Procedure> procedures;
    private final InMemoryLexicalIndex<Procedure> javaIndex;
    private final RagJavaCalibration.DomainSettings javaCalibration;

    /**
        * Builds an in-memory index for the given city's procedures.
     *
     * <p>
     * Procedures are loaded from S3 when {@code PROCEDURES_BUCKET} and
     * {@code PROCEDURES_REGION} are set; the S3 object key is always
     * {@code procedures-<cityId>.json}. Falls back to the classpath resource
     * {@code /procedures-<cityId>.json} when S3 env vars are absent (e.g. local
     * tests).
     */
    public ProcedureRagHelper(String cityId) throws IOException {
        this(cityId, RagJavaCalibration.procedure());
    }

    ProcedureRagHelper(String cityId, RagJavaCalibration.DomainSettings javaCalibration) throws IOException {
        this.cityId = cityId;
        this.javaCalibration = javaCalibration;
        this.procedures = loadProcedures();
        this.javaIndex = buildJavaIndex();
        logger.info(() -> "ProcedureRagHelper initialised — city=" + cityId
                + " procedureCount=" + procedures.size()
                + " engine=java");
    }

    private InputStream getProceduresInputStream() throws IOException {
        String bucket = System.getenv(ENV_PROCEDURES_BUCKET);
        String region = System.getenv(ENV_PROCEDURES_REGION);
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        // The S3 key is always derived from the cityId — no env var override.
        // This ensures multi-city requests each load the correct file.
        String s3Key = "procedures-" + cityId + ".json";

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
                logger.info(
                        () -> "Loading procedures from S3 — bucket=" + bucket + " key=" + s3Key + " region=" + region);
                // getObjectAsBytes() reads all bytes into memory before the S3Client
                // (and its underlying HTTP connection) is closed by try-with-resources.
                // Using getObject() would return a stream backed by the HTTP connection,
                // which is closed by the time the caller tries to read it — a silent data loss.
                byte[] procedureBytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(procedureBytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load procedures from S3 — bucket=" + bucket
                        + " key=" + s3Key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }
        String resourcePath = "/procedures-" + cityId + ".json";
        logger.info("Loading procedures from classpath resource: " + resourcePath);
        InputStream is = ProcedureRagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("procedures-" + cityId + ".json not found — "
                    + "add it to src/main/resources/ or configure PROCEDURES_BUCKET/PROCEDURES_REGION");
        }
        return is;
    }

    private List<Procedure> loadProcedures() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getProceduresInputStream()) {
            JsonNode root = mapper.readTree(is);
            List<Procedure> result = new ArrayList<>();
            for (JsonNode node : root.get("procedures")) {
                result.add(new Procedure(
                        node.path("procedureId").asText(),
                        node.path("title").asText(),
                        node.path("description").asText(),
                        node.path("requirements").asText(),
                        node.path("steps").asText(),
                        node.path("url").asText()));
            }
            return result;
        }
    }

    private InMemoryLexicalIndex<Procedure> buildJavaIndex() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", javaCalibration.titleBoost());
        fieldWeights.put("description", javaCalibration.descriptionBoost());

        return InMemoryLexicalIndex.build(
                procedures,
                fieldWeights,
                procedure -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("title", procedure.title);
                    fields.put("description", procedure.description);
                    return fields;
                },
                procedure -> "CA",
                "CA");
    }

    public List<Procedure> search(String query) {
        if (query == null || query.isBlank())
            return Collections.emptyList();

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return Collections.emptyList();
        }

        return runJavaSearch(context, query.length());
    }

    private List<Procedure> runJavaSearch(QueryContext context, int rawQueryLength) {
        List<String> queryTokens = context.tokens();
        List<String> expandedTokens = DeterministicQueryExpansion.expandProcedureQueryTokens(
            queryTokens,
            javaCalibration.expansionEnabled());

        long startNanos = System.nanoTime();
        InMemoryLexicalIndex.SearchResponse<Procedure> response = javaIndex.search(
            expandedTokens,
                MAX_RESULTS,
            javaCalibration.absoluteFloor(),
            javaCalibration.relativeFloor(),
            context.detectedLanguage());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        List<Procedure> results = response.results().stream()
                .map(SearchResult::source)
                .toList();

        logger.fine(() -> "RAG SEARCH — type=PROCEDURE cityId=" + cityId
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
     * Returns all loaded procedures for title-based filtering.
     * Used by OpenRouterServices to optimize RAG searches.
     */
    public List<Procedure> getAllProcedures() {
        return new ArrayList<>(procedures);
    }
}
