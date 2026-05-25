package cat.complai.services.openrouter;

import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import cat.complai.helpers.openrouter.RagHelper;
import cat.complai.helpers.openrouter.rag.AmbiguityDetector;
import cat.complai.helpers.openrouter.rag.InMemoryLexicalIndex;
import cat.complai.helpers.openrouter.rag.RagJavaCalibration;
import cat.complai.helpers.openrouter.rag.TokenNormalizer;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Handles ambiguity detection and resolution for procedure queries.
 * Detects when a user query matches multiple procedures equally well,
 * and resolves user clarification answers (by number, ordinal, or title).
 *
 * <p>This class was extracted from both {@code OpenRouterServices} and
 * {@code ProcedureContextService} during the god-class split.</p>
 */
@Singleton
public class ClarificationService {

    private static final Set<String> TITLE_STOP_WORDS = Set.of(
            "a", "an", "and", "d", "de", "del", "el", "els", "en", "for", "i", "la", "las",
            "les", "lo", "los", "of", "the", "y");

    private final ProcedureRagHelperRegistry ragRegistry;
    private final IntentDetector intentDetector;

    @Inject
    public ClarificationService(ProcedureRagHelperRegistry ragRegistry,
                                IntentDetector intentDetector) {
        this.ragRegistry = ragRegistry;
        this.intentDetector = intentDetector;
    }

    /**
     * Detects procedure ambiguity from user question.
     * Returns candidates when the search results are ambiguous.
     */
    public Optional<ProcedureAmbiguityResult> detectProcedureAmbiguity(String question, String cityId) {
        try {
            if (question == null || question.isBlank()) {
                return Optional.empty();
            }
            if (!intentDetector.detectContextRequirements(question, cityId).needsProcedureContext()) {
                return Optional.empty();
            }
            InMemoryLexicalIndex.SearchResponse<RagHelper.Procedure> response =
                    ragRegistry.getForCity(cityId).searchWithScores(question);
            if (!AmbiguityDetector.isAmbiguous(response, RagJavaCalibration.procedure().absoluteFloor())) {
                return Optional.empty();
            }
            List<ConversationManagementService.ClarificationCandidate> candidates =
                    AmbiguityDetector.getTopCandidates(response, 3).stream()
                            .filter(sr -> isCandidateRelevantToQuery(sr.source(), question))
                            .map(sr -> new ConversationManagementService.ClarificationCandidate(
                                    sr.source().procedureId(), sr.source().title()))
                            .toList();
            if (candidates.size() < 2) {
                return Optional.empty();
            }
            return Optional.of(new ProcedureAmbiguityResult(candidates));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(ClarificationService.class.getName())
                    .warning("detectProcedureAmbiguity failed for city=" + cityId
                            + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves a user's clarification answer to a candidate index, by numeric
     * choice, ordinal word, or title match.
     */
    public OptionalInt resolveClarificationAnswer(
            String answer, List<ConversationManagementService.ClarificationCandidate> candidates) {
        if (answer == null || answer.isBlank() || candidates == null || candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        String normalized = Normalizer.normalize(answer.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        String trimmed = normalized.trim();
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            if (c >= '1' && c <= '9') {
                int idx = c - '1';
                if (idx < candidates.size()) {
                    return OptionalInt.of(idx);
                }
            }
        }

        Map<String, Integer> ordinals = new HashMap<>();
        ordinals.putAll(Map.of("primer", 0, "primera", 0, "first", 0, "1st", 0, "un", 0,
                "segon", 1, "segunda", 1, "second", 1, "2nd", 1, "dos", 1));
        ordinals.putAll(Map.of("tercer", 2, "tercera", 2, "third", 2, "3rd", 2, "tres", 2));
        for (Map.Entry<String, Integer> entry : ordinals.entrySet()) {
            if (normalized.contains(entry.getKey()) && entry.getValue() < candidates.size()) {
                return OptionalInt.of(entry.getValue());
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            String title = candidates.get(i).title();
            if (title != null) {
                String normalizedTitle = Normalizer.normalize(title.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
                if (normalized.contains(normalizedTitle)) {
                    return OptionalInt.of(i);
                }
            }
        }

        return OptionalInt.empty();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static boolean isCandidateRelevantToQuery(RagHelper.Procedure procedure, String query) {
        if (procedure == null || query == null || query.isBlank()) {
            return false;
        }
        List<String> queryTokens = tokenizeForRelevance(query);
        if (queryTokens.isEmpty()) {
            return false;
        }
        Set<String> candidateTokens = new LinkedHashSet<>();
        candidateTokens.addAll(tokenizeForRelevance(procedure.title()));
        candidateTokens.addAll(tokenizeForRelevance(procedure.description()));
        for (String token : queryTokens) {
            if (candidateTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenizeForRelevance(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return TokenNormalizer.tokenize(text).stream()
                .filter(token -> token.length() >= 3)
                .filter(token -> !TITLE_STOP_WORDS.contains(token))
                .toList();
    }
}
