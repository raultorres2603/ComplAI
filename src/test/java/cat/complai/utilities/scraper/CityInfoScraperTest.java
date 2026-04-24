package cat.complai.utilities.scraper;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityInfoScraperTest {

    @Test
    void discoverThemeLinks_findsPratTemesAnchorsWithFilters() {
        String baseUrl = "https://www.elprat.cat/";
        String html = "<section id='prat-temes'>"
                + "<a href='https://www.elprat.cat/turisme'>Turisme</a>"
                + "<a href='https://www.elprat.cat/serveis-municipals'>Serveis</a>"
                + "<a href='https://www.elprat.cat/search'>Search</a>"
                + "</section>";

        ProcedureScraper.CityInfoConfig config = minimalConfig();

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(baseUrl)).thenReturn(new NewsScraperTest.FakeConnection(html));

            Map<String, String> themes = CityInfoScraper.discoverThemeLinks(config);

            assertEquals(2, themes.size());
            assertTrue(themes.containsKey("https://www.elprat.cat/turisme"));
            assertTrue(themes.containsKey("https://www.elprat.cat/serveis-municipals"));
            assertFalse(themes.containsKey("https://www.elprat.cat/search"));
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void crawlDetailUrls_handlesNestedAndDirectMenuLinks_dedupAndFilters() {
        String themeUrl = "https://www.elprat.cat/turisme";
        String nestedUrl = "https://www.elprat.cat/turisme/visita";
        String detailA = "https://www.elprat.cat/turisme/visita/platja";
        String detailB = "https://www.elprat.cat/turisme/visita/delta";

        String themeHtml = "<nav class='menu-turisme'>"
                + "<ul><li><a href='" + nestedUrl + "'>Visita</a></li>"
                + "<li><a href='" + detailB + "'>Delta</a></li>"
                + "<li><a href='mailto:info@elprat.cat'>Mail</a></li>"
                + "</ul></nav>";

        String nestedHtml = "<nav class='menu-turisme'>"
                + "<ul><li><a href='" + detailA + "'>Platja</a></li>"
                + "<li><a href='" + detailA + "#fragment'>Platja Dup</a></li></ul></nav>";

        ProcedureScraper.CityInfoConfig config = minimalConfig();
        config.crawl.maxDepth = 3;
        config.crawl.maxPages = 30;

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(themeUrl)).thenReturn(new NewsScraperTest.FakeConnection(themeHtml));
            Mockito.when(Jsoup.connect(nestedUrl)).thenReturn(new NewsScraperTest.FakeConnection(nestedHtml));
            Mockito.when(Jsoup.connect(detailA)).thenReturn(new NewsScraperTest.FakeConnection("<html></html>"));
            Mockito.when(Jsoup.connect(detailB)).thenReturn(new NewsScraperTest.FakeConnection("<html></html>"));

            Map<String, String> details = CityInfoScraper.crawlDetailUrls(Map.of(themeUrl, "Turisme"), config);

            assertTrue(details.containsKey(themeUrl));
            assertTrue(details.containsKey(nestedUrl));
            assertTrue(details.containsKey(detailA));
            assertTrue(details.containsKey(detailB));
            assertEquals(4, details.size());
            assertEquals("Turisme", details.get(detailA));
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void scrapeCityInfoPage_appliesSkipRulesForEmptyBody() throws Exception {
        String pageUrl = "https://www.elprat.cat/turisme/visita";
        String html = "<h1>Punts d'interes</h1><div class='summary'>Resum</div><div class='breadcrumb'>Inici</div>";

        ProcedureScraper.CityInfoConfig config = minimalConfig();

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(pageUrl)).thenReturn(new NewsScraperTest.FakeConnection(html));

            Optional<Map<String, Object>> result = CityInfoScraper.scrapeCityInfoPage(pageUrl, "Turisme", config);

            assertFalse(result.isPresent());
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void scrapeCityInfoPage_extractsAllFields() throws Exception {
        String pageUrl = "https://www.elprat.cat/turisme/visita/platja";
        String html = "<h1>Platja del Prat</h1>"
                + "<div class='summary'>Espai natural</div>"
                + "<article><p>L1</p><p>L2</p></article>"
                + "<nav class='breadcrumb'><li>Inici</li><li>Turisme</li></nav>";

        ProcedureScraper.CityInfoConfig config = minimalConfig();

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(pageUrl)).thenReturn(new NewsScraperTest.FakeConnection(html));

            Optional<Map<String, Object>> result = CityInfoScraper.scrapeCityInfoPage(pageUrl, "Turisme", config);

            assertTrue(result.isPresent());
            Map<String, Object> page = result.orElseThrow();
            assertEquals("Platja del Prat", page.get("title"));
            assertEquals("Espai natural", page.get("summary"));
            assertEquals("L1\nL2", page.get("body"));
            assertEquals("Inici\nTurisme", page.get("breadcrumbs"));
            assertEquals("Turisme", page.get("theme"));
            assertEquals(pageUrl, page.get("url"));
            assertNotNull(page.get("cityInfoId"));
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void canonicalizeUrl_rejectsInvalidSchemesAndRemovesFragments() {
        assertNull(CityInfoScraper.canonicalizeUrl("mailto:info@elprat.cat"));
        assertNull(CityInfoScraper.canonicalizeUrl("javascript:void(0)"));
        assertEquals("https://www.elprat.cat/path",
                CityInfoScraper.canonicalizeUrl("https://www.elprat.cat/path#section"));
    }

    private static ProcedureScraper.CityInfoConfig minimalConfig() {
        ProcedureScraper.CityInfoConfig config = new ProcedureScraper.CityInfoConfig();
        config.baseUrl = "https://www.elprat.cat/";

        config.discovery = new ProcedureScraper.CityInfoDiscoveryConfig();
        config.discovery.pratTemesSelector = "#prat-temes a[href]";
        config.discovery.themeIncludePatterns = List.of("^https?://www\\.elprat\\.cat/.*");
        config.discovery.themeExcludePatterns = List.of("/search/?$");

        config.crawl = new ProcedureScraper.CityInfoCrawlConfig();
        config.crawl.themeMenuSelector = "nav.menu-turisme a[href]";
        config.crawl.navIncludePatterns = List.of("^https?://www\\.elprat\\.cat/.*");
        config.crawl.navExcludePatterns = List.of("mailto:", "tel:", "javascript:");
        config.crawl.detailIncludePatterns = List.of("^https?://www\\.elprat\\.cat/.*");
        config.crawl.detailExcludePatterns = List.of("\\.(pdf|jpg)(\\?.*)?$");
        config.crawl.maxDepth = 3;
        config.crawl.maxPages = 100;

        config.fields = new LinkedHashMap<>();
        config.fields.put("title", singleRule("h1"));
        config.fields.put("summary", singleRule(".summary"));
        config.fields.put("body", multipleRule("article p"));
        config.fields.put("breadcrumbs", multipleRule(".breadcrumb li"));

        config.skip = new ProcedureScraper.CityInfoSkipConfig();
        config.skip.whenTitleEmptyOrEquals = List.of("Inici");
        config.skip.whenBodyEmpty = true;

        return config;
    }

    private static ProcedureScraper.FieldExtractionRule singleRule(String selector) {
        ProcedureScraper.FieldExtractionRule rule = new ProcedureScraper.FieldExtractionRule();
        rule.selector = selector;
        rule.multiple = false;
        return rule;
    }

    private static ProcedureScraper.FieldExtractionRule multipleRule(String selector) {
        ProcedureScraper.FieldExtractionRule rule = new ProcedureScraper.FieldExtractionRule();
        rule.selector = selector;
        rule.multiple = true;
        return rule;
    }

}
