package io.shi.gauge.mindmap.ui.mindmap

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
        if (file == null || !file.name.endsWith(".spec") || currentFiles.isEmpty()) {
            return false
        }

        return currentFiles.any { currentFile ->
            if (currentFile.isDirectory) {
                var parent = file.parent
                while (parent != null) {
                    if (parent == currentFile) {
                        return@any true
                    }
                    parent = parent.parent
                }
                false
            } else {
                file == currentFile
            }
        }
    }

    private fun scheduleReload(delayMs: Long = MindmapConstants.FILE_RELOAD_DELAY_MS) {
        reloadTimer?.stop()

        reloadTimer = javax.swing.Timer(delayMs.toInt()) {
            if (!reloadPending) {
                reloadPending = true
                SwingUtilities.invokeLater {
                    reloadPending = false
                    onReload()
                }
            }
            reloadTimer?.stop()
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
                    val editor = event.editor
                    val file = FileDocumentManager.getInstance().getFile(editor.document)

                    if (shouldReloadForFile(file)) {
                        val documentListener = object : DocumentListener {
                            override fun documentChanged(event: DocumentEvent) {
                                scheduleReload(MindmapConstants.TYPING_RELOAD_DELAY_MS)
                            }
                        }
                        editor.document.addDocumentListener(documentListener)
                        documentListeners[editor.document] = documentListener
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
        val fileDocumentManager = FileDocumentManager.getInstance()
        EditorFactory.getInstance().allEditors.forEach { editor ->
            val document = editor.document
            val file = fileDocumentManager.getFile(document)
            if (shouldReloadForFile(file) && !documentListeners.containsKey(document)) {
                val documentListener = object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        scheduleReload(MindmapConstants.TYPING_RELOAD_DELAY_MS)
                    }
                }
                document.addDocumentListener(documentListener)
                documentListeners[document] = documentListener
            }
        }
    }
}

