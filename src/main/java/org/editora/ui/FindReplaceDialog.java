package org.editora.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find and Replace dialog for text editor.
 * Supports case-sensitive search, whole word matching, and regex patterns.
 * Works with both TextArea and CodeArea.
 */
public class FindReplaceDialog extends Stage {

    private final Object textControl; // Can be TextArea or CodeArea
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox caseSensitiveCheck = new CheckBox("Case sensitive");
    private final CheckBox wholeWordCheck = new CheckBox("Whole word");
    private final CheckBox regexCheck = new CheckBox("Regular expression");
    private final Label statusLabel = new Label("");

    private int currentMatchIndex = -1;
    private int lastSearchStart = 0;

    public FindReplaceDialog(TextArea textArea) {
        this.textControl = textArea;
        initialize();
    }

    public FindReplaceDialog(CodeArea codeArea) {
        this.textControl = codeArea;
        initialize();
    }

    private void initialize() {
        setTitle("Find and Replace");
        initModality(Modality.NONE);
        setResizable(false);

        VBox root = createLayout();
        
        Scene scene = new Scene(root);
        setScene(scene);
        
        setupEventHandlers();
    }
    
    private VBox createLayout() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        // Configure fields
        findField.setPromptText("Find text...");
        findField.setPrefWidth(300);
        replaceField.setPromptText("Replace with...");
        replaceField.setPrefWidth(300);
        statusLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");

        // Find section
        Label findLabel = new Label("Find:");
        HBox findBox = new HBox(5, findLabel, findField);
        findBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(findField, Priority.ALWAYS);

        // Replace section
        Label replaceLabel = new Label("Replace:");
        HBox replaceBox = new HBox(5, replaceLabel, replaceField);
        replaceBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        
        // Align labels
        findLabel.setMinWidth(60);
        replaceLabel.setMinWidth(60);
        
        // Options
        HBox optionsBox = new HBox(15, caseSensitiveCheck, wholeWordCheck, regexCheck);
        optionsBox.setPadding(new Insets(5, 0, 5, 0));
        
        // Buttons
        HBox buttonBox = createButtonBox();
        
        // Status
        HBox statusBox = new HBox(statusLabel);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        statusBox.setPadding(new Insets(5, 0, 0, 0));
        
        root.getChildren().addAll(findBox, replaceBox, optionsBox, buttonBox, statusBox);
        
