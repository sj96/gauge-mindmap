package io.shi.gauge.mindmap.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import io.shi.gauge.mindmap.model.Scenario
import io.shi.gauge.mindmap.model.Specification
import java.io.BufferedReader
import java.io.InputStreamReader

class GaugeSpecScanner(private val project: Project) {

    fun scanProject(): List<Specification> {
        val root = ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: return emptyList()
        return scanDirectory(root)
    }

    fun scanDirectory(directory: VirtualFile): List<Specification> {
        val specifications = mutableListOf<Specification>()
        scanDirectoryRecursive(directory, specifications)
        return specifications
    }

    fun scanFile(file: VirtualFile): List<Specification> {
        val specifications = mutableListOf<Specification>()
        if (file.name.endsWith(".spec")) {
            parseSpecFile(file)?.let { specifications.add(it) }
        }
        return specifications
    }

    private fun scanDirectoryRecursive(directory: VirtualFile, specifications: MutableList<Specification>) {
        // Use iterative approach with stack to avoid stack overflow on deeply nested directories
        val stack = ArrayDeque<VirtualFile>()
        val visited = mutableSetOf<VirtualFile>()

        stack.addLast(directory)
        visited.add(directory)

        while (stack.isNotEmpty()) {
            val currentDir = stack.removeLast()

            try {
                for (child in currentDir.children) {
                    // Prevent circular references and already visited directories
                    if (child.isDirectory) {
                        if (!visited.contains(child)) {
                            visited.add(child)
                            stack.addLast(child)
                        }
                    } else if (child.name.endsWith(".spec")) {
                        parseSpecFile(child)?.let { specifications.add(it) }
                    }
                }
            } catch (_: Exception) {
                // Skip directories that can't be accessed (permissions, etc.)
                continue
            }
        }
    }

    private fun parseSpecFile(file: VirtualFile): Specification? {
        try {
            // Try to get content from Document first (if file is open in editor)
            // This ensures we get the latest unsaved changes
            // Must wrap in ReadAction when accessing Document from background thread
            val fileDocumentManager = FileDocumentManager.getInstance()
            val document = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
                fileDocumentManager.getDocument(file)
            }

            val lines: Sequence<String> = if (document != null) {
                // Use document content (includes unsaved changes)
                // Access document.text inside ReadAction
                ReadAction.compute<Sequence<String>, RuntimeException> {
                    document.text.splitToSequence("\n")
                }
            } else {
                // Fall back to file content
                file.refresh(false, false)
                val inputStream = file.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                reader.useLines { it }
            }

            var specName: String? = null
            val scenarios = mutableListOf<Scenario>()
            var lineNumber = 0

            lines.forEach { line ->
                lineNumber++
                val trimmed = line.trim()

                // Specification name starts with single #
                if (trimmed.startsWith("#") && !trimmed.startsWith("##")) {
                    specName = trimmed.removePrefix("#").trim()
                }
                // Scenario name starts with ##
                else if (trimmed.startsWith("##")) {
                    val scenarioName = trimmed.removePrefix("##").trim()
                    if (scenarioName.isNotEmpty()) {
                        scenarios.add(Scenario(scenarioName, lineNumber))
                    }
                }
            }

            return if (specName != null) {
                Specification(
                    name = specName,
                    filePath = file.path,
                    scenarios = scenarios
                )
            } else {
                null
            }
        } catch (_: Exception) {
            // Log error if needed
            return null
        }
    }
}

