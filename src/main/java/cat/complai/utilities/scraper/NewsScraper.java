package cat.complai.utilities.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class NewsScraper {

    private static final Logger logger = Logger.getLogger(NewsScraper.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: NewsScraper <cityId>");
            System.err.println("  cityId — must match a procedures-mapping-<cityId>.json in resources/scrapers");
            System.exit(1);
        }

        String cityId = args[0].trim().toLowerCase(Locale.ROOT);

        String bucket = System.getenv("NEWS_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println("NEWS_BUCKET environment variable is required.");
            System.exit(1);
        }

        ProcedureScraper.ScraperMapping mapping = ProcedureScraper.loadMapping(cityId);
        validateNewsConfig(mapping, cityId);

        String s3Key = "news-" + cityId + ".json";

        Set<String> discoveredCategories = discoverCategoryUrls(mapping.news);
        Set<String> categoryUrls = mergeCategoryUrls(discoveredCategories, mapping.news.seedCategoryUrls);
        logger.info("Using " + categoryUrls.size() + " news categories — city=" + cityId);

        Set<String> articleUrls = crawlArticleDetailUrls(categoryUrls, mapping.news);
        logger.info("Found " + articleUrls.size() + " news article URLs — city=" + cityId);

        List<Map<String, Object>> news = scrapeNewsArticles(articleUrls, mapping.news);
        logger.info("Scraped " + news.size() + " valid news articles — city=" + cityId);

        Map<String, Object> rootJson = buildRootJson(mapping.news.baseUrl, news);
        File outputFile = writeToLocalFile(cityId, rootJson);

        uploadToS3(outputFile, s3Key, bucket, mapping.s3Region);
        logger.info("Upload complete — bucket=" + bucket + " key=" + s3Key);
    }

    static void validateNewsConfig(ProcedureScraper.ScraperMapping mapping, String cityId) {
        if (mapping.news == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'news' section");
        }
        if (mapping.news.baseUrl == null || mapping.news.baseUrl.isBlank()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'news.baseUrl'");
        }
        if (mapping.news.discovery == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'news.discovery'");
        }
        if (mapping.news.discovery.categoryLinkSelector == null
                || mapping.news.discovery.categoryLinkSelector.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'news.discovery.categoryLinkSelector'");
        }
        if (mapping.news.crawl == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'news.crawl'");
        }
        if (mapping.news.crawl.articleLinkSelector == null || mapping.news.crawl.articleLinkSelector.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'news.crawl.articleLinkSelector'");
        }
        if (mapping.news.crawl.paginationLinkSelector == null || mapping.news.crawl.paginationLinkSelector.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'news.crawl.paginationLinkSelector'");
        }
        if (mapping.news.fields == null || mapping.news.fields.isEmpty()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' has no news field extraction rules");
        }

        List<String> requiredFields = List.of("title", "summary", "body", "publishedAt", "categories");
        for (String field : requiredFields) {
            if (!mapping.news.fields.containsKey(field)) {
                throw new IllegalStateException(
                        "Mapping for city='" + cityId + "' must define news field '" + field + "'");
            }
        }
    }

    static Set<String> discoverCategoryUrls(ProcedureScraper.NewsConfig newsConfig) {
        Set<String> categoryUrls = new LinkedHashSet<>();
        try {
            Document doc = connect(newsConfig.baseUrl);
            String selector = Objects.requireNonNull(newsConfig.discovery.categoryLinkSelector,
                    "news.discovery.categoryLinkSelector");
            Elements links = doc.select(selector);
            for (Element link : links) {
                String href = normalizeUrl(link.absUrl("href"));
                if (href.isBlank()) {
                    continue;
                }
                if (matchesUrlFilters(
                        href,
                        newsConfig.discovery.categoryIncludePatterns,
                        newsConfig.discovery.categoryExcludePatterns)) {
                    categoryUrls.add(href);
                }
            }
        } catch (Exception e) {
            logger.severe("Failed category discovery from " + newsConfig.baseUrl + " — " + e.getMessage());
        }
        return categoryUrls;
    }

    static Set<String> mergeCategoryUrls(Set<String> discoveredCategoryUrls, List<String> seedCategoryUrls) {
        Set<String> merged = new LinkedHashSet<>();
        if (discoveredCategoryUrls != null) {
            for (String discovered : discoveredCategoryUrls) {
                String normalized = normalizeUrl(discovered);
                if (!normalized.isBlank()) {
                    merged.add(normalized);
                }
            }
        }
        if (seedCategoryUrls != null) {
            for (String seed : seedCategoryUrls) {
                String normalized = normalizeUrl(seed);
                if (!normalized.isBlank()) {
                    merged.add(normalized);
                }
            }
        }
        return merged;
    }

    static Set<String> crawlArticleDetailUrls(Set<String> categoryUrls, ProcedureScraper.NewsConfig newsConfig) {
        Set<String> articleUrls = new LinkedHashSet<>();
        Set<String> visitedPages = new HashSet<>();

        for (String categoryUrl : categoryUrls) {
            String nextPage = categoryUrl;
            while (nextPage != null && !nextPage.isBlank() && visitedPages.add(nextPage)) {
                try {
                    Document doc = connect(nextPage);
                    String selector = Objects.requireNonNull(newsConfig.crawl.articleLinkSelector,
                            "news.crawl.articleLinkSelector");
                    Elements links = doc.select(selector);
                    for (Element link : links) {
                        String href = normalizeUrl(link.absUrl("href"));
                        if (href.isBlank()) {
                            continue;
                        }
                        if (matchesUrlFilters(
                                href,
                                newsConfig.crawl.articleIncludePatterns,
                                newsConfig.crawl.articleExcludePatterns)) {
                            articleUrls.add(href);
                        }
                    }
                    nextPage = findNextPageUrl(doc, visitedPages, newsConfig.crawl.paginationLinkSelector);
                } catch (Exception e) {
                    logger.severe("Failed to crawl category page: " + nextPage + " — " + e.getMessage());
                    break;
                }
            }
        }

        return articleUrls;
    }

    static String findNextPageUrl(Document doc, Set<String> visitedPages, String paginationSelector) {
        Elements paginationLinks = doc
                .select(Objects.requireNonNull(paginationSelector, "news.crawl.paginationLinkSelector"));
        for (Element link : paginationLinks) {
            String href = normalizeUrl(link.absUrl("href"));
            if (!href.isBlank() && !visitedPages.contains(href)) {
                return href;
            }
        }
        return null;
    }

    static boolean matchesUrlFilters(String url, List<String> includePatterns, List<String> excludePatterns) {
        if (url == null || url.isBlank()) {
            return false;
        }
        boolean included = includePatterns == null || includePatterns.isEmpty();
        List<String> safeIncludePatterns = includePatterns == null ? List.of() : includePatterns;
        if (!included) {
            for (String pattern : safeIncludePatterns) {
                if (pattern != null && !pattern.isBlank() && Pattern.compile(pattern).matcher(url).find()) {
                    included = true;
                    break;
                }
            }
        }
        if (!included) {
            return false;
        }

        if (excludePatterns != null) {
            for (String pattern : excludePatterns) {
                if (pattern != null && !pattern.isBlank() && Pattern.compile(pattern).matcher(url).find()) {
                    return false;
                }
            }
        }

        return true;
    }

    private static List<Map<String, Object>> scrapeNewsArticles(Set<String> articleUrls,
            ProcedureScraper.NewsConfig newsConfig) {
        List<Map<String, Object>> news = new ArrayList<>();
        for (String url : articleUrls) {
            try {
                Optional<Map<String, Object>> article = scrapeNewsArticle(url, newsConfig);
                if (article.isPresent()) {
                    news.add(article.get());
                    logger.info("Scraped: " + url);
                } else {
                    logger.info("Skipped (no valid content): " + url);
                }
            } catch (Exception e) {
                logger.severe("Failed to scrape: " + url + " — " + e.getMessage());
            }
        }
        return news;
    }

    static Optional<Map<String, Object>> scrapeNewsArticle(String url, ProcedureScraper.NewsConfig newsConfig)
            throws IOException {
        Document doc = connect(url);

        Map<String, Object> news = new LinkedHashMap<>();
        news.put("newsId", generateNewsId(url));

        for (Map.Entry<String, ProcedureScraper.FieldExtractionRule> entry : newsConfig.fields.entrySet()) {
            news.put(entry.getKey(), extractFieldValue(doc, entry.getValue()));
        }

        news.put("url", url);

        if (shouldSkip(news, newsConfig.skip)) {
            return Optional.empty();
        }

        return Optional.of(news);
    }

    static boolean shouldSkip(Map<String, Object> article, ProcedureScraper.NewsSkipConfig skipConfig) {
        String title = stringValue(article.get("title"));
        if (title.isBlank()) {
            return true;
        }

        if (skipConfig != null && skipConfig.whenTitleEmptyOrEquals != null) {
            for (String forbidden : skipConfig.whenTitleEmptyOrEquals) {
                if (forbidden != null && title.equalsIgnoreCase(forbidden)) {
                    return true;
                }
            }
        }

        String body = stringValue(article.get("body"));
        boolean skipWhenBodyEmpty = skipConfig == null || skipConfig.whenBodyEmpty;
        return skipWhenBodyEmpty && body.isBlank();
    }

    static String generateNewsId(String url) {
        return UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String extractFieldValue(Document doc, ProcedureScraper.FieldExtractionRule rule) {
        String selector = Objects.requireNonNull(rule.selector, "Field selector");
        if (rule.multiple) {
            Elements elements = doc.select(selector);
            StringBuilder sb = new StringBuilder();
            for (Element el : elements) {
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }

        Element el = doc.selectFirst(selector);
        return el != null ? el.text().trim() : "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Document connect(String url) throws IOException {
        Objects.requireNonNull(url, "url");
        int maxAttempts = 3;
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "ca,es;q=0.8,en-US;q=0.5,en;q=0.3")
                        .header("Upgrade-Insecure-Requests", "1")
                        .timeout(30000)
                        .ignoreHttpErrors(true)
                        .get();
            } catch (SocketTimeoutException | ConnectException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    logger.info("Retry " + attempt + "/3 for " + url + " after timeout - will wait 2s");
                    logger.info("Connection attempt " + attempt + " failed for " + url + " - " + e.getMessage()
                            + " - retrying in 2 seconds");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry backoff", ie);
                    }
                } else {
                    logger.severe("Connection failed after " + maxAttempts + " attempts for " + url
                            + " - " + e.getMessage());
                }
            }
        }

        throw lastException != null ? lastException : new IOException("Failed to connect to " + url);
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            return trimmed;
        }
        if (trimmed.contains("?")) {
            return trimmed;
        }
        return trimmed + "/";
    }

    private static Map<String, Object> buildRootJson(String sourceUrl, List<Map<String, Object>> news) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("sourceUrl", sourceUrl);
        root.put("news", news);
        return root;
    }

    private static File writeToLocalFile(String cityId, Map<String, Object> rootJson) throws IOException {
        File out = new File("news-" + cityId + ".json");
        try (FileWriter fw = new FileWriter(out, StandardCharsets.UTF_8)) {
            fw.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rootJson));
        }
        logger.info("Wrote local file: " + out.getAbsolutePath());
        return out;
    }

    private static void uploadToS3(File file, String key, String bucket, String region) {
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build()) {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    Paths.get(file.getAbsolutePath()));
            logger.info("Uploaded to S3 — bucket=" + bucket + " key=" + key);
        }
    }
}
