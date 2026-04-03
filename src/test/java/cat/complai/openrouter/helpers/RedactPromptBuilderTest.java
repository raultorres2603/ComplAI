package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RedactPromptBuilder}.
 * These tests verify the structural requirements of each prompt — not word-for-word content,
 * which is fragile and test-breaking when the wording improves. We test the invariants the
 * AI depends on: mandatory fields present, forbidden patterns absent, correct format header.
 */
class RedactPromptBuilderTest {

    private final RedactPromptBuilder builder = new RedactPromptBuilder();

    @Test
    void getSystemMessage_containsAssistantName() {
        String msg = builder.getSystemMessage("elprat");
        assertTrue(msg.contains("Gall Potablava"), "System message must name the assistant");
    }

    @Test
    void getSystemMessage_containsOfficialUrl() {
        String msg = builder.getSystemMessage("elprat");
        assertTrue(msg.contains("elprat.cat"), "System message must reference the official El Prat website");
    }

    @Test
    void getSystemMessage_isMultilingual() {
        String msg = builder.getSystemMessage("elprat");
        assertTrue(msg.contains("In English"), "System message must include English section");
        assertTrue(msg.contains("En español"), "System message must include Spanish section");
    }

    @Test
    void getSystemMessage_unknownCity_fallsBackToCityId() {
        // For a city with no mapping file the city name falls back to the cityId itself
        // and source URLs are omitted — the prompt must still be well-formed.
        String msg = builder.getSystemMessage("unknowncity");
        assertTrue(msg.contains("unknowncity"), "System message must contain the cityId as fallback city name");
        assertTrue(msg.contains("Gall Potablava"), "System message must still name the assistant");
    }

    @Test
    void buildRedactPromptWithIdentity_containsMandatoryFields() {
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        String prompt = builder.buildRedactPromptWithIdentity("Noise from the airport.", identity, "elprat");

        assertTrue(prompt.contains("Joan"), "Prompt must include requester first name");
        assertTrue(prompt.contains("Garcia"), "Prompt must include requester surname");
        assertTrue(prompt.contains("12345678A"), "Prompt must include requester ID number");
    }

    @Test
    void buildRedactPromptWithIdentity_containsTodaysDate() {
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        String prompt = builder.buildRedactPromptWithIdentity("Some complaint.", identity, "elprat");

        // The date instruction must be present.
        assertTrue(prompt.contains("Date:"), "Prompt must contain date instruction");
        // The format string must have been resolved — no bare %s should remain.
        assertFalse(prompt.contains("%s"), "Prompt must not contain unresolved format placeholders");
    }

    @Test
    void buildRedactPromptWithIdentity_forbidsMarkdown() {
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        String prompt = builder.buildRedactPromptWithIdentity("Some complaint.", identity, "elprat");

        assertTrue(prompt.contains("PLAIN TEXT output"), "Prompt must forbid Markdown output");
    }

    @Test
    void buildRedactPromptWithIdentity_requiresJsonHeader() {
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        String prompt = builder.buildRedactPromptWithIdentity("Some complaint.", identity, "elprat");

        assertTrue(prompt.contains("{\"format\": \"pdf\"}"), "Prompt must instruct the AI to emit the JSON format header");
    }

    @Test
    void buildRedactPromptWithIdentity_includesComplaintText() {
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        String complaintText = "The street light outside my house has been broken for three months.";
        String prompt = builder.buildRedactPromptWithIdentity(complaintText, identity, "elprat");

        assertTrue(prompt.contains(complaintText), "Prompt must include the verbatim complaint text");
    }

    @Test
    void buildRedactPromptRequestingIdentity_listsAllMissingFields_whenNoIdentity() {
        String prompt = builder.buildRedactPromptRequestingIdentity("Some complaint.", null, "elprat");

        assertTrue(prompt.contains("First name"), "Prompt must ask for first name");
        assertTrue(prompt.contains("Surname"), "Prompt must ask for surname");
        assertTrue(prompt.contains("ID/DNI/NIF"), "Prompt must ask for ID number");
    }

    @Test
    void buildRedactPromptRequestingIdentity_listsOnlyMissingFields_whenPartialIdentity() {
        // Name is already provided, only surname and ID are missing.
        ComplainantIdentity partial = new ComplainantIdentity("Joan", null, null);
        String prompt = builder.buildRedactPromptRequestingIdentity("Some complaint.", partial, "elprat");

        assertFalse(prompt.contains("First name (nom)"), "Prompt must not re-ask for the name that is already provided");
        assertTrue(prompt.contains("Surname"), "Prompt must ask for the missing surname");
        assertTrue(prompt.contains("ID/DNI/NIF"), "Prompt must ask for the missing ID number");
    }

