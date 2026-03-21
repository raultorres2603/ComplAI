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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
    private static final String ENV_PROCEDURES_BUCKET = "PROCEDURES_BUCKET";
    private static final String ENV_PROCEDURES_REGION = "PROCEDURES_REGION";
    private static final Logger logger = Logger.getLogger(ProcedureRagHelper.class.getName());

    private final String cityId;
    private final List<Procedure> procedures;
    private final ByteBuffersDirectory ramDirectory;
    private final Analyzer analyzer;

    /**
     * Builds an in-memory Lucene index for the given city's procedures.
     *
     * <p>Procedures are loaded from S3 when {@code PROCEDURES_BUCKET} and
     * {@code PROCEDURES_REGION} are set; the S3 object key is always
     * {@code procedures-<cityId>.json}. Falls back to the classpath resource
     * {@code /procedures-<cityId>.json} when S3 env vars are absent (e.g. local tests).
     */
    public ProcedureRagHelper(String cityId) throws IOException {
        this.cityId = cityId;
        this.procedures = loadProcedures();
        this.analyzer = new StandardAnalyzer();
        this.ramDirectory = new ByteBuffersDirectory();
        buildIndex();
        logger.info(() -> "ProcedureRagHelper initialised — city=" + cityId + " procedureCount=" + procedures.size());
    }

    private InputStream getProceduresInputStream() throws IOException {
        String bucket     = System.getenv(ENV_PROCEDURES_BUCKET);
        String region     = System.getenv(ENV_PROCEDURES_REGION);
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        // The S3 key is always derived from the cityId — no env var override.
        // This ensures multi-city requests each load the correct file.
        String s3Key = "procedures-" + cityId + ".json";

        if (bucket != null && region != null) {
                software.amazon.awssdk.services.s3.S3ClientBuilder clientBuilder =
                software.amazon.awssdk.services.s3.S3Client.builder()
                    .region(software.amazon.awssdk.regions.Region.of(region))
                    .credentialsProvider(software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build());

            if (endpointUrl != null && !endpointUrl.isBlank()) {
                clientBuilder.endpointOverride(URI.create(endpointUrl));
            }

            try (software.amazon.awssdk.services.s3.S3Client s3 = clientBuilder.build()) {
                software.amazon.awssdk.services.s3.model.GetObjectRequest req =
                        software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(s3Key)
                                .build();
                logger.info(() -> "Loading procedures from S3 — bucket=" + bucket + " key=" + s3Key + " region=" + region);
                // getObjectAsBytes() reads all bytes into memory before the S3Client
                // (and its underlying HTTP connection) is closed by try-with-resources.
                // Using getObject() would return a stream backed by the HTTP connection,
                // which is closed by the time the caller tries to read it — a silent data loss.
                byte[] procedureBytes = s3.getObjectAsBytes(req).asByteArray();
                return new ByteArrayInputStream(procedureBytes);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load procedures from S3 — bucket=" + bucket
                        + " key=" + s3Key + " region=" + region + " error=" + e.getMessage()
                        + "; falling back to classpath resource", e);
            }
        }
        String resourcePath = "/procedures-" + cityId + ".json";
        logger.info("Loading procedures from classpath resource: " + resourcePath);
        InputStream is = ProcedureRagHelper.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("procedures-" + cityId + ".json not found — "
                    + "add it to src/main/resources/ or configure PROCEDURES_BUCKET/PROCEDURES_REGION");
        }
        return is;
    }

    private List<Procedure> loadProcedures() throws IOException {
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
            logger.fine(() -> "RAG SEARCH — type=PROCEDURE cityId=" + cityId + " queryLength=" + query.length() 
                    + " resultCount=" + results.size() + " maxRequested=" + MAX_RESULTS);
        } catch (IOException | ParseException e) {
            logger.log(Level.WARNING, "RAG search failed — queryLength=" + query.length()
                    + " error=" + e.getMessage(), e);
        }
        return results;
    }

    /**
     * Returns all loaded procedures for title-based filtering.
     * Used by OpenRouterServices to optimize RAG searches.
     */
    public List<Procedure> getAllProcedures() {
        return new ArrayList<>(procedures);
    }
}
