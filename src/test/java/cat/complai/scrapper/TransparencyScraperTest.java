package cat.complai.scrapper;

import cat.complai.scrapper.ProcedureScraper.ScraperMapping;
import cat.complai.scrapper.ProcedureScraper.SkipConfig;
import cat.complai.scrapper.ProcedureScraper.FieldExtractionRule;
import cat.complai.scrapper.ProcedureScraper.TransparencyConfig;
import cat.complai.scrapper.ProcedureScraper.TransparencyCrawlConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class TransparencyScraperTest {

    // -------------------------------------------------------------------------
    // validateTransparencyConfig
    // -------------------------------------------------------------------------

    @Test
    void validateTransparencyConfig_validConfig_doesNotThrow() {
        ScraperMapping mapping = minimalValidMapping();
        assertDoesNotThrow(() -> TransparencyScraper.validateTransparencyConfig(mapping, "testcity"));
    }

    @Test
    void validateTransparencyConfig_nullTransparency_throwsIllegalState() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.transparency = null;
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TransparencyScraper.validateTransparencyConfig(mapping, "testcity"));
        assertTrue(ex.getMessage().contains("missing 'transparency' section"));
    }

    @Test
    void validateTransparencyConfig_blankDetailLinkSelector_throwsIllegalState() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.transparency.crawl.detailLinkSelector = "  ";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TransparencyScraper.validateTransparencyConfig(mapping, "testcity"));
        assertTrue(ex.getMessage().contains("transparency.crawl.detailLinkSelector"));
    }

    @Test
    void validateTransparencyConfig_missingTitleField_throwsIllegalState() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.transparency.fields.clear();
        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = "h2";
        mapping.transparency.fields.put("section", rule);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TransparencyScraper.validateTransparencyConfig(mapping, "testcity"));
        assertTrue(ex.getMessage().contains("must define transparency field 'title'"));
    }

    @Test
    void validateTransparencyConfig_emptyFields_throwsIllegalState() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.transparency.fields.clear();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TransparencyScraper.validateTransparencyConfig(mapping, "testcity"));
        assertTrue(ex.getMessage().contains("no transparency field extraction rules"));
    }

    // -------------------------------------------------------------------------
    // shouldSkip
    // -------------------------------------------------------------------------

    @Test
    void shouldSkip_nullTitle_returnsTrue() {
        assertTrue(TransparencyScraper.shouldSkip(null, null));
    }

    @Test
    void shouldSkip_blankTitle_returnsTrue() {
        assertTrue(TransparencyScraper.shouldSkip("   ", null));
    }

    @Test
    void shouldSkip_validTitle_noSkipConfig_returnsFalse() {
        assertFalse(TransparencyScraper.shouldSkip("Transparència activa", null));
    }

    @Test
    void shouldSkip_titleMatchesForbiddenEntry_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals.add("Govern obert i transparència");
        assertTrue(TransparencyScraper.shouldSkip("Govern obert i transparència", skip));
    }

    @Test
    void shouldSkip_titleMatchesCaseInsensitive_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals.add("Transparència");
        assertTrue(TransparencyScraper.shouldSkip("transparència", skip));
    }

    @Test
    void shouldSkip_titleNotInForbiddenList_returnsFalse() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals.add("Transparència");
        assertFalse(TransparencyScraper.shouldSkip("Pressupostos municipals 2024", skip));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ScraperMapping minimalValidMapping() {
        ScraperMapping mapping = new ScraperMapping();
        mapping.baseUrl = "https://example.com/procedures";
        mapping.s3Region = "eu-west-1";

        var crawl = new ProcedureScraper.CrawlConfig();
        crawl.detailLinkSelector = "a[href*='detail']";
        mapping.crawl = crawl;

        FieldExtractionRule titleRule = new FieldExtractionRule();
        titleRule.selector = "h1";
        titleRule.multiple = false;
        mapping.fields = new LinkedHashMap<>();
        mapping.fields.put("title", titleRule);

        TransparencyConfig tc = new TransparencyConfig();
        tc.baseUrl = "https://seu-e.cat/ca/web/testcity/govern-obert-i-transparencia";

        TransparencyCrawlConfig tcc = new TransparencyCrawlConfig();
        tcc.detailLinkSelector = "a[href*='/govern-obert']";
        tc.crawl = tcc;

        FieldExtractionRule tTitle = new FieldExtractionRule();
        tTitle.selector = "h1";
        tTitle.multiple = false;
        tc.fields = new LinkedHashMap<>();
        tc.fields.put("title", tTitle);

        mapping.transparency = tc;

        return mapping;
    }
}
