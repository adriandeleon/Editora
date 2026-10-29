package com.editora.editor.notes;

import java.net.URL;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

import com.editora.config.PersonalNote;
import com.editora.editor.markdown.MarkdownRenderer;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The pointer over a buffer's Personal Notes: hovering a note's span shows its body as rendered Markdown in a
 * pale-yellow popup (updated only when the hovered note changes), and a click on a note's inline start marker
 * — the small amber triangle {@link NoteHighlightOverlay} draws — opens it for editing.
 *
 * <p>Both panes of a split editor install the same instance, so one popup serves the buffer.
 */
public final class NoteHover {

    /** Test seam: how many pointer moves were hit-tested against the notes. */
    public static final AtomicInteger HITS_FOR_TEST = new AtomicInteger();

    /** The marker's hit box on screen, from its top-left corner (it is drawn about 7px wide). */
    private static final double MARKER_HIT = 11;

    private final NoteManager notes;
    private final BooleanSupplier indicatorsShown;
    private final Consumer<PersonalNote> onMarkerClick;
    private final Supplier<String> fontFamily;
    private final IntSupplier fontSize;
    private final Supplier<@Nullable Path> baseDir;

    /** The popup (its own window and scene, so built on the first hover over a note). */
    private @Nullable Tooltip tip;
    /** The note the popup shows now. */
    private @Nullable UUID shownId;

    /**
     * @param notes           the buffer's notes
     * @param indicatorsShown whether the markers and highlight are on (the {@code showNoteIndicators} setting)
     * @param onMarkerClick   what a click on a note's start marker does (the window opens the note editor)
     * @param fontFamily      the editor font, the base of the rendered body
     * @param fontSize        the editor font size
     * @param baseDir         the folder relative images in a body resolve against (null: none)
     */
    public NoteHover(
            NoteManager notes,
            BooleanSupplier indicatorsShown,
            Consumer<PersonalNote> onMarkerClick,
            Supplier<String> fontFamily,
            IntSupplier fontSize,
            Supplier<@Nullable Path> baseDir) {
        this.notes = notes;
        this.indicatorsShown = indicatorsShown;
        this.onMarkerClick = onMarkerClick;
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.baseDir = baseDir;
    }

