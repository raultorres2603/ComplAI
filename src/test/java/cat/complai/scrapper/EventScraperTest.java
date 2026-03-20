package cat.complai.scrapper;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
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

            Set<String> urls = invokeCrawlEventDetailUrls(mapping);
            assertEquals(2, urls.size());
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("event1")));
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("event2")));
        } finally {
            jsoupConnect.close();
        }
    }

    // Helper to invoke private static method
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
    static class FakeConnection implements org.jsoup.Connection {
        private final String html;

        public FakeConnection(String html) {
            this.html = html;
        }

        public Document get() {
            return Jsoup.parse(html, "https://www.elprat.cat");
        }

        @Override
        public org.jsoup.Connection response(org.jsoup.Connection.Response response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.jsoup.Connection.Response response() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.jsoup.Connection request(org.jsoup.Connection.Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.jsoup.Connection.Request request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.jsoup.Connection.Response execute() {
            throw new UnsupportedOperationException();
        }

        public Document post() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.jsoup.Connection postDataCharset(String charset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection newRequest() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'newRequest'");
        }

        @Override
        public Connection url(URL url) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'url'");
        }

        @Override
        public Connection url(String url) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'url'");
        }

        @Override
        public Connection proxy(@Nullable Proxy proxy) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'proxy'");
        }

        @Override
        public Connection proxy(String host, int port) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'proxy'");
        }

        @Override
        public Connection userAgent(String userAgent) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'userAgent'");
        }

        @Override
        public Connection timeout(int millis) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'timeout'");
        }

        @Override
        public Connection maxBodySize(int bytes) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'maxBodySize'");
        }

        @Override
        public Connection referrer(String referrer) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'referrer'");
        }

        @Override
        public Connection followRedirects(boolean followRedirects) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'followRedirects'");
        }

        @Override
        public Connection method(Method method) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'method'");
        }

        @Override
        public Connection ignoreHttpErrors(boolean ignoreHttpErrors) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'ignoreHttpErrors'");
        }

        @Override
        public Connection ignoreContentType(boolean ignoreContentType) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'ignoreContentType'");
        }

        @Override
        public Connection sslSocketFactory(SSLSocketFactory sslSocketFactory) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'sslSocketFactory'");
        }

        @Override
        public Connection data(String key, String value) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public Connection data(String key, String filename, InputStream inputStream) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public Connection data(String key, String filename, InputStream inputStream, String contentType) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public Connection data(Collection<KeyVal> data) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public Connection data(Map<String, String> data) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public Connection data(String... keyvals) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public @Nullable KeyVal data(String key) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'data'");
        }

        @Override
        public Connection requestBody(String body) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'requestBody'");
        }

        @Override
        public Connection header(String name, String value) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'header'");
        }

        @Override
        public Connection headers(Map<String, String> headers) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'headers'");
        }

        @Override
        public Connection cookie(String name, String value) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookie'");
        }

        @Override
        public Connection cookies(Map<String, String> cookies) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookies'");
        }

        @Override
        public Connection cookieStore(CookieStore cookieStore) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookieStore'");
        }

        @Override
        public CookieStore cookieStore() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'cookieStore'");
        }

        @Override
        public Connection parser(Parser parser) {
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
