package io.github.jeromekai.latent.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * :core is tested on a desktop JVM but runs on Android, and the two disagree in
 * places a compiler cannot see. One such place shipped a build that crashed on
 * launch: the inline regex flag (?U) is valid on the JVM and a
 * PatternSyntaxException on Android, in a static initialiser, so the app died
 * before drawing a frame while every test here passed.
 *
 * The real guard is CI installing the APK on an Android emulator. This is the
 * cheap, immediate one: it fails here, in seconds, on the known offenders.
 */
class AndroidPortabilityTest {
    private val sources: List<Pair<String, String>> = File("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.path to it.readText() }
        .toList()

    private fun offenders(pattern: Regex): List<String> =
        sources.flatMap { (path, text) ->
            text.lines().withIndex()
                .filter { (_, line) -> !line.trim().startsWith("//") && !line.trim().startsWith("*") && pattern.containsMatchIn(line) }
                .map { (index, line) -> "$path:${index + 1}: ${line.trim()}" }
        }

    @Test
    fun theSourcesWereFound() {
        assertTrue(sources.size > 10, "the scan found ${sources.size} files; the working directory is wrong")
    }

    @Test
    fun noRegexUsesAnInlineFlagAndroidRejects() {
        // (?U) UNICODE_CHARACTER_CLASS is the known one; (?x) COMMENTS and others are best avoided too.
        val found = offenders(Regex("""\(\?[a-zA-Z]*[Uux][a-zA-Z]*[):]"""))
        assertTrue(found.isEmpty(), "inline regex flags crash on Android:\n" + found.joinToString("\n"))
    }

    @Test
    fun noJdkOnlyFileOrStreamHelpersAreCalled() {
        // Present on a desktop JDK 17, missing from Android's libraries before API 33.
        val found = offenders(
            // Java's Stream.toList() is API 34; Kotlin's Sequence.toList() is fine, so flag Java streams themselves.
            Regex("""\.readAllBytes\(\)|\.transferTo\(|Files\.readString|Files\.writeString|Path\.of\(|import java\.util\.stream"""),
        )
        assertTrue(found.isEmpty(), "APIs newer than the app's minimum Android version:\n" + found.joinToString("\n"))
    }
}
