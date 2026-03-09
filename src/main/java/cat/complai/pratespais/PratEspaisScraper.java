package cat.complai.pratespais;

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
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

public class PratEspaisScraper {

    private static final Logger logger = Logger.getLogger(PratEspaisScraper.class.getName());
    public static void main(String[] args) throws IOException {
        String baseUrl = "https://tramits.pratespais.com/Ciutadania/TramitsTemes.aspx";
        String bucket = System.getenv("PROCEDURES_BUCKET");
        String key = "procedures.json";

        if (bucket == null || bucket.isBlank()) {
            System.err.println("Set PROCEDURES_BUCKET env var to your S3 bucket name.");
            System.exit(1);
        }

        Set<String> categoryUrlsToVisit = new HashSet<>();
        Set<String> visitedCategoryUrls = new HashSet<>();
        Set<String> detailUrls = new HashSet<>();

        // We start on the main page
        categoryUrlsToVisit.add(baseUrl);

        logger.info("Crawling categories to find all procedures...");

        // 1. Search for all category URLs starting from the main page, and collect detail URLs
        while (!categoryUrlsToVisit.isEmpty()) {
            String currentUrl = categoryUrlsToVisit.iterator().next();
            categoryUrlsToVisit.remove(currentUrl);

            // If we've already visited this category URL, skip it
            if (!visitedCategoryUrls.add(currentUrl)) continue;

            try {
                Document doc = Jsoup.connect(currentUrl).get();

                // Take all category links from the current page
                Elements categoryLinks = doc.select("div.LlistaTemes.LlistatMenu ul.llistat a[href]");
                for (Element link : categoryLinks) {
                    String href = link.absUrl("href");
                    if (!visitedCategoryUrls.contains(href)) {
                        categoryUrlsToVisit.add(href);
                    }
                }

                // Take all procedure detail links from the current page
                Elements procLinks = doc.select("a[href*='DetallTramit.aspx']");
                for (Element link : procLinks) {
                    String href = link.absUrl("href");
                    // Do not include links that have "Tramitar=True" as they are not the actual detail pages but the ones that trigger the process
                    if (!href.contains("Tramitar=True")) {
                        detailUrls.add(href);
                    }
                }
            } catch (Exception e) {
                logger.severe("Failed to fetch category page: " + currentUrl + " - " + e.getMessage());
            }
        }

        logger.info("Found " + detailUrls.size() + " unique procedures. Starting scrape...");

        // 2. Export every procedure detail page into a Map and add it to the list of procedures
        List<Map<String, String>> procedures = new ArrayList<>();

        for (String href : detailUrls) {
            try {
                Document doc = Jsoup.connect(href).get();
                Map<String, String> proc = new HashMap<>();

                String procId = UUID.nameUUIDFromBytes(href.getBytes()).toString();
                proc.put("procedureId", procId);
                proc.put("url", href);

                Element title = doc.selectFirst("span.iconesTramit, span.titolTramit");
                proc.put("title", title != null ? title.text().trim() : "");

                Element desc = doc.selectFirst(".introduccio");
                proc.put("description", desc != null ? desc.text().trim() : "");

                Elements reqLists = doc.select(".introduccio ul");
                StringBuilder reqText = new StringBuilder();
                for (Element ul : reqLists) {
                    reqText.append(ul.text()).append("\n");
                }
                proc.put("requirements", reqText.toString().trim());

                Element steps = doc.selectFirst(".block, .blockFirst");
                proc.put("steps", steps != null ? steps.text().trim() : "");

                // get only the text content of the page, excluding headers, footers, and navigation
                if (!proc.get("title").isEmpty() && !proc.get("title").equalsIgnoreCase("Portal de Tràmits")) {
                    procedures.add(proc);
                    logger.info("Successfully scraped: " + href);
                } else {
                    logger.info("Skipped (no valid content found): " + href);
                }
            } catch (Exception e) {
                logger.severe("Failed to scrape procedure page: " + href + " - " + e.getMessage());
            }
        }

        // 3. Create a JSON file with the list of procedures, including metadata about the generation time and source URL
        Map<String, Object> rootJson = new HashMap<>();
        rootJson.put("generatedAt", Instant.now().toString());
        rootJson.put("sourceUrl", baseUrl);
        rootJson.put("procedures", procedures);

        File out = new File("procedures.json");
        try (FileWriter fw = new FileWriter(out)) {
            fw.write(new ObjectMapper().writeValueAsString(rootJson));
        }

        // 4. Upload the JSON file to the specified S3 bucket
        try (S3Client s3 = S3Client.builder()
                .region(Region.EU_WEST_1) // Change to your region
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                    Paths.get("procedures.json"));

            logger.info("Uploaded procedures.json to S3 bucket: " + bucket);
        }
    }
}