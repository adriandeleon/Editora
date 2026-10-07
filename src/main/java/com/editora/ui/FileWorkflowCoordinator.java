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
import com.editora.io.AtomicFileWrite;
import com.editora.io.DocumentWriteSequencer;

import static com.editora.i18n.Messages.tr;

/** Owns file loading, saving, autosave and elevated-save workflows. */
final class FileWorkflowCoordinator {

    @FunctionalInterface
    interface DocumentWriter {

        boolean write(Path target, byte[] bytes, BooleanSupplier commit) throws IOException;

        /** As {@link #write}, also saying whether the file had to be overwritten in place. */
        default AtomicFileWrite.Outcome writeDocument(Path target, byte[] bytes, BooleanSupplier commit)
                throws IOException {
            return write(target, bytes, commit) ? AtomicFileWrite.Outcome.REPLACED : AtomicFileWrite.Outcome.SKIPPED;
        }
    }

    private record SaveRequest(
            EditorBuffer buffer,
            Path target,
            String content,
            String savedText,
            byte[] bytes,
            long documentVersion,
            EditorBuffer.DiskSnapshot diskSnapshot,
            Set<Long> precedingSaveSequences,
            long sequence,
            DocumentWriteSequencer.Ticket ticket,
            boolean saveAs,
            String lineEnding,
            String charsetFallback) {}

    private record SaveAsOrigin(Path path) {}

    private record SavePayload(String text, SaveEncoding.Plan encoding) {}

    private record DiskWrite(long modifiedMillis, long size, boolean inPlace) {
        DiskWrite(long modifiedMillis, long size) {
            this(modifiedMillis, size, false);
        }
    }

    /**
     * What a physical commit wrote. The bytes themselves are not kept — one encoded copy of every saved open
     * file, used only to ask "is this still what we wrote?" — but their SHA-256 and length, which answer the
     * same question (as {@link EditorBuffer.DiskSnapshot} does for a load).
     */
    private record CommittedSave(
            long sequence,
            Path target,
            String content,
            String fingerprint,
            long length,
            DiskWrite disk,
            String lineEnding) {

        /** Whether {@code bytes} are exactly what this commit wrote. */
        boolean wrote(byte[] bytes) {
            return bytes != null
                    && bytes.length == length
                    && fingerprint.equals(FileWorkflowCoordinator.fingerprint(bytes));
        }
    }

    private record RemoteWritePlan(boolean proceed, byte[] expectedBytes, boolean expectedAbsent) {}

    private enum RemoteSaveChoice {
        RELOAD,
        OVERWRITE,
        CANCEL
    }

    record AdminResult(int exit, String error, long modifiedMillis, long size) {}

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

        void bufferPathChanged(EditorBuffer buffer, Path oldPath, boolean oldAlreadyClosed);

        void promoteTab(Tab tab);

        void finishAsyncOpen(Tab tab, EditorBuffer buffer, PreparedLoad load);

