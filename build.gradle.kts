import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

// Root build.gradle.kts — plugin classpath only. All build logic lives in subproject build files.
// Every dependency and plugin version is declared in `gradle/libs.versions.toml`; bump it there and
// all modules pick it up through the `libs` catalog accessors.

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.kover) apply false
}

// ---- resolvedCoordinates: dependency-resolution baseline --------------------------------------
// Prints the resolved group:name:version of every external module on the four main classpaths, so
// two trees can be diffed line by line. `./gradlew :module:dependencies` is not usable for this:
// its tree renders conflict resolution as `1.8.0 -> 1.9.0`, and parsing that out of ASCII art can
// hide exactly the difference a baseline is meant to catch.
//
// `plugins.withId("java")`, not a bare `subprojects {}` body: the root script is evaluated before
// the subprojects, so `configurations` is still empty at that point.
subprojects {
    plugins.withId("java") {
        tasks.register("resolvedCoordinates") {
            group = "verification"
            description = "Writes resolved group:name:version per configuration, for baseline diffing."

            val configNames = listOf(
                "compileClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
            )
            // Captured at configuration time; rootComponent is a Provider, so resolution still
            // happens lazily at execution time and the task stays configuration-cache safe.
            val roots = configNames.associateWith { name ->
                configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
            }
            val out = layout.buildDirectory.file("reports/resolved-coordinates/${project.name}.txt")
            outputs.file(out)
            // Never skip: the task's real input is dependency resolution, which is not modelled as
            // a file input. An up-to-date skip would make an "after" capture copy "before" content
            // and let the baseline diff pass on a tree whose resolution actually changed.
            outputs.upToDateWhen { false }

            doLast {
                val unresolved = mutableListOf<String>()
                val lines = roots.flatMap { (configName, rootComponent) ->
                    val seen = mutableSetOf<ComponentIdentifier>()
                    val coordinates = sortedSetOf<String>()
                    val queue = ArrayDeque<ResolvedComponentResult>()
                    queue += rootComponent.get()
                    while (queue.isNotEmpty()) {
                        val component = queue.removeFirst()
                        if (!seen.add(component.id)) continue
                        (component.id as? ModuleComponentIdentifier)?.let { id ->
                            coordinates += "${id.group}:${id.module}:${id.version}"
                        }
                        // The unresolved case is fatal, not skippable: `incoming.resolutionResult`
                        // does not fail on its own — it represents a failed edge as a graph node —
                        // so silently dropping one would let the baseline diff pass on a tree whose
                        // resolution is actually broken. `else` is required because DependencyResult
                        // is not sealed from Kotlin's point of view.
                        component.dependencies.forEach { dependency ->
                            when (dependency) {
                                is ResolvedDependencyResult -> queue += dependency.selected
                                is UnresolvedDependencyResult ->
                                    unresolved += "$configName  ${dependency.attempted.displayName}: ${dependency.failure.message}"
                                else -> {}
                            }
                        }
                    }
                    coordinates.map { "$configName  $it" }
                }

                // Fail before the write: a snapshot derived from a broken graph must not land on
                // disk, or a later `diff -ru before after` would compare two equally broken trees.
                if (unresolved.isNotEmpty()) {
                    error(unresolved.joinToString(prefix = "Unresolved dependencies:\n  - ", separator = "\n  - "))
                }

                out.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(lines.joinToString(separator = "\n", postfix = "\n"))
                }
            }
        }
    }
}

// ---- verifyCoverageReports: the single entry point for machine-readable coverage ---------------
// One task, three reports. CI runs exactly one invocation:
//
//   ./gradlew verifyCoverageReports koverVerify koverHtmlReport -PexcludeTags=integration --continue
//
// `--continue` matters: a failing coverage gate must not abort the build before the report tasks
// run, otherwise Codecov has nothing to upload.
//
// The Kotlin modules report through Kover, `:streaming-s3-example-java` through JaCoCo (Kover does
// not instrument a pure-Java module — see docs/log.md), so the third path has a different shape.
// All three are Gradle defaults and identical run to run, which is what lets the Codecov step in
// workflow-build.yml name them as a fixed list.
//
// This task changes no coverage gate: 90% on :streaming-core, 80% on :streaming-s3-example, 80% on
// :streaming-s3-example-java through its existing koverVerify alias.
val coverageReports = mapOf(
    ":streaming-core" to "streaming-core/build/reports/kover/report.xml",
    ":streaming-s3-example" to "streaming-s3-example/build/reports/kover/report.xml",
    ":streaming-s3-example-java" to "streaming-s3-example-java/build/reports/jacoco/test/jacocoTestReport.xml",
)

tasks.register("verifyCoverageReports") {
    group = "verification"
    description = "Produces the Kover/JaCoCo XML coverage reports of all three modules and asserts each one exists, parses, and reports covered lines."

    dependsOn(
        ":streaming-core:koverXmlReport",
        ":streaming-s3-example:koverXmlReport",
        ":streaming-s3-example-java:jacocoTestReport",
    )

    // Captured at configuration time so the task action touches no Project state (configuration
    // cache is enabled repo-wide).
    val reports = coverageReports.mapValues { (_, path) -> rootDir.resolve(path) }
    val relativePaths = coverageReports.values.toList()
    val pathsFile = layout.buildDirectory.file("reports/coverage-report-paths.txt")
    outputs.file(pathsFile)
    // Never skip: an up-to-date skip would let a deleted or emptied report pass unnoticed, and the
    // whole point of the task is that the build fails when a report is unusable.
    outputs.upToDateWhen { false }

    doLast {
        // Written before the assertions so the path list exists even when a report is unusable —
        // the cross-file check compares it against the Codecov step's `files:` input.
        pathsFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(relativePaths.joinToString(separator = "\n", postfix = "\n"))
        }

        val problems = reports.mapNotNull { (module, report) ->
            if (!report.isFile) {
                "$module: no coverage report at ${report.path}"
            } else {
                // External DTD loading off. The JaCoCo report declares
                // `<!DOCTYPE report PUBLIC … "report.dtd">` and report.dtd is not written next to
                // the XML, so a default DocumentBuilder either fails outright or resolves the
                // public ID over the network. The features cover the common parsers; the
                // entity resolver is the belt-and-braces stop that guarantees no lookup happens.
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isValidating = false
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
                val parsed = runCatching {
                    factory.newDocumentBuilder()
                        .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
                        .parse(report)
                }
                val document = parsed.getOrNull()
                if (document == null) {
                    "$module: coverage report at ${report.path} does not parse as XML (${parsed.exceptionOrNull()?.message})"
                } else {
                    val classes = document.getElementsByTagName("class")
                    val coveredLines = (0 until classes.length).sumOf { classIndex ->
                        val children = classes.item(classIndex).childNodes
                        (0 until children.length).sumOf { childIndex ->
                            val child = children.item(childIndex)
                            if (child is Element && child.tagName == "counter" && child.getAttribute("type") == "LINE") {
                                child.getAttribute("covered").toIntOrNull() ?: 0
                            } else {
                                0
                            }
                        }
                    }
                    when {
                        classes.length == 0 ->
                            "$module: coverage report at ${report.path} contains no <class> entries"
                        coveredLines == 0 ->
                            "$module: coverage report at ${report.path} reports zero covered lines across ${classes.length} class entries"
                        else -> {
                            logger.lifecycle("Coverage report OK: $module — ${classes.length} classes, $coveredLines covered lines")
                            null
                        }
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            error(problems.joinToString(prefix = "Unusable coverage report(s):\n  - ", separator = "\n  - "))
        }
    }
}
