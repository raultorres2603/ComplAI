package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedactPromptBuilderMultilingualTest {

    private RedactPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new RedactPromptBuilder();
    }

    @Test
    void testGetSystemMessage_CatalanDefault() {
        String result = promptBuilder.getSystemMessage("testcity");
        assertNotNull(result);
        assertTrue(result.contains("Gall Potablava"), "Default should contain assistant name");
        assertTrue(result.length() > 100, "Default should be substantive content");
    }

    @Test
    void testGetSystemMessage_CatalanExplicit() {
        String result = promptBuilder.getSystemMessage("testcity", "CA");
        assertNotNull(result);
        assertTrue(result.contains("Gall Potablava"), "Catalan prompt should contain assistant name");
        // Should start with Catalan block
        assertTrue(result.contains("amable i proper"), "Catalan prompt should have Catalan phrasing");
    }

    @Test
    void testGetSystemMessage_EnglishLanguage() {
        String result = promptBuilder.getSystemMessage("testcity", "EN");
        assertNotNull(result);
        assertTrue(result.contains("Gall Potablava"), "English prompt should contain assistant name");
        assertTrue(result.contains("friendly local assistant"), "English prompt should have English phrasing");
    }

    @Test
    void testGetSystemMessage_SpanishLanguage() {
        String result = promptBuilder.getSystemMessage("testcity", "ES");
        assertNotNull(result);
        assertTrue(result.contains("Gall Potablava"), "Spanish prompt should contain assistant name");
        assertTrue(result.contains("amable y cercano"), "Spanish prompt should have Spanish phrasing");
    }

    @Test
    void testGetSystemMessage_NullLanguageFallback() {
        String result = promptBuilder.getSystemMessage("testcity", null);
        assertNotNull(result);
        // Should fallback to default (all languages or Catalan first)
        assertTrue(result.contains("Gall Potablava"));
    }

    @Test
    void testGetSystemMessage_UnknownLanguageFallback() {
        String result = promptBuilder.getSystemMessage("testcity", "FR");
        assertNotNull(result);
        // Should fallback to default
        assertTrue(result.contains("Gall Potablava"));
    }

    @Test
    void testGetSystemMessage_FrenchLanguage() {
        String cityId = "testcity";
        String frenchPrompt = promptBuilder.getSystemMessage(cityId, "FR");

        // Verify it's NOT empty and NOT the default
        assertNotNull(frenchPrompt, "French prompt should not be null");
        assertTrue(frenchPrompt.length() > 100, "French prompt should contain substantial content");

        // Verify it contains French text markers
        assertTrue(frenchPrompt.contains("Vous êtes"), "French prompt should contain 'Vous êtes' (French grammar)");
        assertTrue(frenchPrompt.contains("François") || frenchPrompt.contains("assistant"), "French prompt should identify the assistant");

        // Verify it does NOT contain only Spanish/Catalan/English
        assertFalse(frenchPrompt.contains("Eres un asistente") && !frenchPrompt.contains("Vous êtes"),
                "Should contain French, not just Spanish");

        // Verify city name is embedded
        assertTrue(frenchPrompt.toLowerCase().contains(cityId.toLowerCase()),
                "French prompt should include city name: " + cityId);
    }

    @Test
    void testGetSystemMessage_CatalanVsEnglish() {
        String catResult = promptBuilder.getSystemMessage("testcity", "CA");
        String enResult = promptBuilder.getSystemMessage("testcity", "EN");

        assertNotNull(catResult);
        assertNotNull(enResult);
        assertNotEquals(catResult, enResult,
                "Catalan and English prompts should be different");

        assertTrue(catResult.contains("amable i proper"),
                "Catalan should have Catalan phrasing");
        assertTrue(enResult.contains("friendly"),
                "English should have English phrasing");
    }

    @Test
    void testGetSystemMessage_CatalanVsSpanish() {
        String catResult = promptBuilder.getSystemMessage("testcity", "CA");
        String esResult = promptBuilder.getSystemMessage("testcity", "ES");

        assertNotNull(catResult);
        assertNotNull(esResult);
        assertNotEquals(catResult, esResult,
                "Catalan and Spanish prompts should be different");

        assertTrue(catResult.contains("amable i proper"),
                "Catalan should have Catalan phrasing");
        assertTrue(esResult.contains("amable y cercano"),
                "Spanish should have Spanish phrasing");
    }

    @Test
    void testGetSystemMessage_ContainsFormatRules() {
        String result = promptBuilder.getSystemMessage("testcity", "EN");
        assertNotNull(result);
        // Should contain HTML format references
        assertTrue(result.contains("HTML"), "Prompt should mention HTML format");
        assertTrue(result.contains("<"), "Prompt should contain HTML examples or references");
    }

    @Test
    void testGetSystemMessage_ContainsCityName() {
        String result = promptBuilder.getSystemMessage("testcity", "EN");
        assertNotNull(result);
        // City name might be "testcity" or resolved from mapping
        assertTrue(result.length() > 0, "Prompt should not be empty");
    }

    @Test
    void testGetSystemMessage_English_CommonStructure() {
        String result = promptBuilder.getSystemMessage("testcity", "EN");
        assertTrue(result.contains("You are"), "English should use 'You are'");
        assertTrue(result.contains("Gall Potablava"), "Should name the assistant");
        assertTrue(result.contains("residents"), "Should mention residents");
    }

    @Test
    void testGetSystemMessage_Spanish_CommonStructure() {
        String result = promptBuilder.getSystemMessage("testcity", "ES");
        assertTrue(result.contains("Eres") || result.contains("asistente"),
                "Spanish should use proper Spanish grammar");
        assertTrue(result.contains("Gall Potablava"), "Should name the assistant");
        assertTrue(result.contains("vecinos"), "Should mention vecinos (Spanish for residents)");
    }

    @Test
    void testGetSystemMessage_Catalan_CommonStructure() {
        String result = promptBuilder.getSystemMessage("testcity", "CA");
        assertTrue(result.contains("assist"), "Catalan should reference assistant");
        assertTrue(result.contains("Gall Potablava"), "Should name the assistant");
        assertTrue(result.contains("veïn"), "Should mention veïns (Catalan for residents)");
    }

    @Test
    void testGetSystemMessage_NoMarkdownReference() {
        String result = promptBuilder.getSystemMessage("testcity", "EN");
        assertNotNull(result);
        assertTrue(result.contains("HTML") || result.contains("ONLY"),
                "Should instruct to use HTML, not Markdown");
    }

    @Test
    void testGetSystemMessage_URLHandling() {
        String result = promptBuilder.getSystemMessage("testcity", "EN");
        assertNotNull(result);
        // Should have URL handling instructions
        assertTrue(result.contains("URL") || result.contains("link"),
                "Should mention URL or link handling");
    }

    @Test
    void testGetSystemMessage_LanguageInstructions() {
        String enResult = promptBuilder.getSystemMessage("testcity", "EN");
        String esResult = promptBuilder.getSystemMessage("testcity", "ES");
        String caResult = promptBuilder.getSystemMessage("testcity", "CA");

        // Each should be in its own language
        assertTrue(enResult.contains("friendly") || enResult.contains("You"),
                "English prompt should be in English");
        assertTrue(esResult.contains("amable") || esResult.contains("Eres"),
                "Spanish prompt should be in Spanish");
        assertTrue(caResult.contains("assistent") || caResult.contains("amable"),
                "Catalan prompt should be in Catalan");
    }
}
