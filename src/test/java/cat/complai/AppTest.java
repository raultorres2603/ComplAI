package cat.complai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void appClass_isLoadable() {
        assertNotNull(App.class);
    }

    @Test
    void appClass_hasMainMethod() {
        try {
            App.class.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            fail("App must have a main method");
        }
    }
}
