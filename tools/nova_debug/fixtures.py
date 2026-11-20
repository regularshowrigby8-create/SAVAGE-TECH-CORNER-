"""Fixtures for the honesty verifier.

Each fixture is a tiny Kotlin project plus the findings the debugger *must*
produce for it. The verifier runs the real rules over these files and fails when
a rule:

  * does not fire on the defect it exists for          (theatre), or
  * fires on the correct code beside it                (false positive).

``expect`` holds ``(rule id, severity, must_appear_substring)`` triples. A rule id
with ``allow_absent=False`` must be present; ``quiet`` lists rule ids that must
NOT appear anywhere in that fixture's findings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Tuple, Optional


@dataclass
class Fixture:
    name: str
    files: Dict[str, str]                    # relative path -> source
    expect: List[Tuple[str, str, str]] = field(default_factory=list)
    quiet: List[str] = field(default_factory=list)
    notes: str = ""
    # A fixture with expect=[] proves nothing on its own: it passes whether the
    # rule is correctly silent or simply dead. would_fire carries the same code
    # with the defect planted, and the harness requires the quiet rule to fire
    # there. Without it, a "must stay quiet" check is a wish, not a test.
    would_fire: Optional["Fixture"] = None


# 1. The failure that shipped: `.stalled` on an object that never declares it.
MISSING_MEMBER = Fixture(
    name="missing_type_member",
    files={
        "Stall.kt": '''package demo

object Stall {
    const val TIMEOUT_MS = 800L
}
''',
        "Screen.kt": '''package demo

fun screen(stalled: Boolean): Boolean {
    return !stalled
}

fun other(): Boolean {
    // legal: the member exists
    return Stall.TIMEOUT_MS > 0 && Stall.TIMEOUT_MS < 10
}
''',
        "Broken.kt": '''package demo

fun broken(stalled: Boolean): Boolean {
    return !stalled && Stall.SKIP_NEXT
}
''',
    },
    expect=[("KT_MISSING_TYPE_MEMBER", "error", "SKIP_NEXT")],
    quiet=["KT_UNRESOLVED_IDENTIFIER"],
    notes="a member of a project object that it never declares is a compile error",
)

# 2. A bare identifier that is bound nowhere.
UNDECLARED_NAME = Fixture(
    name="undeclared_identifier",
    files={
        "Good.kt": '''package demo

fun good(list: List<Int>): Int {
    val total = list.sum()
    val copy = total
    return copy
}
''',
        "Bad.kt": '''package demo

fun bad(list: List<Int>): Int {
    val total = list.sum()
    return total + missingTail
}
''',
    },
    expect=[("KT_UNRESOLVED_IDENTIFIER", "warn", "missingTail")],
    quiet=[],
    notes="reported at WARN: without a classpath a library name is indistinguishable from a typo",
)

# 3. A file that cannot compile: braces do not balance.
UNBALANCED = Fixture(
    name="unbalanced_braces",
    files={
        "Broken.kt": '''package demo

fun broken(): Int {
    if (true) {
        return 1
    return 2
}
''',
    },
    expect=[("KT_UNBALANCED_BRACES", "error", "unmatched brace")],
    quiet=[],
    notes="lexically detectable, so it is an ERROR",
)

# 4. Dead controls, and live ones that must not be reported.
DEAD_BUTTONS = Fixture(
    name="dead_buttons",
    files={
        "Screen.kt": '''package demo

import androidx.compose.material3.Button

@Composable
fun Live(onClick: () -> Unit) {
    Button(onClick = onClick) { Text("live") }
    Button(onClick = { println("does something") }) { Text("also live") }
    Button(enabled = false, onClick = { println("x") }) { Text("dead if hard disabled") }
}

@Composable
fun Dead() {
    Button(onClick = {}) { Text("nothing") }
    Button(onClick = doNothing) { Text("empty handler") }
    Surface { Text("no onClick at all") }
}

fun doNothing() {
}
''',
    },
    expect=[
        ("KT_DEAD_BUTTON", "warn", "empty onClick lambda"),
        ("KT_DEAD_BUTTON", "warn", "no onClick"),
        ("KT_DEAD_BUTTON", "warn", "whose body is empty"),
        ("KT_DISABLED_CONTROL", "warn", "enabled = false"),
    ],
    quiet=["KT_UNRESOLVED_IDENTIFIER"],
    notes="parameter-delegated onClick is live; capturing a parameter must never be reported",
)

# 5. Failure reported as an ordinary empty result (false green).
FALSE_GREEN = Fixture(
    name="failure_as_zero",
    files={
        "Repo.kt": '''package demo

class Repo {
    fun servers(): List<String> =
        runCatching { discover() }.getOrDefault(0).let { emptyList() }

    private fun discover(): List<String> = listOf("a")

    fun honest(): Result<List<String>> = runCatching { discover() }
}
''',
    },
    expect=[("KT_FAILURE_AS_ZERO", "warn", "getOrDefault")],
    quiet=[],
    notes="a swallowed exception must not look like a successful empty run",
)

# 6. Silent failure paths.
EMPTY_CATCH = Fixture(
    name="empty_catch",
    files={
        "Safe.kt": '''package demo

fun safe() {
    try {
        println("work")
    } catch (error: Exception) {
        println("failed: ${error.message}")
    }
}
''',
        "Silent.kt": '''package demo

fun silent() {
    try {
        println("work")
    } catch (error: Exception) {
    }
}
''',
    },
    expect=[("KT_EMPTY_CATCH", "warn", "empty")],
    quiet=[],
    notes="an empty catch hides the failure",
)

# 7. Main-thread IO in a lifecycle entry point.
MAIN_THREAD = Fixture(
    name="main_thread_io",
    files={
        "Screen.kt": '''package demo

class Screen {
    fun onCreate() {
        val text = File("x").readText()
        println(text)
    }

    suspend fun load() {
        val text = File("y").readText()
        println(text)
    }
}
''',
    },
    expect=[("KT_MAIN_THREAD_IO", "warn", "readText")],
    quiet=[],
    notes="a suspend function may switch dispatchers, so only the lifecycle body is reported",
)

# 8. A save path that is not atomic on its fallback branch.
NON_ATOMIC = Fixture(
    name="non_atomic_save",
    files={
        "Store.kt": '''package demo

class Store(private val file: File) {
    fun save(text: String) {
        val tmp = File("$file.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
        }
    }
}
''',
    },
    expect=[("KT_NON_ATOMIC_SAVE", "warn", "writeText")],
    quiet=[],
    notes="the rename fallback writes the destination in place",
)

# 9. A dead route: a state field the UI writes and nothing reads.
IMPORT = Fixture(
    name="missing_import",
    files={
        "Focus.kt": '''package demo.ui

class FocusDirection
''',
        "Screen.kt": '''package demo.ui.screen

fun move(direction: FocusDirection) = direction
''',
    },
    expect=[("KT_MISSING_IMPORT", "error", "FocusDirection")],
    quiet=[],
    notes="the FocusDirection bug from the build diagnostics",
)

# 10. Duplicate JVM signatures.
DUPLICATES = Fixture(
    name="duplicate_functions",
    files={
        "Twice.kt": '''package demo

class Twice {
    fun go(name: String): String = name
    fun go(name: String): String = name + "!"
}
''',
    },
    expect=[("KT_DUPLICATE_FUNCTION", "warn", "declared 2 times")],
    quiet=[],
    notes="two identical JVM signatures do not compile",
)

# 10b. Extensions on one receiver with different names are not duplicates.
#      Regression guard: the receiver type used to be read as the function name,
#      so any two extensions on the same receiver were reported as a duplicate
#      (it fired on SourceEngine.publish / verifyInto, both correct).
EXTENSION_OVERLOAD = Fixture(
    name="extension_receivers",
    files={
        # The generic receiver is the shape that actually broke the parser:
        # `Scope<T>` was read as the function name, so two different extensions
        # on it collapsed into `Scope()` twice. A non-generic receiver never
        # reproduced it -- the first version of this fixture passed while the
        # bug was live, which is the failure mode a regression test must not have.
        "Ext.kt": """package demo

