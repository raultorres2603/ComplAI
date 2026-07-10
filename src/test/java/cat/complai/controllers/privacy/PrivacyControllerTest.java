package cat.complai.controllers.privacy;

import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PrivacyController}.
 *
 * <p>Follows the project pattern for controller tests:
 * <ul>
 *   <li>No {@code @MicronautTest} — controller is instantiated directly (no dependencies)</li>
 *   <li>HTTP request objects are built manually with
 *       {@link HttpRequest#GET(String)}</li>
 * </ul>
 */
public class PrivacyControllerTest {

    private final PrivacyController controller = new PrivacyController();

    // -------------------------------------------------------------------------
    // Language parameter handling
    // -------------------------------------------------------------------------

    @Test
    void privacy_nullLang_returnsCatalan() {
        HttpResponse<String> response = controller.privacy(null);

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"), "Should default to Catalan");
        assertTrue(body.contains("lang=ca"), "Language switcher should link to ca");
        assertTrue(body.contains("lang=es"), "Language switcher should link to es");
        assertTrue(body.contains("lang=en"), "Language switcher should link to en");
    }

    @Test
    void privacy_emptyLang_returnsCatalan() {
        HttpResponse<String> response = controller.privacy("");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"), "Should default to Catalan");
    }

    @Test
    void privacy_blankLang_returnsCatalan() {
        HttpResponse<String> response = controller.privacy("   ");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"), "Should default to Catalan");
    }

    @Test
    void privacy_ca_returnsCatalan() {
        HttpResponse<String> response = controller.privacy("ca");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"), "Should return Catalan");
        assertTrue(body.contains("Última actualització"), "Should contain Catalan date text");
    }

    @Test
    void privacy_CA_uppercase_returnsCatalan() {
        HttpResponse<String> response = controller.privacy("CA");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"), "Should return Catalan for uppercase CA");
    }

    @Test
    void privacy_es_returnsSpanish() {
        HttpResponse<String> response = controller.privacy("es");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacidad"), "Should return Spanish");
        assertTrue(body.contains("Última actualización"), "Should contain Spanish date text");
    }

    @Test
    void privacy_ES_uppercase_returnsSpanish() {
        HttpResponse<String> response = controller.privacy("ES");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacidad"), "Should return Spanish for uppercase ES");
    }

    @Test
    void privacy_en_returnsEnglish() {
        HttpResponse<String> response = controller.privacy("en");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Privacy Policy"), "Should return English");
        assertTrue(body.contains("Last updated:"), "Should contain English date text");
    }

    @Test
    void privacy_EN_uppercase_returnsEnglish() {
        HttpResponse<String> response = controller.privacy("EN");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Privacy Policy"), "Should return English for uppercase EN");
    }

    @Test
    void privacy_invalidLang_french_fallbackToCatalan() {
        HttpResponse<String> response = controller.privacy("fr");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"),
                "Invalid language 'fr' should fallback to Catalan");
    }

    @Test
    void privacy_invalidLang_german_fallbackToCatalan() {
        HttpResponse<String> response = controller.privacy("de");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"),
                "Invalid language 'de' should fallback to Catalan");
    }

    @Test
    void privacy_invalidLang_numeric_fallbackToCatalan() {
        HttpResponse<String> response = controller.privacy("123");

        assertEquals(200, response.getStatus().getCode());
        String body = response.getBody().orElse("");
        assertTrue(body.contains("Política de Privacitat"),
                "Numeric language '123' should fallback to Catalan");
    }

    // -------------------------------------------------------------------------
    // Response format
    // -------------------------------------------------------------------------

    @Test
    void privacy_returnsHtmlContentType() {
        HttpResponse<String> response = controller.privacy("en");

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getContentType().isPresent());
        assertEquals("text/html", response.getContentType().get().toString());
    }

    @Test
    void privacy_returnsValidHtmlStructure() {
        HttpResponse<String> response = controller.privacy("en");
        String body = response.getBody().orElse("");

        assertTrue(body.contains("<!DOCTYPE html>"), "Should contain DOCTYPE declaration");
        assertTrue(body.contains("<html"), "Should contain html tag");
        assertTrue(body.contains("<head>"), "Should contain head tag");
        assertTrue(body.contains("<body>"), "Should contain body tag");
        assertTrue(body.contains("</html>"), "Should close html tag");
    }

    // -------------------------------------------------------------------------
    // Privacy policy content
    // -------------------------------------------------------------------------

    @Test
    void privacy_englishContainsAllSections() {
        HttpResponse<String> response = controller.privacy("en");
        String body = response.getBody().orElse("");

        assertTrue(body.contains("1. Introduction"), "Should contain Introduction section");
        assertTrue(body.contains("2. Data We Collect"), "Should contain Data Collection section");
        assertTrue(body.contains("3. How We Use Your Data"), "Should contain Data Usage section");
        assertTrue(body.contains("4. Data Storage and Security"), "Should contain Storage section");
        assertTrue(body.contains("5. Third-Party Services"), "Should contain Third-Party section");
        assertTrue(body.contains("6. Your Rights"), "Should contain Rights section");
        assertTrue(body.contains("7. Contact"), "Should contain Contact section");
        assertTrue(body.contains("privacy@complai.cat"), "Should contain contact email");
    }

    @Test
    void privacy_catalanContainsAllSections() {
        HttpResponse<String> response = controller.privacy("ca");
        String body = response.getBody().orElse("");

        assertTrue(body.contains("1. Introducció"), "Should contain Catalan Introduction");
        assertTrue(body.contains("2. Dades que Recollim"), "Should contain Catalan Data Collection");
        assertTrue(body.contains("3. Com Utilitzem Les Vostres Dades"), "Should contain Catalan Data Usage");
        assertTrue(body.contains("4. Emmagatzematge i Seguretat de Dades"), "Should contain Catalan Storage");
        assertTrue(body.contains("5. Serveis de Tercers"), "Should contain Catalan Third-Party");
        assertTrue(body.contains("6. Els Vostres Drets"), "Should contain Catalan Rights");
        assertTrue(body.contains("7. Contacte"), "Should contain Catalan Contact");
        assertTrue(body.contains("privacy@complai.cat"), "Should contain contact email");
    }

    @Test
    void privacy_spanishContainsAllSections() {
        HttpResponse<String> response = controller.privacy("es");
        String body = response.getBody().orElse("");

        assertTrue(body.contains("1. Introducción"), "Should contain Spanish Introduction");
        assertTrue(body.contains("2. Datos que Recopilamos"), "Should contain Spanish Data Collection");
        assertTrue(body.contains("3. Cómo Usamos Sus Datos"), "Should contain Spanish Data Usage");
        assertTrue(body.contains("4. Almacenamiento y Seguridad de Datos"), "Should contain Spanish Storage");
        assertTrue(body.contains("5. Servicios de Terceros"), "Should contain Spanish Third-Party");
        assertTrue(body.contains("6. Sus Derechos"), "Should contain Spanish Rights");
        assertTrue(body.contains("7. Contacto"), "Should contain Spanish Contact");
        assertTrue(body.contains("privacy@complai.cat"), "Should contain contact email");
    }

    @Test
    void privacy_containsLanguageSwitcher() {
        HttpResponse<String> response = controller.privacy("ca");
        String body = response.getBody().orElse("");

        assertTrue(body.contains("href=\"?lang=ca\""), "Switcher should have Catalan link");
        assertTrue(body.contains("href=\"?lang=es\""), "Switcher should have Spanish link");
        assertTrue(body.contains("href=\"?lang=en\""), "Switcher should have English link");
        assertTrue(body.contains("Català"), "Switcher should show Catalan label");
        assertTrue(body.contains("Español"), "Switcher should show Spanish label");
        assertTrue(body.contains("English"), "Switcher should show English label");
    }

    @Test
    void privacy_activeLanguageHighlighted() {
        // When lang=en, the English link should have the active style
        HttpResponse<String> response = controller.privacy("en");
        String body = response.getBody().orElse("");

        // The active language link should NOT have opacity:0.6
        // The inactive ones should have opacity:0.6
        assertTrue(body.contains("opacity:0.6;"), "Inactive languages should have opacity style");
        assertTrue(body.contains("font-weight:700"), "Active language should have bold style");
    }

    @Test
    void privacy_containsComplAiCopyright() {
        HttpResponse<String> response = controller.privacy("en");
        String body = response.getBody().orElse("");

        assertTrue(body.contains("ComplAI"), "Should contain app name");
        assertTrue(body.contains("2026"), "Should contain copyright year");
    }
}
