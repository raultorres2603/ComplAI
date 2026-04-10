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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Scraper for the city's open-data / transparency portal on seu-e.cat.
 *
 * <p>
 * Reads the {@code transparency} section from a per-city mapping file
 * ({@code scrapers/procedures-mapping-<cityId>.json}), crawls the portal using
 * a BFS category → detail approach, and uploads
 * {@code transparency-<cityId>.json} to the {@code TRANSPARENCY_BUCKET}.
 *
 * <p>
 * Usage:
 *
 * <pre>
 *   TRANSPARENCY_BUCKET=complai-transparency-development \
 *   java -cp complai-all.jar cat.complai.scrapper.TransparencyScraper elprat
 * </pre>
 */
public class TransparencyScraper {

    static final Logger logger = Logger.getLogger(TransparencyScraper.class.getName());
    static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("Usage: TransparencyScraper <cityId>");
            System.err.println("  cityId — must match a procedures-mapping-<cityId>.json in resources/scrapers");
            System.exit(1);
        }

        String cityId = args[0].trim().toLowerCase(Locale.ROOT);

        String bucket = System.getenv("TRANSPARENCY_BUCKET");
        if (bucket == null || bucket.isBlank()) {
            System.err.println("TRANSPARENCY_BUCKET environment variable is required.");
            System.exit(1);
        }

        ProcedureScraper.ScraperMapping mapping = ProcedureScraper.loadMapping(cityId);
        validateTransparencyConfig(mapping, cityId);

        String s3Key = "transparency-" + cityId + ".json";

        Set<String> detailUrls = crawlDetailUrls(mapping.transparency);
        logger.info("Found " + detailUrls.size() + " transparency detail URLs — city=" + cityId);

        List<Map<String, Object>> items = scrapeItems(detailUrls, mapping.transparency);
        logger.info("Scraped " + items.size() + " valid transparency items — city=" + cityId);

        Map<String, Object> rootJson = buildRootJson(mapping.transparency.baseUrl, items);
        File outputFile = writeToLocalFile(cityId, rootJson);

        String region = System.getenv("TRANSPARENCY_REGION");
        if (region == null || region.isBlank()) {
            region = mapping.s3Region;
        }
        uploadToS3(outputFile, s3Key, bucket, region);
        logger.info("Upload complete — bucket=" + bucket + " key=" + s3Key);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    static void validateTransparencyConfig(ProcedureScraper.ScraperMapping mapping, String cityId) {
        if (mapping.transparency == null) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'transparency' section");
        }
        if (mapping.transparency.baseUrl == null || mapping.transparency.baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'transparency.baseUrl'");
        }
        if (mapping.transparency.crawl == null) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'transparency.crawl'");
        }
        if (mapping.transparency.crawl.detailLinkSelector == null
                || mapping.transparency.crawl.detailLinkSelector.isBlank()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' is missing 'transparency.crawl.detailLinkSelector'");
        }
        if (mapping.transparency.fields == null || mapping.transparency.fields.isEmpty()) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' has no transparency field extraction rules");
        }
        if (!mapping.transparency.fields.containsKey("title")) {
            throw new IllegalStateException(
                    "Mapping for city='" + cityId + "' must define transparency field 'title'");
        }
        if (mapping.s3Region == null || mapping.s3Region.isBlank()) {
            throw new IllegalStateException("Mapping for city='" + cityId + "' is missing 's3Region'");
        }
    }

    // -------------------------------------------------------------------------
    // Crawl
    // -------------------------------------------------------------------------

    private static Set<String> crawlDetailUrls(ProcedureScraper.TransparencyConfig config) {
        Set<String> visitedCategoryUrls = new LinkedHashSet<>();
        Set<String> detailUrls = new LinkedHashSet<>();
        Set<String> pendingCategoryUrls = new LinkedHashSet<>();
        pendingCategoryUrls.add(config.baseUrl);

        while (!pendingCategoryUrls.isEmpty()) {
            String currentUrl = pendingCategoryUrls.iterator().next();
            pendingCategoryUrls.remove(currentUrl);

            if (visitedCategoryUrls.contains(currentUrl)) continue;
            visitedCategoryUrls.add(currentUrl);

            try {
                @SuppressWarnings("null")
                Document doc = Jsoup.connect(currentUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .timeout(20000)
                        .get();

                // Discover sub-category links
                if (config.crawl.categoryLinkSelector != null && !config.crawl.categoryLinkSelector.isBlank()) {
                    @SuppressWarnings("null")
                    var categoryLinks = doc.select(config.crawl.categoryLinkSelector);
                    for (Element link : categoryLinks) {
                        String href = link.absUrl("href");
                        if (href.isBlank()) continue;
                        if (config.crawl.categoryIncludePattern != null
                                && !Pattern.compile(config.crawl.categoryIncludePattern).matcher(href).find()) {
                            continue;
                        }
                        if (config.crawl.categoryExcludePattern != null
                                && Pattern.compile(config.crawl.categoryExcludePattern).matcher(href).find()) {
                            continue;
                        }
                        if (!visitedCategoryUrls.contains(href)) {
                            pendingCategoryUrls.add(href);
                        }
                    }
                }

                // Collect detail links
                if (config.crawl.detailLinkSelector != null && !config.crawl.detailLinkSelector.isBlank()) {
                    @SuppressWarnings("null")
                    var detailLinks = doc.select(config.crawl.detailLinkSelector);
                    for (Element link : detailLinks) {
                        String href = link.absUrl("href");
                        if (href.isBlank()) continue;
                        detailUrls.add(href);
                    }
                }
            } catch (Exception e) {
                logger.severe("Failed to fetch transparency category page: " + currentUrl + " — " + e.getMessage());
            }
        }

        return detailUrls;
    }

    // -------------------------------------------------------------------------
    // Scrape
    // -------------------------------------------------------------------------

    private static List<Map<String, Object>> scrapeItems(Set<String> urls,
            ProcedureScraper.TransparencyConfig config) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String url : urls) {
            try {
                @SuppressWarnings("null")
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .timeout(20000)
                        .get();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("transparencyId", UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString());

                for (Map.Entry<String, ProcedureScraper.FieldExtractionRule> entry : config.fields.entrySet()) {
                    item.put(entry.getKey(), extractFieldValue(doc, entry.getValue()));
                }

                item.put("url", url);

                String title = (String) item.get("title");
                if (shouldSkip(title, config.skip)) {
                    logger.info("Skipped: " + url);
                    continue;
                }

                result.add(item);
                logger.info("Scraped: " + url);
            } catch (Exception e) {
                logger.severe("Failed to scrape transparency page: " + url + " — " + e.getMessage());
            }
        }
        return result;
    }

    private static String extractFieldValue(Document doc, ProcedureScraper.FieldExtractionRule rule) {
        if (rule.multiple) {
            @SuppressWarnings("null")
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
            @SuppressWarnings("null")
            Element el = doc.selectFirst(rule.selector);
            return el != null ? el.text().trim() : "";
        }
    }

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

    private static Map<String, Object> buildRootJson(String sourceUrl, List<Map<String, Object>> items) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("sourceUrl", sourceUrl);
        root.put("transparency", items);
        return root;
    }

    private static File writeToLocalFile(String cityId, Map<String, Object> rootJson) throws IOException {
        File out = new File("transparency-" + cityId + ".json");
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
