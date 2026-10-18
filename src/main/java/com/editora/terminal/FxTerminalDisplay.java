package com.editora.terminal;

import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;

/**
 * The emulator's view of the display: JediTerm calls into this whenever an escape sequence changes
 * something the UI owns (cursor position, window title, alternate screen, mouse reporting).
 *
 * <p><b>Threading:</b> every method here is invoked on the emulator thread, never on the JavaFX
 * application thread. State the renderer reads is therefore held in volatile fields, and anything
 * that touches the scene graph (the window title) is bounced through {@link Platform#runLater}.
 */
public final class FxTerminalDisplay implements TerminalDisplay {

    /** Cursor column, 0-based. */
    private volatile int cursorX;
    /** Cursor row as JediTerm reports it: <b>1-based</b>, relative to the top of the screen. */
    private volatile int cursorY = 1;

    private volatile boolean cursorVisible = true;
    /**
     * Null until a program requests a shape (DECSCUSR). Null is meaningful: it is what lets the
     * user's preference apply, while still yielding to a program that has an opinion.
     */
    private volatile CursorShape cursorShape;

    private volatile boolean alternateScreen;
    private volatile MouseMode mouseMode = MouseMode.MOUSE_REPORTING_NONE;
    private volatile MouseFormat mouseFormat = MouseFormat.MOUSE_FORMAT_XTERM;
    private volatile boolean bracketedPaste;

    /**
     * The two sources a title can come from, kept apart rather than resolved on arrival.
     *
     * <p>{@code shellTitle} is what an escape sequence set, {@code cwd} is what the OS says the
     * shell's directory is, and {@link TerminalTitle} decides between them. Collapsing them into one
     * field as each arrives would lose the distinction — a program that sets a title and later
     * clears it (vim does exactly this on exit) has to leave the directory showing again, and that
     * is only possible if the directory was never overwritten.
     */
    private volatile String shellTitle = "";

    private volatile String cwd = "";
    private final String home = System.getProperty("user.home", "");

    private final StringProperty windowTitle = new SimpleStringProperty(TerminalTitle.UNTITLED);
    private final StringProperty tabTitle = new SimpleStringProperty(TerminalTitle.UNTITLED);

    /**
     * What the tab's hover says: the directory, in full.
     *
     * <p>Deliberately the directory even when the shell has set a title, which is the case where it
     * is worth the most. A tab reading {@code vim} says what is running and nothing about where, and
     * the tab is too narrow to say both — so the label takes the title and the hover takes the path.
     */
    private final StringProperty tabTooltip = new SimpleStringProperty("");

    private Runnable onBell = () -> {};
    private Runnable onRepaint = () -> {};
    private Runnable onAlternateScreenChanged = () -> {};

    public void setOnBell(Runnable onBell) {
        this.onBell = onBell;
    }

    /** Invoked (off the FX thread) whenever something changed that the renderer should reflect. */
    public void setOnRepaint(Runnable onRepaint) {
        this.onRepaint = onRepaint;
    }

    public void setOnAlternateScreenChanged(Runnable listener) {
        this.onAlternateScreenChanged = listener;
    }

    @Override
    public void setCursor(int x, int y) {
        cursorX = x;
        cursorY = y;
        onRepaint.run();
    }

    @Override
    public void setCursorShape(CursorShape shape) {
        if (shape != null) cursorShape = shape;
        onRepaint.run();
    }

    @Override
    public void beep() {
        onBell.run();
    }

    @Override
    public void onResize(TermSize newSize, RequestOrigin origin) {
        onRepaint.run();
    }

