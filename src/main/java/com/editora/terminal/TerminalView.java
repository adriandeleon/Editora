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

    private TerminalSession session;
    private TerminalTextBuffer buffer;

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
    private volatile TerminalSelection selection;

    /** Where the current drag began, so a backwards drag extends rather than restarts. */
    private Point selectionAnchor;

    /** Lines already in history, to derive the delta the history listener does not supply. */
    private int knownHistoryLines;

    /** Lines a full history dropped since the last {@link #onHistoryLineCountChanged()}. */
    private int discardedHistoryLines;

    private volatile boolean dirty = true;
    private int columns = 80;
    private int rows = 24;

    /** Alt/Option prefixes ESC rather than composing a character; see {@link KeyEncoding}. */
    private boolean altIsMeta = true;

    private final AnimationTimer painter = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (dirty) {
                dirty = false;
                render();
            }
        }
    };

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
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        setOnContextMenuRequested(this::onContextMenuRequested);

        display.setOnRepaint(this::markDirty);
        display.setSelectionSupplier(() -> selection);
        // Visual bell. An audible one would mean java.awt.Toolkit.beep(), which initialises AWT —
        // see App.main for why that is off the table.
        display.setOnBell(() -> {
            if (bellEnabled) Platform.runLater(this::flashBell);
        });
        display.setOnAlternateScreenChanged(() -> {
            // Scrollback belongs to the primary buffer; entering or leaving the alternate screen
            // must snap back to the live view or a full-screen program renders at an offset.
            scrollOrigin = 0;
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
        // Started here rather than inside the session: what the directory is *for* is a UI
        // question. The term layer reports the shell's state; it does not decide what a tab is
        // called. Skipped outright where the OS cannot answer, or the shell is not on this machine,
        // so no thread runs to find out.
        if (ProcessCwd.isSupported() && session.pid() > 0) {
            cwdWatch = CwdWatcher.watch(session.pid(), cwd -> {
                if (!shellReportsCwd) {
                    display.setCwd(cwd);
                }
            });
        }
        painter.start();
        markDirty();
        if (columns != openColumns || rows != openRows) {
            session.resize(columns, rows); // laid out again while it was opening
        }
    }

    private CwdWatcher cwdWatch;

    private Runnable onSessionEnded;

    public void setOnSessionEnded(Runnable onSessionEnded) {
        this.onSessionEnded = onSessionEnded;
    }

    public FxTerminalDisplay getDisplay() {
        return display;
    }

    public TerminalSession getSession() {
        return session;
    }

    public void close() {
        closed = true;
        painter.stop();
        // Before the session: the watcher polls by pid, and a pid is reused. Left running past the
        // process it was watching, it would eventually start reporting some unrelated process's
        // directory into a tab that no longer exists.
        if (cwdWatch != null) cwdWatch.close();
        if (session != null) session.close();
    }

    private void markDirty() {
        dirty = true;
    }

    /** Visual bell: a brief wash over the canvas, cleared by the next frame. */
    private void flashBell() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.color(1, 1, 1, 0.18));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        javafx.animation.PauseTransition clear = new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
        clear.setOnFinished(e -> markDirty());
        clear.play();
    }

    // ---------------------------------------------------------------- font & layout

    public void setFontSize(double size) {
        this.fontSize = Math.max(6, size);
        applyFont();
    }

    /**
     * Sets the face. The caller passes a monospace family (the editor's font settings already limit it to
     * those); a blank one falls back to the bundled default rather than rendering the grid in a proportional
     * face, which would misalign every column.
     */
    public void setFontFamily(String family) {
        this.fontFamily = family == null || family.isBlank() ? DEFAULT_FONT_FAMILY : family.strip();
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
        markDirty();
        requestLayout();
    }

    /** Applies a theme's colours and repaints. */
    public void setPalette(TerminalPalette palette) {
        this.palette = palette;
        this.selectionWash = washFor(palette);
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
        if (scrollBar.isManaged() != useful) {
            scrollBar.setVisible(useful);
            scrollBar.setManaged(useful);
            // Appearing or vanishing changes the width available for columns.
            requestLayout();
        }
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

    /** Width the scrollbar takes from the terminal, or 0 when it is not shown. */
    private double scrollBarWidth() {
        return scrollBar.isManaged() ? scrollBar.prefWidth(-1) : 0;
    }

    private void measureFont() {
        Text probe = new Text("M");
        probe.setFont(font);
        // Cell width is the advance of a single glyph. Valid only because the face is monospace;
        // it is why a proportional fallback would misalign every column.
        charWidth = probe.getLayoutBounds().getWidth();
        ascent = probe.getBaselineOffset();
        lineHeight = Math.ceil(fontSize * LINE_SPACING);
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
                    drawRun(g, columns.columnOf(y, x, characters), y - startRow, style, characters);
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
        g.fillRect(column * charWidth, row * lineHeight, length * charWidth, lineHeight);
    }

    private void drawRun(GraphicsContext g, int column, int row, TextStyle style, CharBuffer characters) {
        if (row < 0 || row >= rows) return;
        String text = characters.toString();
        if (text.isEmpty()) return;

        Color fg = foregroundOf(style);
        Color bg = backgroundOf(style);

        double x = column * charWidth;
        double y = row * lineHeight;
        // Cells, not characters: an astral code point is two array slots and one column, so a run
        // holding one would otherwise be painted a column too wide.
        double width = CellWidth.cells(text) * charWidth;

        if (!bg.equals(palette.background())) {
            g.setFill(bg);
            g.fillRect(x, y, width, lineHeight);
        }

        if (style.hasOption(TextStyle.Option.HIDDEN)) return;

        g.setFont(fontFor(style));
        g.setFill(fg);
        g.setTextBaseline(VPos.BASELINE);
        drawGlyphs(g, text, x, y + ascent);

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
     * <p>The all-ASCII fast path is the one that matters for throughput: it is what almost every
     * run is, and it stays a single draw call.
     */
    private void drawGlyphs(GraphicsContext g, String text, double x, double baseline) {
        if (isPlainAscii(text)) {
            g.fillText(text, x, baseline);
            return;
        }
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
                g.fillText(new String(Character.toChars(codePoint)), x + cell * charWidth, baseline);
            }
            // One column per code point, whatever its UTF-16 length. Advancing by the number of
            // array slots instead gave every astral character — every Nerd Font icon in an `ls`
            // listing — a second, empty column, pushing the rest of the line out of alignment with
            // the lines around it.  A genuinely double-width glyph gets its second column from the
            // DWC placeholder above, not from here.
            cell++;
            i += slots;
        }
    }

    private static boolean isPlainAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
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
        if (style.hasOption(TextStyle.Option.DIM)) fg = TerminalPalette.dim(fg, backgroundOf(style));
        return fg;
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
            drawGlyphs(g, predicted.substring(from), x, y + ascent);
        }
    }

    private void drawCursor(GraphicsContext g) {
        if (!display.isCursorVisible()) return;
        // JediTerm reports the cursor row 1-based against the live screen; scrolling back moves it
        // out of view rather than dragging it along.
        int row = display.getCursorY() - 1 - scrollOrigin;
        int column = session != null && session.typeAheadPrediction() != null
                ? session.typeAheadCursorColumn()
                : display.getCursorX();
        if (row < 0 || row >= rows || column < 0 || column >= columns) return;

        double x = column * charWidth;
        double y = row * lineHeight;
        g.setFill(palette.foreground());

        switch (effectiveCursorShape()) {
            case UNDERLINE -> g.fillRect(x, y + lineHeight - 2, charWidth, 2);
            case BAR -> g.fillRect(x, y, 2, lineHeight);
            default -> {
                g.fillRect(x, y, charWidth, lineHeight);
                // Re-draw the glyph underneath in the background colour so a block cursor does not
                // hide the character it is sitting on.
                TypeAheadOverlay.Prediction p = session == null ? null : session.typeAheadPrediction();
                char ch = p == null
                        ? buffer.getCharAt(column, display.getCursorY() - 1)
                        : column < p.text().length() ? p.text().charAt(column) : ' ';
                if (ch != '\0' && ch != ' ') {
                    g.setFill(palette.background());
                    g.setFont(font);
                    g.setTextBaseline(VPos.BASELINE);
                    g.fillText(String.valueOf(ch), x, y + ascent);
                }
            }
        }
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
        if (contextMenu != null && contextMenu.isShowing()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                contextMenu.hide();
                e.consume();
                return;
            }
        }
        if (session == null || !session.isRunning()) return;

        byte[] encoded = KeyEncoding.encodePressed(e, session::keyCode, altIsMeta);
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
            clearSelection();
            session.send(encoded);
            e.consume();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (session == null || !session.isRunning()) return;
        byte[] encoded = KeyEncoding.encodeTyped(e, altIsMeta);
        if (KeyTrace.enabled()) KeyTrace.log(e, encoded);
        if (encoded != null) {
            snapToLive();
            clearSelection();
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
    private java.util.function.Function<String, java.nio.file.Path> shellPaths;

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
            String path = ShellIntegration.pathOfCwdUrl(url);
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
    };

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
    public String lastCommandOutput() {
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
    private final java.util.concurrent.atomic.AtomicLong searchGeneration = new java.util.concurrent.atomic.AtomicLong();

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
        return show(query, matchCase, scan(query, matchCase, generation));
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
            state = rescanned(state, scan(state.query(), state.matchCase(), generation));
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
        scanAsync(state.query(), state.matchCase(), generation, hits -> done.accept(step(rescanned(state, hits), older)));
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
        SearchState next =
                new SearchState(query, matchCase, java.util.List.copyOf(hits), startIndex(hits, scrollOrigin + rows - 1));
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
            String query,
            boolean matchCase,
            long generation,
            java.util.function.Consumer<java.util.List<Hit>> onFx) {
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
    private java.util.List<Hit> scan(String query, boolean matchCase, long generation) {
        java.util.List<Hit> newestFirst = new java.util.ArrayList<>();
        long next; // the absolute line to scan next, going up
        buffer.lock();
        try {
            next = buffer.getHeight() - 1 + pushedLines;
        } finally {
            buffer.unlock();
        }
        while (newestFirst.size() < TerminalSearch.MAX_MATCHES) {
            if (searchGeneration.get() != generation || closed) return null;
            buffer.lock();
            try {
                long pushed = pushedLines;
                int top = -buffer.getHistoryLinesCount();
                int row = (int) Math.min(buffer.getHeight() - 1, next - pushed);
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
                buffer.unlock();
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
            g.fillRect(x * charWidth, (row - scrollOrigin) * lineHeight, (to - x) * charWidth, lineHeight);
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
        if (pressedOnScrollBar) {
            pressedOnScrollBar = false;
            return;
        }
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.RELEASED)) e.consume();
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
    private record Hover(LinkMatch match, java.nio.file.Path file, java.util.List<LinkSpan> spans) {}

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

    private LinkActions linkActions;
    private Hover hover;
    private Hover menuLink;
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
        Hover previous = hover;
        hover = modifierDown ? linkAt(x, y) : null;
        setCursor(hover == null ? javafx.scene.Cursor.DEFAULT : javafx.scene.Cursor.HAND);
        if (!sameHover(previous, hover)) {
            markDirty();
            showLinkTarget(hover, x, y);
        }
    }

    /** Shows where an OSC 8 link goes: its text is the program's to choose, and need not be its address. */
    private javafx.scene.control.Tooltip linkTip;

    private void showLinkTarget(Hover target, double x, double y) {
        boolean disguised = target != null
                && target.match().isUrl()
                && !target.match().text().equals(target.match().target());
        if (!disguised) {
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
    public String linkTipText() {
        return linkTip != null && linkTip.isShowing() ? linkTip.getText() : null;
    }

    private static boolean sameHover(Hover a, Hover b) {
        if (a == null || b == null) return a == b;
        return a.spans().equals(b.spans()) && a.match().equals(b.match());
    }

    /** The link at a point in the view, or null. Reads the buffer, so it takes the lock. */
    private Hover linkAt(double x, double y) {
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
    private LinkMatch osc8At(LogicalLine logical, int line, int slot, int index) {
        Osc8Links.Target target = osc8TargetAt(line, slot);
        if (target == null) return null;
        int start = index;
        while (start > 0 && osc8TargetAtIndex(logical, start - 1) == target) start--;
        int end = index + 1;
        while (end < logical.text().length() && osc8TargetAtIndex(logical, end) == target) end++;
        return new LinkMatch(LinkMatch.Kind.URL, logical.text().substring(start, end), start, end, target.uri(), 0, 0);
    }

    private Osc8Links.Target osc8TargetAt(int line, int slot) {
        var row = buffer.getLine(line);
        return row == null || slot < 0 || slot >= row.length() ? null : Osc8Links.targetOf(row.getStyleAt(slot));
    }

    private Osc8Links.Target osc8TargetAtIndex(LogicalLine logical, int index) {
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
    private java.nio.file.Path fileFor(LinkMatch match) {
        String target = match.target();
        if (match.kind() == LinkMatch.Kind.URL) {
            // A remote shell's file:// names a file of its machine: the tab's mapping finds it here.
            String onShell = shellPaths == null ? null : ShellIntegration.pathOfCwdUrl(target);
            java.nio.file.Path fromUri = onShell != null ? shellPaths.apply(onShell) : LinkPaths.fromFileUri(target);
            return fromUri != null && java.nio.file.Files.exists(fromUri) ? fromUri : null;
        }
        String cwd = display.getCwd();
        java.nio.file.Path resolved = shellPaths == null
                ? LinkPaths.resolve(target, cwd == null || cwd.isBlank() ? null : java.nio.file.Path.of(cwd))
                : LinkPaths.resolveOn(target, cwd, shellPaths);
        return resolved != null && java.nio.file.Files.exists(resolved) ? resolved : null;
    }

    /** Opens the hovered link. */
    private void openLink(Hover target) {
        if (target == null || linkActions == null) return;
        if (target.file() != null) {
            linkActions.openFile(
                    target.file(), target.match().line(), target.match().column());
        } else if (target.match().isUrl()) {
            linkActions.openUrl(target.match().target());
        }
    }

    /** What the link points at, for the clipboard: the URL, or the resolved file. */
    private String linkAddress(Hover target) {
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
    private LogicalLine logicalLineAt(int line) {
        // Bounded by the history that exists as well as by MAX_WRAP_ROWS: asking for row -1 with an
        // empty scrollback is not an exception but an ERROR line in JediTerm's own log, once per
        // mouse move, which turns a hover into a log flood.
        int floor = -buffer.getHistoryLinesCount();
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

    private ContextMenu contextMenu;
    private MenuItem copyOutputItem;
    private MenuItem openLinkItem;
    private MenuItem copyLinkItem;
    private SeparatorMenuItem linkSeparator;
    private MenuItem copyItem;
    private MenuItem pasteItem;
    private Function<String, Node> menuIcons = key -> null;
    private Supplier<List<MenuItem>> menuExtras = List::of;

    /**
     * Icons for the menu's own items, by key: {@code copy}, {@code paste}, {@code openLink},
     * {@code selectAll}, {@code clear}. The view has no icon set of its own; the window supplies Editora's.
     */
    public void setMenuIcons(Function<String, Node> menuIcons) {
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
     * Decides between the context menu and the running program.
     *
     * <p>Right-click is genuinely contested: a terminal wants it for Copy and Paste, while a
     * mouse-aware TUI (Midnight Commander, some file pickers) wants it as button 2 — and we report
     * it as such. The rule is the one that already governs selection: while a program has grabbed
     * the mouse it wins, and <b>Shift forces the menu</b>. Without that escape hatch there is no way
     * to copy anything from inside such a program.
     */
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

    private void onContextMenuRequested(ContextMenuEvent e) {
        // ContextMenuEvent carries no modifier state — only coordinates and whether a key raised
        // it — so shift is read from the press that triggered it.
        if (!shouldShowMenu(display.getMouseMode(), shiftOnLastPress, e.isKeyboardTrigger())) return;

        if (contextMenu == null) contextMenu = buildContextMenu();
        // Refreshed per show: the menu is built once and reused, while everything it reports on
        // moves underneath it. Copy and Paste depend on the selection and the clipboard; the
        // menu-bar checkbox on a setting the View menu, its own chord and the settings window can
        // all change while this menu sits built and invisible.
        copyItem.setDisable(!hasSelection());
        copyOutputItem.setVisible(hasPromptMarks());
        copyOutputItem.setDisable(lastCommandOutput() == null);
        pasteItem.setDisable(!Clipboard.getSystemClipboard().hasString());
        // The link items are the menu's answer to a gesture nobody was told about: they need no
        // modifier and they say what is there. Computed for the right-clicked cell, not the last
        // hovered one — the menu can be raised from the keyboard, and the pointer may be elsewhere.
        menuLink = linkAt(e.getX(), e.getY());
        boolean onLink = menuLink != null;
        openLinkItem.setVisible(onLink);
        copyLinkItem.setVisible(onLink);
        linkSeparator.setVisible(onLink);
        refreshExtras();
        contextMenu.show(this, e.getScreenX(), e.getScreenY());
        e.consume();
    }

    private ContextMenu buildContextMenu() {
        copyItem = item(tr("terminal.menu.copy"), menuIcons.apply("copy"), this::copySelection);
        pasteItem = item(tr("terminal.menu.paste"), menuIcons.apply("paste"), this::paste);
        openLinkItem = item(tr("terminal.menu.openLink"), menuIcons.apply("openLink"), () -> openLink(menuLink));
        copyLinkItem = item(tr("terminal.menu.copyLinkAddress"), menuIcons.apply("copy"), () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(linkAddress(menuLink));
            Clipboard.getSystemClipboard().setContent(content);
        });

        // Hidden together with the items they divide: a separator left behind when the pointer was
        // not over a link is a rule across the top of the menu with nothing above it.
        linkSeparator = new SeparatorMenuItem();

        ContextMenu menu = new ContextMenu(
                openLinkItem,
                copyLinkItem,
                linkSeparator,
                copyItem,
                pasteItem,
                item(tr("terminal.menu.selectAll"), menuIcons.apply("selectAll"), this::selectAll),
                copyOutputItem =
                        item(tr("terminal.menu.copyLastOutput"), menuIcons.apply("copy"), this::copyLastOutput),
                new SeparatorMenuItem(),
                item(tr("terminal.menu.clearScrollback"), menuIcons.apply("clear"), this::clearScrollback));
        menu.setAutoHide(true);
        ownItems = menu.getItems().size();
        return menu;
    }

    /** How many of the menu's items are the view's own; the window's extras follow them. */
    private int ownItems;

    /** Replaces the window's items below the view's own with what it offers now. */
    private void refreshExtras() {
        var items = contextMenu.getItems();
        items.remove(ownItems, items.size());
        List<MenuItem> extras = menuExtras.get();
        if (extras != null && !extras.isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.addAll(extras);
        }
    }

    private static MenuItem item(String text, Node icon, Runnable action) {
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
            selection = all;
            selectionAnchor = new Point(0, -history);
        } finally {
            buffer.unlock();
        }
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
        if (contextMenu != null && contextMenu.isShowing()) {
            contextMenu.hide();
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
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || buffer == null) return;

        Point at = cellAt(e);
        switch (e.getClickCount()) {
            case 2 -> selectWordAt(at);
            case 3 -> selectLineAt(at);
            default -> {
                // A press with no drag clears the selection, matching every terminal: it is how
                // you dismiss a highlight without copying it.
                selectionAnchor = at;
                selection = null;
            }
        }
        markDirty();
    }

    private void onMouseMoved(MouseEvent e) {
        lastMouseX = e.getX();
        lastMouseY = e.getY();
        updateHover(lastMouseX, lastMouseY, LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown()));
    }

    /** Follows the modifier going down or up while the pointer sits still. */
    private void onLinkModifierChanged(KeyEvent e) {
        if (lastMouseX < 0) return;
        updateHover(lastMouseX, lastMouseY, LinkGesture.opensLink(MAC, e.isMetaDown(), e.isControlDown()));
    }

    private void onKeyReleased(KeyEvent e) {
        onLinkModifierChanged(e);
    }

    private void onMouseDragged(MouseEvent e) {
        if (pressedOnScrollBar) return;
        if (reportMouse(e, com.jediterm.core.input.MouseEvent.Type.DRAGGED)) {
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY || selectionAnchor == null) return;
        Point at = cellAt(e);
        TerminalSelection current = selection;
        if (current == null) {
            // Only on the first drag event, so a plain click never leaves an empty selection.
            if (at.x == selectionAnchor.x && at.y == selectionAnchor.y) return;
            current = new TerminalSelection(new Point(selectionAnchor.x, selectionAnchor.y));
            selection = current;
        }
        current.updateEnd(at);
        markDirty();
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
        TerminalSelection word = wordSelection(at, buffer);
        selection = word;
        selectionAnchor = new Point(word.getStart().x, word.getStart().y);
    }

    private void selectLineAt(Point at) {
        TerminalSelection line = new TerminalSelection(new Point(0, at.y));
        line.updateEnd(new Point(slotsInRow(at.y), at.y));
        selection = line;
        selectionAnchor = new Point(0, at.y);
    }

    public boolean hasSelection() {
        return selection != null;
    }

    /** The selected text, or "" — the find bar starts from a one-line selection. */
    public String selectedText() {
        TerminalSelection current = selection;
        if (current == null || buffer == null) return "";
        buffer.lock();
        try {
            String text = SelectionUtil.getSelectionText(current, buffer);
            return text == null ? "" : text;
        } finally {
            buffer.unlock();
        }
    }

    /** Copies the selection, returning false when there was nothing to copy. */
    public boolean copySelection() {
        TerminalSelection current = selection;
        if (current == null || buffer == null) return false;

        String text;
        buffer.lock();
        try {
            // JediTerm's extractor, not ours: it understands that a wrapped line is one logical
            // line and must not gain a newline in the middle.
            text = SelectionUtil.getSelectionText(current, buffer);
        } finally {
            buffer.unlock();
        }
        if (text == null || text.isEmpty()) return false;

        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    public void clearSelection() {
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
        for (int row = 0; row < rows; row++) {
            int line = scrollOrigin + row;
            kotlin.Pair<Integer, Integer> span = current.intersect(0, line, slotsInRow(line));
            if (span == null) continue;
            int from = span.getFirst();
            int length = span.getSecond();
            if (length <= 0) continue;
            // The span is in slots; the rectangle is in columns.
            String text = lineTextAt(line);
            double x = ColumnMap.columnAt(text, from);
            double to = ColumnMap.columnAt(text, from + length);
            g.fillRect(x * charWidth, row * lineHeight, (to - x) * charWidth, lineHeight);
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

        int lines = (int) Math.signum(e.getDeltaY()) * 3;
        if (lines == 0) return;
        int history = buffer.getHistoryLinesCount();
        int next = Math.max(-history, Math.min(0, scrollOrigin - lines));
        if (next != scrollOrigin) {
            scrollOrigin = next;
            markDirty();
        }
        e.consume();
    }

    /** Pastes the clipboard, honouring bracketed-paste mode if the shell enabled it. */
    public void paste() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.isEmpty() || session == null) return;
        snapToLive();
        session.send(KeyEncoding.encodePaste(text, display.isBracketedPaste()));
    }

    public void setAltIsMeta(boolean altIsMeta) {
        this.altIsMeta = altIsMeta;
    }
}
