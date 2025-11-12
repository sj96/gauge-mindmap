# Mindmap Viewer - JavaFX Application

Chương trình JavaFX hiển thị mindmap dạng cây với cấu trúc như Markmap.

## Yêu cầu

- Java 11 hoặc cao hơn
- JavaFX SDK (nếu dùng Java 11+)

## Cách chạy

### Với Java 8-10 (JavaFX được tích hợp sẵn):
```bash
javac MindmapViewer.java
java MindmapViewer
```

### Với Java 11+ (cần JavaFX riêng):

1. Tải JavaFX SDK từ: https://openjfx.io/

2. Compile với module path:
```bash
javac --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls MindmapViewer.java
```

3. Chạy với module path:
```bash
java --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls MindmapViewer
```

### Hoặc sử dụng Maven/Gradle:

**Maven (pom.xml):**
```xml
<dependencies>
    <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-controls</artifactId>
        <version>17.0.2</version>
    </dependency>
</dependencies>
```

**Gradle (build.gradle):**
```gradle
dependencies {
    implementation 'org.openjfx:javafx-controls:17.0.2'
}
```

## Tính năng

- ✅ Hiển thị mindmap dạng cây từ trái sang phải
- ✅ Node màu trắng trên nền đen (#1e1e1e)
- ✅ Đường nối cong mềm mại màu xám (#888888)
- ✅ Layout tự động tính toán
- ✅ Font dễ đọc (Segoe UI / Arial)
- ✅ Hàm đệ quy để hiển thị cây

## Cấu trúc dữ liệu

Chương trình sử dụng class `TreeNode` để biểu diễn cây:
- `text`: Nội dung của node
- `children`: Danh sách các node con

## Tùy chỉnh

Bạn có thể thay đổi các hằng số trong code:
- `HORIZONTAL_SPACING`: Khoảng cách ngang giữa parent và child (mặc định: 200px)
- `VERTICAL_SPACING`: Khoảng cách dọc giữa các sibling (mặc định: 60px)
- `NODE_PADDING`: Padding bên trong node (mặc định: 10px)
- `NODE_MIN_WIDTH`: Chiều rộng tối thiểu của node (mặc định: 120px)

## Ví dụ dữ liệu

Chương trình đã có sẵn dữ liệu mẫu:
```
markmap
├── Links
│   ├── Website
│   └── GitHub
├── Related Projects
│   ├── coc-markmap for Neovim
│   ├── markmap-vscode for VSCode
│   └── eaf-markmap for Emacs
└── Features
    ├── Lists
    │   ├── strong / italic / highlight
    │   ├── inline code
    │   ├── checkbox
    │   ├── Katex formula
    │   └── Ordered list
    └── Blocks
        ├── console.log('hello, JavaScript')
        └── Products table
```

Bạn có thể thay đổi hàm `createSampleMindmap()` để tạo mindmap của riêng mình.

