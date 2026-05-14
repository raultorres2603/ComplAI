package cat.complai.services;

import cat.complai.config.CivicVocabularyConfig;
import cat.complai.helpers.openrouter.CivicVocabularyService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("CivicVocabularyInitializer Unit Tests")
class CivicVocabularyInitializerTest {

    @Mock
    private CivicVocabularyService civicVocabularyService;

    @Mock
    private CivicVocabularyConfig civicVocabularyConfig;

    private CivicVocabularyInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CivicVocabularyInitializer(civicVocabularyService, civicVocabularyConfig);
    }

    @Test
    @DisplayName("initialize() runs without exception")
    void initialize_runsWithoutException() {
        assertDoesNotThrow(() -> initializer.initialize());
    }

    @Test
    @DisplayName("initialize() can be called twice")
    void initialize_calledTwice() {
        assertDoesNotThrow(() -> {
            initializer.initialize();
            initializer.initialize();
        });
    }
}
