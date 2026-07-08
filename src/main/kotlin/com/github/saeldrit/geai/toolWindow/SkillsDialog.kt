package com.github.saeldrit.geai.toolWindow

import com.github.saeldrit.geai.context.Skill
import com.github.saeldrit.geai.context.SkillStore
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Modal dialog for managing user skills: view, toggle (enable/disable), edit, delete.
 */
class SkillsDialog(private val project: Project) : JDialog() {

    private val store = SkillStore.getInstance(project)
    private val listPanel = JPanel()
    private val emptyLabel = JLabel("No saved skills yet.").apply {
        foreground = JBColor.GRAY
        horizontalAlignment = SwingConstants.CENTER
    }

    init {
        title = "Skills — geai"
        setSize(520, 420)
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setLocationRelativeTo(null)

        val content = JPanel(BorderLayout()).apply {
            border = EmptyBorder(12, 12, 12, 12)
        }

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(JLabel("Manage skills — toggle, edit, or delete").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 12f)
            }, BorderLayout.WEST)
        }
        content.add(header, BorderLayout.NORTH)

        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        val scroll = JBScrollPane(listPanel).apply {
            border = BorderFactory.createLineBorder(JBColor.border())
            preferredSize = Dimension(0, 300)
        }
        content.add(scroll, BorderLayout.CENTER)

        val bottom = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("Close").apply { addActionListener { dispose() } })
        }
        content.add(bottom, BorderLayout.SOUTH)

        contentPane = content
        refreshList()
    }

    private fun refreshList() {
        listPanel.removeAll()
        val skills = store.loadAll()
        if (skills.isEmpty()) {
            listPanel.add(emptyLabel)
            emptyLabel.isVisible = true
        } else {
            emptyLabel.isVisible = false
            skills.forEach { skill ->
                listPanel.add(buildSkillRow(skill))
                listPanel.add(Box.createVerticalStrut(6))
            }
        }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun buildSkillRow(skill: Skill): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 80)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                EmptyBorder(8, 10, 8, 10)
            )
        }

        val left = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
        }

        val toggle = JCheckBox().apply {
            isSelected = skill.enabled
            toolTipText = if (skill.enabled) "Disable" else "Enable"
            addActionListener {
                store.toggle(skill.id)
                refreshList()
            }
        }
        left.add(toggle, BorderLayout.WEST)

        val descLabel = JLabel(skill.description).apply {
            font = font.deriveFont(Font.PLAIN, 13f)
            foreground = if (skill.enabled) JBColor.foreground() else JBColor.GRAY
            toolTipText = skill.description
        }
        left.add(descLabel, BorderLayout.CENTER)

        panel.add(left, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
        }

        buttons.add(JButton("Edit").apply {
            addActionListener { editSkill(skill) }
        })
        buttons.add(JButton("Delete").apply {
            foreground = JBColor(Color(0xe06c75), Color(0xe06c75))
            addActionListener { deleteSkill(skill) }
        })

        panel.add(buttons, BorderLayout.EAST)
        return panel
    }

    private fun editSkill(skill: Skill) {
        val input = JTextArea(skill.description, 4, 40).apply {
            lineWrap = true; wrapStyleWord = true
        }
        val result = JOptionPane.showConfirmDialog(
            this, JBScrollPane(input),
            "Edit skill: ${skill.id}", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            val newDesc = input.text.trim()
            if (newDesc.isNotBlank()) {
                store.update(skill.id, newDesc)
                refreshList()
            }
        }
    }

    private fun deleteSkill(skill: Skill) {
        val confirm = JOptionPane.showConfirmDialog(
            this, "Delete skill \"${skill.description}\"?",
            "Confirm delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            store.delete(skill.id)
            refreshList()
        }
    }
}
