package io.shi.gaugeplugin.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import io.shi.gaugeplugin.ui.MindmapToolWindowFactory

class ShowMindmapAction : AnAction("Show Gauge Mindmap") {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Get multiple selected files/folders
        val selectedFiles = getSelectedFiles(e)
        if (selectedFiles.isEmpty()) return

        // Get or create tool window
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("Gauge Mindmap")
        toolWindow?.show()

        // Get the MindmapView from the factory
        val mindmapView = MindmapToolWindowFactory.getView(project)
        
        // Load specifications from all selected files/folders
        mindmapView?.loadSpecifications(selectedFiles)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        
        // Check if we have at least one file/folder selected
        // In Project View context menu, VIRTUAL_FILE is always available when right-clicking
        val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileArray = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        
        // Enable if we have at least one file (single or multiple)
        e.presentation.isEnabled = singleFile != null || (fileArray != null && fileArray.isNotEmpty())
    }
    
    private fun getSelectedFiles(e: AnActionEvent): List<VirtualFile> {
        // Try to get multiple selected files first (VIRTUAL_FILE_ARRAY)
        val fileArray = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (fileArray != null && fileArray.isNotEmpty()) {
            return fileArray.toList()
        }
        
        // Fallback to single file
        val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        return if (singleFile != null) listOf(singleFile) else emptyList()
    }
}

