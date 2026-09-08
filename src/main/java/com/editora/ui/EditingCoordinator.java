package com.editora.ui;

import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editops.KillRing;
import com.editora.editops.Rectangle;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TextNav;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

import static com.editora.i18n.Messages.tr;

/** Owns editing commands and per-window mark, kill/yank, rectangle and query-replace state. */
final class EditingCoordinator {
    interface Host {
        EditorSettingsCoordinator editorSettings();

        Stage stage();

        ConfigManager config();

        CommandRegistry registry();

        FindReplaceBar findBar();

        StatusBar statusBar();

        SettingsWindow settingsWindow();

        OverlayHost overlayHost();

        void updateWindowTitle();

        void navigateToLine(int line);

        GitCoordinator git();

        LspCoordinator lspCoordinator();

        boolean isLocalBuffer(EditorBuffer b);

        void refreshPasteState();

        void setStatus(String message);

        EditorBuffer activeBuffer();

        CodeArea activeArea();

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);

        SelectionPolicy selPolicy();
    }

    private final Host host;

    EditingCoordinator(Host host) {
        this.host = host;
    }

    /** Emacs mark: when set (C-SPC), caret movement extends the selection from the mark. */
    boolean markActive;

    /** Expand/shrink-selection history (the pure stack); see {@link #expandSelection}/{@link #shrinkSelection}. */
    final com.editora.editops.SmartSelectStack smartSelect = new com.editora.editops.SmartSelectStack();

    /**
     * The Emacs kill ring. Per window rather than app-global: every kill is also written to the system
     * clipboard, so the most recent kill still crosses windows — only the ring's *history* is per-window.
     */
    final KillRing killRing = new KillRing();

    /**
     * Where the previous kill left off: buffer identity + document version + caret. A kill starting at
     * exactly that point accumulates into the same ring entry (Emacs' consecutive-kill behaviour), and
     * anything the user does in between — typing, moving, switching tabs — moves one of the three and so
     * starts a fresh entry. Keying off {@link EditorBuffer#docVersion()} avoids needing a
     * command-sequencing hook (the single {@code CommandRegistry} execution listener belongs to macro
     * recording).
     */
    EditorBuffer lastKillBuffer;

    long lastKillDocVersion = -1;

    int lastKillCaret = -1;

    /** The range the last yank/yank-pop inserted, so {@code M-y} knows what to replace. Same guard shape. */
    EditorBuffer lastYankBuffer;

    long lastYankDocVersion = -1;

    int lastYankStart = -1;

    int lastYankEnd = -1;

    /** Cycle state for {@code move-to-window-line-top-bottom} (M-r): center → top → bottom. */
    int windowLineCycle = -1;

    /**
     * The active buffer's selection when it is non-empty and on one line, else "". A multi-line selection
     * is never a sensible search term, which is the same rule the find bar and Find in Files apply.
     */
    String singleLineSelection() {
        EditorBuffer b = host.activeBuffer();
        CodeArea a = b == null ? null : b.getFocusedArea();
        if (a == null) {
            return "";
        }
        String selection = a.getSelectedText();
        return selection == null || selection.isBlank() || selection.contains("\n") ? "" : selection.strip();
    }

    void onUndo() {
        withArea(CodeArea::undo);
    }

    void onRedo() {
        withArea(CodeArea::redo);
    }

    void onCut() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.multiCaretCut()) { // every caret's selection, one undoable step
            adoptClipboardAsKill();
            deactivateMark();
            host.refreshPasteState();
            host.setStatus(tr("status.cut"));
            return;
        }
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        boolean had = area.getSelection().getLength() > 0;
        if (!had && host.config().getSettings().isCopyLineWhenNoSelection() && b != null) {
            b.cutCurrentLine(); // empty selection → cut the whole current line (VS Code editor.emptySelectionClipboard)
            adoptClipboardAsKill();
            deactivateMark();
            host.refreshPasteState();
            host.setStatus(tr("status.cutLine"));
            return;
        }
        area.cut();
        adoptClipboardAsKill(); // Emacs kill-region: the cut text joins the kill ring
        deactivateMark();
        host.refreshPasteState(); // clipboard now has content
        host.setStatus(tr(had ? "status.cut" : "status.nothingToCut"));
    }

    void onCopy() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.multiCaretCopy()) { // every caret's selection (VS Code one-line-per-caret)
            adoptClipboardAsKill();
            deactivateMark();
            host.refreshPasteState();
            host.setStatus(tr("status.copied"));
            return;
        }
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        boolean had = area.getSelection().getLength() > 0;
        if (!had && host.config().getSettings().isCopyLineWhenNoSelection() && b != null) {
            b.copyCurrentLine(); // empty selection → copy the whole current line (VS Code
            // editor.emptySelectionClipboard)
            adoptClipboardAsKill();
            deactivateMark();
            host.refreshPasteState();
            host.setStatus(tr("status.copiedLine"));
            return;
        }
        // Copy the selection as plain text plus, when enabled, a syntax-highlighted HTML flavor; fall back
        // to RichTextFX's own copy when there's no selection or no buffer.
        if (b == null || !copySelectionRich(area, b, false)) {
            area.copy();
        }
        if (had) {
            area.deselect(); // collapse the selection once copied (leaves the caret in place)
        }
        adoptClipboardAsKill(); // Emacs kill-ring-save
        deactivateMark();
        host.refreshPasteState(); // clipboard now has content
        host.setStatus(tr(had ? "status.copied" : "status.nothingToCopy"));
    }

    /** VS Code cap: above this many chars the auto path skips the (potentially large) HTML flavor. */
    static final int COPY_HTML_CHAR_CAP = 65_536;

    /**
     * Copies {@code area}'s selection to the clipboard as plain text plus — when enabled and within the
     * size cap, or {@code force} — a syntax-highlighted {@code text/html} flavor (VS Code
     * {@code copyWithSyntaxHighlighting}). Returns false when there is no selection (caller falls back).
     */
    boolean copySelectionRich(CodeArea area, EditorBuffer b, boolean force) {
        String sel = area.getSelectedText();
        if (sel.isEmpty()) {
            return false;
        }
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        ClipboardContent cc = new ClipboardContent();
        cc.putString(sel);
        boolean wantHtml = force
                || (host.config().getSettings().isCopyWithSyntaxHighlighting() && sel.length() <= COPY_HTML_CHAR_CAP);
        if (wantHtml && b.hasHighlighting()) {
            // Read the already-applied spans (FX-thread-safe); never re-tokenize on the FX thread.
            cc.putHtml(com.editora.pdf.CodeHtml.toHtml(sel, area.getStyleSpans(start, end), b.getTabSize()));
        }
        Clipboard.getSystemClipboard().setContent(cc);
        return true;
    }

    /**
     * Forced copy-with-highlighting (VS Code's {@code clipboardCopyWithSyntaxHighlightingAction}): always
     * attaches the HTML flavor, bypassing both the setting and the size cap. Acts on the selection, else the
     * current line.
     */
    void copyWithHighlighting() {
        EditorBuffer b = host.activeBuffer();
        CodeArea area = host.activeArea();
        if (area == null || b == null) {
            return;
        }
        boolean hasSel = !area.getSelectedText().isEmpty();
        int para = area.getCurrentParagraph();
        int start = hasSel ? area.getSelection().getStart() : area.getAbsolutePosition(para, 0);
        int end = hasSel
                ? area.getSelection().getEnd()
                : start + area.getParagraph(para).length();
        String htmlText = area.getText(start, end); // the line/selection without any trailing newline
        // A whole-line copy includes its newline in the plain-text flavor (like copyCurrentLine); the HTML
        // flavor uses the line itself so it doesn't render a trailing empty line.
        String plainText = hasSel ? htmlText : htmlText + "\n";
        ClipboardContent cc = new ClipboardContent();
        cc.putString(plainText);
        if (b.hasHighlighting()) {
            // Read the already-applied spans (FX-thread-safe); never re-tokenize on the FX thread.
            cc.putHtml(com.editora.pdf.CodeHtml.toHtml(htmlText, area.getStyleSpans(start, end), b.getTabSize()));
        }
        Clipboard.getSystemClipboard().setContent(cc);
        adoptClipboardAsKill();
        deactivateMark();
        host.refreshPasteState();
        host.setStatus(tr("status.copiedHighlighted"));
    }

    void onPaste() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && tryMarkdownImagePaste(b)) { // a clipboard image → save to assets/ + insert ![](…)
            deactivateMark();
            return;
        }
        if (b != null && b.trySmartLinkPaste()) { // a clipboard URL over a selection → [selection](url)
            deactivateMark();
            host.setStatus(tr("status.markdown.linkPasted"));
            return;
        }
        if (b != null && b.multiCaretPaste()) { // distribute clipboard lines one per caret
            deactivateMark();
            host.setStatus(tr("status.pasted"));
            return;
        }
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        // Capture where the paste will land so the LSP paste auto-import (#742) can name the span: a
        // selection is replaced from its start; otherwise text inserts at the caret.
        int pasteStart =
                area.getSelection().getLength() > 0 ? area.getSelection().getStart() : area.getCaretPosition();
        if (b == null || !yankFromRing(b, area)) {
            area.paste(); // nothing on the ring — fall back to the platform paste
        }
        if (b != null) {
            b.requestLspPasteImports(pasteStart, area.getCaretPosition());
        }
        deactivateMark();
        host.setStatus(tr("status.pasted"));
    }

    /** The image-reference snippet for {@code b}: Typst {@code #image("rel")} or Markdown {@code ![alt](rel)}. */
    static String markupImageSnippet(EditorBuffer b, String rel, String alt) {
        return b.isTypst()
                ? com.editora.typst.TypstMarkup.image(rel)
                : com.editora.markdown.MarkdownImagePaste.snippet(rel, alt);
    }

    /** The Typst "Insert Image" menu action: pick an image file, copy it into the doc's {@code assets/}
     *  dir, and insert {@code #image("assets/…")}. Requires a saved local buffer (so assets/ resolves). */
    void insertTypstImageFromChooser(EditorBuffer b) {
        if (b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.markdown.imageNeedsSave"));
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(tr("command.typst.insertImage"));
        chooser.getExtensionFilters()
                .add(new javafx.stage.FileChooser.ExtensionFilter(
                        "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg", "*.bmp"));
        java.io.File f = chooser.showOpenDialog(host.stage());
        if (f != null) {
            insertDroppedImages(b, java.util.List.of(f));
        }
    }

    boolean tryMarkdownImagePaste(EditorBuffer b) {
        if ((!b.isMarkdown() && !b.isTypst()) || !b.isEditable()) {
            return false;
        }
        if (!javafx.scene.input.Clipboard.getSystemClipboard().hasImage()) {
            return false;
        }
        if (!host.isLocalBuffer(b) || b.getPath() == null) {
            host.setStatus(tr("status.markdown.imageNeedsSave"));
            return true; // claim it: a raw image can't be pasted as text anyway
        }
        javafx.scene.image.Image img =
                javafx.scene.input.Clipboard.getSystemClipboard().getImage();
        if (img == null) {
            return false;
        }
        try {
            java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
            java.nio.file.Path assets = baseDir.resolve(com.editora.markdown.MarkdownImagePaste.ASSETS_DIR);
            java.nio.file.Files.createDirectories(assets);
            String name = com.editora.markdown.MarkdownImagePaste.uniqueFileName(
                    n -> java.nio.file.Files.exists(assets.resolve(n)), "pasted-image", "png");
            java.nio.file.Path target = assets.resolve(name);
            writeFxImageToPng(img, target);
            String rel = com.editora.markdown.MarkdownImagePaste.relativePath(baseDir, target);
            b.insertAtCaret(markupImageSnippet(b, rel, ""));
            host.setStatus(tr("status.markdown.imagePasted", rel));
        } catch (Exception ex) {
            host.setStatus(tr("status.markdown.imageFailed", String.valueOf(ex.getMessage())));
        }
        return true;
    }

    /** Copies dropped image files into the Markdown file's {@code assets/} dir and inserts a link for each. */
    void insertDroppedImages(EditorBuffer b, java.util.List<java.io.File> files) {
        if (b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.markdown.imageNeedsSave"));
            return;
        }
        try {
            java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
            java.nio.file.Path assets = baseDir.resolve(com.editora.markdown.MarkdownImagePaste.ASSETS_DIR);
            java.nio.file.Files.createDirectories(assets);
            StringBuilder out = new StringBuilder();
            for (java.io.File f : files) {
                String ext = extensionOf(f.getName());
                String base = stripExtension(f.getName());
                String name = com.editora.markdown.MarkdownImagePaste.uniqueFileName(
                        n -> java.nio.file.Files.exists(assets.resolve(n)), base, ext);
                java.nio.file.Path target = assets.resolve(name);
                java.nio.file.Files.copy(f.toPath(), target);
                String rel = com.editora.markdown.MarkdownImagePaste.relativePath(baseDir, target);
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(markupImageSnippet(b, rel, base));
            }
            b.insertAtCaret(out.toString());
            host.setStatus(tr("status.markdown.imageDropped", files.size()));
        } catch (Exception ex) {
            host.setStatus(tr("status.markdown.imageFailed", String.valueOf(ex.getMessage())));
        }
    }

    /**
     * Handles a raw image / image URL dragged from a web browser onto a Markdown buffer: encodes a raw
     * dragged image now (FX thread), then off the FX thread downloads the URL (or uses the encoded bytes)
     * into {@code assets/} and inserts {@code ![](assets/…)}. If everything fails but a URL exists, it
     * falls back to referencing the remote URL directly (the preview loads http(s) images).
     */
    void insertWebImage(EditorBuffer b, javafx.scene.image.Image image, String url) {
        if (b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.markdown.imageNeedsSave"));
            return;
        }
        // Encode a raw dragged image now, on the FX thread (PixelReader), before going off-thread.
        byte[] inlinePng = image != null ? com.editora.editor.PreviewImageLoader.imageToPng(image) : null;
        java.nio.file.Path baseDir = b.getPath().toAbsolutePath().getParent();
        host.setStatus(tr("status.markdown.imageDownloading"));
        new Thread(
                        () -> {
                            byte[] bytes = null;
                            String ext = "png";
                            if (url != null && !url.isBlank()) {
                                try {
                                    bytes = com.editora.editor.PreviewImageLoader.fetchBytes(url);
                                    if (bytes != null && bytes.length > 0) {
                                        ext = com.editora.markdown.MarkdownImagePaste.extensionForUrl(
                                                url,
                                                com.editora.editor.PreviewImageLoader.looksLikeSvg(bytes)
                                                        ? "svg"
                                                        : "png");
                                    } else {
                                        bytes = null;
                                    }
                                } catch (Exception ignore) {
                                    bytes = null; // fall back to the encoded image / remote reference below
                                }
                            }
                            if (bytes == null) {
                                bytes = inlinePng;
                                ext = "png";
                            }
                            if (bytes == null) {
                                javafx.application.Platform.runLater(() -> {
                                    if (url != null) {
                                        b.insertAtCaret(markupImageSnippet(b, url, ""));
                                        host.setStatus(tr("status.markdown.imageDropped", 1));
                                    } else {
                                        host.setStatus(tr("status.markdown.imageFailed", ""));
                                    }
                                });
                                return;
                            }
                            byte[] finalBytes = bytes;
                            String finalExt = ext;
                            try {
                                java.nio.file.Path assets =
                                        baseDir.resolve(com.editora.markdown.MarkdownImagePaste.ASSETS_DIR);
                                java.nio.file.Files.createDirectories(assets);
                                String name = com.editora.markdown.MarkdownImagePaste.uniqueFileName(
                                        n -> java.nio.file.Files.exists(assets.resolve(n)),
                                        webImageBaseName(url),
                                        finalExt);
                                java.nio.file.Path target = assets.resolve(name);
                                java.nio.file.Files.write(target, finalBytes);
                                String rel = com.editora.markdown.MarkdownImagePaste.relativePath(baseDir, target);
                                javafx.application.Platform.runLater(() -> {
                                    b.insertAtCaret(markupImageSnippet(b, rel, ""));
                                    host.setStatus(tr("status.markdown.imageDropped", 1));
                                });
                            } catch (Exception ex) {
                                javafx.application.Platform.runLater(() -> host.setStatus(
                                        tr("status.markdown.imageFailed", String.valueOf(ex.getMessage()))));
                            }
                        },
                        "md-web-image")
                .start();
    }

    /** A file-name base for a dragged web image: the URL's last path segment (sans extension), else "image". */
    static String webImageBaseName(String url) {
        if (url == null || url.startsWith("data:")) {
            return "image";
        }
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int h = u.indexOf('#');
        if (h >= 0) {
            u = u.substring(0, h);
        }
        int slash = u.lastIndexOf('/');
        String seg = slash >= 0 ? u.substring(slash + 1) : u;
        int dot = seg.lastIndexOf('.');
        if (dot > 0) {
            seg = seg.substring(0, dot);
        }
        seg = seg.replaceAll("[^A-Za-z0-9_-]", "");
        return seg.isBlank() ? "image" : seg;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "png";
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Writes a JavaFX {@code Image} to a PNG via headless Java2D (no {@code javafx.swing} dependency). */
    static void writeFxImageToPng(javafx.scene.image.Image img, java.nio.file.Path target) throws java.io.IOException {
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(bi, "png", target.toFile());
    }

    void onFind() {
        if (host.findBar().isShown()) {
            host.findBar().hideBar();
        } else {
            host.findBar().show(false);
        }
    }

    /** The prefix argument ({@code C-u}) available while a count-aware command runs, else null. */
    Integer currentPrefixArg;

    /** Inserts {@code ch} {@code count} times at the caret as one undoable edit ({@code C-u 40 -}). */
    void selfInsertRepeat(char ch, int count) {
        if (count <= 0 || !activeEditable()) {
            return;
        }
        CodeArea area = host.activeArea();
        if (area != null) {
            area.insertText(area.getCaretPosition(), String.valueOf(ch).repeat(count));
        }
    }

    void setMark() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        if (currentPrefixArg != null) {
            popMark(); // Emacs C-u C-SPC = pop-to-mark instead of setting a new one
            return;
        }
        int caret = area.getCaretPosition();
        area.selectRange(caret, caret); // anchor = caret; ADJUST moves then extend from here
        markActive = true;
        buffer.pushMark(caret); // record on the mark ring so pop-mark can return here later
        host.setStatus(tr("status.markSet"));
    }

    /**
     * Emacs {@code pop-to-mark} ({@code C-x C-SPC}): move point to the most recent mark on this buffer's
     * ring, cycling on repeat. The current point rotates to the back of the ring, so repeated pops walk
     * back through every mark and return to where you started.
     */
    void popMark() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        java.util.OptionalInt target = buffer.popMark(area.getCaretPosition());
        if (target.isEmpty()) {
            host.setStatus(tr("status.markRing.empty"));
            return;
        }
        deactivateMark();
        area.moveTo(Math.min(target.getAsInt(), area.getLength()));
        area.requestFollowCaret();
        host.setStatus(tr("status.markRing.popped", buffer.markRingSize()));
    }

    /** Emacs {@code C-x C-x}: move the caret to the mark (and the mark to the caret). */
    void exchangePointAndMark() {
        CodeArea area = host.activeArea();
        if (area == null || area.getSelection().getLength() == 0) {
            return;
        }
        area.selectRange(area.getCaretPosition(), area.getAnchor());
        markActive = true;
        area.requestFollowCaret();
    }

    /** Clears the Emacs mark (e.g. after a clipboard action or a mouse click). */
    void deactivateMark() {
        markActive = false;
    }

    void withArea(java.util.function.Consumer<CodeArea> action) {
        if (!activeEditable()) {
            return;
        }
        CodeArea area = host.activeArea();
        if (area != null) {
            action.accept(area);
        }
    }

    /**
     * Guards mutating commands: returns {@code false} (and echoes a hint) when the active buffer is
     * read-only (huge-file or user View mode), so edits are refused instead of bypassing
     * {@code setEditable(false)} via the app's own commands. Returns {@code true} when there is no
     * buffer or it is editable.
     */
    boolean activeEditable() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null && !buffer.isEditable()) {
            host.setStatus(tr("status.bufferReadOnly"));
            return false;
        }
        return true;
    }

    /** Manually opens the autocomplete popup for the active buffer (the {@code edit.completion} command). */
    void triggerCompletion() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.triggerCompletion();
        }
    }

    /** Toggles the completion documentation popup for the open completion list (the {@code edit.completionDoc}
     *  command, Ctrl+Q — IntelliJ "quick documentation"). */
    void toggleCompletionDoc() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.toggleCompletionDoc();
        }
    }

    void toggleComment() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        // The comment logic lives on the buffer (so the editor right-click menu can invoke it too).
        if (!buffer.toggleComment()) {
            host.setStatus(tr("status.noCommentSyntax"));
        }
    }

    /** Applies an Emacs transpose (chars/words/lines) to the active editable buffer at the caret. */
    void transpose(java.util.function.BiFunction<String, Integer, com.editora.editops.Transposer.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.Transposer.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /** Applies a pure {@link com.editora.editops.LineOps} edit to the active area (duplicate / move line). */
    void lineOp(java.util.function.BiFunction<String, Integer, com.editora.editops.LineOps.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.LineOps.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /**
     * Applies a pure {@link com.editora.editops.EmacsEdits} caret-based edit (backward-kill-word, the
     * case-word commands, join-line, the whitespace commands, open-line, kill-whole-line, …) to the
     * active editable buffer. Mirrors {@link #transpose} / {@link #lineOp}.
     */
    void emacsEdit(java.util.function.BiFunction<String, Integer, com.editora.editops.EmacsEdits.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.EmacsEdits.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        deactivateMark();
        area.requestFocus();
    }

    /**
     * Whether a kill starting here continues the previous one (Emacs: {@code last-command} was a kill),
     * in which case the text accumulates into the newest ring entry instead of pushing a new one.
     */
    boolean continuesPreviousKill(EditorBuffer buffer, int caret) {
        return buffer == lastKillBuffer && buffer.docVersion() == lastKillDocVersion && caret == lastKillCaret;
    }

    /**
     * Records {@code text} as killed and mirrors the resulting ring entry to the system clipboard (Emacs'
     * {@code select-enable-clipboard}), then remembers this position so an immediately following kill
     * accumulates. Call <em>after</em> the deletion has been applied — the caret and document version are
     * read as they now stand — and pass the {@code merge} verdict taken <em>before</em> it, since applying
     * the edit has already bumped {@link EditorBuffer#docVersion()} past the recorded one.
     */
    void pushKill(EditorBuffer buffer, CodeArea area, String text, KillRing.Direction dir, boolean merge) {
        if (text == null || text.isEmpty()) {
            return;
        }
        killRing.kill(text, dir, merge);
        setClipboardString(killRing.current()); // the whole accumulated entry, as Emacs does
        lastKillBuffer = buffer;
        lastKillDocVersion = buffer.docVersion();
        lastKillCaret = area.getCaretPosition();
        invalidateYank();
        host.refreshPasteState();
    }

    /** Mirrors whatever a cut/copy just placed on the clipboard into the ring as a fresh entry. */
    void adoptClipboardAsKill() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasString()) {
            pushSave(cb.getString());
        }
    }

    /** Records copied (not killed) text: a fresh ring entry, never an accumulation. */
    void pushSave(String text) {
        killRing.save(text);
        invalidateYank();
        resetKillAccumulation();
    }

    /** Breaks any consecutive-kill run, so the next kill starts a new ring entry. */
    void resetKillAccumulation() {
        lastKillBuffer = null;
        lastKillDocVersion = -1;
        lastKillCaret = -1;
    }

    void invalidateYank() {
        lastYankBuffer = null;
        lastYankDocVersion = -1;
        lastYankStart = -1;
        lastYankEnd = -1;
    }

    void recordYank(EditorBuffer buffer, int start, int end) {
        lastYankBuffer = buffer;
        lastYankDocVersion = buffer.docVersion();
        lastYankStart = start;
        lastYankEnd = end;
    }

    void setClipboardString(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * The variant of {@link #emacsEdit} for the commands that <em>kill</em> (the removed text joins the
     * kill ring) rather than merely delete: {@code C-k}, {@code M-d}, {@code M-DEL}, {@code C-M-k},
     * {@code C-S-DEL}. The plain {@code emacsEdit} still backs {@code delete-horizontal-space} and
     * friends, which Emacs likewise keeps off the ring.
     */
    void emacsKill(
            java.util.function.BiFunction<String, Integer, com.editora.editops.EmacsEdits.Edit> op,
            KillRing.Direction dir) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.EmacsEdits.Edit edit = op.apply(area.getText(), area.getCaretPosition());
        if (edit == null) {
            return;
        }
        boolean merge = continuesPreviousKill(buffer, area.getCaretPosition()); // decide before the edit
        String killed = area.getText(edit.from(), edit.to());
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        pushKill(buffer, area, killed, dir, merge);
        deactivateMark();
        area.requestFocus();
    }

    /**
     * Emacs {@code yank} ({@code C-y}) for the plain paste path: inserts the current ring entry. The
     * system clipboard wins when it holds something the ring doesn't (i.e. the user copied in another
     * application) — Emacs' {@code interprogram-paste-function}. Returns false when there is nothing to
     * yank, so the caller can fall back to the platform paste.
     */
    boolean yankFromRing(EditorBuffer buffer, CodeArea area) {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasString()) {
            killRing.adoptExternal(cb.getString());
        }
        String text = killRing.current();
        if (text == null || text.isEmpty()) {
            return false;
        }
        var sel = area.getSelection();
        int start = sel.getStart();
        area.replaceText(start, sel.getEnd(), text);
        area.moveTo(start + text.length());
        recordYank(buffer, start, start + text.length());
        resetKillAccumulation();
        return true;
    }

    /**
     * Emacs {@code yank-pop} ({@code M-y}): replaces the text the immediately preceding yank inserted
     * with the next-older ring entry. Only legal directly after a yank — if the document has moved since,
     * there is no known range to replace and we say so rather than guessing.
     */
    void yankPop() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        if (buffer != lastYankBuffer || buffer.docVersion() != lastYankDocVersion || lastYankStart < 0) {
            host.setStatus(tr("status.yankPop.notAfterYank"));
            return;
        }
        if (killRing.size() < 2) {
            host.setStatus(tr("status.yankPop.ringEmpty"));
            return;
        }
        String text = killRing.rotate();
        if (text == null) {
            return;
        }
        area.replaceText(lastYankStart, lastYankEnd, text);
        area.moveTo(lastYankStart + text.length());
        recordYank(buffer, lastYankStart, lastYankStart + text.length());
        setClipboardString(text);
        host.refreshPasteState();
        host.setStatus(tr("status.yankPop", killRing.yankIndex() + 1, killRing.size()));
        area.requestFocus();
    }

    /**
     * Palette-only {@code edit.yankFromRing}: pick any past kill from the ring instead of stepping back
     * through it with {@code M-y}.
     */
    void showKillRingPicker() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (killRing.isEmpty()) {
            host.setStatus(tr("status.yankPop.ringEmpty"));
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        QuickOpen<String> picker = new QuickOpen<>(
                tr("dialog.killRing.title"),
                tr("dialog.killRing.prompt"),
                killRing::entries,
                EditingCoordinator::killRingLabel,
                entry -> tr("dialog.killRing.detail", entry.length()),
                entry -> {
                    var sel = area.getSelection();
                    int start = sel.getStart();
                    area.replaceText(start, sel.getEnd(), entry);
                    area.moveTo(start + entry.length());
                    recordYank(buffer, start, start + entry.length());
                    resetKillAccumulation();
                    area.requestFocus();
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** One-line preview of a ring entry for the picker: whitespace flattened, elided when long. */
    static String killRingLabel(String entry) {
        String flat = entry.replace('\n', '⏎').replace('\t', ' ').strip();
        return flat.length() <= KILL_RING_LABEL_MAX ? flat : flat.substring(0, KILL_RING_LABEL_MAX) + "…";
    }

    static final int KILL_RING_LABEL_MAX = 80;

    /** The interactive query-replace in progress, or null. At most one runs at a time per window. */
    QueryReplaceSession queryReplaceSession;

    /** Emacs {@code query-replace} (`M-%`): prompt for search + replacement, then confirm each match. */
    void queryReplace() {
        startQueryReplace(false);
    }

    /** Emacs {@code query-replace-regexp} (`C-M-%`): as above, the search string is a regular expression. */
    void queryReplaceRegexp() {
        startQueryReplace(true);
    }

    void startQueryReplace(boolean regex) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (queryReplaceSession != null) {
            queryReplaceSession.finish(); // a fresh invocation supersedes a dangling one
        }
        String seed = singleLineSelection(buffer.getFocusedArea());
        String title = tr(regex ? "dialog.queryReplaceRegexp.title" : "dialog.queryReplace.title");
        host.promptText(title, tr("dialog.queryReplace.searchLabel"), seed, query -> {
            if (query.isEmpty()) {
                return;
            }
            if (regex) {
                String err = com.editora.editor.SearchMatcher.regexError(query);
                if (err != null) {
                    host.setStatus(tr("find.badRegex", err));
                    return;
                }
            }
            host.promptText(title, tr("dialog.queryReplace.replaceLabel", query), "", replacement -> {
                var spec = new com.editora.editor.QueryReplace.Spec(query, replacement, false, regex, false, false);
                beginQueryReplace(buffer, spec);
            });
        });
    }

    /** Starts an interactive query-replace over {@code buffer} with a resolved spec (past the prompts). */
    void beginQueryReplace(EditorBuffer buffer, com.editora.editor.QueryReplace.Spec spec) {
        if (queryReplaceSession != null) {
            queryReplaceSession.finish();
        }
        new QueryReplaceSession(buffer, spec).start();
    }

    /** The buffer's selection when it lies on a single line (a sensible search seed), else empty. */
    String singleLineSelection(CodeArea area) {
        var sel = area.getSelection();
        if (sel.getLength() == 0) {
            return "";
        }
        String text = area.getSelectedText();
        return text.indexOf('\n') < 0 ? text : "";
    }

    private final class QueryReplaceSession {
        private final EditorBuffer buffer;
        private final CodeArea area;
        private final com.editora.editor.QueryReplace.Spec spec;
        private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> pressed = this::onPressed;
        private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> typed = this::onTyped;
        private final javafx.beans.value.ChangeListener<Boolean> focusLost = (obs, was, isFocused) -> {
            if (!isFocused) {
                finish();
            }
        };
        private int from;
        private com.editora.editor.QueryReplace.Match current;
        private int replaced;
        private boolean active;

        QueryReplaceSession(EditorBuffer buffer, com.editora.editor.QueryReplace.Spec spec) {
            this.buffer = buffer;
            this.area = buffer.getFocusedArea();
            this.spec = spec;
            this.from = area.getCaretPosition();
        }

        void start() {
            if (!showNext()) {
                host.setStatus(tr("status.queryReplace.none"));
                return;
            }
            active = true;
            queryReplaceSession = this;
            area.getProperties().put("editora.ownsKeys", Boolean.TRUE);
            area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, pressed);
            area.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, typed);
            area.focusedProperty().addListener(focusLost);
        }

        /** Finds the next match from {@link #from}, highlights it and prompts. False when none remain. */
        private boolean showNext() {
            var m = com.editora.editor.QueryReplace.next(area.getText(), from, spec);
            if (m.isEmpty()) {
                current = null;
                return false;
            }
            current = m.get();
            buffer.setSearchMatches(List.of(new int[] {current.start(), current.end()}), 0);
            area.moveTo(current.start());
            area.requestFollowCaret();
            host.setStatus(tr("status.queryReplace.prompt", replaced));
            return true;
        }

        /** Applies the current match's replacement and moves the search anchor past it. */
        private void replaceCurrentOnly() {
            area.replaceText(current.start(), current.end(), current.replacement());
            replaced++;
            from = com.editora.editor.QueryReplace.advance(
                    current, current.replacement().length());
        }

        private void replaceCurrentAndAdvance() {
            replaceCurrentOnly();
            if (!showNext()) {
                finish();
            }
        }

        private void skip() {
            from = com.editora.editor.QueryReplace.advance(current, current.end() - current.start());
            if (!showNext()) {
                finish();
            }
        }

        /** {@code !}: replace this and every remaining match in one edit, then stop. */
        private void replaceRest() {
            var plan = com.editora.editor.QueryReplace.planRemaining(area.getText(), from, spec);
            if (!plan.isEmpty()) {
                int start = plan.get(0).start();
                int end = plan.get(plan.size() - 1).end();
                String text = area.getText();
                StringBuilder sb = new StringBuilder();
                int i = start;
                for (var m : plan) {
                    sb.append(text, i, m.start()).append(m.replacement());
                    i = m.end();
                }
                area.replaceText(start, end, sb.toString());
                replaced += plan.size();
            }
            finish();
        }

        private void onPressed(javafx.scene.input.KeyEvent e) {
            if (!active) {
                return;
            }
            e.consume();
            javafx.scene.input.KeyCode c = e.getCode();
            if (c == javafx.scene.input.KeyCode.BACK_SPACE || c == javafx.scene.input.KeyCode.DELETE) {
                skip();
            } else if (c == javafx.scene.input.KeyCode.ENTER
                    || c == javafx.scene.input.KeyCode.ESCAPE
                    || c == javafx.scene.input.KeyCode.Q
                    || (c == javafx.scene.input.KeyCode.G && e.isControlDown())) {
                finish();
            }
            // Every other pressed key is swallowed; the character commands arrive as KEY_TYPED.
        }

        private void onTyped(javafx.scene.input.KeyEvent e) {
            if (!active) {
                return;
            }
            e.consume();
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty()) {
                return;
            }
            switch (ch.charAt(0)) {
                case ' ', 'y', 'Y' -> replaceCurrentAndAdvance();
                case 'n', 'N' -> skip();
                case '!' -> replaceRest();
                case '.' -> { // replace this match and stop, leaving the caret on the replacement
                    replaceCurrentOnly();
                    finish();
                }
                case 'q', 'Q' -> finish();
                default -> {
                    /* unknown key: wait, like Emacs */
                }
            }
        }

        /** Ends the session (idempotent): removes the highlight, the key filters and the key ownership. */
        private void finish() {
            if (!active) {
                return;
            }
            active = false;
            area.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, pressed);
            area.removeEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, typed);
            area.focusedProperty().removeListener(focusLost);
            area.getProperties().remove("editora.ownsKeys");
            buffer.clearSearchMatches();
            if (queryReplaceSession == this) {
                queryReplaceSession = null;
            }
            host.setStatus(tr("status.queryReplace.done", replaced));
        }
    }

    /** Emacs {@code narrow-to-region} (`C-x n n`): restrict the buffer to the selection. */
    void narrowToRegion() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        var sel = buffer.getFocusedArea().getSelection();
        if (sel.getLength() == 0) {
            host.setStatus(tr("status.narrow.noRegion"));
            return;
        }
        applyNarrow(buffer, sel.getStart(), sel.getEnd());
    }

    /** Emacs {@code narrow-to-defun} (`C-x n d`): restrict the buffer to the enclosing function. */
    void narrowToDefun() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        String text = area.getText();
        int caret = area.getCaretPosition();
        int start = com.editora.editops.SexpNav.beginningOfDefun(text, caret);
        int end = com.editora.editops.SexpNav.endOfDefun(text, caret);
        if (start >= end) {
            host.setStatus(tr("status.narrow.noDefun"));
            return;
        }
        applyNarrow(buffer, start, end);
    }

    /** Narrow to the innermost foldable region around the caret — the fold machinery already knows it. */
    void narrowToFoldRegion() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        int line = area.getCurrentParagraph();
        com.editora.editor.FoldRegions.Region best = null;
        for (var r : buffer.getFoldManager().regions()) {
            if (r.startLine() <= line && line <= r.endLine() && (best == null || r.startLine() > best.startLine())) {
                best = r; // innermost containing region
            }
        }
        if (best == null) {
            host.setStatus(tr("status.narrow.noFoldRegion"));
            return;
        }
        int start = area.getAbsolutePosition(best.startLine(), 0);
        int end = area.getAbsolutePosition(best.endLine(), area.getParagraphLength(best.endLine()));
        applyNarrow(buffer, start, end);
    }

    /** Emacs {@code widen} (`C-x n w`): restore access to the whole document. */
    void widenBuffer() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (!buffer.isNarrowed()) {
            host.setStatus(tr("status.narrow.notNarrowed"));
            return;
        }
        buffer.widen(); // the narrow-changed hook reconciles the UI
        host.setStatus(tr("status.narrow.widened"));
    }

    void applyNarrow(EditorBuffer buffer, int start, int end) {
        if (!activeEditable() && !buffer.isViewMode()) {
            return; // a huge read-only buffer cannot be narrowed (the text swap is the mechanism)
        }
        if (!buffer.narrowTo(start, end)) {
            host.setStatus(tr("status.narrow.cannot"));
            return;
        }
        host.setStatus(tr("status.narrow.narrowed"));
    }

    /**
     * Re-derives everything whose coordinates are document-relative after the accessible region changed.
     * The LSP document is suspended/resumed by {@code syncBuffer} (which now refuses a narrowed buffer),
     * and the git change bars are cleared because their line numbers refer to the whole file.
     */
    void afterNarrowChanged(EditorBuffer buffer) {
        buffer.setChangeBars(null, null);
        host.lspCoordinator().syncBuffer(buffer);
        host.statusBar().setNarrowed(buffer.isNarrowed());
        host.updateWindowTitle();
        host.git().refresh();
    }

    /**
     * Emacs' {@code killed-rectangle}: the last killed/copied rectangle, one string per line. Deliberately
     * separate from the kill ring — in Emacs {@code C-x r y} yanks this and {@code C-y} the ring, and
     * mixing them would make each corrupt the other's shape.
     */
    java.util.List<String> killedRectangle = java.util.List.of();

    /**
     * Resolves the current selection to a rectangle and hands it to {@code op}, applying the resulting
     * block replacement as one undoable edit. Rectangle commands read the ordinary mark-based selection;
     * the multi-caret box selection is a different mechanism whose carets we cannot enumerate, so rather
     * than silently acting on the primary caret's line alone we say so.
     */
    void rectangleEdit(java.util.function.BiFunction<String, Rectangle.Bounds, Rectangle.Edit> op) {
        withRectangle((buffer, area, bounds) -> {
            Rectangle.Edit edit = op.apply(area.getText(), bounds);
            if (edit == null) {
                host.setStatus(tr("status.rectangle.noChange"));
                return;
            }
            applyRectangleEdit(area, edit);
        });
    }

    /** Shared preamble for every rectangle command that needs a region: guards, then resolves bounds. */
    void withRectangle(TriConsumer<EditorBuffer, CodeArea, Rectangle.Bounds> body) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (buffer.hasMultipleCarets()) {
            host.setStatus(tr("status.rectangle.multiCaret"));
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        var sel = area.getSelection();
        if (sel.getLength() == 0) {
            host.setStatus(tr("status.rectangle.noRegion"));
            return;
        }
        body.accept(buffer, area, Rectangle.bounds(area.getText(), sel.getStart(), sel.getEnd()));
    }

    void applyRectangleEdit(CodeArea area, Rectangle.Edit edit) {
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(Math.min(edit.caret(), area.getLength()));
        deactivateMark();
        area.requestFocus();
    }

    /** Emacs {@code kill-rectangle} (`C-x r k`): remove the rectangle and remember it for a later yank. */
    void killRectangle() {
        withRectangle((buffer, area, bounds) -> {
            killedRectangle = Rectangle.extract(area.getText(), bounds);
            Rectangle.Edit edit = Rectangle.delete(area.getText(), bounds);
            if (edit != null) {
                applyRectangleEdit(area, edit);
            }
            host.setStatus(tr("status.rectangle.killed", killedRectangle.size()));
        });
    }

    /** Emacs {@code copy-rectangle-as-kill} (`C-x r M-w`): remember the rectangle without removing it. */
    void copyRectangle() {
        withRectangle((buffer, area, bounds) -> {
            killedRectangle = Rectangle.extract(area.getText(), bounds);
            deactivateMark();
            host.setStatus(tr("status.rectangle.copied", killedRectangle.size()));
        });
    }

    /** Emacs {@code yank-rectangle} (`C-x r y`): insert the last killed rectangle at the caret. */
    void yankRectangle() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (killedRectangle.isEmpty()) {
            host.setStatus(tr("status.rectangle.empty"));
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        Rectangle.Edit edit = Rectangle.yank(area.getText(), area.getCaretPosition(), killedRectangle);
        if (edit != null) {
            applyRectangleEdit(area, edit);
        }
    }

    /** Emacs {@code string-rectangle} (`C-x r t`): replace each line's segment with a typed string. */
    void stringRectangle() {
        withRectangle((buffer, area, bounds) ->
                host.promptText(tr("dialog.stringRectangle.title"), tr("dialog.stringRectangle.label"), "", value -> {
                    if (value == null) {
                        return;
                    }
                    // The prompt is an overlay, so re-resolve the text; the document cannot have moved
                    // (the overlay owns the keys) but the area reference must be read fresh either way.
                    Rectangle.Edit edit = Rectangle.replace(area.getText(), bounds, value);
                    if (edit == null) {
                        host.setStatus(tr("status.rectangle.noChange"));
                        return;
                    }
                    applyRectangleEdit(area, edit);
                }));
    }

    /** Emacs {@code rectangle-number-lines} (`C-x r N`): number the lines down the rectangle's left edge. */
    void numberRectangle() {
        withRectangle((buffer, area, bounds) ->
                host.promptText(tr("dialog.numberRectangle.title"), tr("dialog.numberRectangle.label"), "1", value -> {
                    int first;
                    try {
                        first = Integer.parseInt(value == null ? "1" : value.trim());
                    } catch (NumberFormatException e) {
                        host.setStatus(tr("status.rectangle.badNumber"));
                        return;
                    }
                    Rectangle.Edit edit = Rectangle.numberLines(area.getText(), bounds, first);
                    if (edit == null) {
                        host.setStatus(tr("status.rectangle.noChange"));
                        return;
                    }
                    applyRectangleEdit(area, edit);
                }));
    }

    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    /** Emacs {@code upcase-region} (`C-x C-u`) / {@code downcase-region} (`C-x C-l`): case the selection. */
    void emacsCaseRegion(boolean upper) {
        if (!activeEditable()) {
            return;
        }
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        var sel = area.getSelection();
        com.editora.editops.EmacsEdits.Edit edit = upper
                ? com.editora.editops.EmacsEdits.upcaseRegion(area.getText(), sel.getStart(), sel.getEnd())
                : com.editora.editops.EmacsEdits.downcaseRegion(area.getText(), sel.getStart(), sel.getEnd());
        if (edit == null) {
            return; // no selection / already the target case
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        deactivateMark();
        area.requestFocus();
    }

    /** Emacs structural caret motion (forward/backward-sexp, beginning/end-of-defun); honors the mark. */
    void sexpMove(java.util.function.BiFunction<String, Integer, Integer> nav) {
        moveAndFollow(a -> a.moveTo(nav.apply(a.getText(), a.getCaretPosition()), host.selPolicy()));
    }

    /**
     * Go to the bracket matching the one adjacent to the caret (VS Code {@code jumpToBracket},
     * {@code Ctrl+Shift+\}). Repeated presses toggle between the pair; a no-op when no bracket is adjacent.
     * Uses {@code BraceMatcher}, so — like the match highlight — it doesn't exclude a bracket inside a
     * string or comment (that needs the grammar).
     */
    void jumpToMatchingBracket() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int target = com.editora.editops.BraceMatcher.jumpTarget(
                area.getText(), area.getCaretPosition(), com.editora.editops.BraceMatcher.DEFAULT_MAX_SCAN);
        if (target < 0) {
            return;
        }
        moveAndFollow(a -> a.moveTo(target, host.selPolicy()));
    }

    /** Select from the caret's adjacent bracket to its mate, both brackets included (VS Code
     *  {@code selectToBracket}); a no-op when no bracket is adjacent. */
    void selectToBracket() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int[] span = com.editora.editops.BraceMatcher.selectSpan(
                area.getText(), area.getCaretPosition(), com.editora.editops.BraceMatcher.DEFAULT_MAX_SCAN);
        if (span == null) {
            return;
        }
        area.selectRange(span[0], span[1]);
        markActive = true;
        area.requestFollowCaret();
    }

    /** Emacs {@code mark-sexp} (`C-M-SPC`): select the balanced expression after the caret. */
    void markSexp() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int caret = area.getCaretPosition();
        int end = com.editora.editops.SexpNav.forward(area.getText(), caret);
        if (end <= caret) {
            return;
        }
        area.selectRange(caret, end);
        markActive = true;
        area.requestFollowCaret();
    }

    /** Emacs {@code mark-paragraph} (`M-h`): select the paragraph (blank-line delimited) around the caret. */
    void markParagraph() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int[] bounds = com.editora.editops.SexpNav.paragraphBounds(area.getText(), area.getCaretPosition());
        if (bounds[0] >= bounds[1]) {
            return;
        }
        area.selectRange(bounds[0], bounds[1]);
        markActive = true;
        area.requestFollowCaret();
    }

    /**
     * Semantic expand-selection (VS Code {@code Shift+Alt+Right}, IntelliJ {@code Ctrl+W}): grow the
     * selection to the next larger syntactic range — word → bracket/quote → line → defun → paragraph →
     * document — pushing each onto {@link #smartSelectStack} so {@link #shrinkSelection} can retrace it.
     */
    void expandSelection() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        // Starting a new ladder: re-anchor the server's selection-range chain at this caret (#739). The
        // request is asynchronous by design — this press uses the local ladder, and every press after it
        // uses the grammar-accurate chain, so expand never waits on a round-trip.
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null && !smartSelect.continues(start, end)) {
            host.lspCoordinator().requestSelectionChain(buffer, area.getCurrentParagraph(), area.getCaretColumn());
        }
        int[] next = smartSelect.expand(
                area.getText(),
                start,
                end,
                buffer == null ? null : host.lspCoordinator().selectionChain(buffer));
        if (next == null) {
            return; // already the whole document
        }
        area.selectRange(next[0], next[1]);
        area.requestFollowCaret();
        markActive = true;
    }

    /** Semantic shrink-selection (VS Code {@code Shift+Alt+Left}): pop back to the previous expand range. */
    void shrinkSelection() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int[] prev = smartSelect.shrink(
                area.getSelection().getStart(), area.getSelection().getEnd());
        if (prev == null) {
            return; // nothing to shrink back to
        }
        area.selectRange(prev[0], prev[1]);
        area.requestFollowCaret();
        markActive = prev[0] != prev[1];
    }

    /** Emacs {@code kill-sexp} (`C-M-k`): delete the balanced expression after the caret. */
    void killSexp() {
        emacsKill(
                (text, caret) -> {
                    int end = com.editora.editops.SexpNav.forward(text, caret);
                    return end > caret ? new com.editora.editops.EmacsEdits.Edit(caret, end, "", caret) : null;
                },
                KillRing.Direction.FORWARD);
    }

    /**
     * Emacs {@code zap-to-char} (`M-z`): read one more character, then delete from the caret up to and
     * including its next occurrence. The character is captured via a one-shot {@code KEY_TYPED} filter
     * on the focused area (interactive, like AceJump — the span computation is the pure, tested
     * {@link com.editora.editops.EmacsEdits#zapToChar}).
     */
    void zapToChar() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        host.setStatus(tr("status.zapToChar"));
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, new javafx.event.EventHandler<>() {
            @Override
            public void handle(javafx.scene.input.KeyEvent e) {
                area.removeEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, this);
                e.consume();
                String ch = e.getCharacter();
                if (ch == null || ch.isEmpty() || ch.charAt(0) < ' ') {
                    host.setStatus(""); // Escape / no printable character: cancel
                    return;
                }
                com.editora.editops.EmacsEdits.Edit edit =
                        com.editora.editops.EmacsEdits.zapToChar(area.getText(), area.getCaretPosition(), ch.charAt(0));
                if (edit == null) {
                    host.setStatus(tr("status.zapNotFound", ch));
                    return;
                }
                boolean merge = continuesPreviousKill(buffer, area.getCaretPosition());
                String killed = area.getText(edit.from(), edit.to());
                area.replaceText(edit.from(), edit.to(), edit.replacement());
                area.moveTo(edit.caret());
                pushKill(buffer, area, killed, KillRing.Direction.FORWARD, merge);
                deactivateMark();
                host.setStatus("");
            }
        });
    }

    /**
     * Emacs {@code move-to-window-line-top-bottom} (`M-r`): cycle the caret through the center, top,
     * and bottom visible lines of the editor window on successive presses.
     */
    void moveToWindowLine() {
        CodeArea a = host.activeArea();
        if (a == null) {
            return;
        }
        try {
            int first = a.firstVisibleParToAllParIndex();
            int last = a.lastVisibleParToAllParIndex();
            windowLineCycle = (windowLineCycle + 1) % 3;
            int target =
                    switch (windowLineCycle) {
                        case 0 -> (first + last) / 2; // center
                        case 1 -> first; // top
                        default -> last; // bottom
                    };
            a.moveTo(a.getAbsolutePosition(target, 0), host.selPolicy());
            a.requestFollowCaret();
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet — nothing to move to.
        }
    }

    /** Emacs {@code fill-paragraph} (`M-q`): re-wrap the paragraph at the caret to the fill column. */
    void fillParagraph() {
        applyFill((text, b) -> com.editora.editops.Filler.fillParagraph(
                text, b.getFocusedArea().getCaretPosition(), fillColumn(), lineCommentFor(b)));
    }

    /** Emacs {@code fill-region}: re-wrap every paragraph in the selection (caret line if no selection). */
    void fillRegion() {
        applyFill((text, b) -> {
            CodeArea a = b.getFocusedArea();
            int start = a.getSelection().getLength() > 0 ? a.getSelection().getStart() : a.getCaretPosition();
            int end = a.getSelection().getLength() > 0 ? a.getSelection().getEnd() : a.getCaretPosition();
            return com.editora.editops.Filler.fillRegion(text, start, end, fillColumn(), lineCommentFor(b));
        });
    }

    /** Shared applier for the fill commands (guarded by {@link #activeEditable()}). */
    void applyFill(java.util.function.BiFunction<String, EditorBuffer, com.editora.editops.Filler.Edit> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        com.editora.editops.Filler.Edit edit = op.apply(area.getText(), buffer);
        if (edit == null) {
            return; // nothing to fill / already filled
        }
        area.replaceText(edit.from(), edit.to(), edit.replacement());
        area.moveTo(edit.caret());
        area.requestFocus();
    }

    /** The active fill column (Settings), clamped to a sane minimum by {@code Settings.getFillColumn}. */
    int fillColumn() {
        return host.config().getSettings().getFillColumn();
    }

    /** The buffer language's line-comment token (e.g. {@code "//"}), or {@code null} — for the fill prefix. */
    static String lineCommentFor(EditorBuffer buffer) {
        String line =
                com.editora.editops.Commenter.styleFor(buffer.getLanguage()).line();
        return line == null || line.isBlank() ? null : line;
    }

    /** The string-manipulation command ids, in the order the {@code edit.stringOps} picker lists them. */
    static final java.util.List<String> STRING_OP_IDS = java.util.List.of(
            "edit.case.cycle",
            "edit.case.camel",
            "edit.case.pascal",
            "edit.case.snake",
            "edit.case.screamingSnake",
            "edit.case.kebab",
            "edit.case.dot",
            "edit.case.swap",
            "edit.sortLinesAsc",
            "edit.sortLinesDesc",
            "edit.sortLinesByLength",
            "edit.reverseLines",
            "edit.shuffleLines",
            "edit.removeDuplicateLines",
            "edit.removeEmptyLines",
            "edit.trimTrailingWhitespace");

    /**
     * Applies a pure token transform ({@link com.editora.editops.StringCase} — the case-style
     * commands) to the selection, or to the identifier at the caret when nothing is selected; the
     * result is re-selected so repeated invocations (the {@code edit.case.cycle} gesture) keep
     * acting on the same token. One undoable {@code replaceText}, guarded by {@link #activeEditable()}.
     */
    void caseOp(java.util.function.UnaryOperator<String> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        var sel = area.getSelection();
        int from;
        int to;
        if (sel.getLength() > 0) {
            from = sel.getStart();
            to = sel.getEnd();
        } else {
            int[] token = com.editora.editops.StringCase.tokenAt(area.getText(), area.getCaretPosition());
            if (token == null) {
                host.setStatus(tr("status.stringops.noTarget"));
                return;
            }
            from = token[0];
            to = token[1];
        }
        String before = area.getText().substring(from, to);
        String after = op.apply(before);
        if (after.equals(before)) {
            host.setStatus(tr("status.stringops.noChange"));
            return;
        }
        area.replaceText(from, to, after);
        area.selectRange(from, from + after.length());
        area.requestFocus();
    }

    /**
     * Emacs {@code occur} ({@code M-s o}): prompt for a regexp, then list every line of the active buffer
     * that matches in a keyboard picker; choosing one jumps the caret to that line. A buffer-scoped
     * counterpart to Find in Files (which is project-scoped). Matching reuses the pure
     * {@link com.editora.search.MultiFileSearch#matchesInText}; the picker keeps its own substring filter,
     * so you can narrow the occur list further as you would any picker.
     */
    void occur() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        String seed = singleLineSelection(area);
        host.promptText(tr("dialog.occur.title"), tr("dialog.occur.label"), seed, pattern -> {
            if (pattern.isEmpty()) {
                return;
            }
            String err = com.editora.editor.SearchMatcher.regexError(pattern);
            if (err != null) {
                host.setStatus(tr("find.badRegex", err));
                return;
            }
            java.util.List<com.editora.search.LineMatch> matches = occurMatches(area.getText(), pattern);
            if (matches.isEmpty()) {
                host.setStatus(tr("status.occur.none"));
                return;
            }
            QuickOpen<com.editora.search.LineMatch> picker = new QuickOpen<>(
                    tr("dialog.occur.pickerTitle", matches.size()),
                    tr("dialog.occur.pickerPrompt"),
                    () -> matches,
                    m -> m.line() + ": " + m.lineText().strip(),
                    m -> "",
                    m -> m.lineText().strip(), // search the line text, not the "N: " prefix
                    m -> host.navigateToLine(m.line() - 1));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.stage());
        });
    }

    /** The lines of {@code text} matching {@code pattern} (regex, case-insensitive) — the occur match list. */
    java.util.List<com.editora.search.LineMatch> occurMatches(String text, String pattern) {
        return com.editora.search.MultiFileSearch.matchesInText(
                text, new com.editora.search.SearchQuery(pattern, false, true, false));
    }

    /** Emacs {@code untabify}: expand tabs to spaces over the selection (else the whole buffer). */
    void untabifyRegion() {
        int tw = tabWidthForActive();
        lineTransform(before -> com.editora.editops.TabConvert.untabify(before, tw));
    }

    /** Emacs {@code tabify}: convert runs of spaces back to tabs over the selection (else the buffer). */
    void tabifyRegion() {
        int tw = tabWidthForActive();
        lineTransform(before -> com.editora.editops.TabConvert.tabify(before, tw));
    }

    /** The active buffer's effective tab width, else the global setting. */
    int tabWidthForActive() {
        EditorBuffer b = host.activeBuffer();
        int tw = b == null ? 0 : b.getTabSize();
        return tw > 0 ? tw : host.config().getSettings().getTabSize();
    }

    /** Emacs {@code align-regexp}: prompt for a regexp and pad the selection's lines to align it. */
    void alignRegexpRegion() {
        if (!activeEditable()) {
            return;
        }
        host.promptText(
                tr("dialog.alignRegexp.title"), tr("dialog.alignRegexp.label"), "", regex -> applyAlignRegexp(regex));
    }

    /** The align-regexp apply past the prompt (the FX-test seam): validate the regexp, then pad the lines. */
    void applyAlignRegexp(String regex) {
        if (regex == null || regex.isEmpty()) {
            return;
        }
        String err = com.editora.editor.SearchMatcher.regexError(regex);
        if (err != null) {
            host.setStatus(tr("find.badRegex", err));
            return;
        }
        lineTransform(before -> com.editora.editops.AlignRegexp.align(before, regex));
    }

    /**
     * VS Code {@code indentationToSpaces}/{@code indentationToTabs}: rewrite the whole file's leading
     * indentation between tabs and spaces (one undoable edit). Only leading whitespace is touched.
     */
    void convertIndentation(boolean toSpaces) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        String text = area.getText();
        String after = com.editora.editops.Indenter.convertIndentation(text, toSpaces, buffer.getTabSize());
        if (after.equals(text)) {
            host.setStatus(tr("status.indent.noChange"));
            return;
        }
        int caret = area.getCaretPosition();
        area.replaceText(after);
        area.moveTo(Math.min(caret, area.getLength()));
        area.requestFollowCaret();
        host.setStatus(tr(toSpaces ? "status.indent.toSpaces" : "status.indent.toTabs"));
    }

    void lineTransform(java.util.function.UnaryOperator<String> op) {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        CodeArea area = buffer.getFocusedArea();
        String text = area.getText();
        var sel = area.getSelection();
        int from;
        int to;
        if (sel.getLength() > 0) {
            int[] bounds = com.editora.editops.LineTransforms.lineBounds(text, sel.getStart(), sel.getEnd());
            from = bounds[0];
            to = bounds[1];
        } else {
            from = 0;
            to = text.length();
        }
        String before = text.substring(from, to);
        String after = op.apply(before);
        if (after.equals(before)) {
            host.setStatus(tr("status.stringops.noChange"));
            return;
        }
        area.replaceText(from, to, after);
        area.selectRange(from, from + after.length());
        area.requestFocus();
    }

    /** {@code edit.stringOps} (`C-c x`): one picker over all string-manipulation commands (the Alt+M popup). */
    void stringOpsPicker() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.edit.stringOps"),
                tr("palette.stringops.prompt"),
                () -> new java.util.ArrayList<>(STRING_OP_IDS),
                id -> tr("command." + id),
                id -> tr("command." + id + ".desc"),
                id -> host.registry().run(id));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Emacs {@code expand-abbrev} ({@code C-x a e}): expand the abbreviation before the caret now. */
    void expandAbbrev() {
        if (!activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        host.setStatus(buffer.expandAbbrevAtCaret() ? tr("status.abbrev.expanded") : tr("status.abbrev.noExpansion"));
    }

    /**
     * Emacs {@code add-global-abbrev} ({@code C-x a g}): define a new abbreviation. The word before the caret
     * pre-fills the abbreviation field; prompt for the abbreviation, then its expansion, and add it.
     */
    void defineAbbrev() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String seed = "";
        if (area != null) {
            int caret = area.getCaretPosition();
            seed = area.getText(com.editora.editops.Abbrev.wordStart(area.getText(), caret), caret);
        }
        host.promptText(tr("dialog.defineAbbrev.title"), tr("dialog.defineAbbrev.abbrevLabel"), seed, abbrev -> {
            if (abbrev.isBlank()) {
                return;
            }
            host.promptText(
                    tr("dialog.defineAbbrev.title"),
                    tr("dialog.defineAbbrev.expansionLabel", abbrev),
                    "",
                    expansion -> addAbbreviation(abbrev.strip(), expansion));
        });
    }

    /** Adds or replaces an abbreviation (case-insensitive on the key), persists, and re-applies to buffers. */
    void addAbbreviation(String abbrev, String expansion) {
        java.util.List<com.editora.config.Abbreviation> list =
                new java.util.ArrayList<>(host.config().getAbbreviations());
        list.removeIf(a -> a.getAbbreviation().equalsIgnoreCase(abbrev));
        list.add(new com.editora.config.Abbreviation(abbrev, expansion));
        host.config().setAbbreviations(list);
        host.config().saveAbbreviations();
        host.editorSettings().applyViewSettingsToAllBuffers(host.config().getSettings());
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncAll();
        }
        host.setStatus(tr("status.abbrev.defined", abbrev));
    }

    void setFillColumn() {
        host.promptText(
                tr("dialog.fillColumn.title"), tr("dialog.fillColumn.label"), Integer.toString(fillColumn()), value -> {
                    try {
                        int col = Integer.parseInt(value.strip());
                        if (col < 1) {
                            host.setStatus(tr("status.fillColumn.invalid"));
                            return;
                        }
                        host.config().getSettings().setFillColumn(col);
                        host.config().save();
                        host.setStatus(tr("status.fillColumn.set", col));
                    } catch (NumberFormatException e) {
                        host.setStatus(tr("status.fillColumn.invalid"));
                    }
                });
    }

    /** Selects the whole document in the active area (no edit, so it works in read-only/view mode too). */
    void selectAll() {
        CodeArea area = host.activeArea();
        if (area != null) {
            area.selectAll();
            area.requestFocus();
        }
    }

    /** Emacs-style vertical caret move (C-n/C-p) preserving the goal column; see {@link EditorBuffer#moveLine}. */
    void moveLine(int delta) {
        if (multiCaretMove(b -> b.multiMoveVertical(delta > 0, markActive))) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.moveLine(delta, host.selPolicy());
        }
    }

    /**
     * When the active buffer has multiple carets, runs {@code op} (a fork multi-caret movement that fans
     * out to every caret) and follows the primary caret; returns whether it handled the move. The Emacs
     * movement chords are resolved by the scene-level {@code KeyDispatcher} on the primary caret only, so
     * the nav.* commands branch here to reach the fork's fan-out (#635). Each {@code op} returns false when
     * no extra carets exist, so the caller falls through to its normal single-caret motion.
     */
    boolean multiCaretMove(java.util.function.Predicate<EditorBuffer> op) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !op.test(buffer)) {
            return false;
        }
        CodeArea area = host.activeArea();
        if (area != null) {
            area.requestFollowCaret(); // fork moves all carets; keep the primary in view (best-effort)
        }
        return true;
    }

    /** Run a navigation action and scroll the viewport to follow the caret. */
    void moveAndFollow(java.util.function.Consumer<CodeArea> motion) {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        motion.accept(area);
        area.requestFollowCaret();
    }

    /** Scrolls so the caret's line is vertically centered in the viewport (Emacs C-l, center). */
    void recenterCaret() {
        CodeArea a = host.activeArea();
        if (a == null) {
            return;
        }
        try {
            int cur = a.getCurrentParagraph();
            int first = a.firstVisibleParToAllParIndex();
            int last = a.lastVisibleParToAllParIndex();
            int visible = Math.max(1, last - first + 1);
            a.showParagraphAtTop(Math.max(0, cur - visible / 2));
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet — nothing to recenter.
        }
    }

    /**
     * VS Code {@code deleteWordPartLeft}/{@code Right}: delete from the caret to the next/previous subword
     * boundary as one undoable edit. Plain delete (not the kill ring), matching VS Code. Acts on the primary
     * caret only — like the Emacs word/sexp commands, it does not fan out to multiple carets.
     */
    void deleteSubword(boolean forward) {
        if (!activeEditable()) {
            return;
        }
        CodeArea area = host.activeArea();
        if (area == null || area.getSelection().getLength() > 0) {
            if (area != null && area.getSelection().getLength() > 0) {
                area.replaceSelection(""); // a selection deletes normally
            }
            return;
        }
        int caret = area.getCaretPosition();
        String text = area.getText();
        int target = forward ? TextNav.nextSubwordBoundary(text, caret) : TextNav.prevSubwordBoundary(text, caret);
        if (target == caret) {
            return;
        }
        int from = Math.min(caret, target);
        int to = Math.max(caret, target);
        area.deleteText(from, to);
    }

    /** Position of the next word boundary at or after {@code from}: skip non-word chars, then word chars. */
    static int nextWordBoundary(String text, int from) {
        int i = from;
        while (i < text.length() && !Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Position of the previous word boundary at or before {@code from}. */
    static int prevWordBoundary(String text, int from) {
        int i = from;
        while (i > 0 && !Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * Emacs {@code kill-line} (`C-k`) as a pure span: from the caret to the end of the line, or — when the
     * caret is already there — the line break itself, so repeated presses eat successive lines.
     */
    static com.editora.editops.EmacsEdits.Edit killLineEdit(String text, int caret) {
        int eol = caret;
        while (eol < text.length() && text.charAt(eol) != '\n') {
            eol++;
        }
        if (caret < eol) {
            return new com.editora.editops.EmacsEdits.Edit(caret, eol, "", caret);
        }
        return eol < text.length() ? new com.editora.editops.EmacsEdits.Edit(caret, caret + 1, "", caret) : null;
    }
}
