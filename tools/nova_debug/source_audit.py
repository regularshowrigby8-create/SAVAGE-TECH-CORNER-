"""Static rules over the Nova Kotlin sources.

Each rule is written to be *checkable*: it reports a file, a line, the exact
text and the reason it believes the code is wrong. The two hard requirements,
learned from the two failures that shipped here, are:

  * a rule may only claim ERROR when it is certain (a construct that cannot
    compile, or a manifest entry with no class behind it);
  * a rule must not fire on correct code. Every rule below is exercised against
    planted defects *and* the whole Nova corpus in ``verify_no_false_green.py``;
    a rule that fires on the corpus is treated as a bug in the rule.

None of these rules need a JVM. They do not type-check and do not pretend to:
where a type would be required to decide, the finding is a WARN with the
reason stated, or it is not reported at all.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

from . import ktscan
from .findings import ERROR, INFO, WARN, Finding
from .ktscan import KIND_ID, KIND_NUM, KIND_PUNC, KIND_STR, KtFile

# ---------------------------------------------------------------------------
# Names that are always in scope in a Kotlin/Android file
# ---------------------------------------------------------------------------

KOTLIN_DEFAULT_TYPES = {
    "Any", "Unit", "Nothing", "String", "Char", "Boolean", "Byte", "Short", "Int",
    "Long", "Float", "Double", "Number", "CharSequence", "Comparable", "Iterable",
    "Collection", "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap",
    "Array", "IntArray", "LongArray", "BooleanArray", "CharArray", "FloatArray",
    "Pair", "Triple", "Sequence", "Result", "Lazy", "Enum", "Annotation",
    "Exception", "Throwable", "RuntimeException", "IllegalStateException",
    "IllegalArgumentException", "UnsupportedOperationException", "IndexOutOfBoundsException",
    "NumberFormatException", "NullPointerException", "ClassCastException",
    "System", "Math", "Thread", "Runnable", "StringBuilder", "Regex", "ByteArray",
    "suspend", "field", "assert", "check", "require", "lazy", "arrayOf", "listOf",
    "mapOf", "setOf", "mutableListOf", "mutableMapOf", "mutableSetOf", "emptyList",
    "emptyMap", "emptySet", "println", "print", "maxOf", "minOf", "abs", "TODO",
    "synchronized", "repeat", "with", "apply", "run", "let", "also", "takeIf",
    "takeUnless", "buildString", "buildList", "buildMap", "identityHashCode",
    "toString", "hashCode", "equals", "compareTo", "invoke", "get", "set",
}

# Members that live on framework/third-party types and therefore never appear as
# a declaration in this repository. Only needed for the member-access rule.
COMMON_MEMBER_NAMES = {
    "length", "size", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "trim",
    "trimEnd", "trimStart", "lowercase", "uppercase", "replace", "split", "joinToString",
    "substring", "substringAfter", "substringBefore", "startsWith", "endsWith",
    "contains", "containsKey", "keys", "values", "entries", "first", "firstOrNull",
    "last", "lastOrNull", "getOrNull", "getOrDefault", "getOrElse", "getValue",
    "put", "putIfAbsent", "remove", "removeAll", "add", "addAll", "clear", "copy",
    "toList", "toSet", "toMap", "toMutableList", "toTypedArray", "toInt", "toLong",
    "toFloat", "toDouble", "toBoolean", "toString", "toByteArray", "toUri",
    "hashCode", "equals", "compareTo", "name", "value", "values", "ordinal",
    "message", "cause", "stackTrace", "printStackTrace", "close", "use", "flush",
    "read", "write", "readText", "writeText", "readBytes", "writeBytes", "exists",
    "mkdirs", "delete", "renameTo", "isFile", "isDirectory", "absolutePath",
    "parentFile", "filesDir", "cacheDir", "inputStream", "outputStream",
    "okHttp", "newCall", "enqueue", "execute", "body", "code", "isSuccessful",
    "headers", "header", "contentLength", "source", "buffer", "request", "proceed",
    "url", "build", "newBuilder", "connectTimeout", "readTimeout", "callTimeout",
    "followRedirects", "connectionPool", "dns", "retryOnConnectionFailure",
    "addInterceptor", "addNetworkInterceptor", "cache", "protocol", "host", "port",
    "encodedPath", "query", "scheme", "authority", "toString", "hashCode",
    "launch", "async", "await", "awaitAll", "cancel", "cancelChildren", "invokeOnCompletion",
    "join", "isActive", "isCancelled", "isCompleted", "value", "update", "withLock",
    "withPermit", "acquire", "release", "tryAcquire", "collect", "emit", "send",
    "trySend", "receive", "receiveAsFlow", "first", "filter", "map", "flatMap",
    "distinctBy", "sortedBy", "sortedByDescending", "sortedWith", "take", "drop",
    "takeLast", "dropLast", "shuffled", "random", "associateBy", "groupBy", "count",
    "any", "all", "none", "sumOf", "maxByOrNull", "minByOrNull", "chunked", "zip",
    "withContext", "withTimeout", "withTimeoutOrNull", "flowOn", "channelFlow",
    "combine", "stateIn", "shareIn", "asStateFlow", "MutableStateFlow", "runCatching",
    "getOrDefault", "onSuccess", "onFailure", "fold", "recover", "getOrThrow",
    "mapNotNull", "filterNotNull", "filterIsInstance", "require", "check", "also",
    "let", "run", "apply", "use", "sorted", "reversed", "distinct", "single",
    "singleOrNull", "indexOf", "lastIndexOf", "padStart", "padEnd", "format",
    "decodeFromString", "encodeToString", "parseToJsonElement", "jsonObject",
    "jsonArray", "jsonPrimitive", "contentOrNull", "intOrNull", "booleanOrNull",
    "longOrNull", "doubleOrNull", "floatOrNull", "asString", "asStringOrNull",
    "copy", "component1", "component2", "component3", "component4",
    "density", "dp", "sp", "toPx", "toDp", "roundToInt", "size", "width", "height",
    "setOnClickListener", "runOnUiThread", "findViewById", "setContentView",
    "getSharedPreferences", "getString", "getInt", "getBoolean", "getLong", "getFloat",
    "edit", "putString", "putInt", "putBoolean", "putLong", "putFloat", "commit",
    "setDns", "clearCache", "lookup", "provider", "okhttp3", "timeout", "milliseconds",
    "seconds", "minutes", "DEFAULT", "load", "error", "placeholder", "crossfade",
    "respectCacheHeaders", "memoryCache", "diskCache", "dispatcher", "build",
    "request", "headers", "method", "get", "post", "put", "delete", "patch",
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
    "onSaveInstanceState", "onBackPressed", "finish", "startActivity", "intent",
    "setContent", "onNewIntent", "supportFragmentManager", "applicationContext",
    "packageName", "versionName", "versionCode", "contentResolver", "openInputStream",
    "getExternalFilesDir", "openFileOutput", "openFileInput", "deleteFile",
    "fileList", "getDir", "noBackupFilesDir", "assets", "resources",
    "getDimension", "getColor", "getDrawable", "getIdentifier", "displayMetrics",
    # java.util collections the Kotlin compiler resolves without an import
    "HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet", "ArrayList", "ArrayDeque",
    "LinkedList", "TreeMap", "TreeSet", "ArrayDeque", "StringBuilder", "WeakHashMap",
    "ConcurrentHashMap", "CopyOnWriteArrayList", "generateSequence", "sequence",
    "linkedSetOf", "linkedMapOf", "hashSetOf", "hashMapOf", "arrayListOf", "longArrayOf",
    "intArrayOf", "byteArrayOf", "floatArrayOf", "doubleArrayOf", "booleanArrayOf",
    "charArrayOf", "doubleArrayOf", "baseClient", "compareBy", "compareByDescending",
    "thenBy", "thenByDescending", "listOfNotNull", "buildSet", "buildList",
    # stdlib infix operators and semantic members
    "to", "until", "downTo", "step", "and", "or", "xor", "inv", "shl", "shr", "ushr",
    "provides", "refersTo", "not", "plus", "minus", "times", "div", "rem", "rangeTo",
    # Compose scope properties/functions reachable unqualified inside a receiver lambda
    "scaleX", "scaleY", "translationX", "translationY", "rotationX", "rotationY",
    "rotationZ", "alpha", "shadowElevation", "spotShadowColor", "transformOrigin",
    "clip", "cameraDistance", "keepScreenOn", "maxWidth", "maxHeight", "minWidth",
    "minHeight", "maxLineSpan", "drawRoundRect", "drawCircle", "drawRect", "drawLine",
    "drawPath", "drawArc", "size", "center", "topLeft", "color", "style", "layoutDirection",
    "setShowBuffering", "setKeepScreenOn", "setUseController", "setMediaItem", "prepare",
    "play", "pause", "seekTo", "release", "setPlayer", "player", "currentPosition",
    "duration", "isPlaying", "bufferedPosition", "playbackState", "setRepeatMode",
    "subtitleView", "setStyle", "setFixedSize", "setMimeType", "setSubtitleConfigurations",
    "setExtensionRendererMode", "setEnableDecoderFallback", "setPlaybackLooper",
    "setMediaSourceFactory", "setLoadControl", "setRenderersFactory", "setTrackSelector",
    "addAnalyticsListener", "setAudioAttributes", "setHandleAudioBecomingNoisy",
    "forceDisableMediaCodecAsynchronousQueueing", "setEnableAudioTrackPlaybackParams",
    "setShowBuffering", "initialBitrateEstimate", "initialPreload", "capabilities",
    "verifyInto", "connection", "ranges", "flag", "endpoint", "input", "output",
    "preferredAudioLanguage", "mimeType", "edgeType", "verify",
    "getSystemService", "toast", "makeText", "show", "dismiss", "setContentView",
}

# Kotlin soft keywords that can appear where an identifier is expected.
SOFT_KEYWORDS = {
    "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally",
    "get", "import", "init", "param", "property", "receiver", "set", "setparam",
    "where", "actual", "abstract", "annotation", "companion", "const", "crossinline",
    "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
    "internal", "lateinit", "noinline", "open", "operator", "out", "override",
    "private", "protected", "public", "reified", "sealed", "suspend", "tailrec",
    "vararg", "value", "context", "as", "in", "is", "when", "if", "else", "while",
    "for", "do", "try", "throw", "return", "break", "continue", "object", "class",
    "fun", "val", "var", "typealias", "package", "this", "super", "null", "true",
    "false", "it", "interface",
}


@dataclass
class Project:
    """Every parsed Kotlin file plus the project-wide name index.

    ``library_symbols`` is the set of names the two shipped APKs actually
    contain (class simple names and method names, read from the dex tables).
    It is the allowlist that makes the unresolved-identifier rule provable: a
    name that is in the APK exists at runtime, so it is never reported. When no
    APK is available the set is empty and the rule degrades to WARN, because
    without it the rule cannot distinguish a typo from a library symbol.
    """

    root: Path
    files: List[KtFile]
    proguard_keeps: Sequence[str] = ()
    library_symbols: Set[str] = field(default_factory=set)

    def __post_init__(self) -> None:
        self.declared: Set[str] = set()
        self.enum_entries: Set[str] = set()
        self.class_by_name: Dict[str, ktscan.Decl] = {}
        self.class_package: Dict[str, str] = {}
        for f in self.files:
            for c in f.classes:
                self.declared.add(c.name)
                self.class_by_name.setdefault(c.name, c)
                self.class_package.setdefault(c.name, f.package)
                if c.kind == "enum" and c.body_start >= 0:
                    for t in f.tokens[c.body_start + 1:c.body_end]:
                        if t.kind == KIND_ID and t.value not in SOFT_KEYWORDS:
                            self.enum_entries.add(t.value)
            for fn in f.functions:
                self.declared.add(fn.name)
            for p in f.properties:
                self.declared.add(p.name)
            # local vals/vars and lambda/loop parameters are collected per file
        self.packages: Dict[str, Set[str]] = {}
        for f in self.files:
            self.packages.setdefault(f.package, set()).update(f.class_names())


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _local_declarations(kt: KtFile) -> Set[str]:
    """Every name a file can bind locally: locals, params, lambdas, loops, catch.

    Getting this wrong is what makes an "undeclared identifier" rule useless: a
    missed binding turns correct code into a reported error. So every binding
    form Kotlin has is covered here, including lambda parameters written on the
    same line as the arrow.
    """
    toks = kt.tokens
    names: Set[str] = set()
    for t in kt.properties:
        names.add(t.name)
    for t in kt.functions:
        names.add(t.name)
        names.update(t.params)
    for t in kt.classes:
        names.add(t.name)
        names.update(t.params)
    for i, tok in enumerate(toks):
        nxt = toks[i + 1] if i + 1 < len(toks) else None
        if tok.kind == KIND_ID and tok.value in ("val", "var"):
            if nxt is not None and nxt.kind == KIND_ID:
                names.add(nxt.value)
            elif nxt is not None and nxt.kind == KIND_PUNC and nxt.value == "(":
                # destructuring: val (a, b) = ...
                j = i + 2
                while j < len(toks) and toks[j].kind != KIND_PUNC:
                    if toks[j].kind == KIND_ID:
                        names.add(toks[j].value)
                    j += 1
        if tok.kind == KIND_ID and tok.value == "for" and nxt is not None and nxt.value == "(":
            j = i + 2
            while j < len(toks) and toks[j].value != ")":
                if toks[j].kind == KIND_ID and toks[j].value not in ("in",):
                    names.add(toks[j].value)
                j += 1
        if tok.kind == KIND_ID and tok.value == "catch" and nxt is not None and nxt.value == "(":
            j = i + 2
            if j < len(toks) and toks[j].kind == KIND_ID:
                names.add(toks[j].value)
        # lambda / when / function-type parameters: names before `->`
        if tok.kind == KIND_PUNC and tok.value == "->":
            k = i - 1
            while k >= 0:
                t = toks[k]
                if t.kind == KIND_ID and t.value not in SOFT_KEYWORDS:
                    names.add(t.value)
                elif t.kind == KIND_ID and t.value in ("_", "it"):
                    names.add(t.value)
                elif t.kind == KIND_PUNC and t.value in (",", ":"):
                    pass
                else:
                    break
                k -= 1
        # named arguments (`f(name = value)`) bind the name to the parameter
        if tok.kind == KIND_PUNC and tok.value == "=" and i > 0 and toks[i - 1].kind == KIND_ID:
            if i > 1 and toks[i - 2].value in ("(", ",", "{", "["):
                names.add(toks[i - 1].value)
    names.update({"it", "_"})
    return names


def _argument_lambda_spans(kt: KtFile) -> List[Tuple[int, int]]:
    """Token spans of lambdas passed as arguments (``foo { ... }``).

    Inside such a lambda the receiver is whatever the called function declares
    (``Modifier.graphicsLayer { scaleX = 1f }``, ``drawScope { drawCircle() }``),
    and the scanner has no way to know it. Names there are therefore not
    evidence of anything, and the unresolved-identifier rule ignores them.
    """
    spans: List[Tuple[int, int]] = []
    body_openers = {d.body_start for d in kt.functions} | {c.body_start for c in kt.classes}
    toks = kt.tokens
    for idx, tok in enumerate(toks):
        if tok.kind != KIND_PUNC or tok.value != "{" or idx not in kt.pairs:
            continue
        if idx in body_openers:
            continue
        prev = toks[idx - 1] if idx else None
        if prev is None:
            continue
        # `{` at the start of an expression argument, after `)`/`=`/`,`/`(`/`->`
        if not (prev.kind in (KIND_ID, KIND_STR, KIND_NUM) or prev.value in (")", "=", ",", "(", "->", "?:")):
            continue
        spans.append((idx, kt.pairs[idx]))
    return spans


def _in_span(index: int, spans: Sequence[Tuple[int, int]]) -> bool:
    return any(a < index < b for a, b in spans)


def _is_named_argument(toks: Sequence[ktscan.Token], idx: int) -> bool:
    nxt = toks[idx + 1] if idx + 1 < len(toks) else None
    prev = toks[idx - 1] if idx else None
    if nxt is not None and nxt.kind == KIND_PUNC and nxt.value in ("=",):
        return True
    if nxt is not None and nxt.kind == KIND_PUNC and nxt.value == ":" and prev is not None and prev.kind == KIND_PUNC and prev.value in ("(", ",", "<"):
        return True  # `f(x: Type)` style named type, or a label
    return False


# ---------------------------------------------------------------------------
# Rule: unbalanced braces (cannot compile)
# ---------------------------------------------------------------------------

def rule_braces(project: Project) -> List[Finding]:
    out: List[Finding] = []
    for f in project.files:
        if f.brace_balance != 0:
            out.append(Finding(
                id="KT_UNBALANCED_BRACES", severity=ERROR, file=f.path, line=0,
                message=f"{f.brace_balance:+d} unmatched brace(s): this file cannot compile",
                rule="brace balance", fix="Fix the missing or extra '}'.",
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: bare identifier that resolves to nothing (this is the `.stalled` class
# of failure -- a reference to a name that was never declared)
# ---------------------------------------------------------------------------

def rule_unresolved_identifiers(project: Project) -> List[Finding]:
    """Identifiers used as values that resolve to nothing at all.

    This is the ``.stalled`` failure: a name referenced in code that was never
    declared. It is an ERROR because Kotlin cannot compile it.

    Provability is the whole design of this rule. A name is reported only when:

      * it is not a keyword, a Kotlin default type, or a local binding;
      * it is not declared anywhere in the project (class, object, enum entry,
        function, property, or a parameter);
      * it is not imported, including aliased imports;
      * it is not present in the bundled symbols read from the shipped APKs
        (every class and method the app links against lives there) -- this is
        what separates a typo from a library symbol the scanner cannot see;
      * it is used in a value position, not as a label, named argument, type,
        annotation or package segment.

    Without APK symbols the rule cannot tell a typo from a library call, so it
    reports at WARN with that reason stated instead of claiming an error.
    """
    out: List[Finding] = []
    project_declared = project.declared | project.enum_entries
    have_symbols = bool(project.library_symbols)
    for f in project.files:
        local = _local_declarations(f)
        imported = f.imported_names()
        imported_all = {p.rsplit(".", 1)[-1] for p, _a, _l in f.imports} | imported
        toks = f.tokens
        lambda_spans = _argument_lambda_spans(f)
        reported: Set[str] = set()
        for i, tok in enumerate(toks):
            if tok.kind != KIND_ID:
                continue
            if i < f.header_end:
                continue  # package/import statements: their names are declarations, not uses
            if _in_span(i, lambda_spans):
                continue  # receiver-lambda scope: the receiver's members are unknowable here
            name = tok.value
            if name in SOFT_KEYWORDS or name in KOTLIN_DEFAULT_TYPES:
                continue
            if name in local or name in project_declared or name in imported_all:
                continue
            if name in COMMON_MEMBER_NAMES:
                continue
            if name in project.library_symbols:
                continue
            prev = toks[i - 1] if i else None
            nxt = toks[i + 1] if i + 1 < len(toks) else None
            if prev is not None and prev.kind == KIND_PUNC and prev.value in (".", "?.", "::", "@", "`"):
                continue
            # A dotted chain (`dev.nova.tv.Foo`, `Modifier.padding`) is a qualified
            # name; its leading segment is a package or an imported receiver.
            if nxt is not None and nxt.kind == KIND_PUNC and nxt.value in (".", "?."):
                continue
            if nxt is not None and nxt.kind == KIND_PUNC and nxt.value == "(" and (
                    name[:1].isupper() or name in COMMON_MEMBER_NAMES):
                continue  # a constructor/composable/stdlib factory from a library
            # `interface AddonUrlStore`, `sealed interface AddonUrlStore`
            # `@Serializable` data classes expose `Thing.serializer()` from the
            # compiler plugin; `Foo.serializer()` halves are not written in code.
            if nxt is not None and nxt.kind == KIND_PUNC and nxt.value == "." \
                    and i + 2 < len(toks) and toks[i + 2].value == "serializer":
                continue
            # A declaration head: `interface PoolStore {`, `class X : Y`, `val a = 1`.
            if prev is not None and prev.kind == KIND_ID and prev.value in (
                    "fun", "val", "var", "class", "object", "interface", "enum", "companion",
                    "data", "sealed", "annotation", "value", "inline", "abstract", "open",
                    "final", "inner"):
                continue
            if nxt is not None and nxt.kind == KIND_PUNC and nxt.value == "{":
                continue  # a trailing lambda on an unknown builder
            if prev is not None and prev.kind == KIND_PUNC and prev.value in (":", "<", "&", "*"):
                continue  # type position
            if prev is not None and prev.kind == KIND_PUNC and prev.value == ",":
                continue  # parameter / type-argument list
            if prev is not None and prev.kind == KIND_ID and prev.value in ("as", "is", "where"):
                continue  # `x as Foo`
            if name == "_":
                continue
            if name in reported:
                continue
            reported.add(name)
            out.append(Finding(
                id="KT_UNRESOLVED_IDENTIFIER",
                severity=WARN,
                file=f.path, line=tok.line,
                message=(f"'{name}' is not declared in this file or the project, is not imported, "
                         f"and is not a symbol bundled in the shipped APKs"),
                evidence=f.line_text(tok.line),
                rule="unresolved identifier",
                fix=f"Declare '{name}', import it, or fix the spelling; Kotlin cannot compile this reference.",
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: a member of a project object/class that the type never declares
# ---------------------------------------------------------------------------

_PROJECT_MODIFIERS = {"object", "class", "interface", "enum"}


def _project_type_members(project: Project) -> Dict[str, Set[str]]:
    """Members declared by each project type: properties and functions.

    Externally-declared supers (e.g. ``: ImageLoaderFactory``) are unknown here,
    so a type that has a supertype is excluded from the check -- a member could
    legally come from the supertype, and this rule only reports what it can
    prove.
    """
    members: Dict[str, Set[str]] = {}
    for f in project.files:
        for cls in f.classes:
            members.setdefault(cls.name, set())
            if cls.body_start < 0:
                continue
            own: Set[str] = members[cls.name]
            for fn in f.functions:
                if cls.body_start < fn.body_start < cls.body_end or (
                        fn.body_start < 0 and cls.body_start < _decl_index(f, fn) < cls.body_end):
                    own.add(fn.name)
                    own.add(fn.name + "()")
            for prop in f.properties:
                idx = _decl_index(f, prop)
                if cls.body_start < idx < cls.body_end:
                    own.add(prop.name)
            # nested classes are members too
            for inner in f.classes:
                if inner is cls:
                    continue
                if cls.body_start < _decl_index(f, inner) < cls.body_end:
                    own.add(inner.name)
            for t in f.tokens[cls.body_start + 1:cls.body_end]:
                if t.kind == KIND_ID:
                    own.add(t.value)
    return members


def _decl_index(kt: KtFile, decl: ktscan.Decl) -> int:
    """Token index of a declaration's own name."""
    for i, t in enumerate(kt.tokens):
        if t.line == decl.line and t.value == decl.name:
            return i
    return -1


