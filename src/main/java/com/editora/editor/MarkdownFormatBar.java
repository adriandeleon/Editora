package com.editora.editor;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import static com.editora.i18n.Messages.tr;

/**
 * The floating IntelliJ-style format bar shown above a non-empty selection in a Markdown buffer. It is a
 * plain (non-focus-traversable, free-positioned) {@link HBox} of flat buttons plus a Normal/H1–H6
 * paragraph-style dropdown; each control calls back into the owning {@link EditorBuffer}'s format
 * methods. Kept in the {@code editor} package (no dependency on {@code ui}), like {@code CompletionPopup}.
 * {@link EditorBuffer} owns its show/hide/position lifecycle.
 */
final class MarkdownFormatBar {

    private static final javafx.css.PseudoClass SELECTED = javafx.css.PseudoClass.getPseudoClass("selected");
    /** Set on a view while the primary button is down in it: a selection being dragged out is not finished. */
    private static final String DRAGGING = "editora.formatBar.dragging";

    private static final double GAP = 4;

    private final HBox node = new HBox();
    private final EditorBuffer buffer;
    /** The inline markers whose buttons light up when the selection already carries them. */
    private final java.util.Map<Button, String> toggles = new java.util.LinkedHashMap<>();

    private ComboBox<Integer> heading;
    /** True while {@link #sync} sets the Style box, so that is not taken for the user picking a level. */
    private boolean syncing;

    MarkdownFormatBar(EditorBuffer buffer) {
        this.buffer = buffer;
        node.getStyleClass().add("markdown-format-bar");
        node.setManaged(false); // free-positioned by EditorBuffer via resizeRelocate
        node.setFocusTraversable(false);
        node.setSpacing(2);
        node.setPadding(new Insets(3));
        node.setAlignment(Pos.CENTER_LEFT);

        // Icon glyphs (the same MenuIcons used by the editor right-click menu) instead of letter labels —
        // an italic "I" barely slants, and icons keep the bar consistent with the menu. Typst uses a
        // different markup set (single-* bold, _emph_, no native strikethrough/task/pipe-table), so its bar
        // carries bold/emphasis/raw/link/bullet + the heading dropdown; the heading/link cores dispatch on
        // the buffer type in EditorBuffer.
        if (buffer.isTypst()) {
            node.getChildren()
                    .addAll(
                            toggle(MenuIcons.bold(), "tooltip.markdown.bold", "*"),
                            toggle(MenuIcons.italic(), "tooltip.markdown.italic", "_"),
                            toggle(MenuIcons.code(), "tooltip.markdown.code", "`"),
                            button(MenuIcons.link(), "tooltip.markdown.link", buffer::formatLinkFromClipboard),
                            button(MenuIcons.bulletList(), "tooltip.markdown.bulletList", buffer::formatBulletList),
                            button(
                                    MenuIcons.table(),
                                    "tooltip.markdown.insertTable",
                                    buffer::insertTypstTableInteractive),
                            new Separator(javafx.geometry.Orientation.VERTICAL),
                            headingBox(buffer));
        } else {
            node.getChildren()
                    .addAll(
                            toggle(MenuIcons.bold(), "tooltip.markdown.bold", "**"),
                            toggle(MenuIcons.italic(), "tooltip.markdown.italic", "*"),
                            toggle(MenuIcons.strikethrough(), "tooltip.markdown.strikethrough", "~~"),
                            toggle(MenuIcons.code(), "tooltip.markdown.code", "`"),
                            button(MenuIcons.link(), "tooltip.markdown.link", buffer::formatLinkFromClipboard),
                            button(MenuIcons.bulletList(), "tooltip.markdown.bulletList", buffer::formatBulletList),
                            button(MenuIcons.taskList(), "tooltip.markdown.taskList", buffer::formatTaskList),
                            button(MenuIcons.table(), "tooltip.markdown.insertTable", buffer::insertTableInteractive),
                            new Separator(javafx.geometry.Orientation.VERTICAL),
                            headingBox(buffer));
        }
    }

    Node node() {
        return node;
    }

    /**
     * Whether placing {@code bar} (which may not exist yet) can change anything: it could come up — the
     * feature is on and {@code view} has a selection — or it is up and may have to move or go away. The
     * buffer asks before queueing an update, because the triggers are every scroll event and caret move.
     */
    static boolean updateNeeded(MarkdownFormatBar bar, boolean enabled, org.fxmisc.richtext.CodeArea view) {
        return view == null
                || (enabled && view.getSelection().getLength() > 0)
                || (bar != null && bar.node.isVisible());
    }

