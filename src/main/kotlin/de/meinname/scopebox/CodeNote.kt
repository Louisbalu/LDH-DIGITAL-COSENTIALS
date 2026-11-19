package de.meinname.scopebox

import com.intellij.openapi.vfs.VirtualFile

data class CodeNote(
    val file: VirtualFile,
    val line: Int,        // 0-based line index
    val column: Int,      // 0-based column offset
    val text: String,     // Extracted note text after //
    val fullLine: String, // Full text of the source line
    val prefix: String    // "NOTIZ", "NOTE", "TODO", "FIXME" etc. or empty
) {
    val displayLine: Int get() = line + 1
    val fileName: String get() = file.name
}
