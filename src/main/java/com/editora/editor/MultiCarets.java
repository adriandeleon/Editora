package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.event.EventDispatcher;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.fxmisc.richtext.CaretNode;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.Selection;
import org.fxmisc.richtext.multi.MultiCaretController;
import org.fxmisc.richtext.multi.MultiCaretManager;
import org.reactfx.Subscription;

/**
 * One area's multiple-cursor add-on: the RichTextFX fork's {@link MultiCaretController} plus the Editora-side
 * guards the fork's manager needs.
 *
 * <p><b>Stale anchors.</b> The manager keeps each extra caret's selection anchor as a plain number that only
 * its own edits rewrite. RichTextFX moves the caret and its selection when the document changes, but not
 * that number, so after <em>any other</em> edit — Undo, a command that edits at the primary caret, a
 * language-server edit, a reload — every extra caret believed it had a selection reaching back to where
 * its anchor used to be, and the next key typed over text nobody selected. The manager offers no way to
 * read or reset an anchor, so this class notices an edit that did not come from the manager and rebuilds
 * the extra carets from the positions RichTextFX did keep right ({@link #syncAnchors}) before the manager
 * is next used.
 *
 * <p><b>Whole characters.</b> The manager steps and deletes one UTF-16 unit at a time, which strands a caret
 * inside a surrogate pair and deletes half of one. Backspace, Delete and the horizontal arrow keys are
 * taken here instead and moved by code point, as the single-caret keys are.
 *
 * <p><b>Add caret above/below</b> starts from the outermost caret in that direction rather than from the
 * primary one, so a second press reaches a third line.
 *
 * <p>All methods run on the FX thread.
 */
final class MultiCarets {

    /** The names the fork gives the carets and selections it creates, numbered alike for a pair. */
    private static final String CARET_PREFIX = "multi-caret-";

    private static final String SELECTION_PREFIX = "multi-selection-";

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    private final CodeArea area;
    private final MultiCaretController<?, ?, ?> controller;
    private final MultiCaretManager<?, ?, ?> manager;
    private final EventDispatcher stockDispatcher;
    private final Subscription changes;
    private final EventHandler<KeyEvent> onPressed = this::keyPressed;
    private final EventHandler<KeyEvent> onTyped = this::keyTyped;
    /** Alt+drag over wrapped paragraphs, which the fork's box selection treats as one line each. */
    private final WrapBoxSelection wrapBox;

    /** Non-zero while the manager itself is editing: that edit leaves every anchor right. */
    private int managerEdit;

    private boolean anchorsStale;
    private boolean syncQueued;

    private MultiCarets(CodeArea area) {
        this.area = area;
        this.controller = MultiCaretController.install(area);
        this.manager = controller.getManager();
        this.stockDispatcher = area.getEventDispatcher();
        // The fork edits from its own key bindings, which nothing here can wrap; the dispatcher can, so a
        // change made while one of those keys is being delivered is known to be the manager's own.
        area.setEventDispatcher((event, tail) -> {
            boolean managerKey = event instanceof KeyEvent key && manager.hasExtras() && managerEdits(key);
            if (!managerKey) {
                return stockDispatcher.dispatchEvent(event, tail);
            }
            managerEdit++;
            try {
                return stockDispatcher.dispatchEvent(event, tail);
            } finally {
                managerEdit--;
            }
        });
        this.changes = area.multiPlainChanges().subscribe(c -> textChanged());
        area.addEventFilter(KeyEvent.KEY_PRESSED, onPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, onTyped);
        this.wrapBox = WrapBoxSelection.install(area, manager);
    }

    static MultiCarets install(CodeArea area) {
        return new MultiCarets(area);
    }

    void dispose() {
        changes.unsubscribe();
        wrapBox.dispose();
        area.removeEventFilter(KeyEvent.KEY_PRESSED, onPressed);
        area.removeEventFilter(KeyEvent.KEY_TYPED, onTyped);
        area.setEventDispatcher(stockDispatcher);
        controller.dispose();
    }

    /** The fork's manager. Used directly only to place carets; edits and moves go through this class. */
    MultiCaretManager<?, ?, ?> getManager() {
        return manager;
    }

    boolean hasExtras() {
        return manager.hasExtras();
    }

    /** Ctrl/Cmd+D: selects the next occurrence of the primary selection as another caret. */
    void addNextOccurrence() {
        syncAnchors(); // it searches from the furthest selection end, anchors included
        manager.addNextOccurrence();
    }