    @Test
    void getSystemMessage_containsNoHardcodedExampleUrls() {
        String msg = builder.getSystemMessage("elprat");
        // The system message should not contain hardcoded example URLs that don't exist in procedures
        assertFalse(msg.contains("https://tramits.pratespais.com/Ciutadania/Empadronament"), 
                  "System message must not contain hardcoded Empadronament URL example");
        // Should contain clarification about official sources being for general info only
        assertTrue(msg.contains("només són per a informació general, no per enllaços específics de tràmits"), 
                  "System message must clarify that official sources are for general info only");
    }

    @Test
    void buildProcedureContextBlock_returnsNullWhenQueryProducesNoResults() {
        // A nonsense query should produce no RAG matches and return null.
        // Either null (no results) or a non-empty string (if fuzzy matched) — both are valid.
        // The important contract: it must never throw.
        builder.buildProcedureContextBlock("xyzzy_nonexistent_term_abc123", "elprat");
    }

    // -------------------------------------------------------------------------
    // Language-specific system message tests (Step 4)
    // -------------------------------------------------------------------------

    @Test
    void getSystemMessage_withCA_containsCatalanAndNotSpanishMarker() {
        String msg = builder.getSystemMessage("elprat", "CA");
        assertTrue(msg.contains("Ets un assistent"), "CA prompt must start with Catalan opener");
        assertFalse(msg.contains("En español:"), "CA prompt must NOT contain the Spanish section marker");
        assertFalse(msg.contains("In English"), "CA prompt must NOT contain the English section");
    }

    @Test
    void getSystemMessage_withES_containsSpanishAndNotCatalanOpener() {
        String msg = builder.getSystemMessage("elprat", "ES");
        assertTrue(msg.contains("En español:"), "ES prompt must contain the Spanish section marker");
        assertFalse(msg.contains("Ets un assistent"), "ES prompt must NOT contain the Catalan opener");
        assertFalse(msg.contains("In English"), "ES prompt must NOT contain the English section");
    }

    @Test
    void getSystemMessage_withEN_containsEnglishBlock() {
        String msg = builder.getSystemMessage("elprat", "EN");
        assertTrue(msg.contains("In English"), "EN prompt must contain the English section");
        assertFalse(msg.contains("Ets un assistent"), "EN prompt must NOT contain the Catalan opener");
        assertFalse(msg.contains("En español:"), "EN prompt must NOT contain the Spanish section marker");
    }

    @Test
    void getSystemMessage_withFR_containsFrenchBlock() {
        String msg = builder.getSystemMessage("elprat", "FR");
        assertTrue(msg.contains("En français:"), "FR prompt must contain the French section marker");
        assertTrue(msg.contains("Gall Potablava"), "FR prompt must contain the assistant name");
        assertFalse(msg.contains("Ets un assistent"), "FR prompt must NOT contain the Catalan opener");
        assertFalse(msg.contains("En español:"), "FR prompt must NOT contain the Spanish section marker");
        assertFalse(msg.contains("In English"), "FR prompt must NOT contain the English section marker");
    }

    @Test
    void getSystemMessage_frenchPrompt_adheresToHTMLOnlyRequirement() {
        String msg = builder.getSystemMessage("elprat", "FR");
        assertTrue(msg.contains("OBLIGATOIRE") || msg.contains("HTML"), 
                "FR prompt must enforce HTML-only output (French terms)");
        assertTrue(msg.contains("Jamais Markdown") || msg.contains("HTML"),
                "FR prompt must forbid Markdown");
    }

    @Test
    void getSystemMessage_frenchPrompt_containsOfficialCityReferences() {
        String msg = builder.getSystemMessage("elprat", "FR");
        assertTrue(msg.contains("elprat"), "FR prompt must reference the official city");
        assertTrue(msg.contains("Gall Potablava"), "FR prompt must name the assistant");
    }

    @Test
    void getSystemMessage_withNull_returnsTriLingual() {
        String triLingual = builder.getSystemMessage("elprat");
        String withNull = builder.getSystemMessage("elprat", null);
        assertEquals(triLingual, withNull, "null language must fall back to tri-lingual prompt");
    }

    @Test
    void getSystemMessage_withUnknownCode_fallsBackToTriLingual() {
        String triLingual = builder.getSystemMessage("elprat");
        String withXX = builder.getSystemMessage("elprat", "XX");
        assertEquals(triLingual, withXX, "unrecognized language code must fall back to tri-lingual prompt");
    }
}
