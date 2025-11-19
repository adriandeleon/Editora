package org.editora.ui;

import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code editor area using RichTextFX with built-in line numbers and syntax highlighting.
 * Provides syntax highlighting for Java and other languages.
 */
public class CodeEditorArea extends BorderPane {
    
    private final CodeArea codeArea;
    private boolean showLineNumbers = true;
    private String currentLanguage = "java";
    
    public CodeEditorArea() {
        this.codeArea = new CodeArea();
        
        setupCodeArea();
        setupContextMenu();
        setupLineNumbers();
        setupSyntaxHighlighting();
        setupLayout();
    }
    
    private void setupCodeArea() {
        codeArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', 'Courier New', monospace; -fx-font-size: 14px;");
        codeArea.setEditable(true);
        
        // Ensure CodeArea can receive focus and mouse events
        codeArea.setFocusTraversable(true);
        codeArea.setMouseTransparent(false);
        
        // Make sure the CodeArea is enabled for interaction
        codeArea.setDisable(false);
        
        // Enable text selection with mouse
        codeArea.setUseInitialStyleForInsertion(true);
    }
    
    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem undoItem = new MenuItem("Undo");
        undoItem.setOnAction(e -> codeArea.undo());
        undoItem.disableProperty().bind(codeArea.undoAvailableProperty().map(b -> !b));
        
        MenuItem redoItem = new MenuItem("Redo");
        redoItem.setOnAction(e -> codeArea.redo());
        redoItem.disableProperty().bind(codeArea.redoAvailableProperty().map(b -> !b));
        
