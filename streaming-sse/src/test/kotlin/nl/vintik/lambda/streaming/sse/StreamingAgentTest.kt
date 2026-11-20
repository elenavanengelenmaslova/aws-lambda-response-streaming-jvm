package nl.vintik.lambda.streaming.sse

import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.instrument.Instrumentation
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream

/**
 * [StreamingAgent] decides whether streaming is possible at all, so the behaviour that matters is
 * what it does when something is wrong: it must stay inert rather than registering a patch that
 * will fail at runtime.
 *
 * The agent cannot patch anything here — the Lambda runtime class is absent — so
 * [StreamingAgent.isPatched] stays false throughout, which is also the state
 * [StreamingResponse] relies on to refuse to stream.
 */
class StreamingAgentTest {

    private val instrumentation: Instrumentation = mockk(relaxed = true)

    private fun jarAt(directory: Path, name: String): Path {
        val jar = directory.resolve(name)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("marker.txt"))
            out.write("probe".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `Given no runtime class to patch When the agent has not run Then streaming reports itself unavailable`() {
        assertFalse(
            StreamingAgent.isPatched(),
            "isPatched must only be true once the real runtime class was patched",
        )
    }

    @Test
    fun `Given an agent jar argument When premain runs Then the jar is appended and the transformer registered`(
        @TempDir directory: Path,
    ) {
        val jar = jarAt(directory, "function.jar")

        StreamingAgent.premain(jar.toString(), instrumentation)

        verify(exactly = 1) { instrumentation.appendToSystemClassLoaderSearch(any()) }
        verify(exactly = 1) { instrumentation.addTransformer(ResponsePostSuppressor) }
    }

    @Test
    fun `Given an argument pointing at no file When premain runs Then nothing is registered rather than failing later`(
        @TempDir directory: Path,
    ) {
        clearMocks(instrumentation, answers = false)

        StreamingAgent.premain(directory.resolve("absent.jar").toString(), instrumentation)

        verify(exactly = 0) { instrumentation.appendToSystemClassLoaderSearch(any()) }
        verify(exactly = 0) { instrumentation.addTransformer(any()) }
    }

    @Test
    fun `Given no argument and no conventional jar directory When premain runs Then the agent stays inert`() {
        clearMocks(instrumentation, answers = false)

        // Outside a Lambda sandbox /var/task/lib does not exist, so the jar cannot be resolved.
        StreamingAgent.premain(null, instrumentation)

        verify(exactly = 0) { instrumentation.addTransformer(any()) }
    }

    @Test
    fun `Given appending to the class loader fails When premain runs Then no transformer is registered`(
        @TempDir directory: Path,
    ) {
        clearMocks(instrumentation, answers = false)
        val failing: Instrumentation = mockk {
            io.mockk.every { appendToSystemClassLoaderSearch(any()) } throws UnsupportedOperationException("nope")
        }
        val jar = jarAt(directory, "function.jar")

        StreamingAgent.premain(jar.toString(), failing)

        verify(exactly = 0) { failing.addTransformer(any()) }
    }
}
