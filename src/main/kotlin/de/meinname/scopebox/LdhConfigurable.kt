package de.meinname.scopebox

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class LdhConfigurable : Configurable {

    // General feature checkboxes
    private val scopeBoxCheck = JBCheckBox("Scope-Box (Rahmen um den aktuellen Block)")
    private val functionHighlightCheck = JBCheckBox("Funktions-Hervorhebung (Rotierende Hintergrundfarben)")
    private val copyGutterIconCheck = JBCheckBox("Kopier-Icon im Gutter (Funktion mit 1 Klick kopieren)")
    private val codeNotesCheck = JBCheckBox("Code-Notizen (//) Scanner & Navigation")

    // Memory check components
    private val memoryCheck = JBCheckBox("Memory-Check für C/C++ aktivieren")
    private val severityCombo = JComboBox(arrayOf("Warnung (empfohlen)", "Schwache Warnung", "Information"))
    private val checkLeaksCheck = JBCheckBox("Speicherlecks (Leaks) erkennen")
    private val checkDoubleFreeCheck = JBCheckBox("Doppelte Freigabe (Double Free) erkennen")
    private val checkUseAfterFreeCheck = JBCheckBox("Nutzung nach Freigabe (Use After Free) erkennen")

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "LDH Digital Cosentials"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }

        // Section 1: Features
        val generalSection = createSection("Funktionen aktivieren / deaktivieren").apply {
            add(scopeBoxCheck)
            add(Box.createVerticalStrut(4))
            add(functionHighlightCheck)
            add(Box.createVerticalStrut(4))
            add(copyGutterIconCheck)
            add(Box.createVerticalStrut(4))
            add(codeNotesCheck)
        }
        panel.add(generalSection)
        panel.add(Box.createVerticalStrut(16))

        // Section 2: Memory Check
        val memorySection = createSection("Memory-Check (C/C++)").apply {
            add(memoryCheck)
            add(Box.createVerticalStrut(6))

            val severityRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JBLabel("Schweregrad der Anzeige:"))
                add(severityCombo)
            }
            add(severityRow)
            add(Box.createVerticalStrut(6))

            val subChecks = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(0, 20, 0, 0)
                add(checkLeaksCheck)
                add(Box.createVerticalStrut(4))
                add(checkDoubleFreeCheck)
                add(Box.createVerticalStrut(4))
                add(checkUseAfterFreeCheck)
            }
            add(subChecks)
            add(Box.createVerticalStrut(4))

            val formatsHint = JBLabel("Unterstützte Dateitypen: .c, .h, .cpp, .hpp, .cc").apply {
                font = font.deriveFont(font.size2D - 1.5f)
                foreground = java.awt.Color.GRAY
            }
            add(formatsHint)
        }
        panel.add(memorySection)
        panel.add(Box.createVerticalStrut(16))

        // Section 3: Hotkeys / Shortcuts
        val hotkeySection = createSection("Tastenkürzel (Hotkeys)").apply {
            val label1 = JBLabel("• Funktion an Cursor-Position kopieren:  Strg + Alt + Umschalt + K")
            val label2 = JBLabel("• Alle Funktionen der Datei kopieren:     Editor-Kontextmenü (Rechtsklick)")
            add(label1)
            add(Box.createVerticalStrut(4))
            add(label2)
            add(Box.createVerticalStrut(10))

            val keymapBtn = JButton("Tastenkürzel in der Keymap anpassen...").apply {
                addActionListener {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Keymap")
                }
            }
            val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(keymapBtn)
            }
            add(btnRow)
        }
        panel.add(hotkeySection)

        memoryCheck.addActionListener {
            val enabled = memoryCheck.isSelected
            severityCombo.isEnabled = enabled
            checkLeaksCheck.isEnabled = enabled
            checkDoubleFreeCheck.isEnabled = enabled
            checkUseAfterFreeCheck.isEnabled = enabled
        }

        rootPanel = JPanel(BorderLayout()).apply {
            add(panel, BorderLayout.NORTH)
        }
        return rootPanel!!
    }

    private fun createSection(title: String): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(),
                    title,
                    javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                    javax.swing.border.TitledBorder.DEFAULT_POSITION,
                    java.awt.Font("SansSerif", Font.BOLD, 12)
                ),
                BorderFactory.createEmptyBorder(6, 10, 8, 10)
            )
        }
    }

    override fun isModified(): Boolean {
        val s = LdhSettings.instance.state
        return scopeBoxCheck.isSelected != s.scopeBoxEnabled ||
                functionHighlightCheck.isSelected != s.functionHighlightingEnabled ||
                copyGutterIconCheck.isSelected != s.copyGutterIconEnabled ||
                codeNotesCheck.isSelected != s.codeNotesEnabled ||
                memoryCheck.isSelected != s.memoryCheckEnabled ||
                severityCombo.selectedIndex != severityIndex(s.memorySeverity) ||
                checkLeaksCheck.isSelected != s.checkLeaks ||
                checkDoubleFreeCheck.isSelected != s.checkDoubleFree ||
                checkUseAfterFreeCheck.isSelected != s.checkUseAfterFree
    }

    override fun apply() {
        val s = LdhSettings.instance.state
        s.scopeBoxEnabled = scopeBoxCheck.isSelected
        s.functionHighlightingEnabled = functionHighlightCheck.isSelected
        s.copyGutterIconEnabled = copyGutterIconCheck.isSelected
        s.codeNotesEnabled = codeNotesCheck.isSelected
        s.memoryCheckEnabled = memoryCheck.isSelected
        s.memorySeverity = when (severityCombo.selectedIndex) {
            1 -> "WEAK_WARNING"
            2 -> "INFO"
            else -> "WARNING"
        }
        s.checkLeaks = checkLeaksCheck.isSelected
        s.checkDoubleFree = checkDoubleFreeCheck.isSelected
        s.checkUseAfterFree = checkUseAfterFreeCheck.isSelected
    }

    override fun reset() {
        val s = LdhSettings.instance.state
        scopeBoxCheck.isSelected = s.scopeBoxEnabled
        functionHighlightCheck.isSelected = s.functionHighlightingEnabled
        copyGutterIconCheck.isSelected = s.copyGutterIconEnabled
        codeNotesCheck.isSelected = s.codeNotesEnabled
        memoryCheck.isSelected = s.memoryCheckEnabled
        severityCombo.selectedIndex = severityIndex(s.memorySeverity)
        checkLeaksCheck.isSelected = s.checkLeaks
        checkDoubleFreeCheck.isSelected = s.checkDoubleFree
        checkUseAfterFreeCheck.isSelected = s.checkUseAfterFree

        val enabled = memoryCheck.isSelected
        severityCombo.isEnabled = enabled
        checkLeaksCheck.isEnabled = enabled
        checkDoubleFreeCheck.isEnabled = enabled
        checkUseAfterFreeCheck.isEnabled = enabled
    }

    private fun severityIndex(sev: String): Int = when (sev) {
        "WEAK_WARNING" -> 1
        "INFO" -> 2
        else -> 0
    }
}
