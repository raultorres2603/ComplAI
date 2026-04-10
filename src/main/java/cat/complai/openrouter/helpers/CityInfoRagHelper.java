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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RAG helper for general city information (services, contacts, infrastructure, etc.).
 *
 * <p>Builds and queries an {@link InMemoryLexicalIndex} (BM25) over the city-info corpus
 * for a specific city. The corpus JSON is loaded from S3 (when {@code CITYINFO_BUCKET} and
 * {@code CITYINFO_REGION} environment variables are set) or from the classpath resource
 * {@code /cityinfo-<cityId>.json} (for local tests).
 *
 * <p>Serves as a fallback information source when the query does not match any specific
 * procedure, event, or news article, providing broader municipal context to the AI.
 */
public class CityInfoRagHelper {

    /**
     * Represents a single city-info document with all retrievable fields.
     *
     * <p>Fields may be null when the source JSON does not include them for a given document.
     */
    public static class CityInfo {
        public final String cityInfoId;
        public final String theme;
        public final String title;
        public final String summary;
        public final String body;
        public final String breadcrumbs;
        public final String url;

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

    private static final int MAX_RESULTS = 3;
    private static final String ENV_CITYINFO_BUCKET = "CITYINFO_BUCKET";
    private static final String ENV_CITYINFO_REGION = "CITYINFO_REGION";
    private static final Logger logger = Logger.getLogger(CityInfoRagHelper.class.getName());

    private final String cityId;
    private final List<CityInfo> cityInfoItems;
    private final InMemoryLexicalIndex<CityInfo> javaIndex;
    private final RagJavaCalibration.DomainSettings javaCalibration;

    public CityInfoRagHelper(String cityId) {
        this(cityId, RagJavaCalibration.procedure());
    }

    CityInfoRagHelper(String cityId, RagJavaCalibration.DomainSettings javaCalibration) {
        this.cityId = cityId;
        this.javaCalibration = javaCalibration;
        this.cityInfoItems = loadCityInfoSafely();
        this.javaIndex = buildJavaIndex();
        logger.info(() -> "CityInfoRagHelper initialised - city=" + cityId + " cityInfoCount=" + cityInfoItems.size()
                + " engine=java");
    }

    private InputStream getCityInfoInputStream() throws IOException {
        String bucket = System.getenv(ENV_CITYINFO_BUCKET);
        String region = System.getenv(ENV_CITYINFO_REGION);
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        String s3Key = "cityinfo-" + cityId + ".json";

        if (bucket != null && !bucket.isBlank() && region != null && !region.isBlank()) {
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
                logger.info(() -> "Loading city info from S3 - bucket=" + bucket + " key=" + s3Key + " region=" + region);
                byte[] cityInfoBytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(cityInfoBytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load city info from S3 - bucket=" + bucket
                        + " key=" + s3Key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }

        String resourcePath = "/cityinfo-" + cityId + ".json";
        logger.info("Loading city info from classpath resource: " + resourcePath);
        InputStream is = CityInfoRagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("cityinfo-" + cityId + ".json not found - "
                    + "add it to src/main/resources/ or configure CITYINFO_BUCKET/CITYINFO_REGION");
        }
        return is;
    }

    private List<CityInfo> loadCityInfoSafely() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getCityInfoInputStream()) {
            JsonNode root = mapper.readTree(is);
            JsonNode cityInfoNode = root.path("cityInfo");
            if (!cityInfoNode.isArray()) {
                return List.of();
            }

            List<CityInfo> result = new ArrayList<>();
            for (JsonNode node : cityInfoNode) {
                result.add(new CityInfo(
                        node.path("cityInfoId").asText(),
                        node.path("theme").asText(),
                        node.path("title").asText(),
                        node.path("summary").asText(),
                        node.path("body").asText(),
                        node.path("breadcrumbs").asText(),
                        node.path("url").asText()));
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load city info for city=" + cityId
                    + "; using empty dataset: " + e.getMessage(), e);
            return List.of();
        }
    }

    private InMemoryLexicalIndex<CityInfo> buildJavaIndex() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", javaCalibration.titleBoost());
        fieldWeights.put("summary", javaCalibration.descriptionBoost());
        fieldWeights.put("body", javaCalibration.descriptionBoost());
        fieldWeights.put("breadcrumbs", 0.6d);

        return InMemoryLexicalIndex.build(cityInfoItems, fieldWeights, item -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title", item.title);
            fields.put("summary", item.summary);
            fields.put("body", item.body);
            fields.put("breadcrumbs", item.breadcrumbs);
            return fields;
        });
    }

    public List<CityInfo> search(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return Collections.emptyList();
        }

        return runJavaSearch(context, query.length());
    }

    private List<CityInfo> runJavaSearch(QueryContext context, int rawQueryLength) {
        List<String> queryTokens = context.tokens();
        List<String> expandedTokens = DeterministicQueryExpansion.expandProcedureQueryTokens(
                queryTokens,
                javaCalibration.expansionEnabled());

        long startNanos = System.nanoTime();
        InMemoryLexicalIndex.SearchResponse<CityInfo> response = javaIndex.search(
                expandedTokens,
                MAX_RESULTS,
                javaCalibration.absoluteFloor(),
                javaCalibration.relativeFloor());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        List<CityInfo> results = response.results().stream()
                .map(SearchResult::source)
                .toList();

        logger.fine(() -> "RAG SEARCH - type=CITYINFO cityId=" + cityId
                + " engine=java queryLength=" + rawQueryLength
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

    public List<CityInfo> getAllCityInfo() {
        return new ArrayList<>(cityInfoItems);
    }
}
