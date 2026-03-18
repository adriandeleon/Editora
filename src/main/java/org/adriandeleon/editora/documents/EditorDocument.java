package org.adriandeleon.editora.documents;

import org.adriandeleon.editora.languages.Diagnostic;
import org.adriandeleon.editora.languages.LanguageService;
import org.kordamp.ikonli.javafx.FontIcon;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EditorDocument {
    private final String untitledName;
    private final Tab tab;
    private final CodeArea codeArea;
    private final VirtualizedScrollPane<CodeArea> container;
    private final HBox tabHeader;
    private final FontIcon tabIcon;
    private final Label tabTitleLabel;
    private final Tooltip tabTooltip;

    private Path filePath;
    private boolean dirty;
    private String savedText;
    private LanguageService languageService;
    private Map<Integer, List<Diagnostic>> diagnosticsByLine = Map.of();
    private StyleSpans<Collection<String>> baseHighlighting;
    private Integer navigationGoalColumn;
    private boolean preserveNavigationGoalOnNextCaretChange;
    private Integer markPosition;

    public EditorDocument(String untitledName, CodeArea codeArea, LanguageService languageService) {
        this.untitledName = untitledName;
        this.codeArea = codeArea;
        this.languageService = Objects.requireNonNull(languageService, "languageService");
        this.savedText = codeArea.getText();
        this.tab = new Tab();
        this.container = new VirtualizedScrollPane<>(codeArea);
        this.tabIcon = new FontIcon("bi-file-earmark-text");
        this.tabIcon.setIconSize(12);
        this.tabIcon.getStyleClass().add("editor-tab-icon");
        this.tabTitleLabel = new Label();
        this.tabTitleLabel.getStyleClass().add("editor-tab-title");
        this.tabHeader = new HBox(6, tabIcon, tabTitleLabel);
        this.tabHeader.setAlignment(Pos.CENTER_LEFT);
        this.tabHeader.getStyleClass().add("editor-tab-header");
        this.tabTooltip = new Tooltip();
        this.baseHighlighting = emptyHighlighting(codeArea.getLength());
        this.tab.setContent(container);
        this.tab.setGraphic(tabHeader);
        this.tab.setText(null);
        this.tab.setTooltip(tabTooltip);
        refreshTabTitle();
    }

    public String getUntitledName() {
        return untitledName;
    }

    public Tab getTab() {
        return tab;
    }

    public CodeArea getCodeArea() {
        return codeArea;
    }

    public VirtualizedScrollPane<CodeArea> getContainer() {
        return container;
    }

    public Path getFilePath() {
        return filePath;
    }

    public void setFilePath(Path filePath) {
        this.filePath = filePath == null ? null : filePath.toAbsolutePath().normalize();
        refreshTabTitle();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void refreshDirtyState() {
        dirty = !Objects.equals(savedText, codeArea.getText());
        refreshTabTitle();
    }

    public void markSaved() {
        savedText = codeArea.getText();
        dirty = false;
        refreshTabTitle();
    }

    public LanguageService getLanguageService() {
        return languageService;
    }

    public void setLanguageService(LanguageService languageService) {
        this.languageService = Objects.requireNonNull(languageService, "languageService");
    }

    public void setDiagnosticsByLine(Map<Integer, List<Diagnostic>> diagnosticsByLine) {
        if (diagnosticsByLine == null || diagnosticsByLine.isEmpty()) {
            this.diagnosticsByLine = Map.of();
            return;
        }

        Map<Integer, List<Diagnostic>> normalizedDiagnostics = new LinkedHashMap<>();
        diagnosticsByLine.forEach((lineIndex, diagnostics) ->
                normalizedDiagnostics.put(lineIndex, diagnostics == null || diagnostics.isEmpty() ? List.of() : List.copyOf(diagnostics)));
        this.diagnosticsByLine = Collections.unmodifiableMap(normalizedDiagnostics);
    }

    public StyleSpans<Collection<String>> getBaseHighlighting() {
        return baseHighlighting;
    }

    public void setBaseHighlighting(StyleSpans<Collection<String>> baseHighlighting) {
        this.baseHighlighting = baseHighlighting == null ? emptyHighlighting(codeArea.getLength()) : baseHighlighting;
    }

    public Integer getNavigationGoalColumn() {
        return navigationGoalColumn;
    }

    public void setNavigationGoalColumn(Integer navigationGoalColumn) {
        this.navigationGoalColumn = navigationGoalColumn == null || navigationGoalColumn < 0
                ? null
                : navigationGoalColumn;
    }

    public void clearNavigationGoalColumn() {
        navigationGoalColumn = null;
    }

    public void preserveNavigationGoalOnNextCaretChange() {
        preserveNavigationGoalOnNextCaretChange = true;
    }

    public boolean consumeNavigationGoalPreservation() {
        boolean preserve = preserveNavigationGoalOnNextCaretChange;
        preserveNavigationGoalOnNextCaretChange = false;
        return preserve;
    }

    public Integer getMarkPosition() {
        return markPosition;
    }

    public boolean hasMark() {
        return markPosition != null;
    }

    public void setMarkPosition(Integer markPosition) {
        if (markPosition == null) {
            this.markPosition = null;
            return;
        }
        this.markPosition = Math.max(0, Math.min(markPosition, codeArea.getLength()));
    }

    public void clearMark() {
        markPosition = null;
    }

    public void clampMarkPosition() {
        if (markPosition != null) {
            markPosition = Math.max(0, Math.min(markPosition, codeArea.getLength()));
        }
    }

    public List<Diagnostic> getDiagnosticsForLine(int lineIndex) {
        return diagnosticsByLine.getOrDefault(lineIndex, List.of());
    }

    public int getDiagnosticCount() {
        return diagnosticsByLine.values().stream().mapToInt(List::size).sum();
    }

    public String getDisplayName() {
        String baseName = filePath == null ? untitledName : filePath.getFileName().toString();
        return dirty ? "*" + baseName : baseName;
    }

    public String getFullDisplayName() {
        return filePath == null ? untitledName : filePath.toAbsolutePath().toString();
    }

    private void refreshTabTitle() {
        tabTitleLabel.setText(getDisplayName());
        tabIcon.setIconLiteral(resolveTabIconLiteral());
        tabHeader.getStyleClass().remove("dirty");
        if (dirty) {
            tabHeader.getStyleClass().add("dirty");
        }
        tabTooltip.setText(getFullDisplayName());
    }

    private String resolveTabIconLiteral() {
        String fileName = filePath == null ? untitledName.toLowerCase() : filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".java")) {
            return "bi-file-earmark-code";
        }
        if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
            return "bi-file-earmark-text";
        }
        if (fileName.endsWith(".fxml") || fileName.endsWith(".xml") || fileName.endsWith(".css") || fileName.endsWith(".js") || fileName.endsWith(".ts") || fileName.endsWith(".json")) {
            return "bi-file-earmark-code";
        }
        return "bi-file-earmark-text";
    }

    private StyleSpans<Collection<String>> emptyHighlighting(int textLength) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        builder.add(Collections.emptyList(), Math.max(0, textLength));
        return builder.create();
    }
}

