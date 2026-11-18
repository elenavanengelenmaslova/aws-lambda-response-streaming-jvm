package nl.vintik.lambda.streaming.sse

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the real connection against a local HTTP server standing in for the Lambda Runtime API.
 *
 * Worth doing with a socket rather than a mock, because the three things that make the response
 * stream are all wire-level: the `Lambda-Runtime-Function-Response-Mode: streaming` header, the
 * prelude content type (the marker Node's `HttpResponseStream.from()` sets), and chunked transfer
 * encoding. A mock would assert our own intent; this asserts what actually goes out.
 */
class RuntimeApiChannelFactoryTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String

    private val received = CountDownLatch(1)
    private var requestMethod: String? = null
    private var requestPath: String? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var body: ByteArray = ByteArray(0)
    private var respondWith: Int = RUNTIME_API_ACCEPTED

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestMethod = exchange.requestMethod
            requestPath = exchange.requestURI.path
            headers = exchange.requestHeaders.mapValues { it.value.toList() }
            body = exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(respondWith, -1)
            exchange.close()
            received.countDown()
        }
        server.start()
        endpoint = "127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    @Test
    fun `Given an invocation When a channel is opened and written Then the request carries the streaming headers and the body`() {
        val channel = RuntimeApiChannelFactory(endpoint = endpoint).open("req-42")

        channel.output.write("prelude".toByteArray())
        channel.output.flush()
        channel.output.write("tokens".toByteArray())
        val status = channel.complete()

        assertTrue(received.await(5, TimeUnit.SECONDS), "the server never received the request")
        assertEquals(RUNTIME_API_ACCEPTED, status)
        assertEquals("POST", requestMethod)
        assertEquals("/2018-06-01/runtime/invocation/req-42/response", requestPath)
        assertEquals("streaming", header("Lambda-Runtime-Function-Response-Mode"))
        assertEquals("application/vnd.awslambda.http-integration-response", header("Content-Type"))
        assertEquals("chunked", header("Transfer-Encoding"))
        assertEquals("preludetokens", body.decodeToString())
    }

    @Test
    fun `Given the Runtime API answers an error When the channel is completed Then that status is returned rather than swallowed`() {
        respondWith = 400
        val channel = RuntimeApiChannelFactory(endpoint = endpoint).open("req-43")
        channel.output.write("x".toByteArray())

        val status = channel.complete()

        assertEquals(400, status)
    }

    @Test
    fun `Given a blank request id When opening a channel Then it is rejected before any connection is made`() {
        val factory = RuntimeApiChannelFactory(endpoint = endpoint)

        assertThrows(IllegalArgumentException::class.java) { factory.open("   ") }
    }

    @Test
    fun `Given no runtime API endpoint When opening a channel Then the error says streaming needs a Lambda environment`() {
        val factory = RuntimeApiChannelFactory(endpoint = null)

        val failure = assertThrows(IllegalStateException::class.java) { factory.open("req-44") }

        assertTrue(
            failure.message?.contains(RuntimeApiChannelFactory.RUNTIME_API_ENV) == true,
            "the error must name the missing variable, got: ${failure.message}",
        )
    }
}
