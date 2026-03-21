package org.adriandeleon.editora.documents;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.NodeOrientation;
import org.adriandeleon.editora.languages.Diagnostic;
import org.adriandeleon.editora.languages.LanguageService;
import org.adriandeleon.editora.editor.MiniMapSupport;
import org.adriandeleon.editora.editor.ProgressiveHighlightSupport;
import org.kordamp.ikonli.javafx.FontIcon;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

public final class EditorDocument {
    private static final double MINI_MAP_WIDTH = 92d;
    private static final int MAX_MINI_MAP_ROWS = 600;
    private static final int MINI_MAP_MAX_COLUMNS = 160;
    private static final double MINI_MAP_CONTENT_LEFT_INSET = 10d;
    private static final double MINI_MAP_CONTENT_RIGHT_INSET = 8d;
    private static final double MINI_MAP_FADE_HEIGHT = 18d;

    private final String untitledName;
    private final Tab tab;
    private final CodeArea codeArea;
    private final VirtualizedScrollPane<CodeArea> container;
    private final StackPane editorHost;
    private final HBox primaryEditorPane;
    private final VBox content;
    private final HBox editorRow;
    private final HBox readOnlyInfoBar;
    private final StackPane miniMapHost;
    private final Pane miniMapContent;
    private final Region miniMapViewport;
    private final Region miniMapTopFade;
    private final Region miniMapBottomFade;
    private final HBox tabHeader;
    private final FontIcon tabIcon;
    private final Label tabTitleLabel;
    private final Tooltip tabTooltip;
    private final InvalidationListener miniMapViewportListener = ignored -> refreshMiniMapViewport();
    private final InvalidationListener secondaryMiniMapViewportListener = ignored -> refreshSecondaryMiniMapViewport();
    private final Runnable noOpToggleHandler = () -> {
    };

    private Path filePath;
    private boolean dirty;
    private boolean readOnly;
    private Runnable readOnlyToggleHandler;
    private String savedText;
    private LanguageService languageService;
    private Map<Integer, List<Diagnostic>> diagnosticsByLine = Map.of();
    private StyleSpans<Collection<String>> baseHighlighting;
    private Integer navigationGoalColumn;
    private boolean preserveNavigationGoalOnNextCaretChange;
    private Integer markPosition;
    private ScrollBar verticalScrollBar;
    private final List<BookmarkAnchor> bookmarks = new ArrayList<>();
    private boolean miniMapVisible;
    private double miniMapRenderHeight;
    private final StackPane secondaryMiniMapHost;
    private final Pane secondaryMiniMapContent;
    private final Region secondaryMiniMapViewport;
    private final Region secondaryMiniMapTopFade;
    private final Region secondaryMiniMapBottomFade;
    private ScrollBar secondaryVerticalScrollBar;
    private double secondaryMiniMapRenderHeight;
    private long analysisRevision;
    private long progressiveHighlightRevision;
    private boolean progressiveHighlightingActive;
    private final List<HighlightRange> completedHighlightRanges = new ArrayList<>();
    private final List<HighlightRange> pendingHighlightRanges = new ArrayList<>();
    private ProgressiveHighlightSupport.ParagraphWindow lastVisibleViewportParagraphWindow;
    private SplitPane splitEditorPane;
    private CodeArea secondaryCodeArea;
    private VirtualizedScrollPane<CodeArea> secondaryContainer;
    private Orientation splitOrientation = Orientation.HORIZONTAL;
    private boolean synchronizingSplitEditors;
    private boolean splitTextSyncScheduled;
    private CodeArea pendingSplitSyncSource;
    private CodeArea pendingSplitSyncTarget;
    private ChangeListener<String> primarySplitTextSyncListener;
    private ChangeListener<String> secondarySplitTextSyncListener;
    private ChangeListener<Number> primarySplitCaretSyncListener;
    private ChangeListener<Number> secondarySplitCaretSyncListener;
    private ChangeListener<String> secondaryMiniMapTextListener;
    private ChangeListener<Scene> secondaryMiniMapSceneListener;
    private ChangeListener<Bounds> secondaryMiniMapLayoutListener;
    private Consumer<CodeArea> splitEditorInitializer = ignored -> {
    };

