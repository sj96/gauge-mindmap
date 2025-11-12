package io.shi.gauge.mindmap.model

data class Specification(
    val name: String,
    val filePath: String,
    val scenarios: List<Scenario> = emptyList()
)

data class Scenario(
    val name: String,
    val lineNumber: Int
)

