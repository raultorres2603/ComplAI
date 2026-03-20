package cat.complai.scrapper;

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
 * Generic city events scraper.
 *
 * <p>Reads the events section from a per-city mapping file from the classpath
 * ({@code scrapers/procedures-mapping-<cityId>.json}) that describes which HTML
 * selectors to use when crawling the city's events website. Produces a
 * {@code events-<cityId>.json} file locally and uploads it to S3.
 *
 * <p>Usage:
 * <pre>
 *   EVENTS_BUCKET=complai-events-development \
 *   java -cp complai-all.jar cat.complai.scrapper.EventScraper elprat
 * </pre>
 *
 * <p>To add a new city, ensure the mapping file contains an "events" section
 * following the schema of {@code procedures-mapping-elprat.json}.
 */
public class EventScraper {

    private static final Logger logger = Logger.getLogger(EventScraper.class.getName());
    private static final String MAPPING_RESOURCE_PATTERN = "/scrapers/procedures-mapping-%s.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: EventScraper <cityId>");
            System.err.println("  cityId — must match a procedures-mapping-<cityId>.json in resources/scrapers");
            System.exit(1);
        }

        String cityId = args[0].trim().toLowerCase(Locale.ROOT);

        String bucket = System.getenv("EVENTS_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println("EVENTS_BUCKET environment variable is required.");
            System.exit(1);
        }

        ProcedureScraper.ScraperMapping mapping = ProcedureScraper.loadMapping(cityId);
        String s3Key = "events-" + cityId + ".json";

        if (mapping.events == null) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 'events' section");
        }

        Set<String> detailUrls = crawlEventDetailUrls(mapping);
        logger.info("Found " + detailUrls.size() + " event detail URLs — city=" + cityId);

        List<Map<String, Object>> events = scrapeEvents(detailUrls, mapping);
        logger.info("Scraped " + events.size() + " valid events — city=" + cityId);

        Map<String, Object> rootJson = buildRootJson(mapping.events.baseUrl, events);
        File outputFile = writeToLocalFile(cityId, rootJson);

        uploadToS3(outputFile, s3Key, bucket, mapping.s3Region);
        logger.info("Upload complete — bucket=" + bucket + " key=" + s3Key);
    }

    // -------------------------------------------------------------------------
    // Crawl
    // -------------------------------------------------------------------------

    private static Set<String> crawlEventDetailUrls(ProcedureScraper.ScraperMapping mapping) {
        Set<String> pendingCategoryUrls = new LinkedHashSet<>();
        Set<String> visitedCategoryUrls = new HashSet<>();
        Set<String> detailUrls = new LinkedHashSet<>();

        pendingCategoryUrls.add(mapping.events.baseUrl);

        while (!pendingCategoryUrls.isEmpty()) {
            String currentUrl = pendingCategoryUrls.iterator().next();
            pendingCategoryUrls.remove(currentUrl);

            if (!visitedCategoryUrls.add(currentUrl)) continue;

            try {
                Document doc = Jsoup.connect(currentUrl).get();

                // For events, we typically only need to find event detail links
                // No category navigation like procedures
                for (Element link : doc.select(mapping.events.crawl.eventLinkSelector)) {
                    String href = link.absUrl("href");
                    if (href.isBlank()) continue;
                    if (mapping.events.crawl.eventDetailExcludePattern != null
                            && href.contains(mapping.events.crawl.eventDetailExcludePattern)) continue;
                    detailUrls.add(href);
                }
            } catch (Exception e) {
                logger.severe("Failed to fetch event page: " + currentUrl + " — " + e.getMessage());
            }
        }

        return detailUrls;
    }

    // -------------------------------------------------------------------------
    // Scrape
    // -------------------------------------------------------------------------

    private static List<Map<String, Object>> scrapeEvents(Set<String> detailUrls, ProcedureScraper.ScraperMapping mapping) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String url : detailUrls) {
            try {
                Optional<Map<String, Object>> event = scrapeEvent(url, mapping);
                if (event.isPresent()) {
                    events.add(event.get());
                    logger.info("Scraped: " + url);
                } else {
                    logger.info("Skipped (no valid content): " + url);
                }
            } catch (Exception e) {
                logger.severe("Failed to scrape: " + url + " — " + e.getMessage());
            }
        }
        return events;
    }

    private static Optional<Map<String, Object>> scrapeEvent(String url, ProcedureScraper.ScraperMapping mapping) throws IOException {
        Document doc = Jsoup.connect(url).get();

        Map<String, Object> event = new LinkedHashMap<>();
        // eventId is deterministic: same URL always produces the same ID.
        event.put("eventId", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());

        for (Map.Entry<String, ProcedureScraper.FieldExtractionRule> entry : mapping.events.fields.entrySet()) {
            event.put(entry.getKey(), extractFieldValue(doc, entry.getValue()));
        }

        event.put("url", url);

        String title = (String) event.get("title");
        if (shouldSkip(title, mapping.skip)) {
            return Optional.empty();
        }

        return Optional.of(event);
    }

    private static String extractFieldValue(Document doc, ProcedureScraper.FieldExtractionRule rule) {
        if (rule.multiple) {
            Elements elements = doc.select(rule.selector);
            StringBuilder sb = new StringBuilder();
            for (Element el : elements) {
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(text);
                }
            }
            return sb.toString();
        } else {
            Element el = doc.selectFirst(rule.selector);
            return el != null ? el.text().trim() : "";
        }
    }

    // Package-private for unit testing.
    static boolean shouldSkip(String title, ProcedureScraper.SkipConfig skip) {
        if (title == null || title.isBlank()) return true;
        if (skip == null || skip.whenTitleEmptyOrEquals == null) return false;
        for (String forbidden : skip.whenTitleEmptyOrEquals) {
            if (title.equalsIgnoreCase(forbidden)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildRootJson(String sourceUrl, List<Map<String, Object>> events) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("sourceUrl", sourceUrl);
        root.put("events", events);
        return root;
    }

    private static File writeToLocalFile(String cityId, Map<String, Object> rootJson) throws IOException {
        File out = new File("events-" + cityId + ".json");
        try (FileWriter fw = new FileWriter(out, StandardCharsets.UTF_8)) {
            fw.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rootJson));
        }
        logger.info("Wrote local file: " + out.getAbsolutePath());
        return out;
    }

    private static void uploadToS3(File file, String key, String bucket, String region) {
        try (S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    Paths.get(file.getAbsolutePath()));
            logger.info("Uploaded to S3 — bucket=" + bucket + " key=" + key);
        }
    }
}