        void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error);

        String autoSaveModeOf(String mode);

        String autoSaveLabel(String mode);

        EditorBuffer bufferOf(Tab tab);

        Tab tabFor(EditorBuffer buffer);

        Tab tabForPath(Path file);

        /** Whether a tab of <em>another</em> window shows {@code file}. */
        boolean openInAnotherWindow(Path file);

        /** A {@code .editorconfig} was saved: every window re-resolves the rules of the files it has open. */
        void editorConfigSaved();

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
    /** Original committed identity retained across one or more superseding Save As attempts. */
    private final Map<EditorBuffer, SaveAsOrigin> saveAsOrigins = new IdentityHashMap<>();

    private final Set<SaveRequest> activeSaveRequests = ConcurrentHashMap.newKeySet();
    private final AtomicLong saveSequence = new AtomicLong();

    /** Local-document persistence boundary. Package-visible replacement supports deterministic faults. */
    private volatile DocumentWriter documentWriter = new DocumentWriter() {
        @Override
        public boolean write(Path target, byte[] bytes, BooleanSupplier commit) throws IOException {
            return writeDocument(target, bytes, commit) != AtomicFileWrite.Outcome.SKIPPED;
        }

        @Override
        public AtomicFileWrite.Outcome writeDocument(Path target, byte[] bytes, BooleanSupplier commit)
                throws IOException {
            // A file that cannot be replaced (read-only folder, hard links, another owner) is overwritten in
            // place; its previous bytes wait here, on a disk that survives a crash, until the write is done.
            return AtomicFileWrite.writeDocument(
                    target, bytes, commit, host.config().getConfigDir().resolve("save-backups"));
        }
    };

    /** UTF-16 buffers whose file had no byte-order mark, so a save does not add one. FX-thread only. */
    private final Set<EditorBuffer> bomlessUtf16 = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Buffers the user agreed to save over a file that is read-only on disk, and that file. FX-thread only. */
    private final Map<EditorBuffer, Path> readOnlyOverwrites = new java.util.WeakHashMap<>();
    /** Buffers whose changed disk metadata is being compared by content off the FX thread. FX-thread only. */
    private final Set<EditorBuffer> verifyingExternalChange = Collections.newSetFromMap(new IdentityHashMap<>());

    @FunctionalInterface
    interface ElevatedWriter {

        AdminResult write(Path target, byte[] bytes) throws IOException;
    }

    /** The privileged write ({@code pkexec}/{@code osascript}). Package-visible replacement for tests. */
    volatile ElevatedWriter elevatedWriter = this::runElevatedWrite;

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

    /**
     * One writer per remote connection. A save to a slow or stalled server used to hold the single writer
     * thread, and with it every other save — local files, auto-save, the synchronous save before Run or
     * close. Order per file is the {@link DocumentWriteSequencer}'s business, not this thread's.
     */
    private final Map<String, ExecutorService> remoteSaveExecutors = new ConcurrentHashMap<>();

    /** The thread {@code target} is written on: the local writer, or its connection's own. */
    ExecutorService saveExecutor(Path target) {
        String authority = shutdown ? null : com.editora.vfs.Vfs.authorityOf(target);
        if (authority == null) {
            return autoSaveExecutor;
        }
        return remoteSaveExecutors.computeIfAbsent(
                authority,
                ignored -> Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "editora-remote-save");
                    t.setDaemon(true);
                    return t;
                }));
    }

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
        if (RemoteReadFailure.connectionClosed(file)) {
            // Said before a tab shell appears and vanishes — and before the viewers below ask the dead
            // connection about the file on the FX thread, which it answers with an unchecked exception.
            host.setStatus(tr("status.failedOpen", RemoteReadFailure.reason(file, null)));
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
                PreparedLoad load = prepareLoad(file, classifyBinary).preparedFor(buffer);
                Platform.runLater(() -> ifWindowOpen(() -> host.finishAsyncOpen(tab, buffer, load)));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> ifWindowOpen(() -> host.failAsyncOpen(tab, buffer, file, e)));
            }
        });
    }

    /**
     * Opens a new, empty buffer bound to {@code file}, which does not exist yet — what {@code editora
     * notes/new.txt} means in every editor: the first save creates the file. It used to flash a tab and
     * report "Failed to open". Only for a path the user typed (the command line, an OS open request): a
     * session restore or a jump to a file that has since been deleted must keep failing visibly.
     *
     * @return false when {@code file} is not a new file in an existing local folder (open it normally)
     */
    boolean openNewFileAt(Path file) {
        Path parent = file.getParent();
        if (!com.editora.vfs.Vfs.isLocal(file)
                || parent == null
                || !Files.isDirectory(parent)
                || !Files.notExists(file)
                || host.tabForPath(file) != null) {
            return false;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(file);
        // "Loaded" as an empty file, so one that appears on disk before the first save is a conflict to ask
        // about rather than something to overwrite.
        buffer.setDiskSnapshot(0, 0, fingerprint(new byte[0]));
        host.addBuffer(buffer, true);
        buffer.markUnsaved(); // nothing is on disk: closing the tab must offer to save, even while empty
        Tab tab = host.tabFor(buffer);
        if (tab != null) {
            host.updateTabMeta(tab, buffer);
        }
        host.setStatus(tr("status.newFileAtPath", com.editora.config.PathDisplay.of(file)));
        return true;
    }

    /**
     * Runs a load completion unless the window was disposed while the read was in flight. Its tabs outlive
     * disposal, so the completion would otherwise fill a disposed buffer and start a language server for a
     * window that no longer exists.
     */
    private void ifWindowOpen(Runnable completion) {
        if (!shutdown) {
            completion.run();
        }
    }

    /** True once {@link #shutdown()} has run: the window is closed and no completion may touch it. */
    boolean isShutdown() {
        return shutdown;
    }

    private volatile boolean shutdown;

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

    /**
     * Opens {@code file} (or selects its tab) and, a pulse later, runs {@code action} through
     * {@link #whenLoaded} — the load-aware form of "open, then act on the editor". {@link #openPath} only
     * starts the read: until it lands the tab is an empty read-only shell, so a bare {@code runLater} acts on
     * one blank line.
     */
    void openThen(Path file, Runnable action) {
        openPath(file);
        Platform.runLater(() -> whenLoaded(file, action));
    }

    /**
     * Runs {@code action} with {@code file}'s tab selected once its text is in the buffer: now when it is
     * already loaded, else when the load lands. Never runs when the file has no text tab (an image, a PDF, a
     * hex view), when its load fails, or when the tab is closed first.
     */
    void whenLoaded(Path file, Runnable action) {
        Tab tab = host.tabForPath(file);
        EditorBuffer buffer = tab == null ? null : host.bufferOf(tab);
        if (buffer == null) {
            return;
        }
        afterBufferLoad(buffer, () -> {
            Tab now = host.tabForBuffer(buffer);
            if (now == null) {
                return;
            }
            if (host.editorArea().selectedTab() != now) {
                host.editorArea().select(now); // the user looked elsewhere while it loaded
            }
            action.run();
        });
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
        WindowSessionCoordinator.afterNextPaint(
                () -> com.editora.perf.Startup.mark(com.editora.perf.Startup.FIRST_PAINT));
    }

    /**
     * The first-paint mark for a launch that loads no file: the window's own first frame. Same one-shot
     * timer as {@link #notePerfContentLoaded}, so whichever of the two arms first is the one that reports.
     */
    void notePerfWindowShown() {
        if (!com.editora.perf.Startup.enabled() || perfPaintTimerStarted) {
            return;
        }
        perfPaintTimerStarted = true;
        WindowSessionCoordinator.afterNextPaint(
                () -> com.editora.perf.Startup.mark(com.editora.perf.Startup.FIRST_PAINT));
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
            boolean tail,
            byte[] sourceBytes,
            String declaredCharset,
            String fingerprint,
            com.editora.editor.InitialDocument document) {

        /**
         * Hashes the file's bytes where the load is prepared — the read worker — so the SHA-256 of a large
         * file is never computed on the FX thread when the prepared document is applied.
         */
        PreparedLoad(
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
                boolean tail,
                byte[] sourceBytes,
                String declaredCharset) {
            this(
                    file,
                    content,
                    size,
                    mtime,
                    lines,
                    maxLineLength,
                    charset,
                    editorConfig,
                    binary,
                    large,
                    heavy,
                    longLine,
                    truncated,
                    log,
                    logOffset,
                    tail,
                    sourceBytes,
                    declaredCharset,
                    binary ? null : FileWorkflowCoordinator.fingerprint(sourceBytes), // a hex view compares nothing
                    null);
        }

        /**
         * This load with its paragraphs built for {@code buffer} — still on the read worker, so the FX thread
         * is left with the document swap alone. Only for a buffer that is an empty shell: the document is
         * built with the area's initial styles, which is what an insertion into an empty area uses.
         */
        PreparedLoad preparedFor(EditorBuffer buffer) {
            if (content == null || document != null) {
                return this;
            }
            return new PreparedLoad(
                    file,
                    content,
                    size,
                    mtime,
                    lines,
                    maxLineLength,
                    charset,
                    editorConfig,
                    binary,
                    large,
                    heavy,
                    longLine,
                    truncated,
                    log,
                    logOffset,
                    tail,
                    sourceBytes,
                    declaredCharset,
                    fingerprint,
                    buffer.prepareInitialContent(content, longLine));
        }

        /** The declared charset could not decode the file, so {@link #charset} is a lossless stand-in. */
        boolean charsetAssumed() {
            return declaredCharset != null && !declaredCharset.equals(charset);
        }
    }

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
        return prepareLoad(file, sniffBinary, true);
    }

    /**
     * @param forDocument the result becomes an editor document, so it must fit the free heap (see
     *     {@link #heapCap}); false for a read that only compares the file with what a save is about to replace
     */
    private PreparedLoad prepareLoad(Path file, boolean sniffBinary, boolean forDocument) throws IOException {
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
        long cap = forDocument && size >= EditorBuffer.LARGE_FILE_BYTES
                ? heapCap(size)
                : Math.min(size, EditorBuffer.HUGE_FILE_BYTES);
        if (size >= EditorBuffer.HUGE_FILE_BYTES || cap < size) {
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
                        false,
                        null,
                        null);
            }
            if (isLog) {
                // A huge log opens at its END (the tail is what matters) instead of the first chunk.
                com.editora.logviewer.LogTail.Tail tail = com.editora.logviewer.LogTail.readTail(file, cap);
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
                        true,
                        null,
                        null);
            }
            String content = readCapped(file, (int) cap);
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
                    false,
                    null,
                    null);
        }
        byte[] bytes = Files.readAllBytes(file);
        // BOM-less UTF-16 is half NUL bytes; when .editorconfig declares it, it is text, not a hex dump.
        if (sniffBinary
                && !com.editora.editorconfig.EditorConfigCharset.isBomlessUtf16(bytes, editorConfig.charset())) {
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
                        false,
                        bytes,
                        null);
            }
        }
        // Strict, never with replacement: a U+FFFD substituted here would be written back on the next save.
        com.editora.editorconfig.EditorConfigCharset.Decoded decoded =
                com.editora.editorconfig.EditorConfigCharset.decodeLossless(bytes, editorConfig.charset());
        String charset = decoded.charset();
        String content = decoded.text();
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
                false,
                bytes,
                decoded.declared());
    }

    /** {max, used} heap bytes; replaced by tests to stand in for a heap that is nearly full. */
    volatile java.util.function.Supplier<long[]> heapUsage = () -> {
        Runtime runtime = Runtime.getRuntime();
        return new long[] {runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory()};
    };

    /**
     * How many bytes of a large file to load (see {@link LoadHeapGuard}): the whole file, capped at the
     * huge-file limit, when its document fits the free heap — else only what fits, which the caller opens
     * read-only like any other partial load.
     */
    private long heapCap(long size) {
        long wanted = Math.min(size, EditorBuffer.HUGE_FILE_BYTES);
        long[] heap = heapUsage.get();
        if (LoadHeapGuard.fits(wanted, heap[0], heap[1])) {
            return wanted;
        }
        // "Used" counts garbage not collected yet (the previous large load leaves plenty), so collect once
        // before concluding that the file does not fit. Rare, and on the read worker.
        System.gc();
        heap = heapUsage.get();
        return LoadHeapGuard.cap(wanted, heap[0], heap[1]);
    }

    /** Applies a prepared document atomically on the FX thread, with all expensive-mode flags already active. */
    String applyPreparedLoad(EditorBuffer buffer, PreparedLoad load) {
        buffer.setDiskSnapshot(load.mtime(), load.size(), load.fingerprint());
        buffer.setTruncatedLoad(load.truncated());
        host.editorSettings().applyResolvedEditorConfig(buffer, load.editorConfig());
        buffer.setDetectedCharset(load.charset(), load.charsetAssumed());
        if (isUtf16(load.charset())
                && !load.charsetAssumed()
                && load.sourceBytes() != null
                && com.editora.editorconfig.EditorConfigCharset.detectByBom(load.sourceBytes()) == null) {
            bomlessUtf16.add(buffer);
        } else {
            bomlessUtf16.remove(buffer);
        }
        buffer.setLargeFile(load.large() || load.longLine());
        buffer.setHeavyFile(load.heavy());
        buffer.setWrapSuppressed(load.longLine());
        if (load.longLine()) {
            // Wrapping a giant RichTextFX paragraph multiplies layout work. The user can explicitly turn it
            // back on after loading (Toggle Word Wrap on this buffer), but the first rendered frame must use the
            // safe profile — and so must every later settings re-apply, hence the flag above.
            buffer.setWordWrap(false);
        }
        if (load.truncated() || buffer.isReadOnly()) {
            // Also back to editable: a reload of a file that is no longer huge used to stay read-only.
            buffer.setReadOnly(load.truncated());
        }
        if (load.document() != null) {
            buffer.setInitialContent(load.document());
        } else {
            buffer.setInitialContent(load.content(), load.longLine());
        }
        if (load.log()) {
            host.logViewer().recordLoadOffset(buffer, load.logOffset());
        } else if (!load.truncated()) {
            host.logViewer().sniffLoaded(buffer, load.content()); // a log its name does not announce
        }
        if (load.truncated()) {
            return tr(
                    load.tail() ? "status.load.hugeLog" : "status.load.hugeFile",
                    load.file().getFileName(),
                    StatusBar.formatSize(load.size()),
                    StatusBar.formatSize(load.content().length()));
        }
        if (load.charsetAssumed()) {
            return tr(
                    "status.charsetAssumed",
                    load.file().getFileName(),
                    com.editora.editorconfig.EditorConfigCharset.displayName(load.declaredCharset()),
                    com.editora.editorconfig.EditorConfigCharset.displayName(load.charset()));
        }
        if (load.document() != null
                ? load.document().mixedLineEndings()
                : com.editora.editor.LineEndings.mixed(load.content())) {
            return tr("status.mixedLineEndings", load.file().getFileName(), buffer.getLineEnding());
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
        return tr("status.largeFileOpened", file.getFileName(), StatusBar.formatSize(size));
    }

    private static boolean isUtf16(String charset) {
        return com.editora.editorconfig.EditorConfigCharset.UTF_16LE.equals(charset)
                || com.editora.editorconfig.EditorConfigCharset.UTF_16BE.equals(charset);
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
        Tab tab = host.editorArea().selectedTab();
        EditorBuffer buffer = host.bufferOf(tab);
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        Path file = buffer.getPath();
        if (com.editora.vfs.Vfs.isRemote(file)) {
            return; // remote (SFTP) mtime polling would be a network call per focus — skipped for now
        }
        if (buffer.isLogFollowing()) {
            return; // the follow is what is reading the file's changes; "reload?" would ask about each of them
        }
        if (!verifyingExternalChange.add(buffer)) {
            return; // a check of this buffer is already on its way back
        }
        // Everything below asks the disk: a stat per ancestor directory for .editorconfig, then the file's
        // own existence, time and size. On a cold network or FUSE mount each of those can take as long as it
        // likes, and this runs on every tab switch and focus gain — so it is asked off the FX thread, and
        // the answer applied only if the buffer is still the one it was asked about.
        boolean resolveConfig = host.editorSettings().editorConfigEnabled();
        Runnable settle = () -> verifyingExternalChange.remove(buffer);
        try {
            fileLoadExecutor.execute(() -> {
                Runnable hook = beforeExternalStatForTest;
                if (hook != null) {
                    hook.run();
                }
                com.editora.editorconfig.EditorConfigProperties rules = resolveConfig
                        ? com.editora.editorconfig.EditorConfig.resolveFor(file)
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY;
                boolean exists = Files.exists(file);
                long mtime = exists ? lastModifiedMillis(file) : 0;
                long size = exists ? fileSize(file) : 0;
                Platform.runLater(() -> ifWindowOpen(() -> {
                    boolean verifying = false;
                    try {
                        if (buffer.isDisposed()
                                || host.tabForBuffer(buffer) != tab
                                || buffer.getPath() == null
                                || !com.editora.config.PathKeys.sameNormalized(buffer.getPath(), file)) {
                            return; // closed, renamed or saved elsewhere while the disk was being asked
                        }
                        if (!loadingBuffers.contains(buffer)) {
                            // A changed .editorconfig (edited here, or by a pull / branch switch) reaches the
                            // open file.
                            host.editorSettings().applyRefreshedEditorConfig(buffer, rules);
                        }
                        if (!exists || !buffer.diskChangedFrom(mtime, size)) {
                            return; // deleted/renamed externally — keep what's open (no prompt) — or unchanged
                        }
                        String loaded = buffer.diskSnapshot().fingerprint();
                        if (loaded != null) {
                            verifying = true; // it settles the guard itself, after comparing the bytes
                            verifyExternalChange(tab, buffer, file, loaded);
                        } else if (host.editorArea().selectedTab() == tab && !checkingExternalChanges) {
                            checkingExternalChanges = true; // the prompt steals focus: do not re-enter
                            try {
                                promptExternalChange(tab, buffer, mtime, size);
                            } finally {
                                checkingExternalChanges = false;
                            }
                        }
                    } finally {
                        if (!verifying) {
                            settle.run();
                        }
                    }
                }));
            });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            settle.run();
        }
    }

    /** Test seam: runs on the worker before {@link #checkExternalChanges} asks the disk anything. */
    volatile Runnable beforeExternalStatForTest;

    /**
     * Metadata changed; compares the bytes before asking. {@code touch}, a build step or a checkout that
     * rewrites identical content changes the modified time only — the prompt then asked whether to reload a
     * file that is byte for byte what the editor holds, again on every tab switch. Hashing is disk work, so
     * it runs off the FX thread and the prompt (or the silent re-baseline) follows.
     */
    private void verifyExternalChange(Tab tab, EditorBuffer buffer, Path file, String loaded) {
        Runnable settle = () -> verifyingExternalChange.remove(buffer);
        try {
            fileLoadExecutor.execute(() -> {
                long mtime = lastModifiedMillis(file);
                long size = fileSize(file);
                String current;
                try {
                    current = fingerprint(Files.readAllBytes(file));
                } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
                    current = null; // cannot tell: ask, as before
                }
                boolean same = loaded.equals(current);
                Platform.runLater(() -> ifWindowOpen(() -> {
                    try {
                        if (buffer.isDisposed()
                                || host.tabForBuffer(buffer) != tab
                                || buffer.getPath() == null
                                || !com.editora.config.PathKeys.sameNormalized(buffer.getPath(), file)
                                || !loaded.equals(buffer.diskSnapshot().fingerprint())
                                || !buffer.diskChangedFrom(mtime, size)) {
                            return; // closed, renamed, saved or reloaded while the bytes were being read
                        }
                        if (same) {
                            buffer.setDiskSnapshot(mtime, size, loaded);
                        } else if (host.editorArea().selectedTab() == tab && !checkingExternalChanges) {
                            checkingExternalChanges = true; // the prompt steals focus: do not re-enter
                            try {
                                promptExternalChange(tab, buffer, mtime, size);
                            } finally {
                                checkingExternalChanges = false;
                            }
                        }
                    } finally {
                        settle.run();
                    }
                }));
            });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            settle.run();
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
            // What was kept is not what is on disk. Left clean, it had no modified marker, auto-save never
            // wrote it, and closing the tab dropped the version the user had just chosen without a word.
            buffer.markUnsaved();
            host.updateTabMeta(tab, buffer);
        }
    }

    /** Reloads a buffer's content from disk, preserving the caret position as best it can. */
    void reloadFromDisk(Tab tab, EditorBuffer buffer) {
        reloadFromDisk(tab, buffer, ignored -> {});
    }

    /** Reloads asynchronously and reports on the FX thread whether the prepared snapshot was applied. */
    void reloadFromDisk(Tab tab, EditorBuffer buffer, java.util.function.Consumer<Boolean> onComplete) {
        Path file = buffer.getPath();
        invalidatePendingWrite(file);
        fileLoadExecutor.execute(() -> {
            try {
                PreparedLoad load = prepareLoad(file, false);
                Platform.runLater(() -> {
                    boolean applied = false;
                    try {
                        if (!buffer.isDisposed()
                                && buffer.getPath() != null
                                && com.editora.config.PathKeys.sameNormalized(buffer.getPath(), file)) {
                            applyPreparedReload(tab, buffer, load);
                            applied = true;
                        }
                    } finally {
                        onComplete.accept(applied);
                    }
                });
            } catch (IOException | RuntimeException e) { // a closed SFTP file system may raise either
                Platform.runLater(() -> {
                    host.setStatus(tr("status.failedReload", file.getFileName(), RemoteReadFailure.reason(file, e)));
                    onComplete.accept(false);
                });
            }
        });
    }

    /** Applies bytes prepared off-thread after the user explicitly chooses the external copy. FX thread. */
    private void applyPreparedReload(Tab tab, EditorBuffer buffer, PreparedLoad load) {
        // The whole document is about to be replaced, which resets the viewport and clears the folds: keep
        // the user's place (caret line/column, scroll position, collapsed folds) rather than the caret alone.
        ReloadViewState view = ReloadViewState.capture(buffer);
        host.historyCoordinator()
                .record(buffer, HistoryRevision.REASON_EXTERNAL); // snapshot the in-memory version before disk wins
        String note = applyPreparedLoad(buffer, load);
        notePerfContentLoaded(buffer);
        buffer.markClean();
        view.restore();
        host.updateTabMeta(tab, buffer);
        host.lspCoordinator().watchedFilesReloaded(java.util.List.of(load.file()));
        host.setStatus(note.isEmpty() ? tr("status.reloaded", load.file().getFileName()) : note);
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

    /**
     * True when a plain save of {@code p} would fail for permissions but an elevated save could succeed. A
     * read-only file of the user's own, in a folder they can write, is not such a file: an ordinary save
     * replaces it once the user agrees ({@link #mayReplaceReadOnly}), and asking for root there is wrong.
     */
    boolean adminSaveApplicable(Path p) {
        return elevationAvailable()
                && p != null
                && com.editora.vfs.Vfs.isLocal(p)
                && Files.exists(p)
                && !Files.isWritable(p)
                && !replaceableWithoutElevation(p);
    }

    private static boolean replaceableWithoutElevation(Path p) {
        try {
            Path parent = p.toAbsolutePath().getParent();
            return parent != null
                    && Files.isWritable(parent)
                    && Files.getOwner(p).getName().equals(System.getProperty("user.name"));
        } catch (IOException | RuntimeException unknownOwner) {
            return false;
        }
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
     * <p>The same holds for a tab whose document has not arrived yet (an empty loading shell would replace
     * the file with zero bytes) and for a log that follow mode has trimmed to its tail.
     *
     * @return true when the save was refused (the caller must not write).
     */
    boolean refuseUnsavable(EditorBuffer buffer) {
        SaveRefusal refusal = saveRefusal(buffer);
        if (refusal == SaveRefusal.NONE) {
            return false;
        }
        if (refusal.messageKey() != null) {
            host.setStatus(tr(refusal.messageKey(), buffer.getTitle()));
        }
        return true;
    }

    /**
     * Why {@code buffer} must not be written right now. Evaluated again in {@link #captureSave}, the one point
     * every write path passes through, so a caller that forgot to ask (autosave, the elevated save, the MCP
     * bridge) still cannot put an incomplete document on disk.
     */
    SaveRefusal saveRefusal(EditorBuffer buffer) {
        return buffer == null
                ? SaveRefusal.DISPOSED
                : SaveRefusal.of(
                        buffer.isDisposed(),
                        buffer.isLoading() || loadingBuffers.contains(buffer),
                        buffer.isTruncatedLoad(),
                        buffer.isLogTrimmed());
    }

    boolean save(EditorBuffer buffer) {
        if (refuseUnsavable(buffer)) {
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
        if (refuseUnsavable(buffer)) {
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
        if (request == null) {
            return;
        }
        host.setStatus(tr("status.admin.saving", com.editora.config.PathDisplay.of(target)));
        new Thread(
                        () -> {
                            try {
                                var outcome = request.ticket().runIfCurrent(() -> {
                                    // The same preimage check as an ordinary save: the elevated copy truncates
                                    // in place, so an external edit would otherwise be overwritten unasked.
                                    if (Files.exists(target)
                                            && !prepareRemoteWrite(request, false, false, null)
                                                    .proceed()) {
                                        return new AdminResult(-2, "conflict", -1, -1);
                                    }
                                    return elevatedWriter.write(request.target(), request.bytes());
                                });
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

    private AdminResult runElevatedWrite(Path target, byte[] bytes) throws IOException {
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
            Files.write(tmp, bytes);
            var result = com.editora.process.ProcessRunner.run(
                    null,
                    java.time.Duration.ofMinutes(2),
                    com.editora.process.ElevatedSave.elevatedArgv(
                            System.getProperty("os.name"), com.editora.process.ElevatedSave.PKEXEC, tmp, target));
            return new AdminResult(result.exit(), result.err(), lastModifiedMillis(target), fileSize(target));
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
                    host.lspCoordinator().notifyDocumentSaved(buffer, request.content());
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
        if (refuseUnsavable(buffer)) {
            return false;
        }
        // A remote buffer may be saved as a LOCAL file (the chooser only offers those): with its connection
        // gone that is the one way left to keep the text. A new remote destination is still not offered.
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveAs.title"));
        if (buffer.getDisplayName() != null) {
            chooser.setInitialFileName(buffer.getDisplayName()); // suggested name from --new-file=NAME
        } else if (com.editora.vfs.Vfs.isRemote(buffer.getPath())) {
            chooser.setInitialFileName(String.valueOf(buffer.getPath().getFileName()));
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
        if (refuseUnsavable(buffer) || refuseTargetOpenElsewhere(buffer, file)) {
            return false; // before the buffer is re-pointed: a refused write must not leave it renamed
        }
        Path previousPath = buffer.getPath();
        saveAsOrigins.putIfAbsent(buffer, new SaveAsOrigin(previousPath));
        invalidatePendingWrites(buffer);
        buffer.setPath(file);
        host.bufferPathChanged(buffer, previousPath, false);
        // The buffer's EditorConfig properties + charset were resolved against the OLD path. Without
        // re-resolving, a Save-As into another tree writes with the previous project's charset/EOL/trim rules
        // (and keeps doing so on every later save), while an untitled buffer saved INTO a project with an
        // .editorconfig gets none of its rules.
        host.editorSettings().applyEditorConfig(buffer);
        host.previews().ensurePreviewControls(buffer); // a new untitled saved as .md/.mmd now gets the preview toggle
        host.htmlPreview().ensureControl(buffer); // a save-as to .html now gets the "open in browser" globe
        host.logViewer().ensureControl(buffer); // a save-as to .log now gets the log control + level overlay
        boolean ok = synchronous ? writeBufferSynchronously(buffer, file, true) : writeBuffer(buffer, file, true);
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
        if (buffer == null || refuseUnsavable(buffer)) {
            return;
        }
        Path base = saveAsBaseDir(buffer); // a local folder, also for a remote buffer: its copy is saved locally
        host.promptText(tr("dialog.saveAs.title"), tr("dialog.saveAs.prompt"), saveAsInitial(buffer, base), value -> {
            Path target = com.editora.config.PathKeys.resolveUserInput(value, base, System.getProperty("user.home"));
            if (target == null) {
                host.setStatus(tr("status.saveAs.invalidPath"));
                return;
            }
            if (refuseTargetOpenElsewhere(buffer, target)) {
                return; // before asking to overwrite: the answer could not be honoured
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

    /**
     * Refuses a Save As whose target is open in another tab — of this window or of any other. Re-pointing this
     * buffer there left two tabs on one path: the other one kept its stale text (and any unsaved edits), path
     * lookups found whichever came first, and its next save or "Keep Mine" silently replaced what had just
     * been written.
     */
    private boolean refuseTargetOpenElsewhere(EditorBuffer buffer, Path target) {
        if (target == null) {
            return false;
        }
        Tab other = host.tabForPath(target);
        if ((other == null || other == host.tabForBuffer(buffer)) && !host.openInAnotherWindow(target)) {
            return false;
        }
        host.setStatus(tr("status.saveAs.cannotReplaceOpenFile", target.getFileName()));
        return true;
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
        String name = buffer.getDisplayName() != null
                ? buffer.getDisplayName()
                : p != null && p.getFileName() != null ? p.getFileName().toString() : "untitled.txt";
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
     * (BOM-aware). The live document is not mutated — only what we write. With EditorConfig off this is the
     * content in the file's own line ending and detected charset.
     */
    byte[] saveBytes(EditorBuffer buffer) {
        return savePayload(buffer, buffer.getContent()).encoding().bytes();
    }

    private SavePayload savePayload(EditorBuffer buffer, String content) {
        com.editora.editorconfig.EditorConfigProperties p =
                host.editorSettings().editorConfigEnabled()
                        ? buffer.getEditorConfigProps()
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY;
        String text = com.editora.editorconfig.EditorConfigTransform.transform(content, p);
        if (com.editora.editor.LineEndings.labelOf(p.endOfLine()) == null) {
            // No end_of_line rule: write the file's own line ending back. The document only ever holds '\n',
            // so without this every CRLF file was rewritten as LF by its first save.
            text = com.editora.editor.LineEndings.apply(text, buffer.getLineEnding());
        }
        // A charset that can't represent what the user typed (an em dash / curly quote / emoji under
        // `charset = latin1`) would be written as '?' by String.getBytes — and the editor keeps showing the
        // real character until the file is reopened, so the corruption is invisible until it's permanent.
        // SaveEncoding decides what is written instead, and refuses when nothing safe can be.
        String charset = buffer.getEffectiveCharset();
        boolean bom = !(isUtf16(charset) && bomlessUtf16.contains(buffer));
        return new SavePayload(text, SaveEncoding.plan(text, charset, buffer.isCharsetAssumed(), bom));
    }

    private static String fingerprint(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    boolean writeBuffer(EditorBuffer buffer, Path file) {
        return writeBuffer(buffer, file, false);
    }

    private boolean writeBuffer(EditorBuffer buffer, Path file, boolean saveAs) {
        SaveRequest request = captureSave(buffer, file, saveAs);
        if (request == null) {
            return false;
        }
        try {
            saveExecutor(file).submit(() -> writeAsync(request, false));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
            return false;
        }
    }

    boolean writeBufferSynchronously(EditorBuffer buffer, Path file) {
        return writeBufferSynchronously(buffer, file, false);
    }

    private boolean writeBufferSynchronously(EditorBuffer buffer, Path file, boolean saveAs) {
        SaveRequest request = captureSave(buffer, file, saveAs);
        if (request == null) {
            return false;
        }
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        try {
            saveExecutor(file).submit(() -> writeAsync(request, false, completed));
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
        if (request == null) {
            return;
        }
        try {
            saveExecutor(file).submit(() -> writeAsync(request, true));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
        }
    }

    private SaveRequest captureSave(EditorBuffer buffer, Path file) {
        return captureSave(buffer, file, false);
    }

    /**
     * Snapshots {@code buffer} for a write, or returns {@code null} (with a status message) when it must not be
     * written — see {@link #saveRefusal}. The single choke point for Save, Save As, autosave, the elevated
     * save and every programmatic caller.
     */
    private SaveRequest captureSave(EditorBuffer buffer, Path file, boolean saveAs) {
        if (refuseUnsavable(buffer)) {
            return null;
        }
        if (!saveAs) {
            // The rules were resolved when the file was opened; an .editorconfig edited or pulled since then
            // must govern this write, not the next session's. (Save As has just re-resolved for its target.)
            host.editorSettings().refreshEditorConfig(buffer);
        }
        String content = buffer.getContent();
        SavePayload payload = savePayload(buffer, content);
        if (payload.encoding().bytes() == null) {
            SaveEncoding.Unencodable first = payload.encoding().refused();
            host.setStatus(tr(
                    "status.save.cannotEncodeAssumed",
                    buffer.getTitle(),
                    com.editora.editorconfig.EditorConfigCharset.displayName(buffer.getEffectiveCharset()),
                    first == null ? "?" : first.character(),
                    first == null ? "?" : String.valueOf(first.line())));
            return null;
        }
        pendingSaves.merge(buffer, 1, Integer::sum);
        SaveRequest request = new SaveRequest(
                buffer,
                file,
                content,
                payload.text(),
                payload.encoding().bytes(),
                buffer.docVersion(),
                buffer.diskSnapshot(),
                activeSaveRequests.stream()
                        .filter(active -> active.buffer() == buffer)
                        .map(SaveRequest::sequence)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                saveSequence.incrementAndGet(),
                host.config().shared().documentWrites().begin(file),
                saveAs,
                buffer.getLineEnding(),
                payload.encoding().fallbackFrom());
        activeSaveRequests.add(request);
        return request;
    }

    private DiskWrite writeToDisk(SaveRequest request, RemoteWritePlan plan) throws IOException {
        Runnable hook = beforeDocumentWriteForTest;
        if (hook != null) {
            hook.run();
        }
        BooleanSupplier commit = () -> request.ticket().isCurrent()
                && (plan.expectedAbsent()
                        ? Files.notExists(request.target())
                        : plan.expectedBytes() == null || diskBytesEqual(request.target(), plan.expectedBytes()));
        AtomicFileWrite.Outcome written = documentWriter.writeDocument(request.target(), request.bytes(), commit);
        if (written == AtomicFileWrite.Outcome.SKIPPED) {
            return null;
        }
        return new DiskWrite(
                lastModifiedMillis(request.target()),
                fileSize(request.target()),
                written == AtomicFileWrite.Outcome.IN_PLACE);
    }

    private static boolean diskBytesEqual(Path target, byte[] expected) {
        try {
            return java.util.Arrays.equals(expected, Files.readAllBytes(target));
        } catch (IOException missingOrUnreadable) {
            return false;
        }
    }

    /**
     * Reads the remote file off the FX thread and compares it with the last successfully loaded/saved bytes.
     * The returned preimage is checked again at the atomic replacement boundary, closing the gap between
     * conflict detection and commit.
     */
    private RemoteWritePlan prepareRemoteWrite(
            SaveRequest request, boolean autoSave, boolean raced, java.util.concurrent.atomic.AtomicBoolean blocked)
            throws IOException {
        PreparedLoad current = prepareLoad(request.target(), false, false);
        byte[] currentBytes = current.sourceBytes();
        CommittedSave ownCommit = committedSaves.get(com.editora.config.PathKeys.key(request.target()));
        // A preceding request may be acknowledged and retired after this request captured its old
        // disk snapshot. Preserve that relationship across FX callbacks instead of consulting live requests.
        boolean followsOwnCommit = ownCommit != null
                && ownCommit.sequence() < request.sequence()
                && request.precedingSaveSequences().contains(ownCommit.sequence());
        boolean changed = raced
                || (!request.saveAs()
                        && (followsOwnCommit
                                ? !ownCommit.fingerprint().equals(current.fingerprint())
                                : request.diskSnapshot()
                                        .differsFrom(current.mtime(), current.size(), fingerprint(currentBytes))));
        if (!changed) {
            return new RemoteWritePlan(true, currentBytes, false);
        }
        if (autoSave) {
            blocked.set(true); // never prompt from a background save, but do not go quiet either
            return new RemoteWritePlan(false, currentBytes, false);
        }
        RemoteSaveChoice choice = promptRemoteSaveConflict(request, current);
        return new RemoteWritePlan(choice == RemoteSaveChoice.OVERWRITE, currentBytes, false);
    }

    private RemoteSaveChoice promptRemoteSaveConflict(SaveRequest request, PreparedLoad current) throws IOException {
        CompletableFuture<RemoteSaveChoice> choice = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                EditorBuffer buffer = request.buffer();
                if (buffer.isDisposed()
                        || buffer.getPath() == null
                        || !com.editora.config.PathKeys.sameNormalized(buffer.getPath(), request.target())
                        || !request.ticket().isCurrent()) {
                    choice.complete(RemoteSaveChoice.CANCEL);
                    return;
                }
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.initOwner(host.stage());
                alert.setTitle(tr("dialog.externalChange.title"));
                alert.setHeaderText(
                        tr("dialog.externalChange.header", request.target().getFileName()));
                alert.setContentText(tr("dialog.externalChange.dirty"));
                ButtonType reload = new ButtonType(tr("dialog.externalChange.reload"), ButtonBar.ButtonData.LEFT);
                ButtonType overwrite =
                        new ButtonType(tr("dialog.externalChange.keepMine"), ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(reload, overwrite, cancel);
                ButtonType selected = alert.showAndWait().orElse(cancel);
                if (selected == reload) {
                    invalidatePendingWrite(request.target());
                    Tab tab = host.editorArea().tabs().stream()
                            .filter(candidate -> host.bufferOf(candidate) == buffer)
                            .findFirst()
                            .orElse(null);
                    if (tab != null) {
                        applyPreparedReload(tab, buffer, current);
                    }
                    choice.complete(RemoteSaveChoice.RELOAD);
                } else {
                    choice.complete(selected == overwrite ? RemoteSaveChoice.OVERWRITE : RemoteSaveChoice.CANCEL);
                }
            } catch (Throwable failure) {
                choice.completeExceptionally(failure);
            }
        });
        try {
            return choice.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving a remote save conflict", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Failed to resolve a remote save conflict", cause);
        }
    }

    /**
     * A file that is read-only on disk is still replaceable — a rename needs the directory's permission, not
     * the file's — so a save used to replace it without a word once View mode was switched off (and a file
     * owned by someone else became the user's). An explicit save now asks first, once per buffer and file;
     * a background save never asks and never writes, it says why.
     */
    private boolean mayReplaceReadOnly(SaveRequest request, boolean autoSave) throws IOException {
        Path target = request.target();
        if (com.editora.vfs.Vfs.isWritableOnDisk(target)) {
            return true;
        }
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                EditorBuffer buffer = request.buffer();
                if (buffer.isDisposed() || !request.ticket().isCurrent()) {
                    answer.complete(false);
                } else if (target.equals(readOnlyOverwrites.get(buffer))) {
                    answer.complete(true);
                } else if (autoSave) {
                    host.setStatus(tr("status.autoSave.cannotWriteReadOnly", target.getFileName()));
                    answer.complete(false);
                } else {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.initOwner(host.stage());
                    alert.setTitle(tr("dialog.saveReadOnly.title"));
                    alert.setHeaderText(tr("dialog.saveReadOnly.header", target.getFileName()));
                    alert.setContentText(tr("dialog.saveReadOnly.content"));
                    ButtonType overwrite =
                            new ButtonType(tr("dialog.saveReadOnly.overwrite"), ButtonBar.ButtonData.OK_DONE);
                    ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert.getButtonTypes().setAll(overwrite, cancel);
                    boolean agreed = Dialogs.styled(alert).showAndWait().orElse(cancel) == overwrite;
                    if (agreed) {
                        readOnlyOverwrites.put(buffer, target);
                    } else {
                        host.setStatus(tr("status.save.cannotWriteReadOnly", target.getFileName()));
                    }
                    answer.complete(agreed);
                }
            } catch (Throwable failure) {
                answer.completeExceptionally(failure);
            }
        });
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while confirming a save over a read-only file", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Failed to confirm a save over a read-only file", e.getCause());
        }
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
        java.util.concurrent.atomic.AtomicBoolean autoSaveBlocked = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var outcome = request.ticket().runIfCurrent(() -> {
                Path target = request.target();
                if (com.editora.vfs.RemoteFileSystems.isDisconnected(target)) {
                    throw new IOException(connectionLost(target)); // before a request that can only fail oddly
                }
                for (int attempt = 0; request.ticket().isCurrent(); attempt++) {
                    SaveTarget state = SaveTarget.of(Files.exists(target), Files.notExists(target));
                    if (state == SaveTarget.INDETERMINATE) {
                        // Neither present nor absent (a dropped mount, a folder that can no longer be
                        // searched). The commit check can never pass, so retrying would spin forever.
                        throw new IOException(tr("status.save.targetUnreachable", target.getFileName()));
                    }
                    if (attempt >= SaveTarget.MAX_ATTEMPTS) {
                        throw new IOException(tr("status.save.targetKeptChanging", target.getFileName()));
                    }
                    if (state == SaveTarget.PRESENT && attempt == 0 && !mayReplaceReadOnly(request, autoSave)) {
                        return null;
                    }
                    RemoteWritePlan plan = state == SaveTarget.PRESENT
                            ? prepareRemoteWrite(request, autoSave, attempt > 0, autoSaveBlocked)
                            : new RemoteWritePlan(true, null, true);
                    if (!plan.proceed()) {
                        return null;
                    }
                    DiskWrite disk = writeToDisk(request, plan);
                    if (disk != null || !request.ticket().isCurrent()) {
                        return disk;
                    }
                    // The target appeared or changed after preflight. Read the new preimage and ask before
                    // overwriting it, including Save As targets that were absent when the dialog closed.
                }
                return null;
            });
            DiskWrite disk = outcome.executed() ? outcome.value() : null;
            if (disk != null) {
                publishCommit(request, disk);
            }
            Platform.runLater(() -> {
                try {
                    if (disk != null) {
                        completeSave(request, disk, autoSave, request.ticket().isCurrent());
                    } else {
                        rollbackFailedSaveAs(request);
                        if (autoSaveBlocked.get()
                                && request.ticket().isCurrent()
                                && !request.buffer().isDisposed()) {
                            // The disk copy differs from what this buffer was loaded from. Say so, and ask
                            // now if it is the file being looked at, rather than leaving auto-save silently off.
                            host.setStatus(tr(
                                    "status.autoSaveConflict", request.target().getFileName()));
                            checkExternalChanges();
                        }
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
                        // A connection that dropped mid-save surfaces as whatever its last request threw.
                        String why = com.editora.vfs.RemoteFileSystems.isDisconnected(request.target())
                                ? connectionLost(request.target())
                                : e.getMessage();
                        host.setStatus(tr(autoSave ? "status.autoSaveFailed" : "status.failedSave", why));
                    }
                    rollbackFailedSaveAs(request);
                    completeFuture(completed, false);
                } finally {
                    finishRequest(request);
                }
            });
        }
    }

    /** Why a save to a closed connection failed, and the two ways to keep the text. */
    private static String connectionLost(Path target) {
        return tr(
                "status.remote.connectionLost",
                com.editora.vfs.Vfs.authorityOf(target),
                tr("command.remote.connect"),
                tr("command.file.saveAs"));
    }

    private void completeSave(SaveRequest request, DiskWrite disk, boolean autoSave, boolean showFeedback) {
        host.historyCoordinator()
                .record(
                        request.target(),
                        request.content(),
                        autoSave ? HistoryRevision.REASON_AUTOSAVE : HistoryRevision.REASON_SAVE);
        acknowledgeLatestCommit(request.buffer());
        if (request.saveAs()
                && request.buffer().getPath() != null
                && com.editora.config.PathKeys.sameNormalized(request.buffer().getPath(), request.target())) {
            saveAsOrigins.remove(request.buffer());
        }
        boolean stillThisFile = !request.buffer().isDisposed()
                && request.buffer().getPath() != null
                && com.editora.config.PathKeys.sameNormalized(request.buffer().getPath(), request.target());
        if (request.charsetFallback() != null && stillThisFile) {
            // The file on disk is UTF-8 with a BOM now; the status bar and the next save must agree with it.
            host.editorSettings().charsetFellBackToUtf8(request.buffer());
        }
        if (".editorconfig".equals(String.valueOf(request.target().getFileName()))) {
            host.editorConfigSaved(); // its rules reach the files already open, in every window
        }
        ProjectPanel.noteLocalWrite(host.projectPanel(), request.target()); // ours: not an external change
        if (showFeedback && !request.buffer().isDisposed()) {
            host.setStatus(savedStatus(request, disk, autoSave));
            host.git().refresh();
            host.lspCoordinator().notifyDocumentSaved(request.buffer(), request.savedText());
            if (!autoSave) {
                host.refreshBuildTools();
                host.indexCoordinator().onBufferSaved(request.buffer());
            }
        }
    }

    /** What a finished save says: the warnings a plain "Saved" used to overwrite a few milliseconds later. */
    private static String savedStatus(SaveRequest request, DiskWrite disk, boolean autoSave) {
        if (request.charsetFallback() != null) {
            return tr(
                    "status.charsetFallback",
                    com.editora.editorconfig.EditorConfigCharset.displayName(request.charsetFallback()));
        }
        if (disk.inPlace()) {
            return tr("status.savedInPlace", com.editora.config.PathDisplay.of(request.target()));
        }
        return autoSave
                ? tr("status.autoSaved", request.target().getFileName())
                : tr("status.saved", com.editora.config.PathDisplay.of(request.target()));
    }

    private void rollbackFailedSaveAs(SaveRequest request) {
        if (!request.saveAs()
                || !request.ticket().isCurrent()
                || request.buffer().isDisposed()) {
            return;
        }
        EditorBuffer buffer = request.buffer();
        Path current = buffer.getPath();
        if (current == null || !com.editora.config.PathKeys.sameNormalized(current, request.target())) {
            return;
        }
        SaveAsOrigin origin = saveAsOrigins.remove(buffer);
        if (origin == null) {
            return;
        }
        buffer.setPath(origin.path());
        host.bufferPathChanged(buffer, current, false);
        host.editorSettings().applyEditorConfig(buffer);
        if (origin.path() == null) {
            buffer.markUnsaved();
        }
        Tab tab = host.tabFor(buffer);
        if (tab != null) {
            host.updateTabMeta(tab, buffer);
        }
        if (buffer == host.activeBuffer()) {
            host.breadcrumb().setActiveFile(origin.path());
            host.updateProjectFolderView();
        }
    }

    private void publishCommit(SaveRequest request, DiskWrite disk) {
        // Hashed here, by whoever performed the write (the save worker for every asynchronous save), so the
        // FX-thread acknowledgement that follows never runs SHA-256 over the file.
        CommittedSave committed = new CommittedSave(
                request.sequence(),
                request.target(),
                request.content(),
                fingerprint(request.bytes()),
                request.bytes().length,
                disk,
                request.lineEnding());
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
            return committed.wrote(java.nio.file.Files.readAllBytes(target));
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
        // With the ending that was written: a line-ending conversion chosen while this write was in flight is
        // not on disk (the text is the same, so the text alone would call the buffer clean), and converting
        // back afterwards matches the disk again.
        buffer.acknowledgeSavedContent(committed.content(), committed.lineEnding());
        buffer.setDiskSnapshot(
                committed.disk().modifiedMillis(), committed.disk().size(), committed.fingerprint());
    }

    boolean hasPendingSave(EditorBuffer buffer) {
        return pendingSaves.getOrDefault(buffer, 0) > 0;
    }

    void shutdown() {
        shutdown = true;
        autoSaveIdleTimer.stop();
        autoSaveExecutor.shutdownNow();
        remoteSaveExecutors.values().forEach(ExecutorService::shutdownNow);
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
