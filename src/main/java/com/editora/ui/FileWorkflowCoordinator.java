package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.RecentFiles;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import com.editora.io.DocumentWriteSequencer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/** Owns file loading, saving, autosave and elevated-save workflows. */
final class FileWorkflowCoordinator {

    @FunctionalInterface
    interface DocumentWriter {

        boolean write(Path target, byte[] bytes, BooleanSupplier commit) throws IOException;
    }

    private record SaveRequest(
            EditorBuffer buffer,
            Path target,
            String content,
            byte[] bytes,
            long documentVersion,
            EditorBuffer.DiskSnapshot diskSnapshot,
            long sequence,
            DocumentWriteSequencer.Ticket ticket) {}

    private record DiskWrite(long modifiedMillis, long size) {}

    private record CommittedSave(long sequence, Path target, String content, byte[] bytes, DiskWrite disk) {}

    private record AdminResult(int exit, String error, long modifiedMillis, long size) {}

    interface Host {

        EditorArea editorArea();

        Stage stage();

        ConfigManager config();

        FileBreadcrumb breadcrumb();

        ProjectPanel projectPanel();

        HistoryCoordinator historyCoordinator();

        RecentFiles recentFiles();

        void updateProjectFolderView();

        EditorSettingsCoordinator editorSettings();

        PreviewCoordinator previews();

        GitCoordinator git();

        HtmlPreviewCoordinator htmlPreview();

        LogViewerCoordinator logViewer();

        void refreshBuildTools();

        IndexCoordinator indexCoordinator();

        LspCoordinator lspCoordinator();

        boolean isLocalBuffer(EditorBuffer b);

        void setStatus(String message);

        EditorBuffer activeBuffer();

        Tab addBuffer(EditorBuffer buffer);

        Tab addBuffer(EditorBuffer buffer, boolean select);

        Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings);

        Tab addContentTab(TabContent content, boolean select);

        void updateTabMeta(Tab tab, EditorBuffer buffer);

        void promoteTab(Tab tab);

        void finishAsyncOpen(Tab tab, EditorBuffer buffer, PreparedLoad load);

