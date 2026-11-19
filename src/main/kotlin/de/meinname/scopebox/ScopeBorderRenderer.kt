package de.meinname.scopebox

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.Graphics

/** Draws ONE clean rectangle around the full lines of a block (no per-token seams like EffectType.BOXED). */
class ScopeBorderRenderer : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        try {
            if (!highlighter.isValid || editor.isDisposed) return
            val doc = editor.document
            val start = highlighter.startOffset
            val end = highlighter.endOffset
            if (end <= start) return
            val first = editor.offsetToXY(doc.getLineStartOffset(doc.getLineNumber(start)))
            val last = editor.offsetToXY(end - 1)
            val top = first.y
            val bottom = last.y + editor.lineHeight
            val x0 = first.x
            val x1 = editor.contentComponent.width
            g.color = BORDER
            g.drawRect(x0, top, x1 - x0 - 1, bottom - top - 1)
        } catch (t: Throwable) {
            // never break editor painting
        }
    }

    companion object {
        private val BORDER = Color(100, 150, 255, 190)
    }
}
