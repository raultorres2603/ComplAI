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

public class PratEspaisScraper {
    public static void main(String[] args) throws IOException {
        // Updated Base URL to the categorization page
        String baseUrl = "https://tramits.pratespais.com/Ciutadania/TramitsTemes.aspx";
        String bucket = System.getenv("PROCEDURES_BUCKET");
        String key = "procedures.json";

        if (bucket == null || bucket.isBlank()) {
            System.err.println("Set PROCEDURES_BUCKET env var to your S3 bucket name.");
            System.exit(1);
        }

        // 1. Discover all categories and procedure links via crawling
        Set<String> categoryUrlsToVisit = new HashSet<>();
        Set<String> visitedCategoryUrls = new HashSet<>();
        Set<String> detailUrls = new HashSet<>();

        categoryUrlsToVisit.add(baseUrl);

        System.out.println("Crawling categories to find all procedures...");

        while (!categoryUrlsToVisit.isEmpty()) {
            String currentUrl = categoryUrlsToVisit.iterator().next();
            categoryUrlsToVisit.remove(currentUrl);

            // Skip if we've already parsed this category page
            if (!visitedCategoryUrls.add(currentUrl)) continue;

            try {
                Document doc = Jsoup.connect(currentUrl).get();
                Elements links = doc.select("a[href]");

                for (Element link : links) {
                    String href = link.absUrl("href");

                    // If it's a category page (menu links), add it to the crawl queue
                    if (href.contains("TramitsTemes.aspx")) {
                        if (!visitedCategoryUrls.contains(href)) {
                            categoryUrlsToVisit.add(href);
                        }
                    }
                    // If it's a detail page ("Informació" button), save it to scrape later
                    else if (href.contains("DetallTramit.aspx")) {
                        detailUrls.add(href);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch category page: " + currentUrl + " - " + e.getMessage());
            }
        }

        System.out.println("Found " + detailUrls.size() + " unique procedures. Starting scrape...");

        // 2. Scrape each detail page collected
        List<Map<String, String>> procedures = new ArrayList<>();

        for (String href : detailUrls) {
            try {
                Document doc = Jsoup.connect(href).get();
                Map<String, String> proc = new HashMap<>();

                // Add procedureId! ProcedureRagHelper expects this field.
                // We generate a deterministic UUID based on the URL.
                String procId = UUID.nameUUIDFromBytes(href.getBytes()).toString();
                proc.put("procedureId", procId);
                proc.put("url", href);

                // Try to catch the title using various common ASPX title classes
                Element title = doc.selectFirst(".iconesTramit, .senseCertificat, h1, .titolTramit");
                proc.put("title", title != null ? title.text() : "");

                Element desc = doc.selectFirst(".introduccio");
                proc.put("description", desc != null ? desc.text() : "");

                // Requirements: all <ul> inside .introduccio
                Elements reqLists = doc.select(".introduccio ul");
                StringBuilder reqText = new StringBuilder();
                for (Element ul : reqLists) {
                    reqText.append(ul.text()).append("\n");
                }
                proc.put("requirements", reqText.toString().trim());

                Element steps = doc.selectFirst(".block, .blockFirst");
                proc.put("steps", steps != null ? steps.text() : "");

                procedures.add(proc);
                System.out.println("Successfully scraped: " + href);
            } catch (Exception e) {
                System.err.println("Failed to scrape procedure page: " + href + " - " + e.getMessage());
            }
        }

        // 3. Write to local file with the correct JSON structure requested in README.md
        Map<String, Object> rootJson = new HashMap<>();
        rootJson.put("generatedAt", Instant.now().toString());
        rootJson.put("sourceUrl", baseUrl);
        rootJson.put("procedures", procedures);

        File out = new File("procedures.json");
        try (FileWriter fw = new FileWriter(out)) {
            fw.write(new ObjectMapper().writeValueAsString(rootJson));
        }

        // 4. Upload to S3
        try (S3Client s3 = S3Client.builder()
                .region(Region.EU_SOUTH_2)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build(),
                    Paths.get("procedures.json"));

            System.out.println("Uploaded procedures.json to S3 bucket: " + bucket);
        }
    }
}