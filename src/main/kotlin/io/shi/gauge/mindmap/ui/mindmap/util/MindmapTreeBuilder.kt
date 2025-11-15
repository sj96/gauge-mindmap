package io.shi.gauge.mindmap.ui.mindmap.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import io.shi.gauge.mindmap.model.Specification
import io.shi.gauge.mindmap.ui.mindmap.model.MindmapNode
import java.io.File

/**
 * Builds mindmap tree structure from specifications
 */
class MindmapTreeBuilder(private val project: Project) {

    fun buildTree(specifications: List<Specification>): MindmapNode {
        val baseDir = ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: return createEmptyRoot()
        val specsFolder = baseDir.findChild("specs") ?: baseDir
        val specsFolderPath = specsFolder.path

        // Group specs by folder path
        val specsByFolder = specifications.groupBy { getFolderPath(it, specsFolderPath, baseDir.path) }

        // Separate root specs and folder specs
        val rootSpecs = specsByFolder[""] ?: emptyList()
        val folderSpecs = specsByFolder.filterKeys { it.isNotEmpty() }

        // Create root-level spec nodes
        val rootSpecNodes = rootSpecs.map { createSpecNode(it, 1) }

        // Create folder nodes
        val folderNodes = if (folderSpecs.isNotEmpty()) {
            folderSpecs.map { (folderPath, folderSpecsList) ->
                if (folderPath.isEmpty()) return@map null

                val specNodes = folderSpecsList.map { createSpecNode(it, 2) }

                MindmapNode(
                    id = "folder_$folderPath",
                    text = folderPath,
                    level = 1,
                    children = specNodes,
                    tags = listOf("folder"),
                    data = folderPath
                )
            }.filterNotNull()
        } else {
            emptyList()
        }

        // Create root node
        return MindmapNode(
            id = "root",
            text = "Gauge",
            level = 0,
            children = rootSpecNodes + folderNodes,
            tags = listOf("root")
        )
    }

    private fun createEmptyRoot(): MindmapNode {
        return MindmapNode(
            id = "root",
            text = "Gauge",
            level = 0,
            children = emptyList(),
            tags = listOf("root")
        )
    }

    private fun getFolderPath(spec: Specification, specsFolderPath: String, projectRootPath: String): String {
        return try {
            val specFilePath = spec.filePath
            val specFile = File(specFilePath)
            val parentPath = specFile.parent ?: return ""

            val normalizedParentPath = File(parentPath).canonicalPath
            val normalizedSpecsFolderPath = File(specsFolderPath).canonicalPath

            if (normalizedParentPath == normalizedSpecsFolderPath) {
                return ""
            }

            if (!normalizedParentPath.startsWith(normalizedSpecsFolderPath)) {
                val normalizedProjectRootPath = File(projectRootPath).canonicalPath

                if (normalizedParentPath.startsWith(normalizedProjectRootPath)) {
                    val relativePath = normalizedParentPath.removePrefix(normalizedProjectRootPath)
                        .removePrefix(File.separator)
                        .removeSuffix(File.separator)

                    val pathParts = relativePath.split(File.separator).filter { it.isNotEmpty() }
                    return when {
                        pathParts.isEmpty() -> ""
                        pathParts.size == 1 -> pathParts[0]
                        else -> pathParts.takeLast(2).joinToString("/")
                    }
                }
                return ""
            }

            val relativePath = normalizedParentPath.removePrefix(normalizedSpecsFolderPath)
                .removePrefix(File.separator)
                .removeSuffix(File.separator)

            val pathParts = relativePath.split(File.separator).filter { it.isNotEmpty() }

            when {
                pathParts.isEmpty() -> ""
                pathParts.size == 1 -> pathParts[0]
                else -> pathParts.takeLast(2).joinToString("/")
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun createSpecNode(spec: Specification, specLevel: Int): MindmapNode {
        return MindmapNode(
            id = "spec_${spec.filePath}",
            text = spec.name,
            level = specLevel,
            children = spec.scenarios.map { scenario ->
                MindmapNode(
                    id = "scenario_${spec.filePath}_${scenario.lineNumber}",
                    text = scenario.name,
                    level = specLevel + 1,
                    tags = listOf("scenario"),
                    data = scenario
                )
            },
            tags = listOf("spec"),
            data = spec
        )
    }

    fun filterTree(node: MindmapNode, filterText: String): MindmapNode? {
        if (filterText.isBlank()) return node

        val matchesFilter = node.text.contains(filterText, ignoreCase = true) ||
                node.tags.any { it.contains(filterText, ignoreCase = true) }

        val filteredChildren = node.children.mapNotNull { child ->
            filterTree(child, filterText)
        }

        return if (matchesFilter || filteredChildren.isNotEmpty()) {
            node.copy(children = filteredChildren)
        } else {
            null
        }
    }
}

