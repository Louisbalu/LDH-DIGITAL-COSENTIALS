package de.meinname.scopebox

import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Gutter warning icon next to allocations or locations with memory issues.
 */
class MemoryGutterRenderer(val finding: MemoryFinding) : GutterIconRenderer() {

    private val icon = object : Icon {
        override fun getIconWidth() = 14
        override fun getIconHeight() = 14
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val color = when (finding.type) {
                    MemoryIssueType.LEAK -> JBColor(Color(230, 130, 0), Color(245, 160, 30))
                    MemoryIssueType.DOUBLE_FREE -> JBColor(Color(210, 40, 40), Color(250, 80, 80))
                    MemoryIssueType.USE_AFTER_FREE -> JBColor(Color(180, 40, 160), Color(225, 75, 205))
                }
                g2.color = color
                val xs = intArrayOf(x + 7, x + 1, x + 13)
                val ys = intArrayOf(y + 1, y + 13, y + 13)
                g2.fillPolygon(xs, ys, 3)

                // Exclamation mark
                g2.color = Color.WHITE
                g2.fillRect(x + 6, y + 5, 2, 4)
                g2.fillRect(x + 6, y + 10, 2, 2)
            } finally {
                g2.dispose()
            }
        }
    }

    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String = "[${finding.type.title}] ${finding.message}"
    override fun equals(other: Any?): Boolean = other is MemoryGutterRenderer && other.finding == finding
    override fun hashCode(): Int = finding.hashCode()
}
