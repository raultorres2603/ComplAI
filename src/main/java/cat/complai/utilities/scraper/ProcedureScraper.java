package cat.complai.utilities.scraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Generic city procedures scraper.
 *
 * <p>
 * Reads a per-city mapping file from the classpath
 * ({@code scrapers/procedures-mapping-<cityId>.json}) that describes which HTML
 * selectors to use when crawling the city's procedure website. Produces a
 * {@code procedures-<cityId>.json} file locally and uploads it to S3.
 *
 * <p>
 * Usage:
 * 
 * <pre>
 *   PROCEDURES_BUCKET=complai-procedures-development \
 *   java -cp complai-all.jar cat.complai.pratespais.ProcedureScraper elprat
 * </pre>
 *
 * <p>
 * To add a new city, create
 * {@code src/main/resources/scrapers/procedures-mapping-<cityId>.json}
 * following the schema of the existing {@code procedures-mapping-elprat.json}.
 */
public class ProcedureScraper {

    private static final Logger logger = Logger.getLogger(ProcedureScraper.class.getName());
    private static final String MAPPING_RESOURCE_PATTERN = "/scrapers/procedures-mapping-%s.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: ProcedureScraper <cityId>");
            System.err.println("  cityId — must match a procedures-mapping-<cityId>.json in resources/scrapers");
            System.exit(1);
        }

        String cityId = args[0].trim().toLowerCase(Locale.ROOT);

        String bucket = System.getenv("PROCEDURES_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println("PROCEDURES_BUCKET environment variable is required.");
            System.exit(1);
        }

        ScraperMapping mapping = loadMapping(cityId);
        String s3Key = "procedures-" + cityId + ".json";

        Set<String> detailUrls = crawlProcedureDetailUrls(mapping);
        logger.info("Found " + detailUrls.size() + " procedure detail URLs — city=" + cityId);

        List<Map<String, Object>> procedures = scrapeProcedures(detailUrls, mapping);
        logger.info("Scraped " + procedures.size() + " valid procedures — city=" + cityId);

        Map<String, Object> rootJson = buildRootJson(mapping.baseUrl, procedures);
        File outputFile = writeToLocalFile(cityId, rootJson);

        uploadToS3(outputFile, s3Key, bucket, mapping.s3Region);
        logger.info("Upload complete — bucket=" + bucket + " key=" + s3Key);
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    // Package-private for unit testing.
    static ScraperMapping loadMapping(String cityId) throws IOException {
        String resourcePath = String.format(MAPPING_RESOURCE_PATTERN, cityId);
        InputStream is = ProcedureScraper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException(
                    "No mapping found for city='" + cityId + "'. "
                            + "Expected classpath resource: " + resourcePath);
        }
        ScraperMapping mapping = MAPPER.readValue(is, ScraperMapping.class);
        validateMapping(mapping, cityId);
        return mapping;
    }

    // Package-private for unit testing.
    static void validateMapping(ScraperMapping mapping, String cityId) {
        if (mapping.baseUrl == null || mapping.baseUrl.isBlank()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'baseUrl'");
        }
        if (mapping.crawl == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'crawl' config");
        }
        if (mapping.crawl.detailLinkSelector == null || mapping.crawl.detailLinkSelector.isBlank()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'crawl.detailLinkSelector'");
        }
        if (mapping.fields == null || mapping.fields.isEmpty()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' has no field extraction rules");
        }
        if (!mapping.fields.containsKey("title")) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' must define a 'title' field (required for skip evaluation)");
        }
        if (mapping.s3Region == null || mapping.s3Region.isBlank()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 's3Region'");
        }
    }

    // -------------------------------------------------------------------------
    // Crawl
    // -------------------------------------------------------------------------

    private static Set<String> crawlProcedureDetailUrls(ScraperMapping mapping) {
        Set<String> pendingCategoryUrls = new LinkedHashSet<>();
        Set<String> visitedCategoryUrls = new HashSet<>();
        Set<String> detailUrls = new LinkedHashSet<>();

        pendingCategoryUrls.add(mapping.baseUrl);
        if (mapping.crawl.additionalSeeds != null) {
            pendingCategoryUrls.addAll(mapping.crawl.additionalSeeds);
        }

        while (!pendingCategoryUrls.isEmpty()) {
            String currentUrl = pendingCategoryUrls.iterator().next();
            pendingCategoryUrls.remove(currentUrl);

            if (!visitedCategoryUrls.add(currentUrl))
                continue;

            try {
                @SuppressWarnings("null")
                Document doc = Jsoup.connect(currentUrl).get();

                if (mapping.crawl.categoryLinkSelector != null && !mapping.crawl.categoryLinkSelector.isBlank()) {
                    @SuppressWarnings("null")
                    var categoryLinks = doc.select(mapping.crawl.categoryLinkSelector);
                    for (Element link : categoryLinks) {
                        String href = link.absUrl("href");
                        if (!href.isBlank() && !visitedCategoryUrls.contains(href)) {
                            pendingCategoryUrls.add(href);
                        }
                    }
                }

                @SuppressWarnings("null")
                var detailLinks = doc.select(mapping.crawl.detailLinkSelector);
                for (Element link : detailLinks) {
                    String href = link.absUrl("href");
                    if (href.isBlank())
                        continue;
                    if (mapping.crawl.detailLinkExcludePattern != null
                            && href.contains(mapping.crawl.detailLinkExcludePattern))
                        continue;
                    detailUrls.add(href);
                }
            } catch (Exception e) {
                logger.severe("Failed to fetch category page: " + currentUrl + " — " + e.getMessage());
            }
        }

        return detailUrls;
    }

    // -------------------------------------------------------------------------
    // Scrape
    // -------------------------------------------------------------------------

    private static List<Map<String, Object>> scrapeProcedures(Set<String> detailUrls, ScraperMapping mapping) {
        List<Map<String, Object>> procedures = new ArrayList<>();
        for (String url : detailUrls) {
            try {
                Optional<Map<String, Object>> procedure = scrapeProcedure(url, mapping);
                if (procedure.isPresent()) {
                    procedures.add(procedure.get());
                    logger.info("Scraped: " + url);
                } else {
                    logger.info("Skipped (no valid content): " + url);
                }
            } catch (Exception e) {
                logger.severe("Failed to scrape: " + url + " — " + e.getMessage());
            }
        }
        return procedures;
    }

    private static Optional<Map<String, Object>> scrapeProcedure(String url, ScraperMapping mapping)
            throws IOException {
        @SuppressWarnings("null")
        Document doc = Jsoup.connect(url).get();

        Map<String, Object> procedure = new LinkedHashMap<>();
        // procedureId is deterministic: same URL always produces the same ID.
        procedure.put("procedureId", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());

        for (Map.Entry<String, FieldExtractionRule> entry : mapping.fields.entrySet()) {
            procedure.put(entry.getKey(), extractFieldValue(doc, entry.getValue()));
        }

        procedure.put("url", url);

        String title = (String) procedure.get("title");
        if (shouldSkip(title, mapping.skip)) {
            return Optional.empty();
        }

        return Optional.of(procedure);
    }

    private static String extractFieldValue(Document doc, FieldExtractionRule rule) {
        if (rule.multiple) {
            @SuppressWarnings("null")
            Elements elements = doc.select(rule.selector);
            StringBuilder sb = new StringBuilder();
            for (Element el : elements) {
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    if (!sb.isEmpty())
                        sb.append("\n");
                    sb.append(text);
                }
            }
            return sb.toString();
        } else {
            @SuppressWarnings("null")
            Element el = doc.selectFirst(rule.selector);
            return el != null ? el.text().trim() : "";
        }
    }

    // Package-private for unit testing.
    static boolean shouldSkip(String title, SkipConfig skip) {
        if (title == null || title.isBlank())
            return true;
        if (skip == null || skip.whenTitleEmptyOrEquals == null)
            return false;
        for (String forbidden : skip.whenTitleEmptyOrEquals) {
            if (title.equalsIgnoreCase(forbidden))
                return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildRootJson(String sourceUrl, List<Map<String, Object>> procedures) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("sourceUrl", sourceUrl);
        root.put("procedures", procedures);
        return root;
    }

    private static File writeToLocalFile(String cityId, Map<String, Object> rootJson) throws IOException {
        File out = new File("procedures-" + cityId + ".json");
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

    // -------------------------------------------------------------------------
    // Mapping model — deserialized from procedures-mapping-<cityId>.json
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ScraperMapping {
        // cityId and cityName are intentionally absent from the model:
        // they are documentation-only fields in the JSON, not consumed by the scraper.
        public String baseUrl;
        /** AWS region of the target S3 bucket (e.g. "eu-west-1"). */
        public String s3Region;
        public CrawlConfig crawl;
        /**
         * Field extraction rules keyed by output field name (e.g. "title",
         * "description").
         * Insertion order is preserved — use a schema that puts "title" first.
         */
        public Map<String, FieldExtractionRule> fields = new LinkedHashMap<>();
        public SkipConfig skip;
        /** Events configuration for scraping city events */
        public EventsConfig events;
        /** News configuration for scraping city news categories/articles */
        public NewsConfig news;
        /** City-info configuration for scraping municipal information pages */
        public CityInfoConfig cityInfo;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CrawlConfig {
        /** CSS selector for navigating category/topic pages from the base URL. */
        public String categoryLinkSelector;
        /** CSS selector that matches procedure detail page links. */
        public String detailLinkSelector;
        /**
         * Substring pattern — links whose href contains this string are excluded. Null
         * = no exclusion.
         */
        public String detailLinkExcludePattern;
        /** Additional seed URLs to crawl alongside baseUrl (e.g. SIAC portal). */
        public List<String> additionalSeeds = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FieldExtractionRule {
        /** CSS selector applied to the detail page document. */
        public String selector;
        /**
         * If {@code false}: take the text of the first matching element (selectFirst).
         * If {@code true}: take the text of all matching elements joined with newline.
         */
        public boolean multiple;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SkipConfig {
        /**
         * A procedure page is silently skipped when the extracted title is blank
         * or case-insensitively matches any entry in this list.
         */
        public List<String> whenTitleEmptyOrEquals = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class EventsConfig {
        /** Base URL for the events agenda page */
        public String baseUrl;
        /** Crawl configuration for events */
        public EventCrawlConfig crawl;
        /**
         * Field extraction rules keyed by output field name (e.g. "title",
         * "description", "date").
         * Insertion order is preserved.
         */
        public Map<String, FieldExtractionRule> fields = new LinkedHashMap<>();
        /** External event venue/calendar sites to crawl as additional event sources. */
        public List<EventSeedSite> seedSites = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class EventSeedSite {
        /** Base URL of the external event venue/calendar page to crawl. */
        public String baseUrl;
        /** CSS selector that matches event detail page links on this site. */
        public String eventLinkSelector;
        /**
         * Optional per-site field extraction overrides. When non-empty, these rules
         * are merged over the global {@code events.fields} map when scraping event
         * pages whose host matches this seed site's host.
         */
        public Map<String, FieldExtractionRule> fields = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class EventCrawlConfig {
        /** CSS selector that matches event detail page links */
        public String eventLinkSelector;
        /**
         * Substring pattern — links whose href contains this string are excluded. Null
         * = no exclusion.
         */
        public String eventDetailExcludePattern;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class NewsConfig {
        /** Base URL for the news website */
        public String baseUrl;
        /** Category discovery configuration */
        public NewsDiscoveryConfig discovery;
        /** Crawl configuration for category/article traversal */
        public NewsCrawlConfig crawl;
        /** Optional fallback categories used when discovery is incomplete */
        public List<String> seedCategoryUrls = new ArrayList<>();
        /** Field extraction rules for article pages */
        public Map<String, FieldExtractionRule> fields = new LinkedHashMap<>();
        /** Optional skip rules specific to news pages */
        public NewsSkipConfig skip;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class NewsDiscoveryConfig {
        /** CSS selector that matches category links from the base page */
        public String categoryLinkSelector;
        /** Regex patterns that category URLs must match (any) */
        public List<String> categoryIncludePatterns = new ArrayList<>();
        /** Regex patterns that category URLs must not match */
        public List<String> categoryExcludePatterns = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class NewsCrawlConfig {
        /** CSS selector that matches article detail page links */
        public String articleLinkSelector;
        /** Regex patterns that article URLs must match (any) */
        public List<String> articleIncludePatterns = new ArrayList<>();
        /** Regex patterns that article URLs must not match */
        public List<String> articleExcludePatterns = new ArrayList<>();
        /** CSS selector that matches pagination links between category pages */
        public String paginationLinkSelector;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class NewsSkipConfig {
        /** Skip articles when title is blank or equals any listed value */
        public List<String> whenTitleEmptyOrEquals = new ArrayList<>();
        /** Skip articles when extracted body is blank */
        public boolean whenBodyEmpty = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CityInfoConfig {
        /** Base URL for city information discovery */
        public String baseUrl;
        /** Discovery configuration from landing page */
        public CityInfoDiscoveryConfig discovery;
        /** Crawl configuration for nested menu traversal */
        public CityInfoCrawlConfig crawl;
        /** Field extraction rules for city-info pages */
        public Map<String, FieldExtractionRule> fields = new LinkedHashMap<>();
        /** Optional skip rules specific to city-info pages */
        public CityInfoSkipConfig skip;
        /** External municipal utility sites to include as additional crawl seed themes. */
        public List<String> seedThemeUrls = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CityInfoDiscoveryConfig {
        /** CSS selector for #prat-temes theme links */
        public String pratTemesSelector;
        /** Regex patterns that discovered theme URLs must match (any) */
        public List<String> themeIncludePatterns = new ArrayList<>();
        /** Regex patterns that discovered theme URLs must not match */
        public List<String> themeExcludePatterns = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CityInfoCrawlConfig {
        /** CSS selector for nested/direct links within the tourism menu */
        public String themeMenuSelector;
        /** Regex patterns that nav traversal URLs must match (any) */
        public List<String> navIncludePatterns = new ArrayList<>();
        /** Regex patterns that nav traversal URLs must not match */
        public List<String> navExcludePatterns = new ArrayList<>();
        /** Regex patterns that detail URLs must match (any) */
        public List<String> detailIncludePatterns = new ArrayList<>();
        /** Regex patterns that detail URLs must not match */
        public List<String> detailExcludePatterns = new ArrayList<>();
        /** Max traversal depth from each theme page */
        public int maxDepth = 3;
        /** Max visited pages from each theme page */
        public int maxPages = 120;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CityInfoSkipConfig {
        /** Skip entries when title is blank or equals any listed value */
        public List<String> whenTitleEmptyOrEquals = new ArrayList<>();
        /** Skip entries when extracted body is blank */
        public boolean whenBodyEmpty = true;
    }

}
