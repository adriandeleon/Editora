package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import javafx.application.Platform;

import com.editora.editops.HtmlLinks;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.vfs.Vfs;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Go to Definition and Ctrl-click on an HTML page's {@code href}/{@code src} (HTML review B6, C8, D4): the file
 * opens — at the element whose {@code id} the link's {@code #fragment} names — a {@code #fragment} alone moves in
 * the page, a web address goes to the browser after asking, and a missing file says so ("File not found:
 * missing.html") rather than "No definition found".
 *
 * <p>The HTML server's definition answers only for embedded JavaScript, so this is what runs when it found
 * nothing. Where the server runs, its document link at the caret is taken (it resolves a root-relative link
 * against the workspace folder); without one — or LSP off — the value is resolved against the page itself.
 */
final class HtmlLinkFollow {

    /** A fragment's anchor is looked for only in a file up to this size. */
    private static final long MAX_ANCHOR_SCAN_BYTES = 5_000_000;

    private static final ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "html-link-follow");
        t.setDaemon(true);
        return t;
    });

    private final CoordinatorHost host;
    private final LspManager lsp;
    private final LspCoordinator.Ops ops;

    /** Asks before a web address goes to the browser; a field so a test can answer. */
    Predicate<String> webConfirmer = this::confirmWeb;

    HtmlLinkFollow(CoordinatorHost host, LspManager lsp, LspCoordinator.Ops ops) {
        this.host = host;
        this.lsp = lsp;
        this.ops = ops;
    }

    /**
     * Follows the link the active HTML buffer's caret is in. False when the buffer is not HTML or the caret is
     * not in an {@code href}/{@code src} value, so the caller's own answer (the symbol index, "No definition
     * found") stands.
     */
    boolean followAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !HtmlLinks.isHtml(b.getLanguage())) {
            return false;
        }
        CodeArea a = b.getFocusedArea();
        String text = b.text();
        HtmlLinks.Link link = HtmlLinks.at(text, a.getCaretPosition());
        if (link == null) {
            return false;
        }
        Path file = b.getPath();
        HtmlLinks.Target local = file == null
                ? fragmentOrWeb(link.value())
                : HtmlLinks.resolve(file, ops.lspProjectRoot(), link.value(), HtmlLinks.baseHref(text));
        if (file != null && lsp.isManaged(file) && local.kind() == HtmlLinks.Kind.FILE) {
            int line = a.getCurrentParagraph();
            int column = a.getCaretColumn();
            lsp.documentLinkTarget(file, line, column, server -> {
                if (host.activeBuffer() == b) {
                    go(b, server != null ? server : local, link.value());
                }
            });
        } else {
            go(b, local, link.value());
        }
        return true;
    }

    /** An untitled page's link: only a fragment or a web address means anything without a folder. */
    private static HtmlLinks.Target fragmentOrWeb(String value) {
        HtmlLinks.Target t = HtmlLinks.resolve(Path.of("untitled.html").toAbsolutePath(), null, value, null);
        return t.kind() == HtmlLinks.Kind.FILE ? new HtmlLinks.Target(HtmlLinks.Kind.NONE, null, "", "") : t;
    }

    private void go(EditorBuffer from, HtmlLinks.Target target, String written) {
        switch (target.kind()) {
            case FRAGMENT -> gotoAnchor(from, from.getPath(), from.text(), target.fragment());
            case WEB -> {
                if (webConfirmer.test(target.url())) {
                    host.openExternalUrl(target.url());
                }
            }
            case FILE -> {
                Path file = target.file();
                if (file != null) {
                    openFile(from, file, target.fragment(), written);
                }
            }
            case NONE -> host.setStatus(tr("status.html.linkNotFollowed", written.trim()));
        }
    }

    /** Opens {@code file} (a folder's {@code index.html}), at {@code fragment}'s element; checked off the FX thread. */
    private void openFile(EditorBuffer from, Path file, String fragment, String written) {
        CompletableFuture.supplyAsync(() -> look(file, fragment), IO)
                .exceptionally(t -> new Looked(null, null))
                .thenAccept(found -> Platform.runLater(() -> {
                    if (host.activeBuffer() != from) {
                        return; // the user moved on while the file was looked for
                    }
                    Path to = found.file();
                    if (to == null) {
                        host.setStatus(tr("status.html.linkMissing", missingName(written)));
                        return;
                    }
                    if (fragment.isEmpty()) {
                        ops.openAndGoto(to, 0, 0);
                        return;
                    }
                    EditorBuffer open = ops.bufferForPath(to);
                    String text = open != null ? open.text() : found.text();
                    gotoAnchor(null, to, text == null ? "" : text, fragment);
                }));
    }

    /** What the I/O thread found: the file to open (null when missing) and, for a fragment, its text. */
    private record Looked(@Nullable Path file, @Nullable String text) {}

    private static Looked look(Path file, String fragment) {
        Path to = file;
        if (Files.isDirectory(to)) {
            to = to.resolve("index.html"); // a link to a folder serves its index page
        }
        if (!Files.isRegularFile(to)) {
            return new Looked(null, null);
        }
        String text = null;
        try {
            if (!fragment.isEmpty() && (!Vfs.isLocal(to) || Files.size(to) <= MAX_ANCHOR_SCAN_BYTES)) {
                text = Files.readString(to);
            }
        } catch (java.io.IOException | RuntimeException unreadable) {
            text = null; // opened at the top instead
        }
        return new Looked(to, text);
    }

    /**
     * Moves to {@code fragment}'s element in {@code text}, the content of {@code file} (null: the untitled
     * {@code page} itself); says so when no element has that id, and then opens the file at its top.
     */
    private void gotoAnchor(@Nullable EditorBuffer page, @Nullable Path file, String text, String fragment) {
        int at = HtmlLinks.anchorOffset(text, fragment);
        if (at < 0) {
            host.setStatus(tr("status.html.anchorMissing", fragment));
            if (file != null && page == null) {
                ops.openAndGoto(file, 0, 0);
            }
            return;
        }
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        if (file != null) {
            ops.openAndGoto(file, line, at - lineStart);
        } else if (page != null) {
            CodeArea a = page.getFocusedArea();
            a.moveTo(Math.min(at, a.getLength()));
            a.requestFollowCaret();
        }
    }

    /** The file name a missing link is reported by: as written, without its query or fragment. */
    static String missingName(String written) {
        String v = written.trim();
        int cut = v.length();
        for (char c : new char[] {'#', '?'}) {
            int i = v.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        return v.substring(0, cut);
    }

    private boolean confirmWeb(String url) {
        javafx.scene.control.ButtonType open = new javafx.scene.control.ButtonType(
                tr("dialog.html.openWeb.open"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert ask = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr("dialog.html.openWeb.body", url),
                open,
                javafx.scene.control.ButtonType.CANCEL));
        ask.initOwner(host.window());
        ask.setTitle(tr("dialog.html.openWeb.title"));
        ask.setHeaderText(null);
        return ask.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == open;
    }
}
