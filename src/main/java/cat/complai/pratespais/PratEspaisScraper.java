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
import java.util.List;
import java.util.Map;

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
        Elements links = index.select("a[href^='/Ciutadania/']");
        List<Map<String, String>> procedures = new ArrayList<>();

        for (Element link : links) {
            String href = link.absUrl("href");
            if (!href.contains("/Ciutadania/")) continue;

            try {
                Document doc = Jsoup.connect(href).get();
                Map<String, String> proc = new HashMap<>();
                proc.put("url", href);

                // Added null-safety: prevent NullPointerException if an element doesn't exist on a specific page
                Element title = doc.selectFirst("h1, .title, .titol");
                proc.put("title", title != null ? title.text() : "");

                Element desc = doc.selectFirst(".description, .descripcio");
                proc.put("description", desc != null ? desc.text() : "");

                Element req = doc.selectFirst(".requirements, .requisits");
                proc.put("requirements", req != null ? req.text() : "");

                Element steps = doc.selectFirst(".steps, .passos");
                proc.put("steps", steps != null ? steps.text() : "");

                Element fees = doc.selectFirst(".fees, .taxes");
                proc.put("fees", fees != null ? fees.text() : "");

                Element office = doc.selectFirst(".office, .oficina");
                proc.put("office", office != null ? office.text() : "");

                Element deadlines = doc.selectFirst(".deadlines, .terminis");
                proc.put("deadlines", deadlines != null ? deadlines.text() : "");

                procedures.add(proc);
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
                .region(Region.EU_WEST_1)
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