package de.meinname.scopebox

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Small "copy" icon in the gutter next to the first line of a function; one click copies the function. */
class CopyFunctionGutterRenderer(
    private val editor: Editor,
    private val highlighter: RangeHighlighter,
    private val color: Color
) : GutterIconRenderer() {

    private val icon = object : Icon {
        override fun getIconWidth() = 14
        override fun getIconHeight() = 14
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.drawRoundRect(x + 4, y + 1, 8, 9, 2, 2)      // back sheet
                g2.fillRoundRect(x + 1, y + 4, 8, 9, 2, 2)      // front sheet
            } finally {
                g2.dispose()
            }
        }
    }

    private val click = object : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            if (highlighter.isValid && !editor.isDisposed) {
                FunctionCopy.copyRange(editor, highlighter.startOffset, highlighter.endOffset)
            }
        }
    }

    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String = "Funktion kopieren"
    override fun getClickAction(): AnAction = click
    override fun equals(other: Any?): Boolean = other is CopyFunctionGutterRenderer && other.highlighter === highlighter
    override fun hashCode(): Int = System.identityHashCode(highlighter)
}
