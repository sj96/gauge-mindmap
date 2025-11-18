# Gauge MindMap Plugin

An IntelliJ IDEA plugin that visualizes Gauge specification files as interactive mindmaps.

## Overview

Gauge MindMap transforms your Gauge test specifications into beautiful, interactive mindmap visualizations. Navigate and understand your test specifications at a glance with an intuitive visual representation.

## Features

### 🗺️ Interactive Mindmap Visualization
- Visualize Gauge specifications as hierarchical mindmaps
- Smooth, responsive rendering with optimized performance
- Color-coded nodes for different specification levels
- Curved connection lines for better visual flow

### 🎮 Navigation & Interaction
- **Pan & Zoom**: Navigate large mindmaps with mouse drag and zoom controls
- **Minimap**: Quick overview and navigation with an interactive minimap overlay
- **Node Interaction**: Hover, select, and click nodes to explore specifications
- **Collapse/Expand**: Collapse or expand nodes to focus on specific areas
- **Search & Filter**: Filter mindmap content by text to find specific specifications

### 📤 Export & Sharing
- Export mindmap as high-quality PNG image
- Customizable export settings (scale, padding, quality)

### 🔄 Auto-refresh
- Automatically updates when specification files change
- Real-time file monitoring for seamless workflow

### 🔌 IDE Integration
- Tool window integration (View → Tool Windows → Gauge Mindmap)
- Context menu action: Right-click files/folders in Project View → "Show Gauge Mindmap"
- Seamless integration with IntelliJ IDEA UI

## Installation

1. Open IntelliJ IDEA
2. Go to **File → Settings → Plugins** (or **Preferences → Plugins** on macOS)
3. Search for "Gauge MindMap"
4. Click **Install**
5. Restart IntelliJ IDEA

## Usage

### Opening the Mindmap View

**Method 1: Tool Window**
- Go to **View → Tool Windows → Gauge Mindmap**
- The mindmap tool window will appear at the bottom of the IDE

**Method 2: Context Menu**
- Right-click on Gauge specification files or folders in the Project View
- Select **"Show Gauge Mindmap"**
- The tool window will open and display the mindmap for selected files

### Navigation Controls

- **Pan**: Click and drag to move around the mindmap
- **Zoom**: Use mouse wheel or zoom controls in the toolbar
- **Minimap**: Click and drag the viewport rectangle in the minimap to navigate
- **Node Interaction**:
  - Hover over nodes to highlight them
  - Click nodes to select them
  - Double-click nodes to navigate to the source file
  - Click collapse/expand indicators to toggle node visibility

### Toolbar Actions

- **Refresh**: Reload specifications from current files
- **Export**: Export mindmap as PNG image
- **Filter**: Search and filter mindmap content
- **Collapse All**: Collapse all specification nodes
- **Expand All**: Expand all specification nodes

## Requirements

- IntelliJ IDEA 2025.1.4.1 or later
- Java 21
- Gauge project with specification files (.spec files)

## Performance

The plugin is optimized for performance:
- Font and text size caching
- Viewport culling for large mindmaps
- Efficient rendering with minimal repaints
- Optimized for macOS Retina displays

## Technical Details

- Built with Kotlin
- Uses Swing/AWT for rendering
- Modular architecture with separation of concerns
- Game-style API for node rendering and interaction

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This plugin is open source. See LICENSE file for details.

## Support

For issues, feature requests, or questions:
- Open an issue on GitHub: https://github.com/sj96/gauge-plugin
- Contact the vendor: https://github.com/sj96

## Changelog

### Version 1.0.6

- Add Plugin Icon

### Version 1.0.5

- Performance optimizations: Font and text size caching, viewport culling improvements
- Fixed minimap rendering issues on macOS Retina displays
- Improved error handling and transform management
- Enhanced code organization and maintainability
- Added MIT License

### Version 1.0.4
- Performance optimizations: Font and text size caching, viewport culling improvements
- Fixed minimap rendering issues on macOS Retina displays
- Improved error handling and transform management
- Enhanced code organization and maintainability

### Version 1.0.3
- Added minimap navigation with interactive viewport rectangle
- Improved rendering performance with viewport culling
- Enhanced node interaction (hover, selection, collapse/expand)
- Added export to PNG image functionality
- File monitoring for automatic updates

### Version 1.0.0
- Initial release
- Interactive mindmap visualization of Gauge specifications
- Pan and zoom navigation
- Search and filter capabilities
- Context menu integration