class Scope<T>

fun Scope<String>.publish(state: String) { println(state) }
fun Scope<String>.verifyInto(state: String, limit: Int = 3) { println(state + limit) }
""",
    },
    expect=[],
    quiet=["KT_DUPLICATE_FUNCTION"],
    # Same two extensions, one of them genuinely declared twice: the rule must
    # fire here, or the quiet check above is meaningless.
    would_fire=Fixture(
        name="extension_receivers_planted",
        files={
            "Ext.kt": """package demo

class Scope<T>

fun Scope<String>.publish(state: String) { println(state) }
fun Scope<String>.publish(state: String) { println(state + "!") }
""",
        },
        expect=[("KT_DUPLICATE_FUNCTION", "warn", "declared 2 times")],
        notes="a real duplicate on a generic receiver",
    ),
    notes="different extension names on one generic receiver are different functions",
)

# 11. Correct, idiomatic Kotlin. Nothing may fire except the informational rules.
CLEAN = Fixture(
    name="clean_code",
    files={
        "Clean.kt": '''package demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Correct on purpose: every construct here is one the rules previously
 * flagged. The verifier fails if any of them is reported.
 */
class Clean(private val file: File) {

    private val cache = HashMap<String, Int>()
    private val linked = LinkedHashSet<String>()

    suspend fun load(): String = withContext(Dispatchers.IO) { file.readText() }

    fun pairs(items: List<String>): Map<String, Int> =
        items.associateWith { it.length }

    fun sorted(items: List<String>): List<String> =
        items.sortedWith(compareBy({ it.length }, { it }))

    @Composable
    fun Screen(onClick: () -> Unit) {
        Button(onClick = onClick) { Text("live") }
        val scale = 1f
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { Text("scaled") }
        LaunchedEffect(Unit) { load() }
    }

    fun describe(value: Int): String = when (value) {
        0 -> "none"
        in 1..9 -> "small"
        else -> "large"
    }
}
''',
    },
    expect=[],
    quiet=[
        "KT_UNRESOLVED_IDENTIFIER", "KT_MISSING_TYPE_MEMBER", "KT_MISSING_IMPORT",
        "KT_DEAD_BUTTON", "KT_EMPTY_CATCH", "KT_EMPTY_FUNCTION", "KT_MAIN_THREAD_IO",
        "KT_NON_ATOMIC_SAVE", "KT_UNBALANCED_BRACES", "KT_DUPLICATE_FUNCTION",
        "KT_FAILURE_AS_ZERO", "KT_DISABLED_CONTROL",
    ],
    notes="the false-positive corpus: correct code, no error and no warning",
)

FIXTURES = [
    MISSING_MEMBER, UNDECLARED_NAME, UNBALANCED, DEAD_BUTTONS, FALSE_GREEN,
    EMPTY_CATCH, MAIN_THREAD, NON_ATOMIC, IMPORT, DUPLICATES, EXTENSION_OVERLOAD, CLEAN,
]
