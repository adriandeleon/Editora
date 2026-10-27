package com.editora.terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.StyledTextConsumer;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.mouse.MouseEventProcessingSettings;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Renders a {@link TerminalSession}'s screen onto a canvas and feeds it keyboard input.
 *
 * <p><b>Repaint policy.</b> The emulator signals a change on its own thread, often once per
 * character written. Repainting on each signal would queue thousands of runLater tasks a second
 * during something like {@code cat} of a large file. Instead a change only sets a dirty flag, and a
 * single {@link AnimationTimer} repaints at most once per frame — the terminal's output rate is
 * decoupled from the render rate, which is what keeps a flood of output from starving the UI.
 *
 * <p>Drawing is per style run, not per character: {@code processScreenLines} hands back each run of
 * identically-styled text, and each becomes one {@code fillText} call.
 */
public final class TerminalView extends Region {

    /** Extra leading, as a fraction of font size. Terminals look cramped at exactly 1.0. */
    private static final double LINE_SPACING = 1.18;

    private static final int MIN_COLUMNS = 20;
    private static final int MIN_ROWS = 4;

    /** How opaque the selection wash is over the text beneath it. */
    private static final double SELECTION_ALPHA = 0.30;

    private final Canvas canvas = new Canvas();

    /**
     * Scrollbar for the scrollback.
     *
     * <p>Its range is in <em>lines of history</em>, with the bottom of the track meaning the live
     * screen — the direction every terminal uses. Value therefore counts up as you scroll back:
     * {@code value = historyLines + scrollOrigin}, since scrollOrigin is 0 at the live screen and
     * negative going up.
     */
    private final javafx.scene.control.ScrollBar scrollBar = new javafx.scene.control.ScrollBar();

    /** Guards the value listener while the bar is being updated from the buffer, not the mouse. */
    private boolean updatingScrollBar;

    private boolean scrollBarEnabled = true;

    /**
     * Whether the press that began the current drag landed on the scrollbar.
     *
     * <p>Latched at the press rather than re-tested per event: what a drag reports as its target is
     * not something to depend on, and a drag that started on the thumb must stay the thumb's for as
     * long as the button is held — including when the pointer leaves the bar, which it does the
     * moment anyone drags at a natural angle.
     */
    private boolean pressedOnScrollBar;

    private final FxTerminalDisplay display = new FxTerminalDisplay();

    private @Nullable TerminalSession session;
    private @Nullable TerminalTextBuffer buffer;

    /**
     * Theme colours. Held rather than read from a constant so a theme change repaints in place —
     * see {@link #setPalette}.
     */
    private TerminalPalette palette = TerminalPalettes.EDITORA_DARK;

    /**
     * Selection highlight, painted over the text rather than behind it.
     *
     * <p>Behind would mean threading selection state through every style run and splitting runs at
     * its edges. A translucent wash keeps the run loop untouched and stays legible over any
     * foreground colour, which matters in a terminal where a cell can be any of 16 million. Derived
     * from the palette's blue so it suits a light theme as well as a dark one.
     */
    private Color selectionWash = washFor(TerminalPalettes.EDITORA_DARK);

    /** Preferred cursor shape. A program can still override it with DECSCUSR while running. */
    private TerminalCursor preferredCursor = TerminalCursor.BLOCK;

    private boolean bellEnabled = true;

    /** Bundled with Editora, so always there; the window passes the editor font once it is wired. */
    static final String DEFAULT_FONT_FAMILY = "JetBrains Mono";

    private String fontFamily = DEFAULT_FONT_FAMILY;

    private Font font;
    private Font boldFont;
    private Font italicFont;
    private Font boldItalicFont;

    /** Faces for private-use code points (Nerd Font icons), null when the terminal face should draw them: see IconFont. */
    private @Nullable Font iconFont;

    private @Nullable Font boldIconFont;
    private @Nullable Font italicIconFont;
    private @Nullable Font boldItalicIconFont;
    private double fontSize;
    private double charWidth;
    private double lineHeight;
    private double ascent;

    /**
     * Rows scrolled back. 0 is the live screen; negative values reach into history. Named after
     * JediTerm's own term for the coordinate {@code processHistoryAndScreenLines} expects.
     *
     * <p>Volatile because the history listener adjusts it from the emulator thread — see
     * {@link #onHistoryLineCountChanged()}.
     */
    private volatile int scrollOrigin;

    /**
     * The active selection, in buffer coordinates: x is a column, y is a row where 0 is the top of
     * the live screen and negative reaches into history — the same axis as {@link #scrollOrigin}.
     *
     * <p>Volatile because the emulator reads it through {@code TerminalDisplay.getSelection()} on
     * its own thread while the mouse mutates it on the FX thread.
     */
    private volatile @Nullable TerminalSelection selection;

    /**
     * Where the current drag began, so a backwards drag extends rather than restarts.
     *
     * <p>Moved with its text as output scrolls, like the selection: a drag held while a build runs would otherwise
     * extend from whatever row has since scrolled under where it began — and with autoscroll a drag routinely lasts
     * long enough for that. Volatile for the same reason the selection is: the emulator thread moves it.
     */
    private volatile @Nullable Point selectionAnchor;

    /**
     * Whether {@link #selection} is a block — the same columns on every row — rather than a stream. A block's x is a
     * <b>column</b>, not a slot; see {@link SelectionGeometry#blockDrag}.
     */
    private volatile boolean rectangular;

    /** The column the current drag was pressed in, which a block needs and a stream does not. */
    private int anchorColumn;

    /** Whether the current drag makes a block, decided at the press and kept for the drag. */
    private boolean blockDrag;

    /** Whether the button is down on a drag of our own (not the scrollbar's, not a program's). */
    private boolean dragging;

    /**
     * The selection's text as it was when the selection was made, so a later change to that text drops the
     * highlight. A keystroke no longer does: typing a command under a selection of the output above it should leave
     * that selection alone. What must not survive is a highlight over text that has been redrawn in place — a line
     * editor rewriting the prompt — because copying it would then produce something the user never selected.
     */
    private volatile @Nullable String settledText;

    /** Set by any buffer change while there is a selection; the next frame compares the text. */
    private volatile boolean selectionToVerify;

    /** Past this many rows the comparison is skipped: a Select All over a long history is not redrawn. */
    private static final int VERIFIED_ROWS = 1000;

    /** Copies each selection to the clipboard as it is made. A setting. */
    private boolean copyOnSelect;

    /** Lines already in history, to derive the delta the history listener does not supply. */
    private int knownHistoryLines;

    /** Lines a full history dropped since the last {@link #onHistoryLineCountChanged()}. */
    private int discardedHistoryLines;

    private volatile boolean dirty = true;
    private int columns = 80;
    private int rows = 24;

    /** Alt/Option prefixes ESC rather than composing a character; see {@link KeyEncoding}. */
    private boolean altIsMeta = true;

    /**
     * Paints dirty frames, and runs only while there are some: started by {@link #markDirty}, it stops after a
     * few frames with nothing to paint. Left running, one idle terminal kept the FX pulse at 60 Hz forever, even
     * with the tool window hidden — a wakeup every frame on a machine doing nothing.
     */
    private final AnimationTimer painter = new AnimationTimer() {
        private int idleFrames;

        @Override
        public void handle(long now) {
            if (dirty && isPaintable()) {
                dirty = false;
                idleFrames = 0;
                render();
                return;
            }
            if (++idleFrames < IDLE_FRAMES) return;
            idleFrames = 0;
            stop();
            painting.set(false);
            // A change that landed between the check above and the flag going down must still be painted.
            if (dirty && isPaintable() && painting.compareAndSet(false, true)) start();
        }
    };

    /** Frames with nothing to paint before the painter stops. */
    private static final int IDLE_FRAMES = 30;

    /** Whether {@link #painter} runs (or is about to). */
    private final java.util.concurrent.atomic.AtomicBoolean painting = new java.util.concurrent.atomic.AtomicBoolean();

    /** In a shown window and visible: a hidden terminal is painted when it is shown again, not meanwhile. */
    private boolean isPaintable() {
        if (getScene() == null
                || getScene().getWindow() == null
                || !getScene().getWindow().isShowing()) return false;
        for (Node n = this; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false; // an unselected tab, a hidden panel
        }
        return true;
    }

    /** Paints again when next possible: its tab was selected, its window shown. */
    public void repaint() {
        markDirty();
    }

    /** Whether the painter is running; for tests. */
    boolean isPainting() {
        return painting.get();
    }

    public TerminalView(double fontSize) {
        applyFont();
        setFontSize(fontSize);
        scrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        scrollBar.getStyleClass().add("terminal-scroll-bar");
        scrollBar.setFocusTraversable(false);
        scrollBar.setVisible(false);
        scrollBar.setManaged(false);
        scrollBar.valueProperty().addListener((o, was, now) -> onScrollBarMoved(now.doubleValue()));
        getChildren().addAll(canvas, scrollBar);
        setFocusTraversable(true);
        canvas.setFocusTraversable(false);
        // A screen reader sees a text area holding the screen, not an unnamed group (see queryAccessibleAttribute).
        setAccessibleRole(javafx.scene.AccessibleRole.TEXT_AREA);
        setAccessibleRoleDescription(tr("toolwindow.terminal"));

        addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
        addEventFilter(MouseEvent.MOUSE_EXITED, e -> updateHover(-1, -1, false));
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        // The modifier can go down and up without the mouse moving at all, and the underline has to
        // follow it either way — that is how the gesture announces itself to someone who has not
        // been told about it. Registered as its own filter because KEY_RELEASED is otherwise
        // unhandled here, and it never consumes.
        addEventFilter(KeyEvent.KEY_RELEASED, this::onKeyReleased);
        addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        // Keys held when focus leaves are released somewhere else; forgetting them keeps the next
        // press of one from being reported as a repeat, and no release from being owed for it.
        focusedProperty().addListener((o, was, now) -> {
            if (!now) kittyInput.reset();
        });
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        setOnContextMenuRequested(this::onContextMenuRequested);
        // The cursor is drawn hollow while the keyboard is elsewhere, so which terminal (or whether one) has it shows.
        focusedProperty().addListener((o, was, now) -> markDirty());
        // Not painted while out of sight (see isPaintable): shown again, it catches up.
        visibleProperty().addListener((o, was, now) -> markDirty());
        sceneProperty()
                .flatMap(javafx.scene.Scene::windowProperty)
                .flatMap(javafx.stage.Window::showingProperty)
                .addListener((o, was, now) -> markDirty());

        display.setOnRepaint(this::markDirty);
        display.setSelectionSupplier(() -> selection);
        // Visual bell. An audible one would mean java.awt.Toolkit.beep(), which initialises AWT —
        // see App.main for why that is off the table.
        //
        // Coalesced: `cat` of a binary file rings the bell thousands of times in one read, and each
        // used to queue its own runLater and its own transition. One flash is pending at a time;
        // the rest are the same flash.
        display.setOnBell(() -> {
            if (bellPending.compareAndSet(false, true)) Platform.runLater(this::onBell);
        });
        bellClear.setOnFinished(e -> markDirty());
        display.setOnAlternateScreenChanged(() -> {
            // Scrollback belongs to the primary buffer; entering or leaving the alternate screen
            // must snap back to the live view or a full-screen program renders at an offset. The
            // selection goes with it: its coordinates named text on the other screen, and left in
            // place it was drawn over vim and copied vim's cells.
            scrollOrigin = 0;
            selection = null;
            selectionAnchor = null;
            markDirty();
        });
    }

    /** Lines of history kept above the screen, unless the window sets another number. */
    static final int DEFAULT_SCROLLBACK = 5000;

    private int scrollbackLines = DEFAULT_SCROLLBACK;

    /**
     * History to keep. Fixed at session start: it sizes a buffer that then already exists, so a change takes
     * effect in the next session.
     */
    public void setScrollbackLines(int scrollbackLines) {
        this.scrollbackLines = Math.max(0, scrollbackLines);
    }

    /**
     * Opens the connector at the view's current size and starts the emulator. Must be called once, after the
     * view is in a scene.
     */
    public void start(TerminalConnector.Factory connectors) throws IOException {
        attach(connectors.open(columns, rows), columns, rows);
    }

