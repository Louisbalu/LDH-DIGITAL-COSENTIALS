package de.meinname.scopebox

/** A function-like block: [start] is the first char of the signature, [open]/[close] are the braces. */
data class FunctionRange(val start: Int, val open: Int, val close: Int)

/** Pure text logic (no IDE dependencies) for finding C/C++ function bodies and enclosing braces. */
object ScopeScanner {

    /** Replaces comments, string/char literals and preprocessor lines with spaces (offsets stay identical). */
    fun sanitize(src: CharSequence): CharArray {
        val n = src.length
        val out = CharArray(n) { src[it] }
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, n)) if (out[k] != '\n' && out[k] != '\r') out[k] = ' '
        }
        var i = 0
        var lineStart = true
        while (i < n) {
            val c = src[i]
            when {
                c == '\n' -> { lineStart = true; i++ }
                c == ' ' || c == '\t' || c == '\r' -> i++
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    var j = i
                    while (j < n && src[j] != '\n') j++
                    blank(i, j); i = j
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    var j = i + 2
                    while (j + 1 < n && !(src[j] == '*' && src[j + 1] == '/')) j++
                    j = minOf(n, j + 2)
                    blank(i, j); i = j; lineStart = false
                }
                c == '#' && lineStart -> {
                    var j = i
                    while (j < n && src[j] != '\n') {
                        if (src[j] == '\\' && j + 1 < n && (src[j + 1] == '\n' || src[j + 1] == '\r')) {
                            j += 2
                            if (j < n && src[j - 1] == '\r' && src[j] == '\n') j++
                        } else j++
                    }
                    blank(i, j); i = j
                }
                c == '"' -> {
                    // raw string literal R"delim( ... )delim"
                    if (i > 0 && src[i - 1] == 'R') {
                        var d = i + 1
                        while (d < n && src[d] != '(' && src[d] != '\n') d++
                        if (d < n && src[d] == '(') {
                            val end = ")" + src.subSequence(i + 1, d) + "\""
                            val idx = src.toString().indexOf(end, d)
                            val j = if (idx < 0) n else idx + end.length
                            blank(i, j); i = j; lineStart = false
                            continue
                        }
                    }
                    var j = i + 1
                    while (j < n && src[j] != '"' && src[j] != '\n') { if (src[j] == '\\') j++; j++ }
                    j = minOf(n, j + 1)
                    blank(i, j); i = j; lineStart = false
                }
                c == '\'' -> {
                    val prev = if (i > 0) src[i - 1] else ' '
                    if (prev.isLetterOrDigit() || prev == '_') { i++; lineStart = false } // digit separator / prefix
                    else {
                        var j = i + 1
                        while (j < n && src[j] != '\'' && src[j] != '\n') { if (src[j] == '\\') j++; j++ }
                        j = minOf(n, j + 1)
                        blank(i, j); i = j; lineStart = false
                    }
                }
                else -> { lineStart = false; i++ }
            }
        }
        return out
    }

    private val CONTAINER = Regex("^(template\\s*<[^;{}]*>\\s*)?((typedef|inline|public|private|protected|static|final|abstract|open|sealed|data|export)\\s+)*(namespace|class|struct|union|enum|extern|interface|object)\\b")
    private val ACCESS = Regex("^(public|private|protected)\\s*:\\s*")
    private val TAIL = Regex("^(\\s|const|volatile|noexcept|override|final|mutable|&|->[^;]*)*$")
    private val CONTROL = setOf("if", "for", "while", "switch", "catch", "else", "do", "try")

    /** Returns [start, ...] of the head text before `{` at [pos] if it looks like a function, else null. */
    private fun functionStart(t: CharArray, pos: Int): Int? {
        var s = pos
        var parens = 0
        while (s > 0) {
            val ch = t[s - 1]
            if (ch == ')') parens++ else if (ch == '(') parens--
            if (parens <= 0 && (ch == ';' || ch == '{' || ch == '}')) break
            s--
            if (pos - s > 4000) return null
        }
        var head = String(t, s, pos - s)
        var lead = head.length - head.trimStart().length
        var trimmed = head.trim()
        while (true) {
            val m = ACCESS.find(trimmed) ?: break
            lead += trimmed.length - trimmed.removePrefix(m.value).length
            trimmed = trimmed.substring(m.value.length)
        }
        if (trimmed.isEmpty()) return null
        if (CONTAINER.containsMatchIn(trimmed)) return null
        val lastParen = trimmed.lastIndexOf(')')
        if (lastParen < 0) return null
        if (!TAIL.matches(trimmed.substring(lastParen + 1))) return null
        // identifier before the matching '('
        var depth = 0
        var k = lastParen
        while (k >= 0) {
            if (trimmed[k] == ')') depth++ else if (trimmed[k] == '(') { depth--; if (depth == 0) break }
            k--
        }
        if (k < 0) return null
        var e = k
        while (e > 0 && trimmed[e - 1].isWhitespace()) e--
        var b = e
        while (b > 0 && (trimmed[b - 1].isLetterOrDigit() || trimmed[b - 1] == '_')) b--
        if (trimmed.substring(b, e) in CONTROL) return null
        return s + lead
    }

    /** Finds outermost function bodies (nested blocks / lambdas inside a function are not reported). */
    fun findFunctions(t: CharArray): List<FunctionRange> {
        val result = ArrayList<FunctionRange>()
        val stack = ArrayList<IntArray>() // [openPos, startOrMinus1]
        var fnDepth = 0
        for (i in t.indices) {
            when (t[i]) {
                '{' -> {
                    var start: Int? = null
                    if (fnDepth == 0) start = functionStart(t, i)
                    stack.add(intArrayOf(i, start ?: -1))
                    if (start != null) fnDepth++
                }
                '}' -> {
                    if (stack.isEmpty()) continue
                    val f = stack.removeAt(stack.size - 1)
                    if (f[1] >= 0) { fnDepth--; result.add(FunctionRange(f[1], f[0], i)) }
                }
            }
        }
        return result
    }

    /** Offsets of the '{' and matching '}' enclosing [offset] (innermost), or null. */
    fun findEnclosing(t: CharArray, offset: Int): IntRange? {
        if (t.isEmpty()) return null
        val caret = offset.coerceIn(0, t.size)
        var depth = 0
        var open = -1
        var i = caret - 1
        while (i >= 0) {
            when (t[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) { open = i; break } else depth--
            }
            i--
        }
        if (open < 0) return null
        depth = 0
        var j = open + 1
        while (j < t.size) {
            when (t[j]) {
                '{' -> depth++
                '}' -> if (depth == 0) return open..j else depth--
            }
            j++
        }
        return null
    }
}
