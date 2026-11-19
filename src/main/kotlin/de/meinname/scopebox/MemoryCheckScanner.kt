package de.meinname.scopebox

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile

enum class MemoryIssueType(val title: String) {
    LEAK("Memory Leak"),
    DOUBLE_FREE("Double Free"),
    USE_AFTER_FREE("Use After Free")
}

data class MemoryFinding(
    val file: VirtualFile?,
    val line: Int,           // 0-based
    val column: Int,         // 0-based
    val variableName: String,
    val type: MemoryIssueType,
    val message: String,
    val offset: Int = 0,
    val length: Int = 0,
    val functionName: String = ""
) {
    val displayLine: Int get() = line + 1
    val fileName: String get() = file?.name ?: "source"
}

object MemoryCheckScanner {

    // Functions that typically inspect memory without taking ownership
    private val INSPECT_FUNCTIONS = setOf(
        "printf", "fprintf", "sprintf", "snprintf", "vprintf", "vfprintf", "vsprintf", "vsnprintf",
        "puts", "fputs", "putchar", "fputc", "write", "read",
        "strlen", "strcpy", "strncpy", "strcat", "strncat", "strcmp", "strncmp", "strchr", "strrchr", "strstr", "strspn", "strcspn",
        "memcpy", "memmove", "memset", "memcmp", "memchr",
        "sizeof", "assert", "exit", "abort", "log", "perror"
    )

    // Regex for allocations:
    // Branch 1: var = (cast) malloc/calloc/realloc/strdup/strndup(...)
    // Branch 2: var = (::)?new
    private val ALLOC_PATTERN = Regex(
        """(?:\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:\([^)]*\)\s*)*(malloc|calloc|realloc|strdup|strndup)\s*\()|""" +
        """(?:\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:::)?new\b)"""
    )

