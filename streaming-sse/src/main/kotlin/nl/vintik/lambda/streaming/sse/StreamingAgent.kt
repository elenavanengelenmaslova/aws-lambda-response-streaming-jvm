package nl.vintik.lambda.streaming.sse

import java.io.File
import java.lang.instrument.Instrumentation
import java.util.jar.JarFile

/**
 * Java agent that makes [StreamingResponse] safe to use on the managed JVM Lambda runtime.
 *
 * ### Why an agent, and why this is not a Lambda layer or a custom runtime
 * Streaming requires one change to AWS's invocation loop: it must not post a second response for an
 * invocation the handler has already answered itself. The loop cannot be replaced from a library —
 * `/var/task` is not even on the JVM classpath, so a class shipped under a runtime package name can
 * never win — and replacing it wholesale (via `AWS_LAMBDA_EXEC_WRAPPER`) was measured to silently
 * stop CRaC/SnapStart hooks from firing.
 *
 * An agent is a dependency plus one flag. `premain` runs before the runtime's `main`, so the loop
 * stays AWS's, including whatever drives the CRaC callbacks. The function keeps
 * `Runtime: java25`, SnapStart, warm reuse and your handler signature.
 *
 * ### Setup
 * ```yaml
 * # Package the function as a zip containing the fat jar under lib/ (-javaagent needs a real jar
 * # file; a bare-jar CodeUri is extracted loose into /var/task).
 * CodeUri: ../../../build/dist/my-function.zip
 * Environment:
 *   Variables:
 *     JAVA_TOOL_OPTIONS: "-XX:+TieredCompilation -XX:TieredStopAtLevel=1
 *                         -javaagent:/var/task/lib/my-function.jar"
 * ```
 *
 * Set `Premain-Class` on your fat jar's manifest so the function jar is also the agent:
 * ```kotlin
 * tasks.shadowJar {
 *     manifest { attributes("Premain-Class" to "nl.vintik.lambda.streaming.sse.StreamingAgent") }
 * }
 * ```
 *
 * The agent argument is optional and overrides the jar path used for the class-loader append:
 * `-javaagent:/var/task/lib/my-function.jar=/var/task/lib/my-function.jar`.
 *
 * ### Failure behaviour
 * If anything goes wrong — the jar cannot be appended, the runtime class has changed — the agent
 * logs and patches nothing, leaving the function on AWS's normal buffered path rather than broken.
 * [isPatched] then returns false and [StreamingResponse] refuses to stream, so the problem surfaces
 * as one clear error rather than a failed invocation per request.
 */
public object StreamingAgent {

    /** Jar path used when the agent is given no argument; the conventional zip layout. */
    private const val DEFAULT_JAR_HINT = "/var/task/lib"

    /**
     * Agent entry point. Named by `Premain-Class`; not called directly.
     *
     * @param arguments optional path to the jar to append to the system class loader search.
     */
    @JvmStatic
    public fun premain(arguments: String?, instrumentation: Instrumentation) {
        log("premain jvm=${System.getProperty("java.vm.version")}")

        // The patched class lives on the application class loader; this module normally lives on a
        // child CustomerClassLoader, and a parent cannot see its child's classes — the injected
        // StreamingState call would fail with NoClassDefFoundError. Appending the jar to the system
        // loader's search path is the documented fix and makes one shared copy visible to both.
        val jar = resolveAgentJar(arguments)
        if (jar == null) {
            log("could not locate the agent jar (argument=${arguments ?: "<none>"}); not patching")
            return
        }
        val appended = runCatching {
            JarFile(jar).use { instrumentation.appendToSystemClassLoaderSearch(it) }
        }.onFailure { e ->
            log("appendToSystemClassLoaderSearch failed for $jar (${e::class.java.name}); not patching")
        }
        if (appended.isFailure) return
        log("appended to the system class loader search: $jar")

        instrumentation.addTransformer(ResponsePostSuppressor)
        log("registered transformer for ${ResponsePostSuppressor.TARGET_CLASS}")
    }

    /**
     * True when the runtime's response-posting method has been patched, i.e. streaming is safe.
     *
     * [StreamingResponse] checks this before streaming. Worth asserting in a post-deploy test: it
     * distinguishes "the agent is not loaded" from "streaming is slow".
     */
    @JvmStatic
    public fun isPatched(): Boolean = ResponsePostSuppressor.isApplied()

    /**
     * Resolves the jar to append: the agent argument when given, otherwise the single jar under
     * `/var/task/lib`. Returns null when it cannot be determined, which keeps the agent inert
     * rather than guessing.
     */
    private fun resolveAgentJar(arguments: String?): File? {
        arguments?.takeIf { it.isNotBlank() }?.let { path ->
            return File(path).takeIf { it.isFile }
        }
        val jars = File(DEFAULT_JAR_HINT).listFiles { file -> file.isFile && file.name.endsWith(".jar") }
        return jars?.singleOrNull()
    }

    private fun log(message: String) = System.err.println("$AGENT_LOG_PREFIX $message")
}
