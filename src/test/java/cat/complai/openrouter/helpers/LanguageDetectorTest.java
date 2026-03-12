package cat.complai.openrouter.helpers;

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
}

