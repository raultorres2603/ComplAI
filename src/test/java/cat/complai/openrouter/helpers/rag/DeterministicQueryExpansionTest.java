package cat.complai.openrouter.helpers.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicQueryExpansionTest {

    @Test
    void expandProcedureQueryTokens_noopWhenDisabled() {
        List<String> base = List.of("municipal", "license");

        List<String> expanded = DeterministicQueryExpansion.expandProcedureQueryTokens(base, false);

        assertEquals(base, expanded);
    }

    @Test
    void expandEventQueryTokens_addsConfiguredAliasesDeterministically() {
        List<String> expanded = DeterministicQueryExpansion.expandEventQueryTokens(List.of("cinema", "kids"), true);

        assertEquals(List.of("cinema", "kids", "film", "children", "family"), expanded);
    }

    @Test
    void termFrequency_countsExpandedTokens() {
        var frequency = DeterministicQueryExpansion.termFrequency(List.of("a", "b", "a"));

        assertEquals(2, frequency.get("a"));
        assertEquals(1, frequency.get("b"));
        assertTrue(frequency.size() == 2);
    }
}