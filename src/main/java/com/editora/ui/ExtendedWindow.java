package com.editora.ui;

import java.util.Locale;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The window drawn without a system title bar, with the menu bar in its place
 * ({@link StageStyle#EXTENDED}, JavaFX 24+).
 *
 * <p>{@code EXTENDED} is the reason this is worth doing at all: it drops the title bar while <b>keeping
 * the system window buttons</b>, so none of minimise/maximise/close, drag-to-move, double-click-to-
 * maximise, edge resize or Aero Snap has to be reimplemented — which is what {@code UNDECORATED} would
 * have meant. {@link HeaderBar} reports where those buttons are ({@code leftSystemInset} on macOS,
 * {@code rightSystemInset} on Windows/Linux) and reserves the space itself.
 *
 * <p><b>Experimental, and Linux-only for now.</b> Not because the API is: it is cross-platform. Because
 * a menu bar drawn inside the window is <em>wrong</em> on macOS, where the menu belongs to the system
 * bar at the top of the screen, and because window decoration is the most platform-specific thing an
 * application does — Windows and macOS need device-testing before either is switched on.
 */
final class ExtendedWindow {

    private ExtendedWindow() {}

    /**
     * Whether the extended window may be used on this OS: <b>Linux and Windows</b>.
     *
     * <p>macOS is excluded, and not because the API is missing there — it is the one platform where this
     * is the wrong idea. The menu belongs to the system menu bar at the top of the screen, so drawing it
     * inside the window would be a worse window, not a taller-value one.
     *
     * <p>An unrecognised {@code os.name} is refused rather than assumed to work: this decides how the
     * window is decorated, and the failure mode of guessing wrong is a window that will not open.
     *
     * <p>Pure, and takes the OS name, so the gate is testable off the platform it gates.
     */
    static boolean supportedOn(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return false;
        }
        // Windows, plus Linux and the BSDs, where the desktop draws client-side decorations anyway.
        return os.contains("win") || os.contains("linux") || os.contains("bsd") || os.contains("unix");
    }

    /** The system property JavaFX requires before any preview API may be used. */
    static final String PREVIEW_PROPERTY = "javafx.enablePreview";

    /**
     * Whether a window built now should be extended: the user asked for it, the OS allows it, and JavaFX's
     * preview features are switched on.
     *
     * <p>That last condition is not optional and not defensive. {@code StageStyle.EXTENDED} is a
     * <b>preview</b> feature in JavaFX 26, and {@code Stage.initStyle} <em>throws</em> without
     * {@code -Djavafx.enablePreview=true} — which, thrown out of window construction, means the
     * application starts with no window at all. Asking first is what keeps a cosmetic setting from being
     * able to do that.
     */
    static boolean enabled(boolean setting, String osName, boolean previewEnabled) {
        return setting && previewEnabled && supportedOn(osName);
    }

    /** Whether this JVM was started with JavaFX's preview features enabled. */
    static boolean previewEnabled() {
        return Boolean.getBoolean(PREVIEW_PROPERTY);
    }

    /**
     * The header that replaces the title bar: the menu bar on the left, the window title in the middle.
     *
     * <p>The title is drawn here because nothing else will — the OS is no longer painting one, and it is
     * the only place the active file's full path appears besides the breadcrumb.
     *
     * <p>The menu bar is marked {@link HeaderDragType#NONE}: a header is draggable by default, and a menu
     * bar that begins a window drag instead of opening its menu is unusable.
     */
    static HeaderBar header(Node menuBar, Stage stage) {
        Label title = new Label();
        title.getStyleClass().add("window-header-title");
        title.textProperty().bind(stage.titleProperty());
        HeaderBar.setDragType(title, HeaderDragType.DRAGGABLE);
        HeaderBar.setDragType(menuBar, HeaderDragType.NONE);

        HeaderBar header = new HeaderBar();
        header.getStyleClass().add("window-header");
        header.setLeft(menuBar);
        header.setCenter(title);
        // Reserve for the system buttons on whichever side this platform puts them; the unused side
        // reports a zero inset, so asking for both is right rather than merely harmless.
        header.setLeftSystemPadding(true);
        header.setRightSystemPadding(true);
        return header;
    }
}
