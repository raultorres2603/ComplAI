package cat.complai.utilities.scraper;

import cat.complai.utilities.scraper.GencatProcedureScraper.CrawlConfig;
import cat.complai.utilities.scraper.GencatProcedureScraper.FieldExtractionRule;
import cat.complai.utilities.scraper.GencatProcedureScraper.ScraperMapping;
import cat.complai.utilities.scraper.GencatProcedureScraper.SkipConfig;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.CookieStore;
import java.net.Proxy;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class GencatProcedureScraperTest {

    @Test
    void loadMapping_loadsSuccessfully() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();

        assertNotNull(mapping);
        assertFalse(mapping.baseUrl.isBlank());
        assertFalse(mapping.s3Region.isBlank());
        assertNotNull(mapping.crawl);
        assertFalse(mapping.crawl.detailLinkSelector.isBlank());
        assertTrue(mapping.fields.containsKey("title"));
        assertTrue(mapping.fields.containsKey("description"));
        assertTrue(mapping.fields.containsKey("requirements"));
        assertTrue(mapping.fields.containsKey("steps"));
    }

    @Test
    void shouldSkip_nullTitle_returnsTrue() {
        assertTrue(GencatProcedureScraper.shouldSkip(null, null));
    }

    @Test
    void shouldSkip_blankTitle_returnsTrue() {
        assertTrue(GencatProcedureScraper.shouldSkip("   ", null));
    }

    @Test
    void shouldSkip_validTitle_nullSkipConfig_returnsFalse() {
        assertFalse(GencatProcedureScraper.shouldSkip("Ajuts", null));
    }

    @Test
    void shouldSkip_titleMatchesForbiddenExactly_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Tràmits per temes");
        assertTrue(GencatProcedureScraper.shouldSkip("Tràmits per temes", skip));
    }

    @Test
    void shouldSkip_titleMatchesForbiddenCaseInsensitive_returnsTrue() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Tràmits per temes");
        assertTrue(GencatProcedureScraper.shouldSkip("tràmits per temes", skip));
    }

    @Test
    void shouldSkip_titleDoesNotMatchForbidden_returnsFalse() {
        SkipConfig skip = new SkipConfig();
        skip.whenTitleEmptyOrEquals = List.of("Tràmits per temes");
        assertFalse(GencatProcedureScraper.shouldSkip("Ajuts per a la rehabilitació", skip));
    }

    @Test
    void extractFieldValue_singleSelector_findsElement() throws Exception {
        String html = "<div class='fpca-tramit'><h1 class='menu-tramits-xl__title'>Ajuts</h1></div>";
        Document doc = Jsoup.parse(html);

        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = "h1.menu-tramits-xl__title";
        rule.multiple = false;

        String result = invokeExtractFieldValue(doc, rule);
        assertEquals("Ajuts", result);
    }

    @Test
    void extractFieldValue_singleSelector_noMatch_returnsEmpty() throws Exception {
        Document doc = Jsoup.parse("<html></html>");

        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = "h1.nonexistent";
        rule.multiple = false;

        String result = invokeExtractFieldValue(doc, rule);
        assertEquals("", result);
    }

    @Test
    void extractFieldValue_multipleSelector_concatenatesElements() throws Exception {
        String html = "<section id='requisits'><ul><li>Requisit A</li><li>Requisit B</li></ul></section>";
        Document doc = Jsoup.parse(html);

        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = "section#requisits ul li";
        rule.multiple = true;

        String result = invokeExtractFieldValue(doc, rule);
        assertEquals("Requisit A\nRequisit B", result);
    }

    @Test
    void extractFieldValue_multipleSelector_skipsEmptyElements() throws Exception {
        String html = "<ul><li>First</li><li>  </li><li>Third</li></ul>";
        Document doc = Jsoup.parse(html);

        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = "ul li";
        rule.multiple = true;

        String result = invokeExtractFieldValue(doc, rule);
        assertEquals("First\nThird", result);
    }

    @Test
    void extractFieldValue_multipleSelector_noMatch_returnsEmpty() throws Exception {
        Document doc = Jsoup.parse("<html></html>");

        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = "ul.nonexistent li";
        rule.multiple = true;

        String result = invokeExtractFieldValue(doc, rule);
        assertEquals("", result);
    }

    @Test
    void scrapeProcedure_extractsFieldsCorrectly() throws Exception {
        String detailUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes/exemple";
        String html = "<div class='fpca-tramit'><h1 class='menu-tramits-xl__title'>Ajuts</h1></div>"
                + "<div class='contingut-tramit'><p>Descripció del tràmit</p></div>"
                + "<section id='requisits'><ul><li>Requisit 1</li><li>Requisit 2</li></ul></section>"
                + "<div class='steps-content'><ol><li>Pas 1</li><li>Pas 2</li></ol></div>"
                + "<div class='banner-tramits-xl__info'><dl><dd><a>Ciutadania</a></dd></dl></div>";

        ScraperMapping mapping = minimalMapping();

        try (MockedStatic<Jsoup> jsoupStatic = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoupStatic.when(() -> Jsoup.connect(detailUrl)).thenReturn(new FakeConnection(html));

            Optional<Map<String, Object>> result = invokeScrapeProcedure(detailUrl, mapping);

            assertTrue(result.isPresent());
            Map<String, Object> procedure = result.orElseThrow();
            assertEquals("Ajuts", procedure.get("title"));
            assertEquals("Descripció del tràmit", procedure.get("description"));
            assertEquals("Requisit 1\nRequisit 2", procedure.get("requirements"));
            assertEquals("Pas 1\nPas 2", procedure.get("steps"));
            assertEquals("Ciutadania", procedure.get("targetAudience"));
            assertTrue(((String) procedure.get("procedureId")).length() > 0);
            assertEquals(detailUrl, procedure.get("url"));
        }
    }

    @Test
    void scrapeProcedure_skipTitle_returnsEmpty() throws Exception {
        String detailUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes/portada";
        String html = "<div class='fpca-tramit'><h1 class='menu-tramits-xl__title'>Tràmits per temes</h1></div>";

        ScraperMapping mapping = minimalMapping();
        mapping.skip = new SkipConfig();
        mapping.skip.whenTitleEmptyOrEquals = List.of("Tràmits per temes");

        try (MockedStatic<Jsoup> jsoupStatic = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoupStatic.when(() -> Jsoup.connect(detailUrl)).thenReturn(new FakeConnection(html));

            Optional<Map<String, Object>> result = invokeScrapeProcedure(detailUrl, mapping);

            assertFalse(result.isPresent());
        }
    }

    @Test
    void crawlProcedureDetailUrls_extractsDetailLinks() throws Exception {
        String baseUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes";
        String html = "<html>"
                + "<li class='list-group-item'><a href='/tramits-temes/procedure-1/'>Proc 1</a></li>"
                + "<li class='list-group-item'><a href='/tramits-temes/procedure-2/'>Proc 2</a></li>"
                + "</html>";

        ScraperMapping mapping = minimalMapping();
        mapping.crawl.categoryLinkSelector = null;
        mapping.crawl.additionalSeeds = null;

        try (MockedStatic<Jsoup> jsoupStatic = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoupStatic.when(() -> Jsoup.connect(baseUrl)).thenReturn(new FakeConnection(html, baseUrl));

            Set<String> detailUrls = invokeCrawlProcedureDetailUrls(mapping);

            assertEquals(2, detailUrls.size());
            assertTrue(detailUrls.contains("https://tramits.gencat.cat/tramits-temes/procedure-1/"));
            assertTrue(detailUrls.contains("https://tramits.gencat.cat/tramits-temes/procedure-2/"));
        }
    }

    @Test
    void crawlProcedureDetailUrls_followsCategoryLinks() throws Exception {
        String baseUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes";
        String categoryPageUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes?tema=123";

        String baseHtml = "<html>"
                + "<ul id='navTemResp'>"
                + "<li><a href='?tema=123'>Tema 1</a></li>"
                + "</ul>"
                + "<li class='list-group-item'><a href='/tramits-temes/procedure-1/'>Proc 1</a></li>"
                + "</html>";

        String categoryHtml = "<html>"
                + "<li class='list-group-item'><a href='/tramits-temes/procedure-2/'>Proc 2</a></li>"
                + "</html>";

        ScraperMapping mapping = minimalMapping();
        mapping.crawl.categoryLinkSelector = "ul#navTemResp a[href*='?tema=']";
        mapping.crawl.additionalSeeds = null;

        try (MockedStatic<Jsoup> jsoupStatic = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoupStatic.when(() -> Jsoup.connect(baseUrl)).thenReturn(new FakeConnection(baseHtml, baseUrl));
            jsoupStatic.when(() -> Jsoup.connect(categoryPageUrl)).thenReturn(new FakeConnection(categoryHtml, categoryPageUrl));

            Set<String> detailUrls = invokeCrawlProcedureDetailUrls(mapping);

            assertEquals(2, detailUrls.size());
            assertTrue(detailUrls.contains("https://tramits.gencat.cat/tramits-temes/procedure-1/"));
            assertTrue(detailUrls.contains("https://tramits.gencat.cat/tramits-temes/procedure-2/"));
        }
    }

    @Test
    void crawlProcedureDetailUrls_excludesMatchingExcludePattern() throws Exception {
        String baseUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes";
        String html = "<html>"
                + "<li class='list-group-item'><a href='/tramits-temes/procedure-1/'>Proc 1</a></li>"
                + "<li class='list-group-item'><a href='/tramits-temes/procedure-2/Tramitar=True'>Proc 2</a></li>"
                + "</html>";

        ScraperMapping mapping = minimalMapping();
        mapping.crawl.categoryLinkSelector = null;
        mapping.crawl.detailLinkExcludePattern = "Tramitar=True";
        mapping.crawl.additionalSeeds = null;

        try (MockedStatic<Jsoup> jsoupStatic = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoupStatic.when(() -> Jsoup.connect(baseUrl)).thenReturn(new FakeConnection(html, baseUrl));

            Set<String> detailUrls = invokeCrawlProcedureDetailUrls(mapping);

            assertEquals(1, detailUrls.size());
            assertTrue(detailUrls.contains("https://tramits.gencat.cat/tramits-temes/procedure-1/"));
        }
    }

    @Test
    void crawlProcedureDetailUrls_handlesFetchErrorGracefully() throws Exception {
        String baseUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes";

        ScraperMapping mapping = minimalMapping();
        mapping.crawl.categoryLinkSelector = null;
        mapping.crawl.additionalSeeds = null;

        try (MockedStatic<Jsoup> jsoupStatic = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoupStatic.when(() -> Jsoup.connect(anyString())).thenThrow(new RuntimeException("Connection timeout"));

            Set<String> detailUrls = invokeCrawlProcedureDetailUrls(mapping);

            assertTrue(detailUrls.isEmpty());
        }
    }

    @Test
    void gencatMapping_titleField_isSingle() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        FieldExtractionRule title = mapping.fields.get("title");
        assertNotNull(title);
        assertFalse(title.multiple);
    }

    @Test
    void gencatMapping_descriptionField_isSingle() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        FieldExtractionRule description = mapping.fields.get("description");
        assertNotNull(description);
        assertFalse(description.multiple);
    }

    @Test
    void gencatMapping_requirementsField_isMultiple() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        FieldExtractionRule requirements = mapping.fields.get("requirements");
        assertNotNull(requirements);
        assertTrue(requirements.multiple);
    }

    @Test
    void gencatMapping_skipCondition_includesTramitsPerTemes() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        assertNotNull(mapping.skip);
        assertTrue(mapping.skip.whenTitleEmptyOrEquals.stream()
                .anyMatch(v -> v.equalsIgnoreCase("Tràmits per temes")));
    }

    @Test
    void gencatMapping_skipCondition_includesPortalDeTramits() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        assertNotNull(mapping.skip);
        assertTrue(mapping.skip.whenTitleEmptyOrEquals.stream()
                .anyMatch(v -> v.equalsIgnoreCase("Portal de Tràmits")));
    }

    @Test
    void gencatMapping_stepsField_isMultiple() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        FieldExtractionRule steps = mapping.fields.get("steps");
        assertNotNull(steps);
        assertTrue(steps.multiple);
    }

    @Test
    void gencatMapping_targetAudienceField_isSingle() throws Exception {
        ScraperMapping mapping = GencatProcedureScraper.loadMapping();
        FieldExtractionRule targetAudience = mapping.fields.get("targetAudience");
        assertNotNull(targetAudience);
        assertFalse(targetAudience.multiple);
    }

    // -------------------------------------------------------------------------
    // Helpers — reflection for private methods
    // -------------------------------------------------------------------------

    private static String invokeExtractFieldValue(Document doc, FieldExtractionRule rule) throws Exception {
        Method method = GencatProcedureScraper.class.getDeclaredMethod(
                "extractFieldValue", Document.class, FieldExtractionRule.class);
        method.setAccessible(true);
        return (String) method.invoke(null, doc, rule);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> invokeScrapeProcedure(String url, ScraperMapping mapping)
            throws Exception {
        Method method = GencatProcedureScraper.class.getDeclaredMethod(
                "scrapeProcedure", String.class, ScraperMapping.class);
        method.setAccessible(true);
        try {
            return (Optional<Map<String, Object>>) method.invoke(null, url, mapping);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> invokeCrawlProcedureDetailUrls(ScraperMapping mapping) throws Exception {
        Method method = GencatProcedureScraper.class.getDeclaredMethod(
                "crawlProcedureDetailUrls", ScraperMapping.class);
        method.setAccessible(true);
        try {
            return (Set<String>) method.invoke(null, mapping);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    private static ScraperMapping minimalMapping() {
        ScraperMapping mapping = new ScraperMapping();
        mapping.baseUrl = "https://tramits.gencat.cat/ca/tramits/tramits-temes";
        mapping.s3Region = "eu-west-1";

        CrawlConfig crawl = new CrawlConfig();
        crawl.detailLinkSelector = "li.list-group-item a[href*='/tramits-temes/'], li.llista-titol + li a[href*='/tramits-temes/']";
        mapping.crawl = crawl;

        mapping.fields = new LinkedHashMap<>();
        mapping.fields.put("title", singleRule("div.fpca-tramit h1.menu-tramits-xl__title, h1.title"));
        mapping.fields.put("description", singleRule("meta[name='description'], section#que-es p, div.contingut-tramit p"));
        mapping.fields.put("requirements", multipleRule("section#requisits ul li, section#documentacio ul li"));
        mapping.fields.put("steps", multipleRule("div.steps-content ol li, section#steps ol li"));
        mapping.fields.put("targetAudience", singleRule("div.banner-tramits-xl__info dl:first-child dd a, dl dt + dd"));

        return mapping;
    }

    private static FieldExtractionRule singleRule(String selector) {
        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = selector;
        rule.multiple = false;
        return rule;
    }

    private static FieldExtractionRule multipleRule(String selector) {
        FieldExtractionRule rule = new FieldExtractionRule();
        rule.selector = selector;
        rule.multiple = true;
        return rule;
    }

    @SuppressWarnings("all")
    static class FakeConnection implements Connection {
        private final String html;
        private final String baseUrl;

        FakeConnection(@NonNull String html) {
            this(html, "https://tramits.gencat.cat");
        }

        FakeConnection(@NonNull String html, @NonNull String baseUrl) {
            this.html = html;
            this.baseUrl = baseUrl;
        }

        @Override
        public @NonNull Document get() {
            return Jsoup.parse(html, baseUrl);
        }

        @Override
        public @NonNull Connection userAgent(@NonNull String userAgent) {
            return this;
        }

        @Override
        public @NonNull Connection timeout(int millis) {
            return this;
        }

        @Override
        public @NonNull Connection response(Connection.Response response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection.@NonNull Response response() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection request(Connection.Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection.@NonNull Request request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection.@NonNull Response execute() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Document post() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection postDataCharset(@NonNull String charset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection newRequest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection url(@NonNull URL url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection url(@NonNull String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection proxy(@Nullable Proxy proxy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection proxy(@NonNull String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection maxBodySize(int bytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection referrer(@NonNull String referrer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection followRedirects(boolean followRedirects) {
            return this;
        }

        @Override
        public @NonNull Connection method(@NonNull Method method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection ignoreHttpErrors(boolean ignoreHttpErrors) {
            return this;
        }

        @Override
        public @NonNull Connection ignoreContentType(boolean ignoreContentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection sslSocketFactory(@NonNull SSLSocketFactory sslSocketFactory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection data(@NonNull String key, @NonNull String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection data(@NonNull String key, @NonNull String filename,
                @NonNull InputStream inputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection data(@NonNull String key, @NonNull String filename, @NonNull InputStream inputStream,
                @NonNull String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection data(java.util.Collection<KeyVal> data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection data(@NonNull Map<String, String> data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection data(@NonNull String @NonNull... keyvals) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable KeyVal data(@NonNull String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection requestBody(@NonNull String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection header(@NonNull String name, @NonNull String value) {
            return this;
        }

        @Override
        public @NonNull Connection headers(@NonNull Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection cookie(@NonNull String name, @NonNull String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection cookies(@NonNull Map<String, String> cookies) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection cookieStore(@NonNull CookieStore cookieStore) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull CookieStore cookieStore() {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Connection parser(@NonNull Parser parser) {
            throw new UnsupportedOperationException();
        }
    }
}
