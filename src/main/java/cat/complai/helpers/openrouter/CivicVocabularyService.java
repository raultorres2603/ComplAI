package cat.complai.helpers.openrouter;

import cat.complai.helpers.openrouter.rag.TokenNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service that expands user queries with Catalan civic vocabulary equivalents.
 *
 * <p>
 * Loads civic vocabulary mappings from {@code civic-vocabulary-mapping.json}
 * (classpath resource) that provides English → Catalan, Spanish → Catalan, and
 * French → Catalan synonym mappings. When a user queries in English, Spanish,
 * or
 * French using civic terminology (complaint, permit, application, etc.), this
 * service appends the Catalan equivalents to the query, enabling cross-language
 * retrieval from a Catalan-indexed knowledge base.
 *
 * <p>
 * Example: expandQuery("plainte autorisation", "fr") returns
 * "plainte autorisation reclamació autorització"
 */
@Singleton
public class CivicVocabularyService {

    private static final Logger logger = Logger.getLogger(CivicVocabularyService.class.getName());
    private static final String MAPPING_RESOURCE = "/civic-vocabulary-mapping.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Language-specific civic vocabulary maps: language → term → list of Catalan
     * synonyms.
     * Loaded on construction from classpath resource.
     */
    private final Map<String, Map<String, List<String>>> vocabularyMapping;

    public CivicVocabularyService() {
        this.vocabularyMapping = loadVocabularyMapping();
    }

    /**
     * Expands a query by appending Catalan synonyms for civic terms
     * detected in the user's input language.
     *
     * <p>
     * For example:
     * <ul>
     * <li>{@code expandQuery("complaint", "en")} → "complaint reclamació denúncia"
     * <li>{@code expandQuery("queja", "es")} → "queja reclamació denúncia"
     * <li>{@code expandQuery("plainte", "fr")} → "plainte reclamació denúncia"
     * <li>{@code expandQuery("unknown", "en")} → "unknown" (unchanged)
     * </ul>
     *
     * @param query    the input query (may contain multiple words)
     * @param language ISO language code: "en", "es", "ca", "fr"; others default to
     *                 no
     *                 expansion
     * @return expanded query with Catalan synonyms appended, or original if no
     *         mappings found
     */
    public String expandQuery(String query, String language) {
        if (query == null || query.isBlank()) {
            return query;
        }

        if (language == null || (!language.equalsIgnoreCase("en") &&
                !language.equalsIgnoreCase("es") &&
                !language.equalsIgnoreCase("ca") &&
                !language.equalsIgnoreCase("fr"))) {
            logger.fine(() -> "CivicVocabularyService.expandQuery: unsupported language='" + language
                    + "'; returning original query");
            return query;
        }

        String langKey = language.toLowerCase();
        Map<String, List<String>> langMapping = vocabularyMapping.get(langKey);
        if (langMapping == null || langMapping.isEmpty()) {
            logger.fine(() -> "CivicVocabularyService: no vocabulary mapping for language='" + language + "'");
            return query;
        }

        String[] tokens = query.toLowerCase().split("\\s+");
        Set<String> synonyms = new LinkedHashSet<>();

        for (String token : tokens) {
            // Use TokenNormalizer for consistent accent removal (NFD + mark stripping)
            String cleanToken = TokenNormalizer.normalizeForSearch(token);
            if (!cleanToken.isBlank() && langMapping.containsKey(cleanToken)) {
                synonyms.addAll(langMapping.get(cleanToken));
                logger.fine(() -> "CivicVocabularyService: expanded token='" + cleanToken
                        + "' with synonyms=" + langMapping.get(cleanToken));
            }
        }

        if (synonyms.isEmpty()) {
            logger.fine(() -> "CivicVocabularyService: no synonyms found for query='" + query
                    + "' language='" + language + "'");
            return query;
        }

        String expanded = query + " " + String.join(" ", synonyms);
        logger.fine(() -> "CivicVocabularyService: expanded query from '" + query + "' to '" + expanded + "'");
        return expanded;
    }

    /**
     * Loads the civic vocabulary mapping from the classpath resource.
     * Gracefully degrades to an empty map if the resource is not found or
     * parsing fails.
     *
     * @return {@code Map<String, Map<String, List<String>>>} where outer map key is
     *         language code ("en", "es", "ca"), and inner map is term → list of
     *         synonyms
     */
    private Map<String, Map<String, List<String>>> loadVocabularyMapping() {
        try (InputStream is = CivicVocabularyService.class.getResourceAsStream(MAPPING_RESOURCE)) {
            if (is == null) {
                logger.warning("Civic vocabulary mapping file not found at " + MAPPING_RESOURCE
                        + "; civic term expansion will be disabled");
                return new HashMap<>();
            }

            JsonNode root = MAPPER.readTree(is);
            Map<String, Map<String, List<String>>> result = new HashMap<>();

            // Iterate over language keys (e.g., "en", "es", "ca")
            root.properties().forEach(languageEntry -> {
                String languageCode = languageEntry.getKey();
                JsonNode languageNode = languageEntry.getValue();
                Map<String, List<String>> termToSynonyms = new HashMap<>();

                // Iterate over term keys (e.g., "complaint", "permit")
                languageNode.properties().forEach(termEntry -> {
                    String term = termEntry.getKey();
                    // Normalize the term key (remove accents, lowercase) for consistent lookup
                    String normalizedTerm = TokenNormalizer.normalizeForSearch(term);
                    JsonNode synonymsNode = termEntry.getValue();
                    List<String> synonymsList = new ArrayList<>();

                    if (synonymsNode.isArray()) {
                        synonymsNode.forEach(synNode -> {
                            String syn = synNode.asText(null);
                            if (syn != null && !syn.isBlank()) {
                                synonymsList.add(syn);
                            }
                        });
                    }

                    termToSynonyms.put(normalizedTerm, List.copyOf(synonymsList));
                });

                result.put(languageCode, Map.copyOf(termToSynonyms));
            });

            logger.info(() -> "Loaded civic vocabulary mapping with " + result.size() + " language(s)");
            return result;
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Failed to load civic vocabulary mapping from " + MAPPING_RESOURCE + ": " + e.getMessage(),
                    e);
            return new HashMap<>();
        }
    }
}