    public EditorDocument(String untitledName,
                          CodeArea codeArea,
                          LanguageService languageService,
                          boolean miniMapVisible) {
        this.untitledName = untitledName;
        this.codeArea = codeArea;
        this.languageService = Objects.requireNonNull(languageService, "languageService");
        this.savedText = codeArea.getText();
        this.tab = new Tab();
        this.container = new VirtualizedScrollPane<>(codeArea);
        this.editorHost = new StackPane();
        this.primaryEditorPane = new HBox();
        this.content = new VBox();
        this.editorRow = new HBox();
        this.readOnlyInfoBar = new HBox();
        this.miniMapHost = new StackPane();
        this.miniMapContent = new Pane();
        this.miniMapViewport = new Region();
        this.miniMapTopFade = new Region();
        this.miniMapBottomFade = new Region();
        this.secondaryMiniMapHost = new StackPane();
        this.secondaryMiniMapContent = new Pane();
        this.secondaryMiniMapViewport = new Region();
        this.secondaryMiniMapTopFade = new Region();
        this.secondaryMiniMapBottomFade = new Region();
        this.tabIcon = new FontIcon("bi-file-earmark-text");
        this.tabIcon.setIconSize(12);
        this.tabIcon.getStyleClass().add("editor-tab-icon");
        this.tabTitleLabel = new Label();
        this.tabTitleLabel.getStyleClass().add("editor-tab-title");
        this.tabHeader = new HBox(6, tabIcon, tabTitleLabel);
        this.tabHeader.setAlignment(Pos.CENTER_LEFT);
        this.tabHeader.getStyleClass().add("editor-tab-header");
        this.tabTooltip = new Tooltip();
        this.readOnlyToggleHandler = noOpToggleHandler;
        this.baseHighlighting = emptyHighlighting(codeArea.getLength());
        this.miniMapVisible = miniMapVisible;
        configureContent();
        configureMiniMap();
        configureSecondaryMiniMap();
        this.tab.setContent(content);
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

    public void setMiniMapVisible(boolean miniMapVisible) {
        this.miniMapVisible = miniMapVisible;
        miniMapHost.setManaged(miniMapVisible);
        miniMapHost.setVisible(miniMapVisible);
        secondaryMiniMapHost.setManaged(miniMapVisible && hasSplitView());
        secondaryMiniMapHost.setVisible(miniMapVisible && hasSplitView());
        if (miniMapVisible) {
            refreshMiniMap();
        }
    }

    public void refreshMiniMap() {
        redrawMiniMap();
        refreshMiniMapViewport();
        redrawSecondaryMiniMap();
        refreshSecondaryMiniMapViewport();
        scheduleMiniMapBinding();
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

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnlyToggleHandler(Runnable readOnlyToggleHandler) {
        this.readOnlyToggleHandler = readOnlyToggleHandler == null ? noOpToggleHandler : readOnlyToggleHandler;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        codeArea.setEditable(!readOnly);
        if (secondaryCodeArea != null) {
            secondaryCodeArea.setEditable(!readOnly);
        }
        tabHeader.getStyleClass().remove("read-only");
        if (readOnly) {
            tabHeader.getStyleClass().add("read-only");
        }
        readOnlyInfoBar.setManaged(readOnly);
        readOnlyInfoBar.setVisible(readOnly);
    }

    public boolean toggleBookmark(int lineIndex) {
        int normalizedLineIndex = normalizeLineIndex(lineIndex);
        int bookmarkIndex = bookmarkIndexForLine(normalizedLineIndex);
        if (bookmarkIndex >= 0) {
            bookmarks.remove(bookmarkIndex);
            return false;
        }
        bookmarks.add(new BookmarkAnchor(normalizedLineIndex, lineTextAt(normalizedLineIndex)));
        sortBookmarks();
        return true;
    }

    public boolean addBookmark(int lineIndex) {
        int normalizedLineIndex = normalizeLineIndex(lineIndex);
        if (bookmarkIndexForLine(normalizedLineIndex) >= 0) {
            return false;
        }
        bookmarks.add(new BookmarkAnchor(normalizedLineIndex, lineTextAt(normalizedLineIndex)));
        sortBookmarks();
        return true;
    }

    public boolean removeBookmark(int lineIndex) {
        int bookmarkIndex = bookmarkIndexForLine(normalizeLineIndex(lineIndex));
        if (bookmarkIndex < 0) {
            return false;
        }
        bookmarks.remove(bookmarkIndex);
        return true;
    }

    public boolean hasBookmark(int lineIndex) {
        return bookmarkIndexForLine(normalizeLineIndex(lineIndex)) >= 0;
    }

    public List<Integer> bookmarkedLines() {
        return bookmarkLineIndexes();
    }

    public void setBookmarkedLines(Collection<Integer> lineIndexes) {
        bookmarks.clear();
        if (lineIndexes == null || lineIndexes.isEmpty()) {
            return;
        }

        TreeSet<Integer> normalizedLineIndexes = new TreeSet<>();
        lineIndexes.forEach(lineIndex -> normalizedLineIndexes.add(normalizeLineIndex(lineIndex)));
        normalizedLineIndexes.forEach(normalizedLineIndex ->
                bookmarks.add(new BookmarkAnchor(normalizedLineIndex, lineTextAt(normalizedLineIndex))));
    }

    public boolean pruneBookmarksToValidRange() {
        List<Integer> previousLineIndexes = bookmarkLineIndexes();
        TreeSet<Integer> normalizedLineIndexes = new TreeSet<>();
        previousLineIndexes.stream().map(this::normalizeLineIndex).forEach(normalizedLineIndexes::add);
        bookmarks.clear();
        normalizedLineIndexes.forEach(normalizedLineIndex ->
                bookmarks.add(new BookmarkAnchor(normalizedLineIndex, lineTextAt(normalizedLineIndex))));
        return !previousLineIndexes.equals(List.copyOf(normalizedLineIndexes));
    }

    public boolean realignBookmarksToContent() {
        if (bookmarks.isEmpty()) {
            return false;
        }

        List<Integer> previousLineIndexes = bookmarkLineIndexes();
        List<String> paragraphTexts = codeArea.getParagraphs().stream().map(paragraph -> paragraph.getText()).toList();
        if (paragraphTexts.isEmpty()) {
            return false;
        }

        List<BookmarkAnchor> sortedBookmarks = bookmarks.stream()
                .sorted(Comparator.comparingInt(BookmarkAnchor::lineIndex))
                .toList();
        HashSet<Integer> claimedLines = new HashSet<>();
        List<BookmarkAnchor> realignedBookmarks = new ArrayList<>(sortedBookmarks.size());
        int maxLineIndex = paragraphTexts.size() - 1;

        for (BookmarkAnchor bookmark : sortedBookmarks) {
            int targetLine = findNearestMatchingLine(paragraphTexts, bookmark, claimedLines);
            if (targetLine < 0) {
                targetLine = Math.max(0, Math.min(bookmark.lineIndex(), maxLineIndex));
            }
            if (claimedLines.contains(targetLine)) {
                targetLine = findNearestAvailableLine(targetLine, maxLineIndex, claimedLines);
            }
            claimedLines.add(targetLine);
            realignedBookmarks.add(new BookmarkAnchor(targetLine, paragraphTexts.get(targetLine)));
        }

        bookmarks.clear();
        bookmarks.addAll(realignedBookmarks);
        sortBookmarks();
        return !previousLineIndexes.equals(bookmarkLineIndexes());
    }

    public void scrollPageDown() {
        int visibleParagraphs = Math.max(1, codeArea.getVisibleParagraphs().size());
        int currentParagraph = codeArea.getCurrentParagraph();
        int totalParagraphs = codeArea.getParagraphs().size();
        int targetParagraph = Math.min(currentParagraph + visibleParagraphs, totalParagraphs - 1);
        codeArea.moveTo(targetParagraph, 0);
        codeArea.requestFollowCaret();
    }

    public void scrollPageUp() {
        int visibleParagraphs = Math.max(1, codeArea.getVisibleParagraphs().size());
        int currentParagraph = codeArea.getCurrentParagraph();
        int targetParagraph = Math.max(currentParagraph - visibleParagraphs, 0);
        codeArea.moveTo(targetParagraph, 0);
        codeArea.requestFollowCaret();
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

    public void setSplitEditorInitializer(Consumer<CodeArea> splitEditorInitializer) {
        this.splitEditorInitializer = splitEditorInitializer == null ? ignored -> {
        } : splitEditorInitializer;
    }

    public boolean hasSplitView() {
        return secondaryCodeArea != null && splitEditorPane != null;
    }

    public boolean splitRight() {
        return ensureSplit(Orientation.HORIZONTAL);
    }

    public boolean splitDown() {
        return ensureSplit(Orientation.VERTICAL);
    }

    public boolean unsplit() {
        if (!hasSplitView()) {
            return false;
        }
        removeSplitEditorListeners();
        editorHost.getChildren().setAll(primaryEditorPane);
        splitEditorPane = null;
        secondaryCodeArea = null;
        secondaryContainer = null;
        splitOrientation = Orientation.HORIZONTAL;
        secondaryMiniMapHost.setManaged(false);
        secondaryMiniMapHost.setVisible(false);
        secondaryVerticalScrollBar = null;
        secondaryMiniMapRenderHeight = 0d;
        secondaryMiniMapContent.getChildren().clear();
        pendingSplitSyncSource = null;
        pendingSplitSyncTarget = null;
        splitTextSyncScheduled = false;
        codeArea.requestFocus();
        return true;
    }

    public boolean focusOtherSplitView() {
        if (!hasSplitView()) {
            return false;
        }
        if (secondaryCodeArea != null && secondaryCodeArea.isFocused()) {
            codeArea.requestFocus();
        } else {
            secondaryCodeArea.requestFocus();
        }
        return true;
    }

    public CodeArea activeCodeArea() {
        if (secondaryCodeArea != null && secondaryCodeArea.isFocused()) {
            return secondaryCodeArea;
        }
        return codeArea;
    }

    public Optional<CodeArea> secondaryCodeArea() {
        return Optional.ofNullable(secondaryCodeArea);
    }

    public void applyEditorPresentationToSplitView() {
        if (secondaryCodeArea == null) {
            return;
        }
        secondaryCodeArea.setWrapText(codeArea.isWrapText());
        secondaryCodeArea.setStyle(codeArea.getStyle());
        secondaryCodeArea.setEditable(codeArea.isEditable());
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

    public long nextAnalysisRevision() {
        return ++analysisRevision;
    }

    public boolean isAnalysisRevisionCurrent(long analysisRevision) {
        return this.analysisRevision == analysisRevision;
    }

    public long getAnalysisRevision() {
        return analysisRevision;
    }

    public void beginProgressiveHighlighting(long analysisRevision) {
        progressiveHighlightingActive = true;
        progressiveHighlightRevision = analysisRevision;
        completedHighlightRanges.clear();
        pendingHighlightRanges.clear();
        lastVisibleViewportParagraphWindow = null;
    }

    public void clearProgressiveHighlighting() {
        progressiveHighlightingActive = false;
        progressiveHighlightRevision = 0L;
        completedHighlightRanges.clear();
        pendingHighlightRanges.clear();
        lastVisibleViewportParagraphWindow = null;
    }

    public boolean isProgressiveHighlightingActive() {
        return progressiveHighlightingActive;
    }

    public ProgressiveHighlightSupport.HighlightWindow claimProgressiveHighlightWindow(long analysisRevision,
                                                                                       ProgressiveHighlightSupport.HighlightWindow desiredWindow) {
        if (!progressiveHighlightingActive || progressiveHighlightRevision != analysisRevision || desiredWindow == null) {
            return null;
        }

        int normalizedStart = Math.max(0, Math.min(desiredWindow.startOffset(), codeArea.getLength()));
        int normalizedEnd = Math.max(normalizedStart, Math.min(desiredWindow.endOffset(), codeArea.getLength()));
        HighlightRange uncovered = firstUncoveredRange(normalizedStart, normalizedEnd);
        if (uncovered == null) {
            return null;
        }

        pendingHighlightRanges.add(uncovered);
        return new ProgressiveHighlightSupport.HighlightWindow(
                uncovered.start(),
                uncovered.end(),
                desiredWindow.startParagraph(),
                desiredWindow.endParagraphExclusive()
        );
    }

    public void completeProgressiveHighlightRange(long analysisRevision, int start, int end) {
        if (progressiveHighlightRevision != analysisRevision) {
            return;
        }
        pendingHighlightRanges.removeIf(range -> range.start() == start && range.end() == end);
        mergeHighlightRange(completedHighlightRanges, new HighlightRange(start, end));
    }

    public void abandonProgressiveHighlightRange(long analysisRevision, int start, int end) {
        if (progressiveHighlightRevision != analysisRevision) {
            return;
        }
        pendingHighlightRanges.removeIf(range -> range.start() == start && range.end() == end);
    }

    public int getParagraphCount() {
        return Math.max(1, codeArea.getParagraphs().size());
    }

    public ProgressiveHighlightSupport.ParagraphWindow visibleParagraphWindow(int bufferParagraphs, int fallbackVisibleParagraphs) {
        int paragraphCount = Math.max(1, codeArea.getParagraphs().size());
        int visibleParagraphCount = codeArea.getVisibleParagraphs().size();
        int firstVisibleParagraph = visibleParagraphCount > 0 ? codeArea.visibleParToAllParIndex(0) : 0;
        return ProgressiveHighlightSupport.windowAroundVisibleParagraphs(
                firstVisibleParagraph,
                visibleParagraphCount,
                paragraphCount,
                bufferParagraphs,
                fallbackVisibleParagraphs
        );
    }

    public ProgressiveHighlightSupport.ViewportMotion updateViewportMotion(ProgressiveHighlightSupport.ParagraphWindow viewportParagraphWindow) {
        ProgressiveHighlightSupport.ViewportMotion motion = ProgressiveHighlightSupport.inferViewportMotion(
                lastVisibleViewportParagraphWindow,
                viewportParagraphWindow
        );
        lastVisibleViewportParagraphWindow = viewportParagraphWindow;
        return motion;
    }

    public ProgressiveHighlightSupport.HighlightWindow highlightWindowForParagraphs(ProgressiveHighlightSupport.ParagraphWindow paragraphWindow) {
        int paragraphCount = getParagraphCount();
        int startOffset = codeArea.getAbsolutePosition(paragraphWindow.startParagraph(), 0);
        int endOffset = paragraphWindow.endParagraphExclusive() >= paragraphCount
                ? codeArea.getLength()
                : codeArea.getAbsolutePosition(paragraphWindow.endParagraphExclusive(), 0);
        return new ProgressiveHighlightSupport.HighlightWindow(
                startOffset,
                endOffset,
                paragraphWindow.startParagraph(),
                paragraphWindow.endParagraphExclusive()
        );
    }

    public ProgressiveHighlightSupport.HighlightWindow visibleHighlightWindow(int bufferParagraphs, int fallbackVisibleParagraphs) {
        return highlightWindowForParagraphs(visibleParagraphWindow(bufferParagraphs, fallbackVisibleParagraphs));
    }

    public void resetViewToTop() {
        codeArea.moveTo(0);
        Platform.runLater(() -> {
            codeArea.moveTo(0);
            codeArea.showParagraphAtTop(0);
        });
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

    private void configureContent() {
        content.getStyleClass().add("editor-document-shell");
        content.setFillWidth(true);
        content.setMinHeight(0d);
        content.setPrefHeight(0d);
        content.setMaxHeight(Double.MAX_VALUE);
        editorRow.getStyleClass().add("editor-document-editor-row");
                        editorHost.getStyleClass().add("editor-document-editor-host");
                        editorHost.setMinHeight(0d);
                        editorHost.setPrefHeight(0d);
                        editorHost.setMaxHeight(Double.MAX_VALUE);
        editorRow.setFillHeight(true);
        editorRow.setMinHeight(0d);
        editorRow.setPrefHeight(0d);
        editorRow.setMaxHeight(Double.MAX_VALUE);
        readOnlyInfoBar.getStyleClass().add("editor-read-only-banner");
        readOnlyInfoBar.setAlignment(Pos.CENTER_LEFT);
        readOnlyInfoBar.setSpacing(10d);
        readOnlyInfoBar.setManaged(false);
        readOnlyInfoBar.setVisible(false);
        FontIcon readOnlyBannerIcon = new FontIcon("bi-lock-fill");
        readOnlyBannerIcon.setIconSize(12);
        readOnlyBannerIcon.getStyleClass().add("editor-read-only-banner-icon");
        Label readOnlyInfoLabel = new Label("Read-only mode is on. Use Space to page down and Backspace to page up.");
        readOnlyInfoLabel.getStyleClass().add("editor-read-only-banner-text");
        Button readOnlyToggleButton = new Button("Enable Editing");
        readOnlyToggleButton.getStyleClass().add("read-only-banner-button");
        readOnlyToggleButton.setFocusTraversable(false);
        readOnlyToggleButton.setMnemonicParsing(false);
        readOnlyToggleButton.setOnAction(event -> readOnlyToggleHandler.run());
        container.setMinHeight(0d);
        container.setMaxHeight(Double.MAX_VALUE);
        miniMapHost.setMinHeight(0d);
        miniMapHost.setMaxHeight(Double.MAX_VALUE);
        primaryEditorPane.getStyleClass().add("editor-document-editor-pane");
        primaryEditorPane.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        primaryEditorPane.setFillHeight(true);
        primaryEditorPane.setMinHeight(0d);
        primaryEditorPane.setPrefHeight(0d);
        primaryEditorPane.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(container, Priority.ALWAYS);
        primaryEditorPane.getChildren().setAll(container, miniMapHost);
        VBox.setVgrow(editorRow, Priority.ALWAYS);
        HBox.setHgrow(editorHost, Priority.ALWAYS);
        editorHost.getChildren().setAll(primaryEditorPane);
        editorRow.getChildren().setAll(editorHost);
        readOnlyInfoBar.getChildren().setAll(readOnlyBannerIcon, readOnlyInfoLabel, new Region(), readOnlyToggleButton);
        HBox.setHgrow(readOnlyInfoBar.getChildren().get(2), Priority.ALWAYS);
        content.getChildren().setAll(readOnlyInfoBar, editorRow);
    }

    private void configureMiniMap() {
        miniMapHost.getStyleClass().add("editor-mini-map");
        miniMapHost.setMinWidth(MINI_MAP_WIDTH);
        miniMapHost.setPrefWidth(MINI_MAP_WIDTH);
        miniMapHost.setMaxWidth(MINI_MAP_WIDTH);
        miniMapContent.setManaged(false);
        miniMapContent.setMouseTransparent(true);
        miniMapContent.getStyleClass().add("editor-mini-map-content");
        miniMapViewport.setManaged(false);
        miniMapViewport.setMouseTransparent(true);
        miniMapViewport.getStyleClass().add("editor-mini-map-viewport");
        configureFadeRegion(miniMapTopFade, "editor-mini-map-fade-top");
        configureFadeRegion(miniMapBottomFade, "editor-mini-map-fade-bottom");
        miniMapHost.getChildren().setAll(miniMapContent, miniMapTopFade, miniMapBottomFade, miniMapViewport);
        StackPane.setAlignment(miniMapContent, Pos.TOP_LEFT);
        StackPane.setAlignment(miniMapTopFade, Pos.TOP_LEFT);
        StackPane.setAlignment(miniMapBottomFade, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(miniMapViewport, Pos.TOP_LEFT);

        miniMapHost.widthProperty().addListener(ignored -> redrawMiniMap());
        miniMapHost.heightProperty().addListener(ignored -> {
            redrawMiniMap();
            refreshMiniMapViewport();
        });
        codeArea.textProperty().addListener(ignored -> redrawMiniMap());
        codeArea.sceneProperty().addListener((observable, previous, current) -> scheduleMiniMapBinding());
        container.layoutBoundsProperty().addListener((observable, previous, current) -> scheduleMiniMapBinding());
        miniMapHost.setOnMousePressed(event -> jumpToMiniMapPosition(event.getY()));
        miniMapHost.setOnMouseDragged(event -> jumpToMiniMapPosition(event.getY()));

        setMiniMapVisible(miniMapVisible);
        scheduleMiniMapBinding();
    }

    private void configureSecondaryMiniMap() {
        secondaryMiniMapHost.getStyleClass().add("editor-mini-map");
        secondaryMiniMapHost.setMinWidth(MINI_MAP_WIDTH);
        secondaryMiniMapHost.setPrefWidth(MINI_MAP_WIDTH);
        secondaryMiniMapHost.setMaxWidth(MINI_MAP_WIDTH);
        secondaryMiniMapContent.setManaged(false);
        secondaryMiniMapContent.setMouseTransparent(true);
        secondaryMiniMapContent.getStyleClass().add("editor-mini-map-content");
        secondaryMiniMapViewport.setManaged(false);
        secondaryMiniMapViewport.setMouseTransparent(true);
        secondaryMiniMapViewport.getStyleClass().add("editor-mini-map-viewport");
        configureFadeRegion(secondaryMiniMapTopFade, "editor-mini-map-fade-top");
        configureFadeRegion(secondaryMiniMapBottomFade, "editor-mini-map-fade-bottom");
        secondaryMiniMapHost.getChildren().setAll(secondaryMiniMapContent, secondaryMiniMapTopFade, secondaryMiniMapBottomFade, secondaryMiniMapViewport);
        StackPane.setAlignment(secondaryMiniMapContent, Pos.TOP_LEFT);
        StackPane.setAlignment(secondaryMiniMapTopFade, Pos.TOP_LEFT);
        StackPane.setAlignment(secondaryMiniMapBottomFade, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(secondaryMiniMapViewport, Pos.TOP_LEFT);
        secondaryMiniMapHost.widthProperty().addListener(ignored -> redrawSecondaryMiniMap());
        secondaryMiniMapHost.heightProperty().addListener(ignored -> {
            redrawSecondaryMiniMap();
            refreshSecondaryMiniMapViewport();
        });
        secondaryMiniMapHost.setOnMousePressed(event -> jumpToSecondaryMiniMapPosition(event.getY()));
        secondaryMiniMapHost.setOnMouseDragged(event -> jumpToSecondaryMiniMapPosition(event.getY()));
    }

    private void scheduleMiniMapBinding() {
        if (!miniMapVisible) {
            return;
        }
        Platform.runLater(() -> {
            bindVerticalScrollBar();
            redrawMiniMap();
            refreshMiniMapViewport();
            bindSecondaryVerticalScrollBar();
            redrawSecondaryMiniMap();
            refreshSecondaryMiniMapViewport();
        });
    }

    private void bindVerticalScrollBar() {
        ScrollBar candidate = container.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .filter(scrollBar -> scrollBar.getOrientation() == Orientation.VERTICAL)
                .findFirst()
                .orElse(null);
        if (candidate == verticalScrollBar) {
            return;
        }

        if (verticalScrollBar != null) {
            verticalScrollBar.valueProperty().removeListener(miniMapViewportListener);
            verticalScrollBar.visibleAmountProperty().removeListener(miniMapViewportListener);
            verticalScrollBar.minProperty().removeListener(miniMapViewportListener);
            verticalScrollBar.maxProperty().removeListener(miniMapViewportListener);
        }

        verticalScrollBar = candidate;
        if (verticalScrollBar != null) {
            verticalScrollBar.valueProperty().addListener(miniMapViewportListener);
            verticalScrollBar.visibleAmountProperty().addListener(miniMapViewportListener);
            verticalScrollBar.minProperty().addListener(miniMapViewportListener);
            verticalScrollBar.maxProperty().addListener(miniMapViewportListener);
        }
    }

    private void bindSecondaryVerticalScrollBar() {
        if (secondaryContainer == null) {
            return;
        }
        ScrollBar candidate = secondaryContainer.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .filter(scrollBar -> scrollBar.getOrientation() == Orientation.VERTICAL)
                .findFirst()
                .orElse(null);
        if (candidate == secondaryVerticalScrollBar) {
            return;
        }

        if (secondaryVerticalScrollBar != null) {
            secondaryVerticalScrollBar.valueProperty().removeListener(secondaryMiniMapViewportListener);
            secondaryVerticalScrollBar.visibleAmountProperty().removeListener(secondaryMiniMapViewportListener);
            secondaryVerticalScrollBar.minProperty().removeListener(secondaryMiniMapViewportListener);
            secondaryVerticalScrollBar.maxProperty().removeListener(secondaryMiniMapViewportListener);
        }

        secondaryVerticalScrollBar = candidate;
        if (secondaryVerticalScrollBar != null) {
            secondaryVerticalScrollBar.valueProperty().addListener(secondaryMiniMapViewportListener);
            secondaryVerticalScrollBar.visibleAmountProperty().addListener(secondaryMiniMapViewportListener);
            secondaryVerticalScrollBar.minProperty().addListener(secondaryMiniMapViewportListener);
            secondaryVerticalScrollBar.maxProperty().addListener(secondaryMiniMapViewportListener);
        }
    }

    private void redrawMiniMap() {
        double width = miniMapHost.getWidth();
        double hostHeight = miniMapHost.getHeight();
        miniMapContent.getChildren().clear();
        miniMapRenderHeight = 0d;
        if (!miniMapVisible || width <= 0 || hostHeight <= 0) {
            return;
        }

        MiniMapSupport.MiniMapLayout layout = MiniMapSupport.layout(codeArea.getText(), hostHeight, MAX_MINI_MAP_ROWS);
        List<MiniMapSupport.MiniMapSample> samples = MiniMapSupport.sampleText(codeArea.getText(), layout.sampleCount(), MINI_MAP_MAX_COLUMNS);
        miniMapRenderHeight = layout.renderHeight();
        double rowHeight = layout.rowHeight();
        double leftPadding = miniMapContentX(miniMapHost);
        double rightPadding = miniMapContentRightInset(miniMapHost);
        double contentWidth = miniMapContentWidth(miniMapHost);
        double usableWidth = Math.max(1d, contentWidth);
        for (int index = 0; index < samples.size(); index++) {
            MiniMapSupport.MiniMapSample sample = samples.get(index);
            if (sample.widthFraction() <= 0d) {
                continue;
            }

            double x = leftPadding + usableWidth * sample.indentFraction();
            double lineWidth = Math.max(1d, usableWidth * sample.widthFraction());
            lineWidth = Math.min(lineWidth, Math.max(1d, width - rightPadding - x));
            double y = index * rowHeight;
            double lineHeight = Math.max(1d, rowHeight - 0.2d);
            Region line = new Region();
            line.setManaged(false);
            line.getStyleClass().add("editor-mini-map-line");
            line.resizeRelocate(x, y, lineWidth, lineHeight);
            miniMapContent.getChildren().add(line);
        }
        miniMapContent.resizeRelocate(leftPadding, 0d, contentWidth, miniMapRenderHeight);
        updateMiniMapFadeRegions(miniMapTopFade, miniMapBottomFade, miniMapHost, contentWidth, miniMapRenderHeight);
    }

    private void redrawSecondaryMiniMap() {
        double width = secondaryMiniMapHost.getWidth();
        double hostHeight = secondaryMiniMapHost.getHeight();
        secondaryMiniMapContent.getChildren().clear();
        secondaryMiniMapRenderHeight = 0d;
        if (!miniMapVisible || !hasSplitView() || secondaryCodeArea == null || width <= 0 || hostHeight <= 0) {
            return;
        }

        MiniMapSupport.MiniMapLayout layout = MiniMapSupport.layout(secondaryCodeArea.getText(), hostHeight, MAX_MINI_MAP_ROWS);
        List<MiniMapSupport.MiniMapSample> samples = MiniMapSupport.sampleText(secondaryCodeArea.getText(), layout.sampleCount(), MINI_MAP_MAX_COLUMNS);
        secondaryMiniMapRenderHeight = layout.renderHeight();
        double rowHeight = layout.rowHeight();
        double leftPadding = miniMapContentX(secondaryMiniMapHost);
        double rightPadding = miniMapContentRightInset(secondaryMiniMapHost);
        double contentWidth = miniMapContentWidth(secondaryMiniMapHost);
        double usableWidth = Math.max(1d, contentWidth);
        for (int index = 0; index < samples.size(); index++) {
            MiniMapSupport.MiniMapSample sample = samples.get(index);
            if (sample.widthFraction() <= 0d) {
                continue;
            }

            double x = leftPadding + usableWidth * sample.indentFraction();
            double lineWidth = Math.max(1d, usableWidth * sample.widthFraction());
            lineWidth = Math.min(lineWidth, Math.max(1d, width - rightPadding - x));
            double y = index * rowHeight;
            double lineHeight = Math.max(1d, rowHeight - 0.2d);
            Region line = new Region();
            line.setManaged(false);
            line.getStyleClass().add("editor-mini-map-line");
            line.resizeRelocate(x, y, lineWidth, lineHeight);
            secondaryMiniMapContent.getChildren().add(line);
        }
        secondaryMiniMapContent.resizeRelocate(leftPadding, 0d, contentWidth, secondaryMiniMapRenderHeight);
        updateMiniMapFadeRegions(secondaryMiniMapTopFade, secondaryMiniMapBottomFade, secondaryMiniMapHost, contentWidth, secondaryMiniMapRenderHeight);
    }

    private void refreshMiniMapViewport() {
        if (!miniMapVisible) {
            miniMapViewport.setVisible(false);
            return;
        }

        bindVerticalScrollBar();
        double hostHeight = miniMapHost.getHeight();
        double renderHeight = Math.max(0d, Math.min(hostHeight, miniMapRenderHeight));
        if (verticalScrollBar == null || renderHeight <= 0d) {
            miniMapViewport.setVisible(false);
            return;
        }

        MiniMapSupport.ViewportIndicator indicator = MiniMapSupport.viewportIndicator(
                verticalScrollBar.getMin(),
                verticalScrollBar.getMax(),
                verticalScrollBar.getValue(),
                verticalScrollBar.getVisibleAmount()
        );
        double top = renderHeight * indicator.startFraction();
        double viewportHeight = Math.max(Math.min(12d, renderHeight), renderHeight * indicator.heightFraction());
        viewportHeight = Math.min(renderHeight, viewportHeight);
        top = Math.max(0d, Math.min(renderHeight - viewportHeight, top));
        double viewportX = Math.max(0d, miniMapContentX(miniMapHost) - 2d);
        double viewportWidth = Math.min(miniMapHost.getWidth() - viewportX, miniMapContentWidth(miniMapHost) + 4d);
        miniMapViewport.setVisible(true);
        miniMapViewport.resizeRelocate(viewportX, top, viewportWidth, viewportHeight);
    }

    private void refreshSecondaryMiniMapViewport() {
        if (!miniMapVisible || !hasSplitView()) {
            secondaryMiniMapViewport.setVisible(false);
            return;
        }

        bindSecondaryVerticalScrollBar();
        double hostHeight = secondaryMiniMapHost.getHeight();
        double renderHeight = Math.max(0d, Math.min(hostHeight, secondaryMiniMapRenderHeight));
        if (secondaryVerticalScrollBar == null || renderHeight <= 0d) {
            secondaryMiniMapViewport.setVisible(false);
            return;
        }

        MiniMapSupport.ViewportIndicator indicator = MiniMapSupport.viewportIndicator(
                secondaryVerticalScrollBar.getMin(),
                secondaryVerticalScrollBar.getMax(),
                secondaryVerticalScrollBar.getValue(),
                secondaryVerticalScrollBar.getVisibleAmount()
        );
        double top = renderHeight * indicator.startFraction();
        double viewportHeight = Math.max(Math.min(12d, renderHeight), renderHeight * indicator.heightFraction());
        viewportHeight = Math.min(renderHeight, viewportHeight);
        top = Math.max(0d, Math.min(renderHeight - viewportHeight, top));
        double viewportX = Math.max(0d, miniMapContentX(secondaryMiniMapHost) - 2d);
        double viewportWidth = Math.min(secondaryMiniMapHost.getWidth() - viewportX, miniMapContentWidth(secondaryMiniMapHost) + 4d);
        secondaryMiniMapViewport.setVisible(true);
        secondaryMiniMapViewport.resizeRelocate(viewportX, top, viewportWidth, viewportHeight);
    }

    private void jumpToMiniMapPosition(double mouseY) {
        double renderHeight = Math.max(0d, Math.min(miniMapHost.getHeight(), miniMapRenderHeight));
        if (!miniMapVisible || verticalScrollBar == null || renderHeight <= 0d) {
            return;
        }

        double clickFraction = Math.max(0d, Math.min(1d, mouseY / renderHeight));
        double nextValue = MiniMapSupport.scrollValueForFraction(
                clickFraction,
                verticalScrollBar.getMin(),
                verticalScrollBar.getMax(),
                verticalScrollBar.getVisibleAmount()
        );
        verticalScrollBar.setValue(nextValue);
        codeArea.requestFocus();
    }

    private void jumpToSecondaryMiniMapPosition(double mouseY) {
        double renderHeight = Math.max(0d, Math.min(secondaryMiniMapHost.getHeight(), secondaryMiniMapRenderHeight));
        if (!miniMapVisible || secondaryVerticalScrollBar == null || renderHeight <= 0d || secondaryCodeArea == null) {
            return;
        }

        double clickFraction = Math.max(0d, Math.min(1d, mouseY / renderHeight));
        double nextValue = MiniMapSupport.scrollValueForFraction(
                clickFraction,
                secondaryVerticalScrollBar.getMin(),
                secondaryVerticalScrollBar.getMax(),
                secondaryVerticalScrollBar.getVisibleAmount()
        );
        secondaryVerticalScrollBar.setValue(nextValue);
        secondaryCodeArea.requestFocus();
    }

    private void configureFadeRegion(Region region, String styleClass) {
        region.setManaged(false);
        region.setMouseTransparent(true);
        region.getStyleClass().add(styleClass);
    }

    private void updateMiniMapFadeRegions(Region topFade,
                                          Region bottomFade,
                                          StackPane host,
                                          double contentWidth,
                                          double renderHeight) {
        double fadeHeight = Math.min(MINI_MAP_FADE_HEIGHT, Math.max(0d, renderHeight / 3d));
        boolean showFade = fadeHeight >= 6d;
        topFade.setVisible(showFade);
        bottomFade.setVisible(showFade);
        if (!showFade) {
            return;
        }
        double x = miniMapContentX(host);
        double y = Math.max(0d, renderHeight - fadeHeight);
        topFade.resizeRelocate(x, 0d, contentWidth, fadeHeight);
        bottomFade.resizeRelocate(x, y, contentWidth, fadeHeight);
    }

    private double miniMapContentX(StackPane host) {
        return Math.min(Math.max(0d, host.getWidth() - 12d), MINI_MAP_CONTENT_LEFT_INSET);
    }

    private double miniMapContentRightInset(StackPane host) {
        return Math.min(Math.max(0d, host.getWidth() - miniMapContentX(host) - 8d), MINI_MAP_CONTENT_RIGHT_INSET);
    }

    private double miniMapContentWidth(StackPane host) {
        return Math.max(24d, host.getWidth() - miniMapContentX(host) - miniMapContentRightInset(host));
    }

    private HighlightRange firstUncoveredRange(int start, int end) {
        if (end <= start) {
            return null;
        }

        List<HighlightRange> blockedRanges = new ArrayList<>(completedHighlightRanges.size() + pendingHighlightRanges.size());
        blockedRanges.addAll(completedHighlightRanges);
        blockedRanges.addAll(pendingHighlightRanges);
        blockedRanges.sort(Comparator.comparingInt(HighlightRange::start).thenComparingInt(HighlightRange::end));

        int cursor = start;
        for (HighlightRange blocked : blockedRanges) {
            if (blocked.end() <= cursor) {
                continue;
            }
            if (blocked.start() > cursor) {
                return new HighlightRange(cursor, Math.min(end, blocked.start()));
            }
            cursor = Math.max(cursor, blocked.end());
            if (cursor >= end) {
                return null;
            }
        }
        return new HighlightRange(cursor, end);
    }

    private void mergeHighlightRange(List<HighlightRange> ranges, HighlightRange addition) {
        int mergedStart = addition.start();
        int mergedEnd = addition.end();
        for (int index = 0; index < ranges.size(); ) {
            HighlightRange current = ranges.get(index);
            if (current.end() < mergedStart || current.start() > mergedEnd) {
                index++;
                continue;
            }
            mergedStart = Math.min(mergedStart, current.start());
            mergedEnd = Math.max(mergedEnd, current.end());
            ranges.remove(index);
        }
        ranges.add(new HighlightRange(mergedStart, mergedEnd));
    }

    private int normalizeLineIndex(int lineIndex) {
        int paragraphCount = Math.max(1, codeArea.getParagraphs().size());
        return Math.max(0, Math.min(lineIndex, paragraphCount - 1));
    }

    private boolean ensureSplit(Orientation orientation) {
        if (hasSplitView()) {
            splitOrientation = orientation;
            splitEditorPane.setOrientation(orientation);
            return false;
        }

        splitOrientation = orientation;
        secondaryCodeArea = createSecondaryCodeArea();
        secondaryContainer = new VirtualizedScrollPane<>(secondaryCodeArea);
        secondaryContainer.setMinHeight(0d);
        secondaryContainer.setMaxHeight(Double.MAX_VALUE);
        secondaryMiniMapHost.setManaged(miniMapVisible);
        secondaryMiniMapHost.setVisible(miniMapVisible);
        HBox secondaryEditorPane = new HBox();
        secondaryEditorPane.getStyleClass().add("editor-document-editor-pane");
        secondaryEditorPane.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        secondaryEditorPane.setFillHeight(true);
        secondaryEditorPane.setMinHeight(0d);
        secondaryEditorPane.setPrefHeight(0d);
        secondaryEditorPane.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(secondaryContainer, Priority.ALWAYS);
        secondaryEditorPane.getChildren().setAll(secondaryContainer, secondaryMiniMapHost);

        splitEditorPane = new SplitPane();
        splitEditorPane.getStyleClass().add("editor-document-split-pane");
        splitEditorPane.setOrientation(orientation);
        splitEditorPane.getItems().setAll(primaryEditorPane, secondaryEditorPane);
        splitEditorPane.setDividerPositions(0.5d);

        editorHost.getChildren().setAll(splitEditorPane);
        attachSecondaryMiniMapListeners();
        scheduleMiniMapBinding();
        secondaryCodeArea.requestFocus();
        return true;
    }

    private CodeArea createSecondaryCodeArea() {
        CodeArea secondary = new CodeArea();
        secondary.getStyleClass().setAll(codeArea.getStyleClass());
        secondary.setWrapText(codeArea.isWrapText());
        secondary.setStyle(codeArea.getStyle());
        secondary.replaceText(codeArea.getText());
        secondary.moveTo(codeArea.getCaretPosition());
        secondary.setEditable(codeArea.isEditable());
        wireSplitEditorSync(codeArea, secondary);
        splitEditorInitializer.accept(secondary);
        return secondary;
    }

    private void wireSplitEditorSync(CodeArea primary, CodeArea secondary) {
        primarySplitTextSyncListener = (observable, previous, current) -> queueSplitEditorTextSync(primary, secondary);
        secondarySplitTextSyncListener = (observable, previous, current) -> queueSplitEditorTextSync(secondary, primary);
        primarySplitCaretSyncListener = (observable, previous, current) -> syncSplitEditorCaret(primary, secondary);
        secondarySplitCaretSyncListener = (observable, previous, current) -> syncSplitEditorCaret(secondary, primary);
        primary.textProperty().addListener(primarySplitTextSyncListener);
        secondary.textProperty().addListener(secondarySplitTextSyncListener);
        primary.caretPositionProperty().addListener(primarySplitCaretSyncListener);
        secondary.caretPositionProperty().addListener(secondarySplitCaretSyncListener);
    }

    private void attachSecondaryMiniMapListeners() {
        if (secondaryCodeArea == null || secondaryContainer == null) {
            return;
        }

        secondaryMiniMapTextListener = (observable, previous, current) -> redrawSecondaryMiniMap();
        secondaryMiniMapSceneListener = (observable, previous, current) -> scheduleMiniMapBinding();
        secondaryMiniMapLayoutListener = (observable, previous, current) -> scheduleMiniMapBinding();
        secondaryCodeArea.textProperty().addListener(secondaryMiniMapTextListener);
        secondaryCodeArea.sceneProperty().addListener(secondaryMiniMapSceneListener);
        secondaryContainer.layoutBoundsProperty().addListener(secondaryMiniMapLayoutListener);
    }

    private void removeSplitEditorListeners() {
        if (primarySplitTextSyncListener != null) {
            codeArea.textProperty().removeListener(primarySplitTextSyncListener);
            primarySplitTextSyncListener = null;
        }
        if (primarySplitCaretSyncListener != null) {
            codeArea.caretPositionProperty().removeListener(primarySplitCaretSyncListener);
            primarySplitCaretSyncListener = null;
        }
        if (secondaryCodeArea != null) {
            if (secondarySplitTextSyncListener != null) {
                secondaryCodeArea.textProperty().removeListener(secondarySplitTextSyncListener);
                secondarySplitTextSyncListener = null;
            }
            if (secondarySplitCaretSyncListener != null) {
                secondaryCodeArea.caretPositionProperty().removeListener(secondarySplitCaretSyncListener);
                secondarySplitCaretSyncListener = null;
            }
            if (secondaryMiniMapTextListener != null) {
                secondaryCodeArea.textProperty().removeListener(secondaryMiniMapTextListener);
                secondaryMiniMapTextListener = null;
            }
            if (secondaryMiniMapSceneListener != null) {
                secondaryCodeArea.sceneProperty().removeListener(secondaryMiniMapSceneListener);
                secondaryMiniMapSceneListener = null;
            }
        }
        if (secondaryContainer != null && secondaryMiniMapLayoutListener != null) {
            secondaryContainer.layoutBoundsProperty().removeListener(secondaryMiniMapLayoutListener);
            secondaryMiniMapLayoutListener = null;
        }
    }

    private void queueSplitEditorTextSync(CodeArea source, CodeArea target) {
        if (source == null || target == null) {
            return;
        }
        pendingSplitSyncSource = source;
        pendingSplitSyncTarget = target;
        if (splitTextSyncScheduled) {
            return;
        }
        splitTextSyncScheduled = true;
        Platform.runLater(this::flushSplitEditorTextSync);
    }

    private void flushSplitEditorTextSync() {
        splitTextSyncScheduled = false;
        CodeArea source = pendingSplitSyncSource;
        CodeArea target = pendingSplitSyncTarget;
        pendingSplitSyncSource = null;
        pendingSplitSyncTarget = null;
        syncSplitEditorText(source, target);
    }

    private void syncSplitEditorText(CodeArea source, CodeArea target) {
        if (synchronizingSplitEditors || target == null || source == null) {
            if (source != null && target != null) {
                queueSplitEditorTextSync(source, target);
            }
            return;
        }
        if (Objects.equals(source.getText(), target.getText())) {
            return;
        }
        synchronizingSplitEditors = true;
        try {
            int targetCaret = Math.max(0, Math.min(source.getCaretPosition(), source.getLength()));
            target.replaceText(source.getText());
            target.moveTo(Math.min(targetCaret, target.getLength()));
        } finally {
            synchronizingSplitEditors = false;
        }
    }

    private void syncSplitEditorCaret(CodeArea source, CodeArea target) {
        if (synchronizingSplitEditors || target == null || source == null) {
            return;
        }
        int sourceCaret = source.getCaretPosition();
        int targetCaret = Math.max(0, Math.min(sourceCaret, target.getLength()));
        if (target.getCaretPosition() == targetCaret) {
            return;
        }
        synchronizingSplitEditors = true;
        try {
            target.moveTo(targetCaret);
        } finally {
            synchronizingSplitEditors = false;
        }
    }

    private int bookmarkIndexForLine(int lineIndex) {
        for (int index = 0; index < bookmarks.size(); index++) {
            if (bookmarks.get(index).lineIndex() == lineIndex) {
                return index;
            }
        }
        return -1;
    }

    private List<Integer> bookmarkLineIndexes() {
        return bookmarks.stream()
                .map(BookmarkAnchor::lineIndex)
                .sorted()
                .toList();
    }

    private void sortBookmarks() {
        bookmarks.sort(Comparator.comparingInt(BookmarkAnchor::lineIndex));
    }

    private String lineTextAt(int lineIndex) {
        int normalizedLineIndex = normalizeLineIndex(lineIndex);
        return codeArea.getParagraph(normalizedLineIndex).getText();
    }

    private int findNearestMatchingLine(List<String> paragraphTexts,
                                        BookmarkAnchor bookmark,
                                        HashSet<Integer> claimedLines) {
        int bestLine = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int lineIndex = 0; lineIndex < paragraphTexts.size(); lineIndex++) {
            if (claimedLines.contains(lineIndex) || !Objects.equals(paragraphTexts.get(lineIndex), bookmark.lineText())) {
                continue;
            }
            int distance = Math.abs(lineIndex - bookmark.lineIndex());
            if (bestLine < 0 || distance < bestDistance || (distance == bestDistance && lineIndex < bestLine)) {
                bestLine = lineIndex;
                bestDistance = distance;
            }
        }
        return bestLine;
    }

    private int findNearestAvailableLine(int preferredLine,
                                         int maxLineIndex,
                                         HashSet<Integer> claimedLines) {
        if (!claimedLines.contains(preferredLine)) {
            return preferredLine;
        }
        for (int distance = 1; distance <= maxLineIndex + 1; distance++) {
            int lower = preferredLine - distance;
            if (lower >= 0 && !claimedLines.contains(lower)) {
                return lower;
            }
            int upper = preferredLine + distance;
            if (upper <= maxLineIndex && !claimedLines.contains(upper)) {
                return upper;
            }
        }
        return Math.max(0, Math.min(preferredLine, maxLineIndex));
    }

    private record HighlightRange(int start, int end) {
    }

    private record BookmarkAnchor(int lineIndex, String lineText) {
    }
}

