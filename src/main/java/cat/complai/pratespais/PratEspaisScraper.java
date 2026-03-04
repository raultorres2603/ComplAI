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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PratEspaisScraper {
    public static void main(String[] args) throws IOException {
        String baseUrl = "https://tramits.pratespais.com/Ciutadania/";
        String bucket = System.getenv("PROCEDURES_BUCKET");
        String key = "procedures.json";

        if (bucket == null || bucket.isBlank()) {
            System.err.println("Set PROCEDURES_BUCKET env var to your S3 bucket name.");
            System.exit(1);
        }

        // 1. Scrape index page
        Document index = Jsoup.connect(baseUrl).get();

        // FIX 1: Look specifically for links that go to the detail pages
        Elements links = index.select("a[href*='DetallTramit.aspx']");

        List<Map<String, String>> procedures = new ArrayList<>();

        // FIX 2: Keep track of visited URLs to avoid scraping the same procedure multiple times
        Set<String> visitedUrls = new HashSet<>();

        for (Element link : links) {
            String href = link.absUrl("href");

            // Skip if we already scraped this exact procedure URL
            if (!visitedUrls.add(href)) continue;

            try {
                Document doc = Jsoup.connect(href).get();
                Map<String, String> proc = new HashMap<>();
                proc.put("url", href);

                // FIX 3: YOU NEED TO UPDATE THESE SELECTORS!
                // Inspect the DetallTramit.aspx page in your browser to find the real IDs or classes.
                // For ASPX pages, they usually look like "#ctl00_MainContent_lblDescripcio"

                Element title = doc.selectFirst(".iconesTramit, .senseCertificat");
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
                // Prevent a single bad page from crashing the whole scraping job
                System.err.println("Failed to scrape procedure page: " + href + " - " + e.getMessage());
            }
        }

        // 2. Write to local file with the correct JSON structure requested in README.md
        Map<String, Object> rootJson = new HashMap<>();
        rootJson.put("generatedAt", Instant.now().toString());
        rootJson.put("sourceUrl", baseUrl);
        rootJson.put("procedures", procedures);

        File out = new File("procedures.json");
        try (FileWriter fw = new FileWriter(out)) {
            // Let ObjectMapper handle the full JSON serialization properly
            fw.write(new ObjectMapper().writeValueAsString(rootJson));
        }

        // 3. Upload to S3
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