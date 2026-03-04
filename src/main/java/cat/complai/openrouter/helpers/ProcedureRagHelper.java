package cat.complai.openrouter.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class ProcedureRagHelper {
    public static class Procedure {
        public final String procedureId;
        public final String title;
        public final String description;
        public final String requirements;
        public final String steps;
        public final String fees;
        public final String office;
        public final String deadlines;
        public final String url;

        public Procedure(String procedureId, String title, String description, String requirements, String steps, String fees, String office, String deadlines, String url) {
            this.procedureId = procedureId;
            this.title = title;
            this.description = description;
            this.requirements = requirements;
            this.steps = steps;
            this.fees = fees;
            this.office = office;
            this.deadlines = deadlines;
            this.url = url;
        }
    }

    private static final String[] SEARCH_FIELDS = {"title", "description", "requirements", "steps"};
    private static final int MAX_RESULTS = 3;
    private static final String RESOURCE_PATH = "/procedures.json";
    private static final String ENV_PROCEDURES_BUCKET = "PROCEDURES_BUCKET";
    private static final String ENV_PROCEDURES_KEY = "PROCEDURES_KEY";
    private static final String ENV_PROCEDURES_REGION = "PROCEDURES_REGION";

    private final List<Procedure> procedures;
    private final ByteBuffersDirectory ramDirectory;
    private final Analyzer analyzer;

    public ProcedureRagHelper() throws IOException {
        this.procedures = loadProceduresFromResource();
        this.analyzer = new StandardAnalyzer();
        this.ramDirectory = new ByteBuffersDirectory();
        buildIndex();
    }

    private static InputStream getProceduresInputStream() throws IOException {
        String bucket = System.getenv(ENV_PROCEDURES_BUCKET);
        String key = System.getenv(ENV_PROCEDURES_KEY);
        String region = System.getenv(ENV_PROCEDURES_REGION);
        if (bucket != null && key != null && region != null) {
            // Try to load from S3
            try {
                software.amazon.awssdk.services.s3.S3Client s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                        .region(software.amazon.awssdk.regions.Region.of(region))
                        .credentialsProvider(software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.create())
                        .build();
                software.amazon.awssdk.services.s3.model.GetObjectRequest req = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                return s3.getObject(req);
            } catch (Exception e) {
                // Fallback to resource
            }
        }
        // Fallback to resource
        InputStream is = ProcedureRagHelper.class.getResourceAsStream(RESOURCE_PATH);
        if (is == null) throw new IOException("procedures.json not found in resources or S3");
        return is;
    }

    private List<Procedure> loadProceduresFromResource() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getProceduresInputStream();
        JsonNode root = mapper.readTree(is);
        List<Procedure> result = new ArrayList<>();
        for (JsonNode node : root.get("procedures")) {
            result.add(new Procedure(
                    node.path("procedureId").asText(),
                    node.path("title").asText(),
                    node.path("description").asText(),
                    node.path("requirements").asText(),
                    node.path("steps").asText(),
                    node.path("fees").asText(),
                    node.path("office").asText(),
                    node.path("deadlines").asText(),
                    node.path("url").asText()
            ));
        }
        return result;
    }

    private void buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(ramDirectory, config)) {
            for (Procedure p : procedures) {
                Document doc = new Document();
                doc.add(new StringField("procedureId", p.procedureId, Field.Store.YES));
                doc.add(new TextField("title", p.title, Field.Store.YES));
                doc.add(new TextField("description", p.description, Field.Store.YES));
                doc.add(new TextField("requirements", p.requirements, Field.Store.YES));
                doc.add(new TextField("steps", p.steps, Field.Store.YES));
                doc.add(new TextField("fees", p.fees, Field.Store.YES));
                doc.add(new TextField("office", p.office, Field.Store.YES));
                doc.add(new TextField("deadlines", p.deadlines, Field.Store.YES));
                doc.add(new StringField("url", p.url, Field.Store.YES));
                writer.addDocument(doc);
            }
        }
    }

    public List<Procedure> search(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        List<Procedure> results = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(ramDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer);
            Query luceneQuery = parser.parse(query); // No QueryParserUtil.escape
            TopDocs topDocs = searcher.search(luceneQuery, MAX_RESULTS);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                results.add(new Procedure(
                        doc.get("procedureId"),
                        doc.get("title"),
                        doc.get("description"),
                        doc.get("requirements"),
                        doc.get("steps"),
                        doc.get("fees"),
                        doc.get("office"),
                        doc.get("deadlines"),
                        doc.get("url")
                ));
            }
        } catch (IOException | ParseException e) {
            // Log and return empty
        }
        return results;
    }
}
