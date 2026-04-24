package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageDetectorTest {

    @Test
    void detect_catalanText_returnsCA() {
        assertEquals("CA", LanguageDetector.detect("Vull presentar una queixa sobre el soroll al carrer."));
    }

    @Test
    void detect_spanishText_returnsES() {
        assertEquals("ES", LanguageDetector.detect("Quiero presentar una queja sobre el ruido en la calle."));
    }

    @Test
    void detect_englishText_returnsEN() {
        assertEquals("EN", LanguageDetector.detect("I would like to file a complaint about the noise on my street."));
    }

    @Test
    void detect_catalanWithMidotMarker_returnsCA() {
        // l·l is exclusively Catalan
        assertEquals("CA", LanguageDetector.detect("Cal col·locar la paperera al carrer Major."));
    }

    @Test
    void detect_spanishWithEnye_returnsES() {
        // ñ is uniquely Spanish
        assertEquals("ES", LanguageDetector.detect("El señor alcalde no atiende las quejas del vecindario."));
    }

    @Test
    void detect_nullInput_returnsCA() {
        // Default to Catalan when input is absent — El Prat residents are predominantly Catalan speakers.
        assertEquals("CA", LanguageDetector.detect(null));
    }

    @Test
    void detect_blankInput_returnsCA() {
        assertEquals("CA", LanguageDetector.detect("   "));
    }

    @Test
    void detect_mixedCatalanSpanish_picksCatalanWhenCatalanScoreHigher() {
        // Clearly Catalan sentence that also contains the word "pero" (ambiguous).
        // Catalan-specific signals should dominate.
        assertEquals("CA", LanguageDetector.detect("Vull saber quan es farà la reparació, però ningú em respon."));
    }

    @Test
    void detect_frenchText_returnsFR() {
        assertEquals("FR", LanguageDetector.detect("Je veux présenter une plainte sur le bruit."));
    }

    @Test
    void detect_frenchWithAccents_returnsFR() {
        // French uses accented characters extensively: é, è, ê, à, ô, û, ç
        assertEquals("FR", LanguageDetector.detect("J'ai une réclamation à faire concernant l'éclairage."));
    }

    @Test
    void detect_frenchWithCedilla_returnsFR() {
        // ç is uniquely French (absent in Spanish, Catalan, English)
        assertEquals("FR", LanguageDetector.detect("La mairie doit améliorer les façades des bâtiments."));
    }

    @Test
    void detect_frenchKeywords_returnsFR() {
        // French-specific keywords and phrases
        assertEquals("FR", LanguageDetector.detect("Bonjour, vous devez autoriser ma demande de construction."));
    }

    @Test
    void detect_frenchCivicVocabulary_returnsFR() {
        // French civic terms used in complaints
        assertEquals("FR", LanguageDetector.detect("Je veux demander un permis pour une manifestation."));
    }

    @Test
    void detect_frenchVousMarker_returnsFR() {
        // "vous" is strongly French (Spanish uses "usted", Catalan uses other forms)
        assertEquals("FR", LanguageDetector.detect("Pouvez-vous m'aider avec mon autorisation?"));
    }
}

