package com.editora;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.ui.MainController;

import com.editora.ui.Themes;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class App extends Application {

    // Quiet tm4e/Oniguruma grammar-compile WARNINGs ("']' without escape", "No grammar source for
    // scope …") — benign noise from bundled-grammar regex quirks. Held in a static field so the JUL
    // logger isn't garbage-collected (which would silently drop the configured level). SEVERE still
    // surfaces real errors.
    private static final Logger TM4E_LOG = Logger.getLogger("org.eclipse.tm4e");

    // Quiet LSP4J's "Unsupported notification method" WARNINGs — language servers (e.g. JDT LS) send
    // vendor extension notifications (language/status, language/eventNotification, …) that the standard
    // LanguageClient doesn't define; LSP4J logs+ignores them, which would spam during project indexing.
    // Held in a static field so the JUL logger isn't garbage-collected; SEVERE still surfaces real errors.
    private static final Logger LSP4J_LOG = Logger.getLogger("org.eclipse.lsp4j");

    static {
        TM4E_LOG.setLevel(Level.SEVERE);
        LSP4J_LOG.setLevel(Level.SEVERE);
    }

    @Override
    public void start(Stage stage) throws IOException {
        com.editora.ui.Fonts.load(); // register bundled fonts before any UI/CSS uses them

        // Config dir precedence: --config-dir CLI arg > EDITORA_CONFIG_DIR env var > default
        // (~/.editora, or ~/.editora-dev with --dev so a dev instance can't disturb production).
        var rawArgs = getParameters().getRaw();
        String cliConfigDir = configDirArg(rawArgs);
        ConfigManager config = cliConfigDir != null
                ? new ConfigManager(java.nio.file.Path.of(cliConfigDir))
                : new ConfigManager(devFlag(rawArgs));
        Settings settings = config.load();
        // Mirror captured logs to a session file in the config dir so a packaged build's log survives a
        // crash and can be attached to a bug report (the in-memory capture was installed in main()).
        com.editora.ui.DebugLog.attachFile(config.getConfigDir());

        // Localize the UI: pick the language (explicit setting, else system, else English) and load the
        // message catalog before any UI text is created. A change takes effect on the next launch.
        com.editora.i18n.Messages.init(com.editora.i18n.Messages.resolve(
                settings.getUiLanguage(),
                com.editora.i18n.Messages.available().keySet(),
                java.util.Locale.getDefault().getLanguage()));
        // Align the JVM default locale with the chosen UI language so JavaFX localizes its own
        // built-in dialog buttons (OK/Cancel/Yes/No) to match. Resolved above first, so this doesn't
        // affect the system-language fallback.
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(com.editora.i18n.Messages.current()));

        Application.setUserAgentStylesheet(Themes.stylesheetFor(settings.getTheme()));

        CommandRegistry registry = new CommandRegistry();
        KeymapManager keymap = new KeymapManager();
        keymap.loadNamed(settings.getKeymap());
        keymap.applyOverrides(settings.getKeybindings());

        FXMLLoader loader = new FXMLLoader(App.class.getResource("ui/main.fxml"));
        BorderPane root = loader.load();
        MainController controller = loader.getController();
        controller.init(stage, config, registry, keymap);
        controller.setHostServices(getHostServices()); // for the Welcome page's home-page link

        // Wrap the UI in a StackPane so a floating overlay (the Zen-mode exit button) can sit on top.
        javafx.scene.layout.StackPane sceneRoot = new javafx.scene.layout.StackPane(root);
        // Experiment: on macOS, render the UI chrome in the native system font (San Francisco) instead of
        // AtlantaFX's Inter — across every window (main, dialogs, the Settings window, and popups like the
        // command palette / context menus / tooltips). See installMacSystemFont.
        if (isMac()) {
            installMacSystemFont();
        }
        controller.installZenOverlay(sceneRoot);
        Scene scene = new Scene(sceneRoot, 1000, 700);
        // Pre-fill with the theme background so the first frame isn't JavaFX's default light gray
        // (which otherwise flashes before the CSS is applied — most visible with a dark theme). The
        // root and editor are transparent until CSS paints them, so this fill shows through. (Scene
        // fill isn't a CSS property, so it doesn't interfere with later theme switches.)
        scene.setFill(Themes.backgroundFor(settings.getTheme()));
        scene.getStylesheets().addAll(
                App.class.getResource("styles/app.css").toExternalForm(),
                App.class.getResource("styles/syntax.css").toExternalForm());

        KeyDispatcher keyDispatcher = new KeyDispatcher(registry, keymap, controller::setStatus);
        keyDispatcher.install(scene);
        controller.setKeyDispatcher(keyDispatcher);

        // Ctrl + mouse wheel zooms (and is consumed so the editor/preview doesn't also scroll): the
        // Markdown preview when the pointer is over it, otherwise the editor text.
        scene.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() && e.getDeltaY() != 0) {
                controller.zoomFromWheel(e);
                e.consume();
            }
        });

        stage.setScene(scene);
        stage.setTitle("Editora");
        loadAppIcons(stage);
        controller.applyEditorTheme(settings.getEditorTheme());
        restoreWindowBounds(stage, config.getWorkspaceState());
        stage.show();

        // Startup CLI actions: open a project (if enabled), restore the session, open any FILE(s)
        // (jumping to LINE:COLUMN), and enter Zen — all on top of the restored session.
        var raw = getParameters().getRaw();
        String project = projectArg(raw);
        controller.startup(project == null ? null : java.nio.file.Path.of(project),
                fileTargets(raw), zenFlag(raw), newFileArg(raw), simpleFlag(raw));
    }

    /** True when running on macOS (used to opt the UI chrome into the native system font). */
    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    /**
     * The macOS UI-font override stylesheet (the {@code .root} system-font rule). It maps San Francisco
     * via the logical {@code "System"} family — the named {@code .AppleSystemUIFont} / {@code SF Pro}
     * families aren't in {@link javafx.scene.text.Font#getFamilies()}.
     */
    private static final String UI_FONT_CSS =
            App.class.getResource("styles/ui-system-font.css").toExternalForm();

    /**
     * macOS only: render every window's UI chrome in the native system font instead of AtlantaFX's Inter.
     * Dialogs and popups (alerts, the Settings window, the command palette, context menus, tooltips) each
     * live in their own scene that doesn't share {@code app.css}, so the override is added as a small
     * author-level <em>scene</em> stylesheet on each window's scene. A scene stylesheet overrides the
     * AtlantaFX user-agent stylesheet and — unlike an inline style on the root — is re-applied cleanly
     * when the theme is switched at runtime ({@code setUserAgentStylesheet}), which is exactly how
     * {@code app.css} already behaves. The editor surface keeps its own monospace font because
     * {@code .editor-area} sets {@code -fx-font-family} explicitly. A listener on the live window list
     * covers windows opened later.
     */
    private static void installMacSystemFont() {
        javafx.stage.Window.getWindows().addListener(
                (javafx.collections.ListChangeListener<javafx.stage.Window>) change -> {
                    while (change.next()) {
                        for (javafx.stage.Window w : change.getAddedSubList()) {
                            hookWindowFont(w);
                        }
                    }
                });
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
            hookWindowFont(w);
        }
    }

    /** Attaches the UI-font stylesheet to a window's scene now and whenever its scene changes. */
    private static void hookWindowFont(javafx.stage.Window window) {
        if (window == null) {
            return;
        }
        addFontStylesheet(window.getScene());
        window.sceneProperty().addListener((obs, old, scene) -> addFontStylesheet(scene));
    }

    /** Adds the UI-font stylesheet to a scene (idempotent). */
    private static void addFontStylesheet(javafx.scene.Scene scene) {
        if (scene == null || scene.getStylesheets().contains(UI_FONT_CSS)) {
            return;
        }
        scene.getStylesheets().add(UI_FONT_CSS);
    }

    /**
     * Extracts the {@code --config-dir} program argument (supporting both {@code --config-dir=PATH} and
     * {@code --config-dir PATH}); returns the trimmed path, or {@code null} when not given. Pure +
     * unit-testable.
     */
    static String configDirArg(java.util.List<String> args) {
        if (args == null) {
            return null;
        }
        String prefix = "--config-dir=";
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null) {
                continue;
            }
            if (a.startsWith(prefix)) {
                String v = a.substring(prefix.length()).trim();
                return v.isEmpty() ? null : v;
            }
            if (a.equals("--config-dir") && i + 1 < args.size() && args.get(i + 1) != null) {
                String v = args.get(i + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /**
     * Restores the main window's size, position, and maximized state from the last session. Bounds
     * are only applied when they were saved (non-zero size) and still land on a connected screen, so
     * a disconnected monitor or first launch falls back to the scene's default size and OS centering.
     */
    private void restoreWindowBounds(Stage stage, WorkspaceState state) {
        double w = state.getWindowWidth();
        double h = state.getWindowHeight();
        if (w > 0 && h > 0) {
            double x = state.getWindowX();
            double y = state.getWindowY();
            if (!Screen.getScreensForRectangle(x, y, w, h).isEmpty()) {
                stage.setX(x);
                stage.setY(y);
            }
            stage.setWidth(w);
            stage.setHeight(h);
        }
        if (state.isWindowMaximized()) {
            stage.setMaximized(true);
        }
    }

    /** Adds the app icon (multiple sizes) so it shows in the title bar, dock, and taskbar. */
    private void loadAppIcons(Stage stage) {
        for (int size : new int[]{16, 32, 48, 128, 256, 512}) {
            var in = App.class.getResourceAsStream("/com/editora/icons/icon-" + size + ".png");
            if (in != null) {
                stage.getIcons().add(new javafx.scene.image.Image(in));
            }
        }
    }

    public static void main(String[] args) {
        // Run AWT/Java2D headless. The Markdown preview rasterizes SVG badges via JSVG, which uses
        // java.awt (BufferedImage/Graphics2D). On macOS, AWT's native toolkit (the AppKit/Metal Java2D
        // pipeline) contends with JavaFX's Glass/Prism for the single AppKit run loop — an intermittent
        // deadlock/hang that grows more likely the more SVGs get rasterized (i.e. the more Markdown
        // previews are open). Headless Java2D rasterizes to a BufferedImage purely in software with no
        // AppKit, so SVG rendering still works while the AWT↔JavaFX conflict disappears. Must be set
        // before any AWT class loads; main() is the first app code, so this is safe for every entry point.
        System.setProperty("java.awt.headless", "true");
        // Capture java.util.logging output + uncaught exceptions in-memory so a packaged build (no
        // visible stderr) can show them via the Debug Log window. Cheap; safe before launch.
        com.editora.ui.DebugLog.install();
        // --version / --help print and exit BEFORE the GUI launches (works for every entry point,
        // since the fat-jar Launcher and the jpackage module both run this main).
        for (String a : args) {
            if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println(helpText());
                return;
            }
            if ("--version".equals(a) || "-V".equals(a)) {
                System.out.println(AppInfo.versionLine());
                return;
            }
        }
        launch(args);
    }

    static String helpText() {
        return """
                %s — a keyboard-driven, cross-platform programmer's text editor.

                Usage: editora [options] [FILE[:LINE[:COLUMN]] ...]

                Options:
                  --config-dir <path>   Use <path> as the config directory (or set EDITORA_CONFIG_DIR)
                  --dev                 Dev mode: use ~/.editora-dev (separate from production config)
                  --project[=]<dir>     Open <dir> as a project (only when Projects are enabled)
                  --new-file[=name]     Open a new buffer instead of the Welcome page (optionally named)
                  --zen                 Start in Zen (distraction-free) mode
                  --simple              Start in Simple UI mode (minimal chrome; session only)
                  --version, -V         Print the version and exit
                  --help, -h            Print this help and exit

                Arguments:
                  FILE                  Open FILE
                  FILE:LINE             Open FILE and jump to LINE
                  FILE:LINE:COLUMN      Open FILE and jump to LINE and COLUMN""".formatted(AppInfo.NAME);
    }

    /** The {@code --project=DIR} / {@code --project DIR} value, or {@code null} when not given. */
    static String projectArg(java.util.List<String> args) {
        return optionValue(args, "--project");
    }

    /** True if {@code --dev} is present (dev mode: separate ~/.editora-dev config; future: more logging). */
    static boolean devFlag(java.util.List<String> args) {
        return args != null && args.contains("--dev");
    }

    /** True if {@code --zen} is present. */
    static boolean zenFlag(java.util.List<String> args) {
        return args != null && args.contains("--zen");
    }

    /** True if {@code --simple} is present (session-only Simple UI mode override). */
    static boolean simpleFlag(java.util.List<String> args) {
        return args != null && args.contains("--simple");
    }

    /**
     * The {@code --new-file[=NAME]} request, or {@code null} when not given. Opens a fresh buffer at
     * startup instead of the Welcome page: {@code ""} (bare {@code --new-file}) ⇒ a blank untitled buffer;
     * {@code NAME} ({@code --new-file=foo.txt}) ⇒ an unsaved buffer titled NAME (highlighted by its
     * extension). Pure + unit-testable.
     */
    static String newFileArg(java.util.List<String> args) {
        if (args == null) {
            return null;
        }
        String prefix = "--new-file=";
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length()).trim();
            }
            if (a.equals("--new-file")) {
                return "";
            }
        }
        return null;
    }

    private static String optionValue(java.util.List<String> args, String name) {
        if (args == null) {
            return null;
        }
        String prefix = name + "=";
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null) {
                continue;
            }
            if (a.startsWith(prefix)) {
                String v = a.substring(prefix.length()).trim();
                return v.isEmpty() ? null : v;
            }
            if (a.equals(name) && i + 1 < args.size() && args.get(i + 1) != null) {
                String v = args.get(i + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /**
     * Positional file arguments (parsed for an optional {@code :LINE[:COLUMN]} suffix), skipping option
     * flags and the value tokens consumed by {@code --config-dir} / {@code --project}.
     */
    static java.util.List<com.editora.ui.MainController.OpenTarget> fileTargets(java.util.List<String> args) {
        java.util.List<com.editora.ui.MainController.OpenTarget> out = new java.util.ArrayList<>();
        if (args == null) {
            return out;
        }
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null || a.isEmpty()) {
                continue;
            }
            if (a.equals("--config-dir") || a.equals("--project")) {
                i++; // skip the value token
                continue;
            }
            if (a.startsWith("-")) {
                continue; // any other option (incl. --xxx=yyy, --zen, --version, --help)
            }
            out.add(parseTarget(a));
        }
        return out;
    }

    /**
     * Parses {@code PATH}, {@code PATH:LINE}, or {@code PATH:LINE:COLUMN} into an {@link
     * com.editora.ui.MainController.OpenTarget} (line/column 1-based, 0 = unspecified). A trailing
     * {@code :digits[:digits]} is the position; everything before it is the path, so Windows paths like
     * {@code C:\dir} (no trailing {@code :digits}) and {@code C:\dir:10} parse correctly.
     */
    static com.editora.ui.MainController.OpenTarget parseTarget(String arg) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?$").matcher(arg);
        if (m.matches()) {
            int line = Integer.parseInt(m.group(2));
            int col = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            return new com.editora.ui.MainController.OpenTarget(java.nio.file.Path.of(m.group(1)), line, col);
        }
        return new com.editora.ui.MainController.OpenTarget(java.nio.file.Path.of(arg), 0, 0);
    }
}
