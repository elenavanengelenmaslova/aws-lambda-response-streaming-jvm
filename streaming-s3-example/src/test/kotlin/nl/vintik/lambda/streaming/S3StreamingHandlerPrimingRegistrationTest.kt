package nl.vintik.lambda.streaming

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.lang.reflect.Modifier

/**
 * Guards the construction site of [Priming].
 *
 * [Priming] registers itself with the global CRaC context **in its constructor**, so the hook only
 * exists if something actually constructs it. For a long time nothing did: the class was fully unit
 * tested and completely inert in production, so `beforeCheckpoint` never ran and no critical path
 * was warmed before the SnapStart snapshot (see `docs/log.md`). Unit tests that construct `Priming`
 * themselves cannot catch that, because they supply the missing construction site.
 *
 * This asserts the thing those tests cannot: that initializing [S3StreamingHandler] — the class the
 * SAM template names as the handler — brings a `Priming` instance into existence.
 */
class S3StreamingHandlerPrimingRegistrationTest {

    @Test
    fun `Given the deployed handler class When it is initialized Then a Priming instance exists so the CRaC hook is registered`() {
        // Reading the field triggers class initialization, which is when the companion runs.
        val field = S3StreamingHandler::class.java.getDeclaredField("PRIMING")
        field.isAccessible = true

        assertTrue(
            Modifier.isStatic(field.modifiers),
            "PRIMING must be static so class initialization creates it",
        )

        val priming = field.get(null)
        assertNotNull(priming) { "PRIMING is null: the CRaC hook would never be registered" }
        assertEquals(Priming::class.java, priming.javaClass)
    }
}
