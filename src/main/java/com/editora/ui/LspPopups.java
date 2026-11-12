package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.MarkdownRenderer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspServerRegistry;
import com.editora.text.LspDiagnostic;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The caret popups of {@link LspCoordinator}: the hover card (the server's documentation, led by the problems at
 * the caret) and signature help (the overloads and the active parameter, refreshed while arguments are typed), plus
 * Smart Type completion, which reads the same signature help. Each request carries a generation and the document
 * version and caret it was made at; an answer for a tab, document or caret that has moved on is dropped. FX thread.
 */
final class LspPopups {

    /** What the popups need of {@link LspCoordinator}, resolved when called. */
    interface Host {
        @Nullable
        Path lspPath(EditorBuffer buffer);

        /** The problems published for {@code path}'s file. */
        List<LspDiagnostic> problemsOf(Path path);
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final Host lsp;
    private final LspNavigation navigation;

    /** The currently-showing LSP hover popup (dismissable), or null. */
    private @Nullable Popup hoverPopup;

    /** The currently-showing signature-help popup (#674), or null. */
    private @Nullable Popup signaturePopup;

    private long signatureGeneration;
    private long hoverGeneration;
    private @Nullable Path pendingSignaturePath;
    private final com.editora.lsp.SignatureSelection signatureSelection = new com.editora.lsp.SignatureSelection();
    private @Nullable CodeArea signatureArea;
    private long signatureRequestVersion = -1;
    private int signatureRequestCaret = -1;
    private final javafx.animation.PauseTransition signatureCaretDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));

    LspPopups(CoordinatorHost host, LspManager lspManager, LspCoordinator.Ops ops, Host lsp, LspNavigation navigation) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.lsp = lsp;
        this.navigation = navigation;
    }

    void showHover() {
        LspNavigation.NavOrigin origin = navigation.navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path path = origin.ownPath();
        navigation.syncForRequest(origin); // sync latest text before the request
        long generation = ++hoverGeneration;
        long version = b.docVersion();
        int caret = area.getCaretPosition();
        // The problems here lead the card: a lemminx error's list of what is allowed reached no keyboard user (C6).
        List<LspDiagnostic> here = path == null
                ? List.of()
                : CaretProblems.at(lsp.problemsOf(path), area.getCurrentParagraph(), area.getCaretColumn());
        java.util.function.BooleanSupplier stale = () -> b != host.activeBuffer()
                || b.isDisposed()
                || area != b.getFocusedArea()
                || generation != hoverGeneration
                || version != b.docVersion()
                || caret != area.getCaretPosition()
                || !java.util.Objects.equals(path, lsp.lspPath(b));
        lspManager.hoverAt(
                origin.anchor(),
                origin.libraryUri(),
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                text -> {
                    if (stale.getAsBoolean()) return;
                    if ((text == null || text.isBlank()) && here.isEmpty()) {
                        host.setStatus(tr("status.lsp.noHover"));
                    } else {
                        showHoverPopup(area, text == null ? "" : text, here);
                    }
                },
                failure -> {
                    if (stale.getAsBoolean()) return;
                    // The server failed, which is not "nothing here": say so (Maven review C2).
                    host.setStatus(tr("status.lsp.hoverFailed", failure));
                    if (!here.isEmpty()) {
                        showHoverPopup(area, "", here);
                    }
                });
    }

    /**
     * Shows LSP hover markdown in a dismissable popup at the caret (rendered via the Markdown renderer).
     * Closes on Escape, a click elsewhere (auto-hide), caret movement, scrolling, or another hover.
     */
    void showHoverPopup(CodeArea area, String markdown, List<LspDiagnostic> problemsHere) {
        hideHoverPopup();
        Node content;
        Node problemsNode = CaretProblems.node(problemsHere);
        if (markdown.isBlank()) {
            content = problemsNode != null ? problemsNode : new Label();
        } else
            try {
                // Links in a hover (documentation URLs) open through the window's link choke point.
                content =
                        MarkdownRenderer.renderPopup(MarkdownRenderer.parseForRender(markdown), host::openExternalUrl);
            } catch (RuntimeException e) {
                Label label = new Label(markdown);
                label.setWrapText(true);
                content = label;
            }
        if (problemsNode != null && content != problemsNode) {
            content = new VBox(10, problemsNode, new javafx.scene.control.Separator(), content);
        }
        Popup[] shown = new Popup[1];
        shown[0] = HoverCard.show(area, content, markdown, () -> {
            if (hoverPopup == shown[0]) {
                hoverPopup = null;
            }
        });
        hoverPopup = shown[0];
    }

    /**
     * Shows {@code popup} under {@code area}'s caret line, or above it when it doesn't fit below (Java review
     * A8: hover and signature help covered the line they describe near the window's bottom). The height is
     * only known once shown, so it is moved after showing.
     */
    static void showAtCaret(javafx.stage.PopupWindow popup, CodeArea area) {
        var bounds = area.getCaretBounds().orElse(null);
        if (bounds == null) {
            popup.show(area, 0, 0);
            return;
        }
        popup.show(area, bounds.getMinX(), bounds.getMaxY());
        double top = com.editora.editor.completion.CaretPopupPlacement.top(bounds, popup.getHeight());
        if (top < bounds.getMinY()) {
            popup.setAnchorY(top);
        }
    }

    /** Hides the LSP hover popup if one is showing. */
    void hideHoverPopup() {
        hoverGeneration++;
        if (hoverPopup != null) {
            hoverPopup.hide();
            hoverPopup = null;
        }
    }

    /**
     * Smart Type completion (IntelliJ's Ctrl+Shift+Space, Java only): the completion list cut down to what fits
     * the type expected at the caret. The type comes from the code ({@link com.editora.lsp.JavaExpectedType}),
     * or for a call's argument from signature help; where none is stated the full list opens, and says so.
     */
    void smartTypeCompletion() {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lsp.lspPath(b);
        if (b == null || path == null || !LspServerRegistry.JAVA_SERVER_ID.equals(lspManager.managedServerId(path))) {
            host.setStatus(tr("status.lsp.smartTypeJavaOnly"));
            if (b != null) {
                b.triggerCompletion();
            }
            return;
        }
        CodeArea area = b.getFocusedArea();
        int caret = area.getCaretPosition();
        long version = b.docVersion();
        var expected = com.editora.lsp.JavaExpectedType.at(area.getText(), caret);
        if (expected == null) {
            host.setStatus(tr("status.lsp.smartTypeUnknown"));
            b.triggerCompletion();
            return;
        }
        String type = expected.type();
        if (type != null) {
            showSmartType(b, List.of(type), expected.afterNew());
            return;
        }
        lspManager.changeDocument(path, b.text());
        lspManager.signatureHelp(path, area.getCurrentParagraph(), area.getCaretColumn(), null, false, null, help -> {
            if (b != host.activeBuffer()
                    || b.isDisposed()
                    || version != b.docVersion()
                    || caret != area.getCaretPosition()) {
                return;
            }
            // Every overload's parameter here, the active one's first: println(count) fits println(int) although
            // jdtls lists println(char[]) first (fourth review AF2). Each binds its type variables from its own
            // return type against what the line declares (AF1).
            String target = com.editora.lsp.JavaExpectedType.declaredTarget(area.getText(), caret);
            java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
            Integer argument = com.editora.lsp.JavaExpectedType.argumentIndex(area.getText(), caret);
            for (var parameter : com.editora.lsp.SignatureFormat.parametersAtCaret(help, argument)) {
                types.addAll(com.editora.lsp.JavaExpectedType.bindTypeVariables(
                        com.editora.lsp.JavaExpectedType.parameterTypes(parameter.label()),
                        target,
                        parameter.returnType()));
            }
            if (types.isEmpty()) {
                host.setStatus(tr("status.lsp.smartTypeUnknown"));
                b.triggerCompletion();
            } else {
                showSmartType(b, List.copyOf(types), expected.afterNew());
            }
        });
    }

    /** {@code types}: what the argument may be, the first named in the status (a varargs element, then its array). */
    private void showSmartType(EditorBuffer b, List<String> types, boolean afterNew) {
        String type = types.get(0);
        long others = types.stream()
                .filter(t -> !t.equals(type) && !t.equals(type + "[]"))
                .count();
        host.setStatus(
                others == 0 ? tr("status.lsp.smartType", type) : tr("status.lsp.smartTypeOverloads", type, others));
        b.triggerCompletion(items -> {
            List<com.editora.completion.Completion> kept =
                    com.editora.lsp.SmartTypeFilter.filter(items, types, afterNew);
            if (kept.isEmpty() && !items.isEmpty()) {
                Platform.runLater(() -> host.setStatus(tr("status.lsp.smartTypeNone", type)));
            }
            return kept;
        });
    }

    /**
     * Signature help (#674): the overloads + active parameter at the caret. {@code manual} is the
     * {@code lsp.signatureHelp} command (reports when unavailable); auto-trigger — a typed {@code (} or
     * {@code ,}, and the typing-pause refresh while the popup is up — stays silent. A response with no
     * signatures hides the popup, which is also how it closes once the caret leaves the call.
     */
    void signatureHelp(boolean manual) {
        signatureHelp(manual, null);
    }

    /**
     * As {@link #signatureHelp(boolean)}, with the {@code triggerChar} that fired it ({@code null} = the
     * explicit command or a typing-pause refresh). Passing it through is what lets the server see
     * {@code triggerKind=TriggerCharacter} instead of a blanket {@code Invoked} (#725); {@code isRetrigger}
     * is derived from whether a popup is already open, which servers use to keep the active overload stable
     * while arguments are typed.
     */
    void signatureHelp(boolean manual, @Nullable Character triggerChar) {
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : lsp.lspPath(b);
        if (b == null || path == null || !lspManager.isManaged(path) || !lspManager.supportsSignatureHelp(path)) {
            if (manual) {
                host.setStatus(tr("status.lsp.noSignatureHelp"));
            }
            return;
        }
        CodeArea area = b.getFocusedArea();
        boolean retrigger = signaturePopup != null; // the popup is already up for this call
        if (!manual
                && triggerChar == null
                && signatureArea == area
                && signatureRequestVersion == b.docVersion()
                && signatureRequestCaret == area.getCaretPosition()) return;
        if (signatureArea != null && signatureArea != area) hideSignaturePopup();
        lspManager.changeDocument(path, b.text()); // sync latest text before the request
        long generation = ++signatureGeneration;
        long version = b.docVersion();
        int caret = area.getCaretPosition();
        signatureArea = area;
        signatureRequestVersion = version;
        signatureRequestCaret = caret;
        pendingSignaturePath = path;
        lspManager.signatureHelp(
                path,
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                triggerChar == null ? null : String.valueOf(triggerChar),
                retrigger,
                signatureSelection.context(),
                help -> {
                    if (b != host.activeBuffer()
                            || b.isDisposed()
                            || area != b.getFocusedArea()
                            || generation != signatureGeneration
                            || version != b.docVersion()
                            || caret != area.getCaretPosition()
                            || !path.equals(lsp.lspPath(b))) return;
                    pendingSignaturePath = null;
                    signatureSelection.update(help);
                    var active = signatureSelection.active();
                    if (active == null) {
                        hideSignaturePopup(); // outside a call now — the natural close
                        if (manual) {
                            host.setStatus(tr("status.lsp.noSignatureHelp"));
                        }
                        return;
                    }
                    showSignaturePopup(area, active);
                });
    }

    void moveSignature(int delta) {
        if (signaturePopup == null || signatureArea == null) return;
        signatureSelection.move(delta);
        var active = signatureSelection.active();
        if (active != null) showSignaturePopup(signatureArea, active);
    }

    /** Refreshes on typing pause, including a first response invalidated by continued typing before
     *  the popup opened. Escape clears the pending context and must never schedule this retry. */
    void refreshSignatureHelpIfShowing() {
        EditorBuffer active = host.activeBuffer();
        if (signaturePopup != null
                || (pendingSignaturePath != null
                        && active != null
                        && !active.isDisposed()
                        && pendingSignaturePath.equals(lsp.lspPath(active)))) {
            signatureHelp(false);
        }
    }

    /**
     * Shows (replacing any previous) the signature popup above/below the caret. Unlike the hover popup it
     * survives multiline argument edits until the server reports no call; caret navigation is debounced.
     */
    private void showSignaturePopup(CodeArea area, com.editora.lsp.SignatureFormat.Active active) {
        String label = active.label();
        javafx.scene.text.Text pre = new javafx.scene.text.Text(label.substring(0, active.paramStart()));
        javafx.scene.text.Text param =
                new javafx.scene.text.Text(label.substring(active.paramStart(), active.paramEnd()));
        param.setStyle("-fx-font-weight: bold; -fx-underline: true");
        javafx.scene.text.Text post = new javafx.scene.text.Text(label.substring(active.paramEnd()));
        javafx.scene.text.TextFlow flow = new javafx.scene.text.TextFlow(pre, param, post);
        flow.setMaxWidth(560);
        VBox box = new VBox(4, flow);
        if (active.total() > 1) {
            Label count = new Label((active.index() + 1) + "/" + active.total());
            count.getStyleClass().add("lsp-signature-count");
            javafx.scene.control.Button previous = new javafx.scene.control.Button("‹");
            javafx.scene.control.Button next = new javafx.scene.control.Button("›");
            previous.setFocusTraversable(false);
            next.setFocusTraversable(false);
            // Each arrow is its command's: named after it, chord and all, for the tooltip and a screen reader.
            CommandTips.name(previous, "lsp.previousSignature");
            CommandTips.name(next, "lsp.nextSignature");
            previous.setOnAction(e -> ops.executeCommand("lsp.previousSignature"));
            next.setOnAction(e -> ops.executeCommand("lsp.nextSignature"));
            box.getChildren().add(0, new javafx.scene.layout.HBox(6, previous, count, next));
        }
        if (!active.documentation().isBlank()) {
            try {
                Node doc = MarkdownRenderer.renderPopup(
                        MarkdownRenderer.parseForRender(active.documentation()), host::openExternalUrl);
                box.getChildren().add(doc);
            } catch (RuntimeException e) {
                Label docLabel = new Label(active.documentation());
                docLabel.setWrapText(true);
                box.getChildren().add(docLabel);
            }
        }
        box.getStyleClass().add("lsp-hover-popup"); // same card styling as hover
        box.setMaxWidth(560);
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());
        com.editora.editor.PopupSheets.followOwnerTheme(
                box, area.getScene() == null ? null : area.getScene().getWindow());

        if (signaturePopup != null) {
            signaturePopup.getContent().setAll(box);
            var bounds = area.getCaretBounds().orElse(null);
            if (bounds != null) {
                signaturePopup.setAnchorX(bounds.getMinX());
                signaturePopup.setAnchorY(bounds.getMaxY());
            }
            return;
        }
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        signaturePopup = popup;

        EventHandler<KeyEvent> esc = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideSignaturePopup();
                ev.consume();
            }
        };
        ChangeListener<Object> caret = (o, a, bNew) -> {
            signatureCaretDebounce.setOnFinished(event -> refreshSignatureHelpIfShowing());
            signatureCaretDebounce.playFromStart();
        };
        javafx.event.EventHandler<javafx.scene.input.ScrollEvent> scroll = event -> hideSignaturePopup();
        ChangeListener<Boolean> focus = (o, was, now) -> {
            if (!now) hideSignaturePopup();
        };
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(caret);
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, scroll);
        area.focusedProperty().addListener(focus);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            area.caretPositionProperty().removeListener(caret);
            area.removeEventFilter(javafx.scene.input.ScrollEvent.SCROLL, scroll);
            area.focusedProperty().removeListener(focus);
            if (signaturePopup == popup) {
                signaturePopup = null;
                hideSignaturePopup();
            }
        });

        showAtCaret(popup, area);
    }

    /** Hides the signature-help popup if one is showing. */
    void hideSignaturePopup() {
        signatureGeneration++;
        pendingSignaturePath = null;
        signatureSelection.clear();
        signatureArea = null;
        signatureRequestVersion = -1;
        signatureCaretDebounce.stop();
        if (signaturePopup != null) {
            signaturePopup.hide();
            signaturePopup = null;
        }
    }
}
