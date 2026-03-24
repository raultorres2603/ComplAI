package cat.complai.openrouter.cache;

/**
 * Utility class for keyword-based question categorization.
 * 
 * This is a simple, fast approach to categorize questions without ML.
 * Uses keyword matching (case-insensitive) to assign question categories.
 * 
 * Privacy note: This operates on static keywords, not user-specific data.
 * It produces a deterministic category output suitable for cache key
 * generation.
 */
public class QuestionCategoryDetector {

    /**
     * Detect the question category based on keyword matching.
     * 
     * @param question The user's question text
     * @return The detected QuestionCategory, or QuestionCategory.OTHER if no match
     */
    public static QuestionCategory detectCategory(String question) {
        if (question == null || question.isBlank()) {
            return QuestionCategory.OTHER;
        }

        String lowerQuestion = question.toLowerCase();

        // Parking-related keywords (check first, most specific)
        // Include vehicle permit as parking context
        // English: parking, vehicle permit, park, permit
        // Catalan: aparcament, estacionament, plaça, permís
        if (containsAny(lowerQuestion,
                "parking", "vehicle permit", "parking permit", "zone",
                "aparcament", "estacionament", "plaça", "permís")) {
            return QuestionCategory.PARKING;
        }
        // Match "park" as a standalone word (verb: to park)
        // Match when "park" appears with word boundary (space before/after, or at
        // start/end)
        if ((lowerQuestion.contains("park ") || lowerQuestion.contains(" park") || lowerQuestion.endsWith("park"))) {
            return QuestionCategory.PARKING;
        }

        // Tax-related keywords
        if (containsAny(lowerQuestion,
                "tax", "rate", "filing", "payment", "deduction",
                "municipal", "irs", "contribution", "impuesto", "tribut", "contribució")) {
            return QuestionCategory.TAX;
        }

        // Garbage/Waste-related keywords
        if (containsAny(lowerQuestion,
                "garbage", "waste", "recycling", "collection", "schedule",
                "trash", "bin", "rubbish", "residus", "recollida", "contenidor")) {
            return QuestionCategory.GARBAGE;
        }

        // Library-related keywords
        if (containsAny(lowerQuestion,
                "library", "book", "loan", "lending", "fiction", "hours", "membership",
                "biblioteca", "llibre", "préstec")) {
            return QuestionCategory.LIBRARY;
        }

        // Complaint-related keywords
        if (containsAny(lowerQuestion,
                "complaint", "grievance", "claim", "dispute", "legal",
                "compensation", "sue", "reclamació", "demanda", "queixa")) {
            return QuestionCategory.COMPLAINT;
        }

        // Administration/Licensing-related keywords (least specific, check last)
        if (containsAny(lowerQuestion,
                "license", "permit", "certification", "certificate", "registration",
                "form", "application", "bureaucrat", "approval", "request",
                "llicència", "permís", "certificat", "registre", "sol·licitud")) {
            return QuestionCategory.ADMINISTRATION;
        }

        // Default: other
        return QuestionCategory.OTHER;
    }

    /**
     * Helper method to check if a string contains any of the given keywords.
     * Uses simple substring matching (case-insensitive).
     * 
     * @param text     The text to search in (assumed already lowercase)
     * @param keywords The keywords to search for
     * @return true if any keyword is found as a substring
     */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