    private val FREE_PATTERN = Regex("""\bfree\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""")
    private val DELETE_ARRAY_PATTERN = Regex("""\bdelete\s*\[\s*\]\s*([A-Za-z_][A-Za-z0-9_]*)""")
    private val DELETE_PATTERN = Regex("""\bdelete\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val RETURN_PATTERN = Regex("""\breturn\b([^;]*);""")

    /**
     * Scans source code for potential memory management issues in C/C++.
     */
    fun scan(file: VirtualFile?, text: CharSequence): List<MemoryFinding> {
        val findings = ArrayList<MemoryFinding>()
        val sanitized = ScopeScanner.sanitize(text)
        val functions = ScopeScanner.findFunctions(sanitized)

        for (fn in functions) {
            val fnName = extractFunctionName(text, fn)
            val fnBodyStart = fn.open + 1
            val fnBodyEnd = fn.close
            if (fnBodyStart >= fnBodyEnd || fnBodyEnd > sanitized.size) continue

            val fnBody = String(sanitized, fnBodyStart, fnBodyEnd - fnBodyStart)
            scanFunction(file, text, fn, fnName, fnBody, fnBodyStart, findings)
        }

        return findings
    }

    fun scan(file: VirtualFile?, document: Document): List<MemoryFinding> {
        return scan(file, document.charsSequence)
    }

    private data class AllocationInfo(
        val variable: String,
        val allocOffset: Int,
        val isRealloc: Boolean,
        val reallocOldVar: String?
    )

    private fun scanFunction(
        file: VirtualFile?,
        originalText: CharSequence,
        fn: FunctionRange,
        fnName: String,
        body: String,
        bodyOffset: Int,
        outFindings: MutableList<MemoryFinding>
    ) {
        // 1. Locate all allocations in this function
        val allocations = ArrayList<AllocationInfo>()
        var searchPos = 0
        while (searchPos < body.length) {
            val match = ALLOC_PATTERN.find(body, searchPos) ?: break
            val varName = match.groups[1]?.value ?: match.groups[3]?.value
            val matchIndex = match.range.first
            val allocGlobalOffset = bodyOffset + matchIndex

            val isIgnored = isIgnoredAllocationTarget(body, matchIndex)

            if (!varName.isNullOrEmpty() && !isIgnored) {
                val funcWord = match.groups[2]?.value ?: "new"
                val isRealloc = funcWord == "realloc"
                var oldVar: String? = null
                if (isRealloc) {
                    val openParen = body.indexOf('(', match.range.last - 1)
                    if (openParen >= 0) {
                        val comma = body.indexOf(',', openParen)
                        if (comma > openParen) {
                            oldVar = body.substring(openParen + 1, comma).trim()
                        }
                    }
                }

                allocations.add(
                    AllocationInfo(
                        variable = varName,
                        allocOffset = allocGlobalOffset,
                        isRealloc = isRealloc,
                        reallocOldVar = oldVar
                    )
                )
            }
            searchPos = match.range.last + 1
        }

        if (allocations.isEmpty()) return

        // Group allocations by variable name
        val allocsByVar = allocations.groupBy { it.variable }

        for ((varName, varAllocs) in allocsByVar) {
            analyzeVariableLifecycle(file, originalText, fn, fnName, body, bodyOffset, varName, varAllocs, outFindings)
        }
    }

    private fun isIgnoredAllocationTarget(body: String, varStart: Int): Boolean {
        var idx = varStart - 1
        while (idx >= 0 && (body[idx] == ' ' || body[idx] == '\t')) idx--
        if (idx < 0) return false
        val c = body[idx]
        // struct member: s.var = malloc() or s->var = malloc()
        if (c == '.') return true
        if (c == '>' && idx > 0 && body[idx - 1] == '-') return true
        // array index: arr[i] = malloc()
        if (c == ']') return true
        // dereference: *dest = malloc()
        if (c == '*') {
            // Check if there is a type keyword before * (e.g. char *ptr = malloc)
            var beforeStar = idx - 1
            while (beforeStar >= 0 && (body[beforeStar] == ' ' || body[beforeStar] == '\t')) beforeStar--
            if (beforeStar < 0) return true
            val tokenEnd = beforeStar + 1
            while (beforeStar >= 0 && (body[beforeStar].isLetterOrDigit() || body[beforeStar] == '_')) beforeStar--
            val prevToken = body.substring(beforeStar + 1, tokenEnd)
            if (prevToken.isEmpty() || prevToken == "return" || prevToken == "else") {
                return true
            }
            return false
        }
        return false
    }

    private fun analyzeVariableLifecycle(
        file: VirtualFile?,
        originalText: CharSequence,
        fn: FunctionRange,
        fnName: String,
        body: String,
        bodyOffset: Int,
        varName: String,
        allocs: List<AllocationInfo>,
        outFindings: MutableList<MemoryFinding>
    ) {
        val firstAlloc = allocs.first()
        val firstAllocOffset = firstAlloc.allocOffset
        val relativeAllocOffset = firstAllocOffset - bodyOffset

        // Find all frees of this variable
        val freeOffsets = ArrayList<Int>()
        findFrees(body, varName, freeOffsets)

        // Find all returns
        val returns = ArrayList<Pair<Int, String>>() // (relativeOffset, returnExpression)
        val returnMatches = RETURN_PATTERN.findAll(body)
        for (m in returnMatches) {
            returns.add(Pair(m.range.first, m.groupValues[1].trim()))
        }

        // Check if variable escapes (returned, assigned to struct/global, passed to ownership function)
        val escapes = doesVariableEscape(body, varName, returns)

        // A) Double Free detection
        if (freeOffsets.size >= 2) {
            for (i in 0 until freeOffsets.size - 1) {
                val f1 = freeOffsets[i]
                val f2 = freeOffsets[i + 1]
                val reassigned = isReassignedBetween(body, varName, f1, f2)
                if (!reassigned) {
                    val doubleFreeOffset = bodyOffset + f2
                    val (line, col) = offsetToLineColumn(originalText, doubleFreeOffset)
                    outFindings.add(
                        MemoryFinding(
                            file = file,
                            line = line,
                            column = col,
                            variableName = varName,
                            type = MemoryIssueType.DOUBLE_FREE,
                            message = "Doppelte Freigabe (Double Free) von '$varName' ohne Neuzuweisung.",
                            offset = doubleFreeOffset,
                            length = varName.length,
                            functionName = fnName
                        )
                    )
                }
            }
        }

        // B) Use After Free detection
        for (freePos in freeOffsets) {
            val nextReassignPos = findNextReassignment(body, varName, freePos)
            val checkEnd = nextReassignPos ?: body.length
            val usePos = findUsageAfterFree(body, varName, freePos, checkEnd)
            if (usePos != null) {
                val useGlobalOffset = bodyOffset + usePos
                val (line, col) = offsetToLineColumn(originalText, useGlobalOffset)
                outFindings.add(
                    MemoryFinding(
                        file = file,
                        line = line,
                        column = col,
                        variableName = varName,
                        type = MemoryIssueType.USE_AFTER_FREE,
                        message = "Nutzung nach Freigabe (Use After Free): '$varName' wurde bereits freigegeben.",
                        offset = useGlobalOffset,
                        length = varName.length,
                        functionName = fnName
                    )
                )
            }
        }

        // C) Error path leak: return before free
        for ((retPos, retExpr) in returns) {
            if (retPos > relativeAllocOffset) {
                val returnedThisVar = isVarReturnedInExpr(varName, retExpr)
                if (!returnedThisVar) {
                    val freedBeforeReturn = freeOffsets.any { it in relativeAllocOffset until retPos }
                    val escapedBeforeReturn = doesVariableEscapeBefore(body, varName, retPos)
                    if (!freedBeforeReturn && !escapedBeforeReturn) {
                        val returnGlobalOffset = bodyOffset + retPos
                        val (line, col) = offsetToLineColumn(originalText, returnGlobalOffset)
                        outFindings.add(
                            MemoryFinding(
                                file = file,
                                line = line,
                                column = col,
                                variableName = varName,
                                type = MemoryIssueType.LEAK,
                                message = "Speicherleck auf Fehlerpfad: '$varName' wird bei 'return' nicht freigegeben.",
                                offset = returnGlobalOffset,
                                length = 6,
                                functionName = fnName
                            )
                        )
                    }
                }
            }
        }

        // D) General Leak: allocation without any free or escape in the function
        if (freeOffsets.isEmpty() && !escapes) {
            val (line, col) = offsetToLineColumn(originalText, firstAllocOffset)
            outFindings.add(
                MemoryFinding(
                    file = file,
                    line = line,
                    column = col,
                    variableName = varName,
                    type = MemoryIssueType.LEAK,
                    message = "Speicherleck: Dynamisch allokierter Speicher in '$varName' wird nicht freigegeben.",
                    offset = firstAllocOffset,
                    length = varName.length,
                    functionName = fnName
                )
            )
        }
    }

    private fun findFrees(body: String, varName: String, outFrees: MutableList<Int>) {
        FREE_PATTERN.findAll(body).forEach {
            if (it.groupValues[1] == varName) outFrees.add(it.range.first)
        }
        DELETE_ARRAY_PATTERN.findAll(body).forEach {
            if (it.groupValues[1] == varName) outFrees.add(it.range.first)
        }
        DELETE_PATTERN.findAll(body).forEach {
            if (it.groupValues[1] == varName) outFrees.add(it.range.first)
        }
    }

    private fun doesVariableEscape(body: String, varName: String, returns: List<Pair<Int, String>>): Boolean {
        // 1. Returned from function
        for ((_, retExpr) in returns) {
            if (isVarReturnedInExpr(varName, retExpr)) return true
        }

        // 2. Assigned to struct member, array or dereferenced pointer
        val structAssignRegex = Regex("""(?:\b[A-Za-z_][A-Za-z0-9_]*(?:->|\.)[A-Za-z_][A-Za-z0-9_]*|\b[A-Za-z_][A-Za-z0-9_]*\[[^\]]*\]|\*[A-Za-z_][A-Za-z0-9_]*)\s*=\s*""" + Regex.escape(varName) + """\b""")
        if (structAssignRegex.containsMatchIn(body)) return true

        // 3. Address-of &var passed
        val addressOfRegex = Regex("""&\s*""" + Regex.escape(varName) + """\b""")
        if (addressOfRegex.containsMatchIn(body)) return true

        // 4. Passed to function that takes ownership / registers pointer (e.g. add, push, set, insert, save, register)
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
        for (m in callRegex.findAll(body)) {
            val fnCalled = m.groupValues[1]
            val args = m.groupValues[2]
            if (fnCalled != "free" && !INSPECT_FUNCTIONS.contains(fnCalled)) {
                val argsList = args.split(',').map { it.trim() }
                if (argsList.any { it == varName || it.matches(Regex("""^.*\b""" + Regex.escape(varName) + """\b.*$""")) }) {
                    val lower = fnCalled.lowercase()
                    if (lower.contains("add") || lower.contains("push") || lower.contains("insert") ||
                        lower.contains("set") || lower.contains("put") || lower.contains("save") ||
                        lower.contains("store") || lower.contains("reg") || lower.contains("send") ||
                        lower.contains("attach") || lower.contains("enqueue") || lower.contains("append")
                    ) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun doesVariableEscapeBefore(body: String, varName: String, beforeOffset: Int): Boolean {
        val sub = body.substring(0, minOf(beforeOffset, body.length))
        val structAssignRegex = Regex("""(?:\b[A-Za-z_][A-Za-z0-9_]*(?:->|\.)[A-Za-z_][A-Za-z0-9_]*|\b[A-Za-z_][A-Za-z0-9_]*\[[^\]]*\]|\*[A-Za-z_][A-Za-z0-9_]*)\s*=\s*""" + Regex.escape(varName) + """\b""")
        if (structAssignRegex.containsMatchIn(sub)) return true

        val addressOfRegex = Regex("""&\s*""" + Regex.escape(varName) + """\b""")
        if (addressOfRegex.containsMatchIn(sub)) return true

        return false
    }

    private fun isVarReturnedInExpr(varName: String, expr: String): Boolean {
        if (expr.isEmpty()) return false
        val tokenRegex = Regex("""\b""" + Regex.escape(varName) + """\b""")
        return tokenRegex.containsMatchIn(expr)
    }

    private fun isTruePointerReassignment(body: String, varName: String, varPos: Int): Boolean {
        // 1. Check characters before varName
        var idx = varPos - 1
        while (idx >= 0 && (body[idx] == ' ' || body[idx] == '\t')) idx--
        if (idx >= 0) {
            val c = body[idx]
            // Dereference: *varName = ...
            if (c == '*') return false
            // Struct member access: obj.varName = ... or obj->varName = ...
            if (c == '.') return false
            if (c == '>' && idx > 0 && body[idx - 1] == '-') return false
            // Array indexing: arr[varName] = ... or arr[i] = ...
            if (c == ']' || c == '[') return false
        }

        // 2. Check characters after varName
        var afterIdx = varPos + varName.length
        while (afterIdx < body.length && (body[afterIdx] == ' ' || body[afterIdx] == '\t')) afterIdx++
        if (afterIdx < body.length) {
            // Must be '=' and NOT '=='
            if (body[afterIdx] == '=') {
                val nextChar = if (afterIdx + 1 < body.length) body[afterIdx + 1] else ' '
                if (nextChar != '=') {
                    return true
                }
            }
        }
        return false
    }

    private fun isReassignedBetween(body: String, varName: String, startOffset: Int, endOffset: Int): Boolean {
        if (startOffset >= endOffset) return false
        val varRegex = Regex("""\b""" + Regex.escape(varName) + """\b""")
        var searchIdx = startOffset
        while (searchIdx < endOffset) {
            val m = varRegex.find(body, searchIdx) ?: break
            if (m.range.first >= endOffset) break
            if (isTruePointerReassignment(body, varName, m.range.first)) {
                return true
            }
            searchIdx = m.range.last + 1
        }
        return false
    }

    private fun findNextReassignment(body: String, varName: String, afterOffset: Int): Int? {
        val varRegex = Regex("""\b""" + Regex.escape(varName) + """\b""")
        var searchIdx = afterOffset
        while (searchIdx < body.length) {
            val m = varRegex.find(body, searchIdx) ?: break
            if (isTruePointerReassignment(body, varName, m.range.first)) {
                return m.range.first
            }
            searchIdx = m.range.last + 1
        }
        return null
    }

    private fun findUsageAfterFree(body: String, varName: String, freePos: Int, endPos: Int): Int? {
        val varRegex = Regex("""\b""" + Regex.escape(varName) + """\b""")
        var searchIdx = freePos
        val endOfFree = body.indexOf(';', freePos).let { if (it >= 0) it + 1 else freePos + 10 }
        searchIdx = minOf(endOfFree, body.length)

        while (searchIdx < endPos && searchIdx < body.length) {
            val match = varRegex.find(body, searchIdx) ?: break
            val pos = match.range.first
            if (pos >= endPos) break

            // 1. Verify this is not another free(varName) or delete varName
            val before = body.substring(maxOf(0, pos - 15), pos).trimEnd()
            if (before.endsWith("free(") || before.endsWith("delete") || before.endsWith("delete[]")) {
                searchIdx = match.range.last + 1
                continue
            }

            // 2. Verify this is not a true variable reassignment (varName = ...)
            if (isTruePointerReassignment(body, varName, pos)) {
                searchIdx = match.range.last + 1
                continue
            }

            // 3. Verify this is not a struct member access like foo.varName or foo->varName
            var idx = pos - 1
            while (idx >= 0 && (body[idx] == ' ' || body[idx] == '\t')) idx--
            if (idx >= 0) {
                val c = body[idx]
                if (c == '.' || (c == '>' && idx > 0 && body[idx - 1] == '-')) {
                    searchIdx = match.range.last + 1
                    continue
                }
            }

            return pos
        }
        return null
    }

    private fun extractFunctionName(source: CharSequence, range: FunctionRange): String {
        return CodeNotesScanner.extractFunctionName(source, range)
    }

    fun offsetToLineColumn(text: CharSequence, offset: Int): Pair<Int, Int> {
        val target = offset.coerceIn(0, text.length)
        var line = 0
        var col = 0
        var i = 0
        while (i < target) {
            val c = text[i]
            if (c == '\n') {
                line++
                col = 0
            } else if (c == '\r') {
                if (i + 1 < target && text[i + 1] == '\n') {
                    i++
                }
                line++
                col = 0
            } else {
                col++
            }
            i++
        }
        return Pair(line, col)
    }
}
