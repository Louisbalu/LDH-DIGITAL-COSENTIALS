package de.meinname.scopebox

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.SwingUtilities

private class EditorState(
    val disposable: Disposable,
    val caretListener: CaretListener
) {
    val functionHighlighters = ArrayList<RangeHighlighter>()
    val scopeHighlighters = ArrayList<RangeHighlighter>()
    var pending = false
    var stamp = -1L
    var sanitized: CharArray = CharArray(0)
}

class ScopeEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val disposable = Disposer.newDisposable()
        val caretListener = object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent) = guarded { updateScope(editor) }
        }
        val state = EditorState(disposable, caretListener)
        editor.putUserData(STATE_KEY, state)
        editor.caretModel.addCaretListener(caretListener)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(e: DocumentEvent) = scheduleRefresh(editor)
        }, disposable)
        scheduleRefresh(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val state = editor.getUserData(STATE_KEY) ?: return
        guarded {
            clearFunctions(editor, state)
            clearScope(editor, state)
            editor.caretModel.removeCaretListener(state.caretListener)
        }
        Disposer.dispose(state.disposable)
        editor.putUserData(STATE_KEY, null)
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            LOG.warn("ScopeBox: error", t)
        }
    }

    private fun scheduleRefresh(editor: Editor) {
        val state = editor.getUserData(STATE_KEY) ?: return
        if (state.pending) return
        state.pending = true
        SwingUtilities.invokeLater {
            state.pending = false
            if (!editor.isDisposed) guarded { refreshAll(editor, state) }
        }
    }

    private fun sanitized(editor: Editor, state: EditorState): CharArray {
        val stamp = editor.document.modificationStamp
        if (stamp != state.stamp) {
            val text = editor.document.charsSequence
            state.sanitized = if (text.length > MAX_CHARS) CharArray(0) else ScopeScanner.sanitize(text)
            state.stamp = stamp
        }
        return state.sanitized
    }

    private fun clearFunctions(editor: Editor, state: EditorState) {
        for (h in state.functionHighlighters) if (h.isValid) editor.markupModel.removeHighlighter(h)
        state.functionHighlighters.clear()
    }

    private fun clearScope(editor: Editor, state: EditorState) {
        for (h in state.scopeHighlighters) if (h.isValid) editor.markupModel.removeHighlighter(h)
        state.scopeHighlighters.clear()
    }

    /** Always-on highlighting of every function, each in a rotating soft colour. */
    private fun refreshAll(editor: Editor, state: EditorState) {
        clearFunctions(editor, state)
        val text = sanitized(editor, state)
        val functions = ScopeScanner.findFunctions(text)
        LOG.info("ScopeBox: " + functions.size + " functions found")
        val bg = editor.colorsScheme.defaultBackground
        val dark = (bg.red * 299 + bg.green * 587 + bg.blue * 114) / 1000 < 128
        val ratio = if (dark) 0.20 else 0.15
        functions.forEachIndexed { index, f ->
            val (r, g, b) = PALETTE[index % PALETTE.size]
            // Opaque colour (blended with the editor background): transparent fills leave seams between tokens.
            val attrs = TextAttributes().apply {
                backgroundColor = Color(
                    (bg.red * (1 - ratio) + r * ratio).toInt(),
                    (bg.green * (1 - ratio) + g * ratio).toInt(),
                    (bg.blue * (1 - ratio) + b * ratio).toInt()
                )
            }
            val h = editor.markupModel.addRangeHighlighter(
                f.start, f.close + 1, HighlighterLayer.SELECTION - 2, attrs, HighlighterTargetArea.LINES_IN_RANGE
            )
            h.setGutterIconRenderer(CopyFunctionGutterRenderer(editor, h, Color(r, g, b)))
            state.functionHighlighters.add(h)
        }
        updateScope(editor)
    }

    /** Boxed highlight of the innermost { } block around the caret. */
    private fun updateScope(editor: Editor) {
        val state = editor.getUserData(STATE_KEY) ?: return
        clearScope(editor, state)
        if (editor.isDisposed) return
        val range = ScopeScanner.findEnclosing(sanitized(editor, state), editor.caretModel.offset) ?: return
        // One rectangle around the lines of the block under the caret.
        val h = editor.markupModel.addRangeHighlighter(
            range.first, range.last + 1, HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE
        )
        h.setCustomRenderer(ScopeBorderRenderer())
        state.scopeHighlighters.add(h)
    }

    companion object {
        private val LOG = Logger.getInstance(ScopeEditorListener::class.java)
        private val STATE_KEY = Key.create<EditorState>("scopebox.state")
        const val MAX_CHARS = 1_500_000
        val PALETTE = listOf(
            Triple(100, 150, 255), // blue
            Triple(80, 200, 120),  // green
            Triple(255, 170, 70),  // orange
            Triple(190, 120, 255)  // purple
        )
    }
}
