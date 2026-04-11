package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransparencyRagHelperTest {

    @Test
    void search_relatedQuery_returnsRankedResults() {
        TransparencyRagHelper helper = new TransparencyRagHelper("testcity");
        List<TransparencyRagHelper.TransparencyItem> results =
                helper.search("subvencions atorgades ajuntament");
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        assertEquals("Subvencions atorgades 2024", results.get(0).title);
    }

    @Test
    void search_unrelatedQuery_returnsEmpty() {
        TransparencyRagHelper helper = new TransparencyRagHelper("testcity");
        assertTrue(helper.search("martian taxation policy").isEmpty());
    }

    @Test
    void search_emptyQuery_returnsEmpty() {
        TransparencyRagHelper helper = new TransparencyRagHelper("testcity");
        assertTrue(helper.search("").isEmpty());
    }

    @Test
    void search_nullQuery_returnsEmpty() {
        TransparencyRagHelper helper = new TransparencyRagHelper("testcity");
        assertTrue(helper.search(null).isEmpty());
    }

    @Test
    void getAllTransparencyItems_returnsAllFive() {
        TransparencyRagHelper helper = new TransparencyRagHelper("testcity");
        assertEquals(5, helper.getAllTransparencyItems().size());
    }

    @Test
    void search_pressupost_returnsBudgetItem() {
        TransparencyRagHelper helper = new TransparencyRagHelper("testcity");
        List<TransparencyRagHelper.TransparencyItem> results = helper.search("pressupost municipal");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(i -> i.title.contains("Pressupost")));
    }
}
