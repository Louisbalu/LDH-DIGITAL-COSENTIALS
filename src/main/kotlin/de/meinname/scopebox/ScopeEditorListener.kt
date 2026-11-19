package de.meinname.scopebox

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
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
    val memoryHighlighters = ArrayList<RangeHighlighter>()
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
            clearMemory(editor, state)
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

    private fun clearMemory(editor: Editor, state: EditorState) {
        for (h in state.memoryHighlighters) if (h.isValid) editor.markupModel.removeHighlighter(h)
        state.memoryHighlighters.clear()
    }

    /** Always-on highlighting of every function, each in a rotating soft colour. */
    private fun refreshAll(editor: Editor, state: EditorState) {
        clearFunctions(editor, state)
        val settings = LdhSettings.instance.state

        if (settings.functionHighlightingEnabled) {
            val text = sanitized(editor, state)
            val functions = ScopeScanner.findFunctions(text)
            val bg = editor.colorsScheme.defaultBackground
            val dark = (bg.red * 299 + bg.green * 587 + bg.blue * 114) / 1000 < 128
            val ratio = if (dark) 0.20 else 0.15
            functions.forEachIndexed { index, f ->
                val (r, g, b) = PALETTE[index % PALETTE.size]
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
                if (settings.copyGutterIconEnabled) {
                    h.setGutterIconRenderer(CopyFunctionGutterRenderer(editor, h, Color(r, g, b)))
                }
                state.functionHighlighters.add(h)
            }
        }

        if (settings.scopeBoxEnabled) {
            updateScope(editor)
        } else {
            clearScope(editor, state)
        }

        refreshMemoryCheck(editor, state)
    }

    /** Boxed highlight of the innermost { } block around the caret. */
    private fun updateScope(editor: Editor) {
        val state = editor.getUserData(STATE_KEY) ?: return
        clearScope(editor, state)
        if (editor.isDisposed) return
        val settings = LdhSettings.instance.state
        if (!settings.scopeBoxEnabled) return

        val range = ScopeScanner.findEnclosing(sanitized(editor, state), editor.caretModel.offset) ?: return
        val h = editor.markupModel.addRangeHighlighter(
            range.first, range.last + 1, HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE
        )
        h.setCustomRenderer(ScopeBorderRenderer())
        state.scopeHighlighters.add(h)
    }

    /** Asynchronous live memory analysis (non-EDT). */
    private fun refreshMemoryCheck(editor: Editor, state: EditorState) {
        val settings = LdhSettings.instance.state
        if (!settings.memoryCheckEnabled) {
            clearMemory(editor, state)
            return
        }

        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val ext = file?.extension?.lowercase() ?: ""
        if (ext !in SUPPORTED_EXTENSIONS) {
            clearMemory(editor, state)
            return
        }

        val text = editor.document.charsSequence
        val stamp = editor.document.modificationStamp

        ApplicationManager.getApplication().executeOnPooledThread {
            val findings = ApplicationManager.getApplication().runReadAction<List<MemoryFinding>> {
                if (editor.isDisposed) emptyList() else MemoryCheckScanner.scan(file, text)
            }

            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed && editor.document.modificationStamp == stamp) {
                    clearMemory(editor, state)
                    val s = LdhSettings.instance.state
                    if (!s.memoryCheckEnabled) return@invokeLater

                    for (finding in findings) {
                        if (finding.type == MemoryIssueType.LEAK && !s.checkLeaks) continue
                        if (finding.type == MemoryIssueType.DOUBLE_FREE && !s.checkDoubleFree) continue
                        if (finding.type == MemoryIssueType.USE_AFTER_FREE && !s.checkUseAfterFree) continue

                        val line = finding.line.coerceIn(0, maxOf(0, editor.document.lineCount - 1))

                        // Gutter warning icon
                        val gutterHighlighter = editor.markupModel.addLineHighlighter(
                            line,
                            HighlighterLayer.WARNING,
                            null
                        )
                        gutterHighlighter.gutterIconRenderer = MemoryGutterRenderer(finding)
                        state.memoryHighlighters.add(gutterHighlighter)

                        // Wave underscore highlight on the variable
                        val varStart = finding.offset.coerceIn(0, editor.document.textLength)
                        val varEnd = (finding.offset + finding.length).coerceIn(varStart, editor.document.textLength)
                        if (varEnd > varStart) {
                            val waveAttrs = TextAttributes().apply {
                                effectType = EffectType.WAVE_UNDERSCORE
                                effectColor = when (finding.type) {
                                    MemoryIssueType.LEAK -> JBColor(Color(230, 130, 0), Color(245, 160, 30))
                                    MemoryIssueType.DOUBLE_FREE -> JBColor(Color(210, 40, 40), Color(250, 80, 80))
                                    MemoryIssueType.USE_AFTER_FREE -> JBColor(Color(180, 40, 160), Color(225, 75, 205))
                                }
                            }
                            val varHighlighter = editor.markupModel.addRangeHighlighter(
                                varStart,
                                varEnd,
                                HighlighterLayer.WARNING,
                                waveAttrs,
                                HighlighterTargetArea.EXACT_RANGE
                            )
                            state.memoryHighlighters.add(varHighlighter)
                        }
                    }
                }
            }
        }
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
        val SUPPORTED_EXTENSIONS = setOf("c", "h", "cpp", "hpp", "cc", "cxx", "hxx")
    }
}
