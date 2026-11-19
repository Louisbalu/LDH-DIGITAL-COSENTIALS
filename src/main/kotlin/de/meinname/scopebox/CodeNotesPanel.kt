package de.meinname.scopebox

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.event.DocumentListener as SwingDocumentListener

class CodeNotesPanel(
    private val project: Project,
    parentDisposable: Disposable,
    private val onExportToNotepad: ((String) -> Unit)? = null
) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<CodeNote>()
    private val list = JBList(listModel)
    private val allNotes = ArrayList<CodeNote>()

    private val statusLabel = JBLabel("Initialisiere Notizen...")
    private val filterField = JBTextField()
    private val scopeCombo = JComboBox(arrayOf("Ganzes Projekt (C-Dateien)", "Aktuelle Datei"))
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Notizen neu laden"
        preferredSize = Dimension(26, 26)
    }
    private val exportButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Alle Notizen in den Notizblock kopieren"
        preferredSize = Dimension(26, 26)
        isVisible = onExportToNotepad != null
    }

    private val cardLayout = CardLayout()
    private val centerPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel(
        "<html><center>Keine // Notizen im C-Code gefunden.<br><small style='color:gray;'>Schreibe z.B. <b>// Notiz: ...</b> oder <b>// ...</b> im Code</small></center></html>",
        SwingConstants.CENTER
    )

    private val debounceTimer = Timer(350) { refreshNotes() }.apply { isRepeats = false }

    init {
        Disposer.register(parentDisposable, this)

        buildUi()
        setupListeners()
        scheduleRefresh()
    }

    private fun buildUi() {
        val toolbarPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)
            )
        }

        val topRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(scopeCombo)
                add(refreshButton)
                if (onExportToNotepad != null) {
                    add(exportButton)
                }
            }
            add(actionsPanel, BorderLayout.EAST)
        }

        val searchRow = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            filterField.emptyText.text = "Filtere Notizen (z.B. Notiz, TODO, Text)..."
            add(filterField, BorderLayout.CENTER)
        }

        toolbarPanel.add(topRow)
        toolbarPanel.add(searchRow)
        add(toolbarPanel, BorderLayout.NORTH)

        list.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        list.cellRenderer = object : ColoredListCellRenderer<CodeNote>() {
            override fun customizeCellRenderer(
                list: JList<out CodeNote>,
                value: CodeNote?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                icon = AllIcons.Toolwindows.ToolWindowMessages

                val isProjectScope = scopeCombo.selectedIndex == 0
                if (isProjectScope) {
                    append("[${value.fileName}] ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                }

                append("Z. ${value.displayLine}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                // Function badge in the exact same color as in the editor
                if (value.functionName.isNotEmpty()) {
                    val fnColor = value.functionColor ?: JBColor(Color(100, 150, 255), Color(120, 170, 255))
                    val fnAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fnColor)
                    append("[${value.functionName}()] ", fnAttr)
                }

                if (value.prefix.isNotEmpty()) {
                    val tagAttrs = when (value.prefix) {
                        "NOTIZ", "NOTE", "NOTIZEN", "NOTES" -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(0, 120, 215), Color(82, 168, 255))
                        )
                        "TODO" -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(215, 120, 0), Color(255, 170, 60))
                        )
                        "FIXME", "ACHTUNG", "WICHTIG", "WARNUNG", "WARNING", "BUG" -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(200, 40, 40), Color(255, 100, 100))
                        )
                        else -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    }
                    append("[${value.prefix}] ", tagAttrs)
                }

                append(value.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val fnInfo = if (value.functionName.isNotEmpty()) " in ${value.functionName}()" else ""
                toolTipText = "${value.fileName} (Zeile ${value.displayLine})$fnInfo - Klick springt direkt dorthin"
            }
        }

        val scrollPane = JBScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
        }

        val emptyPanel = JPanel(BorderLayout()).apply {
            add(emptyLabel, BorderLayout.CENTER)
        }

        centerPanel.add(scrollPane, "LIST")
        centerPanel.add(emptyPanel, "EMPTY")
        add(centerPanel, BorderLayout.CENTER)

        val hintLabel = JBLabel("Klick auf eine Notiz springt direkt zur Codezeile.", SwingConstants.CENTER).apply {
            font = font.deriveFont(font.size2D - 1.5f)
            foreground = JBColor.GRAY
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        add(hintLabel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) {
                        val note = listModel.getElementAt(index)
                        navigateTo(note)
                    }
                }
            }
        })

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_SPACE) {
                    val note = list.selectedValue ?: return
                    navigateTo(note)
                }
            }
        })

        scopeCombo.addActionListener {
            scheduleRefresh()
        }

        refreshButton.addActionListener {
            scheduleRefresh()
        }

        exportButton.addActionListener {
            exportNotesToNotepad()
        }

        filterField.document.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = applyFilter()
            override fun removeUpdate(e: SwingDocumentEvent) = applyFilter()
            override fun changedUpdate(e: SwingDocumentEvent) = applyFilter()
        })

        // Listen for editor tab switching or file opening
        subscribeTopic(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    scheduleRefresh()
                }

                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    scheduleRefresh()
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    scheduleRefresh()
                }
            }
        )

        // Listen for live text editing inside active editor
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                debounceTimer.restart()
            }
        }, this)

        // Listen for files created / modified / deleted on disk
        subscribeTopic(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { ev ->
                        val vf = ev.file
                        vf != null && isSupportedSourceFile(vf)
                    }
                    if (relevant) {
                        debounceTimer.restart()
                    }
                }
            }
        )
    }

    private fun <T> subscribeTopic(topic: com.intellij.util.messages.Topic<T>, handler: T) {
        try {
            val getMessageBus = project.javaClass.getMethod("getMessageBus")
            val bus = getMessageBus.invoke(project) ?: return
            val connect = bus.javaClass.getMethod("connect", Disposable::class.java)
            val connection = connect.invoke(bus, this) ?: return
            val subscribe = connection.javaClass.getMethod(
                "subscribe",
                com.intellij.util.messages.Topic::class.java,
                Any::class.java
            )
            subscribe.invoke(connection, topic, handler)
        } catch (_: Throwable) {
        }
    }

    fun scheduleRefresh() {
        debounceTimer.restart()
    }

    private fun refreshNotes() {
        if (project.isDisposed) return

        val isProjectScope = scopeCombo.selectedIndex == 0

        if (isProjectScope) {
            statusLabel.text = "Projekt wird gescannt..."
            ApplicationManager.getApplication().executeOnPooledThread {
                val notes = ArrayList<CodeNote>()
                val files = ApplicationManager.getApplication().runReadAction<Set<VirtualFile>> {
                    if (project.isDisposed) emptySet() else collectProjectFiles(project)
                }

                for (vf in files) {
                    if (project.isDisposed) break
                    ApplicationManager.getApplication().runReadAction {
                        if (vf.isValid) {
                            val cachedDoc = FileDocumentManager.getInstance().getCachedDocument(vf)
                            val text = cachedDoc?.charsSequence ?: try {
                                VfsUtilCore.loadText(vf)
                            } catch (_: Throwable) {
                                null
                            }
                            if (text != null) {
                                notes.addAll(CodeNotesScanner.scan(vf, text))
                            }
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        allNotes.clear()
                        allNotes.addAll(notes)
                        statusLabel.text = "${allNotes.size} Notizen in ${files.size} C-Dateien"
                        applyFilter()
                    }
                }
            }
        } else {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
                ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

            if (file == null || !file.isValid) {
                allNotes.clear()
                statusLabel.text = "Keine Datei aktiv"
                applyFilter()
                return
            }

            val doc = editor?.document ?: FileDocumentManager.getInstance().getDocument(file)
            val text = doc?.charsSequence ?: try {
                VfsUtilCore.loadText(file)
            } catch (_: Throwable) {
                ""
            }

            val notes = CodeNotesScanner.scan(file, text)
            allNotes.clear()
            allNotes.addAll(notes)
            statusLabel.text = "${file.name} (${allNotes.size} Notizen)"
            applyFilter()
        }
    }

    private fun collectProjectFiles(project: Project): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()

        // 1. Open editor files
        for (vf in FileEditorManager.getInstance(project).openFiles) {
            if (vf.isValid && !vf.isDirectory && isSupportedSourceFile(vf)) {
                files.add(vf)
            }
        }

        // 2. Project File Index (CLion/IDEA indexed files)
        try {
            ProjectFileIndex.getInstance(project).iterateContent { vf ->
                if (!vf.isDirectory && isSupportedSourceFile(vf) && vf.length < 2_000_000) {
                    val p = vf.path
                    if (!p.contains("/cmake-build-") && !p.contains("/.git/") && !p.contains("/.idea/")) {
                        files.add(vf)
                    }
                }
                true
            }
        } catch (_: Throwable) {}

        // 3. Fallback / supplementary directory traversal (handles unindexed CMake/folders)
        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

        if (baseDir != null && baseDir.isValid) {
            VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Void>(limit(8)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) {
                        val name = file.name
                        if (name.startsWith(".") || name == "build" || name == "out" ||
                            name.startsWith("cmake-build-") || name == "target" || name == "bin"
                        ) {
                            return false
                        }
                        return true
                    }
                    if (isSupportedSourceFile(file) && file.length < 2_000_000) {
                        files.add(file)
                    }
                    return true
                }
            })
        }

        return files
    }

    private fun applyFilter() {
        val query = filterField.text.trim().lowercase()
        listModel.clear()

        val filtered = if (query.isEmpty()) {
            allNotes
        } else {
            allNotes.filter {
                it.text.lowercase().contains(query) ||
                it.fileName.lowercase().contains(query) ||
                it.prefix.lowercase().contains(query) ||
                it.functionName.lowercase().contains(query) ||
                "z. ${it.displayLine}".contains(query)
            }
        }

        for (note in filtered) {
            listModel.addElement(note)
        }

        if (listModel.isEmpty) {
            cardLayout.show(centerPanel, "EMPTY")
        } else {
            cardLayout.show(centerPanel, "LIST")
        }
    }

    private fun navigateTo(note: CodeNote) {
        if (project.isDisposed || !note.file.isValid) return

        val descriptor = OpenFileDescriptor(project, note.file, note.line, note.column)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor != null) {
            val line = note.line.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
            val lineStart = editor.document.getLineStartOffset(line)
            val lineEnd = editor.document.getLineEndOffset(line)
            val targetOffset = (lineStart + note.column).coerceIn(lineStart, lineEnd)
            editor.caretModel.moveToOffset(targetOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    private fun exportNotesToNotepad() {
        if (allNotes.isEmpty()) return
        val sb = StringBuilder()
        sb.appendLine("\n=== // Code-Notizen (Exportiert am ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}) ===")
        for (note in allNotes) {
            val fnPart = if (note.functionName.isNotEmpty()) " in ${note.functionName}()" else ""
            val prefixPart = if (note.prefix.isNotEmpty()) "[${note.prefix}] " else ""
            sb.appendLine("- ${note.fileName}:${note.displayLine}$fnPart -> $prefixPart${note.text}")
        }
        sb.appendLine()
        onExportToNotepad?.invoke(sb.toString())
    }

    fun getAllNotesFormatted(): String {
        val sb = StringBuilder()
        for (note in allNotes) {
            val fnPart = if (note.functionName.isNotEmpty()) " in ${note.functionName}()" else ""
            val prefixPart = if (note.prefix.isNotEmpty()) "[${note.prefix}] " else ""
            sb.appendLine("- ${note.fileName}:${note.displayLine}$fnPart -> $prefixPart${note.text}")
        }
        return sb.toString()
    }

    override fun dispose() {
        debounceTimer.stop()
    }

    companion object {
        private val C_SOURCE_EXTENSIONS = setOf(
            "c", "h", "cpp", "hpp", "cc", "cxx", "hxx", "c++", "h++", "i", "ii"
        )
        private val OTHER_SOURCE_EXTENSIONS = setOf(
            "java", "kt", "kts", "cs", "py", "js", "ts", "rs", "go"
        )

        fun isSupportedSourceFile(file: VirtualFile): Boolean {
            val ext = file.extension?.lowercase() ?: return false
            return ext in C_SOURCE_EXTENSIONS || ext in OTHER_SOURCE_EXTENSIONS
        }
    }
}
