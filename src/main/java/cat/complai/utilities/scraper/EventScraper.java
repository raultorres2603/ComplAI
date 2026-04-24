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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Generic city events scraper.
 *
 * <p>
 * Reads the events section from a per-city mapping file from the classpath
 * ({@code scrapers/procedures-mapping-<cityId>.json}) that describes which HTML
 * selectors to use when crawling the city's events website. Produces a
 * {@code events-<cityId>.json} file locally and uploads it to S3.
 *
 * <p>
 * Usage:
 * 
 * <pre>
 *   EVENTS_BUCKET=complai-events-development \
 *   java -cp complai-all.jar cat.complai.utilities.scraper.EventScraper elprat
 * </pre>
 *
 * <p>
 * To add a new city, ensure the mapping file contains an "events" section
 * following the schema of {@code procedures-mapping-elprat.json}.
 */
public class EventScraper {

    private static final Logger logger = Logger.getLogger(EventScraper.class.getName());
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
        Set<String> visitedPageUrls = new HashSet<>();
        Set<String> detailUrls = new LinkedHashSet<>();
        String nextPageUrl = mapping.events.baseUrl;
        int pageCount = 0;

        while (nextPageUrl != null && !visitedPageUrls.contains(nextPageUrl)) {
            pageCount++;
            visitedPageUrls.add(nextPageUrl);
            try {
                Document doc = fetchDocument(nextPageUrl);
                int eventsOnPage = 0;
                var eventLinks = doc.select(mapping.events.crawl.eventLinkSelector);
                for (Element link : eventLinks) {
                    String href = link.absUrl("href");
                    if (href.isBlank())
                        continue;
                    if (mapping.events.crawl.eventDetailExcludePattern != null
                            && href.contains(mapping.events.crawl.eventDetailExcludePattern))
                        continue;
                    if (detailUrls.add(href)) {
                        eventsOnPage++;
                    }
                }
                logger.info("Page " + pageCount + ": Found " + eventsOnPage + " event links (" + nextPageUrl + ")");

                // Find next page link in Drupal pager: look for "li.pager-item a"
                // that contains "next" or "següent" text, or rel=next attribute
                nextPageUrl = findNextPageUrl(doc, visitedPageUrls);
            } catch (Exception e) {
                logger.severe("Failed to fetch event page: " + nextPageUrl + " — " + e.getMessage());
                break;
            }
        }
        logger.info("Total event detail URLs found: " + detailUrls.size() + " across " + pageCount + " pages");

        // Crawl external event venue seed sites (single-page, no pagination)
        if (mapping.events.seedSites != null) {
            for (ProcedureScraper.EventSeedSite seed : mapping.events.seedSites) {
                if (seed.baseUrl == null || seed.baseUrl.isBlank()) continue;
                if (seed.eventLinkSelector == null || seed.eventLinkSelector.isBlank()) continue;
                try {
                    Document doc = fetchDocument(seed.baseUrl);
                    var seedLinks = doc.select(seed.eventLinkSelector);
                    int added = 0;
                    for (Element link : seedLinks) {
                        String href = link.absUrl("href");
                        if (!href.isBlank() && detailUrls.add(href)) {
                            added++;
                        }
                    }
                    logger.info("Seed site " + seed.baseUrl + ": found " + added + " event links");
                } catch (Exception e) {
                    logger.severe("Failed to crawl event seed site: " + seed.baseUrl + " — " + e.getMessage());
                }
            }
        }

        return detailUrls;
    }

    private static String findNextPageUrl(Document doc, Set<String> visitedPageUrls) {
        // Try rel=next first (most reliable)
        Element nextLink = doc.selectFirst("a[rel=next]");
        if (nextLink != null) {
            String absNext = nextLink.absUrl("href");
            if (!absNext.isBlank() && !visitedPageUrls.contains(absNext)) {
                return absNext;
            }
        }

        // Try Drupal pager: look for li.pager-next a (contains "next" or "següent"
        // text)
        nextLink = doc.selectFirst("li.pager-next a");
        if (nextLink != null) {
            String absNext = nextLink.absUrl("href");
            if (!absNext.isBlank() && !visitedPageUrls.contains(absNext)) {
                return absNext;
            }
        }

        // Fallback: look for any link with next/previous indicators in text
        for (Element link : doc.select("a")) {
            String text = link.text().trim().toLowerCase();
            String href = link.absUrl("href");
            if ((text.contains("next") || text.contains("següent") || text.equals("›"))
                    && !href.isBlank() && !visitedPageUrls.contains(href)) {
                return href;
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Scrape
    // -------------------------------------------------------------------------

    private static List<Map<String, Object>> scrapeEvents(Set<String> detailUrls,
            ProcedureScraper.ScraperMapping mapping) {
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

    private static Map<String, ProcedureScraper.FieldExtractionRule> resolveFields(
            String url, ProcedureScraper.ScraperMapping mapping) {
        if (mapping.events.seedSites != null) {
            for (ProcedureScraper.EventSeedSite seed : mapping.events.seedSites) {
                if (seed.fields == null || seed.fields.isEmpty()) continue;
                try {
                    String seedHost = new java.net.URI(seed.baseUrl).getHost();
                    String urlHost  = new java.net.URI(url).getHost();
                    if (seedHost != null && seedHost.equalsIgnoreCase(urlHost)) {
                        Map<String, ProcedureScraper.FieldExtractionRule> merged =
                                new LinkedHashMap<>(mapping.events.fields);
                        merged.putAll(seed.fields);
                        return merged;
                    }
                } catch (Exception ignored) {
                    // Malformed URI — skip this seed entry and fall through to global.
                }
            }
        }
        return mapping.events.fields;
    }

    private static Optional<Map<String, Object>> scrapeEvent(String url, ProcedureScraper.ScraperMapping mapping)
            throws IOException {
        Document doc = fetchDocument(url);

        Map<String, Object> event = new LinkedHashMap<>();
        // eventId is deterministic: same URL always produces the same ID.
        event.put("eventId", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());

        Map<String, ProcedureScraper.FieldExtractionRule> fields = resolveFields(url, mapping);
        for (Map.Entry<String, ProcedureScraper.FieldExtractionRule> entry : fields.entrySet()) {
            event.put(entry.getKey(), extractFieldValue(doc, entry.getValue()));
        }

        event.put("url", url);

        String title = (String) event.get("title");
        if (shouldSkip(title, mapping.skip)) {
            return Optional.empty();
        }

        return Optional.of(event);
    }

    private static Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get();
    }

    private static String extractFieldValue(Document doc, ProcedureScraper.FieldExtractionRule rule) {
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

    // Package-private for unit testing.
    static boolean shouldSkip(String title, ProcedureScraper.SkipConfig skip) {
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
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build()) {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    Paths.get(file.getAbsolutePath()));
            logger.info("Uploaded to S3 — bucket=" + bucket + " key=" + key);
        }
    }
}
