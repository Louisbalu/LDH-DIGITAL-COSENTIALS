package de.meinname.scopebox

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class NotesToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabbedPane = JBTabbedPane()
        val store = NotesStore(project)

        lateinit var projectNotesPanel: NotesPanel

        val codeNotesPanel = CodeNotesPanel(project, toolWindow.disposable) { exportedText ->
            projectNotesPanel.appendNotes(exportedText)
            tabbedPane.selectedIndex = 1
        }

        projectNotesPanel = NotesPanel(store) {
            val notesText = codeNotesPanel.getAllNotesFormatted()
            if (notesText.isNotBlank()) {
                val formatted = "\n=== // C-Code Notizen ===\n$notesText\n"
                projectNotesPanel.appendNotes(formatted)
            }
        }

        val memoryCheckPanel = MemoryCheckPanel(project, toolWindow.disposable)

        tabbedPane.addTab("Code-Notizen (//)", codeNotesPanel)
        tabbedPane.addTab("Memory", memoryCheckPanel)
        tabbedPane.addTab("Notizblock", projectNotesPanel)

        val content = ContentFactory.getInstance().createContent(tabbedPane, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class NotesPanel(
    private val store: NotesStore,
    private val onImportCodeNotes: (() -> Unit)? = null
) : JPanel(BorderLayout()) {
    private val area = JTextArea(store.load())
    private val saveTimer = Timer(600) { store.save(area.text) }.apply { isRepeats = false }

    init {
        area.lineWrap = true
        area.wrapStyleWord = true
        area.tabSize = 4
        area.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        area.toolTipText = "Notizen werden gespeichert in " + store.file.path

        if (onImportCodeNotes != null) {
            val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                val importBtn = JButton("Alle // Notizen aus Code einfügen", AllIcons.Actions.Download).apply {
                    toolTipText = "Fügt alle gefundenen // Kommentare aus dem Code in diesen Notizblock ein"
                    addActionListener { onImportCodeNotes.invoke() }
                }
                add(importBtn)
            }
            add(topBar, BorderLayout.NORTH)
        }

        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = saveTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = saveTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = saveTimer.restart()
        })

        area.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                saveTimer.stop()
                store.save(area.text)
            }

            override fun focusGained(e: FocusEvent) {
                val disk = store.load()
                if (disk != area.text) {
                    area.text = disk
                    area.caretPosition = area.document.length
                }
            }
        })

        add(JScrollPane(area), BorderLayout.CENTER)
    }

    fun appendNotes(text: String) {
        val current = area.text
        val newText = if (current.isBlank()) text.trimStart() else "$current\n$text"
        area.text = newText
        area.caretPosition = area.document.length
        store.save(newText)
    }
}