    void collapse() {
        manager.collapseToPrimary();
        anchorsStale = false;
    }

    // --- Anchors ---------------------------------------------------------------------------------

    private void textChanged() {
        if (managerEdit > 0 || !manager.hasExtras()) {
            return;
        }
        anchorsStale = true;
        if (!syncQueued) { // also tidy up soon, so carets a big edit piled onto one spot merge without a key
            syncQueued = true;
            Platform.runLater(() -> {
                syncQueued = false;
                syncAnchors();
            });
        }
    }

    /**
     * Whether the fork's own bindings edit on this key while extra carets exist — the bindings of its
     * {@code MultiCaretInputMap}, modifier for modifier. Anything else that changes the text during the key
     * (the stock handler for Shift+Enter, say) edits at the primary caret only and must count as foreign.
     */
    static boolean managerEdits(KeyEvent e) {
        if (e.getEventType() == KeyEvent.KEY_TYPED) {
            String ch = e.getCharacter();
            return ch != null
                    && !ch.isEmpty()
                    && ch.charAt(0) >= 0x20
                    && ch.charAt(0) != 0x7F
                    && !e.isControlDown()
                    && !e.isMetaDown();
        }
        if (e.getEventType() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        boolean none = !e.isShiftDown() && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown();
        // The fork's word-delete modifier: Option on macOS, the shortcut key elsewhere.
        boolean wordOnly = !e.isShiftDown()
                && (MAC
                        ? e.isAltDown() && !e.isControlDown() && !e.isMetaDown()
                        : e.isShortcutDown() && !e.isAltDown());
        return switch (e.getCode()) {
            case ENTER, TAB -> none;
            case BACK_SPACE, DELETE -> none || wordOnly;
            case V, X -> e.isShortcutDown() && !e.isShiftDown();
            default -> false;
        };
    }

    /** One extra caret: where its selection is anchored and where the caret is. */
    private record Extra(int id, int anchor, int caret) {}

    /** The extra carets as RichTextFX tracks them, in the order they were created. */
    private List<Extra> extras() {
        List<Extra> out = new ArrayList<>(manager.extraCount());
        java.util.Map<String, Selection<?, ?, ?>> selections = new java.util.HashMap<>();
        for (Selection<?, ?, ?> s : area.getSelectionSet()) {
            String name = s.getSelectionName();
            if (name != null && name.startsWith(SELECTION_PREFIX)) {
                selections.put(name.substring(SELECTION_PREFIX.length()), s);
            }
        }
        for (CaretNode c : area.getCaretSet()) {
            String name = c.getCaretName();
            if (name == null || !name.startsWith(CARET_PREFIX)) {
                continue;
            }
            String id = name.substring(CARET_PREFIX.length());
            int caret = c.getPosition();
            int anchor = caret;
            Selection<?, ?, ?> s = selections.get(id);
            if (s != null && s.getLength() > 0) {
                // The caret sits at one end of its selection; the anchor is the other. A caret an edit
                // pushed off both ends has no meaningful selection left.
                if (caret == s.getEndPosition()) {
                    anchor = s.getStartPosition();
                } else if (caret == s.getStartPosition()) {
                    anchor = s.getEndPosition();
                }
            }
            out.add(new Extra(parseId(id), anchor, caret));
        }
        out.sort(java.util.Comparator.comparingInt(Extra::id));
        return out;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Replaces the extra carets with {@code extras}, which gives every one a fresh, correct anchor. */
    private void rebuild(List<Extra> extras) {
        int length = area.getLength();
        manager.collapseToPrimary();
        for (Extra x : extras) {
            manager.addCaretWithSelection(Math.clamp(x.anchor(), 0, length), Math.clamp(x.caret(), 0, length));
        }
        manager.mergeCoincident();
        anchorsStale = false;
    }

    /** Makes the manager's anchors match the document again after an edit it did not make. Cheap otherwise. */
    void syncAnchors() {
        if (!anchorsStale) {
            return;
        }
        if (manager.hasExtras()) {
            rebuild(extras());
        }
        anchorsStale = false;
    }

    /** Runs a manager edit: anchors are made right first, and the change it makes is not taken as foreign. */
    private void edit(Runnable op) {
        syncAnchors();
        managerEdit++;
        try {
            op.run();
        } finally {
            managerEdit--;
        }
    }

    /**
     * Runs {@code op} — Undo or Redo — keeping the primary caret with its text. RichTextFX puts the primary
     * caret at the end of the last change it replays, which with several carets is some other caret's place:
     * two carets then sat on one spot and the primary's own was lost.
     */
    void keepingPrimary(Runnable op) {
        if (!manager.hasExtras()) {
            op.run();
            return;
        }
        // Not added to the area: a caret follows the document's edits from the moment it is created.
        CaretNode marker = new CaretNode("editora-primary-marker", area, area.getCaretPosition());
        int at;
        try {
            op.run();
        } finally {
            at = marker.getPosition();
            marker.dispose();
        }
        area.moveTo(Math.clamp(at, 0, area.getLength()));
        anchorsStale = true;
        syncAnchors();
    }

    // --- Clipboard ---------------------------------------------------------------------------------

    /** Whether any caret, primary or extra, has text selected. */
    boolean anySelection() {
        if (area.getSelection().getLength() > 0) {
            return true;
        }
        for (Selection<?, ?, ?> s : area.getSelectionSet()) {
            String name = s.getSelectionName();
            if (name != null && name.startsWith(SELECTION_PREFIX) && s.getLength() > 0) {
                return true;
            }
        }
        return false;
    }

    void copy() {
        syncAnchors();
        manager.copy();
    }

    void cut() {
        edit(manager::cut);
    }

    void paste() {
        edit(manager::paste);
    }

    // --- Movement and deletion by whole character ---------------------------------------------------

    void moveHorizontal(int amount, boolean byWord, boolean select) {
        syncAnchors();
        manager.moveHorizontal(amount, byWord, select);
        if (!byWord) {
            leaveSurrogatePairs(amount > 0);
        }
    }

    void moveVertical(boolean down, boolean select) {
        syncAnchors();
        manager.moveVertical(down, select);
    }

    void moveLineBoundary(boolean toEnd, boolean select) {
        syncAnchors();
        manager.moveLineBoundary(toEnd, select);
    }

    /** True when {@code pos} falls between the two halves of a surrogate pair. */
    private boolean splitsPair(int pos) {
        if (pos <= 0 || pos >= area.getLength()) {
            return false;
        }
        String around = area.getText(pos - 1, pos + 1);
        return Character.isHighSurrogate(around.charAt(0)) && Character.isLowSurrogate(around.charAt(1));
    }

    /** After a one-unit move: carries any caret that stopped inside a surrogate pair on across it. */
    private void leaveSurrogatePairs(boolean forward) {
        int step = forward ? 1 : -1;
        int primary = area.getCaretPosition();
        if (splitsPair(primary)) {
            int anchor = area.getAnchor();
            // A collapsed caret stays collapsed; an extended selection keeps its anchor.
            area.selectRange(anchor == primary ? primary + step : anchor, primary + step);
        }
        List<Extra> extras = null;
        if (manager.hasExtras()) {
            List<Extra> now = extras();
            for (int i = 0; i < now.size(); i++) {
                Extra x = now.get(i);
                if (splitsPair(x.caret())) {
                    if (extras == null) {
                        extras = new ArrayList<>(now);
                    }
                    int moved = x.caret() + step;
                    extras.set(i, new Extra(x.id(), x.anchor() == x.caret() ? moved : x.anchor(), moved));
                }
            }
        }
        if (extras != null) {
            rebuild(extras);
        } else {
            manager.mergeCoincident();
        }
    }

    /**
     * Backspace ({@code forward == false}) or Delete at every caret. A caret with nothing selected first
     * selects the whole code point beside it when that is a surrogate pair, so the manager — which removes a
     * selection whole but otherwise one UTF-16 unit — never leaves half a character behind.
     */
    void deleteChar(boolean forward) {
        syncAnchors();
        int length = area.getLength();
        int primary = area.getCaretPosition();
        if (area.getSelection().getLength() == 0) {
            int other = pairEdge(primary, forward, length);
            if (other != primary) {
                area.selectRange(other, primary);
            }
        }
        List<Extra> extras = null;
        List<Extra> now = extras();
        for (int i = 0; i < now.size(); i++) {
            Extra x = now.get(i);
            int other = x.anchor() == x.caret() ? pairEdge(x.caret(), forward, length) : x.caret();
            if (other != x.caret()) {
                if (extras == null) {
                    extras = new ArrayList<>(now);
                }
                extras.set(i, new Extra(x.id(), other, x.caret()));
            }
        }
        if (extras != null) {
            rebuild(extras);
        }
        edit(forward ? manager::deleteNextChar : manager::deletePrevChar);
    }

    /** The far edge of the surrogate pair next to {@code pos} in the given direction, else {@code pos}. */
    private int pairEdge(int pos, boolean forward, int length) {
        if (forward) {
            return pos + 2 <= length && splitsPair(pos + 1) ? pos + 2 : pos;
        }
        return pos >= 2 && splitsPair(pos - 1) ? pos - 2 : pos;
    }

    private void keyTyped(KeyEvent e) {
        if (manager.hasExtras()) {
            syncAnchors(); // before the fork's binding types at every caret
        }
    }

    private void keyPressed(KeyEvent e) {
        if (e.isConsumed()) {
            return;
        }
        KeyCode code = e.getCode();
        boolean vertical = code == KeyCode.UP || code == KeyCode.DOWN;
        if (vertical && e.isAltDown() && e.isShortcutDown() && !e.isShiftDown()) {
            addCaretOnNextLine(code == KeyCode.DOWN); // the fork's own chord, which starts from the primary caret
            e.consume();
            return;
        }
        if (!manager.hasExtras()) {
            return;
        }
        syncAnchors(); // before any binding of the fork's acts on this key
        if (!manager.hasExtras() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        switch (code) {
            case LEFT -> moveHorizontal(-1, false, e.isShiftDown());
            case RIGHT -> moveHorizontal(1, false, e.isShiftDown());
            case BACK_SPACE, DELETE -> {
                if (e.isShiftDown() || !area.isEditable()) {
                    return;
                }
                deleteChar(code == KeyCode.DELETE);
            }
            default -> {
                return;
            }
        }
        e.consume();
    }

    // --- Add caret above / below ---------------------------------------------------------------------

    /**
     * Adds a caret one line below ({@code down}) or above the outermost caret in that direction, in the
     * primary caret's column. The fork's own version measures from the primary caret every time, so its
     * second press lands on the caret the first one added and does nothing.
     */
    void addCaretOnNextLine(boolean down) {
        syncAnchors();
        CaretNode primary = area.getCaretSelectionBind().getUnderlyingCaret();
        CaretNode edge = primary;
        for (CaretNode c : area.getCaretSet()) {
            String name = c.getCaretName();
            if (name == null || !name.startsWith(CARET_PREFIX)) {
                continue;
            }
            if (down ? c.getPosition() > edge.getPosition() : c.getPosition() < edge.getPosition()) {
                edge = c;
            }
        }
        int target = hitNextLine(primary, edge, down);
        if (target < 0) { // the edge caret is scrolled out of view: no geometry, so go by paragraph and column
            int paragraph = edge.getParagraphIndex() + (down ? 1 : -1);
            if (paragraph < 0 || paragraph >= area.getParagraphs().size()) {
                return;
            }
            int column = Math.min(primary.getColumnPosition(), area.getParagraphLength(paragraph));
            target = area.getAbsolutePosition(paragraph, column);
            area.showParagraphInViewport(paragraph);
        }
        boolean above = target < edge.getPosition();
        if (target == edge.getPosition() || above == down) {
            return; // no further line that way
        }
        manager.addCaretAt(target);
    }

    /** The offset one visual line beyond {@code edge}, at {@code primary}'s x; -1 when it cannot be measured. */
    private int hitNextLine(CaretNode primary, CaretNode edge, boolean down) {
        Optional<Bounds> edgeScreen = area.getCaretBoundsOnScreen(edge);
        Optional<Bounds> primaryScreen = area.getCaretBoundsOnScreen(primary);
        if (edgeScreen.isEmpty() || primaryScreen.isEmpty()) {
            return -1;
        }
        Bounds at = area.screenToLocal(edgeScreen.get());
        Bounds column = area.screenToLocal(primaryScreen.get());
        if (at == null || column == null) {
            return -1;
        }
        double h = Math.max(1, at.getHeight());
        double y = down ? at.getMaxY() + h * 0.5 : at.getMinY() - h * 0.5;
        if (y < 0 || y > area.getHeight()) {
            return -1; // past the viewport edge: a hit there answers with the last visible line
        }
        return area.hit(column.getMinX(), y).getInsertionIndex();
    }
}
