package de.meinname.scopebox

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile

object CodeNotesScanner {

    private val PREFIX_REGEX = Regex(
        """^(notiz(?:en)?|notes?|todo|fixme|achtung|wichtig|hinweis|warnung|warning|bug|info)\s*[:\-]?\s*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Scans the given text for single-line `//` comments in C/C++ source code.
     * Accurately avoids `//` appearing inside strings `"..."`, chars `'...'`, or block comments.
     */
    fun scan(file: VirtualFile, text: CharSequence): List<CodeNote> {
        val result = ArrayList<CodeNote>()
        var inBlockComment = false
        var lineIndex = 0
        var lineStart = 0
        val totalLen = text.length

        while (lineStart < totalLen) {
            var lineEnd = lineStart
            while (lineEnd < totalLen && text[lineEnd] != '\n' && text[lineEnd] != '\r') {
                lineEnd++
            }
            val lineText = text.subSequence(lineStart, lineEnd).toString()
            val len = lineText.length

            var inString = false
            var inChar = false
            var i = 0

            while (i < len) {
                val c = lineText[i]

                if (inBlockComment) {
                    if (c == '*' && i + 1 < len && lineText[i + 1] == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }

                if (inString) {
                    if (c == '\\' && i + 1 < len) {
                        i += 2
                        continue
                    }
                    if (c == '"') {
                        inString = false
                    }
                    i++
                    continue
                }

                if (inChar) {
                    if (c == '\\' && i + 1 < len) {
                        i += 2
                        continue
                    }
                    if (c == '\'') {
                        inChar = false
                    }
                    i++
                    continue
                }

                // Block comment start /*
                if (c == '/' && i + 1 < len && lineText[i + 1] == '*') {
                    val closeIdx = lineText.indexOf("*/", i + 2)
                    if (closeIdx >= 0) {
                        val commentContent = lineText.substring(i + 2, closeIdx).trim()
                        if (isValidNoteText(commentContent)) {
                            val (prefix, cleanText) = extractPrefixAndText(commentContent)
                            result.add(
                                CodeNote(
                                    file = file,
                                    line = lineIndex,
                                    column = i,
                                    text = cleanText,
                                    fullLine = lineText,
                                    prefix = prefix
                                )
                            )
                        }
                        i = closeIdx + 2
                        continue
                    } else {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                }

                // Single line comment //
                if (c == '/' && i + 1 < len && lineText[i + 1] == '/') {
                    val rawComment = lineText.substring(i + 2).trim()
                    if (isValidNoteText(rawComment)) {
                        val (prefix, cleanText) = extractPrefixAndText(rawComment)
                        result.add(
                            CodeNote(
                                file = file,
                                line = lineIndex,
                                column = i,
                                text = cleanText,
                                fullLine = lineText,
                                prefix = prefix
                            )
                        )
                    }
                    break // The remainder of the line is a comment
                }

                // C++ raw string literal R"delim( ... )delim"
                if (c == '"' && i > 0 && lineText[i - 1] == 'R') {
                    var d = i + 1
                    while (d < len && lineText[d] != '(') d++
                    if (d < len && lineText[d] == '(') {
                        val delim = lineText.substring(i + 1, d)
                        val endToken = ")$delim\""
                        val endPos = lineText.indexOf(endToken, d)
                        if (endPos >= 0) {
                            i = endPos + endToken.length
                            continue
                        }
                    }
                }

                if (c == '"') {
                    inString = true
                    i++
                    continue
                }

                if (c == '\'') {
                    val prev = if (i > 0) lineText[i - 1] else ' '
                    if (!prev.isLetterOrDigit() && prev != '_') {
                        inChar = true
                    }
                    i++
                    continue
                }

                i++
            }

            // Advance line
            if (lineEnd < totalLen && text[lineEnd] == '\r') lineEnd++
            if (lineEnd < totalLen && text[lineEnd] == '\n') lineEnd++
            lineStart = lineEnd
            lineIndex++
        }

        return result
    }

    fun scan(file: VirtualFile, document: Document): List<CodeNote> {
        return scan(file, document.charsSequence)
    }

    fun isValidNoteText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // Exclude dividers like // -------- or // ======== or // /////
        if (trimmed.all { it in "-=*#_~/." || it.isWhitespace() }) {
            return false
        }
        // Must contain at least one letter or digit
        return trimmed.any { it.isLetterOrDigit() }
    }

    fun extractPrefixAndText(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val match = PREFIX_REGEX.find(trimmed)
        if (match != null) {
            val prefix = match.groupValues[1].uppercase()
            val rest = trimmed.substring(match.range.last + 1).trim()
            val clean = if (rest.isNotEmpty()) rest else trimmed
            return Pair(prefix, clean)
        }
        return Pair("", trimmed)
    }
}