        void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error);

        String autoSaveModeOf(String mode);

        String autoSaveLabel(String mode);

        EditorBuffer bufferOf(Tab tab);

        Tab tabFor(EditorBuffer buffer);

        Tab tabForPath(Path file);

        Path tabPath(Tab tab);

        void requestSave();

        Tab tabForBuffer(EditorBuffer buffer);

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);

        Path pathOf(java.io.File file);
    }

    private final Host host;
    /** Physical commits are published before their FX callbacks, so a following autosave can identify the
     * previous application-owned disk state without mistaking it for an external edit. */
    private final Map<String, CommittedSave> committedSaves = new ConcurrentHashMap<>();
    /** FX-confined count used by close decisions: a clean buffer with an unresolved save is not safe to close. */
    private final Map<EditorBuffer, Integer> pendingSaves = new IdentityHashMap<>();

    private final Set<SaveRequest> activeSaveRequests = ConcurrentHashMap.newKeySet();
    private final AtomicLong saveSequence = new AtomicLong();

    /** Local-document persistence boundary. Package-visible replacement supports deterministic faults. */
    private volatile DocumentWriter documentWriter = com.editora.io.AtomicFileWrite::writeIf;

    /** Test seam for holding an actual write worker while proving the FX thread remains responsive. */
    volatile Runnable beforeDocumentWriteForTest;

    FileWorkflowCoordinator(Host host) {
        this.host = host;
    }

    void setDocumentWriter(DocumentWriter documentWriter) {
        this.documentWriter = java.util.Objects.requireNonNull(documentWriter, "documentWriter");
    }

    static final String AUTOSAVE_OFF = "off";

    static final String AUTOSAVE_DELAY = "afterDelay";

    static final String AUTOSAVE_FOCUS = "onFocusChange";

    final PauseTransition autoSaveIdleTimer = new PauseTransition(Duration.millis(1000));

    final ExecutorService autoSaveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editora-autosave");
        t.setDaemon(true);
        return t;
    });

    /** Disk reads/charset decoding for file opens. Virtual threads also keep remote-provider waits cheap. */
    final ExecutorService fileLoadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** A paragraph this wide makes RichTextFX layout pathological even when the file itself is small. */
    static final int LONG_LINE_FILE_CHARS = 64 * 1024;

    /** Buffers whose tab shell exists but whose document has not reached the FX thread yet. FX-thread only. */
    final Set<EditorBuffer> loadingBuffers = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Navigation requested while a shell is loading (CLI file:line, diagnostics, external-open). */
    final Map<EditorBuffer, List<Runnable>> afterBufferLoad = new IdentityHashMap<>();

    /** Re-entrancy guard so the external-change prompt (which steals focus) doesn't re-trigger itself. */
    boolean checkingExternalChanges;

    void openPath(Path file) {
        openPath(file, false);
    }

    /**
     * Opens a file, or re-selects its tab when it is already open.
     *
     * <p>{@code quietIfOpen} suppresses the "Already open" echo for the startup path: a command-line
     * {@code FILE} that is also part of the restored session has its tab created + selected by
     * {@link #openInitialBuffer()} a pulse earlier, so the CLI action's own {@code openPath} always finds it
     * there — reporting "Already open" for a tab Editora itself just made is noise, not information.
     */
    void openPath(Path file, boolean quietIfOpen) {
        Tab existing = host.tabForPath(file);
        if (existing != null) {
            // Already open — switch to its tab instead of opening a duplicate. Asking for it explicitly
            // is a choice, so if it was only being previewed it stops being disposable.
            host.promoteTab(existing);
            host.editorArea().select(existing);
            EditorBuffer existingBuffer = host.bufferOf(existing);
            if (existingBuffer != null) {
                existingBuffer.getArea().requestFocus();
            }
            if (host.recentFiles() != null) {
                host.recentFiles().add(file);
            }
            if (!quietIfOpen) {
                host.setStatus(tr("status.alreadyOpen", file.getFileName()));
            }
            return;
        }
        // A raster image opens in the read-only image viewer instead of dumping its bytes into a text buffer.
        if (ImageFormats.isSupported(file.getFileName().toString())) {
            openImageTab(file, true);
            if (host.recentFiles() != null) {
                host.recentFiles().add(file);
            }
            host.setStatus(tr("status.opened", com.editora.config.PathDisplay.of(file)));
            return;
        }
        // A PDF opens in the read-only PDF viewer (rasterized pages) instead of the hex viewer.
        if (PdfViewerPane.isPdf(file.getFileName().toString())) {
            openPdfTab(file, true);
            if (host.recentFiles() != null) {
                host.recentFiles().add(file);
            }
            host.setStatus(tr("status.opened", com.editora.config.PathDisplay.of(file)));
            return;
        }
        // Every text candidate is classified/read/decoded in the background. Even a tiny local file can live
        // on a cold, network-mounted, or FUSE-backed path, so size is not a safe proxy for FX-thread latency.
        openTextBufferAsync(file, true);
    }

    /**
     * Adds a responsive tab shell immediately, then reads/decodes on a virtual thread and performs exactly
     * one RichTextFX insertion back on the FX thread. {@code classifyBinary} is false for Open as Text.
     */
    void openTextBufferAsync(Path file, boolean classifyBinary) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(file);
        // Prevent the empty shell from starting LSP/minimap work or accepting edits before its document lands.
        buffer.setHeavyFile(true);
        buffer.setLoading(true);
        loadingBuffers.add(buffer);
        Tab tab = host.addBuffer(buffer, true, false);
        fileLoadExecutor.execute(() -> {
            try {
                PreparedLoad load = prepareLoad(file, classifyBinary);
                Platform.runLater(() -> host.finishAsyncOpen(tab, buffer, load));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> host.failAsyncOpen(tab, buffer, file, e));
            }
        });
    }

    /** Runs an action after an asynchronous buffer load reaches the FX thread, or now if it already has. */
    void afterBufferLoad(EditorBuffer buffer, Runnable action) {
        if (loadingBuffers.contains(buffer)) {
            afterBufferLoad
                    .computeIfAbsent(buffer, ignored -> new ArrayList<>())
                    .add(action);
        } else {
            action.run();
        }
    }

    /** Opens {@code file} in a read-only {@link ImageViewerPane} tab (raster images render, not their bytes). */
    Tab openImageTab(Path file, boolean select) {
        ImageViewerPane pane = new ImageViewerPane(file);
        Tab tab = host.addContentTab(pane, select);
        // Disposal is driven by the tabs ListChangeListener (see disposeViewerTab) — setOnClosed here would
        // only fire for a click on the ✕, never for Ctrl-W / Close All / window close.
        Platform.runLater(pane::relayout); // fit-to-window once the tab is laid out
        return tab;
    }

    /** Opens {@code file} in a read-only {@link HexViewerPane} tab (a binary shows its bytes, not garbage text). */
    Tab openHexTab(Path file, boolean select) {
        HexViewerPane pane = new HexViewerPane(file);
        Tab tab = host.addContentTab(pane, select);
        return tab;
    }

    /** Opens {@code file} in a read-only {@link PdfViewerPane} tab (a PDF renders its pages, not its bytes). */
    Tab openPdfTab(Path file, boolean select) {
        PdfViewerPane pane = new PdfViewerPane(file);
        Tab tab = host.addContentTab(pane, select);
        Platform.runLater(pane::relayout); // fit-to-window once the tab is laid out
        return tab;
    }

    /**
     * True when {@code file} looks like a binary (so it opens in the hex viewer, not as garbage text): reads a
     * small sample and applies the {@link BinarySniff} heuristic. Unreadable ⇒ false (the text path reports the
     * error). Skipped for the huge-file case is unnecessary — only a bounded {@link BinarySniff#SAMPLE_BYTES}
     * prefix is read regardless of file size.
     */
    static boolean looksBinaryFile(Path file) {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            return BinarySniff.looksBinary(in.readNBytes(BinarySniff.SAMPLE_BYTES));
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /** Command {@code view.openAsHex}: opens the active file's bytes in a read-only hex tab (forces hex even
     *  for a text file). No-op with a status when the active tab has no file (an untitled buffer / Welcome). */
    void openActiveAsHex() {
        Path p = host.tabPath(host.editorArea().selectedTab());
        if (p == null) {
            host.setStatus(tr("status.hex.noFile"));
            return;
        }
        openHexTab(p, true);
        host.setStatus(tr("status.opened", com.editora.config.PathDisplay.of(p)));
    }

    /**
     * Reads {@code file} into {@code buffer} and applies the size-based mode, returning a status note
     * ({@code ""} for a normal file):
     * <ul>
     *   <li>≥ {@link EditorBuffer#HUGE_FILE_BYTES}: read at most that many chars (so a multi-GB file
     *       can't exhaust memory) and open read-only;</li>
     *   <li>≥ {@link EditorBuffer#LARGE_FILE_BYTES}: full read, but highlighting + minimap disabled;</li>
     *   <li>otherwise: full read, normal editing.</li>
     * </ul>
     */
    String loadInto(EditorBuffer buffer, Path file) throws IOException {
        String note = applyPreparedLoad(buffer, prepareLoad(file, false));
        notePerfContentLoaded(buffer);
        return note;
    }

    /**
     * Startup instrumentation (inert unless {@code -Deditora.perf}): a buffer's content is in place, so the
     * next rendered frame is the one that shows the user their file.
     *
     * <p>The <b>first</b> loaded buffer is the one that counts, and it is deliberately not gated on being the
     * active buffer: on the fresh-open path ({@code --no-session}, or any file not in the session)
     * {@code loadInto} runs <em>before</em> the tab is added, so the buffer isn't selected yet and such a
     * guard silently never fired — the run then never marked first paint at all. Taking the first load is
     * correct in both paths anyway, since the CLI target is front-loaded: with a session it's the selected
     * tab filled first, without one it's the only file opened.
     */
    void notePerfContentLoaded(EditorBuffer buffer) {
        if (!com.editora.perf.Startup.enabled() || perfPaintTimerStarted) {
            return;
        }
        perfPaintTimerStarted = true;
        com.editora.perf.Startup.mark(com.editora.perf.Startup.FILE_LOADED);
        // A pulse's handle() runs at the *start* of a pulse, before that pulse renders. So the first tick
        // only means "a pulse is beginning"; it's the second tick that proves the pulse in between — the one
        // that laid out and painted this content — completed. Accurate to about one frame (~16 ms), which is
        // the honest resolution of "when did the user first see it" without hooking Prism internals.
        new javafx.animation.AnimationTimer() {
            private int ticks;

            @Override
            public void handle(long now) {
                if (++ticks >= 2) {
                    stop();
                    com.editora.perf.Startup.mark(com.editora.perf.Startup.FIRST_PAINT);
                }
            }
        }.start();
    }

    /** True once the first-paint timer has been armed, so it arms for one buffer only. */
    boolean perfPaintTimerStarted;

    /** Immutable disk-side result; no JavaFX object is touched while this is prepared. */
    record PreparedLoad(
            Path file,
            String content,
            long size,
            long mtime,
            int lines,
            int maxLineLength,
            String charset,
            com.editora.editorconfig.EditorConfigProperties editorConfig,
            boolean binary,
            boolean large,
            boolean heavy,
            boolean longLine,
            boolean truncated,
            boolean log,
            long logOffset,
            boolean tail) {}

    /** Document shape computed while the decoded text is already in background-thread memory. */
    record TextStats(int lines, int maxLineLength) {}

    static TextStats textStats(String text) {
        int lines = 1;
        int lineLength = 0;
        int maxLineLength = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
                maxLineLength = Math.max(maxLineLength, lineLength);
                lineLength = 0;
            } else {
                lineLength++;
            }
        }
        return new TextStats(lines, Math.max(maxLineLength, lineLength));
    }

    PreparedLoad prepareLoad(Path file, boolean sniffBinary) throws IOException {
        // One stat call for both size + mtime instead of two separate syscalls per file load.
        long size;
        long mtime;
        try {
            var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            size = attrs.size();
            mtime = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            size = fileSize(file);
            mtime = lastModifiedMillis(file);
        }
        boolean isLog = host.logViewer().handlesLogFile(file);
        com.editora.editorconfig.EditorConfigProperties editorConfig =
                host.editorSettings().editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)
                        ? com.editora.editorconfig.EditorConfig.resolveFor(file)
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY;
        if (size >= EditorBuffer.HUGE_FILE_BYTES) {
            boolean binary = sniffBinary && looksBinaryFile(file);
            if (binary) {
                return new PreparedLoad(
                        file,
                        null,
                        size,
                        mtime,
                        0,
                        0,
                        null,
                        editorConfig,
                        true,
                        true,
                        false,
                        false,
                        true,
                        isLog,
                        0,
                        false);
            }
            if (isLog) {
                // A huge log opens at its END (the tail is what matters) instead of the first chunk.
                com.editora.logviewer.LogTail.Tail tail =
                        com.editora.logviewer.LogTail.readTail(file, EditorBuffer.HUGE_FILE_BYTES);
                TextStats stats = textStats(tail.text());
                return new PreparedLoad(
                        file,
                        tail.text(),
                        size,
                        mtime,
                        stats.lines(),
                        stats.maxLineLength(),
                        com.editora.editorconfig.EditorConfigCharset.UTF_8,
                        editorConfig,
                        false,
                        true,
                        false,
                        stats.maxLineLength() >= LONG_LINE_FILE_CHARS,
                        true,
                        true,
                        tail.offset(),
                        true);
            }
            String content = readCapped(file, (int) EditorBuffer.HUGE_FILE_BYTES);
            TextStats stats = textStats(content);
            return new PreparedLoad(
                    file,
                    content,
                    size,
                    mtime,
                    stats.lines(),
                    stats.maxLineLength(),
                    com.editora.editorconfig.EditorConfigCharset.UTF_8,
                    editorConfig,
                    false,
                    true,
                    false,
                    stats.maxLineLength() >= LONG_LINE_FILE_CHARS,
                    true,
                    false,
                    0,
                    false);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (sniffBinary) {
            byte[] sample = bytes.length <= BinarySniff.SAMPLE_BYTES
                    ? bytes
                    : java.util.Arrays.copyOf(bytes, BinarySniff.SAMPLE_BYTES);
            if (BinarySniff.looksBinary(sample)) {
                return new PreparedLoad(
                        file,
                        null,
                        size,
                        mtime,
                        0,
                        0,
                        null,
                        editorConfig,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        0,
                        false);
            }
        }
        String charset = com.editora.editorconfig.EditorConfigCharset.resolveName(bytes, editorConfig.charset());
        String content = com.editora.editorconfig.EditorConfigCharset.decode(bytes, charset);
        boolean large = size >= EditorBuffer.LARGE_FILE_BYTES;
        // Intermediate tier: a very long single file (e.g. a 13k-line source) keeps highlighting + editing
        // but drops the minimap + LSP — the two heaviest features for a huge source — so it stays responsive.
        int threshold = host.config().getSettings().getLargeFileThreshold();
        TextStats stats = textStats(content);
        boolean longLine = stats.maxLineLength() >= LONG_LINE_FILE_CHARS;
        boolean heavy = !large && !longLine && threshold > 0 && stats.lines() >= threshold;
        return new PreparedLoad(
                file,
                content,
                size,
                mtime,
                stats.lines(),
                stats.maxLineLength(),
                charset,
                editorConfig,
                false,
                large,
                heavy,
                longLine,
                false,
                isLog,
                size,
                false);
    }

    /** Applies a prepared document atomically on the FX thread, with all expensive-mode flags already active. */
    String applyPreparedLoad(EditorBuffer buffer, PreparedLoad load) {
        buffer.setDiskSnapshot(load.mtime(), load.size());
        buffer.setTruncatedLoad(load.truncated());
        host.editorSettings().applyResolvedEditorConfig(buffer, load.editorConfig());
        buffer.setDetectedCharset(load.charset());
        buffer.setLargeFile(load.large() || load.longLine());
        buffer.setHeavyFile(load.heavy());
        if (load.longLine()) {
            // Wrapping a giant RichTextFX paragraph multiplies layout work. The user can explicitly turn it
            // back on after loading, but the first rendered frame must use the safe profile.
            buffer.setWordWrap(false);
        }
        if (load.truncated()) {
            buffer.setReadOnly(true);
        }
        buffer.setInitialContent(load.content(), load.longLine());
        if (load.log()) {
            host.logViewer().recordLoadOffset(buffer, load.logOffset());
        }
        if (load.truncated()) {
            return load.file().getFileName()
                    + (load.tail() ? " — very large log (" : " — very large file (")
                    + StatusBar.formatSize(load.size())
                    + (load.tail() ? "): read-only, showing last " : "): read-only, showing first ")
                    + StatusBar.formatSize(load.content().length());
        }
        if (load.large()) {
            return largeFileNote(load.file(), load.size());
        }
        if (load.longLine()) {
            return tr("status.longLineFileTier", load.maxLineLength());
        }
        return load.heavy() ? tr("status.largeFileTier", load.lines()) : "";
    }

    /** Reads up to {@code maxChars} characters (UTF-8) from {@code file}, bounding memory use. */
    static String readCapped(Path file, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 1 << 20));
        char[] buf = new char[1 << 16];
        try (java.io.Reader r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            int read;
            while (sb.length() < maxChars
                    && (read = r.read(buf, 0, Math.min(buf.length, maxChars - sb.length()))) != -1) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }

    String largeFileNote(Path file) {
        return largeFileNote(file, fileSize(file));
    }

    static String largeFileNote(Path file, long size) {
        return file.getFileName() + " — large file (" + StatusBar.formatSize(size)
                + "): syntax highlighting and minimap disabled";
    }

    static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The file's last-modified time in epoch millis, or {@code -1} when unavailable. */
    static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Checks the <em>active</em> tab's file against its on-disk snapshot (taken at load/save) and, if it
     * was modified by another program, prompts to reload or keep the in-editor version. Run when the
     * window regains focus and when the user switches tabs — so the prompt only appears for the file the
     * user is actually looking at (background tabs are checked when switched to). Deleted files are left
     * alone (the in-editor copy is kept).
     */
    void checkExternalChanges() {
        if (checkingExternalChanges || host.editorArea() == null) {
            return;
        }
        checkingExternalChanges = true;
        try {
            Tab tab = host.editorArea().selectedTab();
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer == null || buffer.getPath() == null) {
                return;
            }
            Path file = buffer.getPath();
            if (com.editora.vfs.Vfs.isRemote(file)) {
                return; // remote (SFTP) mtime polling would be a network call per focus — skipped for now
            }
            if (!Files.exists(file)) {
                return; // deleted/renamed externally — keep what's open (no prompt)
            }
            long mtime = lastModifiedMillis(file);
            long size = fileSize(file);
            if (buffer.diskChangedFrom(mtime, size)) {
                promptExternalChange(tab, buffer, mtime, size);
            }
        } finally {
            checkingExternalChanges = false;
        }
    }

    /** Asks whether to reload an externally-modified file; "keep" just re-baselines so it stops prompting. */
    void promptExternalChange(Tab tab, EditorBuffer buffer, long mtime, long size) {
        String name = buffer.getPath().getFileName().toString();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(host.stage());
        alert.setTitle(tr("dialog.externalChange.title"));
        alert.setHeaderText(tr("dialog.externalChange.header", name));
        alert.setContentText(buffer.isDirty() ? tr("dialog.externalChange.dirty") : tr("dialog.externalChange.clean"));
        ButtonType reload = new ButtonType(tr("dialog.externalChange.reload"));
        ButtonType keep = new ButtonType(
                buffer.isDirty() ? tr("dialog.externalChange.keepMine") : tr("dialog.externalChange.keep"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reload, keep);
        if (alert.showAndWait().filter(b -> b == reload).isPresent()) {
            reloadFromDisk(tab, buffer);
        } else {
            buffer.setDiskSnapshot(mtime, size); // keep the editor's version, stop nagging about this change
        }
    }

    /** Reloads a buffer's content from disk, preserving the caret position as best it can. */
    void reloadFromDisk(Tab tab, EditorBuffer buffer) {
        Path file = buffer.getPath();
        invalidatePendingWrite(file);
        try {
            CodeArea area = buffer.getArea();
            int caret = area.getCaretPosition();
            host.historyCoordinator()
                    .record(buffer, HistoryRevision.REASON_EXTERNAL); // snapshot the in-memory version before disk wins
            String note = loadInto(buffer, file); // replaces content + re-baselines the disk snapshot
            buffer.markClean();
            area.moveTo(Math.min(caret, area.getLength()));
            host.updateTabMeta(tab, buffer);
            host.lspCoordinator().watchedFilesReloaded(java.util.List.of(file)); // the server's model too (#677)
            host.setStatus(note.isEmpty() ? tr("status.reloaded", file.getFileName()) : note);
        } catch (IOException e) {
            host.setStatus(tr("status.failedReload", file.getFileName(), e.getMessage()));
        }
    }

    void onSave() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            save(buffer);
        }
    }

    /** {@code file.saveAsAdmin}: write the active buffer via the OS auth agent (root-owned files). */
    void onSaveAsAdmin() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (buffer.getPath() == null) {
            saveAs(buffer);
            return;
        }
        if (!elevationAvailable()) {
            host.setStatus(tr("status.admin.unavailable"));
            return;
        }
        saveAsAdmin(buffer);
    }

    void onSaveAs() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            saveAs(buffer);
        }
    }

    /** Whether the elevation tool is present (macOS osascript always; Linux pkexec probed on PATH). */
    boolean adminToolAvailable;

    /** Admin-save is enabled in Settings and the OS supports it (Linux/polkit or macOS/osascript). */
    boolean adminSaveEnabled() {
        return host.config().getSettings().isAdminSave()
                && com.editora.process.ElevatedSave.supportedOnOs(System.getProperty("os.name"));
    }

    /** Admin-save is enabled and the elevation tool (pkexec/osascript) is actually available. */
    boolean elevationAvailable() {
        return adminSaveEnabled() && adminToolAvailable;
    }

    /** True when a plain save of {@code p} would fail for permissions but an elevated save could succeed. */
    boolean adminSaveApplicable(Path p) {
        return elevationAvailable()
                && p != null
                && com.editora.vfs.Vfs.isLocal(p)
                && Files.exists(p)
                && !Files.isWritable(p);
    }

    /**
     * Detects the elevation tool when admin-save is enabled and pushes the "Edit as Administrator"
     * affordance to every open buffer. macOS ships {@code osascript}, so it's available immediately;
     * Linux probes {@code pkexec} off the FX thread (it spawns a process) and updates the gate when it
     * returns. Mirrors {@code applyRipgrepSupport}.
     */
    void applyAdminSaveSupport() {
        if (!adminSaveEnabled()) {
            adminToolAvailable = false;
            pushAdminEditAvailable();
            return;
        }
        if (com.editora.process.ElevatedSave.isMac(System.getProperty("os.name"))) {
            adminToolAvailable = true; // osascript is always present on macOS
            pushAdminEditAvailable();
            return;
        }
        new Thread(
                        () -> {
                            boolean ok;
                            try {
                                ok = com.editora.process.ProcessRunner.run(
                                                        null,
                                                        java.time.Duration.ofSeconds(5),
                                                        List.of(com.editora.process.ElevatedSave.PKEXEC, "--version"))
                                                .exit()
                                        == 0;
                            } catch (RuntimeException e) {
                                ok = false;
                            }
                            boolean available = ok;
                            Platform.runLater(() -> {
                                adminToolAvailable = available;
                                pushAdminEditAvailable();
                            });
                        },
                        "pkexec-detect")
                .start();
    }

    /** Pushes the current "Edit as Administrator" availability to every open buffer's banner. */
    void pushAdminEditAvailable() {
        boolean available = elevationAvailable();
        for (Tab t : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(t);
            if (b != null) {
                b.setAdminEditAvailable(available && host.isLocalBuffer(b));
            }
        }
    }

    /**
     * Blocks every write path for a buffer the loader could only read <em>part</em> of (a file at/over
     * {@link EditorBuffer#HUGE_FILE_BYTES}: the first 50 MB, or a log's <em>last</em> 50 MB). Such a buffer is
     * a slice, not the file — writing it back with {@code Files.write} would truncate the real file on disk
     * and destroy everything outside the slice. The buffer is read-only, but that only stops <em>typing</em>:
     * {@code file.save} (Ctrl/Cmd-S) needs no edit and no dirty flag to fire, so it has to be refused here.
     *
     * @return true when the save was refused (the caller must not write).
     */
    boolean refuseTruncatedSave(EditorBuffer buffer) {
        if (buffer == null || !buffer.isTruncatedLoad()) {
            return false;
        }
        host.setStatus(tr("status.truncatedNoSave"));
        return true;
    }

    boolean save(EditorBuffer buffer) {
        if (refuseTruncatedSave(buffer)) {
            return false;
        }
        if (buffer.getPath() == null) {
            return saveAs(buffer);
        }
        if (adminSaveApplicable(buffer.getPath())) {
            saveAsAdmin(buffer); // async elevated write; the buffer stays dirty until it completes
            return true;
        }
        return writeBuffer(buffer, buffer.getPath());
    }

    /** Synchronous variant for close/run/debug flows that must observe the saved bytes before continuing. */
    boolean saveSynchronously(EditorBuffer buffer) {
        if (refuseTruncatedSave(buffer)) {
            return false;
        }
        if (buffer.getPath() == null) {
            return saveAsSynchronously(buffer);
        }
        if (adminSaveApplicable(buffer.getPath())) {
            saveAsAdmin(buffer);
            return false;
        }
        return writeBufferSynchronously(buffer, buffer.getPath());
    }

    /**
     * Writes {@code buffer} to its (e.g. root-owned) file via the OS auth agent ({@code pkexec}/polkit),
     * which prompts for the password itself — Editora never handles it. The bytes go to a private temp
     * file, then {@code cat tmp > target} runs as root, truncating the target in place so its owner and
     * permissions are preserved. Runs off the FX thread (the auth dialog blocks); the result is applied back
     * on the FX thread.
     */
    void saveAsAdmin(EditorBuffer buffer) {
        Path target = buffer.getPath();
        if (target == null) {
            saveAs(buffer);
            return;
        }
        if (!elevationAvailable()) {
            host.setStatus(tr("status.admin.unavailable"));
            return;
        }
        SaveRequest request = captureSave(buffer, target);
        host.setStatus(tr("status.admin.saving", com.editora.config.PathDisplay.of(target)));
        new Thread(
                        () -> {
                            try {
                                var outcome = request.ticket().runIfCurrent(() -> runElevatedWrite(request));
                                AdminResult result = outcome.executed()
                                        ? outcome.value()
                                        : new AdminResult(-2, "superseded", -1, -1);
                                if (result.exit() == 0) {
                                    publishCommit(request, new DiskWrite(result.modifiedMillis(), result.size()));
                                }
                                Platform.runLater(() -> onAdminSaveDone(request, result));
                            } catch (IOException | RuntimeException e) {
                                AdminResult result = new AdminResult(-1, e.getMessage(), -1, -1);
                                Platform.runLater(() -> onAdminSaveDone(request, result));
                            }
                        },
                        "admin-save")
                .start();
    }

    private AdminResult runElevatedWrite(SaveRequest request) throws IOException {
        Path tmp = Files.createTempFile("editora-admin-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(
                        tmp,
                        java.util.Set.of(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignore) {
                // Non-POSIX filesystem: the temp file keeps default permissions.
            }
            Files.write(tmp, request.bytes());
            var result = com.editora.process.ProcessRunner.run(
                    null,
                    java.time.Duration.ofMinutes(2),
                    com.editora.process.ElevatedSave.elevatedArgv(
                            System.getProperty("os.name"),
                            com.editora.process.ElevatedSave.PKEXEC,
                            tmp,
                            request.target()));
            return new AdminResult(
                    result.exit(), result.err(), lastModifiedMillis(request.target()), fileSize(request.target()));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Applies the outcome of an elevated save on the FX thread (ok / user-cancelled / failed). */
    void onAdminSaveDone(SaveRequest request, AdminResult result) {
        try {
            EditorBuffer buffer = request.buffer();
            Path target = request.target();
            if (result.exit() == 0) {
                host.historyCoordinator().record(target, request.content(), HistoryRevision.REASON_SAVE);
                acknowledgeLatestCommit(buffer);
                if (request.ticket().isCurrent() && !buffer.isDisposed()) {
                    host.setStatus(tr("status.admin.saved", com.editora.config.PathDisplay.of(target)));
                    host.git().refresh();
                    host.lspCoordinator().notifyDocumentSaved(buffer);
                    Tab tab = host.tabForBuffer(buffer);
                    if (tab != null) {
                        host.updateTabMeta(tab, buffer);
                    }
                }
            } else if (result.exit() == -2 || !request.ticket().isCurrent()) {
                return;
            } else if (com.editora.process.ElevatedSave.isCancellation(
                    System.getProperty("os.name"), result.exit(), result.error())) {
                host.setStatus(tr("status.admin.cancelled"));
            } else {
                host.setStatus(tr(
                        "status.admin.failed",
                        result.error() == null || result.error().isBlank()
                                ? String.valueOf(result.exit())
                                : result.error()));
            }
        } finally {
            finishRequest(request);
        }
    }

    boolean saveAs(EditorBuffer buffer) {
        return saveAs(buffer, false);
    }

    private boolean saveAsSynchronously(EditorBuffer buffer) {
        return saveAs(buffer, true);
    }

    private boolean saveAs(EditorBuffer buffer, boolean synchronous) {
        if (refuseTruncatedSave(buffer)) {
            return false;
        }
        if (com.editora.vfs.Vfs.isRemote(buffer.getPath())) {
            // A remote buffer always opens with a path, so plain Save writes it back over SFTP; choosing a
            // new remote destination (an async prompt) isn't supported yet.
            host.setStatus(tr("status.remote.saveAsUnsupported"));
            return false;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveAs.title"));
        if (buffer.getDisplayName() != null) {
            chooser.setInitialFileName(buffer.getDisplayName()); // suggested name from --new-file=NAME
        }
        Path file = host.pathOf(chooser.showSaveDialog(host.stage()));
        if (file == null) {
            return false;
        }
        return applySaveAsTarget(buffer, file, synchronous);
    }

    /** Points {@code buffer} at {@code file}, refreshes its previews/tab/breadcrumb, and writes it. */
    boolean applySaveAsTarget(EditorBuffer buffer, Path file) {
        return applySaveAsTarget(buffer, file, false);
    }

    private boolean applySaveAsTarget(EditorBuffer buffer, Path file, boolean synchronous) {
        invalidatePendingWrites(buffer);
        buffer.setPath(file);
        // The buffer's EditorConfig properties + charset were resolved against the OLD path. Without
        // re-resolving, a Save-As into another tree writes with the previous project's charset/EOL/trim rules
        // (and keeps doing so on every later save), while an untitled buffer saved INTO a project with an
        // .editorconfig gets none of its rules.
        host.editorSettings().applyEditorConfig(buffer);
        host.previews().ensurePreviewControls(buffer); // a new untitled saved as .md/.mmd now gets the preview toggle
        host.htmlPreview().ensureControl(buffer); // a save-as to .html now gets the "open in browser" globe
        host.logViewer().ensureControl(buffer); // a save-as to .log now gets the log control + level overlay
        boolean ok = synchronous ? writeBufferSynchronously(buffer, file) : writeBuffer(buffer, file);
        Tab tab = host.tabFor(buffer);
        if (tab != null) {
            host.updateTabMeta(tab, buffer);
        }
        if (buffer == host.activeBuffer()) {
            host.breadcrumb().setActiveFile(buffer.getPath());
            host.updateProjectFolderView(); // global window: an untitled buffer just gained a folder to show
        }
        return ok;
    }

    /**
     * The keyboard-first Save As: instead of the native file chooser (which the toolbar button uses), prompt
     * for the target path in the in-scene overlay so the command palette / a keybinding stay mouse-free. The
     * field is pre-filled with the current path (or the project folder + suggested name for an untitled
     * buffer); a relative name resolves against that folder, {@code ~} expands to home. Confirms before
     * overwriting a different existing file.
     */
    void saveAsPrompt(EditorBuffer buffer) {
        if (buffer == null || refuseTruncatedSave(buffer)) {
            return;
        }
        if (buffer.getPath() != null && com.editora.vfs.Vfs.isRemote(buffer.getPath())) {
            host.setStatus(tr("status.remote.saveAsUnsupported"));
            return;
        }
        Path base = saveAsBaseDir(buffer);
        host.promptText(tr("dialog.saveAs.title"), tr("dialog.saveAs.prompt"), saveAsInitial(buffer, base), value -> {
            Path target = com.editora.config.PathKeys.resolveUserInput(value, base, System.getProperty("user.home"));
            if (target == null) {
                host.setStatus(tr("status.saveAs.invalidPath"));
                return;
            }
            Path current = buffer.getPath();
            boolean overwritingOther = Files.exists(target)
                    && (current == null || !com.editora.config.PathKeys.sameNormalized(target, current));
            if (overwritingOther && !confirmOverwrite(target)) {
                return;
            }
            applySaveAsTarget(buffer, target);
        });
    }

    /** The folder a typed Save-As name resolves against: the current file's folder, else project root/home. */
    Path saveAsBaseDir(EditorBuffer buffer) {
        Path p = buffer.getPath();
        if (p != null && com.editora.vfs.Vfs.isLocal(p) && p.getParent() != null) {
            return p.getParent();
        }
        Path root = host.projectPanel() == null ? null : host.projectPanel().getRoot();
        if (root != null && com.editora.vfs.Vfs.isLocal(root)) {
            return root;
        }
        return Path.of(System.getProperty("user.home"));
    }

    /** The Save-As prompt's pre-filled value: the current absolute path, else the base folder + a name. */
    String saveAsInitial(EditorBuffer buffer, Path base) {
        Path p = buffer.getPath();
        if (p != null && com.editora.vfs.Vfs.isLocal(p)) {
            return p.toString();
        }
        String name = buffer.getDisplayName() != null ? buffer.getDisplayName() : "untitled.txt";
        return base.resolve(name).toString();
    }

    /** Native confirmation before overwriting an existing (different) file from the keyboard Save-As prompt. */
    boolean confirmOverwrite(Path target) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(host.stage());
        alert.setTitle(tr("dialog.saveAs.overwrite.title"));
        alert.setHeaderText(tr("dialog.saveAs.overwrite.header", target.getFileName()));
        alert.setContentText(tr("dialog.saveAs.overwrite.content"));
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /**
     * The exact bytes to write for {@code buffer}: the EditorConfig save transforms (trim trailing
     * whitespace / final newline / end-of-line) applied to the text, then encoded in the effective charset
     * (BOM-aware). The live document is not mutated — only what we write. Inert (content + detected charset)
     * when EditorConfig is off.
     */
    byte[] saveBytes(EditorBuffer buffer) {
        return saveBytes(buffer, buffer.getContent());
    }

    private byte[] saveBytes(EditorBuffer buffer, String content) {
        com.editora.editorconfig.EditorConfigProperties p =
                host.editorSettings().editorConfigEnabled()
                        ? buffer.getEditorConfigProps()
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY;
        String text = com.editora.editorconfig.EditorConfigTransform.transform(content, p);
        String charset = buffer.getEffectiveCharset();
        // A charset that can't represent what the user typed (an em dash / curly quote / emoji under
        // `charset = latin1`) would be written as '?' by String.getBytes — and the editor keeps showing the
        // real character until the file is reopened, so the corruption is invisible until it's permanent.
        // Fall back to UTF-8 and say so: a changed encoding is recoverable, mangled text is not.
        if (!com.editora.editorconfig.EditorConfigCharset.canEncode(text, charset)) {
            host.setStatus(
                    tr("status.charsetFallback", com.editora.editorconfig.EditorConfigCharset.displayName(charset)));
            charset = com.editora.editorconfig.EditorConfigCharset.UTF_8;
        }
        return com.editora.editorconfig.EditorConfigCharset.encode(text, charset);
    }

    boolean writeBuffer(EditorBuffer buffer, Path file) {
        SaveRequest request = captureSave(buffer, file);
        try {
            autoSaveExecutor.submit(() -> writeAsync(request, false));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
            return false;
        }
    }

    boolean writeBufferSynchronously(EditorBuffer buffer, Path file) {
        SaveRequest request = captureSave(buffer, file);
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        try {
            autoSaveExecutor.submit(() -> writeAsync(request, false, completed));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
            return false;
        }
        return awaitSave(completed);
    }

    /** Current auto-save mode, parsed leniently from settings. */
    String autoSaveMode() {
        return host.autoSaveModeOf(host.config().getSettings().getAutoSave());
    }

    /** Applies the auto-save setting: refreshes the idle-timer delay and stops it unless in delay mode. */
    void applyAutoSave() {
        autoSaveIdleTimer.setDuration(
                Duration.millis(Math.max(100, host.config().getSettings().getAutoSaveDelayMillis())));
        if (!AUTOSAVE_DELAY.equals(autoSaveMode())) {
            autoSaveIdleTimer.stop();
        }
    }

    /** Auto-saves every dirty, file-backed, writable buffer (untitled/read-only buffers are skipped). */
    void autoSaveAllDirty() {
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer != null && buffer.isDirty() && buffer.getPath() != null && buffer.isEditable()) {
                autoSaveBuffer(buffer);
            }
        }
    }

    /**
     * Writes {@code buffer} to disk off the UI thread: snapshots the text + path here, writes on a
     * background thread, then clears the dirty flag on the FX thread only if the content is unchanged
     * (so we never mark clean over edits made after the snapshot).
     */
    void autoSaveBuffer(EditorBuffer buffer) {
        Path file = buffer.getPath();
        SaveRequest request = captureSave(buffer, file);
        try {
            autoSaveExecutor.submit(() -> writeAsync(request, true));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
        }
    }

    private SaveRequest captureSave(EditorBuffer buffer, Path file) {
        String content = buffer.getContent();
        pendingSaves.merge(buffer, 1, Integer::sum);
        SaveRequest request = new SaveRequest(
                buffer,
                file,
                content,
                saveBytes(buffer, content),
                buffer.docVersion(),
                buffer.diskSnapshot(),
                saveSequence.incrementAndGet(),
                host.config().shared().documentWrites().begin(file));
        activeSaveRequests.add(request);
        return request;
    }

    private DiskWrite writeToDisk(SaveRequest request) throws IOException {
        Runnable hook = beforeDocumentWriteForTest;
        if (hook != null) {
            hook.run();
        }
        if (!documentWriter.write(request.target(), request.bytes(), request.ticket()::isCurrent)) {
            return null;
        }
        return new DiskWrite(lastModifiedMillis(request.target()), fileSize(request.target()));
    }

    /** Invalidates queued or staged writes when a buffer stops representing this path. */
    void invalidatePendingWrite(Path file) {
        host.config().shared().documentWrites().supersede(file);
        if (file != null) {
            committedSaves.remove(com.editora.config.PathKeys.key(file));
        }
    }

    /** Cancels saves captured from one buffer without invalidating another window's newer path ticket. */
    void invalidatePendingWrites(EditorBuffer buffer) {
        List.copyOf(activeSaveRequests).stream()
                .filter(request -> request.buffer() == buffer)
                .forEach(request -> request.ticket().invalidate());
        Path file = buffer.getPath();
        if (file != null) {
            committedSaves.remove(com.editora.config.PathKeys.key(file));
        }
    }

    private void writeAsync(SaveRequest request, boolean autoSave) {
        writeAsync(request, autoSave, null);
    }

    private void writeAsync(SaveRequest request, boolean autoSave, CompletableFuture<Boolean> completed) {
        try {
            var outcome = request.ticket().runIfCurrent(() -> {
                if (autoSave) {
                    long modified = lastModifiedMillis(request.target());
                    long size = fileSize(request.target());
                    boolean ownCommit = hasOwnCommitMetadata(request.target(), modified, size);
                    if (ownCommit
                            ? !matchesOwnCommit(request.target(), modified, size)
                            : request.diskSnapshot().differsFrom(modified, size)) {
                        return null;
                    }
                }
                return writeToDisk(request);
            });
            DiskWrite disk = outcome.executed() ? outcome.value() : null;
            if (disk != null) {
                publishCommit(request, disk);
            }
            Platform.runLater(() -> {
                try {
                    if (disk != null) {
                        completeSave(request, disk, autoSave, request.ticket().isCurrent());
                    }
                    completeFuture(completed, disk != null);
                } finally {
                    finishRequest(request);
                }
            });
        } catch (IOException | RuntimeException e) {
            Platform.runLater(() -> {
                try {
                    if (request.ticket().isCurrent()) {
                        host.setStatus(tr(autoSave ? "status.autoSaveFailed" : "status.failedSave", e.getMessage()));
                    }
                    completeFuture(completed, false);
                } finally {
                    finishRequest(request);
                }
            });
        }
    }

    private void completeSave(SaveRequest request, DiskWrite disk, boolean autoSave, boolean showFeedback) {
        host.historyCoordinator()
                .record(
                        request.target(),
                        request.content(),
                        autoSave ? HistoryRevision.REASON_AUTOSAVE : HistoryRevision.REASON_SAVE);
        acknowledgeLatestCommit(request.buffer());
        if (showFeedback && !request.buffer().isDisposed()) {
            host.setStatus(
                    autoSave
                            ? tr("status.autoSaved", request.target().getFileName())
                            : tr("status.saved", com.editora.config.PathDisplay.of(request.target())));
            host.git().refresh();
            if (!autoSave) {
                host.refreshBuildTools();
                host.lspCoordinator().syncBuffer(request.buffer());
                host.lspCoordinator().notifyDocumentSaved(request.buffer());
                host.indexCoordinator().onBufferSaved(request.buffer());
            }
        }
    }

    private void publishCommit(SaveRequest request, DiskWrite disk) {
        CommittedSave committed =
                new CommittedSave(request.sequence(), request.target(), request.content(), request.bytes(), disk);
        committedSaves.compute(
                com.editora.config.PathKeys.key(request.target()),
                (ignored, previous) ->
                        previous == null || previous.sequence() < committed.sequence() ? committed : previous);
    }

    private boolean matchesOwnCommit(Path target, long modifiedMillis, long size) {
        CommittedSave committed = committedSaves.get(com.editora.config.PathKeys.key(target));
        if (!hasOwnCommitMetadata(committed, target, modifiedMillis, size)) {
            return false;
        }
        try {
            return java.util.Arrays.equals(committed.bytes(), java.nio.file.Files.readAllBytes(target));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasOwnCommitMetadata(Path target, long modifiedMillis, long size) {
        return hasOwnCommitMetadata(
                committedSaves.get(com.editora.config.PathKeys.key(target)), target, modifiedMillis, size);
    }

    private static boolean hasOwnCommitMetadata(CommittedSave committed, Path target, long modifiedMillis, long size) {
        return committed != null
                && com.editora.config.PathKeys.sameNormalized(committed.target(), target)
                && committed.disk().modifiedMillis() == modifiedMillis
                && committed.disk().size() == size;
    }

    private void acknowledgeLatestCommit(EditorBuffer buffer) {
        Path path = buffer.getPath();
        CommittedSave committed = path == null ? null : committedSaves.get(com.editora.config.PathKeys.key(path));
        if (committed == null || buffer.isDisposed()) {
            return;
        }
        if (buffer.isDisposed()
                || buffer.getPath() == null
                || !com.editora.config.PathKeys.sameNormalized(buffer.getPath(), committed.target())) {
            return;
        }
        buffer.acknowledgeSavedContent(committed.content());
        buffer.setDiskSnapshot(
                committed.disk().modifiedMillis(), committed.disk().size());
    }

    boolean hasPendingSave(EditorBuffer buffer) {
        return pendingSaves.getOrDefault(buffer, 0) > 0;
    }

    void shutdown() {
        autoSaveIdleTimer.stop();
        autoSaveExecutor.shutdownNow();
        fileLoadExecutor.shutdownNow();
        List.copyOf(activeSaveRequests).forEach(this::finishRequest);
        committedSaves.clear();
        pendingSaves.clear();
    }

    private void finishRequest(SaveRequest request) {
        if (!activeSaveRequests.remove(request)) {
            return;
        }
        request.ticket().close();
        pendingSaves.computeIfPresent(request.buffer(), (ignored, count) -> count > 1 ? count - 1 : null);
    }

    private static void completeFuture(CompletableFuture<Boolean> completed, boolean result) {
        if (completed != null) {
            completed.complete(result);
        }
    }

    private boolean awaitSave(CompletableFuture<Boolean> completed) {
        if (!Platform.isFxApplicationThread()) {
            try {
                return completed.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                return false;
            }
        }
        Object key = new Object();
        completed.whenComplete((ok, error) -> Platform.runLater(() -> {
            if (Platform.isNestedLoopRunning()) {
                Platform.exitNestedEventLoop(key, error == null && Boolean.TRUE.equals(ok));
            }
        }));
        return Boolean.TRUE.equals(Platform.enterNestedEventLoop(key));
    }

    void toggleAutoSave() {
        String next =
                switch (autoSaveMode()) {
                    case AUTOSAVE_OFF -> AUTOSAVE_DELAY;
                    case AUTOSAVE_DELAY -> AUTOSAVE_FOCUS;
                    default -> AUTOSAVE_OFF;
                };
        host.config().getSettings().setAutoSave(next);
        host.requestSave();
        applyAutoSave();
        host.setStatus(tr("status.autoSave", host.autoSaveLabel(next)));
    }
}
