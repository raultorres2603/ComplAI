package cat.complai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