    @Override
    public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        onRepaint.run();
    }

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        onRepaint.run();
    }

    @Override
    public void useAlternateScreenBuffer(boolean enabled) {
        alternateScreen = enabled;
        // Entering the alternate screen (vim, less, htop) must also pin the view to the live
        // screen: scrollback belongs to the primary buffer, and leaving a scroll offset applied
        // would render the full-screen program at an offset it knows nothing about.
        onAlternateScreenChanged.run();
        onRepaint.run();
    }

    /**
     * What the <em>shell</em> set, not what is on screen.
     *
     * <p>JediTerm reads this back to implement the title stack (OSC 22 push, OSC 23 pop). Answering
     * with the resolved title would let a push/pop round-trip bake the current directory into
     * {@code shellTitle}, after which the tab would be frozen at whichever directory happened to be
     * current when some program saved the title.
     */
    @Override
    public String getWindowTitle() {
        return shellTitle;
    }

    @Override
    public void setWindowTitle(String title) {
        shellTitle = title == null ? "" : title;
        refreshTitles();
    }

    /** The shell's working directory, from {@link CwdWatcher}. */
    public void setCwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd;
        refreshTitles();
    }

    /**
     * The shell's working directory, empty when the OS will not say.
     *
     * <p>Read by the link layer, which resolves a relative path against it: {@code src/Foo.java} in
     * a build's output means that file where the build ran, and every tab is somewhere different.
     * Volatile because the watcher's thread writes it and the FX thread reads it.
     */
    public String getCwd() {
        return cwd;
    }

    private void refreshTitles() {
        String window = TerminalTitle.window(shellTitle, cwd, home);
        String tab = TerminalTitle.tab(shellTitle, cwd, home);
        String tooltip = TerminalTitle.tooltip(shellTitle, cwd, home);
        Platform.runLater(() -> {
            windowTitle.set(window);
            tabTitle.set(tab);
            tabTooltip.set(tooltip);
        });
    }

    /** The full path, for the title bar. */
    public StringProperty windowTitleProperty() {
        return windowTitle;
    }

    /** The directory's own name, for the tab strip, where a path does not fit. */
    public StringProperty tabTitleProperty() {
        return tabTitle;
    }

    /**
     * The shell's directory in full, for the tab's hover — where a path does fit.
     *
     * <p>Empty is a real answer rather than a failure: {@code ProcessCwd} has no Windows
     * implementation, so there the hover has nothing to add and is not shown at all.
     */
    public StringProperty tabTooltipProperty() {
        return tabTooltip;
    }

    /**
     * Supplies the live selection. A supplier rather than a field because the selection is owned by
     * the view and mutated on the FX thread, while this is read from the emulator thread.
     */
    public void setSelectionSupplier(Supplier<TerminalSelection> selectionSupplier) {
        this.selectionSupplier = selectionSupplier;
    }

    private Supplier<TerminalSelection> selectionSupplier = () -> null;

    @Override
    public TerminalSelection getSelection() {
        return selectionSupplier.get();
    }

    @Override
    public void terminalMouseModeSet(MouseMode mode) {
        mouseMode = mode;
    }

    @Override
    public void setMouseFormat(MouseFormat format) {
        mouseFormat = format;
    }

    @Override
    public boolean ambiguousCharsAreDoubleWidth() {
        // East-Asian "ambiguous width" characters rendered narrow, matching xterm's default and
        // every mainstream terminal. Rendering them wide desynchronises column arithmetic against
        // any program that assumes narrow.
        return false;
    }

    @Override
    public void setBracketedPasteMode(boolean enabled) {
        bracketedPaste = enabled;
    }

    public int getCursorX() {
        return cursorX;
    }

    /** 1-based, as JediTerm reports it. Subtract 1 to index a row. */
    public int getCursorY() {
        return cursorY;
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    /** The shape a program requested, or null if none has. */
    public CursorShape getCursorShape() {
        return cursorShape;
    }

    public boolean isAlternateScreen() {
        return alternateScreen;
    }

    public MouseMode getMouseMode() {
        return mouseMode;
    }

    public MouseFormat getMouseFormat() {
        return mouseFormat;
    }

    public boolean isBracketedPaste() {
        return bracketedPaste;
    }
}