    /**
     * As {@link #start}, opening the connector off the FX thread: a shell on another machine takes a channel open
     * (and, the first time, its shell-integration scripts) to start. Exactly one callback runs, on the FX thread:
     * {@code onStarted} once the session runs, or {@code onFailed} with why the connector did not open. A view
     * closed in the meantime runs neither, and closes the connector that arrives late.
     */
    public void startAsync(
            TerminalConnector.Factory connectors, Runnable onStarted, java.util.function.Consumer<Exception> onFailed) {
        int openColumns = columns;
        int openRows = rows;
        Thread.ofVirtual().name("terminal-connect").start(() -> {
            TerminalConnector opened;
            try {
                opened = connectors.open(openColumns, openRows);
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (!closed) onFailed.accept(e);
                });
                return;
            }
            Platform.runLater(() -> {
                if (closed) {
                    opened.close();
                    return;
                }
                attach(opened, openColumns, openRows);
                onStarted.run();
            });
        });
    }

    /** Whether {@link #close} ran. */
    private boolean closed;

    /** Runs a session on {@code opened}, which was opened at {@code openColumns} x {@code openRows}. */
    private void attach(TerminalConnector opened, int openColumns, int openRows) {
        TerminalConnector connector = new TappedConnector(opened, shellIntegration);
        session = new TerminalSession(display, connector, columns, rows, scrollbackLines);
        buffer = session.getTextBuffer();
        // Fires on the emulator thread for every buffer mutation — cheap by design.
        // A line scrolling into history fires only this one (the history listener is for resizes and
        // clears), so the history shift is checked here too.
        buffer.addModelListener(() -> {
            onHistoryLineCountChanged();
            if (search != SearchState.NONE) searchStale = true;
            if (selection != null) selectionToVerify = true;
            markDirty();
        });
        buffer.addHistoryBufferListener(this::onHistoryLineCountChanged);
        // Once history is full its count stops growing; the lines it drops say how far the text moved.
        buffer.addChangesListener(new com.jediterm.terminal.model.TextBufferChangesListener() {
            @Override
            public void linesDiscardedFromHistory(java.util.List<com.jediterm.terminal.model.TerminalLine> lines) {
                discardedHistoryLines += lines.size();
            }
        });
        session.setTypeAhead(typeAhead);
        session.setOnTypeAheadChanged(this::markDirty);
        session.setOnSessionEnded(() -> Platform.runLater(() -> {
            if (onSessionEnded != null) onSessionEnded.run();
        }));
        session.start();
        // Started here rather than inside the session: what the directory and the program are
        // *for* is a UI question. The term layer reports the shell's state; it does not decide what
        // a tab is called. Skipped outright when the shell is not on this machine, so no thread runs
        // to find out; the directory half also where the OS cannot answer (see CwdWatcher).
        if (session.pid() > 0) {
            cwdWatch = CwdWatcher.watch(
                    session.pid(),
                    cwd -> {
                        if (!shellReportsCwd) {
                            display.setCwd(cwd);
                        }
                    },
                    display::setProgram);
        }
        markDirty();
        if (columns != openColumns || rows != openRows) {
            session.resize(columns, rows); // laid out again while it was opening
        }
    }

    private @Nullable CwdWatcher cwdWatch;

    private @Nullable Runnable onSessionEnded;

    public void setOnSessionEnded(Runnable onSessionEnded) {
        this.onSessionEnded = onSessionEnded;
    }

    public FxTerminalDisplay getDisplay() {
        return display;
    }

    public @Nullable TerminalSession getSession() {
        return session;
    }

    public void close() {
        closed = true;
        painter.stop();
        painting.set(false);
        // Before the session: the watcher polls by pid, and a pid is reused. Left running past the
        // process it was watching, it would eventually start reporting some unrelated process's
        // directory into a tab that no longer exists.
        if (cwdWatch != null) cwdWatch.close();
        if (session != null) session.close();
    }

    private void markDirty() {
        dirty = true;
        if (session != null && !closed && painting.compareAndSet(false, true)) {
            if (Platform.isFxApplicationThread()) {
                painter.start();
            } else {
                Platform.runLater(() -> {
                    if (closed) painting.set(false);
                    else painter.start();
                });
            }
        }
    }

    /** Whether a bell is queued for the FX thread; the ones that ring meanwhile are the same bell. */
    private final java.util.concurrent.atomic.AtomicBoolean bellPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Clears the bell's wash; restarted by a bell that rings while it shows. */
    private final javafx.animation.PauseTransition bellClear =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));

    /** One coalesced bell: the tab's mark, and the flash if it is on. */
    private void onBell() {
        bellPending.set(false);
        if (!bellEnabled) return;
        if (onBellRung != null) onBellRung.run();
        flashBell();
    }

    /** Visual bell: a brief wash over the canvas, cleared by the next frame. */
    private void flashBell() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        // The text's own colour: a white wash vanished on a light theme.
        Color fg = palette.foreground();
        g.setFill(Color.color(fg.getRed(), fg.getGreen(), fg.getBlue(), 0.18));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        bellClear.playFromStart();
    }

    // ---------------------------------------------------------------- font & layout

    public void setFontSize(double size) {
        double next = Math.max(6, size);
        // Every settings change re-applies every setting to every terminal, so most calls here change nothing —
        // and an unchanged font rebuilt four faces, re-measured and re-laid out the view.
        if (next == fontSize && font != null) return;
        this.fontSize = next;
        applyFont();
    }

    /**
     * Sets the face. The caller passes a monospace family (the editor's font settings already limit it to
     * those); a blank one falls back to the bundled default rather than rendering the grid in a proportional
     * face, which would misalign every column.
     */
    public void setFontFamily(String family) {
        String next = family == null || family.isBlank() ? DEFAULT_FONT_FAMILY : family.strip();
        if (next.equals(fontFamily) && font != null) return; // see setFontSize
        this.fontFamily = next;
        applyFont();
    }

    public double getFontSize() {
        return fontSize;
    }

    private void applyFont() {
        String family = fontFamily;
        font = Font.font(family, fontSize);
        boldFont = Font.font(family, FontWeight.BOLD, fontSize);
        italicFont = Font.font(family, FontWeight.NORMAL, javafx.scene.text.FontPosture.ITALIC, fontSize);
        boldItalicFont = Font.font(family, FontWeight.BOLD, javafx.scene.text.FontPosture.ITALIC, fontSize);
        measureFont();
        applyIconFont(IconFont.familyFor(family).orElse(null));
        markDirty();
        requestLayout();
    }

    private void applyIconFont(@Nullable String icons) {
        if (icons == null) {
            iconFont = boldIconFont = italicIconFont = boldItalicIconFont = null;
            return;
        }
        iconFont = Font.font(icons, fontSize);
        boldIconFont = Font.font(icons, FontWeight.BOLD, fontSize);
        italicIconFont = Font.font(icons, FontWeight.NORMAL, javafx.scene.text.FontPosture.ITALIC, fontSize);
        boldItalicIconFont = Font.font(icons, FontWeight.BOLD, javafx.scene.text.FontPosture.ITALIC, fontSize);
    }

    /** Applies a theme's colours and repaints. */
    public void setPalette(TerminalPalette palette) {
        this.palette = palette;
        this.selectionWash = washFor(palette);
        display.setPalette(palette);
        styleScrollBar(palette);
        markDirty();
    }

    /**
     * Colours the scrollbar from the terminal palette rather than the chrome theme.
     *
     * <p>It sits inside the terminal, so a control-themed bar reads as a piece of the window that
     * wandered into the text. The colours are pushed as looked-up variables, because a thumb is a
     * child node an inline style on the bar cannot reach.
     */
    private void styleScrollBar(TerminalPalette palette) {
        Color fg = palette.foreground();
        scrollBar.setStyle("-terminal-thumb: " + web(fg, 0.35) + "; -terminal-thumb-hover: " + web(fg, 0.6) + ";");
    }

    private static String web(Color c, double alpha) {
        return "rgba(%d,%d,%d,%s)"
                .formatted(
                        Math.round(c.getRed() * 255),
                        Math.round(c.getGreen() * 255),
                        Math.round(c.getBlue() * 255),
                        alpha);
    }

    private static Color washFor(TerminalPalette palette) {
        Color blue = palette.ansi()[4];
        return Color.color(blue.getRed(), blue.getGreen(), blue.getBlue(), SELECTION_ALPHA);
    }

    public void setPreferredCursor(TerminalCursor shape) {
        this.preferredCursor = shape == null ? TerminalCursor.BLOCK : shape;
        markDirty();
    }

    public void setBellEnabled(boolean bellEnabled) {
        this.bellEnabled = bellEnabled;
    }

    private @Nullable Runnable onBellRung;

    /** Told (on the FX thread) when the bell rings, so a tab out of sight can say so. */
    public void setOnBell(Runnable onBellRung) {
        this.onBellRung = onBellRung;
    }

    private boolean typeAhead;

    /** Paints keystrokes before a slow shell echoes them (see {@link TypeAheadOverlay}); applies at once. */
    public void setTypeAhead(boolean typeAhead) {
        this.typeAhead = typeAhead;
        if (session != null) session.setTypeAhead(typeAhead);
    }

    /**
     * Shows or hides the scrollbar. This changes how many columns fit, so the shell is resized —
     * the bar takes real width rather than floating over the text, where it would cover the last
     * column of every line.
     */
    public void setScrollBarEnabled(boolean enabled) {
        if (scrollBarEnabled == enabled) return;
        scrollBarEnabled = enabled;
        requestLayout();
        markDirty();
    }

    private void onScrollBarMoved(double value) {
        if (updatingScrollBar || buffer == null) return;
        scrollOrigin = ScrollBarModel.origin(buffer.getHistoryLinesCount(), value);
        markDirty();
    }

    /** Reflects the buffer into the scrollbar. Cheap: property setters no-op on an equal value. */
    private void updateScrollBar() {
        if (buffer == null) return;
        int history = buffer.getHistoryLinesCount();
        // Nothing to scroll on the alternate screen — a full-screen program owns the viewport and
        // has no scrollback of its own.
        boolean useful = ScrollBarModel.useful(scrollBarEnabled, history, display.isAlternateScreen());
        // Only shown or hidden: its width is kept either way (see scrollBarWidth). Taking the width when the
        // first line reached history and giving it back on `clear` or a full-screen program resized the shell
        // and reflowed the scrollback each time.
        scrollBar.setVisible(useful);
        if (!useful) return;
        updatingScrollBar = true;
        try {
            scrollBar.setMin(0);
            scrollBar.setMax(history);
            scrollBar.setVisibleAmount(rows);
            scrollBar.setValue(ScrollBarModel.value(history, scrollOrigin));
        } finally {
            updatingScrollBar = false;
        }
    }

    /** Width kept for the scrollbar, shown or not, unless the bar is turned off. */
    private double scrollBarWidth() {
        return scrollBarEnabled ? scrollBar.prefWidth(-1) : 0;
    }

    private void measureFont() {
        Text probe = new Text("M");
        probe.setFont(font);
        // Cell width is the advance of a single glyph. Valid only because the face is monospace;
        // it is why a proportional fallback would misalign every column.
        charWidth = probe.getLayoutBounds().getWidth();
        ascent = probe.getBaselineOffset();
        // The taller of the leading we want and the height the face needs. A fixed ratio alone clipped faces
        // with deep descenders — JetBrains Mono wants about 1.32 em — and the row below, drawn after, painted its
        // background over the tails of every g and y above it.
        double measured = Math.ceil(probe.getLayoutBounds().getHeight());
        lineHeight = Math.max(Math.ceil(fontSize * LINE_SPACING), measured > 0 ? measured : 0);
        if (charWidth <= 0) charWidth = fontSize * 0.6;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        double barWidth = scrollBarWidth();
        canvas.setWidth(Math.max(0, w - barWidth));
        canvas.setHeight(h);
        canvas.relocate(0, 0);
        if (barWidth > 0) scrollBar.resizeRelocate(w - barWidth, 0, barWidth, h);

        int newColumns = Math.max(MIN_COLUMNS, (int) Math.floor((w - barWidth) / charWidth));
        int newRows = Math.max(MIN_ROWS, (int) Math.floor(h / lineHeight));
        if (session == null) {
            columns = newColumns;
            rows = newRows;
        } else if (newColumns == columns && newRows == rows) {
            resizeDebounce.stop(); // back to the size the shell has
        } else {
            // Every size change reflows the whole scrollback on the emulator's thread, under the
            // buffer lock: a splitter drag across a full history would queue dozens of them. The
            // size is applied once it has stopped changing; the old grid is painted meanwhile.
            pendingColumns = newColumns;
            pendingRows = newRows;
            resizeDebounce.playFromStart();
        }
        render();
    }

    /** How long a size must hold before the shell is resized to it (see {@link #layoutChildren}). */
    static final javafx.util.Duration RESIZE_SETTLE = javafx.util.Duration.millis(120);

    private int pendingColumns;
    private int pendingRows;
    private final javafx.animation.PauseTransition resizeDebounce = new javafx.animation.PauseTransition(RESIZE_SETTLE);

    {
        resizeDebounce.setOnFinished(e -> applyPendingSize());
    }

    private void applyPendingSize() {
        if (closed || (pendingColumns == columns && pendingRows == rows) || pendingColumns <= 0) return;
        columns = pendingColumns;
        rows = pendingRows;
        if (session != null) session.resize(columns, rows);
        markDirty();
    }

    /** Applies a size still waiting out {@link #RESIZE_SETTLE}; for tests. */
    void flushResize() {
        resizeDebounce.stop();
        applyPendingSize();
    }

    @Override
    protected double computePrefWidth(double height) {
        return charWidth * 80;
    }

    @Override
    protected double computePrefHeight(double width) {
        return lineHeight * 24;
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        // The emulator writes into this buffer from its own thread; the lock is what makes a frame
        // internally consistent rather than a mix of two states. Only tried: a flood of output or a
        // reflow of a long scrollback holds it for a long time, and the FX thread waiting for it
        // would freeze every window. The frame is skipped instead, and the next pulse paints.
        if (buffer != null && !buffer.tryLock()) {
            dirty = true;
            return;
        }
        g.setFontSmoothingType(FontSmoothingType.GRAY);
        g.setFill(palette.background());
        g.fillRect(0, 0, w, h);
        if (buffer == null) return;
        try {
            // x arrives as an index into the line's char[], which is not its column: see
            // RunColumns. Translating here rather than inside drawRun keeps every consumer
            // callback on the same footing, including the background-only one.
            RunColumns columns = new RunColumns();
            buffer.processHistoryAndScreenLines(scrollOrigin, rows, new StyledTextConsumer() {
                @Override
                public void consume(int x, int y, TextStyle style, CharBuffer characters, int startRow) {
                    int column = columns.columnOf(y, x, characters);
                    drawRun(g, column, columns.lastCells(), y - startRow, style, characters);
                }

                @Override
                public void consumeNul(
                        int x, int y, int nulIndex, TextStyle style, CharBuffer characters, int startRow) {
                    // Cells past the last written column. Only the background is meaningful —
                    // drawing NUL as text would paint boxes across every short line.
                    drawBackground(g, columns.columnOf(y, x, characters), y - startRow, characters.length(), style);
                }

                @Override
                public void consumeQueue(int x, int y, int nulIndex, int startRow) {}
            });
            if (selectionToVerify && !dragging) {
                selectionToVerify = false;
                dropSelectionIfRewritten();
            }
            drawSearch(g);
            drawSelection(g);
            drawLinkUnderline(g);
            drawPredictions(g);
            drawCursor(g);
            updateScrollBar();
        } finally {
            buffer.unlock();
        }
    }

    private void drawBackground(GraphicsContext g, int column, int row, int length, TextStyle style) {
        Color bg = backgroundOf(style);
        if (bg.equals(palette.background())) return; // already cleared
        g.setFill(bg);
        fillCells(g, column, column + length, row * lineHeight, lineHeight);
    }

    /**
     * Fills columns {@code from} to {@code to} (end exclusive) of a row, edges on whole pixels. A cell is a
     * fractional number of pixels wide (8.43 at 14 px), so two runs that met at 25.29 px were each drawn
     * anti-aliased there, and a seam showed between backgrounds of the same colour; on whole pixels they abut.
     */
    private void fillCells(GraphicsContext g, double from, double to, double y, double height) {
        double left = cellEdge(from, charWidth);
        double right = cellEdge(to, charWidth);
        if (right > left) g.fillRect(left, y, right - left, height);
    }

    /** The whole-pixel x where column {@code column} starts (the same for a run ending there and one starting). */
    static double cellEdge(double column, double charWidth) {
        return Math.round(column * charWidth);
    }

    private void drawRun(GraphicsContext g, int column, int cells, int row, TextStyle style, CharBuffer characters) {
        if (row < 0 || row >= rows) return;
        String text = characters.toString();
        if (text.isEmpty()) return;

        Color fg = foregroundOf(style);
        Color bg = backgroundOf(style);

        double x = column * charWidth;
        double y = row * lineHeight;
        // Cells, not characters: an astral code point is two array slots and one column, so a run
        // holding one would otherwise be painted a column too wide.
        double width = cells * charWidth;

        if (!bg.equals(palette.background())) {
            g.setFill(bg);
            fillCells(g, column, column + cells, y, lineHeight);
        }

        if (style.hasOption(TextStyle.Option.HIDDEN)) return;

        g.setFont(fontFor(style));
        g.setFill(fg);
        g.setTextBaseline(VPos.BASELINE);
        drawGlyphs(g, text, x, y + ascent, iconFontFor(style));

        if (style.hasOption(TextStyle.Option.UNDERLINED)) {
            double underlineY = Math.floor(y + ascent + Math.max(1, fontSize * 0.12)) + 0.5;
            g.setStroke(fg);
            g.setLineWidth(1);
            g.strokeLine(x, underlineY, x + width, underlineY);
        }
    }

    /**
     * Draws a style run onto the cell grid.
     *
     * <p>Array slots are not columns, in either direction: a double-width character
     * (CJK, most emoji) therefore occupies two: the glyph itself, followed by {@link CharUtils#DWC}
     * — a private-use placeholder that must never be drawn. Rendering it produces a stray box or
     * bar between every pair of CJK characters, and drawing the run with one {@code fillText} also
     * lets the wide glyph's natural advance push the rest of the line off the grid.
     *
     * <p>The one-cell-per-char fast path is the one that matters for throughput: it is what almost
     * every run is — including every box-drawing border and braille bar a TUI draws, which an
     * ASCII-only test sent down the slow path — and it stays a single draw call. It trusts the face,
     * or its fallback, to give those glyphs a one-cell advance.
     *
     * <p>A run with a private-use code point in it is drawn per glyph when {@code iconFace} is set,
     * so the icon is drawn in that face and the text around it in the run's own.
     *
     * @param iconFace the face for private-use code points, or null to leave them to the run's
     */
    private void drawGlyphs(GraphicsContext g, String text, double x, double baseline, @Nullable Font iconFace) {
        if (CellWidth.isOneCellPerChar(text) && (iconFace == null || !IconFont.containsPrivateUse(text))) {
            g.fillText(text, x, baseline);
            return;
        }
        Font face = g.getFont();
        int cell = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == CharUtils.DWC) {
                // Continuation of the preceding wide glyph: consumes a cell, draws nothing.
                cell++;
                i++;
                continue;
            }
            int codePoint = text.codePointAt(i);
            int slots = Character.charCount(codePoint);
            if (codePoint != 0 && c != ' ') {
                drawCodePoint(g, codePoint, x + cell * charWidth, baseline, face, iconFace);
            }
            // Columns per code point, not per UTF-16 slot. Advancing by slots gave every narrow
            // astral character — every Nerd Font icon in an `ls` listing — a second, empty column,
            // pushing the rest of the line out of alignment with the lines around it. A wide BMP
            // glyph gets its second column from the DWC placeholder above; a wide astral one (an
            // emoji) has none, and takes its two columns here (see CellWidth).
            cell += CellWidth.of(codePoint);
            i += slots;
        }
    }

    /**
     * Draws one code point in {@code face}, which the context is already set to — or, for a private-use one when
     * {@code iconFace} is set, in the icon face, leaving {@code face} set after.
     */
    private static void drawCodePoint(
            GraphicsContext g, int codePoint, double x, double baseline, Font face, @Nullable Font iconFace) {
        String glyph = new String(Character.toChars(codePoint));
        if (iconFace == null || !IconFont.isPrivateUse(codePoint)) {
            g.fillText(glyph, x, baseline);
            return;
        }
        g.setFont(iconFace);
        g.fillText(glyph, x, baseline);
        g.setFont(face);
    }

    /** The face for private-use code points in this style, or null to draw them in {@link #fontFor}. */
    private @Nullable Font iconFontFor(TextStyle style) {
        if (iconFont == null) return null;
        boolean bold = style.hasOption(TextStyle.Option.BOLD);
        boolean italic = style.hasOption(TextStyle.Option.ITALIC);
        if (bold && italic) return boldItalicIconFont;
        if (bold) return boldIconFont;
        if (italic) return italicIconFont;
        return iconFont;
    }

    private Font fontFor(TextStyle style) {
        boolean bold = style.hasOption(TextStyle.Option.BOLD);
        boolean italic = style.hasOption(TextStyle.Option.ITALIC);
        if (bold && italic) return boldItalicFont;
        if (bold) return boldFont;
        if (italic) return italicFont;
        return font;
    }

    private Color foregroundOf(TextStyle style) {
        boolean inverse = style.hasOption(TextStyle.Option.INVERSE);
        Color fg = palette.resolve(
                inverse ? style.getBackground() : style.getForeground(),
                inverse ? palette.background() : palette.foreground());
        Color bg = backgroundOf(style);
        // Dim text is meant to recede, so it is left as it asked.
        if (style.hasOption(TextStyle.Option.DIM)) return TerminalPalette.dim(fg, bg);
        return readable(fg, bg);
    }

    /** Text colours already raised to {@link TerminalPalette#MIN_CONTRAST}, by foreground and background. */
    private final java.util.Map<Long, Color> readable = new java.util.HashMap<>();

    /** {@code fg} with at least the minimum contrast against {@code bg}; cached, it is asked per style run. */
    private Color readable(Color fg, Color bg) {
        long key = ((long) argb(fg) << 32) | (argb(bg) & 0xffffffffL);
        Color known = readable.get(key);
        if (known == null) {
            if (readable.size() > 4096) readable.clear(); // a truecolour flood: plenty of pairs, each cheap
            known = TerminalPalette.withContrast(fg, bg, TerminalPalette.MIN_CONTRAST);
            readable.put(key, known);
        }
        return known;
    }

    private static int argb(Color c) {
        return ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8)
                | (int) Math.round(c.getBlue() * 255);
    }

    private Color backgroundOf(TextStyle style) {
        boolean inverse = style.hasOption(TextStyle.Option.INVERSE);
        return palette.resolve(
                inverse ? style.getForeground() : style.getBackground(),
                inverse ? palette.foreground() : palette.background());
    }

    /**
     * Paints type-ahead predictions over the cursor's row: from the first cell where they differ from what the
     * shell has echoed, in the palette's dim grey (JediTerm's colour for them). Caller holds the lock.
     */
    private void drawPredictions(GraphicsContext g) {
        TypeAheadOverlay.Prediction p = session == null ? null : session.typeAheadPrediction();
        if (p == null) return;
        int row = p.row() - scrollOrigin;
        if (row < 0 || row >= rows) return;
        String actual = lineTextAt(p.row());
        String predicted = p.text();
        int from = 0;
        while (from < actual.length() && from < predicted.length() && actual.charAt(from) == predicted.charAt(from)) {
            from++;
        }
        int end = Math.max(actual.length(), predicted.length());
        if (from >= end) return;
        double y = row * lineHeight;
        double x = ColumnMap.columnAt(actual, from) * charWidth;
        g.setFill(palette.background());
        g.fillRect(x, y, Math.max(0, columns * charWidth - x), lineHeight);
        if (from < predicted.length()) {
            g.setFont(font);
            g.setFill(palette.ansi()[8]);
            g.setTextBaseline(VPos.BASELINE);
            drawGlyphs(g, predicted.substring(from), x, y + ascent, iconFont);
        }
    }

    private void drawCursor(GraphicsContext g) {
        if (!display.isCursorVisible()) return;
        // JediTerm reports the cursor row 1-based against the live screen; scrolling back moves it
        // out of view rather than dragging it along.
        int row = display.getCursorY() - 1 - scrollOrigin;
        TerminalSession live = session;
        TypeAheadOverlay.Prediction p = live == null ? null : live.typeAheadPrediction();
        // The emulator's cursor is a slot of its row's text, which is a column only while nothing
        // to its left is astral: an emoji is two slots and two columns, a Nerd Font icon two slots
        // and one. Mapped through the row's text, as every other overlay is.
        String text = p == null ? lineTextAt(display.getCursorY() - 1) : p.text();
        int slot = p == null || live == null ? display.getCursorX() : live.typeAheadCursorColumn();
        // After a line is written to exactly its full width the cursor is *at* the width, pending the wrap that the
        // next character will cause. xterm draws it on the last column; bailing on it made the cursor vanish on
        // every exactly-full line.
        int column = Math.min(ColumnMap.columnAt(text, slot), columns - 1);
        if (row < 0 || row >= rows || column < 0) return;

        double x = column * charWidth;
        double y = row * lineHeight;
        g.setFill(palette.foreground());

        if (!isFocused()) {
            g.setStroke(palette.foreground());
            g.setLineWidth(1);
            g.strokeRect(x + 0.5, y + 0.5, charWidth - 1, lineHeight - 1);
            return;
        }
        switch (effectiveCursorShape()) {
            case UNDERLINE -> g.fillRect(x, y + lineHeight - 2, charWidth, 2);
            case BAR -> g.fillRect(x, y, 2, lineHeight);
            default -> {
                // The cell the cursor sits on: two wide for a wide glyph.
                int codePoint = glyphAt(text, slot);
                int width = codePoint > 0 && slot + 1 < text.length() && text.charAt(slot + 1) == CharUtils.DWC
                        ? 2
                        : codePoint > 0 ? CellWidth.of(codePoint) : 1;
                g.fillRect(x, y, width * charWidth, lineHeight);
                // Re-draw the glyph underneath in the cell's background colour so a block cursor does not hide the
                // character it is sitting on — in the cell's own font, or a bold character under the cursor came
                // back regular for the duration. A prediction has no styles of its own yet.
                if (codePoint > 0 && codePoint != ' ') {
                    TextStyle style = p == null ? styleAt(slot, display.getCursorY() - 1) : null;
                    g.setFill(style == null ? palette.background() : backgroundOf(style));
                    Font face = style == null ? font : fontFor(style);
                    g.setFont(face);
                    g.setTextBaseline(VPos.BASELINE);
                    drawCodePoint(g, codePoint, x, y + ascent, face, style == null ? iconFont : iconFontFor(style));
                }
            }
        }
    }

    /** The style of a cell, or null when the row is not there. Caller holds the lock. */
    private @Nullable TextStyle styleAt(int slot, int row) {
        TerminalTextBuffer buf = buffer;
        if (buf == null) return null;
        try {
            var line = buf.getLine(row);
            return line == null || slot < 0 || slot >= line.length() ? null : line.getStyleAt(slot);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The code point that starts at {@code slot} of a row's text, or 0 for none (past the end, a placeholder). */
    static int glyphAt(String text, int slot) {
        if (slot < 0 || slot >= text.length()) return 0;
        char c = text.charAt(slot);
        if (c == '\0' || c == CharUtils.DWC || Character.isLowSurrogate(c)) return 0;
        return text.codePointAt(slot);
    }

    /**
     * The shape to draw: whatever a program asked for, else the user's preference.
     *
     * <p>A program's request wins because it is usually communicating mode — vim's insert-mode bar
     * against its normal-mode block — which the user's default should not override.
     */
    private TerminalCursor effectiveCursorShape() {
        CursorShape requested = display.getCursorShape();
        if (requested == null) return preferredCursor;
        return switch (requested) {
            case BLINK_UNDERLINE, STEADY_UNDERLINE -> TerminalCursor.UNDERLINE;
            case BLINK_VERTICAL_BAR, STEADY_VERTICAL_BAR -> TerminalCursor.BAR;
            default -> TerminalCursor.BLOCK;
        };
    }

    // ---------------------------------------------------------------- input

    private void onKeyPressed(KeyEvent e) {
        // Escape closes the context menu rather than reaching the shell. This filter runs before
        // the menu would see the key, and Escape now encodes to a real byte, so without this the
        // menu stays open and the shell gets an ESC nobody asked for.
        ContextMenu shown = contextMenu == null ? null : contextMenu.menu();
        if (shown != null && shown.isShowing()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                shown.hide();
                e.consume();
                return;
            }
        }
        if (session == null || !session.isRunning()) return;
        if (scrollbackKey(e) || clipboardKey(e)) {
            e.consume();
            return;
        }

        if (e.getCode() == KeyCode.ALT_GRAPH) {
            altGrHeld = true;
        } else if (!e.isAltDown()) {
            altGrHeld = false; // a release missed while focus was elsewhere must not outlive the next key
        }
        // A program that asked for the kitty keyboard protocol gets it; everything else, and every
        // key the protocol leaves alone — plain typing, Enter at a shell — takes the legacy route.
        int kittyFlags = session.keyboardFlags(display.isAlternateScreen());
        if (kittyFlags != 0) {
            byte[] kitty = kittyInput.pressed(e, kittyFlags, altIsMeta, ctrlAltIsMeta());
            if (kitty != null) {
                if (KeyTrace.enabled()) KeyTrace.log(e, kitty);
                if (kitty.length > 0) {
                    snapToLive();
                    session.send(kitty);
                }
                e.consume();
                return;
            }
        }
        // The selection is not cleared: it survives a keystroke, and goes only if the text under it
        // changes. See settledText.
        byte[] encoded = KeyEncoding.encodePressed(e, session::keyCode, altIsMeta, ctrlAltIsMeta());
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
            session.send(encoded);
            e.consume();
        }
    }

    /**
     * Shift+PageUp/PageDown/Home/End scroll the scrollback, as in xterm, GNOME Terminal and Konsole: the keyboard's
     * only way into history without shell integration's prompt marks. Not on the alternate screen, where a
     * full-screen program owns those keys (and there is no scrollback).
     */
    private boolean scrollbackKey(KeyEvent e) {
        boolean modifier = e.isShiftDown() && !e.isControlDown() && !e.isAltDown() && (!e.isMetaDown() || MAC);
        if (!modifier || buffer == null || display.isAlternateScreen()) return false;
        int history = buffer.getHistoryLinesCount();
        int next = switch (e.getCode()) {
            case PAGE_UP -> scrollOrigin - Math.max(1, rows - 1);
            case PAGE_DOWN -> scrollOrigin + Math.max(1, rows - 1);
            case HOME -> -history;
            case END -> 0;
            default -> Integer.MIN_VALUE;
        };
        if (next == Integer.MIN_VALUE) return false;
        next = Math.max(-history, Math.min(0, next));
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
        return true;
    }

    /**
     * Shift+Insert pastes and Ctrl+Insert copies, the IBM CUA chords every X terminal and Windows console honours —
     * ahead of the encoder, which would otherwise send the program {@code CSI 2;2~}. Only with exactly that modifier,
     * so Insert alone, and with anything else held, still reaches the program.
     */
    private boolean clipboardKey(KeyEvent e) {
        if (e.getCode() != KeyCode.INSERT || e.isAltDown() || e.isMetaDown()) return false;
        if (e.isShiftDown() && !e.isControlDown()) {
            paste();
            return true;
        }
        if (e.isControlDown() && !e.isShiftDown()) {
            copySelection();
            return true;
        }
        return false;
    }

    /** The screen as a screen reader reads it, and where the cursor is in it. */
    @Override
    public Object queryAccessibleAttribute(javafx.scene.AccessibleAttribute attribute, Object... parameters) {
        return switch (attribute) {
            case TEXT -> accessibleText();
            case CARET_OFFSET -> accessibleCaret();
            case EDITABLE -> false;
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }

    /** The rows in view, one per line; empty while the emulator holds the buffer (asked again later). */
    private String accessibleText() {
        if (buffer == null || !buffer.tryLock()) return "";
        try {
            StringBuilder text = new StringBuilder();
            for (int row = 0; row < rows; row++) {
                text.append(accessibleRow(scrollOrigin + row)).append('\n');
            }
            return text.toString();
        } finally {
            buffer.unlock();
        }
    }

    private String accessibleRow(int row) {
        return lineTextAt(row).replace(String.valueOf(CharUtils.DWC), "").stripTrailing();
    }

    private int accessibleCaret() {
        int cursorRow = display.getCursorY() - 1 - scrollOrigin;
        if (buffer == null || cursorRow < 0 || cursorRow >= rows || !buffer.tryLock()) return 0;
        try {
            int offset = 0;
            for (int row = 0; row < cursorRow; row++) {
                offset += accessibleRow(scrollOrigin + row).length() + 1;
            }
            return offset + Math.max(0, display.getCursorX());
        } finally {
            buffer.unlock();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (session == null || !session.isRunning()) return;
        int kittyFlags = session.keyboardFlags(display.isAlternateScreen());
        if (kittyFlags != 0) {
            byte[] kitty = kittyInput.typed(e, kittyFlags);
            if (kitty != null) {
                if (kitty.length > 0) {
                    snapToLive();
                    session.send(kitty);
                }
                e.consume();
                return;
            }
        }
        byte[] encoded = KeyEncoding.encodeTyped(e, altIsMeta, ctrlAltIsMeta());
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
            session.send(encoded);
            e.consume();
        }
    }

    // ---------------------------------------------------------------- shell integration

    /**
     * Maps a path as the shell names it (its working directory, a path in its output) to one Editora can open: the
     * same path on this machine for a local shell, the host's file for an SSH shell, the mounted folder for a
     * container. Null means this machine.
     */
    private java.util.function.@Nullable Function<String, java.nio.file.Path> shellPaths;

    public void setShellPaths(java.util.function.Function<String, java.nio.file.Path> shellPaths) {
        this.shellPaths = shellPaths;
    }

    /** Set once the shell reports its own directory (OSC 7); from then on that, not the pid's, is the cwd. */
    private volatile boolean shellReportsCwd;

    /** A prompt mark (OSC 133) at a buffer row: 0 is the top of the live screen, negative is history. */
    record PromptMark(char kind, int row, String params) {}

    /**
     * A prompt mark as kept: at an absolute line ({@link #pushedLines} plus the row when it was made), so lines aging
     * into history move nothing — the row is worked out when it is read.
     */
    private record Mark(char kind, long line, String params) {}

    private final java.util.List<Mark> marks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Lines ever pushed into history. A row plus this is an absolute line that stays put as output scrolls, which is
     * how prompt marks and search hits keep to their text without being rewritten for every line of output. Written
     * on the emulator thread under the buffer lock (see {@link #onHistoryLineCountChanged}).
     */
    private volatile long pushedLines;

    /** The buffer row of absolute {@code line} now (0 is the top of the live screen, negative is history). */
    private int rowOf(long line) {
        return (int) Math.max(Integer.MIN_VALUE + 1, Math.min(Integer.MAX_VALUE, line - pushedLines));
    }

    /** The marks still in the buffer, as rows. */
    private java.util.List<PromptMark> markRows() {
        int floor = buffer == null ? 0 : -buffer.getHistoryLinesCount();
        java.util.List<PromptMark> rows = new java.util.ArrayList<>(marks.size());
        for (Mark m : marks) {
            int row = rowOf(m.line());
            if (row >= floor) rows.add(new PromptMark(m.kind(), row, m.params()));
        }
        return rows;
    }

    /** Marks kept, oldest first; an older one is dropped past this many. */
    private static final int MAX_MARKS = 2000;

    /** Called on the emulator thread, in stream order: the cursor is where the sequence was. */
    private final OscTap.Listener shellIntegration = new OscTap.Listener() {
        @Override
        public void cwd(String url) {
            ShellIntegration.CwdReport report = ShellIntegration.parseCwdUrl(url);
            // A local tab takes a report only from this machine (see ShellIntegration.isThisMachine); a remote
            // tab's shell is on its host, and its paths are mapped.
            if (report != null && shellPaths == null && !ShellIntegration.isThisMachine(report.host())) {
                return;
            }
            String path = report == null ? null : report.path();
            if (path != null) {
                shellReportsCwd = true;
                display.setCwd(path);
            }
        }

        @Override
        public void promptMark(char kind, String params) {
            marks.add(new Mark(kind, display.getCursorY() - 1 + pushedLines, params));
            while (marks.size() > MAX_MARKS) {
                marks.removeFirst();
            }
        }

        @Override
        public void notification(OscSequences.Notification notification) {
            onNotification(notification);
        }

        @Override
        public void clipboard(String text) {
            if (clipboardWrites) Platform.runLater(() -> toClipboard(text));
        }
    };

    /** Whether a program may set the clipboard with OSC 52 (Settings → Terminal). It can never read it. */
    private volatile boolean clipboardWrites = true;

    public void setClipboardWrites(boolean allowed) {
        this.clipboardWrites = allowed;
    }

    /** Told, on the FX thread, of a program's notification (OSC 9, OSC 777). */
    @FunctionalInterface
    public interface NotificationListener {
        /**
         * @param title the program's title, or empty for none
         * @param post whether this one may reach the desktop: one per terminal every {@link #NOTIFY_INTERVAL_MS};
         *     the rest only mark the tab
         */
        void notified(String title, String body, boolean post);
    }

    private NotificationListener onNotify = (title, body, post) -> {};

    public void setOnNotify(NotificationListener onNotify) {
        this.onNotify = onNotify == null ? (title, body, post) -> {} : onNotify;
    }

    /** A program cannot post more than one notification per terminal in this long; the rest only mark its tab. */
    static final long NOTIFY_INTERVAL_MS = 2_000;

    /** When this terminal last posted one. Written on the FX thread only. */
    private long lastNotifiedMs = Long.MIN_VALUE / 2;

    /**
     * A program asked for a notification. The window decides whether it reaches the desktop — not while you are
     * looking at the terminal — but the view decides how often: a loop printing OSC 9 would otherwise fill the screen
     * with bubbles. Every one still marks the tab.
     */
    private void onNotification(OscSequences.Notification notification) {
        Platform.runLater(() -> {
            if (closed) return;
            long now = System.currentTimeMillis();
            boolean post = now - lastNotifiedMs >= NOTIFY_INTERVAL_MS;
            if (post) lastNotifiedMs = now;
            onNotify.notified(notification.title(), notification.body(), post);
        });
    }

    /** A cell's width in pixels, for tests. */
    double cellWidth() {
        return charWidth;
    }

    /** A row's height in pixels, for tests. */
    double lineHeightForTest() {
        return lineHeight;
    }

    /** Rows scrolled back from the live screen (0 at it, negative into history), for tests. */
    int scrollOrigin() {
        return scrollOrigin;
    }

    /** Whether the shell sends prompt marks (shell integration is running in it). */
    public boolean hasPromptMarks() {
        return !marks.isEmpty();
    }

    /** The prompt marks, for tests and the panel. */
    java.util.List<PromptMark> promptMarks() {
        return markRows();
    }

    /** Scrolls to the prompt above the top of the view; false when there is none. */
    public boolean previousPrompt() {
        int top = scrollOrigin;
        int target = Integer.MIN_VALUE;
        for (PromptMark m : markRows()) {
            if (m.kind() == 'A' && m.row() < top && m.row() > target) {
                target = m.row();
            }
        }
        if (target == Integer.MIN_VALUE || buffer == null) {
            return false;
        }
        scrollOrigin = Math.max(-buffer.getHistoryLinesCount(), Math.min(0, target));
        markDirty();
        return true;
    }

    /** Scrolls to the prompt below the top of the view, or back to the live screen; false when already there. */
    public boolean nextPrompt() {
        int top = scrollOrigin;
        if (top >= 0) {
            return false;
        }
        int target = 0;
        for (PromptMark m : markRows()) {
            if (m.kind() == 'A' && m.row() > top && m.row() < target) {
                target = m.row();
            }
        }
        scrollOrigin = target;
        markDirty();
        return true;
    }

    /**
     * What the last command printed: the rows from its output mark (C) up to the mark that followed it (D, or the
     * next prompt), or up to the cursor while it is still running. Null when the shell sent no such mark.
     */
    public @Nullable String lastCommandOutput() {
        if (buffer == null) {
            return null;
        }
        java.util.List<PromptMark> marks = markRows();
        PromptMark start = null;
        int startIndex = -1;
        for (int i = marks.size() - 1; i >= 0; i--) {
            if (marks.get(i).kind() == 'C') {
                start = marks.get(i);
                startIndex = i;
                break;
            }
        }
        if (start == null) {
            return null;
        }
        int end = display.getCursorY() - 1;
        for (int i = startIndex + 1; i < marks.size(); i++) {
            char k = marks.get(i).kind();
            if (k == 'D' || k == 'A') {
                end = marks.get(i).row();
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        buffer.lock();
        try {
            for (int row = start.row(); row < end; row++) {
                String line = lineTextAt(row).replace(CharUtils.DWC + "", "").stripTrailing();
                out.append(line).append('\n');
            }
        } finally {
            buffer.unlock();
        }
        String text = out.toString().stripTrailing();
        return text.isEmpty() ? null : text + "\n";
    }

    /** Copies {@link #lastCommandOutput()}; false when there was none. */
    public boolean copyLastOutput() {
        String text = lastCommandOutput();
        if (text == null) {
            return false;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    // ---------------------------------------------------------------- search

    /** What a search found: the current hit (1-based; 0 for none) and how many there are. */
    public record SearchResult(int index, int count) {

        static final SearchResult NONE = new SearchResult(0, 0);
    }

    /** A hit as kept: at an absolute line (see {@link #pushedLines}), so output scrolling past moves nothing. */
    private record Hit(long line, int from, int to) {}

    /** The search being shown: its hits, oldest first, and which one is current (-1 for none). */
    private record SearchState(String query, boolean matchCase, java.util.List<Hit> hits, int current) {

        static final SearchState NONE = new SearchState("", false, java.util.List.of(), -1);

        SearchResult result() {
            return new SearchResult(current < 0 ? 0 : current + 1, hits.size());
        }
    }

    private volatile SearchState search = SearchState.NONE;

    /** Set when the buffer changed under a search, so the next step finds again before moving. */
    private volatile boolean searchStale;

    /** Bumped by every search started or cleared; a scan that sees it change gives up. */
    private final java.util.concurrent.atomic.AtomicLong searchGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /** Rows scanned per hold of the buffer lock, so a scan of a long scrollback never stalls the emulator. */
    private static final int SEARCH_CHUNK = 2000;

    /**
     * Highlights every occurrence of {@code query} in the scrollback and screen (the newest {@link
     * TerminalSearch#MAX_MATCHES}) and scrolls to the most recent one at or above the bottom of the view. An empty
     * query clears the search. Scans on the calling thread; the find bar uses {@link #searchAsync}.
     */
    public SearchResult search(String query, boolean matchCase) {
        if (buffer == null || query == null || query.isEmpty()) {
            clearSearch();
            return SearchResult.NONE;
        }
        long generation = searchGeneration.incrementAndGet();
        java.util.List<Hit> hits = scan(query, matchCase, generation);
        return hits == null ? SearchResult.NONE : show(query, matchCase, hits);
    }

    /**
     * {@link #search}, scanning off the FX thread: {@code done} gets the result on the FX thread, unless another
     * search started or the search was cleared meanwhile.
     */
    public void searchAsync(String query, boolean matchCase, java.util.function.Consumer<SearchResult> done) {
        if (buffer == null || query == null || query.isEmpty()) {
            clearSearch();
            done.accept(SearchResult.NONE);
            return;
        }
        long generation = searchGeneration.incrementAndGet();
        scanAsync(query, matchCase, generation, hits -> done.accept(show(query, matchCase, hits)));
    }

    /**
     * Moves to the next hit up ({@code older}) or down, wrapping at either end. Finds again first when output
     * arrived since, keeping to the hit nearest the one that was current.
     */
    public SearchResult findNext(boolean older) {
        SearchState state = search;
        if (state.query().isEmpty()) {
            return SearchResult.NONE;
        }
        if (searchStale) {
            long generation = searchGeneration.incrementAndGet();
            java.util.List<Hit> hits = scan(state.query(), state.matchCase(), generation);
            if (hits == null) {
                return SearchResult.NONE; // the view closed while it scanned
            }
            state = rescanned(state, hits);
        }
        return step(state, older);
    }

    /** {@link #findNext}, finding again off the FX thread when output arrived since; {@code done} runs on FX. */
    public void findNextAsync(boolean older, java.util.function.Consumer<SearchResult> done) {
        SearchState state = search;
        if (state.query().isEmpty()) {
            done.accept(SearchResult.NONE);
            return;
        }
        if (!searchStale) {
            done.accept(step(state, older));
            return;
        }
        long generation = searchGeneration.incrementAndGet();
        scanAsync(
                state.query(), state.matchCase(), generation, hits -> done.accept(step(rescanned(state, hits), older)));
    }

    /** {@code state}'s query with {@code hits} found again, current on the one nearest the old current hit. */
    private SearchState rescanned(SearchState state, java.util.List<Hit> hits) {
        int row = state.current() >= 0 ? rowOf(state.hits().get(state.current()).line()) : scrollOrigin + rows - 1;
        searchStale = false;
        return new SearchState(state.query(), state.matchCase(), hits, startIndex(hits, row));
    }

    private SearchResult step(SearchState state, boolean older) {
        int count = state.hits().size();
        if (count == 0) {
            search = state;
            markDirty();
            return state.result();
        }
        int current = state.current() < 0 ? count - 1 : Math.floorMod(state.current() + (older ? -1 : 1), count);
        SearchState next = new SearchState(state.query(), state.matchCase(), state.hits(), current);
        search = next;
        reveal(next);
        markDirty();
        return next.result();
    }

    /** Shows {@code hits} for {@code query}, current on the latest at or above the bottom of the view. */
    private SearchResult show(String query, boolean matchCase, java.util.List<Hit> hits) {
        SearchState next = new SearchState(
                query, matchCase, java.util.List.copyOf(hits), startIndex(hits, scrollOrigin + rows - 1));
        search = next;
        searchStale = false;
        reveal(next);
        markDirty();
        return next.result();
    }

    private int startIndex(java.util.List<Hit> hits, int row) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (rowOf(hits.get(i).line()) <= row) {
                return i;
            }
        }
        return hits.isEmpty() ? -1 : hits.size() - 1;
    }

    /** Removes the highlights; the view stays where it is. A scan in progress is abandoned. */
    public void clearSearch() {
        searchGeneration.incrementAndGet();
        search = SearchState.NONE;
        searchStale = false;
        markDirty();
    }

    /** The hits being highlighted, as rows now; for tests. */
    public java.util.List<TerminalSearch.Match> searchMatches() {
        return search.hits().stream()
                .map(h -> new TerminalSearch.Match(rowOf(h.line()), h.from(), h.to()))
                .toList();
    }

    /** One scanner for every view: a scan is short, and only the latest one of a view matters. */
    private static final java.util.concurrent.ExecutorService SEARCHER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "editora-terminal-search");
                t.setDaemon(true);
                return t;
            });

    private void scanAsync(
            String query, boolean matchCase, long generation, java.util.function.Consumer<java.util.List<Hit>> onFx) {
        SEARCHER.execute(() -> {
            java.util.List<Hit> hits = scan(query, matchCase, generation);
            if (hits == null) return;
            Platform.runLater(() -> {
                if (!closed && searchGeneration.get() == generation) onFx.accept(hits);
            });
        });
    }

    /**
     * The newest hits of {@code query}, oldest first, scanned from the bottom up a chunk of rows per hold of the
     * lock. Output may arrive between chunks: rows are turned into absolute lines under the lock, so the hits stay
     * put. Null when {@code generation} was overtaken.
     */
    private java.util.@Nullable List<Hit> scan(String query, boolean matchCase, long generation) {
        TerminalTextBuffer buf = buffer;
        if (buf == null) {
            return java.util.List.of(); // no session yet: nothing to find
        }
        java.util.List<Hit> newestFirst = new java.util.ArrayList<>();
        long next; // the absolute line to scan next, going up
        buf.lock();
        try {
            next = buf.getHeight() - 1 + pushedLines;
        } finally {
            buf.unlock();
        }
        while (newestFirst.size() < TerminalSearch.MAX_MATCHES) {
            if (searchGeneration.get() != generation || closed) return null;
            buf.lock();
            try {
                long pushed = pushedLines;
                int top = -buf.getHistoryLinesCount();
                int row = (int) Math.min(buf.getHeight() - 1, next - pushed);
                if (row < top) break;
                int last = Math.max(top, row - SEARCH_CHUNK + 1);
                for (; row >= last && newestFirst.size() < TerminalSearch.MAX_MATCHES; row--) {
                    java.util.List<TerminalSearch.Match> inRow =
                            TerminalSearch.inRow(row, lineTextAt(row), query, matchCase);
                    for (int i = inRow.size() - 1; i >= 0 && newestFirst.size() < TerminalSearch.MAX_MATCHES; i--) {
                        TerminalSearch.Match m = inRow.get(i);
                        newestFirst.add(new Hit(row + pushed, m.from(), m.to()));
                    }
                }
                next = last - 1 + pushed;
            } finally {
                buf.unlock();
            }
        }
        return newestFirst.reversed();
    }

    /** Scrolls so the current hit is in view, a third of the way down, unless it already is. */
    private void reveal(SearchState state) {
        if (state.current() < 0 || buffer == null) {
            return;
        }
        int row = rowOf(state.hits().get(state.current()).line());
        if (row >= scrollOrigin && row < scrollOrigin + rows) {
            return;
        }
        int origin = row - rows / 3;
        scrollOrigin = Math.max(-buffer.getHistoryLinesCount(), Math.min(0, origin));
    }

    /** Paints the hits in view: a wash of the palette's yellow, stronger on the current one. Caller holds the lock. */
    private void drawSearch(GraphicsContext g) {
        SearchState state = search;
        if (state.hits().isEmpty()) return;
        Color yellow = palette.ansi()[3];
        Color wash = Color.color(yellow.getRed(), yellow.getGreen(), yellow.getBlue(), 0.35);
        Color strong = Color.color(yellow.getRed(), yellow.getGreen(), yellow.getBlue(), 0.75);
        long first = scrollOrigin + pushedLines;
        long last = first + rows - 1;
        // Hits are in line order: skip to the first in view rather than walking all of them every frame.
        java.util.List<Hit> hits = state.hits();
        int lo = 0;
        int hi = hits.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (hits.get(mid).line() < first) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < hits.size() && hits.get(i).line() <= last; i++) {
            Hit h = hits.get(i);
            int row = rowOf(h.line());
            String text = lineTextAt(row);
            if (h.to() > text.length()) continue; // the row changed under the hit; it is found again on the next step
            double x = ColumnMap.columnAt(text, h.from());
            double to = ColumnMap.columnAt(text, h.to());
            g.setFill(i == state.current() ? strong : wash);
            fillCells(g, x, to, (row - scrollOrigin) * lineHeight, lineHeight);
        }
    }

    // ---------------------------------------------------------------- selection

    /**
     * Keeps the viewport and any selection pinned to their text as lines age out of the screen and
     * into history.
     *
     * <p>Called on the emulator thread, after any buffer change. No listener carries the delta, so
     * it is derived from the history line count plus the lines a full history dropped. Without this, output arriving while the user is scrolled back slides the
     * text under them, and a selection made moments earlier silently comes to refer to different
     * characters — which matters because copying it would then produce the wrong text.
     */
    private void onHistoryLineCountChanged() {
        if (buffer == null) return;
        int now = buffer.getHistoryLinesCount();
        int delta = now - knownHistoryLines + discardedHistoryLines;
        knownHistoryLines = now;
        discardedHistoryLines = 0;
        if (delta <= 0) return;

        // At the live view (origin 0) staying pinned to the bottom is what is wanted, so only a
        // scrolled-back viewport moves.
        if (scrollOrigin < 0) scrollOrigin = Math.max(-now, scrollOrigin - delta);

        // Marks and hits are kept at absolute lines; this one count moves them all.
        pushedLines += delta;

        TerminalSelection current = selection;
        if (current != null) current.shiftY(-delta);
        Point anchor = selectionAnchor;
        if (anchor != null) selectionAnchor = new Point(anchor.x, anchor.y - delta);
        markDirty();
    }

    /**
     * The buffer cell under a mouse position, clamped to the grid.
     *
     * <p>The x it returns is a <b>slot</b>, not the column the pointer is over: a selection is
     * handed to JediTerm, which indexes the line's array. The two differ by one for every astral
     * character to the left — a Nerd Font icon before each name in an `ls` listing — so without
     * the translation a drag selects text a column off from the one it highlighted.
     *
     * <p>Mouse <i>reporting</i> is the opposite case and deliberately does not come through here:
     * xterm's protocol speaks columns, and a program being told where its own cursor should go
     * wants what the user pointed at.
     */
    private Point cellAt(MouseEvent e) {
        int column = (int) Math.floor(e.getX() / charWidth);
        int row = (int) Math.floor(e.getY() / lineHeight);
        column = Math.max(0, Math.min(columns, column));
        row = Math.max(0, Math.min(rows - 1, row));
        int line = scrollOrigin + row;
        return new Point(ColumnMap.slotAt(lineTextAt(line), column), line);
    }

    /** A row's raw characters, or empty when it is not there. Callers already hold the lock. */
    private String lineTextAt(int row) {
        if (buffer == null) return "";
        try {
            var line = buffer.getLine(row);
            return line == null ? "" : line.getText();
        } catch (RuntimeException e) {
            // Out of range: the row scrolled away between the event and this read.
            return "";
        }
    }

    /** Slots in a row — the bound a selection clamps to, which is not the column count. */
    private int slotsInRow(int row) {
        return Math.max(columns, lineTextAt(row).length());
    }

    // ---------------------------------------------------------------- mouse reporting

    /**
     * Offers the event to the program running in the terminal.
     *
     * <p>Returns true when it was reported, in which case the local gesture (selection, scrollback)
     * must not also run — otherwise a click in vim both moves its cursor and starts a highlight.
     *
     * <p><b>Shift bypasses reporting entirely.</b> That is the xterm convention and it is the only
     * way out of a full-screen program that has grabbed the mouse: without it, an application like
     * htop makes the text impossible to select.
     */
    private boolean reportMouse(MouseEvent e, com.jediterm.core.input.MouseEvent.Type type) {
        if (session == null || e.isShiftDown()) return false;
        int code = MouseEncoding.buttonCode(e.getButton());
        var event = new com.jediterm.core.input.MouseEvent(type, code, MouseEncoding.modifierFlags(e));
        return session.getTerminal().onMouseEvent(column(e), visibleRow(e), event, mouseSettings());
    }

    private boolean reportWheel(ScrollEvent e) {
        if (session == null || e.isShiftDown()) return false;
        int code = MouseEncoding.wheelButtonCode(e.getDeltaY());
        var event = new com.jediterm.core.input.MouseWheelEvent(
                code, MouseEncoding.modifierFlags(e), MouseEncoding.unitsToScroll(e.getDeltaY(), lineHeight));
        int column = Math.max(0, Math.min(columns - 1, (int) Math.floor(e.getX() / charWidth)));
        int row = Math.max(0, Math.min(rows - 1, (int) Math.floor(e.getY() / lineHeight)));
        return session.getTerminal().onMouseEvent(column, row, event, mouseSettings());
    }

    private MouseEventProcessingSettings mouseSettings() {
        // The third flag turns a wheel scroll into arrow keys while on the alternate screen, so
        // less and man scroll with the wheel even though they never enable mouse reporting.
        return new MouseEventProcessingSettings(true, display.isAlternateScreen(), true);
    }

    private int column(MouseEvent e) {
        return Math.max(0, Math.min(columns - 1, (int) Math.floor(e.getX() / charWidth)));
    }

    /** Row relative to the visible screen — what the reporting protocol addresses. */
    private int visibleRow(MouseEvent e) {
        return Math.max(0, Math.min(rows - 1, (int) Math.floor(e.getY() / lineHeight)));
    }

    private void onMouseReleased(MouseEvent e) {
        boolean dragged = dragging;
        dragging = false;
        stopAutoScroll();
        if (pressedOnScrollBar) {
            pressedOnScrollBar = false;
            return;
        }
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.RELEASED)) {
            e.consume();
            return;
        }
        // The end of a drag is when the selection is made; a double or triple click was made at its press. Anything
        // else — a plain click — left no selection to announce.
        if (dragged && selection != null) settleSelection();
    }

    // ---------------------------------------------------------------- links

    /**
     * The link under the pointer, with everything the click and the underline need.
     *
     * <p>{@code file} is null for a URL and non-null for a path that was found on disk — a path
     * that was not is never a hover at all, so nothing here can be opened that does not exist.
     *
     * @param spans the run split across the rows it occupies, since a wrapped URL is one link drawn
     *     on two lines
     */
    private record Hover(LinkMatch match, java.nio.file.@Nullable Path file, java.util.List<LinkSpan> spans) {}

    /** One row's worth of an underline: a line index and the slots it covers. */
    private record LinkSpan(int line, int fromSlot, int toSlot) {}

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /**
     * How far to look either side for the rest of a wrapped line.
     *
     * <p>A URL that fills eight rows is not a URL anyone is going to click, and the bound is what
     * keeps the hover check O(1) rather than O(scrollback) on a screen full of wrapped output.
     */
    private static final int MAX_WRAP_ROWS = 8;

    private @Nullable LinkActions linkActions;
    private @Nullable Hover hover;
    private @Nullable Hover menuLink;
    private double lastMouseX = -1;
    private double lastMouseY = -1;

    /** Where a link goes once clicked. Supplied by the window; without it links are inert. */
    public void setLinkActions(LinkActions linkActions) {
        this.linkActions = linkActions;
    }

    /**
     * Recomputes what is under the pointer.
     *
     * <p>Only while the modifier is held, which is what keeps this off the ordinary mouse-move
     * path: with no modifier the answer is "nothing" after one boolean, and the filesystem is never
     * asked. Terminals that underline on hover regardless have to decorate text you are only
     * reading, and pay for it on every move.
     */
    private void updateHover(double x, double y, boolean modifierDown) {
        hoverModifierDown = modifierDown;
        Hover previous = hover;
        hover = modifierDown ? linkAt(x, y) : null;
        setCursor(hover == null ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.HAND);
        if (!sameHover(previous, hover)) {
            markDirty();
            showLinkTarget(hover, x, y);
        }
    }

    /** Shows where an OSC 8 link goes: its text is the program's to choose, and need not be its address. */
    private javafx.scene.control.@Nullable Tooltip linkTip;

    private void showLinkTarget(@Nullable Hover target, double x, double y) {
        boolean disguised = target != null
                && target.match().isUrl()
                && !target.match().text().equals(target.match().target());
        if (target == null || !disguised) {
            if (linkTip != null) linkTip.hide();
            return;
        }
        if (linkTip == null) {
            linkTip = new javafx.scene.control.Tooltip();
            linkTip.getStyleClass().add("terminal-link-tip");
        }
        linkTip.setText(target.match().target());
        javafx.geometry.Point2D at = localToScreen(x, y + lineHeight);
        if (at != null && getScene() != null) linkTip.show(this, at.getX(), at.getY());
    }

    /** The address an OSC 8 link under the pointer shows in its tip, or null; for tests. */
    public @Nullable String linkTipText() {
        return linkTip != null && linkTip.isShowing() ? linkTip.getText() : null;
    }

    private static boolean sameHover(@Nullable Hover a, @Nullable Hover b) {
        if (a == null || b == null) return a == b;
        return a.spans().equals(b.spans()) && a.match().equals(b.match());
    }

    /** The link at a point in the view, or null. Reads the buffer, so it takes the lock. */
    private @Nullable Hover linkAt(double x, double y) {
        if (buffer == null || linkActions == null) return null;
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return null;

        int row = (int) Math.floor(y / lineHeight);
        if (row < 0 || row >= rows) return null;
        int line = scrollOrigin + row;
        int column = (int) Math.floor(x / charWidth);
        if (column < 0 || column >= columns) return null;

        // Tried, not waited for: a hover is not worth a frozen editor while output floods in.
        if (!buffer.tryLock()) return hover;
        try {
            LogicalLine logical = logicalLineAt(line);
            if (logical == null) return null;
            int slot = ColumnMap.slotAt(lineTextAt(line), column);
            int index = logical.offsetOf(line) + slot;
            LinkMatch match = osc8At(logical, line, slot, index);
            if (match == null) match = LinkScanner.at(logical.text(), index);
            if (match == null) return null;

            // Moving along a link with the modifier held asks the same question of the filesystem
            // for every pixel of travel. The answer cannot change within one hover, so it is asked
            // once per link rather than once per event.
            Hover previous = hover;
            java.nio.file.Path file =
                    previous != null && previous.match().equals(match) ? previous.file() : fileFor(match);
            if (match.kind() == LinkMatch.Kind.PATH && file == null) return null;
            return new Hover(match, file, logical.spansOf(match.start(), match.end()));
        } catch (RuntimeException e) {
            // The rows moved under us between the event and the read. There is no link here now.
            return null;
        } finally {
            buffer.unlock();
        }
    }

    /**
     * The OSC 8 link at a cell, as a URL match over the run of cells that carry the same target, or null. A program
     * that marked text as a link meant it, so it wins over what the text itself looks like. Caller holds the lock.
     */
    private @Nullable LinkMatch osc8At(LogicalLine logical, int line, int slot, int index) {
        Osc8Links.Target target = osc8TargetAt(line, slot);
        if (target == null) return null;
        int start = index;
        while (start > 0 && osc8TargetAtIndex(logical, start - 1) == target) start--;
        int end = index + 1;
        while (end < logical.text().length() && osc8TargetAtIndex(logical, end) == target) end++;
        return new LinkMatch(LinkMatch.Kind.URL, logical.text().substring(start, end), start, end, target.uri(), 0, 0);
    }

    private Osc8Links.@Nullable Target osc8TargetAt(int line, int slot) {
        TerminalTextBuffer buf = buffer;
        if (buf == null) return null;
        var row = buf.getLine(line);
        return row == null || slot < 0 || slot >= row.length() ? null : Osc8Links.targetOf(row.getStyleAt(slot));
    }

    private Osc8Links.@Nullable Target osc8TargetAtIndex(LogicalLine logical, int index) {
        int offset = 0;
        for (int i = 0; i < logical.rowTexts().size(); i++) {
            int length = logical.rowTexts().get(i).length();
            if (index < offset + length) return osc8TargetAt(logical.firstLine() + i, index - offset);
            offset += length;
        }
        return null;
    }

    /**
     * The file a match names, or null when it names none.
     *
     * <p>This is the whole safety story for paths: a candidate is any word under the pointer, and
     * what makes it a link is that it resolves — against <em>this tab's</em> shell directory — to
     * something that is actually there. A pattern cannot tell a filename from a word; the
     * filesystem can.
     */
    private java.nio.file.@Nullable Path fileFor(LinkMatch match) {
        String target = match.target();
        if (match.kind() == LinkMatch.Kind.URL) {
            // A remote shell's file:// names a file of its machine: the tab's mapping finds it here.
            java.util.function.Function<String, java.nio.file.Path> paths = shellPaths;
            String onShell = paths == null ? null : ShellIntegration.pathOfCwdUrl(target);
            java.nio.file.Path fromUri =
                    paths != null && onShell != null ? paths.apply(onShell) : LinkPaths.fromFileUri(target);
            return exists(fromUri) ? fromUri : null;
        }
        String cwd = display.getCwd();
        java.nio.file.Path resolved = shellPaths == null
                ? LinkPaths.resolve(target, cwd == null || cwd.isBlank() ? null : java.nio.file.Path.of(cwd))
                : LinkPaths.resolveOn(target, cwd, shellPaths);
        return exists(resolved) ? resolved : null;
    }

    /** What remote paths are known to be there, as answered off the FX thread; a few hundred at most. */
    private final java.util.Map<java.nio.file.Path, Boolean> remoteExists = new java.util.LinkedHashMap<>();

    private final java.util.Set<java.nio.file.Path> remoteAsking = new java.util.HashSet<>();

    /**
     * Whether {@code path} is there. On this machine it is asked at once (a local stat is cheap); a remote shell's
     * path is a file over SFTP, so it is asked off the FX thread — the hover and the right-click asked the host on
     * the FX thread before, and a slow link froze the editor. Until the answer arrives the link is not offered; when
     * it does, the hover is worked out again.
     */
    private boolean exists(java.nio.file.@Nullable Path path) {
        if (path == null) return false;
        if (shellPaths == null || com.editora.vfs.Vfs.isLocal(path)) return java.nio.file.Files.exists(path);
        Boolean known = remoteExists.get(path);
        if (known != null) return known;
        if (remoteAsking.add(path)) {
            Thread.ofVirtual().name("terminal-link-check").start(() -> {
                boolean there;
                try {
                    there = java.nio.file.Files.exists(path);
                } catch (RuntimeException e) {
                    there = false;
                }
                boolean answer = there;
                Platform.runLater(() -> {
                    remoteAsking.remove(path);
                    if (remoteExists.size() > 500) remoteExists.clear();
                    remoteExists.put(path, answer);
                    if (answer && lastMouseX >= 0) {
                        hover = null; // asked again now that the answer is in
                        updateHover(lastMouseX, lastMouseY, hoverModifierDown);
                    }
                });
            });
        }
        return false;
    }

    /** Whether the link modifier was down at the last hover update, to redo it when a remote answer arrives. */
    private boolean hoverModifierDown;

    /** Opens the hovered link. */
    private void openLink(@Nullable Hover target) {
        if (target == null || linkActions == null) return;
        if (target.file() != null) {
            linkActions.openFile(
                    target.file(), target.match().line(), target.match().column());
        } else if (target.match().isUrl()) {
            linkActions.openUrl(target.match().target());
        }
    }

    /** What the link points at, for the clipboard: the URL, or the resolved file. */
    private String linkAddress(@Nullable Hover target) {
        if (target == null) return "";
        if (target.match().isUrl() && target.file() == null)
            return target.match().target();
        return target.file() == null ? target.match().target() : target.file().toString();
    }

    /**
     * A logical line — one row, or the several a soft wrap spread it over.
     *
     * <p>Joined because a URL that reaches the right edge continues on the next row and is still
     * one URL. Terminals that scan a row at a time cut it in half, which fails on exactly the long
     * URLs most worth clicking.
     */
    private record LogicalLine(int firstLine, java.util.List<String> rowTexts, String text) {

        int offsetOf(int line) {
            int offset = 0;
            for (int i = 0; i < line - firstLine; i++) offset += rowTexts.get(i).length();
            return offset;
        }

        /** The range split into the rows it crosses, so the underline can be drawn per row. */
        java.util.List<LinkSpan> spansOf(int from, int to) {
            java.util.List<LinkSpan> spans = new ArrayList<>();
            int offset = 0;
            for (int i = 0; i < rowTexts.size(); i++) {
                int length = rowTexts.get(i).length();
                int start = Math.max(from, offset);
                int end = Math.min(to, offset + length);
                if (start < end) spans.add(new LinkSpan(firstLine + i, start - offset, end - offset));
                offset += length;
            }
            return spans;
        }
    }

    /** Builds the logical line containing a row. Caller holds the buffer lock. */
    private @Nullable LogicalLine logicalLineAt(int line) {
        // Bounded by the history that exists as well as by MAX_WRAP_ROWS: asking for row -1 with an
        // empty scrollback is not an exception but an ERROR line in JediTerm's own log, once per
        // mouse move, which turns a hover into a log flood.
        TerminalTextBuffer buf = buffer;
        if (buf == null) return null;
        int floor = -buf.getHistoryLinesCount();
        int first = line;
        int back = 0;
        while (back < MAX_WRAP_ROWS && first - 1 >= floor && isWrapped(first - 1)) {
            first--;
            back++;
        }
        java.util.List<String> texts = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int row = first;
        while (true) {
            String text = lineTextAt(row);
            texts.add(text);
            joined.append(text);
            if (!isWrapped(row) || row - first >= MAX_WRAP_ROWS) break;
            row++;
        }
        if (line < first || line > row) return null;
        return new LogicalLine(first, texts, joined.toString());
    }

    /** Whether a row continues onto the next one. Caller holds the buffer lock. */
    private boolean isWrapped(int row) {
        if (buffer == null) return false;
        try {
            var line = buffer.getLine(row);
            return line != null && line.isWrapped();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Underlines the hovered link, in the text's own colour. */
    private void drawLinkUnderline(GraphicsContext g) {
        Hover target = hover;
        if (target == null) return;
        g.setStroke(palette.foreground());
        g.setLineWidth(1);
        for (LinkSpan span : target.spans()) {
            int row = span.line() - scrollOrigin;
            if (row < 0 || row >= rows) continue;
            String text = lineTextAt(span.line());
            double from = ColumnMap.columnAt(text, span.fromSlot()) * charWidth;
            double to = ColumnMap.columnAt(text, span.toSlot()) * charWidth;
            // Half a pixel down the grid line, or a 1px stroke straddles it and paints two grey rows
            // instead of one solid one.
            double y = Math.floor((row + 1) * lineHeight) - 1.5;
            g.strokeLine(from, y, to, y);
        }
    }

    // ---------------------------------------------------------------- context menu

    /** The menu and the items it updates each time it opens. */
    private record Menu(
            ContextMenu menu,
            MenuItem copyItem,
            MenuItem pasteItem,
            MenuItem openLinkItem,
            MenuItem copyLinkItem,
            SeparatorMenuItem linkSeparator,
            MenuItem copyOutputItem) {}

    /** Built on first use, with the icons supplied by then. */
    private @Nullable Menu contextMenu;

    private Function<String, @Nullable Node> menuIcons = key -> null;
    private Supplier<List<MenuItem>> menuExtras = List::of;

    /**
     * Icons for the menu's own items, by key: {@code copy}, {@code paste}, {@code openLink},
     * {@code selectAll}, {@code clear}. The view has no icon set of its own; the window supplies Editora's.
     */
    public void setMenuIcons(Function<String, @Nullable Node> menuIcons) {
        this.menuIcons = menuIcons == null ? key -> null : menuIcons;
    }

    /**
     * Items the window adds below the view's own, asked for each time the menu opens. A terminal knows nothing
     * about tabs, targets or settings — those belong to the window that owns it.
     */
    public void setMenuExtras(Supplier<List<MenuItem>> menuExtras) {
        this.menuExtras = menuExtras == null ? List::of : menuExtras;
    }

    /**
     * Whether a context-menu request should open the menu rather than belong to the running program.
     *
     * <p>Extracted and static because it is the whole of the contention rule, and it is the kind of
     * three-way condition that is easy to get backwards and impossible to notice: getting it wrong
     * either makes right-click dead inside every TUI, or makes Copy unreachable from one.
     *
     * @param keyboardTrigger the menu key rather than a click — never contested, since nothing was
     *     reported to the program and the user asked for a menu explicitly
     */
    static boolean shouldShowMenu(MouseMode mode, boolean shift, boolean keyboardTrigger) {
        if (keyboardTrigger || shift) return true;
        return mode == MouseMode.MOUSE_REPORTING_NONE;
    }

    /**
     * Decides between the context menu and the running program.
     *
     * <p>Right-click is genuinely contested: a terminal wants it for Copy and Paste, while a
     * mouse-aware TUI (Midnight Commander, some file pickers) wants it as button 2 — and we report
     * it as such. The rule is the one that already governs selection: while a program has grabbed
     * the mouse it wins, and <b>Shift forces the menu</b>. Without that escape hatch there is no way
     * to copy anything from inside such a program.
     */
    private void onContextMenuRequested(ContextMenuEvent e) {
        // ContextMenuEvent carries no modifier state — only coordinates and whether a key raised
        // it — so shift is read from the press that triggered it.
        if (!shouldShowMenu(display.getMouseMode(), shiftOnLastPress, e.isKeyboardTrigger())) return;

        Menu m = contextMenu;
        if (m == null) {
            m = buildContextMenu();
            contextMenu = m;
        }
        // Refreshed per show: the menu is built once and reused, while everything it reports on
        // moves underneath it. Copy and Paste depend on the selection and the clipboard; the
        // menu-bar checkbox on a setting the View menu, its own chord and the settings window can
        // all change while this menu sits built and invisible.
        m.copyItem().setDisable(!hasSelection());
        m.copyOutputItem().setVisible(hasPromptMarks());
        m.copyOutputItem().setDisable(lastCommandOutput() == null);
        m.pasteItem().setDisable(!Clipboard.getSystemClipboard().hasString());
        // The link items are the menu's answer to a gesture nobody was told about: they need no
        // modifier and they say what is there. Computed for the right-clicked cell, not the last
        // hovered one — the menu can be raised from the keyboard, and the pointer may be elsewhere.
        menuLink = linkAt(e.getX(), e.getY());
        boolean onLink = menuLink != null;
        m.openLinkItem().setVisible(onLink);
        m.copyLinkItem().setVisible(onLink);
        m.linkSeparator().setVisible(onLink);
        refreshExtras(m.menu());
        m.menu().show(this, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    private Menu buildContextMenu() {
        MenuItem copyItem = item(tr("terminal.menu.copy"), menuIcons.apply("copy"), this::copySelection);
        MenuItem pasteItem = item(tr("terminal.menu.paste"), menuIcons.apply("paste"), this::paste);
        MenuItem openLinkItem =
                item(tr("terminal.menu.openLink"), menuIcons.apply("openLink"), () -> openLink(menuLink));
        MenuItem copyLinkItem = item(tr("terminal.menu.copyLinkAddress"), menuIcons.apply("copy"), () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(linkAddress(menuLink));
            Clipboard.getSystemClipboard().setContent(content);
        });

        // Hidden together with the items they divide: a separator left behind when the pointer was
        // not over a link is a rule across the top of the menu with nothing above it.
        SeparatorMenuItem linkSeparator = new SeparatorMenuItem();
        MenuItem copyOutputItem =
                item(tr("terminal.menu.copyLastOutput"), menuIcons.apply("copy"), this::copyLastOutput);

        ContextMenu menu = new ContextMenu(
                openLinkItem,
                copyLinkItem,
                linkSeparator,
                copyItem,
                pasteItem,
                item(tr("terminal.menu.selectAll"), menuIcons.apply("selectAll"), this::selectAll),
                copyOutputItem,
                new SeparatorMenuItem(),
                item(tr("terminal.menu.clearScrollback"), menuIcons.apply("clear"), this::clearScrollback));
        menu.setAutoHide(true);
        ownItems = menu.getItems().size();
        // The terminal's own chords, shown where its commands are: nothing else in the UI said them. Display only —
        // the panel's key filter is what acts on them, and it consumes them first.
        copyItem.setAccelerator(chord(MAC ? "Meta+C" : "Ctrl+Shift+C"));
        pasteItem.setAccelerator(chord(MAC ? "Meta+V" : "Ctrl+Shift+V"));
        return new Menu(menu, copyItem, pasteItem, openLinkItem, copyLinkItem, linkSeparator, copyOutputItem);
    }

    /** {@code text} as a key combination for a menu item's accelerator text. */
    public static javafx.scene.input.KeyCombination chord(String text) {
        return javafx.scene.input.KeyCombination.keyCombination(text);
    }

    /** How many of the menu's items are the view's own; the window's extras follow them. */
    private int ownItems;

    /** Replaces the window's items below the view's own with what it offers now. */
    private void refreshExtras(ContextMenu menu) {
        var items = menu.getItems();
        items.remove(ownItems, items.size());
        List<MenuItem> extras = menuExtras.get();
        if (extras != null && !extras.isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.addAll(extras);
        }
    }

    private static MenuItem item(String text, @Nullable Node icon, Runnable action) {
        MenuItem menuItem = new MenuItem(text, icon);
        menuItem.setOnAction(e -> action.run());
        return menuItem;
    }

    /** Selects the whole buffer: all of the scrollback plus the live screen. */
    public void selectAll() {
        if (buffer == null) return;
        buffer.lock();
        try {
            int history = buffer.getHistoryLinesCount();
            TerminalSelection all = new TerminalSelection(new Point(0, -history));
            all.updateEnd(new Point(slotsInRow(rows - 1), rows - 1));
            rectangular = false;
            selection = all;
            selectionAnchor = new Point(0, -history);
        } finally {
            buffer.unlock();
        }
        settleSelection();
        markDirty();
    }

    /**
     * Drops the scrollback while keeping the visible screen — clearing history without losing the
     * prompt you are standing on.
     */
    public void clearScrollback() {
        if (buffer == null) return;
        buffer.lock();
        try {
            buffer.clearHistory();
        } finally {
            buffer.unlock();
        }
        // Everything measured against the history that just went away has to be reset with it, or
        // the viewport scrolls into rows that no longer exist.
        selection = null;
        selectionAnchor = null;
        settledText = null;
        scrollOrigin = 0;
        knownHistoryLines = 0;
        discardedHistoryLines = 0;
        marks.removeIf(m -> rowOf(m.line()) < 0);
        clearSearch();
        markDirty();
    }

    /** Shift state of the most recent press, for the context-menu event that may follow it. */
    private boolean shiftOnLastPress;

    /** Whether an event is headed for the scrollbar, i.e. the bar or one of its skin's parts. */
    private boolean fromScrollBar(MouseEvent e) {
        javafx.scene.Node node = e.getTarget() instanceof javafx.scene.Node n ? n : null;
        for (; node != null; node = node.getParent()) {
            if (node == scrollBar) return true;
        }
        return false;
    }

    private void onMousePressed(MouseEvent e) {
        // A click anywhere in the terminal dismisses the menu instead of acting on the terminal.
        // ContextMenu's own auto-hide covers clicks outside the window, but this filter runs first
        // for clicks inside it, which would otherwise start a selection under the open menu.
        ContextMenu shown = contextMenu == null ? null : contextMenu.menu();
        if (shown != null && shown.isShowing()) {
            shown.hide();
            e.consume();
            return;
        }
        // The mouse filters are on this Region, and a filter runs in the capturing phase — so a
        // press on the scrollbar reaches the terminal *before* the bar itself. Without this the
        // terminal starts a selection under every scroll drag.
        pressedOnScrollBar = fromScrollBar(e);
        if (pressedOnScrollBar) return; // deliberately not consumed: the bar still needs it

        requestFocus();
        shiftOnLastPress = e.isShiftDown();

        // Before mouse reporting, deliberately. A program that has grabbed the mouse would
        // otherwise swallow the click, and the modifier is precisely the gesture that means "this
        // one is the terminal's". Only when something is actually under the pointer, so the
        // modifier is not taken away from the program the rest of the time.
        if (e.getButton() == MouseButton.PRIMARY && LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown())) {
            Hover target = linkAt(e.getX(), e.getY());
            if (target != null) {
                openLink(target);
                e.consume();
                return;
            }
        }

        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.PRESSED)) {
            // The program has this press, so nothing local may grow out of it. In mode 1000 the program hears press
            // and release only, the drag between them is not reported, and a drag that falls through to selection
            // must not find an anchor from some earlier plain click waiting for it.
            selectionAnchor = null;
            e.consume();
            return;
        }
        if (buffer == null) return;
        if (e.getButton() == MouseButton.MIDDLE) {
            // Middle-click pastes the PRIMARY selection, as on every X11 terminal — read through wl-paste or xclip,
            // since JavaFX's Clipboard is CLIPBOARD and nothing else. Where there is no PRIMARY (macOS, Windows) or no
            // tool to read it, the clipboard. Reached only when the program did not take the press, or Shift
            // bypassed it.
            PrimarySelection.get(this::pasteText, this::paste);
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;

        Point at = cellAt(e);
        switch (e.getClickCount()) {
            case 2 -> {
                selectWordAt(at);
                settleSelection();
            }
            case 3 -> {
                selectLineAt(at);
                settleSelection();
            }
            default -> {
                // A press with no drag clears the selection, matching every terminal: it is how
                // you dismiss a highlight without copying it.
                selectionAnchor = at;
                selection = null;
                settledText = null;
                // Option on macOS, as in Terminal and iTerm2; Alt elsewhere, as in Windows Terminal and Konsole.
                // Ctrl+Alt works too, and is the one to use where the window manager takes Alt+drag to move windows,
                // as GNOME and KDE do.
                blockDrag = e.isAltDown();
                anchorColumn = Math.max(0, Math.min(columns - 1, (int) Math.floor(e.getX() / charWidth)));
                dragging = true;
                autoScroll.reset();
            }
        }
        markDirty();
    }

    private void onMouseMoved(MouseEvent e) {
        lastMouseX = e.getX();
        lastMouseY = e.getY();
        // Any-event tracking (1003): the program wants the pointer's moves too, buttons up.
        if (display.getMouseMode() == MouseMode.MOUSE_REPORTING_ALL_MOTION) {
            reportMouse(e, com.jediterm.core.input.MouseEvent.Type.MOVED);
        }
        updateHover(lastMouseX, lastMouseY, LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown()));
    }

    /** Follows the modifier going down or up while the pointer sits still. */
    private void onLinkModifierChanged(KeyEvent e) {
        if (lastMouseX < 0) return;
        updateHover(lastMouseX, lastMouseY, LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown()));
    }

    private void onKeyReleased(KeyEvent e) {
        if (e.getCode() == KeyCode.ALT_GRAPH) altGrHeld = false;
        onLinkModifierChanged(e);
        // Always, flags or not: the release is what takes the key out of the held set, and a key
        // left in it would make its next press look like a repeat.
        TerminalSession live = session;
        if (live != null && live.isRunning()) {
            byte[] kitty =
                    kittyInput.released(e, live.keyboardFlags(display.isAlternateScreen()), altIsMeta, ctrlAltIsMeta());
            if (kitty != null) live.send(kitty);
        }
    }

    /** The kitty keyboard protocol's view of the keys: repeats, and the releases owed. */
    private final KittyKeyInput kittyInput = new KittyKeyInput();

    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux");

    /** Whether AltGr is down: Linux reports it as its own key, so Ctrl+Alt there is a chord, not composing. */
    private boolean altGrHeld;

    private boolean ctrlAltIsMeta() {
        return LINUX && !altGrHeld;
    }

    private void onMouseDragged(MouseEvent e) {
        if (pressedOnScrollBar) return;
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.DRAGGED)) {
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || selectionAnchor == null) return;
        dragX = e.getX();
        dragY = e.getY();
        extendDragTo(dragX, dragY);
        if (pastEdge(dragY) != 0) {
            if (dragScroller.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                autoScroll.reset();
                dragScroller.play();
            }
        } else {
            stopAutoScroll();
        }
    }

    /** Extends the drag's selection to a pointer position, clamped to the view. */
    private void extendDragTo(double x, double y) {
        Point anchor = selectionAnchor;
        if (anchor == null || buffer == null) return;
        if (blockDrag) {
            Point to = boundaryColumnAt(x, y);
            if (selection == null && !SelectionGeometry.leftPressCell(new Point(anchorColumn, anchor.y), to.y, to.x))
                return;
            rectangular = true;
            selection = SelectionGeometry.blockDrag(anchorColumn, anchor.y, to.y, to.x);
        } else {
            Point to = boundaryAt(x, y);
            // Nothing until the pointer has left the cell it pressed in, so a plain click never leaves a one-cell
            // selection behind — unless one exists already, in which case the drag is extending it and every
            // movement counts.
            if (selection == null && !SelectionGeometry.leftPressCell(anchor, to.y, to.x)) return;
            rectangular = false;
            selection = SelectionGeometry.drag(anchor, to.y, to.x);
        }
        markDirty();
    }

    /**
     * The cell boundary nearest a pointer position, as a slot — the end of a drag, where "past the middle of a cell"
     * means the cell is in. See {@link SelectionGeometry#drag}.
     */
    private Point boundaryAt(double x, double y) {
        Point cell = boundaryColumnAt(x, y);
        return new Point(ColumnMap.slotAt(lockedLineTextAt(cell.y), cell.x), cell.y);
    }

    /** {@link #boundaryAt} as a column — what a block is measured in. */
    private Point boundaryColumnAt(double x, double y) {
        int column = (int) Math.round(x / charWidth);
        int row = (int) Math.floor(y / lineHeight);
        column = Math.max(0, Math.min(columns, column));
        row = Math.max(0, Math.min(rows - 1, row));
        return new Point(column, scrollOrigin + row);
    }

    /** {@link #lineTextAt} for a caller that does not hold the lock: the mouse handlers, on the FX thread. */
    private String lockedLineTextAt(int row) {
        TerminalTextBuffer buf = buffer;
        if (buf == null) return "";
        buf.lock();
        try {
            return lineTextAt(row);
        } finally {
            buf.unlock();
        }
    }

    /**
     * How far past the top (negative) or bottom (positive) edge the pointer is, in pixels; zero inside. The bottom
     * edge is the last whole row's, not the view's, which can have a sliver below.
     */
    private double pastEdge(double y) {
        if (y < 0) return y;
        double bottom = rows * lineHeight;
        return y >= bottom ? y - bottom + 1 : 0;
    }

    /** Where the pointer last was during the drag, for the ticks that autoscroll with it held still. */
    private double dragX;

    private double dragY;

    /** How far a tick of autoscroll goes: by time, and faster the further past the edge the pointer is. */
    private final AutoScroll autoScroll = new AutoScroll();

    /**
     * While a selection is dragged past an edge: the scrollback scrolls by time rather than per event — a pointer held
     * still below the view sends none, and it still means "keep going" — and faster the further out it is, so a
     * careful selection and one through a long log are both possible with the same gesture.
     */
    private final javafx.animation.Timeline dragScroller = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(16), e -> dragScrollTick()));

    {
        dragScroller.setCycleCount(javafx.animation.Animation.INDEFINITE);
    }

    private void dragScrollTick() {
        if (!dragging || buffer == null || selectionAnchor == null) {
            stopAutoScroll();
            return;
        }
        double past = pastEdge(dragY);
        if (past == 0) {
            stopAutoScroll();
            return;
        }
        int lines = autoScroll.step(System.nanoTime(), Math.abs(past), lineHeight);
        if (lines == 0) return;
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, scrollOrigin + (past < 0 ? -lines : lines)));
        if (next == scrollOrigin) return;
        scrollOrigin = next;
        extendDragTo(dragX, dragY);
    }

    private void stopAutoScroll() {
        dragScroller.stop();
        autoScroll.reset();
    }

    /** Whether a drag past an edge is scrolling the view; for tests. */
    boolean isDragScrolling() {
        return dragScroller.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    /**
     * The selection covering the word under {@code at}.
     *
     * <p>Package-visible and static so it can be tested against a plain buffer — the boundary
     * arithmetic below is exactly the kind that looks right and is wrong by one.
     *
     * <p>{@code getNextSeparator} returns the <em>last character of the word</em>, while a
     * selection's end is exclusive. Passing it through unadjusted drops the final character, so
     * double-clicking "india" yields "indi" — which looks like working selection until someone
     * pastes it.
     */
    static TerminalSelection wordSelection(Point at, TerminalTextBuffer buffer) {
        buffer.lock();
        try {
            Point start = SelectionUtil.getPreviousSeparator(at, buffer);
            Point lastChar = SelectionUtil.getNextSeparator(at, buffer);
            TerminalSelection word = new TerminalSelection(new Point(start.x, start.y));
            word.updateEnd(new Point(lastChar.x + 1, lastChar.y));
            return word;
        } finally {
            buffer.unlock();
        }
    }

    private void selectWordAt(Point at) {
        TerminalTextBuffer buf = buffer;
        if (buf == null) return; // the press handler has checked; no session, no words
        TerminalSelection word = wordSelection(at, buf);
        rectangular = false;
        selection = word;
        selectionAnchor = new Point(word.getStart().x, word.getStart().y);
    }

    private void selectLineAt(Point at) {
        TerminalSelection line = new TerminalSelection(new Point(0, at.y));
        line.updateEnd(new Point(Math.max(columns, lockedLineTextAt(at.y).length()), at.y));
        rectangular = false;
        selection = line;
        selectionAnchor = new Point(0, at.y);
    }

    public boolean hasSelection() {
        return selection != null;
    }

    /** The selected text, or "" — the find bar starts from a one-line selection. */
    public String selectedText() {
        String text = lockedSelectedText();
        return text == null ? "" : text;
    }

    /** The text a selection stands for, a stream's or a block's. The caller holds the lock. */
    private String selectedText(TerminalSelection current, TerminalTextBuffer buf) {
        if (rectangular) return BlockSelection.text(current, buf);
        // JediTerm's extractor, not ours: it understands that a wrapped line is one logical line and must not gain a
        // newline in the middle.
        String text = SelectionUtil.getSelectionText(current, buf);
        return text == null ? "" : text;
    }

    /** {@link #selectedText(TerminalSelection, TerminalTextBuffer)} without the lock; null with no selection. */
    private @Nullable String lockedSelectedText() {
        TerminalSelection current = selection;
        TerminalTextBuffer buf = buffer;
        if (current == null || buf == null) return null;
        buf.lock();
        try {
            return selectedText(current, buf);
        } finally {
            buf.unlock();
        }
    }

    /** Copies the selection, returning false when there was nothing to copy. */
    public boolean copySelection() {
        String text = lockedSelectedText();
        if (text == null || text.isEmpty()) return false;
        toClipboard(text);
        return true;
    }

    private static void toClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * A selection has been made — at the end of a drag, on a double or triple click, by Select All. Its text is
     * remembered (see {@link #settledText}), offered as PRIMARY where there is one, and copied to the clipboard if
     * copy-on-select is on.
     */
    private void settleSelection() {
        String text = lockedSelectedText();
        settledText = text;
        if (text == null || text.isEmpty()) return;
        PrimarySelection.set(text);
        if (copyOnSelect) toClipboard(text);
    }

    /**
     * Drops the selection when the text under it is no longer the text that was selected. Under the lock, from the
     * frame, so the comparison and the highlight agree.
     */
    private void dropSelectionIfRewritten() {
        TerminalSelection current = selection;
        String settled = settledText;
        TerminalTextBuffer buf = buffer;
        if (current == null || settled == null || buf == null) return;
        int top = Math.min(current.getStart().y, current.getEnd().y);
        int bottom = Math.max(current.getStart().y, current.getEnd().y);
        if (bottom - top > VERIFIED_ROWS) return;
        if (!settled.equals(selectedText(current, buf))) {
            selection = null;
            settledText = null;
        }
    }

    /** Copies each selection to the clipboard as it is made (Settings → Terminal). */
    public void setCopyOnSelect(boolean copyOnSelect) {
        this.copyOnSelect = copyOnSelect;
    }

    public void clearSelection() {
        settledText = null;
        if (selection != null) {
            selection = null;
            markDirty();
        }
    }

    /** Paints the selection over the text as a translucent wash. */
    private void drawSelection(GraphicsContext g) {
        TerminalSelection current = selection;
        if (current == null) return;
        g.setFill(selectionWash);
        if (rectangular) {
            for (int row = 0; row < rows; row++) {
                int[] span = SelectionGeometry.blockOnRow(current, scrollOrigin + row);
                if (span == null) continue;
                fillCells(g, span[0], span[1], row * lineHeight, lineHeight);
            }
            return;
        }
        for (int row = 0; row < rows; row++) {
            int line = scrollOrigin + row;
            // Ours, not TerminalSelection.intersect: that one counts the end column in, and the copy does not, so the
            // highlight was a cell wider than the text it stood for.
            int[] span = SelectionGeometry.onRow(current, line, slotsInRow(line));
            if (span == null) continue;
            // The span is in slots; the rectangle is in columns.
            String text = lineTextAt(line);
            double x = ColumnMap.columnAt(text, span[0]);
            double to = ColumnMap.columnAt(text, span[1]);
            fillCells(g, x, to, row * lineHeight, lineHeight);
        }
    }

    /** Any keystroke returns the view to the live screen, as every terminal does. */
    private void snapToLive() {
        if (scrollOrigin != 0) {
            scrollOrigin = 0;
            markDirty();
        }
    }

    private void onScroll(ScrollEvent e) {
        if (buffer == null) return;
        if (reportWheel(e)) {
            e.consume();
            return;
        }
        // No scrollback exists on the alternate screen; a full-screen program owns the viewport.
        if (display.isAlternateScreen()) return;

        int lines = wheelLines(e);
        if (lines == 0) {
            e.consume();
            return;
        }
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, scrollOrigin - lines));
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
        e.consume();
    }

    /** Lines of scroll not yet applied: a trackpad sends many events of a pixel or two. */
    private double wheelRemainder;

    /**
     * Whole lines to scroll for one event, positive up. A wheel notch says how many lines it means (three,
     * usually); a trackpad's small pixel deltas add up until they make a line. One event of a pixel used to move
     * three lines whatever its size, so a trackpad flick flew through the scrollback.
     */
    private int wheelLines(ScrollEvent e) {
        double lines = e.getTextDeltaYUnits() == ScrollEvent.VerticalTextScrollUnits.LINES && e.getTextDeltaY() != 0
                ? e.getTextDeltaY()
                : e.getDeltaY() / Math.max(1, lineHeight);
        if (Math.signum(lines) != Math.signum(wheelRemainder)) wheelRemainder = 0; // a change of direction
        wheelRemainder += lines;
        int whole = (int) wheelRemainder;
        wheelRemainder -= whole;
        return whole;
    }

    /**
     * Asked before a paste of several lines reaches a program that has not turned on bracketed paste — sh, a REPL,
     * an older remote shell — where every line runs as it arrives. False drops the paste.
     */
    private java.util.function.Predicate<String> pasteGuard = text -> true;

    public void setPasteGuard(java.util.function.Predicate<String> pasteGuard) {
        this.pasteGuard = pasteGuard == null ? text -> true : pasteGuard;
    }

    /** Pastes the clipboard, honouring bracketed-paste mode if the shell enabled it. */
    public void paste() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text != null) pasteText(text);
    }

    /** Pastes {@code text} — the clipboard's, or PRIMARY's on a middle-click — as {@link #paste} does. */
    private void pasteText(String text) {
        TerminalSession session = this.session;
        if (text.isEmpty() || session == null) return;
        if (!display.isBracketedPaste()
                && KeyEncoding.pasteText(text).strip().contains("\n")
                && !pasteGuard.test(text)) {
            return;
        }
        snapToLive();
        session.send(KeyEncoding.encodePaste(text, display.isBracketedPaste()));
    }

    public void setAltIsMeta(boolean altIsMeta) {
        this.altIsMeta = altIsMeta;
    }
}
