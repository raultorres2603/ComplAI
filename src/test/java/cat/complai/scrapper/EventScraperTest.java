package cat.complai.scrapper;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.net.CookieStore;
import java.net.Proxy;
import java.net.URL;
import java.util.*;

import javax.net.ssl.SSLSocketFactory;

import static org.junit.jupiter.api.Assertions.*;

class EventScraperTest {

    @Test
    void testExtractFieldValue_single() {
        String html = "<div><h1 class='event-title'>Concert</h1></div>";
        Document doc = Jsoup.parse(html);
        ProcedureScraper.FieldExtractionRule rule = new ProcedureScraper.FieldExtractionRule();
        rule.selector = ".event-title";
        rule.multiple = false;
        String value = invokeExtractFieldValue(doc, rule);
        assertEquals("Concert", value);
    }

    @Test
    void testExtractFieldValue_multiple() {
        String html = "<ul><li class='event-audience'>Adults</li><li class='event-audience'>Kids</li></ul>";
        Document doc = Jsoup.parse(html);
        ProcedureScraper.FieldExtractionRule rule = new ProcedureScraper.FieldExtractionRule();
        rule.selector = ".event-audience";
        rule.multiple = true;
        String value = invokeExtractFieldValue(doc, rule);
        assertEquals("Adults\nKids", value);
    }

    @Test
    void testShouldSkip() {
        ProcedureScraper.SkipConfig skip = new ProcedureScraper.SkipConfig();
        skip.whenTitleEmptyOrEquals = Arrays.asList("Portal de Tràmits", "");
        assertTrue(EventScraper.shouldSkip("", skip));
        assertTrue(EventScraper.shouldSkip("Portal de Tràmits", skip));
        assertFalse(EventScraper.shouldSkip("Concert", skip));
    }

    @Test
    void testCrawlEventDetailUrls_pagination() throws Exception {
        // Simulate two pages with event links and a next page link
        String baseUrl = "https://www.elprat.cat/la-ciutat/guia-agenda";
        String page1 = "<div class='view-guia-agenda'><div class='llistat-serveis-view'><h4><a href='https://www.elprat.cat/la-ciutat/guia-agenda/event1'>Event 1</a></h4></div></div>"
                +
                "<a class='pager-next' href='/la-ciutat/guia-agenda?page=1'>Next</a>";
        String page2 = "<div class='view-guia-agenda'><div class='llistat-serveis-view'><h4><a href='https://www.elprat.cat/la-ciutat/guia-agenda/event2'>Event 2</a></h4></div></div>";

        // Patch Jsoup.connect().get() using Mockito
        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(baseUrl)).thenReturn(new FakeConnection(page1));
            Mockito.when(Jsoup.connect("https://www.elprat.cat/la-ciutat/guia-agenda?page=1"))
                    .thenReturn(new FakeConnection(page2));

            ProcedureScraper.ScraperMapping mapping = new ProcedureScraper.ScraperMapping();

            mapping.events = new ProcedureScraper.EventsConfig();
            mapping.events.baseUrl = baseUrl;
            mapping.events.crawl = new ProcedureScraper.EventCrawlConfig();
            mapping.events.crawl.eventLinkSelector = ".view-guia-agenda .llistat-serveis-view h4 a[href^='https://www.elprat.cat/la-ciutat/guia-agenda/']";
            mapping.events.crawl.eventDetailExcludePattern = null;
            // Add at least one dummy field to make mapping structurally valid
            mapping.events.fields = new LinkedHashMap<>();
            ProcedureScraper.FieldExtractionRule dummyRule = new ProcedureScraper.FieldExtractionRule();
            dummyRule.selector = "h1";
            dummyRule.multiple = false;
            mapping.events.fields.put("title", dummyRule);

