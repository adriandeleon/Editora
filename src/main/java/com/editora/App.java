package com.editora;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.ui.Themes;

/**
 * The JavaFX {@link Application} entry point. {@link #main} runs before the toolkit starts: it sets
 * {@code java.awt.headless=true} (the macOS SVG/Java2D guard), installs the debug-log + uncaught-exception
 * handlers, handles {@code --version}/{@code --help}, and otherwise launches the FX application. {@link #start}
 * parses the remaining CLI arguments, loads the shared config and localized UI, and hands off to
 * {@code ui.WindowManager} to build the window(s). The non-{@link Application} {@code com.editora.Launcher}
 * is the fat-jar/classpath entry point and routes through {@link #main}.
 */
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

        // On macOS the JavaFX/AppKit (FX application) thread's context classloader can be null, which
        // breaks loading FXML-referenced classes (and other lazy class loads) when building windows at
        // runtime — the single-window app loaded its FXML once at startup and never hit this, but
        // multi-window builds a window per project. Pin a real classloader as the FXMLLoader default and
        // as this thread's context classloader so every runtime window build (and lazy load) resolves.
        javafx.fxml.FXMLLoader.setDefaultClassLoader(App.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(App.class.getClassLoader());

        // Config dir precedence: --config-dir CLI arg > EDITORA_CONFIG_DIR env var > default
        // (~/.editora, or ~/.editora-dev with --dev so a dev instance can't disturb production). The
        // bootstrap ConfigManager loads the shared config once; per-window ConfigManagers reuse it.
        var rawArgs = getParameters().getRaw();
        String cliConfigDir = configDirArg(rawArgs);
        ConfigManager bootstrap = cliConfigDir != null
                ? new ConfigManager(java.nio.file.Path.of(cliConfigDir))
                : new ConfigManager(devFlag(rawArgs));
        Settings settings = bootstrap.load();
        SharedConfig shared = bootstrap.shared();
        // Flush any pending off-thread settings/session writes on exit, covering paths where persistSession()
        // doesn't run (the normal quit already flushes via its blocking config.save()).
        Runtime.getRuntime().addShutdownHook(new Thread(shared::flushWrites, "config-flush"));
        // Mirror captured logs to a session file in the config dir so a packaged build's log survives a
        // crash and can be attached to a bug report (the in-memory capture was installed in main()).
        com.editora.ui.DebugLog.attachFile(shared.getConfigDir());

        // Point the spawned-server ledger at the config dir and reap any LSP/DAP server leaked by a
        // previous run that died too hard for the shutdown hook to fire (SIGKILL, power loss). Must run
        // before any window builds (which can start servers) so we don't race a fresh server's startup.
        com.editora.process.ProcessRegistry.setLedgerFile(shared.getConfigDir().resolve("spawned-servers.txt"));
        com.editora.process.ProcessRegistry.reapOrphans();

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

        KeymapManager keymap = new KeymapManager();
        keymap.loadNamed(settings.getKeymap());
        keymap.applyOverrides(settings.getKeybindings());

        // Experiment: on macOS, render the UI chrome in the native system font (San Francisco) instead of
        // AtlantaFX's Inter — across every window. A listener on the live window list covers windows
        // opened later (so each project window picks it up). See installMacSystemFont.
        if (isMac()) {
            installMacSystemFont();
        }

        // WindowManager builds each window (reusing the primary stage for the first) and, with projects
        // enabled, restores every window that was open at last quit. CLI targets go to the first window.
        com.editora.ui.WindowManager windows = new com.editora.ui.WindowManager(shared, keymap, getHostServices());
        windows.launch(
                stage,
                projectArg(rawArgs),
                fileTargets(rawArgs),
                zenFlag(rawArgs),
                newFileArg(rawArgs),
                simpleFlag(rawArgs),
                singleWindowArg(rawArgs));
    }

    /** True when running on macOS (used to opt the UI chrome into the native system font). */
    private static boolean isMac() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
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
        javafx.stage.Window.getWindows().addListener((javafx.collections.ListChangeListener<javafx.stage.Window>)
                change -> {
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
        // since the fat-jar Launcher and the jpackage module both run this main). Use System.exit, not
        // a bare return: when this Application subclass is launched in module mode (the jpackage app,
        // mvn javafx:run), the Java/FX launcher pre-starts the JavaFX toolkit, whose non-daemon
        // keep-alive thread keeps the JVM alive after main() returns — so a plain return would print
        // the text and then hang until killed. (The fat-jar Launcher path has no toolkit and is
        // unaffected, but exit(0) is correct there too.)
        for (String a : args) {
            if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println(helpText());
                System.exit(0);
            }
            if ("--version".equals(a) || "-V".equals(a)) {
                System.out.println(AppInfo.versionLine());
                System.exit(0);
            }
        }
        // Force-kill any LSP/DAP server we spawn if the JVM exits without going through a window-close
        // teardown — a plain `kill`/SIGTERM, an OS quit, or most crashes. Without this, killing the app
        // orphaned the external servers (e.g. jdtls), which then pile up and hold their workspace locks.
        com.editora.process.ProcessRegistry.installShutdownHook();
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
                  --single-window[=project]  Open just one window (the named project, else no-project)
                                        instead of restoring all windows; doesn't change the saved layout
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

    /**
     * The {@code --single-window[=NAME]} request, or {@code null} when not given. Opens exactly one window at
     * startup instead of restoring the whole saved set: {@code ""} (bare {@code --single-window}) ⇒ the
     * no-project window; {@code NAME} ({@code --single-window=MyProj}) ⇒ that project's window (falling back
     * to the no-project window if no project matches). Session-only — it doesn't change the saved layout.
     * Pure + unit-testable.
     */
    static String singleWindowArg(java.util.List<String> args) {
        if (args == null) {
            return null;
        }
        String prefix = "--single-window=";
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length()).trim();
            }
            if (a.equals("--single-window")) {
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
