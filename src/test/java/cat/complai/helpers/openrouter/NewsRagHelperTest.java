package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsRagHelperTest {

    @Test
    void search_relatedQuery_returnsRankedResults() {
        NewsRagHelper helper = new NewsRagHelper("testcity");

        List<NewsRagHelper.News> results = helper.search("latest recycling news in the city");

        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        assertEquals("TestCity launches new recycling campaign", results.get(0).title);
    }

    @Test
    void search_unrelatedQuery_returnsEmpty() {
        NewsRagHelper helper = new NewsRagHelper("testcity");

        List<NewsRagHelper.News> results = helper.search("martian taxation policy");

        assertTrue(results.isEmpty());
    }
}
