package io.shi.gauge.mindmap.service

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
        for (child in directory.children) {
            if (child.isDirectory) {
                scanDirectoryRecursive(child, specifications)
            } else if (child.name.endsWith(".spec")) {
                parseSpecFile(child)?.let { specifications.add(it) }
            }
        }
    }

    private fun parseSpecFile(file: VirtualFile): Specification? {
        try {
            val inputStream = file.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            var specName: String? = null
            val scenarios = mutableListOf<Scenario>()
            var lineNumber = 0

            reader.useLines { lines ->
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