    /**
     * Keeps the bar away while a selection is still being dragged out with the mouse: it comes up on
     * release ({@code onRelease} asks for the update), not over the text the pointer is heading for.
     */
    static void trackMouse(org.fxmisc.richtext.CodeArea view, Runnable onRelease) {
        view.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                view.getProperties().put(DRAGGING, Boolean.TRUE);
            }
        });
        view.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (view.getProperties().remove(DRAGGING) != null) {
                onRelease.run();
            }
        });
    }

    /** Whether {@code view}'s selection calls for the bar: some text other than blanks, and not mid-drag. */
    static boolean wanted(org.fxmisc.richtext.CodeArea view) {
        return view.getSelection().getLength() > 0
                && !view.getProperties().containsKey(DRAGGING)
                && !view.getSelectedText().isBlank();
    }

    /**
     * Where the bar's top edge goes for a caret line spanning {@code [top, bottom]} in a pane {@code paneH}
     * tall. The bar sits next to the caret on the side the selection is not on — {@code below} when the
     * selection runs upward from the caret — and takes the other side only when that one has no room, so it
     * covers the selected text only when there is nowhere else to be. Pure.
     */
    static double placeY(double top, double bottom, double barH, double paneH, boolean below) {
        double above = top - barH - GAP;
        double under = bottom + GAP;
        boolean aboveFits = above >= 0;
        boolean underFits = under + barH <= paneH;
        if (below) {
            return underFits || !aboveFits ? under : above;
        }
        return aboveFits || !underFits ? above : under;
    }

    /** Shows the bar for {@code view}'s selection inside {@code pane}; false when the caret is not on screen. */
    boolean showFor(org.fxmisc.richtext.CodeArea view, javafx.scene.layout.AnchorPane pane) {
        javafx.geometry.Bounds screen = view.getCaretBounds().orElse(null);
        javafx.geometry.Bounds local = screen == null ? null : pane.screenToLocal(screen);
        if (local == null) {
            node.setVisible(false);
            return false;
        }
        if (node.getParent() != pane) {
            if (node.getParent() instanceof javafx.scene.layout.AnchorPane old) {
                old.getChildren().remove(node);
            }
            pane.getChildren().add(node);
        }
        sync();
        node.setVisible(true);
        node.applyCss();
        double w = node.prefWidth(-1);
        double h = node.prefHeight(-1);
        var sel = view.getSelection();
        boolean oneLine = view.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                        .getMajor()
                == view.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                        .getMajor();
        boolean below = !oneLine && view.getCaretPosition() >= sel.getEnd();
        double x = Math.max(0, Math.min(local.getMinX(), Math.max(0, pane.getWidth() - w)));
        node.resizeRelocate(x, placeY(local.getMinY(), local.getMaxY(), h, pane.getHeight(), below), w, h);
        node.toFront();
        return true;
    }

    /** Makes the controls say what the selection already is: lit marker buttons, the line's heading level. */
    private void sync() {
        toggles.forEach((b, marker) ->
                b.pseudoClassStateChanged(SELECTED, buffer.markdown().isMarked(marker)));
        syncing = true;
        try {
            heading.setValue(buffer.markdown().headingLevel());
        } finally {
            syncing = false;
        }
    }

    private Button toggle(Node icon, String tooltipKey, String marker) {
        Button b = button(icon, tooltipKey, () -> buffer.formatInline(marker));
        toggles.put(b, marker);
        return b;
    }

    private static Button button(Node icon, String tooltipKey, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "md-fmt-btn");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tr(tooltipKey)));
        b.setAccessibleText(tr(tooltipKey)); // icon-only: the tooltip text is its name
        b.setOnAction(e -> action.run());
        return b;
    }

    private ComboBox<Integer> headingBox(EditorBuffer buffer) {
        ComboBox<Integer> box = new ComboBox<>(FXCollections.observableArrayList(0, 1, 2, 3, 4, 5, 6));
        box.getStyleClass().add("md-fmt-heading");
        box.setFocusTraversable(false);
        box.setPromptText(tr("markdown.heading.style"));
        box.setAccessibleText(tr("markdown.heading.style"));
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer n) {
                if (n == null) {
                    return "";
                }
                return n == 0 ? tr("markdown.heading.normal") : tr("markdown.heading.h" + n);
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        box.setOnAction(e -> {
            Integer v = box.getValue();
            if (v != null && !syncing) {
                buffer.setHeadingLevel(v);
            }
        });
        heading = box;
        return box;
    }
}