        MenuItem cutItem = new MenuItem("Cut");
        cutItem.setOnAction(e -> codeArea.cut());
        
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> codeArea.copy());
        
        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setOnAction(e -> codeArea.paste());
        
        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> codeArea.replaceSelection(""));
        
        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setOnAction(e -> codeArea.selectAll());
        
        contextMenu.getItems().addAll(
            undoItem, redoItem,
            new SeparatorMenuItem(),
            cutItem, copyItem, pasteItem, deleteItem,
            new SeparatorMenuItem(),
            selectAllItem
        );
        
        codeArea.setContextMenu(contextMenu);
    }
    
    private void setupLineNumbers() {
        if (showLineNumbers) {
            codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        }
    }
    
    private void setupSyntaxHighlighting() {
        // Apply syntax highlighting with a small delay to avoid performance issues
        codeArea.multiPlainChanges()
            .successionEnds(Duration.ofMillis(50))
            .subscribe(ignore -> {
                codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
            });
    }
    
    private void setupLayout() {
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        
        // Ensure the scroll pane doesn't block events
        scrollPane.setFocusTraversable(false);
        scrollPane.setMouseTransparent(false);
        
        setCenter(scrollPane);
    }
    
    /**
     * Computes syntax highlighting for the given text.
     * Currently supports Java syntax.
     */
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeHighlighting(String text) {
        if ("java".equals(currentLanguage)) {
            return computeJavaHighlighting(text);
        } else if ("xml".equals(currentLanguage) || "html".equals(currentLanguage)) {
            return computeXmlHighlighting(text);
        } else {
            return computePlainTextHighlighting(text);
        }
    }
    
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeJavaHighlighting(String text) {
        // Java keywords
        String[] KEYWORDS = new String[] {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "var", "record", "sealed", "permits", "non-sealed"
        };
        
        String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
        String PAREN_PATTERN = "\\(|\\)";
        String BRACE_PATTERN = "\\{|\\}";
        String BRACKET_PATTERN = "\\[|\\]";
        String SEMICOLON_PATTERN = "\\;";
        String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
        String COMMENT_PATTERN = "//[^\n]*" + "|" + "/\\*(.|\\R)*?\\*/";
        String NUMBER_PATTERN = "\\b\\d+(\\.\\d+)?\\b";
        
        Pattern PATTERN = Pattern.compile(
            "(?<KEYWORD>" + KEYWORD_PATTERN + ")"
            + "|(?<PAREN>" + PAREN_PATTERN + ")"
            + "|(?<BRACE>" + BRACE_PATTERN + ")"
            + "|(?<BRACKET>" + BRACKET_PATTERN + ")"
            + "|(?<SEMICOLON>" + SEMICOLON_PATTERN + ")"
            + "|(?<STRING>" + STRING_PATTERN + ")"
            + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
            + "|(?<NUMBER>" + NUMBER_PATTERN + ")"
        );
        
        Matcher matcher = PATTERN.matcher(text);
        int lastKwEnd = 0;
        org.fxmisc.richtext.model.StyleSpansBuilder<Collection<String>> spansBuilder
            = new org.fxmisc.richtext.model.StyleSpansBuilder<>();
        
        while (matcher.find()) {
            String styleClass =
                matcher.group("KEYWORD") != null ? "keyword" :
                matcher.group("PAREN") != null ? "paren" :
                matcher.group("BRACE") != null ? "brace" :
                matcher.group("BRACKET") != null ? "bracket" :
                matcher.group("SEMICOLON") != null ? "semicolon" :
                matcher.group("STRING") != null ? "string" :
                matcher.group("COMMENT") != null ? "comment" :
                matcher.group("NUMBER") != null ? "number" :
                null;
            
            assert styleClass != null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }
    
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computeXmlHighlighting(String text) {
        String TAG_PATTERN = "</?\\s*[a-zA-Z][a-zA-Z0-9]*\\s*/?>";
        String ATTRIBUTE_PATTERN = "\\s+[a-zA-Z][a-zA-Z0-9]*\\s*=";
        String ATTRIBUTE_VALUE_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
        String COMMENT_PATTERN = "<!--(.|\\R)*?-->";
        
        Pattern PATTERN = Pattern.compile(
            "(?<TAG>" + TAG_PATTERN + ")"
            + "|(?<ATTRIBUTE>" + ATTRIBUTE_PATTERN + ")"
            + "|(?<ATTRIBUTEVALUE>" + ATTRIBUTE_VALUE_PATTERN + ")"
            + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
        );
        
        Matcher matcher = PATTERN.matcher(text);
        int lastKwEnd = 0;
        org.fxmisc.richtext.model.StyleSpansBuilder<Collection<String>> spansBuilder
            = new org.fxmisc.richtext.model.StyleSpansBuilder<>();
        
        while (matcher.find()) {
            String styleClass =
                matcher.group("TAG") != null ? "tag" :
                matcher.group("ATTRIBUTE") != null ? "attribute" :
                matcher.group("ATTRIBUTEVALUE") != null ? "string" :
                matcher.group("COMMENT") != null ? "comment" :
                null;
            
            assert styleClass != null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }
    
    private org.fxmisc.richtext.model.StyleSpans<Collection<String>> computePlainTextHighlighting(String text) {
        org.fxmisc.richtext.model.StyleSpansBuilder<Collection<String>> spansBuilder
            = new org.fxmisc.richtext.model.StyleSpansBuilder<>();
        spansBuilder.add(Collections.emptyList(), text.length());
        return spansBuilder.create();
    }
    
    /**
     * Gets the underlying CodeArea.
     * @return CodeArea instance
     */
    public CodeArea getCodeArea() {
        return codeArea;
    }
    
    /**
     * Sets whether to show line numbers.
     * @param show true to show, false to hide
     */
    public void setShowLineNumbers(boolean show) {
        this.showLineNumbers = show;
        if (show) {
            codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        } else {
            codeArea.setParagraphGraphicFactory(null);
        }
    }
    
    /**
     * Checks if line numbers are shown.
     * @return true if shown, false otherwise
     */
    public boolean isShowingLineNumbers() {
        return showLineNumbers;
    }
    
    /**
     * Sets the language for syntax highlighting.
     * @param language "java", "xml", "html", or "plain"
     */
    public void setLanguage(String language) {
        this.currentLanguage = language.toLowerCase();
        // Re-apply highlighting
        codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
    }
    
    /**
     * Gets the current language.
     * @return current language
     */
    public String getLanguage() {
        return currentLanguage;
    }
}
