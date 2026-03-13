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
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProcedureRagHelper {
    public static class Procedure {
        public final String procedureId;
        public final String title;
        public final String description;
        public final String requirements;
        public final String steps;
        public final String url;

        public Procedure(String procedureId, String title, String description, String requirements, String steps, String url) {
            this.procedureId = procedureId;
            this.title = title;
            this.description = description;
            this.requirements = requirements;
            this.steps = steps;
            this.url = url;
        }
    }

    private static final String[] SEARCH_FIELDS = {"title", "description", "requirements", "steps"};
    private static final int MAX_RESULTS = 3;
    // TODO(multi-city): When JWT city routing is implemented, this default will be resolved
    // dynamically from the JWT "city" claim so each city serves its own procedures index.
    // For now the single city "elprat" is the only supported deployment target.
    private static final String RESOURCE_PATH = "/procedures-elprat.json";
    private static final String ENV_PROCEDURES_BUCKET = "PROCEDURES_BUCKET";
    private static final String ENV_PROCEDURES_KEY = "PROCEDURES_KEY";
    private static final String ENV_PROCEDURES_REGION = "PROCEDURES_REGION";
    private static final Logger logger = Logger.getLogger(ProcedureRagHelper.class.getName());

    private final List<Procedure> procedures;
    private final ByteBuffersDirectory ramDirectory;
    private final Analyzer analyzer;

    public ProcedureRagHelper() throws IOException {
        this.procedures = loadProceduresFromResource();
        this.analyzer = new StandardAnalyzer();
        this.ramDirectory = new ByteBuffersDirectory();
        buildIndex();
        logger.info(() -> "ProcedureRagHelper initialised — procedureCount=" + procedures.size());
    }

    private static InputStream getProceduresInputStream() throws IOException {
        String bucket = System.getenv(ENV_PROCEDURES_BUCKET);
        String key = System.getenv(ENV_PROCEDURES_KEY);
        String region = System.getenv(ENV_PROCEDURES_REGION);
        if (bucket != null && key != null && region != null) {
            // Try to load from S3
            try (software.amazon.awssdk.services.s3.S3Client s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                    .region(software.amazon.awssdk.regions.Region.of(region))
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.create())
                    .build()) {
                software.amazon.awssdk.services.s3.model.GetObjectRequest req = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                logger.info(() -> "Loading procedures from S3 — bucket=" + bucket + " key=" + key + " region=" + region);
                return s3.getObject(req);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load procedures from S3 — bucket=" + bucket
                        + " key=" + key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }
        // Fallback to resource
        logger.info("Loading procedures from classpath resource: " + RESOURCE_PATH);
        InputStream is = ProcedureRagHelper.class.getResourceAsStream(RESOURCE_PATH);
        if (is == null) throw new IOException("procedures-elprat.json not found in resources or S3");
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
                        doc.get("url")
                ));
            }
            logger.fine(() -> "RAG search — queryLength=" + query.length() + " resultCount=" + results.size());
        } catch (IOException | ParseException e) {
            logger.log(Level.WARNING, "RAG search failed — queryLength=" + query.length()
                    + " error=" + e.getMessage(), e);
        }
        return results;
    }
}
