package org.adriandeleon.editora;

import atlantafx.base.controls.Breadcrumbs;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.adriandeleon.editora.commands.CommandAction;
import org.adriandeleon.editora.documents.EditorDocument;
import org.adriandeleon.editora.editor.EmacsEditing;
import org.adriandeleon.editora.editor.EmacsNavigation;
import org.adriandeleon.editora.editor.FindFileSupport;
import org.adriandeleon.editora.editor.ProgressiveHighlightSupport;
import org.adriandeleon.editora.editor.ToolWindowLayoutSupport;
import org.adriandeleon.editora.languages.Diagnostic;
import org.adriandeleon.editora.languages.DiagnosticSeverity;
import org.adriandeleon.editora.languages.LanguageAnalysis;
import org.adriandeleon.editora.languages.LanguagePreviewSpec;
import org.adriandeleon.editora.languages.LanguageService;
import org.adriandeleon.editora.languages.LanguageServiceRegistry;
import org.adriandeleon.editora.languages.PlainTextLanguageService;
import org.adriandeleon.editora.plugins.EditoraContext;
import org.adriandeleon.editora.plugins.PluginManager;
import org.adriandeleon.editora.persistence.EditoraPersistence;
import org.adriandeleon.editora.persistence.PersistenceFolderSupport;
import org.adriandeleon.editora.session.SessionManager;
import org.adriandeleon.editora.session.WorkspaceSession;
import org.adriandeleon.editora.settings.CommandPaletteShortcut;
import org.adriandeleon.editora.settings.EditorSettings;
import org.adriandeleon.editora.settings.ReadOnlyOpenRules;
import org.adriandeleon.editora.settings.SettingsManager;
import org.adriandeleon.editora.settings.ToolWindowSide;
import org.adriandeleon.editora.status.StatusBarSupport;
import org.adriandeleon.editora.theme.EditorTheme;
import org.adriandeleon.editora.theme.ThemeManager;
import org.adriandeleon.editora.window.WindowStateSupport;
import org.kordamp.ikonli.javafx.FontIcon;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class EditorController {

    private static final Set<String> EXCLUDED_PROJECT_ENTRIES = Set.of(".git", ".idea", "target");
    private static final int MAX_RECENT_FILES = 12;
    private static final int MAX_FIND_FILE_RESULTS = 200;
    private static final int MAX_FIND_FILE_HISTORY = 25;
    private static final int MAX_FIND_FILE_PREVIEW_LINES = 60;
    private static final int MAX_FIND_FILE_PREVIEW_CHARACTERS = 4_000;
    private static final double DEFAULT_TOOL_DOCK_WIDTH = 260d;
    private static final double MIN_TOOL_DOCK_WIDTH = 220d;
    private static final PseudoClass TOOLBAR_ACTIVE_PSEUDO_CLASS = PseudoClass.getPseudoClass("toolbar-active");
    private static final javafx.util.Duration TOOLBAR_CLICK_FEEDBACK_DURATION = javafx.util.Duration.millis(220);
    private static final String APPLICATION_NAME = "Editora";
    private static final String APPLICATION_VERSION = Optional.ofNullable(EditoraApplication.class.getPackage().getImplementationVersion())
            .orElse("1.0-SNAPSHOT");
    private static final String APPLICATION_AUTHOR = "Adrian De Leon";

    @FXML
    private StackPane rootStack;

    @FXML
    private BorderPane rootPane;

    @FXML
    private TabPane editorTabPane;

    @FXML
    private TextField searchField;

    @FXML
    private TextField replaceField;

    @FXML
    private ToggleButton caseSensitiveToggle;

    @FXML
    private ToggleButton wholeWordToggle;

    @FXML
    private ToggleButton regexToggle;

    @FXML
    private HBox searchBar;

    @FXML
    private Label searchResultsLabel;

    @FXML
    private MenuButton recentFilesMenuButton;

    @FXML
    private MenuButton pluginMenuButton;

    @FXML
    private Label workspaceRootLabel;

    @FXML
    private TreeView<Path> projectTreeView;

    @FXML
    private VBox projectExplorerPane;

    @FXML
    private SplitPane centerSplitPane;

    @FXML
    private VBox leftToolWindowRail;

    @FXML
    private VBox rightToolWindowRail;

    @FXML
    private HBox bottomToolWindowRail;

    @FXML
    private Button projectExplorerLeftRailButton;

    @FXML
    private Button projectExplorerRightRailButton;

    @FXML
    private Label messageStatusLabel;

    @FXML
    private Label documentStatusLabel;

    @FXML
    private Label caretStatusLabel;

    @FXML
    private Label languageStatusLabel;

    @FXML
    private HBox statusBar;

    @FXML
    private HBox statusPathRow;

    @FXML
    private Breadcrumbs<StatusBarSupport.BreadcrumbEntry> documentPathBreadcrumbs;

    @FXML
    private Button undoButton;

    @FXML
    private Button redoButton;

    @FXML
    private Button cutButton;

    @FXML
    private Button copyButton;

    @FXML
    private Button pasteButton;

    @FXML
    private Button newTabToolbarButton;

    @FXML
    private Button closeTabToolbarButton;

    @FXML
    private Button openFileToolbarButton;

    @FXML
    private Button saveFileToolbarButton;

    @FXML
    private Button saveFileAsToolbarButton;

    @FXML
    private Button commandPaletteToolbarButton;

    @FXML
    private Button searchToolbarButton;

    @FXML
    private Button projectExplorerToolbarButton;

    @FXML
    private Button statusBarToolbarButton;

    @FXML
    private Button settingsToolbarButton;

    @FXML
    private Button readOnlyToolbarButton;

    @FXML
    private HBox readOnlyStatusChip;

    @FXML
    private FontIcon readOnlyStatusIcon;

    @FXML
    private Label readOnlyStatusLabel;

    @FXML
    private StackPane commandPaletteOverlay;

    @FXML
    private Tooltip commandPaletteToolbarTooltip;

    @FXML
    private TextField commandPaletteField;

    @FXML
    private Label commandPaletteShortcutLabel;

    @FXML
    private ListView<CommandAction> commandPaletteListView;

    @FXML
    private StackPane findFileOverlay;

    @FXML
    private TextField findFileField;

    @FXML
    private Label findFileHintLabel;

    @FXML
    private ListView<FindFileSupport.Match> findFileListView;

    @FXML
    private Label findFilePreviewTitleLabel;

    @FXML
    private TextArea findFilePreviewArea;

    @FXML
    private StackPane versionOverlay;

    @FXML
    private Label versionTitleLabel;

    @FXML
    private Label versionValueLabel;

    @FXML
    private Label versionAuthorLabel;

    @FXML
    private Label versionDetailsLabel;

    @FXML
    private StackPane settingsOverlay;

    @FXML
    private BorderPane settingsContentHost;

    private final Map<Tab, EditorDocument> documentsByTab = new LinkedHashMap<>();
    private final ObservableList<CommandAction> builtInCommands = FXCollections.observableArrayList();
    private final ObservableList<CommandAction> recentFileCommands = FXCollections.observableArrayList();
    private final ObservableList<CommandAction> pluginCommands = FXCollections.observableArrayList();
    private final ObservableList<CommandAction> commands = FXCollections.observableArrayList();
    private final ObservableList<CommandAction> paletteResults = FXCollections.observableArrayList();
    private final ObservableList<CommandAction> pluginMenuActions = FXCollections.observableArrayList();
    private final ObservableList<Path> recentFiles = FXCollections.observableArrayList();
    private final ObservableList<FindFileSupport.Match> findFileResults = FXCollections.observableArrayList();
    private final Map<String, Integer> commandUsage = new HashMap<>();
    private final Map<Button, PauseTransition> toolbarClickFeedback = new HashMap<>();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("editora-analysis-", 0).factory()
    );

    private final PluginManager pluginManager = new PluginManager(Path.of("plugins"));
    private final LanguageServiceRegistry languageServices = LanguageServiceRegistry.getInstance();

    private EditorSettings currentSettings = SettingsManager.load();
    private SettingsController settingsController;
    private Path lastUsedDirectory;
    private Path workspaceRoot;
    private int untitledCounter = 1;
    private boolean acceleratorsInstalled;
    private boolean searchBarVisible = true;
    private boolean toolDockVisible = true;
    private boolean statusBarVisible = true;
    private ToolWindowSide toolDockSide = currentSettings.toolDockSide();
    private double expandedToolDockDividerPosition = 0.22;
    private double expandedToolDockPrefWidth = DEFAULT_TOOL_DOCK_WIDTH;
    private double expandedToolDockMinWidth = MIN_TOOL_DOCK_WIDTH;
    private String lastCommandPaletteFilter = "";
    private double windowWidth = 1440;
    private double windowHeight = 920;
    private double windowX = Double.NaN;
    private double windowY = Double.NaN;
    private boolean windowMaximized;
    private Timeline searchBarAnimation;
    private Timeline toolDockAnimation;
    private KeyCombination commandPaletteAccelerator;
    private double searchBarExpandedHeight = 52;
    private String lastFindFileQuery = "";
    private final List<String> findFileHistory = new ArrayList<>();
    private List<Path> findFileCandidates = List.of();
    private int findFileHistoryIndex = -1;
    private String findFileHistoryDraft = "";
    private boolean applyingFindFileHistory;
    private EditorTheme settingsThemeBeforePreview;
    private boolean settingsThemePreviewActive;
    private volatile boolean shuttingDown;
    private SplitPane.Divider trackedToolDockDivider;
    private final ChangeListener<Number> toolDockDividerPositionListener = (observable, ignored, current) -> {
        Objects.requireNonNull(observable);
        if (toolDockVisible) {
            expandedToolDockDividerPosition = current.doubleValue();
        }
    };

    @FXML
    private void initialize() {
        Path defaultWorkspaceRoot = Path.of("").toAbsolutePath().normalize();
        WorkspaceSession session = SessionManager.loadWorkspaceSession(defaultWorkspaceRoot);
        workspaceRoot = session.workspaceRoot();
        searchBarVisible = currentSettings.searchBarVisible();
        toolDockVisible = currentSettings.toolDockVisible();
        toolDockSide = currentSettings.toolDockSide();
        statusBarVisible = session.statusBarVisible();
        expandedToolDockDividerPosition = session.toolDockDividerPosition();
        expandedToolDockPrefWidth = Math.max(MIN_TOOL_DOCK_WIDTH, session.toolDockWidth());
        lastCommandPaletteFilter = session.commandPaletteFilter();
        lastFindFileQuery = session.findFileQuery();
        findFileHistory.clear();
        findFileHistory.addAll(session.findFileHistory());
        windowWidth = session.windowWidth();
        windowHeight = session.windowHeight();
        windowX = session.windowX();
        windowY = session.windowY();
        windowMaximized = session.windowMaximized();
        recentFiles.setAll(SessionManager.loadRecentFiles());

        configureProjectTree();
        configureCommandPalette();
        configureFindFilePrompt();
        configureStatusBar();
        registerBuiltInCommands();
        rebuildRecentFilesMenu();
        rebuildRecentFileCommands();
        loadSettingsView();

        searchField.setText(session.searchText());
        replaceField.setText(session.replaceText());
        caseSensitiveToggle.setSelected(session.searchCaseSensitive());
        wholeWordToggle.setSelected(session.searchWholeWord());
        regexToggle.setSelected(session.searchRegex());
        commandPaletteField.setText(lastCommandPaletteFilter);
        searchField.setOnAction(event -> handleEvent(event, () -> findMatch(true)));
        replaceField.setOnAction(event -> handleEvent(event, this::replaceSelection));
        searchField.textProperty().addListener(onChange(this::refreshSearchUi));
        caseSensitiveToggle.selectedProperty().addListener(onChange(this::refreshSearchUi));
        wholeWordToggle.selectedProperty().addListener(onChange(this::refreshSearchUi));
        regexToggle.selectedProperty().addListener(onChange(this::refreshSearchUi));
        searchBar.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSearchBarKeys);
        projectExplorerPane.addEventFilter(KeyEvent.KEY_PRESSED, this::handleProjectExplorerKeys);

        editorTabPane.getSelectionModel().selectedItemProperty().addListener(onCurrentChange(current ->
                updateEditorState(getDocument(current).orElse(null))));
        rootPane.sceneProperty().addListener(onCurrentChange(current -> {
            if (current != null) {
                ThemeManager.apply(currentSettings.theme(), rootStack);
                if (!acceleratorsInstalled) {
                    installAccelerators(current);
                    acceleratorsInstalled = true;
                }
            }
        }));

        commandPaletteOverlay.setOnMouseClicked(event -> {
            if (event.getTarget() == commandPaletteOverlay) {
                hideCommandPalette();
            }
        });
        findFileOverlay.setOnMouseClicked(event -> {
            if (event.getTarget() == findFileOverlay) {
                hideFindFilePrompt(true);
            }
        });
        versionOverlay.setOnMouseClicked(event -> {
            if (event.getTarget() == versionOverlay) {
                hideVersionOverlay(true);
            }
        });
        settingsOverlay.setOnMouseClicked(event -> {
            if (event.getTarget() == settingsOverlay) {
                hideSettingsView();
            }
        });
        projectExplorerPane.widthProperty().addListener(onCurrentChange(newValue -> {
            if (toolDockVisible && newValue.doubleValue() > 0) {
                expandedToolDockPrefWidth = newValue.doubleValue();
            }
        }));
        centerSplitPane.getDividers().addListener((ListChangeListener<SplitPane.Divider>) change -> bindToolDockDividerTracking());
        bindToolDockDividerTracking();
        refreshToolWindowRails();

        setWorkspaceRoot(workspaceRoot, false);
        loadPlugins();
        boolean restored = restoreWorkspaceSession(session);
        if (!restored) {
            createNewTab();
        }

        applySettings(currentSettings, false);
        applySearchBarVisibility(false, false);
        applyToolDockVisibility(false, false);
        applyStatusBarVisibility(false);
        syncToolbarButtonStates();
        updateEditorState(getActiveDocument().orElse(null));
        Platform.runLater(this::captureSearchBarExpandedHeight);
        statusMessage(restored ? "Restored previous session" : "Ready");
    }

    public boolean requestCloseAllDocuments() {
        for (EditorDocument document : List.copyOf(documentsByTab.values())) {
            if (!confirmCloseDocument(document)) {
                return false;
            }
        }

        return true;
    }

    public void shutdown() {
        shuttingDown = true;
        analysisExecutor.shutdownNow();
        SessionManager.saveRecentFiles(recentFiles);
        SessionManager.saveWorkspaceSession(buildCurrentSession());
    }

    public void applyStageState(Stage stage) {
        double restoredWidth = windowWidth > 0 ? windowWidth : 1440;
        double restoredHeight = windowHeight > 0 ? windowHeight : 920;
        stage.setWidth(restoredWidth);
        stage.setHeight(restoredHeight);

        List<Rectangle2D> visualBounds = Screen.getScreens().stream()
                .map(Screen::getVisualBounds)
                .toList();
        WindowStateSupport.Position position = WindowStateSupport.resolveVisiblePosition(
                restoredWidth,
                restoredHeight,
                windowX,
                windowY,
                visualBounds
        );
        stage.setX(position.x());
        stage.setY(position.y());
        stage.setMaximized(windowMaximized);
    }

    public void captureWindowState(Stage stage) {
        windowWidth = stage.getWidth();
        windowHeight = stage.getHeight();
        windowX = stage.getX();
        windowY = stage.getY();
        windowMaximized = stage.isMaximized();
    }

    @FXML
    private void onNewTab() {
        animateToolbarClick(newTabToolbarButton);
        createNewTab();
    }

    @FXML
    private void onOpenFile() {
        animateToolbarClick(openFileToolbarButton);
        FileChooser chooser = createFileChooser("Open File", getActiveDocument().orElse(null));
        var selectedFile = chooser.showOpenDialog(getWindow());
        if (selectedFile != null) {
            openFile(selectedFile.toPath());
        }
    }

    @FXML
    private void onOpenWorkspaceFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Workspace Folder");
        if (workspaceRoot != null && Files.isDirectory(workspaceRoot)) {
            chooser.setInitialDirectory(workspaceRoot.toFile());
        }

        var selectedFolder = chooser.showDialog(getWindow());
        if (selectedFolder != null) {
            setWorkspaceRoot(selectedFolder.toPath(), true);
            statusMessage("Workspace set to " + selectedFolder.getName());
        }
    }

    @FXML
    private void onRefreshWorkspace() {
        refreshWorkspaceTree();
        statusMessage("Workspace refreshed");
    }

    @FXML
    private void onSaveFile() {
        animateToolbarClick(saveFileToolbarButton);
        saveActiveDocument(false);
    }

    @FXML
    private void onSaveFileAs() {
        animateToolbarClick(saveFileAsToolbarButton);
        saveActiveDocument(true);
    }

    @FXML
    private void onCloseCurrentTab() {
        animateToolbarClick(closeTabToolbarButton);
        getActiveDocument().ifPresent(this::closeDocument);
    }

    @FXML
    private void onUndo() {
        animateToolbarClick(undoButton);
        undoActiveEdit();
    }

    @FXML
    private void onRedo() {
        animateToolbarClick(redoButton);
        redoActiveEdit();
    }

    @FXML
    private void onCut() {
        animateToolbarClick(cutButton);
        cutActiveSelection();
    }

    @FXML
    private void onCopy() {
        animateToolbarClick(copyButton);
        copyActiveSelection();
    }

    @FXML
    private void onPaste() {
        animateToolbarClick(pasteButton);
        pasteIntoActiveEditor();
    }

    @FXML
    private void onShowCommandPalette() {
        showCommandPalette();
    }

    @FXML
    private void onToggleSearchBar() {
        toggleSearchBarVisibility();
    }

    @FXML
    private void onToggleProjectExplorer() {
        toggleToolDockVisibility();
    }

    @FXML
    private void onToggleStatusBar() {
        statusBarVisible = !statusBarVisible;
        applyStatusBarVisibility(true);
    }

    @FXML
    private void onToggleTheme() {
        EditorTheme nextTheme = currentSettings.theme().next();
        setTheme(nextTheme);
        statusMessage("Theme switched to " + nextTheme.getDisplayName());
    }

    @FXML
    private void onToggleReadOnly() {
        animateToolbarClick(readOnlyToolbarButton);
        toggleReadOnly();
    }

    @FXML
    private void onToggleReadOnlyFromStatus(MouseEvent event) {
        if (event != null) {
            event.consume();
        }
        toggleReadOnly();
    }

    @FXML
    private void onShowSettings() {
        showSettingsView();
    }

    @FXML
    private void onExitApplication() {
        exitApplication();
    }

    @FXML
    private void onHideVersionOverlay() {
        hideVersionOverlay(true);
    }

    @FXML
    private void onFindNext() {
        findMatch(true);
    }

    @FXML
    private void onFindPrevious() {
        findMatch(false);
    }

    @FXML
    private void onReplaceSelection() {
        replaceSelection();
    }

    @FXML
    private void onReplaceAll() {
        replaceAll();
    }

    private void configureProjectTree() {
        projectTreeView.setShowRoot(true);
        projectTreeView.setCellFactory(this::createProjectTreeCell);
        
        projectTreeView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedProjectTreeItem();
            }
        });
        projectTreeView.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelectedProjectTreeItem();
                event.consume();
            }
        });
    }

    private TreeCell<Path> createProjectTreeCell(TreeView<Path> treeView) {
        Objects.requireNonNull(treeView);
        return new TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(displayPath(item));
                    setTooltip(new Tooltip(item.toString()));
                }
            }
        };
    }

    private void configureCommandPalette() {
        commandPaletteListView.setItems(paletteResults);
        commandPaletteListView.setPlaceholder(new Label("No matching commands"));
        commandPaletteListView.setCellFactory(this::createCommandPaletteCell);

        commandPaletteField.textProperty().addListener(onCurrentChange(this::filterCommands));
        commandPaletteField.setOnAction(event -> handleEvent(event, this::executeSelectedCommand));
        commandPaletteField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCommandPaletteFieldKeys);
        commandPaletteListView.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCommandPaletteListKeys);
        commandPaletteListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                executeSelectedCommand();
            }
        });
    }

    private ListCell<CommandAction> createCommandPaletteCell(ListView<CommandAction> listView) {
        Objects.requireNonNull(listView);
        return new ListCell<>() {
            @Override
            protected void updateItem(CommandAction item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                FontIcon commandIcon = createCommandIcon(item, 16, "command-icon");
                Label nameLabel = new Label(item.name());
                nameLabel.getStyleClass().add("command-name");

                Label categoryLabel = new Label(item.category());
                categoryLabel.getStyleClass().add("command-category");

                Label shortcutLabel = new Label(item.shortcutHint().isBlank() ? "" : item.shortcutHint());
                shortcutLabel.getStyleClass().add("command-shortcut");
                shortcutLabel.setVisible(!item.shortcutHint().isBlank());
                shortcutLabel.setManaged(!item.shortcutHint().isBlank());

                Region spacer = new Region();
                HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                HBox header = new HBox(10, commandIcon, nameLabel, spacer, categoryLabel, shortcutLabel);
                header.setAlignment(Pos.CENTER_LEFT);

                Label descriptionLabel = new Label(item.description());
                descriptionLabel.getStyleClass().add("command-description");
                descriptionLabel.setWrapText(true);

                VBox content = new VBox(4, header, descriptionLabel);
                content.getStyleClass().add("command-cell");
                setGraphic(content);
            }
        };
    }

    private void configureFindFilePrompt() {
        findFileListView.setItems(findFileResults);
        findFileListView.setPlaceholder(new Label("No matching files"));
        findFileListView.setCellFactory(this::createFindFileCell);

        findFileField.textProperty().addListener(onCurrentChange(current -> {
            lastFindFileQuery = current == null ? "" : current;
            if (!applyingFindFileHistory) {
                findFileHistoryDraft = lastFindFileQuery;
                findFileHistoryIndex = -1;
            }
            refreshFindFileResults();
        }));
        findFileField.setOnAction(event -> handleEvent(event, this::acceptFindFileInput));
        findFileField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleFindFileFieldKeys);
        findFileListView.addEventFilter(KeyEvent.KEY_PRESSED, this::handleFindFileListKeys);
        findFileListView.getSelectionModel().selectedItemProperty().addListener(onCurrentChange(current -> {
            updateFindFileHint(current);
            updateFindFilePreview(current);
        }));
        findFileListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                acceptFindFileInput();
            }
        });
        updateFindFileHint(null);
        updateFindFilePreview(null);
    }

    private ListCell<FindFileSupport.Match> createFindFileCell(ListView<FindFileSupport.Match> listView) {
        Objects.requireNonNull(listView);
        return new ListCell<>() {
            @Override
            protected void updateItem(FindFileSupport.Match item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                FontIcon icon = createIcon(item.directory() ? "bi-folder2-open" : "bi-file-earmark-text", 16, "command-icon");
                Label pathLabel = new Label(item.displayPath());
                pathLabel.getStyleClass().add("command-name");

                HBox badges = new HBox(6);
                badges.setAlignment(Pos.CENTER_LEFT);
                if (item.recent()) {
                    Label recentBadge = new Label("Recent");
                    recentBadge.getStyleClass().addAll("find-file-badge", "find-file-badge-recent");
                    badges.getChildren().add(recentBadge);
                }
                if (item.open()) {
                    Label openBadge = new Label("Open");
                    openBadge.getStyleClass().addAll("find-file-badge", "find-file-badge-open");
                    badges.getChildren().add(openBadge);
                }

                Region spacer = new Region();
                HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                HBox header = new HBox(10, icon, pathLabel, spacer, badges);
                header.setAlignment(Pos.CENTER_LEFT);

                Label parentLabel = new Label(item.parentPath());
                parentLabel.getStyleClass().add("command-description");

                VBox content = new VBox(4, header, parentLabel);
                content.getStyleClass().add("command-cell");
                setGraphic(content);
            }
        };
    }

    private void refreshFindFileResults() {
        List<Path> openPaths = documentsByTab.values().stream()
                .map(EditorDocument::getFilePath)
                .filter(Objects::nonNull)
                .toList();

        findFileResults.setAll(FindFileSupport.rankMatches(
                workspaceRoot,
                findFileCandidates,
                recentFiles,
                openPaths,
                findFileField == null ? lastFindFileQuery : findFileField.getText(),
                MAX_FIND_FILE_RESULTS
        ));

        if (!findFileResults.isEmpty()) {
            findFileListView.getSelectionModel().selectFirst();
            findFileListView.scrollTo(0);
        } else if (findFileListView != null) {
            findFileListView.getSelectionModel().clearSelection();
        }
        FindFileSupport.Match selectedMatch = findFileListView == null ? null : findFileListView.getSelectionModel().getSelectedItem();
        updateFindFileHint(selectedMatch);
        updateFindFilePreview(selectedMatch);
    }

    private void updateFindFileHint(FindFileSupport.Match selectedMatch) {
        if (findFileHintLabel == null) {
            return;
        }

        if (selectedMatch != null) {
            String kind = selectedMatch.directory() ? "Directory" : "File";
            StringBuilder hint = new StringBuilder(kind + " • " + selectedMatch.parentPath());
            if (selectedMatch.recent()) {
                hint.append(" • Recent");
            }
            if (selectedMatch.open()) {
                hint.append(" • Already open");
            }
            hint.append(" • Highlight previews • Tab completes • Ctrl+R/Ctrl+S history");
            findFileHintLabel.setText(hint.toString());
            return;
        }

        if (lastFindFileQuery == null || lastFindFileQuery.isBlank()) {
            findFileHintLabel.setText("Recent files first • Highlight previews • Type a project-relative path • Tab completes • Ctrl+R/Ctrl+S history • Esc cancels");
        } else {
            findFileHintLabel.setText("Enter opens exact paths first • Directories descend • Highlight previews • Ctrl+Backspace deletes a path segment");
        }
    }

    private void updateFindFilePreview(FindFileSupport.Match selectedMatch) {
        if (findFilePreviewTitleLabel == null || findFilePreviewArea == null) {
            return;
        }

        FindFileSupport.Preview preview = FindFileSupport.buildPreview(
                workspaceRoot,
                selectedMatch,
                MAX_FIND_FILE_PREVIEW_LINES,
                MAX_FIND_FILE_PREVIEW_CHARACTERS
        );
        findFilePreviewTitleLabel.setText(preview.title());
        findFilePreviewArea.setText(preview.content());
        findFilePreviewArea.positionCaret(0);
        findFilePreviewArea.setScrollTop(0);
        findFilePreviewArea.setScrollLeft(0);
    }

    private void moveFindFileSelection(int delta) {
        if (findFileResults.isEmpty()) {
            return;
        }

        int selectedIndex = findFileListView.getSelectionModel().getSelectedIndex();
        int newIndex = selectedIndex < 0 ? 0 : Math.floorMod(selectedIndex + delta, findFileResults.size());
        findFileListView.getSelectionModel().select(newIndex);
        findFileListView.scrollTo(newIndex);
    }

    private void handleFindFileFieldKeys(KeyEvent event) {
        if ((event.getCode() == KeyCode.N || event.getCode() == KeyCode.DOWN) && event.isControlDown()) {
            moveFindFileSelection(1);
            event.consume();
            return;
        }
        if ((event.getCode() == KeyCode.P || event.getCode() == KeyCode.UP) && event.isControlDown()) {
            moveFindFileSelection(-1);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.DOWN) {
            moveFindFileSelection(1);
            findFileListView.requestFocus();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.UP) {
            moveFindFileSelection(-1);
            findFileListView.requestFocus();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.TAB) {
            applyFindFileCompletion();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.BACK_SPACE && event.isControlDown()) {
            findFileField.setText(FindFileSupport.deleteTrailingPathSegment(findFileField.getText()));
            findFileField.positionCaret(findFileField.getText().length());
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.R && event.isControlDown()) {
            recallOlderFindFileHistory();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.S && event.isControlDown()) {
            recallNewerFindFileHistory();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            hideFindFilePrompt(true);
            event.consume();
        }
    }

    private void handleFindFileListKeys(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            acceptFindFileInput();
            event.consume();
        } else if (event.getCode() == KeyCode.TAB) {
            applyFindFileCompletion();
            event.consume();
        } else if (event.getCode() == KeyCode.R && event.isControlDown()) {
            recallOlderFindFileHistory();
            event.consume();
        } else if (event.getCode() == KeyCode.S && event.isControlDown()) {
            recallNewerFindFileHistory();
            event.consume();
        } else if ((event.getCode() == KeyCode.P || event.getCode() == KeyCode.UP) && event.isControlDown()) {
            moveFindFileSelection(-1);
            event.consume();
        } else if ((event.getCode() == KeyCode.N || event.getCode() == KeyCode.DOWN) && event.isControlDown()) {
            moveFindFileSelection(1);
            event.consume();
        } else if (event.getCode() == KeyCode.UP && findFileListView.getSelectionModel().getSelectedIndex() <= 0) {
            findFileField.requestFocus();
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            hideFindFilePrompt(true);
            event.consume();
        }
    }

    private void applyFindFileCompletion() {
        FindFileSupport.completeInput(workspaceRoot, findFileField.getText(), List.copyOf(findFileResults))
                .ifPresent(completion -> {
                    findFileField.setText(completion);
                    findFileField.positionCaret(completion.length());
                });
    }

    private void recallOlderFindFileHistory() {
        if (findFileHistory.isEmpty()) {
            return;
        }

        int targetIndex;
        if (findFileHistoryIndex < 0) {
            findFileHistoryDraft = findFileField.getText() == null ? "" : findFileField.getText();
            targetIndex = 0;
        } else if (findFileHistoryIndex < findFileHistory.size() - 1) {
            targetIndex = findFileHistoryIndex + 1;
        } else {
            targetIndex = findFileHistoryIndex;
        }

        applyFindFileHistoryEntry(findFileHistory.get(targetIndex), targetIndex);
    }

    private void recallNewerFindFileHistory() {
        if (findFileHistoryIndex < 0) {
            return;
        }
        if (findFileHistoryIndex == 0) {
            applyFindFileHistoryEntry(findFileHistoryDraft, -1);
            return;
        }

        int targetIndex = findFileHistoryIndex - 1;
        applyFindFileHistoryEntry(findFileHistory.get(targetIndex), targetIndex);
    }

    private void applyFindFileHistoryEntry(String value, int historyIndex) {
        String entry = value == null ? "" : value;
        applyingFindFileHistory = true;
        try {
            findFileField.setText(entry);
            findFileField.positionCaret(entry.length());
        } finally {
            applyingFindFileHistory = false;
        }
        findFileHistoryIndex = historyIndex;
    }

    private void acceptFindFileInput() {
        String typedInput = findFileField.getText();
        Path homeDirectory = Path.of(System.getProperty("user.home", "."));
        Optional<FindFileSupport.Match> explicitMatch = FindFileSupport.resolveExistingMatch(
                workspaceRoot,
                typedInput,
                List.copyOf(findFileResults),
                homeDirectory
        );
        if (explicitMatch.isPresent()) {
            openFindFileMatch(explicitMatch.get(), typedInput);
            return;
        }

        FindFileSupport.Match selectedMatch = findFileListView.getSelectionModel().getSelectedItem();
        if (selectedMatch != null) {
            openFindFileMatch(selectedMatch, typedInput);
            return;
        }

        Optional<Path> resolvedPath = FindFileSupport.resolvePath(workspaceRoot, typedInput, homeDirectory);
        if (resolvedPath.isEmpty()) {
            statusMessage("Enter a file path to open");
            return;
        }

        Path path = resolvedPath.get();
        if (Files.isDirectory(path)) {
            String directoryInput = FindFileSupport.presentPath(workspaceRoot, path, true);
            findFileField.setText(directoryInput);
            findFileField.positionCaret(directoryInput.length());
            return;
        }
        if (!Files.isRegularFile(path)) {
            statusMessage("File not found: " + path);
            return;
        }

        recordFindFileQuery(typedInput);
        hideFindFilePrompt(false);
        openFile(path);
    }

    private void openFindFileMatch(FindFileSupport.Match match, String typedInput) {
        if (match.directory()) {
            String directoryInput = FindFileSupport.presentPath(workspaceRoot, match.path(), true);
            findFileField.setText(directoryInput);
            findFileField.positionCaret(directoryInput.length());
            return;
        }

        recordFindFileQuery(typedInput);
        hideFindFilePrompt(false);
        openFile(match.path());
    }

    private void recordFindFileQuery(String query) {
        String normalizedQuery = query == null ? "" : query.strip();
        lastFindFileQuery = normalizedQuery;
        if (normalizedQuery.isBlank()) {
            return;
        }

        findFileHistory.removeIf(existing -> existing.equals(normalizedQuery));
        findFileHistory.addFirst(normalizedQuery);
        while (findFileHistory.size() > MAX_FIND_FILE_HISTORY) {
            findFileHistory.removeLast();
        }
    }

    private void registerBuiltInCommands() {
        List<CommandAction> commandActions = new ArrayList<>(List.of(
                new CommandAction("New Tab", "Create a new untitled document tab", "File", "⌘T", List.of("untitled", "document"), this::createNewTab),
                new CommandAction("Open File", "Open a file into a new or existing tab", "File", "⌘O", List.of("file", "browse"), this::onOpenFile),
                new CommandAction("Find File", "Open a workspace file using minibuffer-style path completion", "File", "", List.of("open file", "visit file", "find-file", "c-x c-f", "minibuffer", "path"), this::showFindFilePrompt),
                new CommandAction("Open Workspace Folder", "Choose the root folder for the project explorer", "Workspace", "", List.of("folder", "explorer", "project"), this::onOpenWorkspaceFolder),
                new CommandAction("Refresh Workspace", "Refresh the project explorer tree", "Workspace", "", List.of("reload", "tree", "refresh"), this::onRefreshWorkspace),
                new CommandAction("Undo", "Undo the last change in the current document", "Edit", "⌘Z", List.of("undo", "revert", "history"), this::undoActiveEdit),
                new CommandAction("Redo", "Redo the last undone change in the current document", "Edit", "⇧⌘Z", List.of("redo", "restore", "history"), this::redoActiveEdit),
                new CommandAction("Cut", "Cut the current selection from the active document", "Edit", "⌘X", List.of("clipboard", "selection", "cut"), this::cutActiveSelection),
                new CommandAction("Copy", "Copy the current selection from the active document", "Edit", "⌘C", List.of("clipboard", "selection", "copy"), this::copyActiveSelection),
                new CommandAction("Paste", "Paste clipboard contents into the active document", "Edit", "⌘V", List.of("clipboard", "paste", "insert"), this::pasteIntoActiveEditor),
                new CommandAction("Save File", "Save the active document", "File", "⌘S", List.of("write", "disk"), this::onSaveFile),
                new CommandAction("Save File As", "Save the active document to a new location", "File", "⇧⌘S", List.of("rename", "duplicate"), this::onSaveFileAs),
                new CommandAction("Close Tab", "Close the current tab with dirty-state confirmation", "File", "⌘W", List.of("document", "close"), this::onCloseCurrentTab),
                new CommandAction("Exit", "Close Editora after confirming unsaved changes", "File", "", List.of("quit", "close window", "exit app"), this::exitApplication),
                new CommandAction("Toggle Theme", "Cycle through the available AtlantaFX themes", "View", "", List.of("dark", "light", "appearance", "primer", "nord", "cupertino", "dracula"), this::onToggleTheme),
                new CommandAction("Find Next", "Find the next search match in the current document", "Search", "⌘F", List.of("search", "next"), this::onFindNext),
                new CommandAction("Find Previous", "Find the previous search match in the current document", "Search", "", List.of("search", "previous"), this::onFindPrevious),
                new CommandAction("Toggle Search Bar", "Show or hide the search and replace controls", "View", "⌥⌘F", List.of("search", "replace", "collapse", "hide"), this::onToggleSearchBar),
                new CommandAction("Toggle Project Explorer", "Show or hide Project Explorer in the tool dock", "View", "⌥⌘E", List.of("explorer", "sidebar", "project", "collapse", "hide", "tool dock"), this::onToggleProjectExplorer),
                new CommandAction("Move Project Explorer Left", "Dock Project Explorer on the left side of the tool dock", "View", "", List.of("explorer", "project", "tool window", "left", "dock", "sidebar", "tool dock"), this::moveProjectExplorerLeft),
                new CommandAction("Move Project Explorer Right", "Dock Project Explorer on the right side of the tool dock", "View", "", List.of("explorer", "project", "tool window", "right", "dock", "sidebar", "tool dock"), this::moveProjectExplorerRight),
                new CommandAction("Toggle Status Bar", "Show or hide the bottom status bar", "View", "⌥⌘B", List.of("status", "footer", "bottom", "hide"), this::onToggleStatusBar),
                new CommandAction("Toggle MiniMap", "Show or hide the MiniMap overview beside each editor tab", "View", "", List.of("minimap", "mini map", "overview", "scrollbar", "editor"), this::toggleMiniMapVisibility),
                new CommandAction("Toggle Breadcrumb Path", "Show or hide the breadcrumb path inside the status bar", "View", "", List.of("breadcrumb", "path", "status", "file path", "location"), this::toggleBreadcrumbBarVisibility),
                new CommandAction("Toggle Default Read-Only Open", "Enable or disable opening matching files in read-only mode by default", "Edit", "", List.of("read only", "readonly", "default", "pattern", "md", "txt", "readme"), () -> setDefaultReadOnlyOpenEnabled(!currentSettings.readOnlyOpenEnabled())),
                new CommandAction("Replace Selection", "Replace the current match selection", "Search", "", List.of("replace", "selection"), this::onReplaceSelection),
                new CommandAction("Replace All", "Replace all matches in the current document", "Search", "", List.of("replace", "all"), this::onReplaceAll),
                new CommandAction("Open Settings", "Show the full Editora settings view", "View", "⌘,", List.of("preferences", "configuration"), this::showSettingsView),
                new CommandAction("Version", "Show Editora version and author information", "Help", "", List.of("about", "version", "author", "info"), this::showVersionOverlay),
                new CommandAction("Show Keyboard Shortcuts", "Open the keyboard reference for shell and editor-local Emacs bindings", "Help", "", List.of("keyboard", "shortcut", "emacs", "mark", "kill", "yank", "region"), this::showKeyboardShortcutsView),
                new CommandAction("Clear Recent Files", "Forget the recent-files history", "Workspace", "", List.of("recent", "history", "clear"), this::clearRecentFiles),
                new CommandAction("Reload TextMate Bundles", "Reload bundled and external TextMate grammars without restarting Editora", "Languages", "", List.of("textmate", "syntax", "grammar", "bundle", "reload"), this::reloadTextMateBundles),
                new CommandAction("Reload Plugins", "Reload plugin commands and menu actions from the plugins directory", "Plugins", "", List.of("plugins", "reload"), this::loadPlugins),
                new CommandAction("Toggle Read-Only Mode", "Make the active document read-only or editable again; Space scrolls down, Backspace scrolls up", "Edit", "", List.of("read only", "readonly", "lock", "view", "immutable", "protect"), this::toggleReadOnly)
        ));
        commandActions.addAll(Arrays.stream(EditorTheme.values())
                .map(theme -> new CommandAction(
                        theme.getCommandName(),
                        "Switch Editora to the " + theme.getDisplayName() + " AtlantaFX theme",
                        "View",
                        "",
                        List.of("theme", "appearance", theme.getDisplayName().toLowerCase(Locale.ROOT), theme.getFamilyDisplayName().toLowerCase(Locale.ROOT)),
                        () -> {
                            setTheme(theme);
                            statusMessage("Theme switched to " + theme.getDisplayName());
                        }
                ))
                .toList());
        builtInCommands.setAll(commandActions);
        rebuildCommands();
    }

    private void setTheme(EditorTheme theme) {
        applySettings(new EditorSettings(
                theme,
                currentSettings.wrapText(),
                currentSettings.diagnosticsEnabled(),
                currentSettings.miniMapVisible(),
                currentSettings.searchBarVisible(),
                currentSettings.toolDockVisible(),
                currentSettings.breadcrumbBarVisible(),
                currentSettings.toolDockSide(),
                currentSettings.commandPaletteShortcut(),
                currentSettings.editorFontFamily(),
                currentSettings.editorFontSize(),
                currentSettings.readOnlyOpenEnabled(),
                currentSettings.readOnlyOpenPatterns()
        ), true);
    }

    private void toggleReadOnly() {
        getActiveDocument().ifPresent(document -> {
            boolean nowReadOnly = !document.isReadOnly();
            document.setReadOnly(nowReadOnly);
            updateReadOnlyStatus(document);
            syncToolbarButtonStates();
            statusMessage(nowReadOnly ? "Read-only mode enabled" : "Read-only mode disabled");
        });
    }

    private void updateReadOnlyStatus(EditorDocument document) {
        if (readOnlyStatusChip == null) {
            return;
        }
        boolean hasDocument = document != null;
        boolean readOnly = hasDocument && document.isReadOnly();
        readOnlyStatusChip.setManaged(hasDocument);
        readOnlyStatusChip.setVisible(hasDocument);
        readOnlyStatusChip.getStyleClass().remove("read-only-chip-active");
        if (readOnly) {
            readOnlyStatusChip.getStyleClass().add("read-only-chip-active");
        }
        if (readOnlyStatusIcon != null) {
            readOnlyStatusIcon.setIconLiteral(readOnly ? "bi-lock-fill" : "bi-unlock");
        }
        if (readOnlyStatusLabel != null) {
            readOnlyStatusLabel.setText(readOnly ? "Read Only" : "Writable");
        }
    }

    private void setDefaultReadOnlyOpenEnabled(boolean enabled) {
        if (currentSettings.readOnlyOpenEnabled() == enabled) {
            statusMessage(enabled ? "Default read-only file opening is already enabled" : "Default read-only file opening is already disabled");
            return;
        }
        applySettings(new EditorSettings(
                currentSettings.theme(),
                currentSettings.wrapText(),
                currentSettings.diagnosticsEnabled(),
                currentSettings.miniMapVisible(),
                searchBarVisible,
                toolDockVisible,
                currentSettings.breadcrumbBarVisible(),
                toolDockSide,
                currentSettings.commandPaletteShortcut(),
                currentSettings.editorFontFamily(),
                currentSettings.editorFontSize(),
                enabled,
                currentSettings.readOnlyOpenPatterns()
        ), true);
        statusMessage(enabled ? "Default read-only file opening enabled" : "Default read-only file opening disabled");
    }

    private void rebuildCommands() {
        commands.setAll(builtInCommands);
        commands.addAll(recentFileCommands);
        commands.addAll(pluginCommands);
        filterCommands(commandPaletteField == null ? "" : commandPaletteField.getText());
    }

    private void rebuildRecentFileCommands() {
        recentFileCommands.setAll(recentFiles.stream()
                .limit(MAX_RECENT_FILES)
                .map(path -> new CommandAction(
                        "Open Recent: " + path.getFileName(),
                        path.toString(),
                        "Recent Files",
                        "",
                        List.of(path.toString(), path.getFileName().toString()),
                        () -> openFile(path)
                ))
                .toList());
        rebuildCommands();
    }

    private void rebuildRecentFilesMenu() {
        recentFilesMenuButton.getItems().clear();

        if (recentFiles.isEmpty()) {
            MenuItem emptyItem = new MenuItem("No recent files");
            emptyItem.setDisable(true);
            recentFilesMenuButton.getItems().add(emptyItem);
        } else {
            for (Path recentFile : recentFiles) {
                CommandAction action = new CommandAction(
                        "Open Recent: " + recentFile.getFileName(),
                        recentFile.toString(),
                        "Recent Files",
                        "",
                        List.of(recentFile.toString(), recentFile.getFileName().toString()),
                        () -> openFile(recentFile)
                );
                MenuItem item = createMenuItem(action, recentFile.getFileName() + " — " + recentFile.getParent());
                item.setOnAction(event -> handleEvent(event, () -> openFile(recentFile)));
                recentFilesMenuButton.getItems().add(item);
            }
            recentFilesMenuButton.getItems().add(new SeparatorMenuItem());
        }

        MenuItem clearItem = createMenuItem(new CommandAction(
                "Clear Recent Files",
                "Forget the recent-files history",
                "Workspace",
                "",
                List.of("recent", "history", "clear"),
                this::clearRecentFiles
        ), "Clear Recent Files");
        clearItem.setOnAction(event -> handleEvent(event, this::clearRecentFiles));
        recentFilesMenuButton.getItems().add(clearItem);
    }

    private void clearRecentFiles() {
        recentFiles.clear();
        SessionManager.saveRecentFiles(recentFiles);
        rebuildRecentFilesMenu();
        rebuildRecentFileCommands();
        statusMessage("Cleared recent files");
    }

    private void loadSettingsView() {
        try {
            FXMLLoader loader = new FXMLLoader(EditoraApplication.class.getResource("settings-view.fxml"));
            Node settingsView = loader.load();
            settingsController = loader.getController();
            settingsContentHost.setCenter(settingsView);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load settings-view.fxml", exception);
        }
    }

    private void loadPlugins() {
        pluginCommands.clear();
        pluginMenuActions.clear();

        pluginManager.loadPlugins(new EditoraContext() {
            @Override
            public void registerCommand(CommandAction commandAction) {
                pluginCommands.add(commandAction);
            }

            @Override
            public void registerPluginAction(CommandAction commandAction) {
                pluginMenuActions.add(commandAction);
            }

            @Override
            public void openFile(Path path) {
                Platform.runLater(() -> EditorController.this.openFile(path));
            }

            @Override
            public void insertTextAtCaret(String text) {
                Platform.runLater(() -> getActiveDocument().ifPresent(document -> {
                    CodeArea area = document.getCodeArea();
                    area.insertText(area.getCaretPosition(), text);
                    area.requestFocus();
                }));
            }

            @Override
            public void replaceSelection(String text) {
                Platform.runLater(() -> getActiveDocument().ifPresent(document -> {
                    document.getCodeArea().replaceSelection(text);
                    document.getCodeArea().requestFocus();
                }));
            }

            @Override
            public void replaceActiveDocumentText(String text) {
                Platform.runLater(() -> getActiveDocument().ifPresent(document -> {
                    document.getCodeArea().replaceText(text);
                    document.getCodeArea().requestFocus();
                }));
            }

            @Override
            public Optional<String> activeDocumentText() {
                return getActiveDocument().map(document -> document.getCodeArea().getText());
            }

            @Override
            public Optional<Path> activeDocumentPath() {
                return getActiveDocument().map(EditorDocument::getFilePath);
            }

            @Override
            public List<Path> openDocumentPaths() {
                return documentsByTab.values().stream()
                        .map(EditorDocument::getFilePath)
                        .filter(Objects::nonNull)
                        .toList();
            }

            @Override
            public Optional<Path> workspaceRoot() {
                return Optional.ofNullable(workspaceRoot);
            }

            @Override
            public void refreshWorkspace() {
                Platform.runLater(EditorController.this::refreshWorkspaceTree);
            }

            @Override
            public void showStatusMessage(String message) {
                Platform.runLater(() -> statusMessage(message));
            }

            @Override
            public Path pluginsDirectory() {
                return pluginManager.getPluginsDirectory();
            }
        });

        rebuildCommands();
        rebuildPluginMenu();
        statusMessage(pluginManager.getLoadedPlugins().isEmpty() ? "No plugins loaded" : "Loaded " + pluginManager.getLoadedPlugins().size() + " plugin(s)");
    }

    private void rebuildPluginMenu() {
        pluginMenuButton.getItems().clear();

        if (pluginMenuActions.isEmpty()) {
            MenuItem emptyItem = new MenuItem("No plugin actions loaded");
            emptyItem.setDisable(true);
            pluginMenuButton.getItems().add(emptyItem);
        } else {
            for (CommandAction action : pluginMenuActions) {
                MenuItem item = createMenuItem(action, action.name());
                item.setOnAction(event -> handleEvent(event, action.action()));
                pluginMenuButton.getItems().add(item);
            }
            pluginMenuButton.getItems().add(new SeparatorMenuItem());
        }

        MenuItem reloadItem = createMenuItem(new CommandAction(
                "Reload Plugins",
                "Reload plugin commands and menu actions from the plugins directory",
                "Plugins",
                "",
                List.of("plugins", "reload"),
                this::loadPlugins
        ), "Reload Plugins");
        reloadItem.setOnAction(event -> handleEvent(event, this::loadPlugins));
        pluginMenuButton.getItems().add(reloadItem);
        pluginMenuButton.setText("Plugins (" + pluginManager.getLoadedPlugins().size() + ")");
    }

    private MenuItem createMenuItem(CommandAction action, String labelText) {
        FontIcon icon = createCommandIcon(action, 14, "menu-item-icon");
        Label label = new Label(labelText);
        label.getStyleClass().add("menu-item-label");
        HBox content = new HBox(10, icon, label);
        content.setAlignment(Pos.CENTER_LEFT);
        CustomMenuItem item = new CustomMenuItem(content, true);
        item.setHideOnClick(true);
        return item;
    }

    private FontIcon createCommandIcon(CommandAction action, int size, String styleClass) {
        return createIcon(commandIconLiteral(action), size, styleClass);
    }

    private FontIcon createIcon(String iconLiteral, int size, String styleClass) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(size);
        icon.getStyleClass().add(styleClass);
        return icon;
    }

    private String commandIconLiteral(CommandAction action) {
        String name = action.name().toLowerCase();
        String category = action.category().toLowerCase();
        if (name.contains("new tab")) {
            return "bi-plus";
        }
        if (name.contains("exit") || name.contains("quit")) {
            return "bi-box-arrow-right";
        }
        if (name.contains("version") || name.contains("about")) {
            return "bi-info-square";
        }
        if (name.contains("find file")) {
            return "bi-folder2-open";
        }
        if (name.contains("open file") || name.contains("open workspace") || name.contains("open recent")) {
            return "bi-folder2-open";
        }
        if (name.contains("refresh")) {
            return "bi-arrow-clockwise";
        }
        if (name.contains("save file as")) {
            return "bi-save2";
        }
        if (name.contains("save file")) {
            return "bi-save2";
        }
        if (name.contains("close")) {
            return "bi-x-circle";
        }
        if (name.contains("toggle theme")) {
            return "bi-circle-half";
        }
        if (name.contains("find") || name.contains("search")) {
            return "bi-search";
        }
        if (name.contains("replace")) {
            return "bi-arrow-left-right";
        }
        if (name.contains("settings")) {
            return "bi-gear";
        }
        if (name.contains("keyboard") || category.contains("help")) {
            return "bi-keyboard";
        }
        if (name.contains("reload plugins") || category.contains("plugins")) {
            return "bi-puzzle";
        }
        if (category.contains("recent")) {
            return "bi-clock-history";
        }
        if (category.contains("workspace")) {
            return "bi-files";
        }
        if (category.contains("file")) {
            return "bi-file-earmark-text";
        }
        return "bi-lightning-charge";
    }

    private boolean restoreWorkspaceSession(WorkspaceSession session) {
        List<Path> restoredFiles = new ArrayList<>();
        for (Path path : session.openFiles()) {
            openFileInternal(path, false, true).ifPresent(document -> restoredFiles.add(document.getFilePath()));
        }

        if (restoredFiles.isEmpty()) {
            return false;
        }

        Optional<Path> selectedFile = session.selectedFile();
        if (selectedFile.isPresent()) {
            documentsByTab.values().stream()
                    .filter(document -> selectedFile.get().equals(document.getFilePath()))
                    .findFirst()
                    .ifPresent(document -> editorTabPane.getSelectionModel().select(document.getTab()));
        } else {
            editorTabPane.getSelectionModel().selectFirst();
        }

        return true;
    }

    private WorkspaceSession buildCurrentSession() {
        List<Path> openFiles = editorTabPane.getTabs().stream()
                .map(documentsByTab::get)
                .filter(Objects::nonNull)
                .map(EditorDocument::getFilePath)
                .filter(Objects::nonNull)
                .toList();
        Optional<Path> selectedFile = getActiveDocument().map(EditorDocument::getFilePath);
        double currentToolDockDividerPosition = toolDockVisible && !centerSplitPane.getDividers().isEmpty()
                ? centerSplitPane.getDividers().getFirst().getPosition()
                : expandedToolDockDividerPosition;
        double currentToolDockWidth = toolDockVisible && projectExplorerPane.getWidth() > 0
                ? projectExplorerPane.getWidth()
                : expandedToolDockPrefWidth;
        return new WorkspaceSession(
                openFiles,
                selectedFile,
                workspaceRoot,
                searchBarVisible,
                toolDockVisible,
                statusBarVisible,
                currentToolDockDividerPosition,
                currentToolDockWidth,
                searchField.getText(),
                replaceField.getText(),
                caseSensitiveToggle.isSelected(),
                wholeWordToggle.isSelected(),
                regexToggle.isSelected(),
                lastCommandPaletteFilter,
                lastFindFileQuery,
                List.copyOf(findFileHistory),
                windowWidth,
                windowHeight,
                windowX,
                windowY,
                windowMaximized
        );
    }

    private void createNewTab() {
        openDocument(buildDocument(starterDocumentText(), null), true);
        statusMessage("Created a new tab");
    }

    private EditorDocument buildDocument(String initialText, Path filePath) {
        CodeArea codeArea = new CodeArea();
        codeArea.getStyleClass().add("editor-code-area");
        codeArea.setWrapText(currentSettings.wrapText());
        applyEditorFontSettings(codeArea);
        codeArea.replaceText(initialText);
        codeArea.moveTo(0);

        String untitledName = filePath == null ? "Untitled " + untitledCounter++ : filePath.getFileName().toString();
        EditorDocument document = new EditorDocument(
                untitledName,
                codeArea,
                languageServices.resolve(filePath),
                currentSettings.miniMapVisible()
        );
        if (filePath != null) {
            document.setFilePath(filePath.toAbsolutePath().normalize());
            document.setReadOnly(ReadOnlyOpenRules.shouldOpenReadOnly(filePath, currentSettings));
        }

        attachEditorBehavior(document);
        document.markSaved();
        analyzeDocument(document);
        return document;
    }

    private void attachEditorBehavior(EditorDocument document) {
        CodeArea codeArea = document.getCodeArea();
        IntFunction<Node> lineNumbers = LineNumberFactory.get(codeArea);
        codeArea.setParagraphGraphicFactory(lineIndex -> createLineFringe(document, lineNumbers, lineIndex));
        installEditorContextMenu(document);
        installEditorKeyBindings(document);

        codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> analyzeDocument(document));
        codeArea.viewportDirtyEvents()
                .successionEnds(Duration.ofMillis(90))
                .subscribe(ignore -> requestProgressiveHighlighting(document));

        codeArea.textProperty().addListener(onChange(() -> {
            document.clearNavigationGoalColumn();
            document.clearMark();
            document.clampMarkPosition();
            document.refreshDirtyState();
            updateDocumentStatus(document);
            updateEditActionAvailability(document);
            updateCaretStatus(document);
            revealActiveDocumentInProjectTree(document);
            refreshSearchUi();
        }));
        codeArea.caretPositionProperty().addListener(onChange(() -> {
            if (!document.consumeNavigationGoalPreservation()) {
                document.clearNavigationGoalColumn();
            }
            updateCaretStatus(document);
            updateEditActionAvailability(document);
            refreshSearchUi();
        }));
        codeArea.selectionProperty().addListener(onChange(() -> {
            updateEditActionAvailability(document);
            refreshSearchUi();
        }));
        codeArea.focusedProperty().addListener(onCurrentChange(current -> {
            if (current) {
                editorTabPane.getSelectionModel().select(document.getTab());
                updateEditActionAvailability(document);
            }
        }));
    }

    private void installEditorKeyBindings(EditorDocument document) {
        document.getCodeArea().addEventFilter(KeyEvent.KEY_PRESSED, event -> handleEditorKeyBindings(document, event));
    }

    private void handleEditorKeyBindings(EditorDocument document, KeyEvent event) {
        if (document.isReadOnly()) {
            if (handleReadOnlyNavigation(document, event)) {
                event.consume();
            }
            return;
        }
        if (handleEmacsControlEditing(document, event)
                || handleEmacsMetaEditing(document, event)
                || handleEmacsControlNavigation(document, event)
                || handleEmacsMetaNavigation(document, event)) {
            event.consume();
        }
    }

    private boolean handleReadOnlyNavigation(EditorDocument document, KeyEvent event) {
        if (event.getCode() == KeyCode.SPACE && !event.isControlDown() && !event.isAltDown() && !event.isShortcutDown()) {
            document.scrollPageDown();
            return true;
        }
        if (event.getCode() == KeyCode.BACK_SPACE && !event.isControlDown() && !event.isAltDown() && !event.isShortcutDown()) {
            document.scrollPageUp();
            return true;
        }
        return false;
    }

    private boolean handleEmacsControlEditing(EditorDocument document, KeyEvent event) {
        if (!event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShortcutDown()) {
            return false;
        }

        if (event.getCode() == KeyCode.SPACE && !event.isShiftDown()) {
            setEditorMark(document);
            return true;
        }
        if (event.isShiftDown()) {
            return false;
        }

        return switch (event.getCode()) {
            case G -> cancelEditorMark(document);
            case W -> killActiveRegion(document);
            case Y -> yankClipboard(document);
            case K -> killLine(document);
            case D -> deleteForwardChar(document);
            default -> false;
        };
    }

    private boolean handleEmacsControlNavigation(EditorDocument document, KeyEvent event) {
        if (!event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShortcutDown() || event.isShiftDown()) {
            return false;
        }

        CodeArea codeArea = document.getCodeArea();
        String text = codeArea.getText();
        int caretPosition = codeArea.getCaretPosition();

        return switch (event.getCode()) {
            case B -> moveEditorCaret(document, EmacsNavigation.backwardChar(text, caretPosition));
            case F -> moveEditorCaret(document, EmacsNavigation.forwardChar(text, caretPosition));
            case P -> moveEditorCaret(document, EmacsNavigation.previousLine(text, caretPosition, document.getNavigationGoalColumn()));
            case N -> moveEditorCaret(document, EmacsNavigation.nextLine(text, caretPosition, document.getNavigationGoalColumn()));
            case A -> moveEditorCaret(document, EmacsNavigation.lineStart(text, caretPosition));
            case E -> moveEditorCaret(document, EmacsNavigation.lineEnd(text, caretPosition));
            default -> false;
        };
    }

    private boolean handleEmacsMetaNavigation(EditorDocument document, KeyEvent event) {
        if (!event.isAltDown() || event.isControlDown() || event.isMetaDown() || event.isShortcutDown()) {
            return false;
        }

        CodeArea codeArea = document.getCodeArea();
        String text = codeArea.getText();
        int caretPosition = codeArea.getCaretPosition();

        if (event.isShiftDown()) {
            return switch (event.getCode()) {
                case COMMA -> moveEditorCaret(document, 0);
                case PERIOD -> moveEditorCaret(document, codeArea.getLength());
                default -> false;
            };
        }

        return switch (event.getCode()) {
            case B -> moveEditorCaret(document, EmacsNavigation.backwardWord(text, caretPosition));
            case F -> moveEditorCaret(document, EmacsNavigation.forwardWord(text, caretPosition));
            default -> false;
        };
    }

    private boolean handleEmacsMetaEditing(EditorDocument document, KeyEvent event) {
        if (!event.isAltDown() || event.isControlDown() || event.isMetaDown() || event.isShortcutDown() || event.isShiftDown()) {
            return false;
        }

        return switch (event.getCode()) {
            case W -> copyActiveRegion(document);
            case D -> killForwardWord(document);
            case BACK_SPACE, DELETE -> killBackwardWord(document);
            default -> false;
        };
    }

    private boolean moveEditorCaret(EditorDocument document, int targetPosition) {
        document.clearNavigationGoalColumn();
        moveEditorCaret(document, targetPosition, false);
        return true;
    }

    private boolean moveEditorCaret(EditorDocument document, EmacsNavigation.CaretMove targetMove) {
        document.setNavigationGoalColumn(targetMove.goalColumn());
        moveEditorCaret(document, targetMove.caretPosition(), true);
        return true;
    }

    private void moveEditorCaret(EditorDocument document, int targetPosition, boolean preserveNavigationGoal) {
        CodeArea codeArea = document.getCodeArea();
        int boundedTarget = Math.max(0, Math.min(targetPosition, codeArea.getLength()));

        if (preserveNavigationGoal && boundedTarget != codeArea.getCaretPosition()) {
            document.preserveNavigationGoalOnNextCaretChange();
        }

        if (document.hasMark()) {
            int anchor = Math.max(0, Math.min(document.getMarkPosition(), codeArea.getLength()));
            codeArea.selectRange(anchor, boundedTarget);
        } else {
            codeArea.selectRange(boundedTarget, boundedTarget);
        }
        codeArea.requestFollowCaret();
    }

    private void setEditorMark(EditorDocument document) {
        CodeArea editor = document.getCodeArea();
        int caretPosition = editor.getCaretPosition();
        document.setMarkPosition(caretPosition);
        editor.selectRange(caretPosition, caretPosition);
        editor.requestFocus();
        updateCaretStatus(document);
        updateEditActionAvailability(document);
        statusMessage("Mark set");
    }

    private boolean cancelEditorMark(EditorDocument document) {
        CodeArea editor = document.getCodeArea();
        boolean hadSelection = editor.getSelection().getLength() > 0;
        boolean hadMark = document.hasMark();
        document.clearMark();
        int caretPosition = editor.getCaretPosition();
        editor.selectRange(caretPosition, caretPosition);
        editor.requestFocus();
        updateCaretStatus(document);
        updateEditActionAvailability(document);
        statusMessage(hadMark || hadSelection ? "Mark cleared" : "No active mark");
        return true;
    }

    private boolean killActiveRegion(EditorDocument document) {
        CodeArea editor = document.getCodeArea();
        EmacsEditing.EditOperation operation = EmacsEditing.killRegion(
                editor.getText(),
                editor.getSelection().getStart(),
                editor.getSelection().getEnd()
        );
        return applyEmacsEdit(document, operation, true, "Killed region", "Set a mark and move the caret to create a region");
    }

    private boolean copyActiveRegion(EditorDocument document) {
        CodeArea editor = document.getCodeArea();
        if (editor.getSelection().getLength() == 0) {
            editor.requestFocus();
            updateEditActionAvailability(document);
            statusMessage("Set a mark and move the caret to create a region");
            return true;
        }

        copyTextToClipboard(editor.getSelectedText());
        document.clearMark();
        int caretPosition = editor.getCaretPosition();
        editor.selectRange(caretPosition, caretPosition);
        editor.requestFocus();
        updateCaretStatus(document);
        updateEditActionAvailability(document);
        statusMessage("Copied region to clipboard");
        return true;
    }

    private boolean yankClipboard(EditorDocument document) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) {
            CodeArea editor = document.getCodeArea();
            editor.requestFocus();
            updateEditActionAvailability(document);
            statusMessage("Clipboard is empty");
            return true;
        }

        CodeArea editor = document.getCodeArea();
        EmacsEditing.EditOperation operation = EmacsEditing.yank(
                editor.getText(),
                editor.getSelection().getStart(),
                editor.getSelection().getEnd(),
                clipboard.getString()
        );
        return applyEmacsEdit(document, operation, false, "Yanked clipboard contents", "Clipboard is empty");
    }

    private boolean killLine(EditorDocument document) {
        EmacsEditing.EditOperation operation = EmacsEditing.killLine(document.getCodeArea().getText(), document.getCodeArea().getCaretPosition());
        return applyEmacsEdit(document, operation, true, "Killed line", "Nothing to kill on this line");
    }

    private boolean deleteForwardChar(EditorDocument document) {
        EmacsEditing.EditOperation operation = EmacsEditing.deleteForwardChar(document.getCodeArea().getText(), document.getCodeArea().getCaretPosition());
        return applyEmacsEdit(document, operation, false, "Deleted character", "Nothing to delete");
    }

    private boolean killForwardWord(EditorDocument document) {
        EmacsEditing.EditOperation operation = EmacsEditing.killWordForward(document.getCodeArea().getText(), document.getCodeArea().getCaretPosition());
        return applyEmacsEdit(document, operation, true, "Killed word", "Nothing to kill forward");
    }

    private boolean killBackwardWord(EditorDocument document) {
        EmacsEditing.EditOperation operation = EmacsEditing.killWordBackward(document.getCodeArea().getText(), document.getCodeArea().getCaretPosition());
        return applyEmacsEdit(document, operation, true, "Killed word", "Nothing to kill backward");
    }

    private boolean applyEmacsEdit(EditorDocument document,
                                   EmacsEditing.EditOperation operation,
                                   boolean copyAffectedTextToClipboard,
                                   String successMessage,
                                   String failureMessage) {
        CodeArea editor = document.getCodeArea();
        if (operation.isNoOp()) {
            editor.requestFocus();
            updateEditActionAvailability(document);
            statusMessage(failureMessage);
            return true;
        }

        if (copyAffectedTextToClipboard) {
            copyTextToClipboard(operation.affectedText());
        }
        editor.replaceText(operation.start(), operation.end(), operation.replacement());
        document.clearMark();
        int caretPosition = Math.max(0, Math.min(operation.caretPosition(), editor.getLength()));
        editor.selectRange(caretPosition, caretPosition);
        editor.requestFollowCaret();
        editor.requestFocus();
        updateCaretStatus(document);
        updateEditActionAvailability(document);
        statusMessage(successMessage);
        return true;
    }

    private void copyTextToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void installEditorContextMenu(EditorDocument document) {
        MenuItem undoItem = new MenuItem("Undo");
        undoItem.setOnAction(event -> handleEvent(event, () -> {
            editorTabPane.getSelectionModel().select(document.getTab());
            undoActiveEdit();
        }));

        MenuItem redoItem = new MenuItem("Redo");
        redoItem.setOnAction(event -> handleEvent(event, () -> {
            editorTabPane.getSelectionModel().select(document.getTab());
            redoActiveEdit();
        }));

        MenuItem cutItem = new MenuItem("Cut");
        cutItem.setOnAction(event -> handleEvent(event, () -> {
            editorTabPane.getSelectionModel().select(document.getTab());
            cutActiveSelection();
        }));

        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(event -> handleEvent(event, () -> {
            editorTabPane.getSelectionModel().select(document.getTab());
            copyActiveSelection();
        }));

        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setOnAction(event -> handleEvent(event, () -> {
            editorTabPane.getSelectionModel().select(document.getTab());
            pasteIntoActiveEditor();
        }));

        ContextMenu contextMenu = new ContextMenu(
                undoItem,
                redoItem,
                new SeparatorMenuItem(),
                cutItem,
                copyItem,
                pasteItem
        );
        contextMenu.setOnShowing(event -> handleEvent(event, () -> {
            editorTabPane.getSelectionModel().select(document.getTab());
            updateContextMenuAvailability(document, undoItem, redoItem, cutItem, copyItem, pasteItem);
        }));
        document.getCodeArea().setContextMenu(contextMenu);
    }

    private void updateContextMenuAvailability(EditorDocument document,
                                               MenuItem undoItem,
                                               MenuItem redoItem,
                                               MenuItem cutItem,
                                               MenuItem copyItem,
                                               MenuItem pasteItem) {
        CodeArea editor = document.getCodeArea();
        boolean hasSelection = editor.getSelection().getLength() > 0;
        boolean clipboardHasString = Clipboard.getSystemClipboard().hasString();
        boolean readOnly = document.isReadOnly();

        undoItem.setDisable(readOnly || !editor.isUndoAvailable());
        redoItem.setDisable(readOnly || !editor.isRedoAvailable());
        cutItem.setDisable(readOnly || !hasSelection);
        copyItem.setDisable(!hasSelection);
        pasteItem.setDisable(readOnly || !clipboardHasString);
    }

    private void openDocument(EditorDocument document, boolean select) {
        Tab tab = document.getTab();
        tab.setClosable(true);
        tab.setOnCloseRequest(event -> {
            event.consume();
            closeDocument(document);
        });

        documentsByTab.put(tab, document);
        editorTabPane.getTabs().add(tab);
        if (select) {
            editorTabPane.getSelectionModel().select(tab);
            document.getCodeArea().requestFocus();
            document.resetViewToTop();
            updateEditorState(document);
        }
        if (findFileOverlay != null && findFileOverlay.isVisible()) {
            refreshFindFileResults();
        }
    }

    private boolean confirmCloseDocument(EditorDocument document) {
        if (document.isDirty()) {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.initOwner(getWindow());
            confirmation.setTitle("Unsaved Changes");
            confirmation.setHeaderText(document.getDisplayName() + " has unsaved changes.");
            confirmation.setContentText("Do you want to save before closing?");

            ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.YES);
            ButtonType discardButton = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
            confirmation.getButtonTypes().setAll(saveButton, discardButton, ButtonType.CANCEL);

            Optional<ButtonType> response = confirmation.showAndWait();
            if (response.isEmpty() || response.get() == ButtonType.CANCEL) {
                return false;
            }
            if (response.get() == saveButton && !saveDocument(document, false)) {
                return false;
            }
        }

        return true;
    }

    private void closeDocument(EditorDocument document) {
        if (!confirmCloseDocument(document)) {
            return;
        }

        documentsByTab.remove(document.getTab());
        editorTabPane.getTabs().remove(document.getTab());
        if (editorTabPane.getTabs().isEmpty()) {
            createNewTab();
        }
        updateEditorState(getActiveDocument().orElse(null));
        if (findFileOverlay != null && findFileOverlay.isVisible()) {
            refreshFindFileResults();
        }
    }

    private void openSelectedProjectTreeItem() {
        TreeItem<Path> selectedItem = projectTreeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null) {
            return;
        }

        Path selectedPath = selectedItem.getValue();
        if (selectedPath != null && Files.isRegularFile(selectedPath)) {
            openFile(selectedPath);
        }
    }

    private void openFile(Path path) {
        openFileInternal(path, true, false);
    }

    private Optional<EditorDocument> openFileInternal(Path path, boolean select, boolean quiet) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Optional<EditorDocument> existingDocument = documentsByTab.values().stream()
                .filter(document -> normalizedPath.equals(document.getFilePath()))
                .findFirst();

        if (existingDocument.isPresent()) {
            if (select) {
                editorTabPane.getSelectionModel().select(existingDocument.get().getTab());
                existingDocument.get().getCodeArea().requestFocus();
            }
            recordRecentFile(normalizedPath);
            if (!quiet) {
                statusMessage("Focused already-open file " + normalizedPath.getFileName());
            }
            return existingDocument;
        }

        if (!Files.exists(normalizedPath) || !Files.isRegularFile(normalizedPath)) {
            if (!quiet) {
                statusMessage("File not found: " + normalizedPath);
            }
            return Optional.empty();
        }

        try {
            String text = Files.readString(normalizedPath, StandardCharsets.UTF_8);
            EditorDocument document = buildDocument(text, normalizedPath);
            openDocument(document, select);
            lastUsedDirectory = normalizedPath.getParent();
            recordRecentFile(normalizedPath);
            if (!quiet) {
                statusMessage("Opened " + normalizedPath.getFileName());
            }
            return Optional.of(document);
        } catch (IOException | RuntimeException exception) {
            if (!quiet) {
                IOException displayException = exception instanceof IOException ioException
                        ? ioException
                        : new IOException(exception.getMessage(), exception);
                showError("Could not open file", displayException);
            }
            return Optional.empty();
        }
    }

    private void recordRecentFile(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        recentFiles.removeIf(existing -> existing.equals(normalized));
        recentFiles.addFirst(normalized);
        while (recentFiles.size() > MAX_RECENT_FILES) {
            recentFiles.removeLast();
        }
        SessionManager.saveRecentFiles(recentFiles);
        rebuildRecentFilesMenu();
        rebuildRecentFileCommands();
        if (findFileOverlay != null && findFileOverlay.isVisible()) {
            refreshFindFileResults();
        }
    }

    private void saveActiveDocument(boolean saveAs) {
        getActiveDocument().ifPresent(document -> saveDocument(document, saveAs));
    }

    private boolean saveDocument(EditorDocument document, boolean saveAs) {
        Path targetPath = document.getFilePath();
        if (saveAs || targetPath == null) {
            FileChooser chooser = createFileChooser(saveAs ? "Save File As" : "Save File", document);
            chooser.setInitialFileName(defaultFileName(document));
            var selectedFile = chooser.showSaveDialog(getWindow());
            if (selectedFile == null) {
                return false;
            }
            targetPath = selectedFile.toPath().toAbsolutePath().normalize();
        }

        try {
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            Files.writeString(targetPath, document.getCodeArea().getText(), StandardCharsets.UTF_8);
            document.setFilePath(targetPath);
            document.setLanguageService(languageServices.resolve(targetPath));
            document.markSaved();
            analyzeDocument(document);
            lastUsedDirectory = targetPath.getParent();
            recordRecentFile(targetPath);
            updateEditorState(document);
            revealActiveDocumentInProjectTree(document);
            statusMessage("Saved " + targetPath.getFileName());
            return true;
        } catch (IOException exception) {
            showError("Could not save file", exception);
            return false;
        }
    }

    private String defaultFileName(EditorDocument document) {
        if (document.getFilePath() != null) {
            return document.getFilePath().getFileName().toString();
        }

        return document.getUntitledName().replace(' ', '-') + ".java";
    }

    private FileChooser createFileChooser(String title, EditorDocument document) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Java Files", "*.java"),
                new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.md", "*.xml", "*.fxml", "*.css"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        Path initialDirectory = document != null && document.getFilePath() != null
                ? document.getFilePath().getParent()
                : lastUsedDirectory;
        if (initialDirectory == null) {
            initialDirectory = workspaceRoot != null ? workspaceRoot : Path.of(System.getProperty("user.home"));
        }
        if (Files.isDirectory(initialDirectory)) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }

        return chooser;
    }

    private void installAccelerators(Scene scene) {
        installCommandPaletteAccelerator(scene);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::undoActiveEdit)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> Platform.runLater(this::redoActiveEdit)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::cutActiveSelection)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::copyActiveSelection)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::pasteIntoActiveEditor)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::createNewTab)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::onOpenFile)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::onSaveFile)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> Platform.runLater(this::onSaveFileAs)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::onCloseCurrentTab)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::showSearchBarAndFocus)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                () -> Platform.runLater(this::onToggleSearchBar)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                () -> Platform.runLater(this::onToggleProjectExplorer)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                () -> Platform.runLater(this::onToggleStatusBar)
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                () -> Platform.runLater(this::showSettingsView)
        );
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (versionOverlay.isVisible()) {
                    hideVersionOverlay(true);
                    event.consume();
                } else if (findFileOverlay.isVisible()) {
                    hideFindFilePrompt(true);
                    event.consume();
                } else if (commandPaletteOverlay.isVisible()) {
                    hideCommandPalette();
                    event.consume();
                } else if (settingsOverlay.isVisible()) {
                    hideSettingsView();
                    event.consume();
                } else if (searchBarVisible && isFocusInside(searchBar)) {
                    searchBarVisible = false;
                    applySearchBarVisibility(true, true);
                    persistShellVisibilitySettings();
                    focusActiveEditor();
                    event.consume();
                } else if (toolDockVisible && isFocusInside(projectExplorerPane)) {
                    toolDockVisible = false;
                    applyToolDockVisibility(true, true);
                    persistShellVisibilitySettings();
                    focusActiveEditor();
                    event.consume();
                }
            }
        });
    }

    private void installCommandPaletteAccelerator(Scene scene) {
        if (commandPaletteAccelerator != null) {
            scene.getAccelerators().remove(commandPaletteAccelerator);
        }

        commandPaletteAccelerator = CommandPaletteShortcut.keyCombination(currentSettings.commandPaletteShortcut());
        scene.getAccelerators().put(commandPaletteAccelerator, () -> Platform.runLater(this::showCommandPalette));
    }

    private void updateCommandPaletteShortcutPresentation() {
        String displayShortcut = CommandPaletteShortcut.displayText(currentSettings.commandPaletteShortcut());
        if (commandPaletteToolbarTooltip != null) {
            commandPaletteToolbarTooltip.setText("Command Palette (" + displayShortcut + ")");
        }
        if (commandPaletteShortcutLabel != null) {
            commandPaletteShortcutLabel.setText(displayShortcut);
        }
    }

    private void updateEditorState(EditorDocument document) {
        updateDocumentPathBreadcrumbs(document);
        updateEditActionAvailability(document);
        updateCaretStatus(document);
        updateDocumentStatus(document);
        updateLanguageStatus(document);
        updateReadOnlyStatus(document);
        revealActiveDocumentInProjectTree(document);
        refreshSearchUi();
        requestProgressiveHighlighting(document);
    }

    private void configureStatusBar() {
        if (documentPathBreadcrumbs == null) {
            return;
        }

        documentPathBreadcrumbs.setAutoNavigationEnabled(false);
        documentPathBreadcrumbs.setFocusTraversable(false);
        documentPathBreadcrumbs.setOnCrumbAction(this::handleBreadcrumbAction);
        applyBreadcrumbBarVisibility(currentSettings.breadcrumbBarVisible(), false);
        updateDocumentPathBreadcrumbs(getActiveDocument().orElse(null));
    }

    private void handleBreadcrumbAction(Breadcrumbs.BreadCrumbActionEvent<StatusBarSupport.BreadcrumbEntry> event) {
        StatusBarSupport.BreadcrumbEntry entry = event == null || event.getSelectedCrumb() == null
                ? null
                : event.getSelectedCrumb().getValue();
        if (entry == null || entry.path() == null) {
            statusMessage("No project location available for this breadcrumb");
            return;
        }

        revealPathInProjectTree(entry.path());
    }

    private void toggleBreadcrumbBarVisibility() {
        boolean nextVisible = !currentSettings.breadcrumbBarVisible();
        applyBreadcrumbBarVisibility(nextVisible, true);
        applySettings(new EditorSettings(
                currentSettings.theme(),
                currentSettings.wrapText(),
                currentSettings.diagnosticsEnabled(),
                currentSettings.miniMapVisible(),
                searchBarVisible,
                toolDockVisible,
                nextVisible,
                toolDockSide,
                currentSettings.commandPaletteShortcut(),
                currentSettings.editorFontFamily(),
                currentSettings.editorFontSize(),
                currentSettings.readOnlyOpenEnabled(),
                currentSettings.readOnlyOpenPatterns()
        ), true);
    }

    private void applyBreadcrumbBarVisibility(boolean visible, boolean announce) {
        if (statusPathRow == null) {
            return;
        }

        statusPathRow.setManaged(visible);
        statusPathRow.setVisible(visible);
        if (announce) {
            statusMessage(visible ? "Breadcrumb path shown" : "Breadcrumb path hidden");
        }
    }

    private void updateDocumentPathBreadcrumbs(EditorDocument document) {
        if (documentPathBreadcrumbs == null) {
            return;
        }

        List<StatusBarSupport.BreadcrumbEntry> segments;
        if (document == null) {
            segments = List.of(new StatusBarSupport.BreadcrumbEntry("No document", null));
        } else {
            segments = StatusBarSupport.buildBreadcrumbEntries(
                    workspaceRoot,
                    document.getFilePath(),
                    document.getDisplayName()
            );
        }
        documentPathBreadcrumbs.setSelectedCrumb(Breadcrumbs.buildTreeModel(segments.toArray(StatusBarSupport.BreadcrumbEntry[]::new)));
    }

    private void updateCaretStatus(EditorDocument document) {
        if (document == null) {
            caretStatusLabel.setText("Line: —  Col: —");
            return;
        }

        CodeArea codeArea = document.getCodeArea();
        String status = "Line: " + (codeArea.getCurrentParagraph() + 1) + "  Col: " + (codeArea.getCaretColumn() + 1);
        if (document.hasMark()) {
            int selectionLength = codeArea.getSelection().getLength();
            status += selectionLength > 0 ? " · Region " + selectionLength + " chars" : " · Mark";
        }
        caretStatusLabel.setText(status);
    }

    private void updateDocumentStatus(EditorDocument document) {
        if (document == null) {
            documentStatusLabel.setText("No document");
            return;
        }

        CodeArea codeArea = document.getCodeArea();
        String text = codeArea.getText();
        int characters = codeArea.getLength();
        int lines = codeArea.getParagraphs().size();
        long utf8Bytes = StatusBarSupport.utf8Size(text);
        documentStatusLabel.setText(StatusBarSupport.formatDocumentStatus(document.getDisplayName(), lines, characters, utf8Bytes));
    }

    private void updateLanguageStatus(EditorDocument document) {
        if (document == null) {
            languageStatusLabel.setText("Language: —");
            return;
        }

        String diagnosticsText = currentSettings.diagnosticsEnabled()
                ? document.getDiagnosticCount() + " diagnostics"
                : "diagnostics hidden";
        languageStatusLabel.setText("Language: " + document.getLanguageService().displayName() + " · " + diagnosticsText);
    }

    private Optional<EditorDocument> getDocument(Tab tab) {
        return Optional.ofNullable(documentsByTab.get(tab));
    }

    private Optional<EditorDocument> getActiveDocument() {
        return getDocument(editorTabPane.getSelectionModel().getSelectedItem());
    }

    private void undoActiveEdit() {
        getActiveDocument().ifPresent(document -> {
            if (!ensureWritable(document, "Undo")) {
                return;
            }
            CodeArea editor = document.getCodeArea();
            if (!editor.isUndoAvailable()) {
                statusMessage("Nothing to undo");
                editor.requestFocus();
                updateEditActionAvailability(document);
                return;
            }

            editor.undo();
            editor.requestFocus();
            statusMessage("Undid last change");
            updateEditActionAvailability(document);
        });
    }

    private void redoActiveEdit() {
        getActiveDocument().ifPresent(document -> {
            if (!ensureWritable(document, "Redo")) {
                return;
            }
            CodeArea editor = document.getCodeArea();
            if (!editor.isRedoAvailable()) {
                statusMessage("Nothing to redo");
                editor.requestFocus();
                updateEditActionAvailability(document);
                return;
            }

            editor.redo();
            editor.requestFocus();
            statusMessage("Redid last change");
            updateEditActionAvailability(document);
        });
    }

    private void cutActiveSelection() {
        getActiveDocument().ifPresent(document -> {
            if (!ensureWritable(document, "Cut")) {
                return;
            }
            CodeArea editor = document.getCodeArea();
            if (editor.getSelection().getLength() == 0) {
                statusMessage("Select text to cut");
                editor.requestFocus();
                updateEditActionAvailability(document);
                return;
            }

            editor.cut();
            editor.requestFocus();
            statusMessage("Cut selection to clipboard");
            updateEditActionAvailability(document);
        });
    }

    private void copyActiveSelection() {
        getActiveDocument().ifPresent(document -> {
            CodeArea editor = document.getCodeArea();
            if (editor.getSelection().getLength() == 0) {
                statusMessage("Select text to copy");
                editor.requestFocus();
                updateEditActionAvailability(document);
                return;
            }

            editor.copy();
            editor.requestFocus();
            statusMessage("Copied selection to clipboard");
            updateEditActionAvailability(document);
        });
    }

    private void pasteIntoActiveEditor() {
        getActiveDocument().ifPresent(document -> {
            if (!ensureWritable(document, "Paste")) {
                return;
            }
            CodeArea editor = document.getCodeArea();
            editor.paste();
            editor.requestFocus();
            statusMessage("Pasted clipboard contents");
            updateEditActionAvailability(document);
        });
    }

    private void findMatch(boolean forward) {
        String query = normalizedSearchQuery();
        if (query == null || query.isBlank()) {
            searchField.requestFocus();
            return;
        }

        getActiveDocument().ifPresent(document -> {
            CodeArea editor = document.getCodeArea();
            SearchComputation search = computeSearch(document);
            if (search.errorMessage() != null) {
                statusMessage(search.errorMessage());
                return;
            }

            List<SearchMatch> matches = search.matches();
            if (matches.isEmpty()) {
                statusMessage("No matches for \"" + query + "\"");
                return;
            }

            SearchMatch targetMatch = matches.get(nextMatchIndex(editor, matches, forward));
            if (targetMatch != null) {
                editor.selectRange(targetMatch.start(), targetMatch.end());
                editor.requestFollowCaret();
                editor.requestFocus();
                statusMessage("Found match for \"" + query + "\"");
            }
        });
    }

    private void replaceSelection() {
        getActiveDocument().ifPresent(document -> {
            if (!ensureWritable(document, "Replace")) {
                return;
            }
            CodeArea editor = document.getCodeArea();
            String query = normalizedSearchQuery();
            if (query == null || query.isBlank()) {
                return;
            }

            SearchComputation search = computeSearch(document);
            if (search.errorMessage() != null) {
                statusMessage(search.errorMessage());
                return;
            }

            int currentIndex = currentMatchIndex(editor, search.matches());
            if (currentIndex < 0) {
                findMatch(true);
                search = computeSearch(document);
                currentIndex = currentMatchIndex(editor, search.matches());
            }

            if (currentIndex >= 0 && currentIndex < search.matches().size()) {
                SearchMatch match = search.matches().get(currentIndex);
                editor.selectRange(match.start(), match.end());
                editor.replaceSelection(replacementForSelectedMatch(editor.getSelectedText()));
                statusMessage("Replaced current match");
                findMatch(true);
            }
        });
    }

    private void replaceAll() {
        String query = normalizedSearchQuery();
        if (query == null || query.isBlank()) {
            return;
        }

        getActiveDocument().ifPresent(document -> {
            if (!ensureWritable(document, "Replace")) {
                return;
            }
            SearchComputation search = computeSearch(document);
            if (search.errorMessage() != null) {
                statusMessage(search.errorMessage());
                return;
            }
            if (search.matches().isEmpty()) {
                statusMessage("No matches for \"" + query + "\"");
                return;
            }

            document.getCodeArea().replaceText(replaceAllMatches(document.getCodeArea().getText()));
            statusMessage("Replaced all matches for \"" + query + "\"");
            refreshSearchUi();
        });
    }

    private void applySettings(EditorSettings settings, boolean persist) {
        if (toolDockVisible) {
            captureToolDockLayoutState();
        }
        currentSettings = settings;
        searchBarVisible = settings.searchBarVisible();
        toolDockVisible = settings.toolDockVisible();
        toolDockSide = settings.toolDockSide();
        ThemeManager.apply(settings.theme(), rootStack);
        updateCommandPaletteShortcutPresentation();
        applySearchBarVisibility(false, false);
        applyToolDockVisibility(false, false);
        applyBreadcrumbBarVisibility(settings.breadcrumbBarVisible(), false);
        if (rootPane.getScene() != null) {
            installCommandPaletteAccelerator(rootPane.getScene());
        }
        documentsByTab.values().forEach(document -> {
            document.getCodeArea().setWrapText(settings.wrapText());
            document.setMiniMapVisible(settings.miniMapVisible());
            applyEditorFontSettings(document.getCodeArea());
            analyzeDocument(document);
        });
        syncToolbarButtonStates();
        updateEditorState(getActiveDocument().orElse(null));

        if (persist) {
            SettingsManager.save(settings);
        }
    }

    private void applyEditorFontSettings(CodeArea codeArea) {
        if (codeArea == null) {
            return;
        }

        String fontFamily = currentSettings.editorFontFamily().replace("\"", "\\\"");
        codeArea.setStyle(String.format("-fx-font-family: \"%s\"; -fx-font-size: %dpx;",
                fontFamily,
                currentSettings.editorFontSize()));
    }

    private void showSearchBarAndFocus() {
        if (!searchBarVisible) {
            searchBarVisible = true;
            applySearchBarVisibility(false, true);
            persistShellVisibilitySettings();
        }
        Platform.runLater(searchField::requestFocus);
    }

    private void toggleSearchBarVisibility() {
        searchBarVisible = !searchBarVisible;
        applySearchBarVisibility(true, true);
        persistShellVisibilitySettings();
    }

    private void applySearchBarVisibility(boolean announce, boolean animate) {
        if (searchBarAnimation != null) {
            searchBarAnimation.stop();
        }

        if (!animate) {
            if (searchBarVisible) {
                searchBar.setManaged(true);
                searchBar.setVisible(true);
                searchBar.setOpacity(1);
                searchBar.setMinHeight(Region.USE_COMPUTED_SIZE);
                searchBar.setPrefHeight(Region.USE_COMPUTED_SIZE);
                searchBar.setMaxHeight(Region.USE_COMPUTED_SIZE);
            } else {
                searchBar.setManaged(false);
                searchBar.setVisible(false);
                searchBar.setOpacity(0);
                searchBar.setMinHeight(0);
                searchBar.setPrefHeight(0);
                searchBar.setMaxHeight(0);
            }
            if (announce) {
                statusMessage(searchBarVisible ? "Search bar shown" : "Search bar hidden");
            }
            syncToolbarButtonStates();
            return;
        }

        if (searchBarVisible) {
            captureSearchBarExpandedHeight();
            searchBar.setManaged(true);
            searchBar.setVisible(true);
            searchBar.setOpacity(0);
            searchBar.setMinHeight(0);
            searchBar.setPrefHeight(0);
            searchBar.setMaxHeight(0);
            searchBarAnimation = new Timeline(
                    new KeyFrame(javafx.util.Duration.ZERO,
                            new KeyValue(searchBar.opacityProperty(), 0),
                            new KeyValue(searchBar.prefHeightProperty(), 0),
                            new KeyValue(searchBar.maxHeightProperty(), 0)),
                    new KeyFrame(javafx.util.Duration.millis(180),
                            new KeyValue(searchBar.opacityProperty(), 1),
                            new KeyValue(searchBar.prefHeightProperty(), searchBarExpandedHeight),
                            new KeyValue(searchBar.maxHeightProperty(), searchBarExpandedHeight))
            );
            searchBarAnimation.setOnFinished(event -> handleEvent(event, () -> {
                searchBar.setMinHeight(Region.USE_COMPUTED_SIZE);
                searchBar.setPrefHeight(Region.USE_COMPUTED_SIZE);
                searchBar.setMaxHeight(Region.USE_COMPUTED_SIZE);
                captureSearchBarExpandedHeight();
            }));
        } else {
            double startHeight = Math.max(searchBar.getHeight(), computeSearchBarExpandedHeight());
            searchBar.setManaged(true);
            searchBar.setVisible(true);
            searchBarAnimation = new Timeline(
                    new KeyFrame(javafx.util.Duration.ZERO,
                            new KeyValue(searchBar.opacityProperty(), searchBar.getOpacity()),
                            new KeyValue(searchBar.prefHeightProperty(), startHeight),
                            new KeyValue(searchBar.maxHeightProperty(), startHeight)),
                    new KeyFrame(javafx.util.Duration.millis(160),
                            new KeyValue(searchBar.opacityProperty(), 0),
                            new KeyValue(searchBar.prefHeightProperty(), 0),
                            new KeyValue(searchBar.maxHeightProperty(), 0))
            );
            searchBarAnimation.setOnFinished(event -> handleEvent(event, () -> {
                searchBar.setManaged(false);
                searchBar.setVisible(false);
                searchBar.setMinHeight(0);
                searchBar.setPrefHeight(0);
                searchBar.setMaxHeight(0);
            }));
        }

        if (announce) {
            statusMessage(searchBarVisible ? "Search bar shown" : "Search bar hidden");
        }
        searchBarAnimation.playFromStart();
        syncToolbarButtonStates();
    }

    private void applyToolDockVisibility(boolean announce, boolean animate) {
        if (toolDockAnimation != null) {
            toolDockAnimation.stop();
        }

        refreshToolWindowRails();

        if (!animate) {
            if (toolDockVisible) {
                updateCenterSplitPaneItems(true);
                projectExplorerPane.setManaged(true);
                projectExplorerPane.setVisible(true);
                projectExplorerPane.setOpacity(1);
                projectExplorerPane.setMinWidth(expandedToolDockMinWidth);
                projectExplorerPane.setPrefWidth(expandedToolDockPrefWidth);
                projectExplorerPane.setMaxWidth(Region.USE_COMPUTED_SIZE);
                Platform.runLater(() -> {
                    if (!centerSplitPane.getDividers().isEmpty()) {
                        centerSplitPane.setDividerPositions(targetToolDockDividerPosition());
                    }
                });
            } else {
                captureToolDockLayoutState();
                projectExplorerPane.setVisible(false);
                projectExplorerPane.setManaged(false);
                projectExplorerPane.setOpacity(0);
                projectExplorerPane.setMinWidth(0);
                projectExplorerPane.setPrefWidth(0);
                projectExplorerPane.setMaxWidth(0);
                updateCenterSplitPaneItems(false);
            }
            if (announce) {
                statusMessage(toolDockVisible ? "Project explorer shown" : "Project explorer hidden");
            }
            syncToolbarButtonStates();
            return;
        }

        if (toolDockVisible) {
            updateCenterSplitPaneItems(true);
            projectExplorerPane.setManaged(true);
            projectExplorerPane.setVisible(true);
            projectExplorerPane.setOpacity(0);
            projectExplorerPane.setMinWidth(0);
            projectExplorerPane.setPrefWidth(0);
            projectExplorerPane.setMaxWidth(expandedToolDockPrefWidth);
            Platform.runLater(() -> {
                if (!centerSplitPane.getDividers().isEmpty()) {
                    centerSplitPane.setDividerPositions(hiddenToolDockDividerPosition());
                }
                Timeline animation = new Timeline(buildToolDockKeyFrames(true));
                toolDockAnimation = animation;
                animation.setOnFinished(event -> handleEvent(event, () -> {
                    projectExplorerPane.setMinWidth(expandedToolDockMinWidth);
                    projectExplorerPane.setPrefWidth(expandedToolDockPrefWidth);
                    projectExplorerPane.setMaxWidth(Region.USE_COMPUTED_SIZE);
                    projectExplorerPane.setOpacity(1);
                }));
                animation.playFromStart();
            });
        } else {
            captureToolDockLayoutState();
            updateCenterSplitPaneItems(true);
            projectExplorerPane.setManaged(true);
            projectExplorerPane.setVisible(true);
            Timeline animation = new Timeline(buildToolDockKeyFrames(false));
            toolDockAnimation = animation;
            animation.setOnFinished(event -> handleEvent(event, () -> {
                projectExplorerPane.setVisible(false);
                projectExplorerPane.setManaged(false);
                projectExplorerPane.setOpacity(0);
                projectExplorerPane.setMinWidth(0);
                projectExplorerPane.setPrefWidth(0);
                projectExplorerPane.setMaxWidth(0);
                updateCenterSplitPaneItems(false);
            }));
            animation.playFromStart();
        }

        if (announce) {
            statusMessage(toolDockVisible ? "Project explorer shown" : "Project explorer hidden");
        }
        syncToolbarButtonStates();
    }

    private void toggleToolDockVisibility() {
        toolDockVisible = !toolDockVisible;
        applyToolDockVisibility(true, true);
        persistShellVisibilitySettings();
    }

    private void moveProjectExplorerLeft() {
        moveProjectExplorer(ToolWindowSide.LEFT);
    }

    private void moveProjectExplorerRight() {
        moveProjectExplorer(ToolWindowSide.RIGHT);
    }

    private void moveProjectExplorer(ToolWindowSide side) {
        Objects.requireNonNull(side);
        if (toolDockSide == side) {
            statusMessage("Project explorer already docked in the " + side.displayName().toLowerCase(Locale.ROOT) + " tool dock");
            return;
        }

        applySettings(new EditorSettings(
                currentSettings.theme(),
                currentSettings.wrapText(),
                currentSettings.diagnosticsEnabled(),
                currentSettings.miniMapVisible(),
                searchBarVisible,
                toolDockVisible,
                currentSettings.breadcrumbBarVisible(),
                side,
                currentSettings.commandPaletteShortcut(),
                currentSettings.editorFontFamily(),
                currentSettings.editorFontSize(),
                currentSettings.readOnlyOpenEnabled(),
                currentSettings.readOnlyOpenPatterns()
        ), true);
        statusMessage("Project explorer moved to the " + side.displayName().toLowerCase(Locale.ROOT) + " tool dock");
    }

    private void toggleMiniMapVisibility() {
        boolean nextVisible = !currentSettings.miniMapVisible();
        applySettings(new EditorSettings(
                currentSettings.theme(),
                currentSettings.wrapText(),
                currentSettings.diagnosticsEnabled(),
                nextVisible,
                searchBarVisible,
                toolDockVisible,
                currentSettings.breadcrumbBarVisible(),
                toolDockSide,
                currentSettings.commandPaletteShortcut(),
                currentSettings.editorFontFamily(),
                currentSettings.editorFontSize(),
                currentSettings.readOnlyOpenEnabled(),
                currentSettings.readOnlyOpenPatterns()
        ), true);
        statusMessage(nextVisible ? "MiniMap shown" : "MiniMap hidden");
    }

    private void persistShellVisibilitySettings() {
        currentSettings = new EditorSettings(
                currentSettings.theme(),
                currentSettings.wrapText(),
                currentSettings.diagnosticsEnabled(),
                currentSettings.miniMapVisible(),
                searchBarVisible,
                toolDockVisible,
                currentSettings.breadcrumbBarVisible(),
                toolDockSide,
                currentSettings.commandPaletteShortcut(),
                currentSettings.editorFontFamily(),
                currentSettings.editorFontSize(),
                currentSettings.readOnlyOpenEnabled(),
                currentSettings.readOnlyOpenPatterns()
        );
        SettingsManager.save(currentSettings);
    }

    private void captureToolDockLayoutState() {
        if (!centerSplitPane.getDividers().isEmpty()) {
            expandedToolDockDividerPosition = centerSplitPane.getDividers().getFirst().getPosition();
        }
        expandedToolDockPrefWidth = Math.max(projectExplorerPane.getWidth(), projectExplorerPane.getPrefWidth());
        expandedToolDockMinWidth = Math.max(MIN_TOOL_DOCK_WIDTH, projectExplorerPane.getMinWidth() > 0 ? projectExplorerPane.getMinWidth() : expandedToolDockMinWidth);
    }

    private double hiddenToolDockDividerPosition() {
        return toolDockSide == ToolWindowSide.RIGHT ? 1d : 0d;
    }

    private double targetToolDockDividerPosition() {
        return ToolWindowLayoutSupport.computeDividerPosition(
                centerSplitPane.getWidth(),
                Math.max(MIN_TOOL_DOCK_WIDTH, expandedToolDockPrefWidth),
                toolDockSide,
                expandedToolDockDividerPosition
        );
    }

    private void applyStatusBarVisibility(boolean announce) {
        statusBar.setManaged(statusBarVisible);
        statusBar.setVisible(statusBarVisible);
        syncToolbarButtonStates();
        if (announce) {
            statusMessage(statusBarVisible ? "Status bar shown" : "Status bar hidden");
        }
    }

    private void updateCenterSplitPaneItems(boolean includeToolDock) {
        if (includeToolDock) {
            if (toolDockSide == ToolWindowSide.RIGHT) {
                centerSplitPane.getItems().setAll(editorTabPane, projectExplorerPane);
            } else {
                centerSplitPane.getItems().setAll(projectExplorerPane, editorTabPane);
            }
        } else {
            centerSplitPane.getItems().setAll(editorTabPane);
        }
        bindToolDockDividerTracking();
    }

    private KeyFrame[] buildToolDockKeyFrames(boolean showing) {
        SplitPane.Divider toolDockDivider = centerSplitPane.getDividers().isEmpty() ? null : centerSplitPane.getDividers().getFirst();
        double visibleToolDockWidth = Math.max(MIN_TOOL_DOCK_WIDTH, expandedToolDockPrefWidth);
        double hiddenDockDividerPosition = hiddenToolDockDividerPosition();
        List<KeyValue> startValues = new ArrayList<>();
        List<KeyValue> endValues = new ArrayList<>();
        startValues.add(new KeyValue(projectExplorerPane.prefWidthProperty(), showing ? 0 : Math.max(projectExplorerPane.getWidth(), visibleToolDockWidth)));
        startValues.add(new KeyValue(projectExplorerPane.maxWidthProperty(), showing ? visibleToolDockWidth : Math.max(projectExplorerPane.getWidth(), visibleToolDockWidth)));
        startValues.add(new KeyValue(projectExplorerPane.opacityProperty(), showing ? 0 : projectExplorerPane.getOpacity()));
        endValues.add(new KeyValue(projectExplorerPane.prefWidthProperty(), showing ? visibleToolDockWidth : 0));
        endValues.add(new KeyValue(projectExplorerPane.maxWidthProperty(), showing ? visibleToolDockWidth : 0));
        endValues.add(new KeyValue(projectExplorerPane.opacityProperty(), showing ? 1 : 0));
        if (toolDockDivider != null) {
            startValues.add(new KeyValue(toolDockDivider.positionProperty(), showing ? hiddenDockDividerPosition : toolDockDivider.getPosition()));
            endValues.add(new KeyValue(toolDockDivider.positionProperty(), showing ? targetToolDockDividerPosition() : hiddenDockDividerPosition));
        }
        return new KeyFrame[]{
                new KeyFrame(javafx.util.Duration.ZERO, startValues.toArray(KeyValue[]::new)),
                new KeyFrame(javafx.util.Duration.millis(showing ? 200 : 180), endValues.toArray(KeyValue[]::new))
        };
    }

    private void bindToolDockDividerTracking() {
        if (trackedToolDockDivider != null) {
            trackedToolDockDivider.positionProperty().removeListener(toolDockDividerPositionListener);
        }
        trackedToolDockDivider = centerSplitPane.getDividers().isEmpty() ? null : centerSplitPane.getDividers().getFirst();
        if (trackedToolDockDivider != null) {
            trackedToolDockDivider.positionProperty().addListener(toolDockDividerPositionListener);
        }
    }

    private void refreshToolWindowRails() {
        boolean dockedLeft = toolDockSide == ToolWindowSide.LEFT;
        configureToolWindowRail(leftToolWindowRail, dockedLeft);
        configureToolWindowRail(rightToolWindowRail, !dockedLeft);
        configureToolWindowRail(projectExplorerLeftRailButton, dockedLeft);
        configureToolWindowRail(projectExplorerRightRailButton, !dockedLeft);
        if (bottomToolWindowRail != null) {
            boolean showBottomRail = bottomToolWindowRail.getChildren().stream().anyMatch(Node::isManaged);
            bottomToolWindowRail.setManaged(showBottomRail);
            bottomToolWindowRail.setVisible(showBottomRail);
        }
    }

    private void configureToolWindowRail(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setManaged(visible);
        node.setVisible(visible);
    }

    private void captureSearchBarExpandedHeight() {
        searchBarExpandedHeight = computeSearchBarExpandedHeight();
    }

    private double computeSearchBarExpandedHeight() {
        return Math.max(44, Math.max(searchBar.getHeight(), searchBar.prefHeight(-1)));
    }


    private void showCommandPalette() {
        hideVersionOverlay(false);
        hideFindFilePrompt(false);
        hideSettingsView();
        updateCommandPaletteShortcutPresentation();
        commandPaletteOverlay.setManaged(true);
        commandPaletteOverlay.setVisible(true);
        syncToolbarButtonStates();
        commandPaletteField.setText(lastCommandPaletteFilter);
        filterCommands(lastCommandPaletteFilter);
        if (!paletteResults.isEmpty()) {
            commandPaletteListView.getSelectionModel().selectFirst();
        }
        Platform.runLater(() -> {
            commandPaletteField.requestFocus();
            commandPaletteField.selectAll();
        });
    }

    private void hideCommandPalette() {
        commandPaletteOverlay.setVisible(false);
        commandPaletteOverlay.setManaged(false);
        syncToolbarButtonStates();
        getActiveDocument().ifPresent(document -> document.getCodeArea().requestFocus());
    }

    private void showFindFilePrompt() {
        hideVersionOverlay(false);
        hideSettingsView();
        hideCommandPalette();
        findFileCandidates = collectFindFileCandidates();
        findFileHistoryIndex = -1;
        findFileHistoryDraft = lastFindFileQuery;
        findFileOverlay.setManaged(true);
        findFileOverlay.setVisible(true);
        findFileField.setText(lastFindFileQuery);
        refreshFindFileResults();
        updateFindFilePreview(findFileListView.getSelectionModel().getSelectedItem());
        Platform.runLater(() -> {
            findFileField.requestFocus();
            findFileField.selectAll();
        });
    }

    private void hideFindFilePrompt(boolean focusEditor) {
        findFileOverlay.setVisible(false);
        findFileOverlay.setManaged(false);
        findFileHistoryIndex = -1;
        updateFindFilePreview(null);
        if (focusEditor) {
            getActiveDocument().ifPresent(document -> document.getCodeArea().requestFocus());
        }
    }

    private void showVersionOverlay() {
        hideFindFilePrompt(false);
        hideCommandPalette();
        hideSettingsView();
        versionTitleLabel.setText(APPLICATION_NAME);
        versionValueLabel.setText("Version " + APPLICATION_VERSION);
        versionAuthorLabel.setText("Author: " + APPLICATION_AUTHOR);
        versionDetailsLabel.setText(String.join("\n",
                "Java 25 + JavaFX 25 desktop editor shell built around RichTextFX CodeArea tabs.",
                "Palette: " + CommandPaletteShortcut.displayText(currentSettings.commandPaletteShortcut())
                        + " • Find File: minibuffer-style path completion • Theme: AtlantaFX • Plugins: ServiceLoader JARs"));
        versionOverlay.setManaged(true);
        versionOverlay.setVisible(true);
    }

    private void hideVersionOverlay(boolean focusEditor) {
        versionOverlay.setVisible(false);
        versionOverlay.setManaged(false);
        if (focusEditor) {
            focusActiveEditor();
        }
    }

    private void showSettingsView() {
        hideVersionOverlay(false);
        hideFindFilePrompt(false);
        hideCommandPalette();
        settingsThemeBeforePreview = currentSettings.theme();
        settingsThemePreviewActive = false;
        settingsController.configure(
                currentSettings,
                pluginManager.getPluginsDirectory(),
                EditoraPersistence.dataDirectory(),
                "JSON files stored in ~/.editora across macOS, Windows, and Linux: " + EditoraPersistence.persistenceFilesDescription(),
                languageServices.availableLanguagesSummary(),
                languageServices.previewSpecs(),
                this::analyzePreviewSpec,
                this::reloadTextMateBundles,
                this::openPersistenceFolder,
                this::previewThemeFromSettings,
                settings -> {
                    applySettings(settings, true);
                    settingsThemeBeforePreview = null;
                    settingsThemePreviewActive = false;
                    hideSettingsView(false);
                    statusMessage("Settings updated");
                },
                () -> hideSettingsView(true)
        );
        settingsOverlay.setManaged(true);
        settingsOverlay.setVisible(true);
        syncToolbarButtonStates();
        settingsController.focusPrimaryControl();
    }

    private void showKeyboardShortcutsView() {
        showSettingsView();
        Platform.runLater(settingsController::showKeyboardShortcutsSection);
    }

    private void hideSettingsView() {
        hideSettingsView(true);
    }

    private void hideSettingsView(boolean restorePreviewTheme) {
        if (restorePreviewTheme) {
            restorePreviewThemeIfNeeded();
        }
        settingsOverlay.setVisible(false);
        settingsOverlay.setManaged(false);
        syncToolbarButtonStates();
    }

    private void openPersistenceFolder() {
        Path persistenceDirectory = EditoraPersistence.dataDirectory();
        boolean opened = PersistenceFolderSupport.openDirectory(persistenceDirectory);
        statusMessage(opened
                ? "Opened persistence folder"
                : "Could not open persistence folder: " + persistenceDirectory);
    }

    private void animateToolbarClick(Button button) {
        if (button == null) {
            return;
        }
        setToolbarButtonActive(button, true);
        PauseTransition transition = toolbarClickFeedback.computeIfAbsent(button, ignored -> new PauseTransition(TOOLBAR_CLICK_FEEDBACK_DURATION));
        transition.stop();
        transition.setOnFinished(event -> handleEvent(event, () -> setToolbarButtonActive(button, false)));
        transition.playFromStart();
    }

    private void syncToolbarButtonStates() {
        setToolbarButtonActive(commandPaletteToolbarButton, commandPaletteOverlay != null && commandPaletteOverlay.isVisible());
        setToolbarButtonActive(searchToolbarButton, searchBarVisible);
        setToolbarButtonActive(projectExplorerToolbarButton, toolDockVisible);
        setToolbarButtonActive(projectExplorerLeftRailButton, toolDockVisible && toolDockSide == ToolWindowSide.LEFT);
        setToolbarButtonActive(projectExplorerRightRailButton, toolDockVisible && toolDockSide == ToolWindowSide.RIGHT);
        setToolbarButtonActive(statusBarToolbarButton, statusBarVisible);
        setToolbarButtonActive(settingsToolbarButton, settingsOverlay != null && settingsOverlay.isVisible());
        setToolbarButtonActive(readOnlyToolbarButton, getActiveDocument().map(EditorDocument::isReadOnly).orElse(false));
    }

    private void setToolbarButtonActive(Button button, boolean active) {
        if (button != null) {
            button.pseudoClassStateChanged(TOOLBAR_ACTIVE_PSEUDO_CLASS, active);
        }
    }

    private void previewThemeFromSettings(EditorTheme theme) {
        if (theme == null) {
            return;
        }
        if (settingsThemeBeforePreview == null) {
            settingsThemeBeforePreview = currentSettings.theme();
        }
        ThemeManager.apply(theme, rootStack);
        settingsThemePreviewActive = !theme.equals(settingsThemeBeforePreview);
    }

    private void restorePreviewThemeIfNeeded() {
        if (!settingsThemePreviewActive || settingsThemeBeforePreview == null) {
            settingsThemeBeforePreview = null;
            settingsThemePreviewActive = false;
            return;
        }
        ThemeManager.apply(settingsThemeBeforePreview, rootStack);
        settingsThemeBeforePreview = null;
        settingsThemePreviewActive = false;
    }

    private void reloadTextMateBundles() {
        languageServices.reloadTextMateBundles();
        documentsByTab.values().forEach(this::analyzeDocument);
        if (settingsOverlay != null && settingsOverlay.isVisible()) {
            settingsController.updateSyntaxPreviewOptions(languageServices.previewSpecs(), languageServices.availableLanguagesSummary());
        }
        statusMessage("Reloaded TextMate bundles");
    }

    private LanguageAnalysis analyzePreviewSpec(LanguagePreviewSpec previewSpec) {
        if (previewSpec == null) {
            return LanguageAnalysis.plainText("");
        }

        LanguageService service = languageServices.resolve(previewSpec.samplePath());
        try {
            return service.analyze(previewSpec.sampleText());
        } catch (RuntimeException exception) {
            return LanguageAnalysis.plainText(previewSpec.sampleText());
        }
    }

    private void exitApplication() {
        Window window = getWindow();
        if (!(window instanceof Stage stage)) {
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(stage);
        confirmation.setTitle("Exit Editora");
        confirmation.setHeaderText("Exit Editora?");
        confirmation.setContentText("Any unsaved documents will still ask for confirmation before closing.");

        ButtonType exitButton = new ButtonType("Exit", ButtonBar.ButtonData.OK_DONE);
        confirmation.getButtonTypes().setAll(exitButton, ButtonType.CANCEL);
        Optional<ButtonType> response = confirmation.showAndWait();
        if (response.isEmpty() || response.get() != exitButton) {
            return;
        }

        if (!requestCloseAllDocuments()) {
            return;
        }

        captureWindowState(stage);
        shutdown();
        stage.setOnCloseRequest(null);
        stage.close();
    }

    private void filterCommands(String filterText) {
        String filter = filterText == null ? "" : filterText.strip().toLowerCase();
        lastCommandPaletteFilter = filterText == null ? "" : filterText;
        List<String> tokens = filter.isBlank()
                ? List.of()
                : Arrays.stream(filter.split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();

        List<CommandAction> rankedResults = commands.stream()
                .filter(command -> tokens.stream().allMatch(command::matches))
                .sorted((left, right) -> {
                    int byScore = Integer.compare(commandMatchScore(left, filter), commandMatchScore(right, filter));
                    if (byScore != 0) {
                        return byScore;
                    }

                    int rightUsage = commandUsage.getOrDefault(right.name(), 0);
                    int leftUsage = commandUsage.getOrDefault(left.name(), 0);
                    if (leftUsage != rightUsage) {
                        return Integer.compare(rightUsage, leftUsage);
                    }

                    int byCategory = left.category().compareToIgnoreCase(right.category());
                    if (byCategory != 0) {
                        return byCategory;
                    }

                    return left.name().compareToIgnoreCase(right.name());
                })
                .toList();

        paletteResults.setAll(rankedResults);
        if (!paletteResults.isEmpty()) {
            commandPaletteListView.getSelectionModel().selectFirst();
            commandPaletteListView.scrollTo(0);
        }
    }

    private int commandMatchScore(CommandAction command, String filter) {
        if (filter == null || filter.isBlank()) {
            return 5;
        }

        String normalizedName = command.name().toLowerCase();
        String normalizedDescription = command.description().toLowerCase();
        if (normalizedName.equals(filter)) {
            return 0;
        }
        if (normalizedName.startsWith(filter)) {
            return 1;
        }
        if (normalizedName.contains(filter)) {
            return 2;
        }
        if (normalizedDescription.contains(filter)) {
            return 3;
        }
        if (command.category().toLowerCase().contains(filter)) {
            return 4;
        }
        return 5;
    }

    private void executeSelectedCommand() {
        CommandAction selectedAction = commandPaletteListView.getSelectionModel().getSelectedItem();
        if (selectedAction != null) {
            commandUsage.merge(selectedAction.name(), 1, Integer::sum);
            hideCommandPalette();
            selectedAction.action().run();
        }
    }

    private void moveCommandPaletteSelection(int delta) {
        if (paletteResults.isEmpty()) {
            return;
        }

        int selectedIndex = commandPaletteListView.getSelectionModel().getSelectedIndex();
        int newIndex = selectedIndex < 0
                ? 0
                : Math.floorMod(selectedIndex + delta, paletteResults.size());
        commandPaletteListView.getSelectionModel().select(newIndex);
        commandPaletteListView.scrollTo(newIndex);
    }

    private void handleCommandPaletteFieldKeys(KeyEvent event) {
        if (event.getCode() == KeyCode.DOWN) {
            moveCommandPaletteSelection(1);
            commandPaletteListView.requestFocus();
            event.consume();
        } else if (event.getCode() == KeyCode.UP) {
            moveCommandPaletteSelection(-1);
            commandPaletteListView.requestFocus();
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            hideCommandPalette();
            event.consume();
        }
    }

    private void handleCommandPaletteListKeys(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            executeSelectedCommand();
            event.consume();
        } else if (event.getCode() == KeyCode.UP && commandPaletteListView.getSelectionModel().getSelectedIndex() <= 0) {
            commandPaletteField.requestFocus();
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            hideCommandPalette();
            event.consume();
        }
    }

    private void handleSearchBarKeys(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            searchBarVisible = false;
            applySearchBarVisibility(true, true);
            focusActiveEditor();
            event.consume();
        }
    }

    private void handleProjectExplorerKeys(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            toolDockVisible = false;
            applyToolDockVisibility(true, true);
            focusActiveEditor();
            event.consume();
        }
    }

    private boolean isFocusInside(Node rootNode) {
        Scene scene = rootPane.getScene();
        if (scene == null) {
            return false;
        }

        Node focusedNode = scene.getFocusOwner();
        while (focusedNode != null) {
            if (focusedNode == rootNode) {
                return true;
            }
            focusedNode = focusedNode.getParent();
        }

        return false;
    }

    private void focusActiveEditor() {
        getActiveDocument().ifPresent(document -> document.getCodeArea().requestFocus());
    }

    private boolean ensureWritable(EditorDocument document, String actionName) {
        if (document == null || !document.isReadOnly()) {
            return true;
        }
        CodeArea editor = document.getCodeArea();
        editor.requestFocus();
        statusMessage("Read-only mode is enabled: cannot " + actionName.toLowerCase(Locale.ROOT));
        updateEditActionAvailability(document);
        return false;
    }

    private void updateEditActionAvailability(EditorDocument document) {
        if (undoButton == null || redoButton == null || cutButton == null || copyButton == null || pasteButton == null) {
            return;
        }

        if (document == null) {
            undoButton.setDisable(true);
            redoButton.setDisable(true);
            cutButton.setDisable(true);
            copyButton.setDisable(true);
            pasteButton.setDisable(true);
            return;
        }

        CodeArea editor = document.getCodeArea();
        boolean hasSelection = editor.getSelection().getLength() > 0;
        boolean clipboardHasString = Clipboard.getSystemClipboard().hasString();
        boolean readOnly = document.isReadOnly();

        undoButton.setDisable(readOnly || !editor.isUndoAvailable());
        redoButton.setDisable(readOnly || !editor.isRedoAvailable());
        cutButton.setDisable(readOnly || !hasSelection);
        copyButton.setDisable(!hasSelection);
        pasteButton.setDisable(readOnly || !clipboardHasString);
    }

    private void refreshSearchUi() {
        if (searchResultsLabel == null) {
            return;
        }

        Optional<EditorDocument> activeDocument = getActiveDocument();
        if (activeDocument.isEmpty()) {
            searchResultsLabel.setText("No document");
            return;
        }

        EditorDocument document = activeDocument.get();
        SearchComputation search = computeSearch(document);
        applyDocumentHighlighting(document, search.matches());

        String query = normalizedSearchQuery();
        if (query.isBlank()) {
            searchResultsLabel.setText("Type to search");
            searchResultsLabel.getStyleClass().remove("search-results-error");
            return;
        }
        if (search.errorMessage() != null) {
            if (!searchResultsLabel.getStyleClass().contains("search-results-error")) {
                searchResultsLabel.getStyleClass().add("search-results-error");
            }
            searchResultsLabel.setText(search.errorMessage());
            return;
        }

        searchResultsLabel.getStyleClass().remove("search-results-error");
        if (search.matches().isEmpty()) {
            searchResultsLabel.setText("No matches");
            return;
        }

        int currentMatchIndex = currentMatchIndex(document.getCodeArea(), search.matches());
        searchResultsLabel.setText(currentMatchIndex >= 0
                ? search.matches().size() + " matches · " + (currentMatchIndex + 1) + "/" + search.matches().size() + searchOptionsSummary()
                : search.matches().size() + " matches" + searchOptionsSummary());
    }

    private void applyDocumentHighlighting(EditorDocument document) {
        applyDocumentHighlighting(document, computeSearch(document).matches());
    }

    private void applyDocumentHighlighting(EditorDocument document, List<SearchMatch> matches) {
        String text = document.getCodeArea().getText();
        StyleSpans<Collection<String>> baseHighlighting = document.getBaseHighlighting();
        if (baseHighlighting == null || baseHighlighting.length() != text.length()) {
            baseHighlighting = LanguageAnalysis.plainText(text).highlighting();
            document.setBaseHighlighting(baseHighlighting);
        }

        if (matches.isEmpty()) {
            document.getCodeArea().setStyleSpans(0, baseHighlighting);
            return;
        }

        StyleSpans<Collection<String>> mergedHighlighting = baseHighlighting.overlay(
                buildSearchHighlighting(text, matches, currentMatchIndex(document.getCodeArea(), matches)),
                this::mergeStyleClasses
        );
        document.getCodeArea().setStyleSpans(0, mergedHighlighting);
    }

    private void applyHighlightRange(EditorDocument document, int startOffset, StyleSpans<Collection<String>> highlighting) {
        if (document == null || highlighting == null) {
            return;
        }
        if (document == getActiveDocument().orElse(null) && !normalizedSearchQuery().isBlank()) {
            refreshSearchUi();
            return;
        }
        document.getCodeArea().setStyleSpans(startOffset, highlighting);
    }

    private StyleSpans<Collection<String>> replaceHighlightRange(StyleSpans<Collection<String>> current,
                                                                 int start,
                                                                 int end,
                                                                 StyleSpans<Collection<String>> replacement) {
        int normalizedStart = Math.max(0, Math.min(start, current.length()));
        int normalizedEnd = Math.max(normalizedStart, Math.min(end, current.length()));
        StyleSpans<Collection<String>> result = normalizedStart > 0
                ? current.subView(0, normalizedStart).concat(replacement)
                : replacement;
        return normalizedEnd < current.length()
                ? result.concat(current.subView(normalizedEnd, current.length()))
                : result;
    }

    private SearchComputation computeSearch(EditorDocument document) {
        String query = normalizedSearchQuery();
        String text = document.getCodeArea().getText();
        if (query.isBlank() || text.isEmpty()) {
            return new SearchComputation(List.of(), null);
        }

        try {
            Pattern pattern = buildSearchPattern(query);
            Matcher matcher = pattern.matcher(text);
            List<SearchMatch> matches = new ArrayList<>();
            while (matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    continue;
                }
                matches.add(new SearchMatch(matcher.start(), matcher.end()));
            }
            return new SearchComputation(matches, null);
        } catch (PatternSyntaxException exception) {
            return new SearchComputation(List.of(), "Invalid regex: " + exception.getDescription());
        }
    }

    private String normalizedSearchQuery() {
        return searchField == null || searchField.getText() == null ? "" : searchField.getText();
    }

    private SearchOptions currentSearchOptions() {
        return new SearchOptions(
                caseSensitiveToggle != null && caseSensitiveToggle.isSelected(),
                wholeWordToggle != null && wholeWordToggle.isSelected(),
                regexToggle != null && regexToggle.isSelected()
        );
    }

    private Pattern buildSearchPattern(String query) {
        SearchOptions options = currentSearchOptions();
        String expression = options.regex() ? query : Pattern.quote(query);
        if (options.wholeWord()) {
            expression = "(?<![\\p{L}\\p{N}_])(?:" + expression + ")(?![\\p{L}\\p{N}_])";
        }
        int flags = options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(expression, flags);
    }

    private int currentMatchIndex(CodeArea codeArea, List<SearchMatch> matches) {
        if (matches.isEmpty()) {
            return -1;
        }

        int selectionStart = codeArea.getSelection().getStart();
        int selectionEnd = codeArea.getSelection().getEnd();
        for (int index = 0; index < matches.size(); index++) {
            SearchMatch match = matches.get(index);
            if (selectionStart == match.start() && selectionEnd == match.end()) {
                return index;
            }
        }

        int caret = codeArea.getCaretPosition();
        for (int index = 0; index < matches.size(); index++) {
            SearchMatch match = matches.get(index);
            if (caret >= match.start() && caret <= match.end()) {
                return index;
            }
        }

        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).start() >= caret) {
                return index;
            }
        }

        return 0;
    }

    private int nextMatchIndex(CodeArea editor, List<SearchMatch> matches, boolean forward) {
        if (matches.isEmpty()) {
            return -1;
        }

        int currentIndex = currentMatchIndex(editor, matches);
        if (forward) {
            if (currentIndex >= 0 && currentIndex + 1 < matches.size()) {
                return currentIndex + 1;
            }
            int anchor = editor.getSelection().getEnd();
            for (int index = 0; index < matches.size(); index++) {
                if (matches.get(index).start() >= anchor) {
                    return index;
                }
            }
            return 0;
        }

        if (currentIndex > 0) {
            return currentIndex - 1;
        }
        int anchor = editor.getSelection().getStart();
        for (int index = matches.size() - 1; index >= 0; index--) {
            if (matches.get(index).end() <= anchor) {
                return index;
            }
        }
        return matches.size() - 1;
    }

    private StyleSpans<Collection<String>> buildSearchHighlighting(String text, List<SearchMatch> matches, int currentMatchIndex) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int cursor = 0;
        for (int index = 0; index < matches.size(); index++) {
            SearchMatch match = matches.get(index);
            if (match.start() > cursor) {
                builder.add(List.of(), match.start() - cursor);
            }
            builder.add(index == currentMatchIndex ? List.of("search-match", "search-match-current") : List.of("search-match"), match.end() - match.start());
            cursor = match.end();
        }
        if (cursor < text.length()) {
            builder.add(List.of(), text.length() - cursor);
        }
        if (text.isEmpty()) {
            builder.add(List.of(), 0);
        }
        return builder.create();
    }

    private Collection<String> mergeStyleClasses(Collection<String> left, Collection<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return List.copyOf(merged);
    }

    private String replacementForSelectedMatch(String selectedText) {
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        if (!currentSearchOptions().regex()) {
            return replacement;
        }
        return buildSearchPattern(normalizedSearchQuery()).matcher(selectedText).replaceFirst(replacement);
    }

    private String replaceAllMatches(String text) {
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        return buildSearchPattern(normalizedSearchQuery()).matcher(text).replaceAll(replacement);
    }

    private String searchOptionsSummary() {
        List<String> enabledOptions = new ArrayList<>();
        SearchOptions options = currentSearchOptions();
        if (options.caseSensitive()) {
            enabledOptions.add("Aa");
        }
        if (options.wholeWord()) {
            enabledOptions.add("W");
        }
        if (options.regex()) {
            enabledOptions.add(".*");
        }
        return enabledOptions.isEmpty() ? "" : " · " + String.join(" ", enabledOptions);
    }

    private void analyzeDocument(EditorDocument document) {
        if (shuttingDown) {
            return;
        }

        String text = document.getCodeArea().getText();
        LanguageService languageService = languageServices.resolve(document.getFilePath());
        long analysisRevision = document.nextAnalysisRevision();
        if (languageService.supportsProgressiveHighlighting(text)) {
            startProgressiveHighlighting(document, languageService, analysisRevision, text);
            return;
        }

        document.clearProgressiveHighlighting();
        try {
            analysisExecutor.execute(() -> {
                DocumentAnalysisResult result = analyzeText(languageService, text);
                Platform.runLater(() -> applyDocumentAnalysis(document, analysisRevision, text, result));
            });
        } catch (RejectedExecutionException ignored) {
            // Ignore late analysis requests during shutdown.
        }
    }

    private DocumentAnalysisResult analyzeText(LanguageService languageService, String text) {
        try {
            return new DocumentAnalysisResult(languageService, languageService.analyze(text));
        } catch (RuntimeException exception) {
            return new DocumentAnalysisResult(PlainTextLanguageService.INSTANCE, LanguageAnalysis.plainText(text));
        }
    }

    private void startProgressiveHighlighting(EditorDocument document,
                                              LanguageService languageService,
                                              long analysisRevision,
                                              String text) {
        document.beginProgressiveHighlighting(analysisRevision);
        document.setLanguageService(languageService);
        document.setDiagnosticsByLine(Map.of());
        StyleSpans<Collection<String>> plainHighlighting = LanguageAnalysis.plainText(text).highlighting();
        document.setBaseHighlighting(plainHighlighting);
        applyHighlightRange(document, 0, plainHighlighting);
        if (getActiveDocument().filter(active -> active == document).isPresent()) {
            updateLanguageStatus(document);
        }
        requestProgressiveHighlighting(document);
    }

    private void requestProgressiveHighlighting(EditorDocument document) {
        if (document == null || shuttingDown || !document.isProgressiveHighlightingActive()) {
            return;
        }
        if (getActiveDocument().filter(active -> active == document).isEmpty()) {
            return;
        }

        String text = document.getCodeArea().getText();
        long analysisRevision = document.getAnalysisRevision();
        ProgressiveHighlightSupport.ParagraphWindow viewportParagraphWindow = document.visibleParagraphWindow(
                0,
                ProgressiveHighlightSupport.FALLBACK_VISIBLE_PARAGRAPHS
        );
        ProgressiveHighlightSupport.ViewportMotion viewportMotion = document.updateViewportMotion(viewportParagraphWindow);
        ProgressiveHighlightSupport.ParagraphWindow visibleParagraphWindow = document.visibleParagraphWindow(
                ProgressiveHighlightSupport.VISIBLE_BUFFER_PARAGRAPHS,
                ProgressiveHighlightSupport.FALLBACK_VISIBLE_PARAGRAPHS
        );
        int forwardPrefetchParagraphs = ProgressiveHighlightSupport.adaptiveForwardPrefetchParagraphs(
                ProgressiveHighlightSupport.PREFETCH_MIN_PARAGRAPHS,
                viewportParagraphWindow,
                viewportMotion
        );
        ProgressiveHighlightSupport.HighlightWindow window = document.claimProgressiveHighlightWindow(
                analysisRevision,
                document.highlightWindowForParagraphs(visibleParagraphWindow)
        );
        if (window == null) {
            for (ProgressiveHighlightSupport.ParagraphWindow neighborWindow : ProgressiveHighlightSupport.neighboringParagraphWindows(
                    visibleParagraphWindow,
                    document.getParagraphCount(),
                    ProgressiveHighlightSupport.PREFETCH_MIN_PARAGRAPHS,
                    forwardPrefetchParagraphs,
                    viewportMotion.direction()
            )) {
                window = document.claimProgressiveHighlightWindow(
                        analysisRevision,
                        document.highlightWindowForParagraphs(neighborWindow)
                );
                if (window != null) {
                    break;
                }
            }
        }
        if (window == null) {
            return;
        }

        final EditorDocument targetDocument = document;
        final String analyzedText = text;
        final long currentAnalysisRevision = analysisRevision;
        final LanguageService rangeLanguageService = document.getLanguageService();
        final ProgressiveHighlightSupport.HighlightWindow claimedWindow = window;
        try {
            analysisExecutor.execute(() -> {
                StyleSpans<Collection<String>> highlighting = analyzeHighlightRange(
                        rangeLanguageService,
                        analyzedText,
                        claimedWindow.startOffset(),
                        claimedWindow.endOffset()
                );
                Platform.runLater(() -> applyProgressiveHighlightRange(
                        targetDocument,
                        currentAnalysisRevision,
                        analyzedText,
                        claimedWindow.startOffset(),
                        claimedWindow.endOffset(),
                        highlighting
                ));
            });
        } catch (RejectedExecutionException ignored) {
            targetDocument.abandonProgressiveHighlightRange(currentAnalysisRevision, claimedWindow.startOffset(), claimedWindow.endOffset());
        }
    }

    private StyleSpans<Collection<String>> analyzeHighlightRange(LanguageService languageService,
                                                                 String text,
                                                                 int startOffset,
                                                                 int endOffset) {
        try {
            return languageService.highlightRange(text, startOffset, endOffset);
        } catch (RuntimeException exception) {
            return LanguageAnalysis.plainText(text.substring(startOffset, endOffset)).highlighting();
        }
    }

    private void applyProgressiveHighlightRange(EditorDocument document,
                                                long analysisRevision,
                                                String analyzedText,
                                                int startOffset,
                                                int endOffset,
                                                StyleSpans<Collection<String>> highlighting) {
        if (shuttingDown
                || !document.isAnalysisRevisionCurrent(analysisRevision)
                || documentsByTab.get(document.getTab()) != document
                || !Objects.equals(document.getCodeArea().getText(), analyzedText)) {
            document.abandonProgressiveHighlightRange(analysisRevision, startOffset, endOffset);
            return;
        }

        document.setBaseHighlighting(replaceHighlightRange(document.getBaseHighlighting(), startOffset, endOffset, highlighting));
        document.completeProgressiveHighlightRange(analysisRevision, startOffset, endOffset);
        applyHighlightRange(document, startOffset, highlighting);
        requestProgressiveHighlighting(document);
    }

    private void applyDocumentAnalysis(EditorDocument document,
                                       long analysisRevision,
                                       String analyzedText,
                                       DocumentAnalysisResult result) {
        if (shuttingDown
                || !document.isAnalysisRevisionCurrent(analysisRevision)
                || documentsByTab.get(document.getTab()) != document
                || !Objects.equals(document.getCodeArea().getText(), analyzedText)) {
            return;
        }

        LanguageAnalysis analysis = result.analysis();
        int previousDiagnosticCount = document.getDiagnosticCount();
        document.setLanguageService(result.languageService());
        document.setBaseHighlighting(analysis.highlighting());
        document.setDiagnosticsByLine(analysis.diagnostics().stream()
                .collect(Collectors.groupingBy(Diagnostic::lineIndex, LinkedHashMap::new, Collectors.toList())));

        if (previousDiagnosticCount > 0 || !analysis.diagnostics().isEmpty()) {
            IntFunction<Node> lineNumbers = LineNumberFactory.get(document.getCodeArea());
            document.getCodeArea().setParagraphGraphicFactory(lineIndex -> createLineFringe(document, lineNumbers, lineIndex));
        }

        if (getActiveDocument().filter(active -> active == document).isPresent()) {
            updateLanguageStatus(document);
            refreshSearchUi();
        } else {
            applyDocumentHighlighting(document);
        }
    }

    private record DocumentAnalysisResult(LanguageService languageService, LanguageAnalysis analysis) {
    }

    private record SearchMatch(int start, int end) {
    }

    private record SearchComputation(List<SearchMatch> matches, String errorMessage) {
    }

    private record SearchOptions(boolean caseSensitive, boolean wholeWord, boolean regex) {
    }

    private Node createLineFringe(EditorDocument document, IntFunction<Node> lineNumbers, int lineIndex) {
        List<Diagnostic> diagnostics = currentSettings.diagnosticsEnabled()
                ? document.getDiagnosticsForLine(lineIndex)
                : List.of();

        Label indicator = new Label();
        indicator.getStyleClass().add("fringe-indicator");
        Tooltip tooltip = null;

        DiagnosticSeverity severity = diagnostics.stream()
                .map(Diagnostic::severity)
                .max(Comparator.comparingInt(this::severityWeight))
                .orElse(null);

        if (severity != null) {
            indicator.setText(switch (severity) {
                case ERROR -> "⛔";
                case WARNING -> "▲";
                case INFO -> "●";
            });
            indicator.getStyleClass().add("diagnostic-" + severity.name().toLowerCase());
            tooltip = new Tooltip(diagnostics.stream().map(Diagnostic::message).distinct().collect(Collectors.joining("\n")));
        } else {
            indicator.setText("●");
            indicator.getStyleClass().add(document.isDirty() ? "dirty-indicator" : "quiet-indicator");
        }

        if (tooltip != null) {
            Tooltip.install(indicator, tooltip);
        }

        HBox gutter = new HBox(8, indicator, lineNumbers.apply(lineIndex));
        gutter.setAlignment(Pos.CENTER_LEFT);
        gutter.setPadding(new Insets(0, 10, 0, 8));
        gutter.getStyleClass().add("line-fringe");
        return gutter;
    }

    private int severityWeight(DiagnosticSeverity severity) {
        return switch (severity) {
            case INFO -> 1;
            case WARNING -> 2;
            case ERROR -> 3;
        };
    }

    private void setWorkspaceRoot(Path root, boolean refreshAndPersistMenus) {
        workspaceRoot = root == null ? Path.of("").toAbsolutePath().normalize() : root.toAbsolutePath().normalize();
        workspaceRootLabel.setText(workspaceRoot.toString());
        refreshWorkspaceTree();
        updateDocumentPathBreadcrumbs(getActiveDocument().orElse(null));
        if (refreshAndPersistMenus) {
            SessionManager.saveWorkspaceSession(buildCurrentSession());
        }
    }

    private void refreshWorkspaceTree() {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            projectTreeView.setRoot(null);
            findFileCandidates = List.of();
            return;
        }

        TreeItem<Path> rootItem = buildProjectTreeItem(workspaceRoot);
        rootItem.setExpanded(true);
        projectTreeView.setRoot(rootItem);
        findFileCandidates = collectFindFileCandidates();
        if (findFileOverlay != null && findFileOverlay.isVisible()) {
            refreshFindFileResults();
        }
    }

    private List<Path> collectFindFileCandidates() {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            return List.of();
        }

        List<Path> candidates = new ArrayList<>();
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!workspaceRoot.equals(directory) && !isVisibleInProjectTree(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!workspaceRoot.equals(directory)) {
                        candidates.add(directory.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && isVisibleInProjectTree(file)) {
                        candidates.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return List.copyOf(candidates);
        }
        return List.copyOf(candidates);
    }

    private TreeItem<Path> buildProjectTreeItem(Path path) {
        TreeItem<Path> item = new TreeItem<>(path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            item.getChildren().setAll(listProjectChildren(path).stream()
                    .map(this::buildProjectTreeItem)
                    .toList());
        }
        return item;
    }

    private List<Path> listProjectChildren(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(this::isVisibleInProjectTree)
                    .sorted(Comparator
                            .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(path -> displayPath(path).toLowerCase()))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private boolean isVisibleInProjectTree(Path path) {
        try {
            if (Files.isHidden(path)) {
                return false;
            }
        } catch (IOException ignored) {
            return false;
        }

        Path fileName = path.getFileName();
        return fileName != null && !EXCLUDED_PROJECT_ENTRIES.contains(fileName.toString());
    }

    private void revealActiveDocumentInProjectTree(EditorDocument document) {
        if (document == null || document.getFilePath() == null) {
            return;
        }

        revealPathInProjectTree(document.getFilePath(), false, false);
    }

    private void revealPathInProjectTree(Path path) {
        revealPathInProjectTree(path, true, true);
    }

    private void revealPathInProjectTree(Path path, boolean announceFailures, boolean allowShowingExplorer) {
        if (path == null || workspaceRoot == null) {
            return;
        }

        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(workspaceRoot)) {
            if (announceFailures) {
                statusMessage("Breadcrumb target is outside the current workspace");
            }
            return;
        }

        if (!toolDockVisible) {
            if (!allowShowingExplorer) {
                return;
            }
            toolDockVisible = true;
            applyToolDockVisibility(false, true);
            persistShellVisibilitySettings();
        } else {
            updateCenterSplitPaneItems(true);
        }

        TreeItem<Path> rootItem = projectTreeView.getRoot();
        if (rootItem == null) {
            return;
        }

        TreeItem<Path> targetItem = findTreeItem(rootItem, normalizedPath);
        if (targetItem == null) {
            if (announceFailures) {
                statusMessage("Could not reveal path in project explorer");
            }
            return;
        }

        expandParents(targetItem);
        projectTreeView.getSelectionModel().select(targetItem);
        projectTreeView.scrollTo(projectTreeView.getRow(targetItem));
    }

    private TreeItem<Path> findTreeItem(TreeItem<Path> item, Path target) {
        if (target.equals(item.getValue())) {
            return item;
        }

        for (TreeItem<Path> child : item.getChildren()) {
            TreeItem<Path> result = findTreeItem(child, target);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private void expandParents(TreeItem<Path> item) {
        TreeItem<Path> current = item;
        while (current != null) {
            current.setExpanded(true);
            current = current.getParent();
        }
    }

    private String displayPath(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private void statusMessage(String message) {
        messageStatusLabel.setText(message);
    }

    private void showError(String header, IOException exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(getWindow());
        alert.setTitle("Editora Error");
        alert.setHeaderText(header);
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        alert.setContentText(message);
        alert.showAndWait();
        statusMessage(header);
    }

    private void handleEvent(Event event, Runnable action) {
        Objects.requireNonNull(event);
        action.run();
    }

    private <T> ChangeListener<T> onChange(Runnable action) {
        return (observable, previous, current) -> {
            Objects.requireNonNull(observable);
            action.run();
        };
    }

    private <T> ChangeListener<T> onCurrentChange(java.util.function.Consumer<T> consumer) {
        return (observable, previous, current) -> {
            Objects.requireNonNull(observable);
            consumer.accept(current);
        };
    }

    private Window getWindow() {
        return rootPane.getScene() == null ? null : rootPane.getScene().getWindow();
    }

    private String starterDocumentText() {
        return String.join("\n",
                "package demo;",
                "",
                "public class Main {",
                "    public static void main(String[] args) {",
                "        // TODO: Extend Editora with more plugins and language services.",
                "        System.out.println(\"Welcome to Editora\");",
                "    }",
                "}",
                "");
    }
}

