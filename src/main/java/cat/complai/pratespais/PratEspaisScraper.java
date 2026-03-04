package cat.complai.pratespais;

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
            Document doc = Jsoup.connect(href).get();
            Map<String, String> proc = new HashMap<>();
            proc.put("url", href);
            proc.put("title", doc.selectFirst("h1").text());
            proc.put("description", doc.selectFirst(".description, .descripcio").text());
            proc.put("requirements", doc.selectFirst(".requirements, .requisits").text());
            proc.put("steps", doc.selectFirst(".steps, .passos").text());
            proc.put("fees", doc.selectFirst(".fees, .taxes").text());
            proc.put("office", doc.selectFirst(".office, .oficina").text());
            proc.put("deadlines", doc.selectFirst(".deadlines, .terminis").text());
            procedures.add(proc);
        }
        // 2. Write to local file
        File out = new File("procedures.json");
        try (FileWriter fw = new FileWriter(out)) {
            fw.write("{\"procedures\": ");
            fw.write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(procedures));
            fw.write("}");
        }
        // 3. Upload to S3
        S3Client s3 = S3Client.builder()
                .region(Region.EU_WEST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                Paths.get("procedures.json"));
        System.out.println("Uploaded procedures.json to S3 bucket: " + bucket);
    }
}

