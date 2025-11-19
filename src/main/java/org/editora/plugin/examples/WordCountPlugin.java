package org.editora.plugin.examples;

import javafx.scene.control.*;
import org.editora.plugin.Plugin;

/**
 * Example plugin that adds word count functionality to the editor.
 * This demonstrates how to create a plugin for the Editora text editor.
 */
public class WordCountPlugin implements Plugin {
    
    private TextArea textArea;
    private Menu menu;
    
    @Override
    public String getName() {
        return "Word Count Plugin";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getDescription() {
        return "Provides word count and character statistics for the current document.";
    }
    
    @Override
    public void initialize(TextArea textArea) {
        this.textArea = textArea;
        
        // Create menu
        menu = new Menu("Statistics");
        
        MenuItem wordCountItem = new MenuItem("Word Count");
        wordCountItem.setOnAction(e -> showWordCount());
        
        MenuItem charCountItem = new MenuItem("Character Count");
        charCountItem.setOnAction(e -> showCharCount());
        
        MenuItem fullStatsItem = new MenuItem("Full Statistics");
        fullStatsItem.setOnAction(e -> showFullStats());
        
        menu.getItems().addAll(wordCountItem, charCountItem, fullStatsItem);
    }
    
    @Override
    public Menu getMenu() {
        return menu;
    }
    
    @Override
    public void shutdown() {
        // Cleanup if needed
    }
    
    private void showWordCount() {
        String text = textArea.getText();
        int wordCount = countWords(text);
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Word Count");
        alert.setHeaderText(null);
        alert.setContentText(String.format("Word count: %d", wordCount));
        alert.showAndWait();
    }
    
    private void showCharCount() {
        String text = textArea.getText();
        int charCount = text.length();
        int charCountNoSpaces = text.replaceAll("\\s", "").length();
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Character Count");
        alert.setHeaderText(null);
        alert.setContentText(String.format("Characters: %d\nCharacters (no spaces): %d", 
            charCount, charCountNoSpaces));
        alert.showAndWait();
    }
    
    private void showFullStats() {
        String text = textArea.getText();
        int wordCount = countWords(text);
        int charCount = text.length();
        int charCountNoSpaces = text.replaceAll("\\s", "").length();
        int lineCount = text.isEmpty() ? 0 : text.split("\n").length;
        int paragraphCount = text.isEmpty() ? 0 : text.split("\n\n").length;
        
        String stats = String.format(
            "Document Statistics:\n\n" +
            "Words: %d\n" +
            "Characters: %d\n" +
            "Characters (no spaces): %d\n" +
            "Lines: %d\n" +
            "Paragraphs: %d",
            wordCount, charCount, charCountNoSpaces, lineCount, paragraphCount
        );
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Full Statistics");
        alert.setHeaderText(null);
        alert.setContentText(stats);
        alert.showAndWait();
    }
    
    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        String[] words = text.trim().split("\\s+");
        return words.length;
    }
}
