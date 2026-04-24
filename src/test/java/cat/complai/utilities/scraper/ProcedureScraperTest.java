package cat.complai.utilities.scraper;

import cat.complai.utilities.scraper.ProcedureScraper.ScraperMapping;
import cat.complai.utilities.scraper.ProcedureScraper.SkipConfig;
import cat.complai.utilities.scraper.ProcedureScraper.CrawlConfig;
import cat.complai.utilities.scraper.ProcedureScraper.FieldExtractionRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure-function logic in {@link ProcedureScraper}.
 *
 * Network I/O (Jsoup crawl, S3 upload) and the {@code main} entry point are not
 * covered here — those require an integration harness. These tests guard the
 * mapping schema validation and the skip-condition evaluation, which are the
 * most
 * error-prone parts when adding new city mappings.
 */
class ProcedureScraperTest {

    // -------------------------------------------------------------------------
    // loadMapping — classpath integration
    // -------------------------------------------------------------------------

    @Test
    void loadMapping_elprat_loadsSuccessfully() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");

        assertNotNull(mapping, "Mapping must not be null");
        assertFalse(mapping.baseUrl.isBlank(), "baseUrl must be present");
        assertFalse(mapping.s3Region.isBlank(), "s3Region must be present");
        assertNotNull(mapping.crawl, "crawl config must be present");
        assertFalse(mapping.crawl.detailLinkSelector.isBlank(), "detailLinkSelector must be present");
        assertTrue(mapping.fields.containsKey("title"), "fields must contain 'title'");
        assertTrue(mapping.fields.containsKey("description"), "fields must contain 'description'");
        assertTrue(mapping.fields.containsKey("requirements"), "fields must contain 'requirements'");
        assertTrue(mapping.fields.containsKey("steps"), "fields must contain 'steps'");
    }

    @Test
    void loadMapping_unknownCity_throwsIoException() {
        IOException ex = assertThrows(IOException.class,
                () -> ProcedureScraper.loadMapping("nonexistent-city-xyz"));
        assertTrue(ex.getMessage().contains("nonexistent-city-xyz"),
                "Error message must name the unknown city");
    }

    // -------------------------------------------------------------------------
    // validateMapping — required field enforcement
    // -------------------------------------------------------------------------

    @Test
    void validateMapping_validMapping_doesNotThrow() {
        ScraperMapping mapping = minimalValidMapping();
        assertDoesNotThrow(() -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_missingBaseUrl_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.baseUrl = null;
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_blankBaseUrl_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.baseUrl = "  ";
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_missingCrawlConfig_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.crawl = null;
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_missingDetailLinkSelector_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.crawl.detailLinkSelector = null;
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_emptyFields_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.fields.clear();
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_missingTitleField_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.fields.remove("title");
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    @Test
    void validateMapping_missingS3Region_throws() {
        ScraperMapping mapping = minimalValidMapping();
        mapping.s3Region = null;
        assertThrows(IllegalStateException.class,
                () -> ProcedureScraper.validateMapping(mapping, "testcity"));
    }

    // -------------------------------------------------------------------------
    // shouldSkip — title filtering
    // -------------------------------------------------------------------------

    @Test
    void shouldSkip_nullTitle_returnsTrue() {
        assertTrue(ProcedureScraper.shouldSkip(null, null));
    }

    @Test
    void shouldSkip_blankTitle_returnsTrue() {
        assertTrue(ProcedureScraper.shouldSkip("   ", null));
    }

    @Test
    void shouldSkip_validTitle_nullSkipConfig_returnsFalse() {
        assertFalse(ProcedureScraper.shouldSkip("Empadronament", null));
    }

    @Test
    void shouldSkip_validTitle_emptyForbiddenList_returnsFalse() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of();
        assertFalse(ProcedureScraper.shouldSkip("Empadronament", skip));
    }

    @Test
    void shouldSkip_titleMatchesForbiddenExactly_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Portal de Tràmits");
        assertTrue(ProcedureScraper.shouldSkip("Portal de Tràmits", skip));
    }

    @Test
    void shouldSkip_titleMatchesForbiddenCaseInsensitive_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Portal de Tràmits");
        assertTrue(ProcedureScraper.shouldSkip("portal de tràmits", skip));
    }

    @Test
    void shouldSkip_titleDoesNotMatchForbidden_returnsFalse() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Portal de Tràmits");
        assertFalse(ProcedureScraper.shouldSkip("Empadronament", skip));
    }

    @Test
    void shouldSkip_titleMatchesOneOfMultipleForbidden_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Portal de Tràmits", "Inici", "Home");
        assertTrue(ProcedureScraper.shouldSkip("Inici", skip));
    }

    // -------------------------------------------------------------------------
    // elprat mapping — spot-check field extraction rules
    // -------------------------------------------------------------------------

    @Test
    void elpratMapping_requirementsField_isMultiple() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");
        FieldExtractionRule requirements = mapping.fields.get("requirements");
        assertNotNull(requirements);
        assertTrue(requirements.multiple,
                "'requirements' must use multiple=true to concatenate all <ul> elements");
    }

    @Test
    void elpratMapping_titleField_isSingle() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");
        FieldExtractionRule title = mapping.fields.get("title");
        assertNotNull(title);
        assertFalse(title.multiple,
                "'title' must use multiple=false (selectFirst)");
    }

    @Test
    void elpratMapping_skipCondition_includesPortalDeTramits() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");
        assertNotNull(mapping.skip);
        assertTrue(mapping.skip.whenTitleEmptyOrEquals.stream()
                .anyMatch(v -> v.equalsIgnoreCase("Portal de Tràmits")),
                "elprat mapping must skip pages titled 'Portal de Tràmits'");
    }

    @Test
    void elpratMapping_newsSection_loadsSuccessfully() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");

        assertNotNull(mapping.news, "news config must be present");
        assertEquals("https://elprataldia.es/", mapping.news.baseUrl);
        assertNotNull(mapping.news.discovery);
        assertFalse(mapping.news.discovery.categoryLinkSelector.isBlank());
        assertNotNull(mapping.news.crawl);
        assertFalse(mapping.news.crawl.articleLinkSelector.isBlank());
        assertFalse(mapping.news.crawl.paginationLinkSelector.isBlank());

        assertTrue(mapping.news.fields.containsKey("title"));
        assertTrue(mapping.news.fields.containsKey("summary"));
        assertTrue(mapping.news.fields.containsKey("body"));
        assertTrue(mapping.news.fields.containsKey("publishedAt"));
        assertTrue(mapping.news.fields.containsKey("categories"));

        assertNotNull(mapping.news.seedCategoryUrls);
        assertEquals(10, mapping.news.seedCategoryUrls.size(),
                "seed categories: 9 original elprataldia + 1 sala-de-premsa");
    }

    @Test
    void elpratMapping_cityInfoSection_loadsSuccessfully() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");

        assertNotNull(mapping.cityInfo, "cityInfo config must be present");
        assertEquals("https://www.elprat.cat/", mapping.cityInfo.baseUrl);
        assertNotNull(mapping.cityInfo.discovery);
        assertFalse(mapping.cityInfo.discovery.pratTemesSelector.isBlank());
        assertNotNull(mapping.cityInfo.crawl);
        assertFalse(mapping.cityInfo.crawl.themeMenuSelector.isBlank());

        assertTrue(mapping.cityInfo.fields.containsKey("title"));
        assertTrue(mapping.cityInfo.fields.containsKey("summary"));
        assertTrue(mapping.cityInfo.fields.containsKey("body"));
        assertTrue(mapping.cityInfo.fields.containsKey("breadcrumbs"));

        assertNotNull(mapping.cityInfo.skip);
        assertTrue(mapping.cityInfo.skip.whenBodyEmpty);
    }

    @Test
    void elpratMapping_additionalSeeds_hasOneEntry() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");

        assertNotNull(mapping.crawl.additionalSeeds);
        assertEquals(1, mapping.crawl.additionalSeeds.size(),
                "additionalSeeds must have exactly 1 entry (SIAC portal)");
        assertEquals("https://seu.elprat.cat/siac/default.aspx", mapping.crawl.additionalSeeds.get(0));
    }

    @Test
    void elpratMapping_eventSeedSites_hasThreeEntries() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");

        assertNotNull(mapping.events, "events config must be present");
        assertNotNull(mapping.events.seedSites);
        assertEquals(3, mapping.events.seedSites.size(),
                "seedSites must have 3 external event venues");
        for (ProcedureScraper.EventSeedSite site : mapping.events.seedSites) {
            assertNotNull(site.baseUrl, "each seedSite must have a baseUrl");
            assertFalse(site.baseUrl.isBlank());
            assertNotNull(site.eventLinkSelector, "each seedSite must have an eventLinkSelector");
            assertFalse(site.eventLinkSelector.isBlank());
        }
    }

    @Test
    void elpratMapping_cityInfoSeedThemeUrls_hasTwoEntries() throws IOException {
        ScraperMapping mapping = ProcedureScraper.loadMapping("elprat");

        assertNotNull(mapping.cityInfo.seedThemeUrls);
        assertEquals(2, mapping.cityInfo.seedThemeUrls.size(),
                "seedThemeUrls must have 2 external utility sites");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ScraperMapping minimalValidMapping() {
        ScraperMapping mapping = new ScraperMapping();
        mapping.baseUrl = "https://example.com/procedures";
        mapping.s3Region = "eu-west-1";

        CrawlConfig crawl = new CrawlConfig();
        crawl.detailLinkSelector = "a[href*='detail']";
        mapping.crawl = crawl;

        FieldExtractionRule titleRule = new FieldExtractionRule();
        titleRule.selector = "h1.title";
        titleRule.multiple = false;
        mapping.fields = new java.util.LinkedHashMap<>(Map.of("title", titleRule));

        return mapping;
    }
}
