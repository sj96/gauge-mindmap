import javafx.application.Application;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public class MindmapViewer extends Application {
    
    // Layout constants
    private static final double HORIZONTAL_SPACING = 200.0; // Distance from parent to children
    private static final double VERTICAL_SPACING = 60.0;   // Vertical spacing between siblings
    private static final double NODE_PADDING = 10.0;       // Padding inside node
    private static final double NODE_MIN_WIDTH = 120.0;    // Minimum node width
    
    // Colors
    private static final Color BACKGROUND_COLOR = Color.web("#1e1e1e");
    private static final Color TEXT_COLOR = Color.web("#ffffff");
    private static final Color CONNECTION_COLOR = Color.web("#888888");
    private static final Color NODE_BG_COLOR = Color.web("#2d2d2d");
    private static final Color NODE_BORDER_COLOR = Color.web("#444444");
    
    // Data structure for tree node
    static class TreeNode {
        String text;
        List<TreeNode> children;
        
        TreeNode(String text) {
            this.text = text;
            this.children = new ArrayList<>();
        }
        
        TreeNode addChild(String text) {
            TreeNode child = new TreeNode(text);
            children.add(child);
            return child;
        }
    }
    
    // Data class to store node position and size
    static class NodeLayout {
        TreeNode node;
        double x, y;
        double width, height;
        List<NodeLayout> children;
        
        NodeLayout(TreeNode node, double x, double y, double width, double height) {
            this.node = node;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.children = new ArrayList<>();
        }
    }
    
    @Override
    public void start(Stage primaryStage) {
        // Create root group
        Group root = new Group();
        
        // Create sample mindmap data
        TreeNode rootNode = createSampleMindmap();
        
        // Calculate layout
        NodeLayout rootLayout = calculateLayout(rootNode, 50, 50);
        
        // Draw connections first (behind nodes)
        drawConnections(root, rootLayout);
        
        // Draw nodes (on top)
        drawNodes(root, rootLayout);
        
        // Create scene
        Scene scene = new Scene(root, 1400, 900, BACKGROUND_COLOR);
        primaryStage.setTitle("Mindmap Viewer");
        primaryStage.setScene(scene);
        primaryStage.show();
    }
    
    // Create sample mindmap data structure
    private TreeNode createSampleMindmap() {
        TreeNode root = new TreeNode("markmap");
        
        // Links branch
        TreeNode links = root.addChild("Links");
        links.addChild("Website");
        links.addChild("GitHub");
        
        // Related Projects branch
        TreeNode relatedProjects = root.addChild("Related Projects");
        relatedProjects.addChild("coc-markmap for Neovim");
        relatedProjects.addChild("markmap-vscode for VSCode");
        relatedProjects.addChild("eaf-markmap for Emacs");
        
        // Features branch
        TreeNode features = root.addChild("Features");
        
        // Lists under Features
        TreeNode lists = features.addChild("Lists");
        lists.addChild("strong / italic / highlight");
        lists.addChild("inline code");
        lists.addChild("checkbox");
        lists.addChild("Katex formula");
        lists.addChild("Ordered list");
        
        // Blocks under Features
        TreeNode blocks = features.addChild("Blocks");
        blocks.addChild("console.log('hello, JavaScript')");
        blocks.addChild("Products table");
        
        return root;
    }
    
    // Recursive function to calculate layout
    private NodeLayout calculateLayout(TreeNode node, double startX, double startY) {
        // Calculate node size based on text
        double nodeWidth = Math.max(NODE_MIN_WIDTH, node.text.length() * 8 + NODE_PADDING * 2);
        double nodeHeight = 40.0;
        
        // Create layout for this node
        NodeLayout layout = new NodeLayout(node, startX, startY, nodeWidth, nodeHeight);
        
        if (node.children.isEmpty()) {
            return layout;
        }
        
        // First, calculate all children layouts recursively
        List<NodeLayout> childLayouts = new ArrayList<>();
        for (TreeNode child : node.children) {
            // Temporary position, will be adjusted later
            NodeLayout childLayout = calculateLayout(child, 
                startX + HORIZONTAL_SPACING, 
                0);
            childLayouts.add(childLayout);
        }
        
        // Calculate total height needed for all children
        double totalChildrenHeight = 0;
        for (NodeLayout childLayout : childLayouts) {
            totalChildrenHeight += childLayout.height;
        }
        totalChildrenHeight += (childLayouts.size() - 1) * VERTICAL_SPACING;
        
        // Center children vertically relative to parent
        double childrenStartY = startY - totalChildrenHeight / 2 + nodeHeight / 2;
        
        // Adjust children positions
        double currentY = childrenStartY;
        for (NodeLayout childLayout : childLayouts) {
            childLayout.y = currentY;
            childLayout.x = startX + HORIZONTAL_SPACING;
            layout.children.add(childLayout);
            currentY += childLayout.height + VERTICAL_SPACING;
        }
        
        return layout;
    }
    
    // Recursive function to draw connections
    private void drawConnections(Group root, NodeLayout layout) {
        if (layout.children.isEmpty()) {
            return;
        }
        
        // Draw connections from parent to each child
        double parentRightX = layout.x + layout.width;
        double parentCenterY = layout.y + layout.height / 2;
        
        for (NodeLayout childLayout : layout.children) {
            double childLeftX = childLayout.x;
            double childCenterY = childLayout.y + childLayout.height / 2;
            
            // Draw smooth curved line
            CubicCurve curve = new CubicCurve();
            curve.setStartX(parentRightX);
            curve.setStartY(parentCenterY);
            curve.setControlX1(parentRightX + (childLeftX - parentRightX) * 0.3);
            curve.setControlY1(parentCenterY);
            curve.setControlX2(parentRightX + (childLeftX - parentRightX) * 0.7);
            curve.setControlY2(childCenterY);
            curve.setEndX(childLeftX);
            curve.setEndY(childCenterY);
            curve.setStroke(CONNECTION_COLOR);
            curve.setStrokeWidth(2.0);
            curve.setFill(Color.TRANSPARENT);
            
            root.getChildren().add(curve);
            
            // Recursively draw connections for children
            drawConnections(root, childLayout);
        }
    }
    
    // Recursive function to draw nodes
    private void drawNodes(Group root, NodeLayout layout) {
        // Create label for node
        Label label = new Label(layout.node.text);
        label.setLayoutX(layout.x);
        label.setLayoutY(layout.y);
        label.setPrefWidth(layout.width);
        label.setPrefHeight(layout.height);
        label.setStyle(
            "-fx-background-color: #2d2d2d; " +
            "-fx-background-radius: 5; " +
            "-fx-border-color: #444444; " +
            "-fx-border-radius: 5; " +
            "-fx-border-width: 1; " +
            "-fx-text-fill: #ffffff; " +
            "-fx-font-family: 'Segoe UI', Arial, sans-serif; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 8px; " +
            "-fx-alignment: center-left;"
        );
        
        root.getChildren().add(label);
        
        // Recursively draw children
        for (NodeLayout childLayout : layout.children) {
            drawNodes(root, childLayout);
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}

