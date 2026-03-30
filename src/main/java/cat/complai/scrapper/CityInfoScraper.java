package cat.complai.scrapper;

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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class CityInfoScraper {

    private static final Logger logger = Logger.getLogger(CityInfoScraper.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: CityInfoScraper <cityId>");
            System.err.println("  cityId - must match a procedures-mapping-<cityId>.json in resources/scrapers");
            System.exit(1);
        }

        String cityId = args[0].trim().toLowerCase(Locale.ROOT);
        String bucket = System.getenv("CITYINFO_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println("CITYINFO_BUCKET environment variable is required.");
            System.exit(1);
        }

        ProcedureScraper.ScraperMapping mapping = ProcedureScraper.loadMapping(cityId);
        validateCityInfoConfig(mapping, cityId);

        String s3Key = "cityinfo-" + cityId + ".json";

        Map<String, String> themeUrls = discoverThemeLinks(mapping.cityInfo);
        logger.info("Theme discovery found " + themeUrls.size() + " URLs");
        logger.info("Found " + themeUrls.size() + " city-info theme URLs - city=" + cityId);

        logger.info("Starting crawl of " + themeUrls.size() + " themes...");
        Map<String, String> detailUrlsByTheme = crawlDetailUrls(themeUrls, mapping.cityInfo);
        logger.info("Crawl complete: " + detailUrlsByTheme.size() + " detail URLs found");
        logger.info("Found " + detailUrlsByTheme.size() + " city-info detail URLs - city=" + cityId);

        List<Map<String, Object>> cityInfo = scrapeCityInfo(detailUrlsByTheme, mapping.cityInfo);
        logger.info("Scraped " + cityInfo.size() + " valid city-info pages - city=" + cityId);

        Map<String, Object> rootJson = buildRootJson(mapping.cityInfo.baseUrl, cityInfo);
        File outputFile = writeToLocalFile(cityId, rootJson);

        String region = System.getenv("CITYINFO_REGION");
        if (region == null || region.isBlank()) {
            region = mapping.s3Region;
        }
        uploadToS3(outputFile, s3Key, bucket, region);
        logger.info("Upload complete - bucket=" + bucket + " key=" + s3Key);
    }

    static void validateCityInfoConfig(ProcedureScraper.ScraperMapping mapping, String cityId) {
        if (mapping.cityInfo == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'cityInfo' section");
        }
        if (mapping.cityInfo.baseUrl == null || mapping.cityInfo.baseUrl.isBlank()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'cityInfo.baseUrl'");
        }
        if (mapping.cityInfo.discovery == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'cityInfo.discovery'");
        }
        if (mapping.cityInfo.discovery.pratTemesSelector == null
                || mapping.cityInfo.discovery.pratTemesSelector.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'cityInfo.discovery.pratTemesSelector'");
        }
        if (mapping.cityInfo.crawl == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'cityInfo.crawl'");
        }
        if (mapping.cityInfo.crawl.themeMenuSelector == null || mapping.cityInfo.crawl.themeMenuSelector.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'cityInfo.crawl.themeMenuSelector'");
        }
        if (mapping.cityInfo.fields == null || mapping.cityInfo.fields.isEmpty()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' has no cityInfo field extraction rules");
        }
        List<String> requiredFields = List.of("title", "summary", "body", "breadcrumbs");
        for (String field : requiredFields) {
            if (!mapping.cityInfo.fields.containsKey(field)) {
                throw new IllegalStateException(
                        "Mapping for city='" + cityId + "' must define cityInfo field '" + field + "'");
            }
        }
    }

    static Map<String, String> discoverThemeLinks(ProcedureScraper.CityInfoConfig cityInfoConfig) {
        Map<String, String> themeUrls = new LinkedHashMap<>();
        try {
            Document doc = connect(cityInfoConfig.baseUrl);
            Elements links = doc.select(Objects.requireNonNull(cityInfoConfig.discovery.pratTemesSelector,
                    "cityInfo.discovery.pratTemesSelector"));
            for (Element link : links) {
                String canonical = canonicalizeUrl(link.absUrl("href"));
                if (canonical == null) {
                    continue;
                }
                if (!matchesUrlFilters(canonical,
                        cityInfoConfig.discovery.themeIncludePatterns,
                        cityInfoConfig.discovery.themeExcludePatterns)) {
                    continue;
                }
                String theme = link.text() == null ? "" : link.text().trim();
                themeUrls.putIfAbsent(canonical, theme);
            }
        } catch (Exception e) {
            logger.severe("Failed city-info theme discovery from " + cityInfoConfig.baseUrl + " - " + e.getMessage());
        }
        return themeUrls;
    }

    static Map<String, String> crawlDetailUrls(Map<String, String> themeUrls,
            ProcedureScraper.CityInfoConfig cityInfoConfig) {
        Map<String, String> detailUrls = new LinkedHashMap<>();
        for (Map.Entry<String, String> themeEntry : themeUrls.entrySet()) {
            String themeUrl = themeEntry.getKey();
            String themeName = themeEntry.getValue();

            Set<String> visitedPages = new LinkedHashSet<>();
            ArrayDeque<CrawlTarget> pending = new ArrayDeque<>();
            pending.add(new CrawlTarget(themeUrl, 0));

            while (!pending.isEmpty() && visitedPages.size() < cityInfoConfig.crawl.maxPages) {
                CrawlTarget current = pending.removeFirst();
                if (!visitedPages.add(current.url())) {
                    continue;
                }

                if (matchesUrlFilters(current.url(),
                        cityInfoConfig.crawl.detailIncludePatterns,
                        cityInfoConfig.crawl.detailExcludePatterns)) {
                    detailUrls.putIfAbsent(current.url(), themeName);
                }

                logger.fine("Attempting to fetch page " + current.url() + " - depth=" + current.depth() 
                        + " visitedCount=" + visitedPages.size() + " pendingCount=" + pending.size());
                try {
                    Document doc = connect(current.url());
                    Elements menuLinks = doc.select(
                            Objects.requireNonNull(cityInfoConfig.crawl.themeMenuSelector,
                                    "cityInfo.crawl.themeMenuSelector"));
                    logger.fine("Matched " + menuLinks.size() + " links from themeMenuSelector on page " + current.url());
                    for (Element link : menuLinks) {
                        String candidate = canonicalizeUrl(link.absUrl("href"));
                        if (candidate == null) {
                            continue;
                        }
                        if (!matchesUrlFilters(candidate,
                                cityInfoConfig.crawl.navIncludePatterns,
                                cityInfoConfig.crawl.navExcludePatterns)) {
                            continue;
                        }
                        if (matchesUrlFilters(candidate,
                                cityInfoConfig.crawl.detailIncludePatterns,
                                cityInfoConfig.crawl.detailExcludePatterns)) {
                            detailUrls.putIfAbsent(candidate, themeName);
                        }
                        if (current.depth() + 1 <= cityInfoConfig.crawl.maxDepth && !visitedPages.contains(candidate)) {
                            pending.addLast(new CrawlTarget(candidate, current.depth() + 1));
                        }
                    }
                } catch (Exception e) {
                    logger.severe("Failed city-info crawl page: " + current.url() + " - " + e.getMessage());
                }
            }
        }
        return detailUrls;
    }

    static List<Map<String, Object>> scrapeCityInfo(Map<String, String> detailUrlsByTheme,
            ProcedureScraper.CityInfoConfig cityInfoConfig) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : detailUrlsByTheme.entrySet()) {
            String url = entry.getKey();
            String theme = entry.getValue();
            try {
                Optional<Map<String, Object>> item = scrapeCityInfoPage(url, theme, cityInfoConfig);
                item.ifPresent(result::add);
            } catch (Exception e) {
                logger.severe("Failed to scrape city-info page: " + url + " - " + e.getMessage());
            }
        }
        return result;
    }

    static Optional<Map<String, Object>> scrapeCityInfoPage(String url, String theme,
            ProcedureScraper.CityInfoConfig cityInfoConfig) throws IOException {
        Document doc = connect(url);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("cityInfoId", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());
        item.put("theme", theme == null ? "" : theme);

        for (Map.Entry<String, ProcedureScraper.FieldExtractionRule> field : cityInfoConfig.fields.entrySet()) {
            item.put(field.getKey(), extractFieldValue(doc, field.getValue()));
        }

        item.put("url", url);

        if (shouldSkip(item, cityInfoConfig.skip)) {
            return Optional.empty();
        }

        return Optional.of(item);
    }

    static boolean shouldSkip(Map<String, Object> item, ProcedureScraper.CityInfoSkipConfig skipConfig) {
        String title = stringValue(item.get("title"));
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

        String body = stringValue(item.get("body"));
        boolean skipWhenBodyEmpty = skipConfig == null || skipConfig.whenBodyEmpty;
        return skipWhenBodyEmpty && body.isBlank();
    }

    static String extractFieldValue(Document doc, ProcedureScraper.FieldExtractionRule rule) {
        String selector = rule.selector;
        
        // If selector is null or empty, return empty string (nothing to extract)
        if (selector == null || selector.isBlank()) {
            return "";
        }
        
        boolean isMetaTag = selector.contains("meta[");
        
        if (rule.multiple) {
            Elements elements = doc.select(selector);
            StringBuilder sb = new StringBuilder();
            for (Element el : elements) {
                String text;
                if (isMetaTag) {
                    text = el.attr("content");
                } else {
                    text = el.text();
                }
                text = text.trim();
                if (!text.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }

        Element element = doc.selectFirst(selector);
        if (element == null) {
            return "";
        }
        
        if (isMetaTag) {
            return element.attr("content").trim();
        }
        
        return element.text().trim();
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

    static String canonicalizeUrl(String candidateUrl) {
        if (candidateUrl == null || candidateUrl.isBlank()) {
            return null;
        }

        try {
            URI raw = URI.create(candidateUrl.trim());
            String scheme = raw.getScheme();
            if (scheme == null) {
                return null;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                return null;
            }

            String host = raw.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }

            String path = raw.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }

            int port = raw.getPort();
            if (("http".equals(normalizedScheme) && port == 80)
                    || ("https".equals(normalizedScheme) && port == 443)) {
                port = -1;
            }

            URI normalized = new URI(normalizedScheme, raw.getUserInfo(), host.toLowerCase(Locale.ROOT), port,
                    path, raw.getQuery(), null);
            return normalized.toString();
        } catch (Exception e) {
            return null;
        }
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
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "ca,es;q=0.8,en-US;q=0.5,en;q=0.3")
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .header("Connection", "keep-alive")
                        .header("Upgrade-Insecure-Requests", "1")
                        .timeout(30000)
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

    private static Map<String, Object> buildRootJson(String sourceUrl, List<Map<String, Object>> cityInfo) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("sourceUrl", sourceUrl);
        root.put("cityInfo", cityInfo == null ? Collections.emptyList() : cityInfo);
        return root;
    }

    private static File writeToLocalFile(String cityId, Map<String, Object> rootJson) throws IOException {
        File out = new File("cityinfo-" + cityId + ".json");
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
            logger.info("Uploaded to S3 - bucket=" + bucket + " key=" + key);
        }
    }

    private record CrawlTarget(String url, int depth) {
    }
}
