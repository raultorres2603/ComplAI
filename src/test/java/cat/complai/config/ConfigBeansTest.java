package cat.complai.config;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Configuration Beans Tests")
class ConfigBeansTest {

    @Nested
    @DisplayName("CivicVocabularyConfig Tests")
    class CivicVocabularyConfigTests {

        @Test
        @DisplayName("Default constructor sets enabled to true")
        void defaultConstructor_enabledTrue() {
            CivicVocabularyConfig config = new CivicVocabularyConfig();
            assertTrue(config.isEnabled());
        }

        @Test
        @DisplayName("Constructor with enabled parameter sets value correctly")
        void constructorWithEnabled_setsValue() {
            CivicVocabularyConfig enabled = new CivicVocabularyConfig(true);
            assertTrue(enabled.isEnabled());

            CivicVocabularyConfig disabled = new CivicVocabularyConfig(false);
            assertFalse(disabled.isEnabled());
        }

        @Test
        @DisplayName("setEnabled updates the enabled flag")
        void setEnabled_updatesFlag() {
            CivicVocabularyConfig config = new CivicVocabularyConfig();
            config.setEnabled(false);
            assertFalse(config.isEnabled());
            config.setEnabled(true);
            assertTrue(config.isEnabled());
        }

        @Test
        @DisplayName("toString contains enabled state")
        void toString_containsEnabled() {
            CivicVocabularyConfig config = new CivicVocabularyConfig(true);
            assertTrue(config.toString().contains("enabled=true"));
        }
    }

    @Nested
    @DisplayName("SesConfiguration Tests")
    class SesConfigurationTests {

        @Test
        @DisplayName("Default constructor sets region default")
        void defaultConstructor_regionDefault() {
            SesConfiguration config = new SesConfiguration();
            assertEquals("eu-west-1", config.getRegion());
        }

        @Test
        @DisplayName("Three-arg constructor sets all fields")
        void threeArgConstructor_setsAllFields() {
            SesConfiguration config = new SesConfiguration("from@test.com", "us-east-1", "to@test.com");
            assertEquals("from@test.com", config.getFromEmail());
            assertEquals("us-east-1", config.getRegion());
            assertEquals("to@test.com", config.getRecipientEmail());
        }

        @Test
        @DisplayName("Setters update values")
        void setters_updateValues() {
            SesConfiguration config = new SesConfiguration();
            config.setFromEmail("new-from@test.com");
            config.setRegion("ap-southeast-1");
            config.setRecipientEmail("new-to@test.com");

            assertEquals("new-from@test.com", config.getFromEmail());
            assertEquals("ap-southeast-1", config.getRegion());
            assertEquals("new-to@test.com", config.getRecipientEmail());
        }

        @Test
        @DisplayName("toString masks emails")
        void toString_masksEmails() {
            SesConfiguration config = new SesConfiguration("from@test.com", "eu-west-1", "to@test.com");
            String str = config.toString();
            assertTrue(str.contains("f**@test.com"));
            assertTrue(str.contains("t*@test.com"));
        }

        @Test
        @DisplayName("ISesRecipientProvider returns recipient email")
        void implementsISesRecipientProvider() {
            SesConfiguration config = new SesConfiguration("f@t.com", "eu-west-1", "r@t.com");
            assertEquals("r@t.com", config.getRecipientEmail());
        }

        @Test
        @DisplayName("ISesSenderConfig returns from email and region")
        void implementsISesSenderConfig() {
            SesConfiguration config = new SesConfiguration("f@t.com", "eu-west-1", "r@t.com");
            assertEquals("f@t.com", config.getFromEmail());
            assertEquals("eu-west-1", config.getRegion());
        }
    }

    @Nested
    @DisplayName("ISesRecipientProvider Tests")
    class ISesRecipientProviderTests {

        @Test
        @DisplayName("Interface loads successfully")
        void interfaceLoads() {
            assertDoesNotThrow(() -> Class.forName("cat.complai.config.ISesRecipientProvider"));
        }
    }

    @Nested
    @DisplayName("ISesSenderConfig Tests")
    class ISesSenderConfigTests {

        @Test
        @DisplayName("Interface loads successfully")
        void interfaceLoads() {
            assertDoesNotThrow(() -> Class.forName("cat.complai.config.ISesSenderConfig"));
        }
    }

    @Nested
    @DisplayName("RefusalPhrasesConfig Tests")
    class RefusalPhrasesConfigTests {

        @Test
        @DisplayName("Default constructor uses DEFAULT_PHRASES")
        void defaultConstructor_usesDefaultPhrases() {
            RefusalPhrasesConfig config = new RefusalPhrasesConfig();
            assertEquals(RefusalPhrasesConfig.DEFAULT_PHRASES, config.getPhrases());
        }

        @Test
        @DisplayName("DEFAULT_PHRASES contains English, Catalan, and Spanish phrases")
        void defaultPhrases_containsAllLanguages() {
            List<String> phrases = RefusalPhrasesConfig.DEFAULT_PHRASES;
            assertFalse(phrases.isEmpty());
            assertTrue(phrases.stream().anyMatch(p -> p.contains("can't")),
                    "Should contain English phrases");
            assertTrue(phrases.stream().anyMatch(p -> p.contains("no puc")),
                    "Should contain Catalan phrases");
            assertTrue(phrases.stream().anyMatch(p -> p.contains("no puedo")),
                    "Should contain Spanish phrases");
        }

        @Test
        @DisplayName("DEFAULT_PHRASES is unmodifiable")
        void defaultPhrases_isUnmodifiable() {
            assertThrows(UnsupportedOperationException.class,
                    () -> RefusalPhrasesConfig.DEFAULT_PHRASES.add("extra"));
        }

        @Test
        @DisplayName("setPhrases replaces the phrase list")
        void setPhrases_replacesList() {
            RefusalPhrasesConfig config = new RefusalPhrasesConfig();
            List<String> custom = List.of("custom refusal");
            config.setPhrases(custom);
            assertSame(custom, config.getPhrases());
        }

        @Test
        @DisplayName("setPhrases null is accepted (defensive)")
        void setPhrases_nullAccepted() {
            RefusalPhrasesConfig config = new RefusalPhrasesConfig();
            config.setPhrases(null);
            assertNull(config.getPhrases());
        }
    }
}
