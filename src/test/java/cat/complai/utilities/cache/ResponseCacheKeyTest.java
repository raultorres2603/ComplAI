package cat.complai.utilities.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResponseCacheKey Tests")
class ResponseCacheKeyTest {

    @Test
    @DisplayName("should create valid cache key with all fields")
    void testCreateValidCacheKey() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.PARKING, 42);

        assertEquals("testcity", key.cityId());
        assertEquals(12345, key.procedureContextHash());
        assertEquals(67890, key.eventContextHash());
        assertEquals(QuestionCategory.PARKING, key.category());
        assertEquals(42, key.questionHash());
    }

    @Test
    @DisplayName("should reject null cityId")
    void testRejectNullCityId() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResponseCacheKey(null, 0, 0, QuestionCategory.OTHER, 0);
        });
    }

    @Test
    @DisplayName("should reject blank cityId")
    void testRejectBlankCityId() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResponseCacheKey("   ", 0, 0, QuestionCategory.OTHER, 0);
        });
    }

    @Test
    @DisplayName("should reject null category")
    void testRejectNullCategory() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResponseCacheKey("testcity", 0, 0, null, 0);
        });
    }

    @Test
    @DisplayName("identical keys should be equal")
    void testIdenticalKeysEqual() {
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.TAX, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.TAX, 0);

        assertEquals(key1, key2);
    }

    @Test
    @DisplayName("different cityId should produce different keys")
    void testDifferentCityIdProducesDifferentKeys() {
        ResponseCacheKey key1 = new ResponseCacheKey("city1", 12345, 67890, QuestionCategory.PARKING, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("city2", 12345, 67890, QuestionCategory.PARKING, 0);

        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("different procedureContextHash should produce different keys")
    void testDifferentProcedureHashProducesDifferentKeys() {
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 11111, 67890, QuestionCategory.PARKING, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 22222, 67890, QuestionCategory.PARKING, 0);

        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("different eventContextHash should produce different keys")
    void testDifferentEventHashProducesDifferentKeys() {
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 12345, 11111, QuestionCategory.PARKING, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 12345, 22222, QuestionCategory.PARKING, 0);

        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("different category should produce different keys")
    void testDifferentCategoryProducesDifferentKeys() {
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.PARKING, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.TAX, 0);

        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("different questionHash should produce different keys")
    void testDifferentQuestionHashProducesDifferentKeys() {
        int hash1 = "com pagar l'impost?".strip().toLowerCase(java.util.Locale.ROOT).hashCode();
        int hash2 = "on és la biblioteca?".strip().toLowerCase(java.util.Locale.ROOT).hashCode();
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.OTHER, hash1);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.OTHER, hash2);

        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("same normalized question should produce equal questionHash and equal keys")
    void testSameNormalizedQuestionProducesSameKey() {
        int hash1 = "Hola, com pots ajudar-me?".strip().toLowerCase(java.util.Locale.ROOT).hashCode();
        int hash2 = "hola, com pots ajudar-me?".strip().toLowerCase(java.util.Locale.ROOT).hashCode();
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 0, 0, QuestionCategory.OTHER, hash1);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 0, 0, QuestionCategory.OTHER, hash2);

        assertEquals(key1, key2, "Case-insensitive normalized questions should produce equal keys");
    }

    @Test
    @DisplayName("hash code should be consistent")
    void testHashCodeConsistency() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.LIBRARY, 0);
        int hash1 = key.hashCode();
        int hash2 = key.hashCode();

        assertEquals(hash1, hash2, "hashCode() should be consistent");
    }

    @Test
    @DisplayName("equal keys should have equal hash codes")
    void testEqualKeysHaveSameHashCode() {
        ResponseCacheKey key1 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.GARBAGE, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.GARBAGE, 0);

        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    @DisplayName("different keys can have different hash codes")
    void testDifferentKeysCanHaveDifferentHashCodes() {
        ResponseCacheKey key1 = new ResponseCacheKey("city1", 12345, 67890, QuestionCategory.ADMINISTRATION, 0);
        ResponseCacheKey key2 = new ResponseCacheKey("city2", 12345, 67890, QuestionCategory.ADMINISTRATION, 0);

        assertFalse(key1.equals(key2), "Different keys should not be equal");
    }

    @Test
    @DisplayName("key should be usable as HashMap key")
    void testUsableAsHashMapKey() {
        java.util.Map<ResponseCacheKey, String> map = new java.util.HashMap<>();
        ResponseCacheKey key = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.COMPLAINT, 0);

        map.put(key, "cached response");
        assertEquals("cached response", map.get(key));
    }

    @Test
    @DisplayName("toString should not reveal sensitive data")
    void testToStringDoesNotRevealSensitiveData() {
        ResponseCacheKey key = new ResponseCacheKey("elprat", 12345, 67890, QuestionCategory.PARKING, 0);
        String str = key.toString();

        assertTrue(str.contains("elprat"));
        assertTrue(str.contains("PARKING"));
        assertFalse(str.contains("user"), "Should not contain 'user' (PII indicator)");
        assertFalse(str.contains("query"), "Should not contain 'query' (PII indicator)");
    }

    @Test
    @DisplayName("record should be immutable")
    void testRecordImmutability() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 12345, 67890, QuestionCategory.OTHER, 0);

        assertEquals("testcity", key.cityId());
    }

    @Test
    @DisplayName("should accept valid city names")
    void testAcceptVariousCityNames() {
        String[] cityNames = { "elprat", "testcity", "another-city", "city_123" };
        for (String cityName : cityNames) {
            assertDoesNotThrow(() -> {
                new ResponseCacheKey(cityName, 0, 0, QuestionCategory.OTHER, 0);
            }, "Should accept valid city name: " + cityName);
        }
    }
}