            Set<String> urls = invokeCrawlEventDetailUrls(mapping);
            assertEquals(2, urls.size());
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("event1")));
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("event2")));
        } finally {
            jsoupConnect.close();
        }
    }

    // Helper to invoke private static method
    @SuppressWarnings("unchecked")
    private Set<String> invokeCrawlEventDetailUrls(ProcedureScraper.ScraperMapping mapping) {
        try {
            var m = EventScraper.class.getDeclaredMethod("crawlEventDetailUrls", ProcedureScraper.ScraperMapping.class);
            m.setAccessible(true);
            return (Set<String>) m.invoke(null, mapping);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Fake Jsoup.Connection for mocking
    @SuppressWarnings("all")
    static class FakeConnection implements org.jsoup.Connection {
        private final String html;

        public FakeConnection(@NonNull String html) {
            this.html = html;
        }

        @Override
        public @NonNull Document get() {
            return Jsoup.parse(html, (String) "https://www.elprat.cat");
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
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'newRequest'");
        }

        @Override
        public @NonNull Connection url(@NonNull URL url) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'url'");
        }

        @Override
        public @NonNull Connection url(@NonNull String url) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'url'");
        }

        @Override
        public @NonNull Connection proxy(@Nullable Proxy proxy) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'proxy'");
        }

        @Override
        public @NonNull Connection proxy(@NonNull String host, int port) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'proxy'");
        }

        @Override
        public @NonNull Connection userAgent(@NonNull String userAgent) {
            // Support method chaining in tests
            return this;
        }

        @Override
        public @NonNull Connection timeout(int millis) {
            // Support method chaining in tests
            return this;
        }

        @Override
        public @NonNull Connection maxBodySize(int bytes) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'maxBodySize'");
        }

        @Override
        public @NonNull Connection referrer(@NonNull String referrer) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'referrer'");
        }

        @Override
        public @NonNull Connection followRedirects(boolean followRedirects) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'followRedirects'");
        }

        @Override
        public @NonNull Connection method(@NonNull Method method) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'method'");
        }

        @Override
        public @NonNull Connection ignoreHttpErrors(boolean ignoreHttpErrors) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'ignoreHttpErrors'");
        }

        @Override
        public @NonNull Connection ignoreContentType(boolean ignoreContentType) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'ignoreContentType'");
        }

        @Override
        public @NonNull Connection sslSocketFactory(@NonNull SSLSocketFactory sslSocketFactory) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'sslSocketFactory'");
        }

        @Override
        public @NonNull Connection data(@NonNull String key, @NonNull String value) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @NonNull Connection data(@NonNull String key, @NonNull String filename,
                @NonNull InputStream inputStream) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @NonNull Connection data(@NonNull String key, @NonNull String filename, @NonNull InputStream inputStream,
                @NonNull String contentType) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @NonNull Connection data(@NonNull Collection<KeyVal> data) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @NonNull Connection data(@NonNull Map<String, String> data) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @NonNull Connection data(@NonNull String @NonNull... keyvals) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @Nullable KeyVal data(@NonNull String key) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @NonNull Connection requestBody(@NonNull String body) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'requestBody'");
        }

        @Override
        public @NonNull Connection header(@NonNull String name, @NonNull String value) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'header'");
        }

        @Override
        public @NonNull Connection headers(@NonNull Map<String, String> headers) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'headers'");
        }

        @Override
        public @NonNull Connection cookie(@NonNull String name, @NonNull String value) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookie'");
        }

        @Override
        public @NonNull Connection cookies(@NonNull Map<String, String> cookies) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookies'");
        }

        @Override
        public @NonNull Connection cookieStore(@NonNull CookieStore cookieStore) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookieStore'");
        }

        @Override
        public @NonNull CookieStore cookieStore() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookieStore'");
        }

        @Override
        public @NonNull Connection parser(@NonNull Parser parser) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'parser'");
        }
    }

    // Helper to invoke private static method
    private String invokeExtractFieldValue(Document doc, ProcedureScraper.FieldExtractionRule rule) {
        try {
            var m = EventScraper.class.getDeclaredMethod("extractFieldValue", Document.class,
                    ProcedureScraper.FieldExtractionRule.class);
            m.setAccessible(true);
            return (String) m.invoke(null, doc, rule);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