    /** Hooks the hover popup and the marker click into {@code a}. */
    public void install(CodeArea a) {
        a.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            // Every buffer has this filter and almost none has a note: without one on screen there is nothing to
            // hit-test the pointer against.
            if (!indicatorsShown.getAsBoolean() || !notes.hasActiveSpans()) {
                hide();
                return;
            }
            HITS_FOR_TEST.incrementAndGet();
            PersonalNote n;
            try {
                // MOUSE_MOVED bubbles from RichTextFX's paragraph/text nodes. MouseEvent#getX/Y are
                // relative to that original event source, not reliably to the CodeArea on which this
                // filter is installed, so feeding them directly to CodeArea.hit() misses the note (and
                // changes as the pointer crosses child nodes). Screen coordinates are stable across the
                // dispatch chain; translate those back into the area's coordinate space first.
                Point2D local = a.screenToLocal(e.getScreenX(), e.getScreenY());
                n = notes.noteAt(a.hit(local.getX(), local.getY()).getInsertionIndex());
            } catch (RuntimeException ex) {
                n = null;
            }
            if (n == null || n.body().isBlank()) {
                hide();
            } else if (!n.id().equals(shownId)) {
                shownId = n.id();
                if (tip == null) {
                    tip = PersonalNoteTooltip.create();
                }
                tip.setText(null);
                tip.setGraphic(render(n.body()));
                if (tip.isShowing()) {
                    tip.hide();
                }
                tip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
            }
        });
        a.addEventFilter(MouseEvent.MOUSE_EXITED, e -> hide());
        // Clicking the inline note marker (the ~7px amber triangle at a note's start) opens the editor —
        // restoring the click-to-edit the gutter glyph used to give. A click elsewhere is untouched.
        a.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (!indicatorsShown.getAsBoolean()
                    || e.getButton() != MouseButton.PRIMARY
                    || e.getClickCount() != 1
                    || !notes.hasActiveSpans()) {
                return;
            }
            int[] window = visibleOffsets(a);
            for (int[] span : notes.activeSpans()) {
                if (window == null || span[0] < window[0] || span[0] > window[1]) {
                    continue; // its marker is off screen: no layout query for it
                }
                Bounds scr;
                try {
                    scr = NoteHighlightOverlay.markerBoundsOnScreen(a, span[0]);
                } catch (RuntimeException ex) {
                    scr = null;
                }
                if (scr == null) {
                    continue;
                }
                if (e.getScreenX() >= scr.getMinX()
                        && e.getScreenX() <= scr.getMinX() + MARKER_HIT
                        && e.getScreenY() >= scr.getMinY()
                        && e.getScreenY() <= scr.getMinY() + MARKER_HIT) {
                    PersonalNote n = notes.noteAt(span[0]);
                    if (n != null) {
                        onMarkerClick.accept(n);
                        e.consume();
                    }
                    return;
                }
            }
        });
    }

    /** Hides the popup (the indicators went off, or the pointer left the note). */
    public void hide() {
        shownId = null;
        if (tip != null && tip.isShowing()) {
            tip.hide();
        }
    }

    /** The document offsets {@code [first, last]} of the paragraphs {@code a} shows, or null mid-layout. */
    private static int @Nullable [] visibleOffsets(CodeArea a) {
        try {
            int total = a.getParagraphs().size();
            int first = Math.max(0, a.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, a.lastVisibleParToAllParIndex());
            return new int[] {a.getAbsolutePosition(first, 0), a.getAbsolutePosition(last, a.getParagraphLength(last))};
        } catch (RuntimeException midLayout) {
            return null;
        }
    }

    /**
     * Renders a note body as the popup's graphic: the body is parsed as Markdown ({@link MarkdownRenderer}) so
     * formatting shows in the popup, the editor's own font family/size is applied as the base font, and the app
     * + syntax stylesheets are attached to the node (the tooltip lives in its own popup scene) so the
     * {@code .markdown-preview} rules resolve. Falls back to a plain wrapped label if rendering fails.
     */
    private Node render(String body) {
        Node node;
        try {
            node = MarkdownRenderer.renderPopup( // a popup: nothing in it takes the editor's keys
                    MarkdownRenderer.parseForRender(body), baseDir.get(), null, MarkdownRenderer.ImagePolicy.DOCUMENT);
        } catch (RuntimeException ex) {
            Label fallback = new Label(body);
            fallback.setWrapText(true);
            node = fallback;
        }
        node.getStyleClass().add("personal-note-tooltip-content");
        node.setStyle("-fx-font-family: \"" + fontFamily.get() + "\"; -fx-font-size: " + fontSize.getAsInt() + "px;");
        if (node instanceof Parent parent) {
            addStylesheet(parent, "/com/editora/styles/app.css");
            addStylesheet(parent, "/com/editora/styles/syntax.css");
        }
        if (node instanceof Region region) {
            // Pin the node to a definite size so the tooltip hugs the rendered content. A TextFlow inside a
            // tooltip otherwise computes its height at a near-zero width (one char per line → a tall, empty
            // popup). Measure in a throwaway Scene so the inline font + stylesheets actually apply (a
            // detached node measures at the default font and mis-sizes): prefWidth(-1) is the natural
            // one-line width (capped so long notes wrap), then the height at that width — so the box matches
            // the rendered text (measuring the raw Markdown source would over-size it by the markup chars).
            Scene measureScene = new Scene(region);
            region.applyCss();
            region.layout();
            double width = Math.min(480, Math.ceil(region.prefWidth(-1)));
            region.setPrefWidth(width);
            region.setMaxWidth(width);
            double height = Math.ceil(region.prefHeight(width)) + 1;
            measureScene.setRoot(new Group()); // release the node to reuse as the tooltip graphic
            region.setMinHeight(height);
            region.setPrefHeight(height);
            region.setMaxHeight(height);
        }
        return node;
    }

    private static void addStylesheet(Parent parent, String resource) {
        URL url = NoteHover.class.getResource(resource);
        if (url != null) {
            parent.getStylesheets().add(url.toExternalForm());
        }
    }
}
