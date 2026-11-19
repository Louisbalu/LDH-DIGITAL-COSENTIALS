package de.meinname.scopebox

import com.intellij.openapi.editor.Editor
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JLabel
import javax.swing.PopupFactory
import javax.swing.Timer
import javax.swing.BorderFactory

object FunctionCopy {

    /** Text of [start, end), with the indentation of the first line removed from all lines (handy for methods). */
    fun extract(chars: CharSequence, start: Int, end: Int): String {
        var lineStart = start
        while (lineStart > 0 && chars[lineStart - 1] != '\n') lineStart--
        val prefix = chars.subSequence(lineStart, start).toString()
        val text = chars.subSequence(start, end).toString()
        if (prefix.isEmpty() || prefix.isNotBlank()) return text
        return text.lines().mapIndexed { i, line -> if (i == 0) line else line.removePrefix(prefix) }.joinToString("\n")
    }

    fun copyToClipboard(editor: Editor, text: String, message: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        toast(editor, message)
    }

    fun copyRange(editor: Editor, start: Int, end: Int) {
        val text = extract(editor.document.charsSequence, start, end)
        copyToClipboard(editor, text, "Funktion kopiert")
    }

    /** Small self-closing hint in the top-left corner of the visible editor area (plain Swing). */
    fun toast(editor: Editor, message: String) {
        try {
            val comp = editor.contentComponent
            if (!comp.isShowing) return
            val origin = comp.locationOnScreen
            val visible = comp.visibleRect
            val label = JLabel(message).apply {
                isOpaque = true
                background = Color(60, 63, 65)
                foreground = Color(230, 230, 230)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(100, 150, 255)),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10)
                )
            }
            val popup = PopupFactory.getSharedInstance()
                .getPopup(comp, label, origin.x + visible.x + 40, origin.y + visible.y + 20)
            popup.show()
            Timer(1200) { popup.hide() }.apply { isRepeats = false; start() }
        } catch (t: Throwable) {
            // the hint is optional
        }
    }
}
