package de.meinname.scopebox

import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

data class CodeNote(
    val file: VirtualFile,
    val line: Int,        // 0-based line index
    val column: Int,      // 0-based column offset
    val text: String,     // Extracted note text after //
    val fullLine: String, // Full text of the source line
    val prefix: String,   // "NOTIZ", "NOTE", "TODO", "FIXME" etc. or empty
    val functionName: String = "",    // Function enclosing this note (e.g. "main"), or empty
    val functionColor: Color? = null  // Exact palette color matching the function in the editor
) {
    val displayLine: Int get() = line + 1
    val fileName: String get() = file.name
}
