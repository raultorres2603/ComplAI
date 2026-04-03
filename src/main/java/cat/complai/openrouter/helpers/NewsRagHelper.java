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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NewsRagHelper {

    public static class News {
        public final String newsId;
        public final String title;
        public final String summary;
        public final String body;
        public final String publishedAt;
        public final String categories;
        public final String author;
        public final String url;

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

    private static final int MAX_RESULTS = 3;
    private static final String ENV_NEWS_BUCKET = "NEWS_BUCKET";
    private static final String ENV_NEWS_REGION = "NEWS_REGION";
    private static final Logger logger = Logger.getLogger(NewsRagHelper.class.getName());
    private static final Set<String> NEWS_INTENT_TOKENS = Set.of(
            "news", "latest", "recent", "current", "affairs", "actuality", "actualitat", "actualidad",
            "noticies", "noticias", "ultimes", "ultimas", "happening", "happenings");
    private static final Set<String> GENERIC_QUERY_TOKENS = Set.of(
            "any", "about", "city", "municipal", "local", "today", "week", "weekend", "tell", "update",
            "updates");

    private final String cityId;
    private final List<News> news;
    private final InMemoryLexicalIndex<News> javaIndex;
    private final RagJavaCalibration.DomainSettings javaCalibration;

    public NewsRagHelper(String cityId) {
        this(cityId, RagJavaCalibration.event());
    }

    NewsRagHelper(String cityId, RagJavaCalibration.DomainSettings javaCalibration) {
        this.cityId = cityId;
        this.javaCalibration = javaCalibration;
        this.news = loadNewsSafely();
        this.javaIndex = buildJavaIndex();
        logger.info(() -> "NewsRagHelper initialised - city=" + cityId + " newsCount=" + news.size() + " engine=java");
    }

    private InputStream getNewsInputStream() throws IOException {
        String bucket = System.getenv(ENV_NEWS_BUCKET);
        String region = System.getenv(ENV_NEWS_REGION);
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        String s3Key = "news-" + cityId + ".json";

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
                logger.info(() -> "Loading news from S3 - bucket=" + bucket + " key=" + s3Key + " region=" + region);
                byte[] newsBytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(newsBytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load news from S3 - bucket=" + bucket
                        + " key=" + s3Key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }

        String resourcePath = "/news-" + cityId + ".json";
        logger.info("Loading news from classpath resource: " + resourcePath);
        InputStream is = NewsRagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("news-" + cityId + ".json not found - "
                    + "add it to src/main/resources/ or configure NEWS_BUCKET/NEWS_REGION");
        }
        return is;
    }

    private List<News> loadNewsSafely() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = getNewsInputStream()) {
            JsonNode root = mapper.readTree(is);
            JsonNode newsNode = root.path("news");
            if (!newsNode.isArray()) {
                return List.of();
            }

            List<News> result = new ArrayList<>();
            for (JsonNode node : newsNode) {
                result.add(new News(
                        node.path("newsId").asText(),
                        node.path("title").asText(),
                        node.path("summary").asText(),
                        node.path("body").asText(),
                        node.path("publishedAt").asText(),
                        readCategories(node.path("categories")),
                        node.path("author").asText(),
                        node.path("url").asText()));
            }
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load news for city=" + cityId
                    + "; using empty dataset: " + e.getMessage(), e);
            return List.of();
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

    private InMemoryLexicalIndex<News> buildJavaIndex() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", javaCalibration.titleBoost());
        fieldWeights.put("summary", javaCalibration.descriptionBoost());
        fieldWeights.put("body", javaCalibration.descriptionBoost());
        fieldWeights.put("categories", 0.7d);

        return InMemoryLexicalIndex.build(news, fieldWeights, item -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title", item.title);
            fields.put("summary", item.summary);
            fields.put("body", item.body);
            fields.put("categories", item.categories);
            return fields;
        });
    }

    public List<News> search(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        QueryContext context = QueryPreprocessor.preprocess(query);
        if (context.tokens().isEmpty()) {
            return Collections.emptyList();
        }

        List<News> rankedResults = runJavaSearch(String.join(" ", context.tokens()), query.length());
        if (rankedResults.isEmpty()) {
            return rankedResults;
        }

        List<String> contentTokens = extractContentTokens(String.join(" ", context.tokens()));
        if (contentTokens.isEmpty()) {
            return rankedResults;
        }

        return rankedResults.stream()
                .filter(item -> matchesAnyContentToken(item, contentTokens))
                .toList();
    }

    private static List<String> extractContentTokens(String cleanedQuery) {
        return TokenNormalizer.tokenize(cleanedQuery).stream()
                .filter(token -> !NEWS_INTENT_TOKENS.contains(token))
                .filter(token -> !GENERIC_QUERY_TOKENS.contains(token))
                .filter(token -> token.length() >= 3)
                .toList();
    }

    private static boolean matchesAnyContentToken(News item, List<String> contentTokens) {
        StringBuilder sb = new StringBuilder();
        appendValue(sb, item.title);
        appendValue(sb, item.summary);
        appendValue(sb, item.body);
        appendValue(sb, item.categories);
        appendValue(sb, item.author);
        String normalizedContent = TokenNormalizer.normalizeForSearch(sb.toString());

        for (String token : contentTokens) {
            if (normalizedContent.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void appendValue(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value);
        }
    }

    private List<News> runJavaSearch(String cleanedQuery, int rawQueryLength) {
        List<String> queryTokens = TokenNormalizer.tokenize(cleanedQuery);
        List<String> expandedTokens = DeterministicQueryExpansion.expandEventQueryTokens(
                queryTokens,
                javaCalibration.expansionEnabled());

        long startNanos = System.nanoTime();
        InMemoryLexicalIndex.SearchResponse<News> response = javaIndex.search(
                expandedTokens,
                MAX_RESULTS,
                javaCalibration.absoluteFloor(),
                javaCalibration.relativeFloor());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        List<News> results = response.results().stream()
                .map(SearchResult::source)
                .toList();

        logger.fine(() -> "RAG SEARCH - type=NEWS cityId=" + cityId
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

    public List<News> getAllNews() {
        return new ArrayList<>(news);
    }
}