        return root;
    }
    
    private HBox createButtonBox() {
        Button findNextBtn = new Button("Find Next");
        findNextBtn.setGraphic(new FontIcon(MaterialDesignA.ARROW_DOWN));
        findNextBtn.setOnAction(e -> findNext());
        findNextBtn.setPrefWidth(110);
        
        Button findPrevBtn = new Button("Find Previous");
        findPrevBtn.setGraphic(new FontIcon(MaterialDesignA.ARROW_UP));
        findPrevBtn.setOnAction(e -> findPrevious());
        findPrevBtn.setPrefWidth(130);
        
        Button replaceBtn = new Button("Replace");
        replaceBtn.setGraphic(new FontIcon(MaterialDesignS.SELECT_SEARCH));
        replaceBtn.setOnAction(e -> replace());
        replaceBtn.setPrefWidth(100);
        
        Button replaceAllBtn = new Button("Replace All");
        replaceAllBtn.setGraphic(new FontIcon(MaterialDesignS.SELECT_SEARCH));
        replaceAllBtn.setOnAction(e -> replaceAll());
        replaceAllBtn.setPrefWidth(110);
        
        Button closeBtn = new Button("Close");
        closeBtn.setGraphic(new FontIcon(MaterialDesignC.CLOSE));
        closeBtn.setOnAction(e -> close());
        closeBtn.setPrefWidth(80);
        
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        buttonBox.getChildren().addAll(findNextBtn, findPrevBtn, replaceBtn, replaceAllBtn, closeBtn);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));
        
        return buttonBox;
    }
    
    private void setupEventHandlers() {
        // Enter in find field triggers find next
        findField.setOnAction(e -> findNext());
        
        // Enter in replace field triggers replace
        replaceField.setOnAction(e -> replace());
        
        // Reset search when find text changes
        findField.textProperty().addListener((obs, oldVal, newVal) -> {
            currentMatchIndex = -1;
            lastSearchStart = 0;
            statusLabel.setText("");
        });
    }
    
    private void findNext() {
        String searchText = findField.getText();
        if (searchText.isEmpty()) {
            showStatus("Please enter text to find", true);
            return;
        }

        String text = getText();
        int startPos = getCaretPosition();

        // If we have a current selection that matches, start after it
        if (getSelectedText().length() > 0) {
            startPos = getSelectionEnd();
        }

        int foundIndex = findInText(text, searchText, startPos, true);

        if (foundIndex >= 0) {
            selectMatch(foundIndex, searchText);
            showStatus("Found", false);
        } else {
            // Wrap around to beginning
            foundIndex = findInText(text, searchText, 0, true);
            if (foundIndex >= 0) {
                selectMatch(foundIndex, searchText);
                showStatus("Found (wrapped to beginning)", false);
            } else {
                showStatus("No matches found", true);
            }
        }
    }
    
    private void findPrevious() {
        String searchText = findField.getText();
        if (searchText.isEmpty()) {
            showStatus("Please enter text to find", true);
            return;
        }

        String text = getText();
        int startPos = getCaretPosition();

        // If we have a current selection that matches, start before it
        if (getSelectedText().length() > 0) {
            startPos = getSelectionStart() - 1;
        }

        if (startPos < 0) startPos = text.length();

        int foundIndex = findInText(text, searchText, startPos, false);

        if (foundIndex >= 0) {
            selectMatch(foundIndex, searchText);
            showStatus("Found", false);
        } else {
            // Wrap around to end
            foundIndex = findInText(text, searchText, text.length(), false);
            if (foundIndex >= 0) {
                selectMatch(foundIndex, searchText);
                showStatus("Found (wrapped to end)", false);
            } else {
                showStatus("No matches found", true);
            }
        }
    }
    
    private void replace() {
        String searchText = findField.getText();
        String replaceText = replaceField.getText();

        if (searchText.isEmpty()) {
            showStatus("Please enter text to find", true);
            return;
        }

        // Check if current selection matches the search text
        String selectedText = getSelectedText();
        if (matches(selectedText, searchText)) {
            int start = getSelectionStart();
            int end = getSelectionEnd();

            replaceText(start, end, replaceText);
            positionCaret(start + replaceText.length());

            showStatus("Replaced 1 occurrence", false);

            // Find next occurrence
            findNext();
        } else {
            // No selection or doesn't match, find next
            findNext();
        }
    }
    
    private void replaceAll() {
        String searchText = findField.getText();
        String replaceText = replaceField.getText();

        if (searchText.isEmpty()) {
            showStatus("Please enter text to find", true);
            return;
        }

        String text = getText();
        int count = 0;
        int pos = 0;

        StringBuilder newText = new StringBuilder();

        while (pos < text.length()) {
            int foundIndex = findInText(text, searchText, pos, true);

            if (foundIndex < 0) {
                // No more matches, append rest of text
                newText.append(text.substring(pos));
                break;
            }

            // Append text before match
            newText.append(text.substring(pos, foundIndex));

            // Append replacement
            newText.append(replaceText);

            // Move position past the match
            int matchLength = getMatchLength(text, searchText, foundIndex);
            pos = foundIndex + matchLength;
            count++;
        }

        if (count > 0) {
            setText(newText.toString());
            showStatus("Replaced " + count + " occurrence(s)", false);
        } else {
            showStatus("No matches found", true);
        }
    }
    
    private int findInText(String text, String searchText, int startPos, boolean forward) {
        try {
            if (regexCheck.isSelected()) {
                // Regex search
                int flags = caseSensitiveCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(searchText, flags);
                
                if (forward) {
                    Matcher matcher = pattern.matcher(text);
                    if (matcher.find(startPos)) {
                        return matcher.start();
                    }
                } else {
                    // Search backwards
                    Matcher matcher = pattern.matcher(text.substring(0, startPos + 1));
                    int lastIndex = -1;
                    while (matcher.find()) {
                        lastIndex = matcher.start();
                    }
                    return lastIndex;
                }
            } else {
                // Plain text search
                String searchIn = text;
                String searchFor = searchText;
                
                if (!caseSensitiveCheck.isSelected()) {
                    searchIn = text.toLowerCase();
                    searchFor = searchText.toLowerCase();
                }
                
                if (wholeWordCheck.isSelected()) {
                    // Whole word matching
                    Pattern pattern = Pattern.compile("\\b" + Pattern.quote(searchFor) + "\\b",
                        caseSensitiveCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE);
                    
                    if (forward) {
                        Matcher matcher = pattern.matcher(searchIn);
                        if (matcher.find(startPos)) {
                            return matcher.start();
                        }
                    } else {
                        Matcher matcher = pattern.matcher(searchIn.substring(0, startPos + 1));
                        int lastIndex = -1;
                        while (matcher.find()) {
                            lastIndex = matcher.start();
                        }
                        return lastIndex;
                    }
                } else {
                    if (forward) {
                        return searchIn.indexOf(searchFor, startPos);
                    } else {
                        return searchIn.lastIndexOf(searchFor, startPos);
                    }
                }
            }
        } catch (PatternSyntaxException e) {
            showStatus("Invalid regular expression: " + e.getMessage(), true);
        }
        
        return -1;
    }
    
    private boolean matches(String text, String searchText) {
        if (text.isEmpty() || searchText.isEmpty()) return false;
        
        if (regexCheck.isSelected()) {
            try {
                int flags = caseSensitiveCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(searchText, flags);
                return pattern.matcher(text).matches();
            } catch (PatternSyntaxException e) {
                return false;
            }
        } else {
            if (caseSensitiveCheck.isSelected()) {
                return text.equals(searchText);
            } else {
                return text.equalsIgnoreCase(searchText);
            }
        }
    }
    
    private int getMatchLength(String text, String searchText, int foundIndex) {
        if (regexCheck.isSelected()) {
            try {
                int flags = caseSensitiveCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(searchText, flags);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find(foundIndex)) {
                    return matcher.end() - matcher.start();
                }
            } catch (PatternSyntaxException e) {
                // Fallback
            }
        }
        return searchText.length();
    }
    
    private void selectMatch(int index, String searchText) {
        int matchLength = getMatchLength(getText(), searchText, index);
        selectRange(index, index + matchLength);
        requestTextControlFocus();

        // Scroll to make selection visible
        scrollTo(index);
    }
    
    private void showStatus(String message, boolean isError) {
        statusLabel.setText(message);
        statusLabel.setStyle(isError ? 
            "-fx-text-fill: #cc0000; -fx-font-size: 11px;" : 
            "-fx-text-fill: #666666; -fx-font-size: 11px;");
    }
    
    // Helper methods to abstract TextArea vs CodeArea differences
    private String getText() {
        if (textControl instanceof TextArea ta) {
            return ta.getText();
        } else if (textControl instanceof CodeArea ca) {
            return ca.getText();
        }
        return "";
    }

    private String getSelectedText() {
        if (textControl instanceof TextArea ta) {
            return ta.getSelectedText();
        } else if (textControl instanceof CodeArea ca) {
            return ca.getSelectedText();
        }
        return "";
    }

    private int getCaretPosition() {
        if (textControl instanceof TextArea ta) {
            return ta.getCaretPosition();
        } else if (textControl instanceof CodeArea ca) {
            return ca.getCaretPosition();
        }
        return 0;
    }

    private int getSelectionStart() {
        if (textControl instanceof TextArea ta) {
            return ta.getSelection().getStart();
        } else if (textControl instanceof CodeArea ca) {
            return ca.getSelection().getStart();
        }
        return 0;
    }

    private int getSelectionEnd() {
        if (textControl instanceof TextArea ta) {
            return ta.getSelection().getEnd();
        } else if (textControl instanceof CodeArea ca) {
            return ca.getSelection().getEnd();
        }
        return 0;
    }

    private void replaceText(int start, int end, String text) {
        if (textControl instanceof TextArea ta) {
            ta.replaceText(start, end, text);
        } else if (textControl instanceof CodeArea ca) {
            ca.replaceText(start, end, text);
        }
    }

    private void setText(String text) {
        if (textControl instanceof TextArea ta) {
            ta.setText(text);
        } else if (textControl instanceof CodeArea ca) {
            ca.replaceText(0, ca.getLength(), text);
        }
    }

    private void positionCaret(int pos) {
        if (textControl instanceof TextArea ta) {
            ta.positionCaret(pos);
        } else if (textControl instanceof CodeArea ca) {
            ca.moveTo(pos);
        }
    }

    private void selectRange(int start, int end) {
        if (textControl instanceof TextArea ta) {
            ta.selectRange(start, end);
        } else if (textControl instanceof CodeArea ca) {
            ca.selectRange(start, end);
        }
    }

    private void requestTextControlFocus() {
        if (textControl instanceof TextArea ta) {
            ta.requestFocus();
        } else if (textControl instanceof CodeArea ca) {
            ca.requestFocus();
        }
    }

    private void scrollTo(int pos) {
        if (textControl instanceof TextArea ta) {
            ta.setScrollTop(Math.max(0, pos - 100));
        } else if (textControl instanceof CodeArea ca) {
            // CodeArea handles scrolling differently - it auto-scrolls to show selection
            // We can use showParagraphAtTop if needed
            int paragraphIndex = ca.getText().substring(0, Math.min(pos, ca.getText().length())).split("\n", -1).length - 1;
            if (paragraphIndex > 0) {
                ca.showParagraphAtTop(Math.max(0, paragraphIndex - 5));
            }
        }
    }

    /**
     * Opens the dialog and focuses the find field.
     */
    public void openAndFocus() {
        show();
        findField.requestFocus();

        // If text is selected in the text area, use it as search text
        String selectedText = getSelectedText();
        if (selectedText != null && !selectedText.isEmpty() && !selectedText.contains("\n")) {
            findField.setText(selectedText);
            findField.selectAll();
        }
    }
}
