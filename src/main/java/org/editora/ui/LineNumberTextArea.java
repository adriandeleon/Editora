package org.editora.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

/**
 * A TextArea with line numbers displayed on the left side.
 * Line numbers automatically update and match the font size of the text area.
 */
public class LineNumberTextArea extends BorderPane {

    private final TextArea textArea;
    private final VBox lineNumberPane;
    private final ScrollPane lineNumberScroll;

    private int previousLineCount = 0;

    public LineNumberTextArea() {
        this.textArea = new TextArea();
        this.lineNumberPane = new VBox();
        this.lineNumberScroll = new ScrollPane(lineNumberPane);

        setupLineNumbers();
        setupLayout();
        setupListeners();

        // Initial update
        updateLineNumbers();
    }

    private void setupLineNumbers() {
        lineNumberPane.setAlignment(Pos.TOP_RIGHT);
        lineNumberPane.setPadding(new Insets(8, 10, 5, 10));
        lineNumberPane.setStyle("-fx-background-color: #f5f5f5;");
        lineNumberPane.setMinWidth(50);
        lineNumberPane.setPrefWidth(50);

        // Configure scroll pane for line numbers
        lineNumberScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lineNumberScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lineNumberScroll.setFitToWidth(true);
        lineNumberScroll.setFitToHeight(true);
        lineNumberScroll.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #cccccc; -fx-border-width: 0 1 0 0;");
        lineNumberScroll.setPrefWidth(60);
        lineNumberScroll.setMinWidth(60);
        lineNumberScroll.setMaxWidth(60);

        // Disable mouse scrolling on line number scroll pane
        lineNumberScroll.setOnScroll(event -> {
            // Redirect scroll events to text area
            textArea.fireEvent(event);
        });
    }

    private void setupLayout() {
        setLeft(lineNumberScroll);
        setCenter(textArea);
    }

    private void setupListeners() {
        // Update line numbers when text changes
        textArea.textProperty().addListener((obs, oldVal, newVal) -> updateLineNumbers());

        // Sync scrolling - use scrollTop property
        textArea.scrollTopProperty().addListener((obs, oldVal, newVal) -> {
            syncLineNumberScroll();
        });

        // Update when font changes
        textArea.fontProperty().addListener((obs, oldVal, newVal) -> {
            updateLineNumbers();
            updateLineNumberFont();
        });

        // Update on style changes (font size might be in style)
        textArea.styleProperty().addListener((obs, oldVal, newVal) -> {
            updateLineNumbers();
            updateLineNumberFont();
        });
    }

    private void syncLineNumberScroll() {
        // Use Platform.runLater to ensure layout is updated
        Platform.runLater(() -> {
            double scrollTop = textArea.getScrollTop();
            double lineHeight = getLineHeight();

            // Calculate how many lines have been scrolled
            double scrolledLines = scrollTop / lineHeight;

            // Set the line number scroll position
            double lineNumberScrollValue = scrolledLines * lineHeight;
            double maxScrollValue = lineNumberPane.getHeight() - lineNumberScroll.getViewportBounds().getHeight();

            if (maxScrollValue > 0) {
                lineNumberScroll.setVvalue(lineNumberScrollValue / maxScrollValue);
            }
        });
    }
    
    private void updateLineNumbers() {
        String text = textArea.getText();
        int lineCount = text.isEmpty() ? 1 : (int) text.chars().filter(ch -> ch == '\n').count() + 1;
        
        // Only update if line count changed
        if (lineCount != previousLineCount) {
            lineNumberPane.getChildren().clear();
            
            for (int i = 1; i <= lineCount; i++) {
                Label lineLabel = new Label(String.valueOf(i));
                lineLabel.setAlignment(Pos.CENTER_RIGHT);
                lineLabel.setPrefWidth(40);
                lineLabel.setStyle("-fx-text-fill: #666666;");
                
                // Match the line height
                double lineHeight = getLineHeight();
                lineLabel.setPrefHeight(lineHeight);
                lineLabel.setMinHeight(lineHeight);
                lineLabel.setMaxHeight(lineHeight);
                
                // Match font
                updateLabelFont(lineLabel);
                
                lineNumberPane.getChildren().add(lineLabel);
            }
            
            previousLineCount = lineCount;
        }
    }
    
    private void updateLineNumberFont() {
        for (var node : lineNumberPane.getChildren()) {
            if (node instanceof Label label) {
                updateLabelFont(label);
            }
        }
    }
    
    private void updateLabelFont(Label label) {
        // Extract font from text area
        Font textAreaFont = textArea.getFont();
        
        // Check if style contains font-size
        String style = textArea.getStyle();
        if (style != null && style.contains("-fx-font-size")) {
            // Extract font size from style
            String[] parts = style.split(";");
            for (String part : parts) {
                if (part.contains("-fx-font-size")) {
                    String fontSize = part.split(":")[1].trim();
                    label.setStyle("-fx-text-fill: #666666; -fx-font-size: " + fontSize + ";");
                    return;
                }
            }
        }
        
        // Fallback to font property
        if (textAreaFont != null) {
            label.setFont(Font.font(textAreaFont.getFamily(), textAreaFont.getSize()));
        }
    }
    
    private double getLineHeight() {
        // Approximate line height based on font size
        Font font = textArea.getFont();
        double fontSize = 14; // default
        
        if (font != null) {
            fontSize = font.getSize();
        }
        
        // Check style for font size
        String style = textArea.getStyle();
        if (style != null && style.contains("-fx-font-size")) {
            try {
                String[] parts = style.split(";");
                for (String part : parts) {
                    if (part.contains("-fx-font-size")) {
                        String fontSizeStr = part.split(":")[1].trim().replace("px", "").replace("pt", "");
                        fontSize = Double.parseDouble(fontSizeStr);
                        break;
                    }
                }
            } catch (Exception e) {
                // Use default if parsing fails
            }
        }
        
        // Line height is typically ~1.3 to 1.5 times font size
        return fontSize * 1.4;
    }
    
    /**
     * Gets the TextArea component.
     * @return The text area
     */
    public TextArea getTextArea() {
        return textArea;
    }
    
    /**
     * Shows or hides the line numbers.
     * @param show true to show, false to hide
     */
    public void setShowLineNumbers(boolean show) {
        lineNumberPane.setVisible(show);
        lineNumberPane.setManaged(show);
    }
    
    /**
     * Checks if line numbers are currently shown.
     * @return true if shown, false otherwise
     */
    public boolean isShowingLineNumbers() {
        return lineNumberPane.isVisible();
    }
}
