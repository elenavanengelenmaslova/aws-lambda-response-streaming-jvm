package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Guards against a priming hook that is defined but never constructed: the handler class must hold
 * a static, non-null {@code PRIMING} of type {@link Priming}, so loading the class registers it.
 */
class BedrockStreamHandlerPrimingRegistrationTest {

    @Test
    void givenHandlerClassWhenInspectedThenStaticNonNullPrimingFieldOfTypePrimingExists() throws Exception {
        Field field = BedrockStreamHandler.class.getDeclaredField("PRIMING");

        assertTrue(Modifier.isStatic(field.getModifiers()), "PRIMING must be static");
        assertEquals(Priming.class, field.getType());

        field.setAccessible(true);
        Object value = field.get(null);

        assertNotNull(value, "PRIMING must be constructed when the handler class is initialised");
        assertTrue(value instanceof Priming);
    }
}
