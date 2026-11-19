package de.meinname.scopebox

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Copies the (outermost) function that contains the caret. */
class CopyFunctionAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = ScopeScanner.sanitize(editor.document.charsSequence)
        val caret = editor.caretModel.offset
        val f = ScopeScanner.findFunctions(text).firstOrNull { caret >= it.start && caret <= it.close + 1 }
        if (f == null) FunctionCopy.toast(editor, "Cursor steht in keiner Funktion")
        else FunctionCopy.copyRange(editor, f.start, f.close + 1)
    }
}

/** Copies every function of the file, separated by blank lines. */
class CopyAllFunctionsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val chars = editor.document.charsSequence
        val functions = ScopeScanner.findFunctions(ScopeScanner.sanitize(chars))
        if (functions.isEmpty()) return
        val joined = functions.joinToString("\n\n") { FunctionCopy.extract(chars, it.start, it.close + 1) }
        FunctionCopy.copyToClipboard(editor, joined, functions.size.toString() + " Funktionen kopiert")
    }
}
