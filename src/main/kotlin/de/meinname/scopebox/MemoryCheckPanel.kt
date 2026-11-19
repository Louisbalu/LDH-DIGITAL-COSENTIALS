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
import com.intellij.openapi.vfs.VirtualFileVisitor
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

class MemoryCheckPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val listModel = DefaultListModel<MemoryFinding>()
    private val list = JBList(listModel)
    private val allFindings = ArrayList<MemoryFinding>()

    private val statusLabel = JBLabel("Memory-Check bereit")
    private val filterField = JBTextField()
    private val typeFilterCombo = JComboBox(arrayOf("Alle Typen", "Nur Leaks", "Nur Double Free", "Nur Use After Free"))
    private val scopeCombo = JComboBox(arrayOf("Ganzes Projekt (C/C++)", "Aktuelle Datei"))
    private val scanButton = JButton("Projekt scannen", AllIcons.Actions.Refresh).apply {
        toolTipText = "Projekt jetzt nach Speicherproblemen durchsuchen"
    }

    private val cardLayout = CardLayout()
    private val centerPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel(
        "<html><center>Keine Speicherprobleme gefunden.<br><small style='color:gray;'>Dynamischer Speicher wird ordnungsgemäß verwaltet.</small></center></html>",
        SwingConstants.CENTER
    )

    private val debounceTimer = Timer(400) { refreshFindings() }.apply { isRepeats = false }

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
                add(typeFilterCombo)
                add(scanButton)
            }
            add(actionsPanel, BorderLayout.EAST)
        }

        val searchRow = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            filterField.emptyText.text = "Filtere nach Variable, Funktion oder Datei..."
            add(filterField, BorderLayout.CENTER)
        }

        toolbarPanel.add(topRow)
        toolbarPanel.add(searchRow)
        add(toolbarPanel, BorderLayout.NORTH)

        list.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        list.cellRenderer = object : ColoredListCellRenderer<MemoryFinding>() {
            override fun customizeCellRenderer(
                list: JList<out MemoryFinding>,
                value: MemoryFinding?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                icon = AllIcons.General.Warning

                val isProjectScope = scopeCombo.selectedIndex == 0
                if (isProjectScope) {
                    append("[${value.fileName}] ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                }

                append("Z. ${value.displayLine}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                // Type badge with distinct warning colors
                val (badgeText, badgeColor) = when (value.type) {
                    MemoryIssueType.LEAK -> Pair("[LEAK]", JBColor(Color(230, 130, 0), Color(245, 160, 30)))
                    MemoryIssueType.DOUBLE_FREE -> Pair("[DOUBLE_FREE]", JBColor(Color(210, 40, 40), Color(250, 80, 80)))
                    MemoryIssueType.USE_AFTER_FREE -> Pair("[USE_AFTER_FREE]", JBColor(Color(180, 40, 160), Color(225, 75, 205)))
                }
                append("$badgeText ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, badgeColor))

                // Variable name badge
                append("[${value.variableName}] ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

                if (value.functionName.isNotEmpty()) {
                    append("in ${value.functionName}()  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                append(value.message, SimpleTextAttributes.REGULAR_ATTRIBUTES)

                toolTipText = "${value.fileName} (Zeile ${value.displayLine}): ${value.message} - Klicken zum Öffnen"
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

        val hintLabel = JBLabel("Klick auf ein Finding springt direkt zur betroffenen Codezeile.", SwingConstants.CENTER).apply {
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
                        val finding = listModel.getElementAt(index)
                        navigateTo(finding)
                    }
                }
            }
        })

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_SPACE) {
                    val finding = list.selectedValue ?: return
                    navigateTo(finding)
                }
            }
        })

        scanButton.addActionListener {
            scheduleRefresh()
        }

        scopeCombo.addActionListener {
            scheduleRefresh()
        }

        typeFilterCombo.addActionListener {
            applyFilter()
        }

        filterField.document.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = applyFilter()
            override fun removeUpdate(e: SwingDocumentEvent) = applyFilter()
            override fun changedUpdate(e: SwingDocumentEvent) = applyFilter()
        })

        // Listen for active editor changes
        subscribeTopic(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = scheduleRefresh()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = scheduleRefresh()
            }
        )

        // Listen for live text editing
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                debounceTimer.restart()
            }
        }, this)
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

    private fun refreshFindings() {
        if (project.isDisposed) return

        val s = LdhSettings.instance.state
        if (!s.memoryCheckEnabled) {
            allFindings.clear()
            statusLabel.text = "Memory-Check in den Einstellungen deaktiviert"
            applyFilter()
            return
        }

        val isProjectScope = scopeCombo.selectedIndex == 0

        if (isProjectScope) {
            statusLabel.text = "Projekt wird analysiert..."
            ApplicationManager.getApplication().executeOnPooledThread {
                val findings = ArrayList<MemoryFinding>()
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
                                findings.addAll(MemoryCheckScanner.scan(vf, text))
                            }
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        allFindings.clear()
                        allFindings.addAll(findings)
                        statusLabel.text = "${allFindings.size} Speicherprobleme in ${files.size} C-Dateien"
                        applyFilter()
                    }
                }
            }
        } else {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
                ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

            if (file == null || !file.isValid) {
                allFindings.clear()
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

            val findings = MemoryCheckScanner.scan(file, text)
            allFindings.clear()
            allFindings.addAll(findings)
            statusLabel.text = "${file.name} (${allFindings.size} Speicherprobleme)"
            applyFilter()
        }
    }

    private fun collectProjectFiles(project: Project): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()

        for (vf in FileEditorManager.getInstance(project).openFiles) {
            if (vf.isValid && !vf.isDirectory && isSupportedSourceFile(vf)) {
                files.add(vf)
            }
        }

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
        val typeFilter = typeFilterCombo.selectedIndex
        listModel.clear()

        val filtered = allFindings.filter { finding ->
            // Type filter
            val matchesType = when (typeFilter) {
                1 -> finding.type == MemoryIssueType.LEAK
                2 -> finding.type == MemoryIssueType.DOUBLE_FREE
                3 -> finding.type == MemoryIssueType.USE_AFTER_FREE
                else -> true
            }

            // Text search
            val matchesQuery = query.isEmpty() ||
                    finding.variableName.lowercase().contains(query) ||
                    finding.functionName.lowercase().contains(query) ||
                    finding.fileName.lowercase().contains(query) ||
                    finding.message.lowercase().contains(query) ||
                    finding.type.name.lowercase().contains(query) ||
                    "z. ${finding.displayLine}".contains(query)

            matchesType && matchesQuery
        }

        for (f in filtered) {
            listModel.addElement(f)
        }

        if (listModel.isEmpty) {
            cardLayout.show(centerPanel, "EMPTY")
        } else {
            cardLayout.show(centerPanel, "LIST")
        }
    }

    private fun navigateTo(finding: MemoryFinding) {
        if (project.isDisposed || finding.file == null || !finding.file.isValid) return

        val descriptor = OpenFileDescriptor(project, finding.file, finding.line, finding.column)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor != null) {
            val line = finding.line.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
            val lineStart = editor.document.getLineStartOffset(line)
            val lineEnd = editor.document.getLineEndOffset(line)
            val targetOffset = (lineStart + finding.column).coerceIn(lineStart, lineEnd)
            editor.caretModel.moveToOffset(targetOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    override fun dispose() {
        debounceTimer.stop()
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf(
            "c", "h", "cpp", "hpp", "cc", "cxx", "hxx"
        )

        fun isSupportedSourceFile(file: VirtualFile): Boolean {
            val ext = file.extension?.lowercase() ?: return false
            return ext in SUPPORTED_EXTENSIONS
        }
    }
}
