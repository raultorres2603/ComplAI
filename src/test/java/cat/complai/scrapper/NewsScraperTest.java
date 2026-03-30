package cat.complai.scrapper;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.net.CookieStore;
import java.net.Proxy;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NewsScraperTest {

    @Test
    void validateNewsConfig_missingNews_throws() {
        ProcedureScraper.ScraperMapping mapping = new ProcedureScraper.ScraperMapping();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NewsScraper.validateNewsConfig(mapping, "elprat"));
        assertTrue(ex.getMessage().contains("missing 'news' section"));
    }

    @Test
    void validateNewsConfig_missingRequiredNewsField_throws() {
        ProcedureScraper.ScraperMapping mapping = new ProcedureScraper.ScraperMapping();
        mapping.news = minimalValidNewsConfig();
        mapping.news.fields.remove("publishedAt");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NewsScraper.validateNewsConfig(mapping, "elprat"));
        assertTrue(ex.getMessage().contains("publishedAt"));
    }

    @Test
    void categoryDiscovery_andSeedMerge_deduplicatesAndKeepsFallbacks() {
        String homeUrl = "https://elprataldia.es/";
        String html = "<nav>"
                + "<a href='https://elprataldia.es/category/actualitat/'>Actualitat</a>"
                + "<a href='https://elprataldia.es/category/esports/'>Esports</a>"
                + "<a href='https://elprataldia.es/category/esports/'>Esports dup</a>"
                + "<a href='https://elprataldia.es/category/actualitat/feed/'>Feed</a>"
                + "</nav>";

        ProcedureScraper.NewsConfig config = minimalValidNewsConfig();
        config.baseUrl = homeUrl;
        config.discovery.categoryLinkSelector = "a[href]";
        config.discovery.categoryIncludePatterns = List.of("^https://elprataldia\\.es/category/[^/?#]+/?$");
        config.discovery.categoryExcludePatterns = List.of("/feed/?$");
        config.seedCategoryUrls = List.of(
                "https://elprataldia.es/category/agenda/",
                "https://elprataldia.es/category/esports/");

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(homeUrl)).thenReturn(new FakeConnection(html));

            Set<String> discovered = NewsScraper.discoverCategoryUrls(config);
            Set<String> merged = NewsScraper.mergeCategoryUrls(discovered, config.seedCategoryUrls);

            assertEquals(Set.of(
                    "https://elprataldia.es/category/actualitat/",
                    "https://elprataldia.es/category/esports/",
                    "https://elprataldia.es/category/agenda/"), merged);
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void crawlArticleUrls_filtersAndTraversesPagination() {
        String categoryUrl = "https://elprataldia.es/category/actualitat/";
        String page2 = "https://elprataldia.es/category/actualitat/page/2/";

        String htmlPage1 = "<div class='dg-blog-grid'>"
                + "<article><h2 class='dg_bm_title'><a href='https://elprataldia.es/article-1/'>A1</a></h2></article>"
                + "<article><h2 class='dg_bm_title'><a href='https://elprataldia.es/article-1/'>A1 dup</a></h2></article>"
                + "<article><h2 class='dg_bm_title'><a href='https://elprataldia.es/category/actualitat/'>Category self</a></h2></article>"
                + "</div>"
                + "<a class='next page-numbers' href='" + page2 + "'>Next</a>";

        String htmlPage2 = "<div class='dg-blog-grid'>"
                + "<article><h2 class='dg_bm_title'><a href='https://elprataldia.es/article-2/'>A2</a></h2></article>"
                + "<article><h2 class='dg_bm_title'><a href='https://elprataldia.es/tag/noticia/'>Tag</a></h2></article>"
                + "</div>"
                + "<a class='page-numbers' href='" + categoryUrl + "'>Prev</a>";

        ProcedureScraper.NewsConfig config = minimalValidNewsConfig();
        config.crawl.articleLinkSelector = ".dg_bm_title a[href]";
        config.crawl.articleIncludePatterns = List.of("^https://elprataldia\\.es/(?!category/|tag/)[^?#]+/?$");
        config.crawl.articleExcludePatterns = List.of("#comments$");
        config.crawl.paginationLinkSelector = "a.page-numbers[href], a.next.page-numbers[href]";

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(categoryUrl)).thenReturn(new FakeConnection(htmlPage1));
            Mockito.when(Jsoup.connect(page2)).thenReturn(new FakeConnection(htmlPage2));

            Set<String> urls = NewsScraper.crawlArticleDetailUrls(Set.of(categoryUrl), config);

            assertEquals(Set.of(
                    "https://elprataldia.es/article-1/",
                    "https://elprataldia.es/article-2/"), urls);
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void generateNewsId_isDeterministic() {
        String urlA = "https://elprataldia.es/article-1/";
        String urlB = "https://elprataldia.es/article-2/";

        String idA1 = NewsScraper.generateNewsId(urlA);
        String idA2 = NewsScraper.generateNewsId(urlA);
        String idB = NewsScraper.generateNewsId(urlB);

        assertEquals(idA1, idA2);
        assertNotEquals(idA1, idB);
    }

    @Test
    void scrapeNewsArticle_extractsFieldsAndAppliesSkipRules() throws Exception {
        String articleUrl = "https://elprataldia.es/article-1/";
        String html = "<div class='title'>Noticia 1</div>"
                + "<div class='summary'><strong>Resumen</strong></div>"
                + "<div class='body'><p>Linea 1</p><p>Linea 2</p></div>"
                + "<div class='published'>Publicado el 29 de marzo de 2026</div>"
                + "<div class='categories'><a>Actualitat</a><a>Praticipa</a></div>"
                + "<div class='author'>Redacción</div>";

        ProcedureScraper.NewsConfig config = minimalValidNewsConfig();

        var jsoupConnect = Mockito.mockStatic(Jsoup.class, Mockito.CALLS_REAL_METHODS);
        try {
            Mockito.when(Jsoup.connect(articleUrl)).thenReturn(new FakeConnection(html));

            Optional<Map<String, Object>> result = NewsScraper.scrapeNewsArticle(articleUrl, config);
            assertTrue(result.isPresent());

            Map<String, Object> article = result.orElseThrow();
            assertEquals("Noticia 1", article.get("title"));
            assertEquals("Resumen", article.get("summary"));
            assertEquals("Linea 1\nLinea 2", article.get("body"));
            assertEquals("Publicado el 29 de marzo de 2026", article.get("publishedAt"));
            assertEquals("Actualitat\nPraticipa", article.get("categories"));
            assertEquals("Redacción", article.get("author"));
            assertEquals(NewsScraper.generateNewsId(articleUrl), article.get("newsId"));
            assertEquals(articleUrl, article.get("url"));
        } finally {
            jsoupConnect.close();
        }
    }

    @Test
    void shouldSkip_whenTitleForbiddenOrBodyEmpty_returnsTrue() {
        ProcedureScraper.NewsSkipConfig skip = new ProcedureScraper.NewsSkipConfig();
        skip.whenTitleEmptyOrEquals = Arrays.asList("No title", "Bloqueado");
        skip.whenBodyEmpty = true;

        Map<String, Object> forbiddenTitle = new LinkedHashMap<>();
        forbiddenTitle.put("title", "Bloqueado");
        forbiddenTitle.put("body", "contenido");

        Map<String, Object> emptyBody = new LinkedHashMap<>();
        emptyBody.put("title", "Valido");
        emptyBody.put("body", "   ");

        Map<String, Object> okArticle = new LinkedHashMap<>();
        okArticle.put("title", "Valido");
        okArticle.put("body", "texto");

        assertTrue(NewsScraper.shouldSkip(forbiddenTitle, skip));
        assertTrue(NewsScraper.shouldSkip(emptyBody, skip));
        assertFalse(NewsScraper.shouldSkip(okArticle, skip));
    }

    private static ProcedureScraper.NewsConfig minimalValidNewsConfig() {
        ProcedureScraper.NewsConfig config = new ProcedureScraper.NewsConfig();
        config.baseUrl = "https://elprataldia.es/";

        config.discovery = new ProcedureScraper.NewsDiscoveryConfig();
        config.discovery.categoryLinkSelector = "a[href]";
        config.discovery.categoryIncludePatterns = List.of("^https://elprataldia\\.es/category/[^/?#]+/?$");
        config.discovery.categoryExcludePatterns = List.of("/feed/?$");

        config.crawl = new ProcedureScraper.NewsCrawlConfig();
        config.crawl.articleLinkSelector = ".dg_bm_title a[href]";
        config.crawl.articleIncludePatterns = List.of("^https://elprataldia\\.es/(?!category/|tag/)[^?#]+/?$");
        config.crawl.articleExcludePatterns = List.of("/feed/?$");
        config.crawl.paginationLinkSelector = "a.next.page-numbers[href], a.page-numbers[href]";

        config.fields = new LinkedHashMap<>();
        config.fields.put("title", singleRule(".title"));
        config.fields.put("summary", singleRule(".summary strong"));
        config.fields.put("body", multipleRule(".body p"));
        config.fields.put("publishedAt", singleRule(".published"));
        config.fields.put("categories", multipleRule(".categories a"));
        config.fields.put("author", singleRule(".author"));

        config.skip = new ProcedureScraper.NewsSkipConfig();
        config.skip.whenTitleEmptyOrEquals = List.of("No title");
        config.skip.whenBodyEmpty = true;

        config.seedCategoryUrls = List.of("https://elprataldia.es/category/actualitat/");
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

    @SuppressWarnings("all")
    static class FakeConnection implements Connection {
        private final String html;

        FakeConnection(@NonNull String html) {
            this.html = html;
        }

        @Override
        public @NonNull Document get() {
            return Jsoup.parse(html, "https://elprataldia.es");
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
