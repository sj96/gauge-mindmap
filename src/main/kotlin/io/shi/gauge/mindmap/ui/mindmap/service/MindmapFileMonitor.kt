package io.shi.gauge.mindmap.ui.mindmap.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.util.messages.MessageBusConnection
import io.shi.gauge.mindmap.ui.mindmap.constants.MindmapConstants
import javax.swing.SwingUtilities

/**
 * Monitors file changes and triggers reload
 */
class MindmapFileMonitor(
    private val project: Project,
    private val onReload: () -> Unit
) {
    private var currentFiles: MutableList<VirtualFile> = mutableListOf()
    private var fileListenerConnection: MessageBusConnection? = null
    private var vfsListener: VirtualFileListener? = null
    private val documentListeners = mutableMapOf<Document, DocumentListener>()
    private var reloadTimer: javax.swing.Timer? = null
    private var reloadPending = false

    fun setCurrentFiles(files: List<VirtualFile>) {
        currentFiles = files.toMutableList()
        // Always update document listeners to ensure all open files are monitored
        // This is important because files might be opened before setCurrentFiles is called
        updateDocumentListeners()
    }

    private fun updateDocumentListeners() {
        val fileDocumentManager = FileDocumentManager.getInstance()

        // Remove listeners for files that are no longer being monitored
        documentListeners.keys.toList().forEach { document ->
            val file = fileDocumentManager.getFile(document)
            if (file == null || !shouldReloadForFile(file)) {
                documentListeners.remove(document)?.let { listener ->
                    document.removeDocumentListener(listener)
                }
            }
        }

        // Add listeners for all spec files that are currently open
        // Always add listener for .spec files, check shouldReloadForFile in documentChanged
        // Use ReadAction to safely access Document from any thread
        ReadAction.run<RuntimeException> {
            EditorFactory.getInstance().allEditors.forEach { editor ->
                val document = editor.document
                val file = fileDocumentManager.getFile(document)
                // Add listener for all .spec files, not just monitored ones
                // This ensures we catch changes even if currentFiles changes later
                if (file != null && file.name.endsWith(".spec") && !documentListeners.containsKey(document)) {
                    val documentListener = object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            // Check if file should be monitored at the time of change
                            val currentFile = FileDocumentManager.getInstance().getFile(document)
                            if (currentFile != null && shouldReloadForFile(currentFile)) {
                                scheduleReload(MindmapConstants.TYPING_RELOAD_DELAY_MS)
                            }
                        }
                    }
                    document.addDocumentListener(documentListener)
                    documentListeners[document] = documentListener
                }
            }
        }
    }

    fun startMonitoring() {
        setupFileChangeListener()
    }

    fun stopMonitoring() {
        fileListenerConnection?.disconnect()
        fileListenerConnection = null

        // Note: vfsListener is automatically removed when project is disposed
        // since it was added with project as disposable
        vfsListener = null

        documentListeners.forEach { (document, listener) ->
            document.removeDocumentListener(listener)
        }
        documentListeners.clear()

        reloadTimer?.stop()
        reloadTimer = null
    }

    private fun shouldReloadForFile(file: VirtualFile?): Boolean {
        if (file == null || !file.name.endsWith(".spec")) {
            return false
        }

        // If currentFiles is empty, monitor all .spec files (scanning entire project)
        if (currentFiles.isEmpty()) {
            return true
        }

        val filePath = file.path
        return currentFiles.any { currentFile ->
            if (currentFile.isDirectory) {
                // Check if file is within the directory
                val dirPath = currentFile.path
                filePath.startsWith(dirPath + "/") || filePath == dirPath
            } else {
                // Compare by path to handle file object recreation
                filePath == currentFile.path || file == currentFile
            }
        }
    }

    private fun scheduleReload(delayMs: Long = MindmapConstants.FILE_RELOAD_DELAY_MS) {
        // Stop existing timer to reset the delay
        // This ensures that when typing continuously, we only reload after user stops typing
        reloadTimer?.stop()
        reloadTimer = null

        // Create new timer with the specified delay
        // This implements debouncing: each keystroke resets the timer
        reloadTimer = javax.swing.Timer(delayMs.toInt()) {
            reloadTimer?.stop()
            reloadTimer = null

            // Trigger reload if not already pending
            // The reloadPending flag prevents multiple simultaneous reloads
            if (!reloadPending) {
                reloadPending = true
                SwingUtilities.invokeLater {
                    try {
                        onReload()
                    } finally {
                        reloadPending = false
                    }
                }
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun setupFileChangeListener() {
        // VFS listener
        vfsListener = object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                if (shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }

            override fun fileCreated(event: VirtualFileEvent) {
                if (shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }

            override fun fileDeleted(event: VirtualFileEvent) {
                if (shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }

            override fun propertyChanged(event: VirtualFilePropertyEvent) {
                if (event.propertyName == VirtualFile.PROP_NAME && shouldReloadForFile(event.file)) {
                    scheduleReload()
                }
            }
        }
        @Suppress("DEPRECATION")
        VirtualFileManager.getInstance().addVirtualFileListener(vfsListener!!, project)

        // FileDocumentManager listener
        fileListenerConnection = project.messageBus.connect()
        fileListenerConnection?.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun fileContentReloaded(file: VirtualFile, document: Document) {
                    if (shouldReloadForFile(file)) {
                        scheduleReload(100)
                    }
                }

                override fun fileWithNoDocumentChanged(file: VirtualFile) {
                    if (shouldReloadForFile(file)) {
                        scheduleReload(100)
                    }
                }

                override fun beforeDocumentSaving(document: Document) {}
                override fun beforeAllDocumentsSaving() {}
                override fun beforeFileContentReload(file: VirtualFile, document: Document) {}
            }
        )

        // Editor factory listener
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                    // Use ReadAction to safely access Document
                    ReadAction.run<RuntimeException> {
                        val editor = event.editor
                        val fileDocumentManager = FileDocumentManager.getInstance()
                        val file = fileDocumentManager.getFile(editor.document)

                        if (file != null && file.name.endsWith(".spec")) {
                            val document = editor.document
                            // Always try to add listener for .spec files
                            // updateDocumentListeners will clean up if file shouldn't be monitored
                            if (!documentListeners.containsKey(document)) {
                                val documentListener = object : DocumentListener {
                                    override fun documentChanged(event: DocumentEvent) {
                                        // Get file from document to ensure we have the latest file
                                        val currentFile = FileDocumentManager.getInstance().getFile(document)
                                        // Only trigger reload if file should be monitored
                                        if (currentFile != null && shouldReloadForFile(currentFile)) {
                                            scheduleReload(MindmapConstants.TYPING_RELOAD_DELAY_MS)
                                        }
                                    }
                                }
                                document.addDocumentListener(documentListener)
                                documentListeners[document] = documentListener
                            }
                        }
                    }
                }

                override fun editorReleased(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
                    val document = event.editor.document
                    documentListeners.remove(document)?.let {
                        document.removeDocumentListener(it)
                    }
                }
            },
            project
        )

        // Add listeners to existing editors
        updateDocumentListeners()
    }
}

