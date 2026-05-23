package cat.complai.utilities.scraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jspecify.annotations.NonNull;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Scraper for Generalitat de Catalunya procedures from tramits.gencat.cat.
 *
 * <p>
 * This scraper is specifically designed for the Generalitat's central procedures repository,
 * which contains common procedures applicable across all Catalan municipalities.
 * It's used as a fallback when:
 * <ul>
 *   <li>User explicitly asks for "Generalitat" or "Generalitat de Catalunya" procedures</li>
 *   <li>City-specific procedures don't return any matching results</li>
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>
 *   PROCEDURES_BUCKET=complai-procedures-development \
 *   java -cp complai-all.jar cat.complai.utilities.scraper.GencatProcedureScraper
 * </pre>
 */
public class GencatProcedureScraper {

    private static final Logger logger = Logger.getLogger(GencatProcedureScraper.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MAPPING_RESOURCE = "/scrapers/procedures-mapping-gencat.json";
    private static final int CONNECT_TIMEOUT_MS = 60000; // 60 seconds for slow pages
    private static final int MAX_CATEGORY_PAGES = 50; // Limit crawling to prevent timeout

    static void main(String[] args) throws IOException {
        String bucket = System.getenv("PROCEDURES_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println("PROCEDURES_BUCKET environment variable is required.");
            System.exit(1);
        }

        ScraperMapping mapping = loadMapping();
        String s3Key = "procedures-gencat.json";

        Set<String> detailUrls = crawlProcedureDetailUrls(mapping);
        logger.info("Found " + detailUrls.size() + " procedure detail URLs");

        List<Map<String, Object>> procedures = scrapeProcedures(detailUrls, mapping);
        logger.info("Scraped " + procedures.size() + " valid procedures");

        Map<String, Object> rootJson = buildRootJson(mapping.baseUrl, procedures);
        File outputFile = writeToLocalFile(rootJson);

        uploadToS3(outputFile, s3Key, bucket, mapping.s3Region);
        logger.info("Upload complete — bucket=" + bucket + " key=" + s3Key);
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    static ScraperMapping loadMapping() throws IOException {
        InputStream is = GencatProcedureScraper.class.getResourceAsStream(MAPPING_RESOURCE);
        if (is == null) {
            throw new IOException("No mapping found at: " + MAPPING_RESOURCE);
        }
        return MAPPER.readValue(is, ScraperMapping.class);
    }

    // -------------------------------------------------------------------------
    // Crawl
    // -------------------------------------------------------------------------

    private static Document fetchDocument(@NonNull String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .timeout(CONNECT_TIMEOUT_MS)
                .get();
        return doc;
    }

    private static Set<String> crawlProcedureDetailUrls(ScraperMapping mapping) {
        Set<String> pendingCategoryUrls = new LinkedHashSet<>();
        Set<String> visitedCategoryUrls = new HashSet<>();
        Set<String> detailUrls = new LinkedHashSet<>();

        pendingCategoryUrls.add(mapping.baseUrl);
        
        // Add seed URLs that directly show procedure lists (avoiding AJAX)
        if (mapping.crawl.additionalSeeds != null) {
            pendingCategoryUrls.addAll(mapping.crawl.additionalSeeds);
        }

        while (!pendingCategoryUrls.isEmpty()) {
            String currentUrl = pendingCategoryUrls.iterator().next();
            pendingCategoryUrls.remove(currentUrl);

            if (!visitedCategoryUrls.add(currentUrl))
                continue;

            // Stop crawling if we've visited too many category pages
            if (visitedCategoryUrls.size() > MAX_CATEGORY_PAGES) {
                logger.warning("Reached max category pages limit (" + MAX_CATEGORY_PAGES + "), stopping crawl");
                break;
            }

            try {
                Document doc = fetchDocument(currentUrl);

                if (mapping.crawl.categoryLinkSelector != null && !mapping.crawl.categoryLinkSelector.isBlank()) {
                    var categoryLinks = doc.select(mapping.crawl.categoryLinkSelector);
                    for (Element link : categoryLinks) {
                        String href = link.absUrl("href");
                        if (!href.isBlank() && !visitedCategoryUrls.contains(href)
                                && (href.contains("?tema=") || href.contains("?mostraFulls"))) {
                            pendingCategoryUrls.add(href);
                        }
                    }
                }

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
        Document doc = fetchDocument(url);

        Map<String, Object> procedure = new LinkedHashMap<>();
        procedure.put("procedureId", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());

        for (Map.Entry<String, FieldExtractionRule> entry : mapping.fields.entrySet()) {
            procedure.put(entry.getKey(), extractFieldValue(doc, entry.getValue()));
        }

        procedure.put("url", url);

        Object rawTitle = procedure.get("title");
        if (!(rawTitle instanceof String title) || shouldSkip(title, mapping.skip)) {
            return Optional.empty();
        }

        return Optional.of(procedure);
    }

    private static String extractFieldValue(Document doc, FieldExtractionRule rule) {
        if (rule.multiple) {
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
            Element el = doc.selectFirst(rule.selector);
            return el != null ? el.text().trim() : "";
        }
    }

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

    private static File writeToLocalFile(Map<String, Object> rootJson) throws IOException {
        File out = new File("procedures-gencat.json");
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
    // Mapping model
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ScraperMapping {
        public String baseUrl;
        public String s3Region;
        public CrawlConfig crawl;
        public Map<String, FieldExtractionRule> fields = new LinkedHashMap<>();
        public SkipConfig skip;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CrawlConfig {
        public String categoryLinkSelector;
        public String detailLinkSelector;
        public String detailLinkExcludePattern;
        public List<String> additionalSeeds = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FieldExtractionRule {
        public String selector;
        public boolean multiple;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SkipConfig {
        public List<String> whenTitleEmptyOrEquals = new ArrayList<>();
    }
}