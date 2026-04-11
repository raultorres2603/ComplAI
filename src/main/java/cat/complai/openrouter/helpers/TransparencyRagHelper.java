package cat.complai.openrouter.helpers;

import cat.complai.openrouter.helpers.rag.DeterministicQueryExpansion;
import cat.complai.openrouter.helpers.rag.InMemoryLexicalIndex;
import cat.complai.openrouter.helpers.rag.RagJavaCalibration;
import cat.complai.openrouter.helpers.rag.SearchResult;
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

public class TransparencyRagHelper {

    public static class TransparencyItem {
        public final String transparencyId;
        public final String title;
        public final String section;
        public final String body;
        public final String url;

        public TransparencyItem(String transparencyId, String title, String section, String body, String url) {
            this.transparencyId = transparencyId;
            this.title = title;
            this.section = section;
            this.body = body;
            this.url = url;
        }
    }

    private static final int MAX_RESULTS = 3;
    private static final String ENV_TRANSPARENCY_BUCKET = "TRANSPARENCY_BUCKET";
    private static final String ENV_TRANSPARENCY_REGION = "TRANSPARENCY_REGION";
    private static final Logger logger = Logger.getLogger(TransparencyRagHelper.class.getName());

    private final String cityId;
    private final List<TransparencyItem> items;
    private final InMemoryLexicalIndex<TransparencyItem> javaIndex;
    private final RagJavaCalibration.DomainSettings javaCalibration;

    public TransparencyRagHelper(String cityId) {
        this(cityId, RagJavaCalibration.procedure());
    }

    TransparencyRagHelper(String cityId, RagJavaCalibration.DomainSettings javaCalibration) {
        this.cityId = cityId;
        this.javaCalibration = javaCalibration;
        this.items = loadTransparencyItemsSafely();
        this.javaIndex = buildJavaIndex();
        logger.info(() -> "TransparencyRagHelper initialised - city=" + cityId + " itemCount=" + items.size()
                + " engine=java");
    }

    private InputStream getTransparencyInputStream() throws IOException {
        String bucket = System.getenv(ENV_TRANSPARENCY_BUCKET);
        String region = System.getenv(ENV_TRANSPARENCY_REGION);
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        String s3Key = "transparency-" + cityId + ".json";

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
                logger.info(() -> "Loading transparency from S3 - bucket=" + bucket + " key=" + s3Key + " region="
                        + region);
                byte[] bytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(bytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load transparency from S3 - bucket=" + bucket
                        + " key=" + s3Key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }

        String resourcePath = "/transparency-" + cityId + ".json";
        logger.info("Loading transparency from classpath resource: " + resourcePath);
        InputStream is = TransparencyRagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("transparency-" + cityId + ".json not found - "
                    + "add it to src/main/resources/ or configure TRANSPARENCY_BUCKET/TRANSPARENCY_REGION");
        }
        return is;
    }

    private List<TransparencyItem> loadTransparencyItemsSafely() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getTransparencyInputStream()) {
            JsonNode root = mapper.readTree(is);
            JsonNode transparencyNode = root.path("transparency");
            if (!transparencyNode.isArray()) {
                return List.of();
            }
            List<TransparencyItem> result = new ArrayList<>();
            for (JsonNode node : transparencyNode) {
                result.add(new TransparencyItem(
                        node.path("transparencyId").asText(),
                        node.path("title").asText(),
                        node.path("section").asText(),
                        node.path("body").asText(),
                        node.path("url").asText()));
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load transparency for city=" + cityId
                    + "; using empty dataset: " + e.getMessage(), e);
            return List.of();
        }
    }

    private InMemoryLexicalIndex<TransparencyItem> buildJavaIndex() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", javaCalibration.titleBoost());
        fieldWeights.put("section", 0.6d);
        fieldWeights.put("body", javaCalibration.descriptionBoost());

        return InMemoryLexicalIndex.build(items, fieldWeights, item -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title", item.title);
            fields.put("section", item.section);
            fields.put("body", item.body);
            return fields;
        });
    }

    public List<TransparencyItem> search(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return Collections.emptyList();
        }

        return runJavaSearch(context, query.length());
    }

    private List<TransparencyItem> runJavaSearch(QueryContext context, int rawQueryLength) {
        List<String> queryTokens = context.tokens();
        List<String> expandedTokens = DeterministicQueryExpansion.expandProcedureQueryTokens(
                queryTokens,
                javaCalibration.expansionEnabled());

        long startNanos = System.nanoTime();
        InMemoryLexicalIndex.SearchResponse<TransparencyItem> response = javaIndex.search(
                expandedTokens,
                MAX_RESULTS,
                javaCalibration.absoluteFloor(),
                javaCalibration.relativeFloor());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        List<TransparencyItem> results = response.results().stream()
                .map(SearchResult::source)
                .toList();

        logger.fine(() -> "RAG SEARCH - type=TRANSPARENCY cityId=" + cityId
                + " engine=java queryLength=" + rawQueryLength
                + " resultCount=" + results.size()
                + " latencyMs=" + latencyMs);
        return results;
    }

    public List<TransparencyItem> getAllTransparencyItems() {
        return new ArrayList<>(items);
    }
}