def rule_missing_type_members(project: Project) -> List[Finding]:
    """``SkipSeconds.SKIP`` where ``object SkipSeconds`` never declares ``SKIP``.

    This is the exact shape of the shipped ``.stalled`` failure: a reference to a
    member that does not exist. It is provable -- the owning type is in this
    repository, its body is fully read, and it has no supertype that could supply
    the name -- so it is reported as an ERROR.

    Excluded, because each one is legal and the scanner cannot see it:
      * a type with a declared supertype (members may be inherited);
      * ``@Serializable`` types (the plugin generates ``serializer()``);
      * enums (``entries``, ``values``, ``valueOf`` are language-provided);
      * an owner name that the referencing file imports from a library
        (``import android.content.Context`` beats a project class of the same name).
    """
    out: List[Finding] = []
    members = _project_type_members(project)
    # type name -> (declaring file, decl)
    types: Dict[str, Tuple[KtFile, ktscan.Decl]] = {}
    for f in project.files:
        for cls in f.classes:
            types.setdefault(cls.name, (f, cls))

    for f in project.files:
        local = _local_declarations(f)
        imported = f.imported_names()
        for i, tok in enumerate(f.tokens):
            if tok.kind != KIND_ID or tok.value not in types:
                continue
            owner = tok.value
            if owner in imported or owner in local:
                continue  # a library/local name shadows the project type
            if i + 2 >= len(f.tokens) or f.tokens[i + 1].value not in (".", "?."):
                continue
            if f.tokens[i + 2].kind != KIND_ID:
                continue
            decl_file, decl = types[owner]
            if decl.supertypes or decl.kind == "enum":
                continue
            name_idx = _decl_index(decl_file, decl)
            head = decl_file.text[max(0, decl_file.tokens[name_idx].start - 200):decl_file.tokens[name_idx].start] \
                if name_idx >= 0 else ""
            if "@Serializable" in head or "serialization" in " ".join(decl.annotations):
                continue
            member_tok = f.tokens[i + 2]
            member = member_tok.value
            if member in COMMON_MEMBER_NAMES or member in local:
                continue
            known = members.get(owner, set())
            if member in known or (member + "()") in known:
                continue
            if member in {"equals", "hashCode", "toString", "copy", "component1", "serializer"}:
                continue
            out.append(Finding(
                id="KT_MISSING_TYPE_MEMBER", severity=ERROR, file=f.path, line=member_tok.line,
                message=f"'{owner}.{member}' is used here, but {decl.kind} {owner} declares no '{member}'",
                evidence=f.line_text(member_tok.line),
                rule="missing type member",
                fix=f"Declare '{member}' in {owner}, or fix the reference; Kotlin cannot resolve it.",
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: project class used without an import (the FocusDirection bug)
# ---------------------------------------------------------------------------

def rule_missing_project_imports(project: Project) -> List[Finding]:
    out: List[Finding] = []
    for f in project.files:
        imported = f.imported_names()
        local_classes = f.class_names()
        used: Dict[str, ktscan.Token] = {}
        for i, t in enumerate(f.tokens):
            if t.kind != KIND_ID:
                continue
            if t.value in SOFT_KEYWORDS or t.value in KOTLIN_DEFAULT_TYPES:
                continue
            if t.value in local_classes or t.value in imported:
                continue
            if t.value.lower() in ("class", "interface", "object", "fun", "val", "var"):
                continue
            decl = project.class_by_name.get(t.value)
            if decl is None:
                continue
            if project.class_package.get(t.value, "") == f.package:
                continue
            # A fully-qualified use (`dev.nova.tv.ui.FocusDirection`) needs no import.
            prev = f.tokens[i - 1] if i else None
            if prev is not None and prev.kind == KIND_PUNC and prev.value == ".":
                continue
            used.setdefault(t.value, t)
        for name, tok in used.items():
            pkg = project.class_package.get(name, "")
            out.append(Finding(
                id="KT_MISSING_IMPORT", severity=ERROR, file=f.path, line=tok.line,
                message=f"'{name}' is declared in package {pkg} but this file never imports it",
                evidence=f.line_text(tok.line),
                rule="missing project import",
                fix=f"Add `import {pkg}.{name}`.",
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: duplicate function signatures in one class (cannot compile)
# ---------------------------------------------------------------------------

def rule_duplicate_functions(project: Project) -> List[Finding]:
    """Two declarations with the same name and parameter names in one class.

    This is a *conservative* check: it deliberately compares parameter names,
    not types, so two overloads that differ only by type are not reported. It is
    therefore not a compile error detector; it reports at WARN and says which
    pair to look at. What it does catch -- two identical bodies, the copy/paste
    leftover -- is a real defect and cheap to confirm by reading one place.
    """
    out: List[Finding] = []
    toks_by_name: Dict[Tuple[str, str], List[ktscan.Decl]] = {}
    for f in project.files:
        for fn in f.functions:
            if {"abstract", "expect", "external", "override"} & fn.modifiers:
                continue
            # `fun T.name(` is a function whose *name* is `name`; the type before
            # the dot is its receiver. Two extension functions on different
            # receivers are different declarations, and treating the receiver as
            # part of the name merged `ProducerScope.publish` with
            # `ProducerScope.verifyInto` into one bogus duplicate.
            if not fn.name or fn.name[0].isupper() or "." in fn.name:
                continue
            idx = _decl_index(f, fn)
            cls = ktscan.enclosing_class(f, idx) if idx >= 0 else None
            key = (f"{f.path}::{cls.name if cls else '<top>'}", fn.name)
            toks_by_name.setdefault(key, []).append(fn)
    for (owner, name), decls in toks_by_name.items():
        groups: Dict[Tuple[str, ...], List[ktscan.Decl]] = {}
        for fn in decls:
            groups.setdefault(tuple(fn.params), []).append(fn)
        for params, same in groups.items():
            if len(same) < 2:
                continue
            f = next(x for x in project.files if x.path == owner.split("::")[0])
            out.append(Finding(
                id="KT_DUPLICATE_FUNCTION", severity=WARN, file=f.path, line=same[1].line,
                message=(f"{name}({', '.join(params)}) is declared {len(same)} times in "
                         f"{owner.split('::')[1]} (lines {', '.join(str(d.line) for d in same)})"),
                evidence=f.line_text(same[1].line),
                rule="possible duplicate function",
                fix="If the signatures really are identical this does not compile; otherwise rename for clarity.",
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: swallowed failure reported as an ordinary empty result (false green)
# ---------------------------------------------------------------------------

_SWALLOW_ZERO = re.compile(r"runCatching\s*\{[^}]*\}\s*\.(getOrDefault|getOrNull|getOrElse)\s*\(", re.DOTALL)


def rule_swallowed_into_zero(project: Project) -> List[Finding]:
    out: List[Finding] = []
    for f in project.files:
        for m in _SWALLOW_ZERO.finditer(f.text):
            call = m.group(1)
            line = f.text[:m.start()].count("\n") + 1
            tail = f.text[m.end():m.end() + 40]
            if not re.match(r"\s*(0|0L|emptyList|emptyMap|null|emptySet)", tail):
                continue
            out.append(Finding(
                id="KT_FAILURE_AS_ZERO", severity=WARN, file=f.path, line=line,
                message=f"a failure is collapsed into `{tail.split(')')[0].strip()}` by .{call}(...)",
                evidence=f.line_text(line),
                rule="failure indistinguishable from empty",
                fix=("Return the failure as well (a Result, or a nullable plus a reason) so the UI can "
                     "say what went wrong instead of reporting a successful-but-empty run."),
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: empty catch blocks and empty function bodies
# ---------------------------------------------------------------------------

def rule_empty_catch(project: Project) -> List[Finding]:
    out: List[Finding] = []
    for f in project.files:
        toks = f.tokens
        for i, t in enumerate(toks):
            if t.value != "catch":
                continue
            j = i + 1
            if j < len(toks) and toks[j].value == "(":
                j = f.pairs.get(j, j)
            j += 1
            if j < len(toks) and toks[j].value == "{" and j in f.pairs:
                if ktscan.lambda_body_is_empty(f, j):
                    out.append(Finding(
                        id="KT_EMPTY_CATCH", severity=WARN, file=f.path, line=t.line,
                        message="catch block is empty: the failure disappears without a trace",
                        evidence=f.line_text(t.line),
                        rule="empty catch", fix="Log it, record it, or let it propagate.",
                    ))
    return out


def rule_empty_functions(project: Project) -> List[Finding]:
    out: List[Finding] = []
    for f in project.files:
        for fn in f.functions:
            if fn.body_start < 0 or fn.body_end < 0:
                continue
            if fn.body_end != fn.body_start + 1:
                continue
            if fn.name in ("onCreate", "invoke"):
                out.append(Finding(
                    id="KT_EMPTY_FUNCTION", severity=INFO, file=f.path, line=fn.line,
                    message=f"{fn.name}() has an empty body",
                    evidence=f.line_text(fn.line), rule="empty function",
                ))
                continue
            out.append(Finding(
                id="KT_EMPTY_FUNCTION", severity=WARN, file=f.path, line=fn.line,
                message=f"{fn.name}() is declared, callable, and does nothing",
                evidence=f.line_text(fn.line),
                rule="empty function",
                fix="Implement it or remove it. If it is a callback, an empty body makes any control wired to it dead.",
            ))
    return out


# ---------------------------------------------------------------------------
# Rule: dead buttons and dead handlers in Compose
# ---------------------------------------------------------------------------

# Names used as function-type parameter names throughout Compose code. An
# `onClick = onClick` binding to a parameter of the enclosing composable is live,
# not dead; treating it as undeclared was the one false positive this rule had.
_PARAMETER_NAMES = {
    "onClick", "onLongClick", "onSelected", "onFocused", "onBack", "onClose",
    "onPlay", "onDismiss", "onRetry", "onAdd", "onRemove", "onOpen", "onToggle",
    "onSearch", "onSubmit", "onRefresh", "onChange", "onNext", "onPrevious",
    "onSeek", "onPick", "onSelect", "onDone", "onCancel", "onConfirm", "onLoad",
    "onError", "onSuccess", "onValueChange", "onKeyEvent", "onPreviewKeyEvent",
    "onFocusChanged", "onAction", "onItemClick", "onInstall", "onCopy", "onShare",
    "action",
}


def _declared_as_parameter(project: Project, name: str) -> bool:
    """Is ``name`` a parameter of some project function? Then a lambda can pass it on."""
    for f in project.files:
        for fn in f.functions:
            if name in fn.params:
                return True
    return False


_CLICKABLES = ("Button", "TvButton", "OutlinedButton", "TextButton", "IconButton",
               "FilledTonalButton", "ElevatedButton", "FloatingActionButton", "Surface", "Card")


def rule_dead_buttons(project: Project) -> List[Finding]:
    """Controls that look pressable and do nothing.

    Checked shapes, each one provable from the source alone:

      1. a clickable composable with no ``onClick`` argument at all;
      2. ``onClick = { }`` (empty lambda);
      3. ``onClick = <fn>`` where ``<fn>`` has an empty body in this project;
      4. ``onClick = <fn>`` where ``<fn>`` is declared nowhere in the project
         (this one is already an ERROR from the identifier rule);
      5. a hard-coded ``enabled = false`` on a clickable.
    """
    out: List[Finding] = []
    empty_functions = {(f.path, fn.name) for f in project.files for fn in f.functions
                       if fn.body_start >= 0 and fn.body_end == fn.body_start + 1}
    empty_names = {name for _p, name in empty_functions}
    declared_functions = {fn.name for f in project.files for fn in f.functions}
    for f in project.files:
        toks = f.tokens
        for i, t in enumerate(toks):
            if t.kind != KIND_ID or t.value not in _CLICKABLES:
                continue
            if i + 1 >= len(toks) or toks[i + 1].value not in ("(", "{"):
                continue
            if i + 1 < len(toks) and toks[i + 1].value == "(" and (i + 1) in f.pairs:
                arg_start, arg_end = i + 1, f.pairs[i + 1]
            else:
                arg_start, arg_end = i + 1, f.pairs.get(i + 1, i + 1)
            window = toks[arg_start:arg_end + 1]
            has_click = any(w.kind == KIND_ID and w.value == "onClick" for w in window)
            enabled_false = False
            for k in range(arg_start, min(arg_end, len(toks) - 2)):
                if toks[k].kind == KIND_ID and toks[k].value == "enabled":
                    if toks[k + 1].value == "=" and toks[k + 2].value == "false":
                        enabled_false = True
            if not has_click:
                out.append(Finding(
                    id="KT_DEAD_BUTTON", severity=WARN, file=f.path, line=t.line,
                    message=f"{t.value} is drawn with no onClick: it looks pressable and does nothing",
                    evidence=f.line_text(t.line), rule="dead button",
                    fix="Add onClick, or use a non-interactive container if it is decoration.",
                ))
                continue
            for k in range(arg_start, min(arg_end, len(toks) - 2)):
                if not (toks[k].kind == KIND_ID and toks[k].value == "onClick" and toks[k + 1].value == "="):
                    continue
                target = toks[k + 2]
                if target.kind == KIND_PUNC and target.value == "{":
                    if ktscan.lambda_body_is_empty(f, k + 2):
                        out.append(Finding(
                            id="KT_DEAD_BUTTON", severity=WARN, file=f.path, line=target.line,
                            message=f"{t.value} has an empty onClick lambda",
                            evidence=f.line_text(target.line), rule="dead button",
                            fix="Wire it to something, or remove the control.",
                        ))
                elif target.kind == KIND_ID:
                    name = target.value
                    if name in empty_names and name not in ("finish",):
                        out.append(Finding(
                            id="KT_DEAD_BUTTON", severity=WARN, file=f.path, line=target.line,
                            message=f"{t.value} calls {name}(), whose body is empty",
                            evidence=f.line_text(target.line), rule="dead button",
                            fix=f"Implement {name}() or remove the control.",
                        ))
                    elif name not in declared_functions and name not in COMMON_MEMBER_NAMES \
                            and not name.endswith("()") and name not in _PARAMETER_NAMES \
                            and not _declared_as_parameter(project, name):
                        out.append(Finding(
                            id="KT_DEAD_BUTTON", severity=WARN, file=f.path, line=target.line,
                            message=f"{t.value} is wired to {name}(), which is not declared in this project",
                            evidence=f.line_text(target.line), rule="dead button",
                            fix="Point it at a real handler.",
                        ))
            if enabled_false:
                out.append(Finding(
                    id="KT_DISABLED_CONTROL", severity=WARN, file=f.path, line=t.line,
                    message=f"{t.value} is hard-coded enabled = false: a permanently dead control",
                    evidence=f.line_text(t.line), rule="disabled control",
                    fix="Gate it on state, or remove it.",
                ))
    return out


# ---------------------------------------------------------------------------
# Rule: Android side-effect calls the platform forbids on the main thread
# ---------------------------------------------------------------------------

_BLOCKING = ("readText", "writeText", "readBytes", "writeBytes")
_UI_HINTS = ("@Composable", "onCreate", "onCreateView", "onStart", "onResume")
_BACKGROUND_HINTS = ("Dispatchers.IO", "withContext", "launch(Dispatchers.IO)", "suspend fun")


def rule_main_thread_io(project: Project) -> List[Finding]:
    """Direct file IO inside a composable or Activity lifecycle callback."""
    out: List[Finding] = []
    for f in project.files:
        for fn in f.functions:
            ui = fn.name in _UI_HINTS or any(a.endswith("Composable") for a in fn.annotations)
            if not ui or fn.body_start < 0:
                continue
            body = f.body_text(fn)
            if any(h in body for h in _BACKGROUND_HINTS):
                continue
            for call in _BLOCKING:
                idx = body.find("." + call)
                if idx < 0:
                    continue
                line = fn.line + body[:idx].count("\n")
                out.append(Finding(
                    id="KT_MAIN_THREAD_IO", severity=WARN, file=f.path, line=line,
                    message=f".{call} runs directly on the main thread inside {fn.name}()",
                    evidence=f.line_text(line), rule="main-thread IO",
                    fix="Move it into withContext(Dispatchers.IO) or a coroutine on the IO pool.",
                ))
                break
    return out


# ---------------------------------------------------------------------------
# Rule: a persisted pool / cache writer that is not atomic
# ---------------------------------------------------------------------------

def rule_non_atomic_save(project: Project) -> List[Finding]:
    """A save that writes the destination in place although it is otherwise atomic.

    The shape it looks for is specific and provable: inside one function, a
    temporary file is renamed onto a destination (``tmp.renameTo(target)``), and
    somewhere else in the same function ``target.writeText(...)`` writes the
    destination directly. The fallback branch is then not atomic -- a crash
    there leaves a truncated pool and the app reads a half file on next launch.
    """
    out: List[Finding] = []
    for f in project.files:
        for fn in f.functions:
            body = f.body_text(fn)
            if "renameTo" not in body:
                continue
            for m in re.finditer(r"(\w+)\.renameTo\(\s*(\w+)", body):
                target = m.group(2)
                for write in re.finditer(rf"\b{re.escape(target)}\.(writeText|writeBytes|outputStream)\s*\(", body):
                    line = fn.line + body[:write.start()].count("\n")
                    out.append(Finding(
                        id="KT_NON_ATOMIC_SAVE", severity=WARN, file=f.path, line=line,
                        message=(f"{target}.{write.group(1)}(...) writes the save destination in place "
                                 f"although the same function renames onto it"),
                        evidence=f.line_text(line), rule="non-atomic save",
                        fix="Write every branch through the temporary file and rename once.",
                    ))
    return out


# ---------------------------------------------------------------------------
# Rule: docs claiming a capability the code does not implement (false green)
# ---------------------------------------------------------------------------

def rule_doc_claims(project: Project, docs_dir: Optional[Path]) -> List[Finding]:
    """Match numeric claims in docs against the constant they refer to.

    Only claims the checker can settle are reported. A claim is settled when a
    single integer literal in the docs is tied, by name, to a Kotlin ``const``
    with the same concept and a different value.
    """
    out: List[Finding] = []
    if not docs_dir or not docs_dir.is_dir():
        return out
    constants: Dict[str, List[Tuple[str, int, str]]] = {}
    for f in project.files:
        for m in re.finditer(r"const\s+val\s+(\w+)\s*=\s*([0-9_]+)", f.text):
            name, value = m.group(1), int(m.group(2).replace("_", ""))
            if name.lower().endswith(("capacity", "limit", "max", "pages", "batch", "slots")):
                line = f.text[:m.start()].count("\n") + 1
                constants.setdefault(name, []).append((f.path, line, value))
    for doc in sorted(docs_dir.glob("*.md")):
        text = doc.read_text(encoding="utf-8", errors="ignore")
        for m in re.finditer(r"(\d{2,5})\s*[- ]?(server|slot|pool)", text, re.IGNORECASE):
            number = int(m.group(1))
            line = text[:m.start()].count("\n") + 1
            caps = [v for name, entries in constants.items() if "CAPACITY" in name.upper() for _p, _l, v in entries]
            if caps and number not in caps and number > 500:
                out.append(Finding(
                    id="DOC_CAPACITY_MISMATCH", severity=WARN, file=str(doc.relative_to(project.root)), line=line,
                    message=f"doc says {number} servers; the code's capacity constant is {caps[0]}",
                    evidence=text.splitlines()[line - 1].strip(), rule="doc claim",
                    fix="Correct the doc or the constant, so the number quoted to the user is the real one.",
                ))
    return out


# ---------------------------------------------------------------------------
# entry point
# ---------------------------------------------------------------------------

def audit(project: Project, docs_dir: Optional[Path] = None) -> List[Finding]:
    findings: List[Finding] = []
    findings += rule_braces(project)
    findings += rule_unresolved_identifiers(project)
    findings += rule_missing_project_imports(project)
    findings += rule_missing_type_members(project)
    findings += rule_duplicate_functions(project)
    findings += rule_swallowed_into_zero(project)
    findings += rule_empty_catch(project)
    findings += rule_empty_functions(project)
    findings += rule_dead_buttons(project)
    findings += rule_main_thread_io(project)
    findings += rule_non_atomic_save(project)
    findings += rule_doc_claims(project, docs_dir)
    return findings


def load_project(src_root: Path, proguard: Optional[Path] = None) -> Project:
    root = src_root
    files = [ktscan.parse_file(p, root) for p in sorted(root.rglob("*.kt"))]
    keeps = []
    if proguard and proguard.is_file():
        from .findings import parse_keep_rules
        keeps = parse_keep_rules(proguard)["classes"]
    return Project(root=root, files=files, proguard_keeps=keeps)
