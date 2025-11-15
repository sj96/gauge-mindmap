plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "io.shi"
version = "1.0.4-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            <h3>Version 1.0.4</h3>
            <ul>
                <li>Performance optimizations: Font and text size caching, viewport culling improvements</li>
                <li>Fixed minimap rendering issues on macOS Retina displays</li>
                <li>Improved error handling and transform management</li>
                <li>Enhanced code organization and maintainability</li>
            </ul>
            
            <h3>Version 1.0.3</h3>
            <ul>
                <li>Added minimap navigation with interactive viewport rectangle</li>
                <li>Improved rendering performance with viewport culling</li>
                <li>Enhanced node interaction (hover, selection, collapse/expand)</li>
                <li>Added export to PNG image functionality</li>
                <li>File monitoring for automatic updates</li>
            </ul>
            
            <h3>Version 1.0.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Interactive mindmap visualization of Gauge specifications</li>
                <li>Pan and zoom navigation</li>
                <li>Search and filter capabilities</li>
                <li>Context menu integration</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
