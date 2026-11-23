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
import org.jspecify.annotations.Nullable;

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
            @Nullable String charsetFallback,
            SaveNotes notes,
            EncryptionCoordinator.@Nullable Seal seal,
            SaveRules rules) {

        /** What goes on disk: the encoded text, or — for an encrypted file — its ciphertext, once sealed. */
        byte[] diskBytes() {
            return seal == null ? bytes() : seal.sealed();
        }

        // The payload as the save worker worked it out again when the file's .editorconfig had changed since the
        // FX thread captured the save ({@link #reresolveRules}); else as captured.

        @Override
        public String savedText() {
            SavePayload again = rules.again;
            return again == null ? savedText : again.text();
        }

        @Override
        public byte[] bytes() {
            SavePayload again = rules.again;
            byte[] encoded = again == null ? null : again.encoding().bytes();
            return encoded == null ? bytes : encoded;
        }

        @Override
        public @Nullable String charsetFallback() {
            SavePayload again = rules.again;
            return again == null ? charsetFallback : again.encoding().fallbackFrom();
        }

        @Override
        public SaveNotes notes() {
            SavePayload again = rules.again;
            return again == null ? notes : again.notes();
        }
    }

    /**
     * What a save needs to work its payload out again on the save worker with the {@code .editorconfig} rules as
     * they are on disk then (PERF-01, Pauta 6.2.0: the FX thread looks at no file; an outside change governs the very
     * next save). {@code inputs} is null when the rules do not apply (EditorConfig off, a remote file, Save As's own
     * freshly resolved target).
     */
    private static final class SaveRules {
        final @Nullable SaveInputs inputs;
        /** The rules the save worker found, when it looked. */
        volatile com.editora.editorconfig.@Nullable EditorConfigProperties found;
        /** The payload worked out again by them, when they differed from the ones captured. */
        volatile @Nullable SavePayload again;

        SaveRules(@Nullable SaveInputs inputs) {
            this.inputs = inputs;
        }
    }

    /** A save's text and the buffer's encoding facts, as captured on the FX thread. */
    private record SaveInputs(
            String content,
            MixedLineEndings.@Nullable Source mixedSource,
            @Nullable Path target,
            String language,
            ProjectScope scope,
            com.editora.editorconfig.EditorConfigProperties rulesUsed,
            String baseLineEnding,
            @Nullable String userCharset,
            String detectedCharset,
            boolean charsetAssumed,
            boolean bomless,
            String lineEnding,
            boolean lineEndingForced,
            String effectiveCharset,
            boolean resolveOnDisk) {}

    /** A charset the save worker found cannot hold what the buffer holds: nothing is written. */
    private static final class Unencodable extends IOException {
        final SaveEncoding.@Nullable Unencodable first;
        final String charset;

        Unencodable(SaveEncoding.@Nullable Unencodable first, String charset) {
            super("unencodable");
            this.first = first;
            this.charset = charset;
        }
    }

    private record SaveAsOrigin(@Nullable Path path) {}

    private record SavePayload(String text, SaveEncoding.Plan encoding, SaveNotes notes) {}

    private record DiskWrite(long modifiedMillis, long size, boolean inPlace) {
        DiskWrite(long modifiedMillis, long size) {
            this(modifiedMillis, size, false);
        }
    }

    /**
     * What a physical commit wrote. Neither the bytes nor the text are kept — one copy of every saved open file
     * each, used only to ask "is this still what we wrote?" and "is the buffer back at what was saved?" — but
     * their SHA-256 and length, which answer the same questions (as {@link EditorBuffer.DiskSnapshot} does for
     * a load, and {@code SavedBaseline} for the buffer). {@code documentVersion} is the buffer's version the
     * text was captured at, so an acknowledgement on an unchanged document can share its snapshot instead.
     */
    private record CommittedSave(
            long sequence,
            Path target,
            long documentVersion,
            int textLength,
            String textFingerprint,
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

    private record RemoteWritePlan(boolean proceed, byte @Nullable [] expectedBytes, boolean expectedAbsent) {}

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

        /** The Project panel if it has been built (its window opened), else {@code null}. */
        ProjectPanel projectPanel();

        /** What the Project tree is (or will be) rooted at; {@code null} outside a project with no folder. */
        @Nullable
        Path projectRoot();

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

        @Nullable
        EditorBuffer activeBuffer();

        Tab addBuffer(EditorBuffer buffer);

        Tab addBuffer(EditorBuffer buffer, boolean select);

        Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings);

        Tab addContentTab(TabContent content, boolean select);

        void updateTabMeta(@Nullable Tab tab, EditorBuffer buffer);

        void bufferPathChanged(EditorBuffer buffer, @Nullable Path oldPath, boolean oldAlreadyClosed);

        void promoteTab(Tab tab);

        void finishAsyncOpen(Tab tab, EditorBuffer buffer, PreparedLoad load);

        void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error);

        String autoSaveModeOf(String mode);

        String autoSaveLabel(String mode);

        @Nullable
        EditorBuffer bufferOf(@Nullable Tab tab);

        /** {@link #bufferOf}, building the editor of a restored tab the session restore has not reached yet. */
        @Nullable
        EditorBuffer ensureBuffer(Tab tab);

        @Nullable
        Tab tabFor(EditorBuffer buffer);

        @Nullable
        Tab tabForPath(@Nullable Path file);

        /** Whether a tab of <em>another</em> window shows {@code file}. */
        boolean openInAnotherWindow(Path file);

        /** A {@code .editorconfig} was saved: every window re-resolves the rules of the files it has open. */
        void editorConfigSaved();

        /** {@code file} was just written by a save (a user snippet file's snippets go live at once). */
        void fileSaved(Path file);

        /** A user save (not an autosave) of {@code buffer} has reached its language server: a Java debug session
         *  may load the change into the running program (hot code replace). */
        default void sourceSaved(EditorBuffer buffer) {}

        @Nullable
        Path tabPath(Tab tab);

        void requestSave();

        @Nullable
        Tab tabForBuffer(EditorBuffer buffer);

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);

        @Nullable
        Path pathOf(java.io.File file);

        /** The window's shared services (the overlay host, the error echo, …). */
        CoordinatorHost services();

        /** Re-derives the status bar from the active buffer (after a buffer's encryption changes). */
        void refreshStatusBar();

        /** The status bar's encryption item, which the encryption details card is anchored to; may be null. */
        javafx.scene.Node encryptionStatusItem();

        /** Runs a registered command (Open Doctor from a locked encrypted file). */
        void runCommand(String commandId);
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
                    target, bytes, commit, host.config().getConfigDir().resolve(SaveBackupRecovery.FOLDER));
        }
    };

    /** UTF-16 buffers whose file had no byte-order mark, so a save does not add one. FX-thread only. */
    private final Set<EditorBuffer> bomlessUtf16 = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** The window this coordinator works in (crash recovery opens its buffers through the same operations). */
    Host host() {
        return host;
    }

    /** Whether a save of {@code buffer} writes a byte-order mark where its charset has one (crash recovery). */
    boolean writesBom(EditorBuffer buffer) {
        return !bomlessUtf16.contains(buffer);
    }

    /** Restores what {@link #writesBom} reported for a buffer that was not loaded from its file. */
    void setWritesBom(EditorBuffer buffer, boolean bom) {
        if (bom) {
            bomlessUtf16.remove(buffer);
        } else {
            bomlessUtf16.add(buffer);
        }
    }

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

    /** Starts the elevation tool: its availability probe, and the privileged copy itself. */
    @FunctionalInterface
    interface ElevationProcess {

        com.editora.process.ProcessRunner.Result run(java.time.Duration timeout, List<String> argv);
    }

    /**
     * The one place {@code pkexec}/{@code osascript} is launched from. Replaced by tests, which must never
     * raise the operating system's password prompt.
     */
    volatile ElevationProcess elevationProcess =
            (timeout, argv) -> com.editora.process.ProcessRunner.run(null, timeout, argv);

    /** Where Save As writes: the native file chooser's answer, null when it is cancelled. Replaced by tests. */
    volatile java.util.function.Function<EditorBuffer, @Nullable Path> saveAsTargetChooser = this::chooseSaveAsTarget;

    /** Test seam for holding an actual write worker while proving the FX thread remains responsive. */
    volatile @Nullable Runnable beforeDocumentWriteForTest;

    FileWorkflowCoordinator(Host host) {
        this.host = host;
        this.encodings = new EncodingCommands(host, this);
        this.encryption = new EncryptionCoordinator(host, this);
        com.editora.io.Trash.warm(); // asked on the FX thread later: looked for here, off it (PERF-01, Pauta 6.2.0)
    }

    /** Reopen / Save with Encoding. */
    private final EncodingCommands encodings;

    EncodingCommands encodings() {
        return encodings;
    }

    private final EncryptionCoordinator encryption;

    /** Encrypted files: decrypting on open, encrypting on save, and the commands around them. */
    EncryptionCoordinator encryption() {
        return encryption;
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
    ExecutorService saveExecutor(@Nullable Path target) {
        String authority = shutdown || target == null ? null : com.editora.vfs.Vfs.authorityOf(target);
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
    final ExecutorService fileLoadExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("file-load-", 0).factory());

    /**
     * A paragraph this wide opens the file in long-line mode: no undo, no highlighting, wrap held off, the line laid
     * out in segments. Shorter long lines are left to the highlighter, which leaves a line over 20,000 characters
     * unstyled and shows one in many colours plain (TextMateHighlighter.MAX_TOKENIZED_LINE, MAX_LONG_LINE_TOKENS),
     * to word wrap, which holds itself off for a line of 10,000 characters or an unbroken run of 2,000
     * ({@link com.editora.editor.WordWrap#tooCostlyToWrap}), and a bundle of them opens in View mode
     * ({@link com.editora.text.LongLineFiles}) — all keep undo. Was 64 KiB until measured on 2026-11-22 (JavaFX
     * 27): unwrapped, with those limits, a caret jump on a 64K-character line took 10–15 ms, 256K about 50 ms and
     * 1M 100–150 ms, segmented or not. Wrapped is another matter (about the square of the line: 11 s to open one
     * of 32K), which is why the wrap hold comes so much sooner.
     */
    static final int LONG_LINE_FILE_CHARS = 1024 * 1024;

    /** The file each buffer last loaded, so the first-open View mode is never applied again on a reload. */
    private final Map<EditorBuffer, Path> viewFirstChecked = new java.util.WeakHashMap<>();

    /** Buffers whose tab shell exists but whose document has not reached the FX thread yet. FX-thread only. */
    final Set<EditorBuffer> loadingBuffers = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Navigation requested while a shell is loading (CLI file:line, diagnostics, external-open). */
    final Map<EditorBuffer, List<Runnable>> afterBufferLoad = new IdentityHashMap<>();

    /** Re-entrancy guard so the external-change prompt (which steals focus) doesn't re-trigger itself. */
    boolean checkingExternalChanges;

    void openPath(@Nullable Path file) {
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
    void openPath(@Nullable Path file, boolean quietIfOpen) {
        if (file == null) {
            return;
        }
        if (file.getFileName() == null) {
            // A root ("/", "D:\", an SFTP host's "/" — what Remote > Open File makes of sftp://host) is a
            // folder with no name. Everything below names the file, so it used to throw instead of answering.
            host.setStatus(tr("status.openIsFolder", com.editora.config.PathDisplay.of(file)));
            return;
        }
        Tab existing = host.tabForPath(file);
        if (existing != null) {
            // Already open — switch to its tab instead of opening a duplicate. Asking for it explicitly
            // is a choice, so if it was only being previewed it stops being disposable.
            host.promoteTab(existing);
            TabFocus.select(host.editorArea(), existing); // an image or PDF too (thirteenth review AK1)
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
        openTextBufferAsync(file, classifyBinary, false);
    }

    /**
     * A command-line {@code FILE} target: its tab is there at once, on the first frame, as any open's; a file that
     * is not there yet, in a local folder that is, becomes a new empty buffer bound to it ({@link #openNewFileAt}) —
     * which the read finds out, off the FX thread (PERF-01, Pauta 6.2.0).
     */
    void openStartupTarget(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (host.tabForPath(file) != null
                || !com.editora.vfs.Vfs.isLocal(file)
                || name.isEmpty()
                || ImageFormats.isSupported(name)
                || PdfViewerPane.isPdf(name)) {
            openPath(file, true);
            return;
        }
        openTextBufferAsync(file, true, true);
    }

    private void openTextBufferAsync(Path file, boolean classifyBinary, boolean newIfMissing) {
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
                boolean fresh = newIfMissing && isNewFileTarget(file);
                Platform.runLater(() -> ifWindowOpen(() -> {
                    host.failAsyncOpen(tab, buffer, file, e);
                    if (fresh) {
                        openNewFileAtLooked(file); // its status replaces the failure's
                    }
                }));
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
        return isNewFileTarget(file) && openNewFileAtLooked(file);
    }

    /**
     * Whether {@code file} is a new file in an existing local folder — the disk half of {@link #openNewFileAt}, for
     * a caller that looks off the FX thread (PERF-01, Pauta 6.2.0).
     */
    static boolean isNewFileTarget(Path file) {
        com.editora.perf.BlockingSites.enter("FileWorkflowCoordinator.isNewFileTarget");
        Path parent = file.getParent();
        return com.editora.vfs.Vfs.isLocal(file)
                && parent != null
                && Files.isDirectory(parent)
                && Files.notExists(file);
    }

    /** {@link #openNewFileAt} for a {@code file} {@link #isNewFileTarget} said yes to; false when it is open. */
    boolean openNewFileAtLooked(Path file) {
        if (host.tabForPath(file) != null) {
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
     * Opens {@code file} with the caret on the 0-based {@code line} at {@code column} — or, when the column is
     * negative, on the first {@code name} in that line (else its first non-blank) — and then runs the command
     * {@code commandId} there: how the Project Map follows code from a card or a declaration (review 3 C12).
     */
    void openThenRun(Path file, int line, int column, @Nullable String name, String commandId) {
        openThen(file, () -> {
            EditorBuffer buffer = host.services().activeBuffer();
            if (buffer != null) {
                var area = buffer.getArea();
                int paragraph =
                        Math.clamp(line, 0, Math.max(0, area.getParagraphs().size() - 1));
                String text = area.getParagraph(paragraph).getText();
                int at = followColumn(text, column, name);
                area.moveTo(paragraph, at);
                area.requestFollowCaret();
            }
            host.runCommand(commandId);
        });
    }

    /** Where in {@code text} the caret goes for {@link #openThenRun}. Pure. */
    static int followColumn(String text, int column, @Nullable String name) {
        if (column >= 0) {
            return Math.min(column, text.length());
        }
        int found = name == null || name.isEmpty() ? -1 : followNameAt(text, name);
        if (found >= 0) {
            return found;
        }
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) {
            first++;
        }
        return first;
    }

    /** The first place {@code name} stands in {@code text} as a whole word, else -1. */
    private static int followNameAt(String text, String name) {
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
            boolean before = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            int end = at + name.length();
            boolean after = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
            if (before && after) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Runs {@code action} with {@code file}'s tab selected once its text is in the buffer: now when it is
     * already loaded, else when the load lands. Never runs when the file has no text tab (an image, a PDF, a
     * hex view), when its load fails, or when the tab is closed first.
     */
    void whenLoaded(@Nullable Path file, Runnable action) {
        Tab tab = host.tabForPath(file);
        EditorBuffer buffer = tab == null ? null : host.ensureBuffer(tab); // a restored tab not built yet: build it
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
    Tab openPdfTab(@Nullable Path file, boolean select) {
        PdfViewerPane pane = new PdfViewerPane(file);
        Tab tab = host.addContentTab(pane, select);
        Platform.runLater(pane::relayout); // fit-to-window once the tab is laid out
        return tab;
    }

    /**
     * Reloads the PDF viewer tab that shows {@code file}, if this window has one: an export has just replaced
     * the file, and the viewer keeps the document it read in memory. A text buffer on the file is left to the
     * external-change check, which knows about unsaved edits.
     */
    void reloadViewerTab(Path file) {
        Tab tab = host.tabForPath(file);
        if (tab != null && tab.getUserData() instanceof PdfViewerPane pdf) {
            pdf.reload();
        }
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
     *   <li>{@link EditorBuffer#largeFileMode} (by lines, or bytes as a backstop): full read, but highlighting,
     *       the minimap and undo disabled;</li>
     *   <li>a document the free heap cannot hold ({@link LoadHeapGuard}): the part that fits, read-only;</li>
     *   <li>otherwise: full read, normal editing.</li>
     * </ul>
     */
    String loadInto(EditorBuffer buffer, Path file) throws IOException {
        String note = applyPreparedLoad(buffer, prepareLoad(file, false).preparedFor(buffer));
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
        if (!firstPaintMatters() || perfPaintTimerStarted) {
            return;
        }
        perfPaintTimerStarted = true;
        com.editora.perf.Startup.mark(com.editora.perf.Startup.FILE_LOADED);
        // A pulse's handle() runs at the *start* of a pulse, before that pulse renders. So the first tick
        // only means "a pulse is beginning"; it's the second tick that proves the pulse in between — the one
        // that laid out and painted this content — completed. Accurate to about one frame (~16 ms), which is
        // the honest resolution of "when did the user first see it" without hooking Prism internals.
        WindowSessionCoordinator.afterNextPaint(FileWorkflowCoordinator::firstPainted);
    }

    /**
     * The first-paint mark for a launch that loads no file: the window's own first frame. Same one-shot
     * timer as {@link #notePerfContentLoaded}, so whichever of the two arms first is the one that reports.
     */
    void notePerfWindowShown() {
        if (!firstPaintMatters() || perfPaintTimerStarted) {
            return;
        }
        perfPaintTimerStarted = true;
        WindowSessionCoordinator.afterNextPaint(FileWorkflowCoordinator::firstPainted);
    }

    /**
     * Whether anything waits for the first painted frame: the startup report, or the probes a launch holds
     * back until the user's file is on screen ({@link com.editora.perf.AfterFirstPaint}). A window built
     * outside a launch (a test, a later window) arms no timer.
     */
    private static boolean firstPaintMatters() {
        return com.editora.perf.Startup.enabled() || com.editora.perf.AfterFirstPaint.held();
    }

    /** The frame after the first content is in place has rendered: report it, and let the held work go. */
    private static void firstPainted() {
        com.editora.perf.Startup.mark(com.editora.perf.Startup.FIRST_PAINT);
        com.editora.perf.AfterFirstPaint.release();
    }

    /** True once the first-paint timer has been armed, so it arms for one buffer only. */
    boolean perfPaintTimerStarted;

    /** Immutable disk-side result; no JavaFX object is touched while this is prepared. */
    record PreparedLoad(
            Path file,
            @Nullable String content,
            long size,
            long mtime,
            int lines,
            int maxLineLength,
            @Nullable String charset,
            com.editora.editorconfig.EditorConfigProperties editorConfig,
            boolean binary,
            boolean large,
            boolean heavy,
            boolean longLine,
            boolean truncated,
            boolean log,
            long logOffset,
            boolean tail,
            byte @Nullable [] sourceBytes,
            @Nullable String declaredCharset,
            @Nullable String fingerprint,
            com.editora.editor.@Nullable InitialDocument document,
            boolean encrypted) {

        /**
         * Hashes the file's bytes where the load is prepared — the read worker — so the SHA-256 of a large
         * file is never computed on the FX thread when the prepared document is applied.
         */
        PreparedLoad(
                Path file,
                @Nullable String content,
                long size,
                long mtime,
                int lines,
                int maxLineLength,
                @Nullable String charset,
                com.editora.editorconfig.EditorConfigProperties editorConfig,
                boolean binary,
                boolean large,
                boolean heavy,
                boolean longLine,
                boolean truncated,
                boolean log,
                long logOffset,
                boolean tail,
                byte @Nullable [] sourceBytes,
                @Nullable String declaredCharset) {
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
                    binary || sourceBytes == null
                            ? null
                            : FileWorkflowCoordinator.fingerprint(sourceBytes), // a hex view compares nothing
                    null,
                    false);
        }

        /**
         * An encrypted file, read but not decrypted: no text, its ciphertext in {@code sourceBytes} (null when it
         * is too large to decrypt into an editor) and the fingerprint of those bytes, so the save-time conflict
         * check and external-change detection compare the file without ever decrypting it.
         */
        static PreparedLoad locked(
                Path file,
                long size,
                long mtime,
                com.editora.editorconfig.EditorConfigProperties editorConfig,
                byte @Nullable [] ciphertext,
                @Nullable String fingerprint) {
            return new PreparedLoad(
                    file,
                    null,
                    size,
                    mtime,
                    0,
                    0,
                    null,
                    editorConfig,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0,
                    false,
                    ciphertext,
                    null,
                    fingerprint,
                    null,
                    true);
        }

        /** This load with {@code fingerprint} and {@code mtime}/{@code size} of the file it was decrypted from. */
        PreparedLoad decryptedFrom(long fileSize, long fileMtime, @Nullable String fileFingerprint) {
            return new PreparedLoad(
                    file,
                    content,
                    fileSize,
                    fileMtime,
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
                    null, // never plaintext bytes: nothing may write them back verbatim or keep a copy
                    declaredCharset,
                    fileFingerprint,
                    document,
                    false);
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
                    buffer.prepareInitialContent(content, longLine),
                    encrypted);
        }

        /**
         * This load with its line endings resolved on the read worker but no paragraphs built: what an
         * in-place reload needs, which rewrites only the span that differs ({@link EditorBuffer#reloadContent})
         * and so never splits the text into paragraphs at all.
         */
        PreparedLoad normalised() {
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
                    com.editora.editor.InitialDocument.ofText(content),
                    encrypted);
        }

        /** The declared charset could not decode the file, so {@link #charset} is a lossless stand-in. */
        boolean charsetAssumed() {
            return declaredCharset != null && !declaredCharset.equals(charset);
        }
    }

    /** Document shape computed while the decoded text is already in background-thread memory. */
    record TextStats(int lines, int maxLineLength) {

        /** Whether the file opens in long-line (large-file) mode: a line too wide for RichTextFX to lay out. */
        boolean longLined() {
            return maxLineLength >= LONG_LINE_FILE_CHARS;
        }
    }

    /** The file's lines as a reader counts them: a final line break does not start another (E109). */
    private static int textLines(PreparedLoad load) {
        String content = load.content();
        return com.editora.lsp.LspServerRegistry.lineCount(
                load.lines(), content == null || content.isEmpty() || content.endsWith("\n"));
    }

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
        com.editora.perf.BlockingSites.enter("FileWorkflowCoordinator.prepareLoad");
        if (forDocument) {
            // What the tab asks when it is shown, looked at here, off the FX thread (PERF-01, Pauta 6.2.0): whether
            // the file may be written (View mode), its repository, and whether a save needs the administrator.
            com.editora.vfs.Vfs.isWritableOnDisk(file);
            GitPathScope.lookAt(file); // the repository its tab's menu and the Git commands ask about
            needsElevation(file); // whether admin save is on now or turned on later, its Save then knows
            com.editora.config.PathKeys.warm(file); // the key every diagnostics publish and note asks for
            if (com.editora.vfs.Vfs.isLocal(file) && file.toString().endsWith(".java")) {
                JavaProjectRoot.warm(file); // its build root, which Run, Debug and their menu rows ask for
            }
        }
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
        if (com.editora.crypto.EncryptedFiles.hasEncryptionExtension(String.valueOf(file.getFileName()))) {
            PreparedLoad locked = EncryptionCoordinator.lockedLoad(file, size, mtime, editorConfig);
            if (locked != null) {
                return locked; // decrypted later, by the window's encryption feature (never as part of a read)
            }
        }
        long wanted = Math.min(size, EditorBuffer.HUGE_FILE_BYTES);
        // Before reading, only the characters are known: a file whose characters alone will not fit is read in
        // part, so it is never read whole. Its lines are counted after the read (prepareFromBytes).
        long charsCost = LoadHeapGuard.cost(wanted, 0, false);
        long cap = forDocument && charsCost >= LoadHeapGuard.CHECK_FROM_COST ? heapCap(wanted, charsCost) : wanted;
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
                        stats.longLined(),
                        true,
                        true,
                        tail.offset(),
                        true,
                        null,
                        null);
            }
            return cappedText(
                    file,
                    readCapped(file, (int) cap),
                    size,
                    mtime,
                    com.editora.editorconfig.EditorConfigCharset.UTF_8,
                    editorConfig);
        }
        byte[] bytes = Files.readAllBytes(file);
        return prepareFromBytes(file, size, mtime, isLog, editorConfig, bytes, sniffBinary, forDocument);
    }

    /**
     * A load of only the first part of a file, {@code content}: read-only (a save would truncate the file) and in
     * large-file mode, with no whole-file bytes to compare a later save with.
     */
    private static PreparedLoad cappedText(
            Path file,
            String content,
            long size,
            long mtime,
            String charset,
            com.editora.editorconfig.EditorConfigProperties editorConfig) {
        TextStats stats = textStats(content);
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
                true,
                false,
                stats.longLined(),
                true,
                false,
                0,
                false,
                null,
                null);
    }

    /**
     * An encrypted file's decrypted bytes, prepared as any file's are (encoding, binary sniff, size tiers) but
     * remembered as the file on disk is: its size, time and the fingerprint of its <em>ciphertext</em>. Off the
     * FX thread.
     */
    PreparedLoad prepareDecrypted(Path file, byte[] plaintext, long size, long mtime, @Nullable String fingerprint) {
        com.editora.editorconfig.EditorConfigProperties editorConfig =
                host.editorSettings().editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)
                        ? com.editora.editorconfig.EditorConfig.resolveFor(file.resolveSibling(
                                com.editora.crypto.EncryptedFiles.innerName(String.valueOf(file.getFileName()))))
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY;
        return prepareFromBytes(file, plaintext.length, mtime, false, editorConfig, plaintext, true, true)
                .decryptedFrom(size, mtime, fingerprint);
    }

    private PreparedLoad prepareFromBytes(
            Path file,
            long size,
            long mtime,
            boolean isLog,
            com.editora.editorconfig.EditorConfigProperties editorConfig,
            byte[] bytes,
            boolean sniffBinary,
            boolean forDocument) {
        // An encoding the user chose for this file wins over detection and .editorconfig — while the bytes are
        // still valid in it. When they are not, the file opens as it would without the choice, and the FX side
        // (applyPreparedLoad) forgets it.
        String chosen = chosenEncoding(file);
        com.editora.io.TextEncodings.Decoded forced =
                chosen == null ? null : com.editora.io.TextEncodings.decode(bytes, chosen);
        String forcedText = forced == null ? null : forced.text(); // null unless the bytes are valid in it
        boolean useChosen = forcedText != null;
        String declaredForSniff = editorConfig.charset();
        // BOM-less UTF-16 is half NUL bytes; when .editorconfig declares it, it is text, not a hex dump.
        if (sniffBinary
                && !useChosen
                && !(declaredForSniff != null
                        && com.editora.editorconfig.EditorConfigCharset.isBomlessUtf16(bytes, declaredForSniff))) {
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
        com.editora.editorconfig.EditorConfigCharset.Decoded decoded = forced != null && forcedText != null
                ? new com.editora.editorconfig.EditorConfigCharset.Decoded(
                        forcedText, forced.charset(), forced.charset(), false)
                : com.editora.editorconfig.EditorConfigCharset.decodeLossless(
                        bytes, declaredCharset(file, bytes, editorConfig));
        String charset = decoded.charset();
        String content = decoded.text();
        TextStats stats = textStats(content);
        boolean large = EditorBuffer.largeFileMode(size, stats.lines());
        // Intermediate tier: a long source file keeps highlighting, the minimap and editing but does not start its
        // language server (unless the language keeps it longer), the heaviest feature for a huge source.
        int threshold = host.config().getSettings().getLargeFileThreshold();
        boolean longLine = stats.longLined();
        boolean heavy = !large && !longLine && threshold > 0 && stats.lines() >= threshold;
        if (forDocument) {
            // Now the lines are known: a document they make too big for the free heap opens as the part that fits.
            long cost = LoadHeapGuard.cost(content.length(), stats.lines(), !large && !longLine);
            long allowed = cost >= LoadHeapGuard.CHECK_FROM_COST ? heapCap(content.length(), cost) : content.length();
            if (allowed < content.length()) {
                return cappedText(file, content.substring(0, (int) allowed), size, mtime, charset, editorConfig);
            }
        }
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

    /**
     * The first time a buffer gets a very large, very-long-lined or minified file, it opens in View mode with the
     * reason in the banner: typing there is slow (and, in the first two, cannot be undone), and such a file is
     * rarely meant to be edited (E104). Enable Editing is one click away; a reload never puts it back.
     */
    private void applyViewFirst(EditorBuffer buffer, PreparedLoad load, int chars) {
        Path name = load.file().getFileName();
        com.editora.text.LongLineFiles.Reason reason = com.editora.text.LongLineFiles.viewFirst(
                load.large(),
                load.longLine(),
                load.truncated(),
                load.log(),
                chars,
                load.lines(),
                com.editora.text.LanguageRegistry.forFileName(name == null ? "" : name.toString()));
        if (load.file().equals(viewFirstChecked.put(buffer, load.file()))) {
            return; // a reload of the file this buffer already shows
        }
        if (reason == null || buffer.isDirty() || buffer.isViewMode()) {
            return;
        }
        buffer.setViewMode(
                true,
                switch (reason) {
                    case LARGE -> tr("viewmode.reason.large", StatusBar.formatSize(load.size()));
                    case LONG_LINE -> tr("viewmode.reason.longLine", load.maxLineLength());
                    case MINIFIED -> tr("viewmode.reason.minified");
                });
    }

    /**
     * The encoding the user chose for {@code file} (Reopen or Save with Encoding), or null. Safe on the
     * file-load worker: the map is concurrent.
     */
    @Nullable
    String chosenEncoding(Path file) {
        if (file == null) {
            return null;
        }
        com.editora.config.WorkspaceState ws = host.config().getWorkspaceState();
        return ws == null ? null : ws.getFileEncodings().get(file.toString());
    }

    /** Remembers {@code charset} as {@code file}'s encoding, or forgets the choice when it is null. FX thread. */
    void rememberEncoding(@Nullable Path file, @Nullable String charset) {
        com.editora.config.WorkspaceState ws = host.config().getWorkspaceState();
        if (file == null || ws == null || java.util.Objects.equals(charset, chosenEncoding(file))) {
            return;
        }
        if (charset == null) {
            ws.getFileEncodings().remove(file.toString());
        } else {
            ws.getFileEncodings().put(file.toString(), charset);
        }
        host.requestSave();
    }

    /** {max, used} heap bytes; replaced by tests to stand in for a heap that is nearly full. */
    volatile java.util.function.Supplier<long[]> heapUsage = () -> {
        Runtime runtime = Runtime.getRuntime();
        return new long[] {runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory()};
    };

    /**
     * How many of a document's {@code chars} characters to load when the whole of it costs {@code cost} (see
     * {@link LoadHeapGuard}): all of them when it fits the free heap — else only what fits, which the caller opens
     * read-only like any other partial load.
     */
    private long heapCap(long chars, long cost) {
        long[] heap = heapUsage.get();
        if (LoadHeapGuard.fits(cost, heap[0], heap[1])) {
            return chars;
        }
        // "Used" counts garbage not collected yet (the previous large load leaves plenty), so collect once
        // before concluding that the file does not fit. Rare, and on the read worker.
        System.gc();
        heap = heapUsage.get();
        return LoadHeapGuard.cap(chars, cost, heap[0], heap[1]);
    }

    /**
     * The charset {@code .editorconfig} declares for {@code file}, except that a shell script is never read as
     * {@code utf-8-bom}: a save would write the mark that hides its {@code #!} line. A real BOM is still
     * detected (and the open notice offers to remove it).
     */
    private static @Nullable String declaredCharset(
            Path file, byte[] bytes, com.editora.editorconfig.EditorConfigProperties editorConfig) {
        String declared = editorConfig.charset();
        if (!com.editora.editorconfig.EditorConfigCharset.UTF_8_BOM.equals(declared) || file.getFileName() == null) {
            return declared;
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 256), java.nio.charset.StandardCharsets.ISO_8859_1);
        return com.editora.text.ShellScripts.isShellScript(file.getFileName().toString(), head) ? null : declared;
    }

    /** Applies a prepared document atomically on the FX thread, with all expensive-mode flags already active. */
    String applyPreparedLoad(EditorBuffer buffer, PreparedLoad load) {
        return applyPreparedLoad(buffer, load, null);
    }

    /**
     * As {@link #applyPreparedLoad(EditorBuffer, PreparedLoad)}; {@code inPlace}, when given, is a reload into a
     * buffer that already shows the file, which rewrites only the span that differs (worked out on the read worker)
     * instead of swapping the whole document — unless the load needs the segmented or capped profile, which only a
     * whole-document install provides.
     */
    String applyPreparedLoad(EditorBuffer buffer, PreparedLoad load, EditorBuffer.@Nullable PreparedEdit inPlace) {
        if (load.encrypted()) {
            return encryption.applyLocked(buffer, load); // its text arrives once it is decrypted
        }
        buffer.setDiskSnapshot(load.mtime(), load.size(), load.fingerprint());
        buffer.setTruncatedLoad(load.truncated());
        host.editorSettings().applyResolvedEditorConfig(buffer, load.editorConfig());
        String loadedCharset = load.charset();
        String charset = loadedCharset == null ? com.editora.editorconfig.EditorConfigCharset.UTF_8 : loadedCharset;
        buffer.setDetectedCharset(charset, load.charsetAssumed());
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
        buffer.wrap().setSuppressed(load.longLine());
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
        // Null only for a binary or encrypted load, neither of which is applied here; empty, as before.
        String loadedText = load.content();
        String text = loadedText == null ? "" : loadedText;
        if (inPlace != null && load.document() != null && !load.longLine() && !load.truncated()) {
            buffer.reloadContent(load.document(), inPlace);
        } else if (load.document() != null) {
            buffer.setInitialContent(load.document());
        } else {
            buffer.setInitialContent(text, load.longLine());
        }
        // A mixed file's bytes are kept: its document cannot say which terminator each line had.
        MixedLineEndings.loaded(
                buffer,
                load.file(),
                load.truncated() ? null : load.sourceBytes(),
                load.document() != null ? load.document().lineEnding() : com.editora.editops.LineEndings.dominant(text),
                load.document() != null
                        ? load.document().mixedLineEndings()
                        : com.editora.editops.LineEndings.mixed(text));
        if (load.log()) {
            host.logViewer().recordLoadOffset(buffer, load.logOffset());
        } else if (!load.truncated()) {
            host.logViewer().sniffLoaded(buffer, load.content()); // a log its name does not announce
        }
        String forgotten = applyChosenEncoding(buffer, load);
        applyViewFirst(buffer, load, text.length());
        if (!load.truncated() && load.file() != null && load.file().getFileName() != null) {
            ShellFileNotice.offer(
                    buffer,
                    load.file().getFileName().toString(),
                    load.document() != null
                            ? load.document().lineEnding()
                            : com.editora.editops.LineEndings.dominant(text),
                    buffer.getEffectiveCharset(),
                    host::setStatus);
        }
        if (load.truncated()) {
            return tr(
                    load.tail() ? "status.load.hugeLog" : "status.load.hugeFile",
                    load.file().getFileName(),
                    StatusBar.formatSize(load.size()),
                    StatusBar.formatSize(text.length()));
        }
        MixedLineEndings.Source mixed = MixedLineEndings.of(buffer);
        if (forgotten != null) {
            return tr(
                    "status.encodingForgotten",
                    load.file().getFileName(),
                    com.editora.editorconfig.EditorConfigCharset.displayName(forgotten),
                    com.editora.editorconfig.EditorConfigCharset.displayName(charset));
        }
        String declared = load.declaredCharset();
        if (declared != null && load.charsetAssumed()) {
            // "…so no bytes are lost" is only true of a file whose line endings are uniform.
            return tr(
                    mixed == null
                            ? "status.charsetAssumed"
                            : mixed.binary() ? "status.charsetAssumedMixedBinary" : "status.charsetAssumedMixed",
                    load.file().getFileName(),
                    com.editora.editorconfig.EditorConfigCharset.displayName(declared),
                    com.editora.editorconfig.EditorConfigCharset.displayName(charset),
                    buffer.getLineEnding());
        }
        if (mixed != null) {
            return tr(
                    mixed.binary() ? "status.mixedLineEndingsBinary" : "status.mixedLineEndings",
                    load.file().getFileName(),
                    buffer.getLineEnding());
        }
        if (load.large()) {
            return largeFileNote(load.file(), load.size());
        }
        if (load.longLine()) {
            return tr("status.longLineFileTier", load.maxLineLength());
        }
        if (load.heavy()
                && com.editora.lsp.LspServerRegistry.lspAllowedForSize(
                        com.editora.text.LanguageRegistry.forFileName(
                                String.valueOf(load.file().getFileName())),
                        true,
                        textLines(load))) {
            return ""; // nothing is off: the language keeps its server for a file this long (B10)
        }
        return load.heavy() ? tr("status.largeFileTier", textLines(load)) : "";
    }

    /**
     * Gives a loaded buffer the encoding the user chose for its file, so it is also the one written — or, when
     * the file is no longer valid in that encoding (the load fell back to detection), forgets the choice and
     * returns it for the status line. A capped load ignores choices: it is read as UTF-8 and read-only.
     */
    private @Nullable String applyChosenEncoding(EditorBuffer buffer, PreparedLoad load) {
        String chosen = load.truncated() || load.content() == null ? null : chosenEncoding(load.file());
        if (chosen != null && !chosen.equals(load.charset())) {
            rememberEncoding(load.file(), null);
            buffer.setUserCharset(null);
            return chosen;
        }
        buffer.setUserCharset(chosen);
        return null;
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

    private static boolean isUtf16(@Nullable String charset) {
        return com.editora.editorconfig.EditorConfigCharset.UTF_16LE.equals(charset)
                || com.editora.editorconfig.EditorConfigCharset.UTF_16BE.equals(charset);
    }

    static long fileSize(@Nullable Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The file's last-modified time in epoch millis, or {@code -1} when unavailable. */
    static long lastModifiedMillis(@Nullable Path file) {
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
        checkExternalChanges(false);
    }

    /**
     * The check for the active tab after the watcher on a remote folder's host reported {@code changed}: a
     * remote file is not asked about on every focus (a network round trip each time), but when its host says
     * it changed, it is asked once.
     */
    void checkRemoteExternalChanges(List<ProjectPanel.FsChange> changed) {
        EditorBuffer buffer = host.bufferOf(
                host.editorArea() == null ? null : host.editorArea().selectedTab());
        Path file = buffer == null ? null : buffer.getPath();
        if (file == null || !com.editora.vfs.Vfs.isRemote(file)) {
            return;
        }
        for (ProjectPanel.FsChange change : changed) {
            if (change.path().getFileSystem() == file.getFileSystem()
                    && change.path()
                            .toAbsolutePath()
                            .normalize()
                            .equals(file.toAbsolutePath().normalize())) {
                checkExternalChanges(true);
                return;
            }
        }
    }

    private void checkExternalChanges(boolean remoteToo) {
        if (checkingExternalChanges || host.editorArea() == null) {
            return;
        }
        host.previews().lint().checkConfigs(); // a markdownlint config edited elsewhere re-lints the open buffers
        Tab tab = host.editorArea().selectedTab();
        EditorBuffer buffer = host.bufferOf(tab);
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        Path file = buffer.getPath();
        boolean remote = com.editora.vfs.Vfs.isRemote(file);
        if (remote && !remoteToo) {
            return; // remote (SFTP) mtime polling would be a network call per focus — asked when its host reports it
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
        // Not for a remote file: a stat per ancestor folder over the network, for a reload check.
        boolean resolveConfig = host.editorSettings().editorConfigEnabled() && !remote;
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
                        if (!exists) {
                            noteMissingOnDisk(tab, buffer); // keep what's open, but no longer as "saved"
                            return;
                        }
                        if (!buffer.diskChangedFrom(mtime, size)) {
                            restoreIfBackOnDisk(tab, buffer); // unchanged — and back, if it had gone missing
                            return;
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
    volatile @Nullable Runnable beforeExternalStatForTest;

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
                            restoreIfBackOnDisk(tab, buffer);
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
        Path path = buffer.getPath();
        if (path == null) {
            return; // untitled: there is no file to have changed
        }
        Confirm.Choice words = ExternalChangePrompt.question(path.getFileName().toString(), buffer.isDirty());
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.stage());
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType reload = new ButtonType(words.answer(0));
        ButtonType keep = new ButtonType(words.cancel(), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reload, keep);
        if (buffer.isDirty()) {
            // Reload discards the unsaved edits: red, and never the default. Keep is this question's Cancel.
            Dialogs.destructive(alert, reload);
            ExternalChangePrompt.keepIsTheKeyboardDefault(alert, reload, keep);
        } else {
            ExternalChangePrompt.reloadIsTheKeyboardDefault(alert, reload);
        }
        if (Dialogs.showAndWait(alert).filter(b -> b == reload).isPresent()) {
            reloadFromDisk(tab, buffer);
        } else {
            buffer.setDiskSnapshot(mtime, size); // keep the editor's version, stop nagging about this change
            // What was kept is not what is on disk. Left clean, it had no modified marker, auto-save never
            // wrote it, and closing the tab dropped the version the user had just chosen without a word.
            buffer.markUnsaved();
            host.updateTabMeta(tab, buffer);
        }
    }

    /**
     * Clean buffers whose file was found missing, with the document version they had then. Such a buffer is
     * marked unsaved so that closing it asks first — but it holds no edit of the user's, so it becomes clean
     * again when the same bytes reappear, and a post-Git reload may still replace it. FX thread only.
     */
    private final Map<EditorBuffer, Long> missingOnDisk = new java.util.WeakHashMap<>();

    /**
     * The file of an open, clean buffer is gone (deleted or moved by another program, a {@code git clean}, a
     * branch switch). The editor now holds what may be the only copy: mark it unsaved and say so, instead of
     * letting the tab close without a word. FX thread.
     */
    void noteMissingOnDisk(Tab tab, EditorBuffer buffer) {
        if (buffer.isDisposed()
                || buffer.isDirty()
                || buffer.diskSnapshot().modifiedMillis() < 0 // never read from or written to disk
                || loadingBuffers.contains(buffer)
                || hasPendingSave(buffer)) {
            return;
        }
        buffer.markUnsaved();
        missingOnDisk.put(buffer, buffer.docVersion());
        host.updateTabMeta(tab, buffer);
        Path gone = buffer.getPath();
        host.setStatus(tr("status.fileGoneKeptUnsaved", gone == null ? buffer.getTitle() : gone.getFileName()));
    }

    /** Whether {@code buffer} is unsaved only because its file went missing: it carries no edit. FX thread. */
    boolean unsavedOnlyBecauseMissing(EditorBuffer buffer) {
        Long version = missingOnDisk.get(buffer);
        if (version == null) {
            return false;
        }
        if (version != buffer.docVersion() || !buffer.isDirty()) {
            missingOnDisk.remove(buffer); // edited or saved since: an ordinary buffer again
            return false;
        }
        return true;
    }

    /** The file is back with the bytes the buffer was loaded from: it is saved again. FX thread. */
    private void restoreIfBackOnDisk(Tab tab, EditorBuffer buffer) {
        if (unsavedOnlyBecauseMissing(buffer)) {
            missingOnDisk.remove(buffer);
            buffer.markClean();
            host.updateTabMeta(tab, buffer);
        }
    }

    /** Test seam: runs on the worker before {@link #reloadFromDisk} reads the file. */
    volatile @Nullable Runnable beforeReloadReadForTest;

    /** Reloads a buffer's content from disk, preserving the caret position as best it can. */
    void reloadFromDisk(Tab tab, EditorBuffer buffer) {
        reloadFromDisk(tab, buffer, ignored -> {});
    }

    /** Reloads asynchronously and reports on the FX thread whether the prepared snapshot was applied. */
    void reloadFromDisk(@Nullable Tab tab, EditorBuffer buffer, java.util.function.Consumer<Boolean> onComplete) {
        reloadFromDisk(tab, buffer, HistoryRevision.REASON_EXTERNAL, onComplete);
    }

    /**
     * As {@link #reloadFromDisk(Tab, EditorBuffer, java.util.function.Consumer)}; the text the reload replaces is
     * kept in Local History under {@code historyReason} — {@link HistoryRevision#REASON_REVERT} for Revert File and
     * Reopen with Encoding, so the row says what replaced it (F3).
     */
    void reloadFromDisk(
            @Nullable Tab tab,
            EditorBuffer buffer,
            String historyReason,
            java.util.function.Consumer<Boolean> onComplete) {
        Path file = buffer.getPath();
        if (file == null) {
            onComplete.accept(false); // untitled: nothing on disk to reload
            return;
        }
        invalidatePendingWrite(file);
        // The decision to let the disk win is about the document as it is now. The read comes back on a
        // later FX turn; whatever was typed in between was never part of that decision and must survive.
        ReloadGuard guard = ReloadGuard.capture(buffer);
        // The text the differing span is worked out against, on the read worker (PERF-01, Pauta 6.2.0).
        EditorBuffer.PreparedEdit.@Nullable Base base = buffer.isNarrowed()
                ? null
                : new EditorBuffer.PreparedEdit.Base(buffer.docVersion(), buffer.getVisibleContent());
        fileLoadExecutor.execute(() -> {
            try {
                Runnable hook = beforeReloadReadForTest;
                if (hook != null) {
                    hook.run();
                }
                PreparedLoad read = prepareLoad(file, false);
                // The differing-span rewrite needs no paragraphs; the segmented/capped profiles need them all.
                PreparedLoad load = read.longLine() || read.truncated() ? read.preparedFor(buffer) : read.normalised();
                com.editora.editor.InitialDocument doc = load.document();
                EditorBuffer.@Nullable PreparedEdit inPlace = base != null && doc != null ? base.to(doc.text()) : null;
                Platform.runLater(() -> {
                    boolean applied = false;
                    try {
                        if (!buffer.isDisposed()
                                && buffer.getPath() != null
                                && com.editora.config.PathKeys.sameNormalized(buffer.getPath(), file)) {
                            if (guard.stillHolds(buffer)) {
                                applyPreparedReload(tab, buffer, load, inPlace, historyReason);
                                applied = true;
                            } else {
                                // Left as it is: unsaved against a changed disk, which the external-change
                                // prompt and the save-time conflict check already handle.
                                host.updateTabMeta(tab, buffer);
                                host.setStatus(tr("status.reloadSkippedEdited", file.getFileName()));
                            }
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
    private void applyPreparedReload(
            @Nullable Tab tab, EditorBuffer buffer, PreparedLoad load, EditorBuffer.@Nullable PreparedEdit inPlace) {
        applyPreparedReload(tab, buffer, load, inPlace, HistoryRevision.REASON_EXTERNAL);
    }

    private void applyPreparedReload(
            @Nullable Tab tab,
            EditorBuffer buffer,
            PreparedLoad load,
            EditorBuffer.@Nullable PreparedEdit inPlace,
            String historyReason) {
        // Only the span that differs is rewritten, but a change above the viewport still moves it and a fold
        // inside the span is lost: keep the user's place (caret line/column, scroll position, collapsed folds).
        ReloadViewState view = ReloadViewState.capture(buffer);
        host.historyCoordinator().record(buffer, historyReason); // snapshot the in-memory version before disk wins
        String note = applyPreparedLoad(buffer, load, inPlace);
        notePerfContentLoaded(buffer);
        buffer.markClean();
        view.restore();
        host.updateTabMeta(tab, buffer);
        host.lspCoordinator().reloadedFromDisk(buffer);
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

    static {
        // The View mode banner answers from what the file's read looked at (PERF-01, Pauta 6.2.0).
        EditorBuffer.setWritableCheck(com.editora.vfs.Vfs::isWritableOnDisk);
    }

    /** Whether the elevation tool is present (macOS osascript always; Linux pkexec probed on PATH). */
    boolean adminToolAvailable;

    /** Admin-save is enabled in Settings and the OS supports it (Linux/polkit or macOS/osascript). */
    boolean adminSaveEnabled() {
        return host.config().getSettings().isAdminSave()
                && com.editora.process.ElevatedSave.supportedOnOs(com.editora.Os.current());
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
    boolean adminSaveApplicable(@Nullable Path p) {
        return elevationAvailable() && p != null && com.editora.vfs.Vfs.isLocal(p) && needsElevation(p);
    }

    /**
     * Whether a plain save of the local file {@code p} would fail for permissions where an elevated one could not:
     * looked at off the FX thread when the file is read ({@link #prepareLoad}) and after a save, answered from
     * memory on it (PERF-01, Pauta 6.2.0).
     */
    private static boolean needsElevation(Path p) {
        return Boolean.TRUE.equals(NEEDS_ELEVATION.get(p));
    }

    private static final com.editora.perf.Looked<Path, Boolean> NEEDS_ELEVATION = new com.editora.perf.Looked<>(
            "FileWorkflowCoordinator.adminSaveApplicable",
            p -> Files.exists(p) && !Files.isWritable(p) && !replaceableWithoutElevation(p),
            Boolean.FALSE); // a Save As target never read: a plain save, whose refusal is reported as before

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
        if (com.editora.Os.isMac()) {
            adminToolAvailable = true; // osascript is always present on macOS
            pushAdminEditAvailable();
            return;
        }
        Thread.ofPlatform().daemon().name("pkexec-detect").start(() -> {
            boolean ok;
            try {
                ok = elevationProcess
                                .run(
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
        });
    }

    /** Pushes the current "Edit as Administrator" availability to every open buffer's banner. */
    void pushAdminEditAvailable() {
        boolean available = elevationAvailable();
        if (available) {
            // whether each open file's save needs the administrator, looked at again off the FX thread for the
            // next Save (PERF-01, Pauta 6.2.0): its permissions may have changed since it was read
            List<Path> open = new ArrayList<>();
            for (Tab t : host.editorArea().tabs()) {
                EditorBuffer b = host.bufferOf(t);
                Path p = b == null ? null : b.getPath();
                if (p != null && com.editora.vfs.Vfs.isLocal(p)) {
                    open.add(p);
                }
            }
            if (!open.isEmpty()) {
                Thread.ofVirtual().name("admin-save-look").start(() -> open.forEach(NEEDS_ELEVATION::lookAgain));
            }
        }
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
    boolean refuseUnsavable(@Nullable EditorBuffer buffer) {
        SaveRefusal refusal = saveRefusal(buffer);
        if (refusal == SaveRefusal.NONE) {
            return false;
        }
        String key = refusal.messageKey();
        if (key != null && buffer != null) { // a null buffer is DISPOSED, which has no message
            host.setStatus(tr(key, buffer.getTitle()));
        }
        return true;
    }

    /**
     * Why {@code buffer} must not be written right now. Evaluated again in {@link #captureSave}, the one point
     * every write path passes through, so a caller that forgot to ask (autosave, the elevated save, the MCP
     * bridge) still cannot put an incomplete document on disk.
     */
    SaveRefusal saveRefusal(@Nullable EditorBuffer buffer) {
        return buffer == null
                ? SaveRefusal.DISPOSED
                : SaveRefusal.of(
                        buffer.isDisposed(),
                        buffer.isLoading() || loadingBuffers.contains(buffer),
                        buffer.isTruncatedLoad(),
                        buffer.isLogTrimmed(),
                        buffer.encryption().isLocked());
    }

    boolean save(EditorBuffer buffer) {
        if (refuseUnsavable(buffer)) {
            return false;
        }
        MarkdownLintActions lint = host.previews().lint();
        if (lint.fixesOnSave(buffer)) {
            // "Fix Markdown lint issues on save", one undo step: worked out off the FX thread (PERF-01, Pauta
            // 6.2.0), applied while the text is unchanged, then saved.
            lint.fixThenSave(buffer, () -> {
                if (!buffer.isDisposed() && !refuseUnsavable(buffer)) {
                    saveFixed(buffer);
                }
            });
            return true;
        }
        return saveFixed(buffer);
    }

    /** {@link #save} once the fixes on save, if any, are in the buffer. */
    private boolean saveFixed(EditorBuffer buffer) {
        if (buffer.getPath() == null) {
            return saveAs(buffer);
        }
        if (!encryption.beforeSave(buffer, buffer.getPath(), () -> save(buffer))) {
            return false; // a new encrypted file: its recipients are chosen first, then this save runs again
        }
        host.editorSettings().applyPendingView(buffer); // a hidden tab's view settings (TOC levels) before saving
        if (buffer.isMarkdown() && host.config().getSettings().isMarkdownTocUpdateOnSave()) {
            // An explicit Save only (autosave never edits the buffer). The headings are parsed off the FX thread
            // (PERF-01, Pauta 6.2.0); the write follows once the regenerated block is in the buffer.
            buffer.markdown().updateTocForSave(() -> {
                if (!buffer.isDisposed() && !refuseUnsavable(buffer) && buffer.getPath() != null) {
                    writeFixed(buffer);
                }
            });
            return true;
        }
        return writeFixed(buffer);
    }

    /** The write of {@link #saveFixed}, the table of contents regenerated. */
    private boolean writeFixed(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return false;
        }
        if (adminSaveApplicable(file)) {
            saveAsAdmin(buffer); // async elevated write; the buffer stays dirty until it completes
            return true;
        }
        return writeBuffer(buffer, file);
    }

    /**
     * {@code file.saveAll}: saves every modified file in this window — every editor group — as File: Save
     * would, then says how many were written. An untitled buffer is skipped (Save All asks for no names);
     * a tab that is not a text buffer (a diff's Result pane) keeps its own Save.
     */
    void saveAll() {
        List<CompletableFuture<Boolean>> writes = new ArrayList<>();
        int untitled = 0;
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer == null || !buffer.isDirty() || !buffer.isEditable()) {
                continue;
            }
            if (buffer.getPath() == null) {
                untitled++;
                continue;
            }
            CompletableFuture<Boolean> write = saveTracked(buffer);
            if (write != null) {
                writes.add(write);
            }
        }
        if (writes.isEmpty() && untitled == 0) {
            host.setStatus(tr("status.saveAll.none"));
            return;
        }
        int skipped = untitled;
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenRunAsync(
                        () -> {
                            long saved = writes.stream()
                                    .filter(w -> Boolean.TRUE.equals(w.getNow(false)))
                                    .count();
                            long notSaved = writes.size() - saved;
                            host.setStatus(
                                    notSaved == 0 && skipped == 0
                                            ? tr("status.saveAll.done", String.valueOf(saved))
                                            : tr(
                                                    "status.saveAll.partial",
                                                    String.valueOf(saved),
                                                    String.valueOf(notSaved),
                                                    String.valueOf(skipped)));
                        },
                        Platform::runLater);
    }

    /**
     * An explicit save of a file-backed buffer whose outcome can be awaited: completes on the FX thread with
     * whether the bytes reached the disk. Null for an elevated (administrator) save, which reports itself.
     */
    @Nullable
    CompletableFuture<Boolean> saveTracked(EditorBuffer buffer) {
        if (refuseUnsavable(buffer)) {
            return CompletableFuture.completedFuture(false);
        }
        MarkdownLintActions lint = host.previews().lint();
        if (lint.fixesOnSave(buffer)) {
            // The fixes on save are worked out off the FX thread first (PERF-01, Pauta 6.2.0), then the save runs.
            CompletableFuture<Boolean> saved = new CompletableFuture<>();
            lint.fixThenSave(buffer, () -> {
                if (buffer.isDisposed() || refuseUnsavable(buffer)) {
                    saved.complete(false); // closed meanwhile, or no longer savable
                    return;
                }
                CompletableFuture<Boolean> write = saveTrackedFixed(buffer);
                if (write == null) {
                    saved.complete(true); // an elevated save, which reports itself
                } else {
                    write.whenComplete((ok, failure) -> saved.complete(Boolean.TRUE.equals(ok)));
                }
            });
            return saved;
        }
        return saveTrackedFixed(buffer);
    }

    /** {@link #saveTracked} once the fixes on save, if any, are in the buffer. */
    @Nullable
    private CompletableFuture<Boolean> saveTrackedFixed(EditorBuffer buffer) {
        host.editorSettings().applyPendingView(buffer); // a hidden tab's view settings (TOC levels) before saving
        if (buffer.isMarkdown() && host.config().getSettings().isMarkdownTocUpdateOnSave()) {
            // The table of contents is regenerated off the FX thread (PERF-01, Pauta 6.2.0), then the write runs.
            CompletableFuture<Boolean> saved = new CompletableFuture<>();
            buffer.markdown().updateTocForSave(() -> {
                if (buffer.isDisposed() || refuseUnsavable(buffer)) {
                    saved.complete(false);
                    return;
                }
                CompletableFuture<Boolean> write = writeTracked(buffer);
                if (write == null) {
                    saved.complete(true); // an elevated save, which reports itself
                } else {
                    write.whenComplete((ok, failure) -> saved.complete(Boolean.TRUE.equals(ok)));
                }
            });
            return saved;
        }
        return writeTracked(buffer);
    }

    /** The write of {@link #saveTrackedFixed}, the table of contents regenerated. */
    @Nullable
    private CompletableFuture<Boolean> writeTracked(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (adminSaveApplicable(file)) {
            saveAsAdmin(buffer);
            return null;
        }
        SaveRequest request = file == null ? null : captureSave(buffer, file, false);
        if (request == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        try {
            saveExecutor(file).submit(() -> writeAsync(request, false, completed));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
            completed.complete(false);
        }
        return completed;
    }

    /** Asked before a revert discards unsaved changes (Local History keeps them). Replaced by tests. */
    volatile java.util.function.Predicate<EditorBuffer> confirmRevert = this::confirmRevertDialog;

    /**
     * {@code file.revert}: replaces the active buffer's text with its file on disk — after asking, when that
     * discards unsaved changes. The caret, scroll position and folds stay where they were, and the file is
     * read in the encoding chosen for it, if any.
     */
    void revert() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        Path file = buffer.getPath();
        if (file == null) {
            host.setStatus(tr("status.revert.untitled", buffer.getTitle()));
            return;
        }
        if (buffer.isLoading() || loadingBuffers.contains(buffer)) {
            return; // what is on disk is what is being read already
        }
        if (!com.editora.vfs.Vfs.isLocal(file)) {
            revertLooked(buffer, file, true);
            return;
        }
        // whether the file is still there is looked at off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then(
                "revert-look", () -> Files.exists(file), there -> revertLooked(buffer, file, there.or(false)));
    }

    private void revertLooked(EditorBuffer buffer, Path file, boolean exists) {
        if (buffer.isDisposed() || !file.equals(buffer.getPath()) || loadingBuffers.contains(buffer)) {
            return; // closed, renamed or being read meanwhile
        }
        if (!exists) {
            host.setStatus(tr("status.revert.missing", buffer.getTitle()));
            return;
        }
        if (buffer.isDirty() && !confirmRevert.test(buffer)) {
            return;
        }
        Tab tab = host.tabFor(buffer);
        reloadFromDisk(tab, buffer, HistoryRevision.REASON_REVERT, applied -> {
            if (applied) {
                host.setStatus(tr("status.revert.done", buffer.getTitle()));
            }
        });
    }

    private boolean confirmRevertDialog(EditorBuffer buffer) {
        return Confirm.ask(host.stage(), revertQuestion(buffer.getTitle()));
    }

    /** The words of the question before a revert discards the unsaved changes of the tab titled {@code title}. */
    static Confirm.Question revertQuestion(String title) {
        return new Confirm.Question(
                tr("command.file.revert"),
                tr("dialog.revert.header", title),
                tr("dialog.revert.content"),
                tr("confirm.action.revert"));
    }

    /** The words of the question before a Save As or an export replaces the existing file {@code fileName}. */
    static Confirm.Question overwriteQuestion(String fileName) {
        return new Confirm.Question(
                tr("dialog.saveAs.overwrite.title"),
                tr("dialog.saveAs.overwrite.header", fileName),
                tr("dialog.saveAs.overwrite.content"),
                tr("confirm.action.overwrite"));
    }

    /**
     * Starts a save of a file-backed buffer and reports whether the bytes reached the disk — for a caller on
     * another thread (the MCP bridge) that must not answer "saved" for a write that was only queued. Never
     * opens Save As or an elevation prompt: those complete with {@code false}. FX thread; the future completes
     * on the FX thread.
     */
    CompletableFuture<Boolean> saveReportingOutcome(EditorBuffer buffer) {
        Path file = buffer == null ? null : buffer.getPath();
        SaveRequest request = file == null || adminSaveApplicable(file) ? null : captureSave(buffer, file, false);
        if (request == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        try {
            saveExecutor(file).submit(() -> writeAsync(request, false, completed));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
            completed.complete(false);
        }
        return completed;
    }

    /**
     * Saves {@code buffer} as {@link #saveSynchronously} does — a write, or Save As for an untitled one — without
     * waiting: the write runs on the save worker and {@code then} hears on the FX thread whether the bytes are on
     * disk. A command that must run on the saved file goes on there, never in a nested event loop (PERF-01, Pauta
     * 6.2.0).
     */
    void saveThen(EditorBuffer buffer, java.util.function.Consumer<Boolean> then) {
        if (refuseUnsavable(buffer)) {
            then.accept(false);
            return;
        }
        Path file = buffer.getPath();
        if (file == null) {
            Path target = saveAsTargetChooser.apply(buffer);
            if (target == null) {
                then.accept(false);
                return;
            }
            applySaveAsTarget(buffer, target, then);
            return;
        }
        if (adminSaveApplicable(file)) {
            saveAsAdmin(buffer);
            then.accept(false);
            return;
        }
        writeBufferThen(buffer, file, false, then);
    }

    /** Captures and queues the write, then {@code then} with its outcome on the FX thread. */
    private void writeBufferThen(
            EditorBuffer buffer, Path file, boolean saveAs, java.util.function.Consumer<Boolean> then) {
        SaveRequest request = captureSave(buffer, file, saveAs);
        if (request == null) {
            then.accept(false);
            return;
        }
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        completed.whenComplete(
                (ok, error) -> Platform.runLater(() -> then.accept(error == null && Boolean.TRUE.equals(ok))));
        try {
            saveExecutor(file).submit(() -> writeAsync(request, false, completed));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
            completed.complete(false);
        }
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
     * permissions are preserved — after the same script has copied the target aside, so a copy that fails
     * part-way can be undone (see {@link com.editora.process.ElevatedSave#SCRIPT}). Runs off the FX thread (the auth dialog blocks); the result is applied back
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
        Thread.ofPlatform().daemon().name("admin-save").start(() -> {
            try {
                var outcome = request.ticket().runIfCurrent(() -> {
                    reresolveRules(request); // the payload, worked out here (off the FX thread)
                    if (request.seal() != null) {
                        request.seal().seal(request.bytes());
                    }
                    // The same preimage check as an ordinary save: the elevated copy truncates
                    // in place, so an external edit would otherwise be overwritten unasked.
                    RemoteWritePlan plan = Files.exists(target)
                            ? prepareRemoteWrite(request, false, false, null)
                            : new RemoteWritePlan(true, null, true);
                    if (!plan.proceed()) {
                        return new AdminResult(-2, "conflict", -1, -1);
                    }
                    if (!mayNormaliseBinary(request, true)) {
                        return new AdminResult(-2, "refused", -1, -1);
                    }
                    keepMixedOriginal(request);
                    AdminResult wrote = elevatedWriter.write(request.target(), request.diskBytes());
                    if (wrote.exit() == 0) {
                        // As an ordinary save: what it replaced is kept when it was not this window's own (R10).
                        host.historyCoordinator().saveReplaced(target, plan.expectedBytes(), request.diskBytes());
                    }
                    return wrote;
                });
                AdminResult written = outcome.value();
                AdminResult result =
                        outcome.executed() && written != null ? written : new AdminResult(-2, "superseded", -1, -1);
                if (result.exit() == 0) {
                    publishCommit(request, new DiskWrite(result.modifiedMillis(), result.size()));
                }
                Platform.runLater(() -> onAdminSaveDone(request, result));
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage();
                AdminResult result = new AdminResult(-1, message == null ? "" : message, -1, -1);
                Platform.runLater(() -> onAdminSaveDone(request, result));
            }
        });
    }

    /**
     * How a save wrote its file — the charset (a byte-order mark names its own), whether it has a mark, and the
     * line ending — kept on its Local History row so a file deleted outside Editora comes back as those bytes
     * (R14).
     */
    private static HistoryCoordinator.Encoding savedEncoding(SaveRequest request) {
        byte[] written = request.bytes();
        String byBom = com.editora.editorconfig.EditorConfigCharset.detectByBom(written);
        String charset = byBom != null ? byBom : request.buffer().getEffectiveCharset();
        return new HistoryCoordinator.Encoding(
                charset, byBom != null, com.editora.editops.LineEndings.dominant(request.savedText()));
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
            List<String> argv = com.editora.process.ElevatedSave.elevatedArgv(
                    com.editora.Os.current(), com.editora.process.ElevatedSave.PKEXEC, tmp, target);
            if (argv == null) { // elevationAvailable() is false on such a system, so no save gets here
                throw new IllegalStateException("no elevated save on " + com.editora.Os.name());
            }
            var result = elevationProcess.run(java.time.Duration.ofMinutes(2), argv);
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
                encryption.saved(buffer, target, request.seal());
                host.historyCoordinator()
                        .recordSaved(target, request.content(), HistoryRevision.REASON_SAVE, savedEncoding(request));
                acknowledgeLatestCommit(buffer);
                settleSaveNotes(request);
                if (request.ticket().isCurrent() && !buffer.isDisposed()) {
                    host.setStatus(request.notes()
                            .appendTo(tr("status.admin.saved", com.editora.config.PathDisplay.of(target))));
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
                    com.editora.Os.current(), result.exit(), result.error())) {
                host.setStatus(tr("status.admin.cancelled"));
            } else {
                host.setStatus(adminFailureStatus(target, result));
            }
        } finally {
            finishRequest(request);
        }
    }

    /** What a failed elevated save says: above all, where the file's previous bytes are now. */
    static String adminFailureStatus(Path target, AdminResult result) {
        String reason = com.editora.process.ElevatedSave.reason(result.error());
        String why = reason.isBlank() ? String.valueOf(result.exit()) : reason;
        String file = com.editora.config.PathDisplay.of(target);
        String backup = com.editora.config.PathDisplay.of(com.editora.process.ElevatedSave.backupOf(target));
        return switch (com.editora.process.ElevatedSave.failureOf(result.exit(), result.error())) {
            case RESTORED -> tr("status.admin.failedRestored", file, why);
            case BACKUP_KEPT -> tr("status.admin.failedBackupKept", file, backup, why);
            case NO_BACKUP -> tr("status.admin.failedNoBackup", file, why);
            case STALE_BACKUP -> tr("status.admin.failedStaleBackup", file, backup);
            case OTHER -> tr("status.admin.failed", why);
        };
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
        Path file = saveAsTargetChooser.apply(buffer);
        if (file == null) {
            return false;
        }
        return applySaveAsTarget(buffer, file, synchronous);
    }

    private @Nullable Path chooseSaveAsTarget(EditorBuffer buffer) {
        // A remote buffer may be saved as a LOCAL file (the chooser only offers those): with its connection
        // gone that is the one way left to keep the text. A new remote destination is still not offered.
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.saveAs.title"));
        if (buffer.getDisplayName() != null) {
            chooser.setInitialFileName(buffer.getDisplayName()); // suggested name from --new-file=NAME
        } else {
            Path current = buffer.getPath();
            if (current != null && com.editora.vfs.Vfs.isRemote(current)) {
                chooser.setInitialFileName(String.valueOf(current.getFileName()));
            }
        }
        return host.pathOf(chooser.showSaveDialog(host.stage()));
    }

    /** Points {@code buffer} at {@code file}, refreshes its previews/tab/breadcrumb, and writes it. */
    boolean applySaveAsTarget(EditorBuffer buffer, Path file) {
        return applySaveAsTarget(buffer, file, false);
    }

    private boolean applySaveAsTarget(EditorBuffer buffer, Path file, boolean synchronous) {
        return applySaveAsTarget(buffer, file, synchronous, null);
    }

    /** As above, the write's outcome handed to {@code then} on the FX thread (no wait at all). */
    private void applySaveAsTarget(EditorBuffer buffer, Path file, java.util.function.Consumer<Boolean> then) {
        if (!applySaveAsTarget(buffer, file, false, then)) {
            then.accept(false);
        }
    }

    /**
     * Re-points {@code buffer} at {@code file} and writes it: in a nested wait when {@code synchronous}, or with its
     * outcome handed to {@code then} when one is given (the method then answers whether the write was queued).
     */
    private boolean applySaveAsTarget(
            EditorBuffer buffer, Path file, boolean synchronous, java.util.function.@Nullable Consumer<Boolean> then) {
        if (refuseUnsavable(buffer) || refuseTargetOpenElsewhere(buffer, file)) {
            return false; // before the buffer is re-pointed: a refused write must not leave it renamed
        }
        if (!encryption.beforeSaveAs(buffer, file, () -> {
            if (then != null) {
                applySaveAsTarget(buffer, file, then);
            } else {
                applySaveAsTarget(buffer, file, synchronous);
            }
        })) {
            return then != null; // asked first: leaving encryption, or choosing who a new encrypted file is for
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
        host.htmlPreview().pathChanged(buffer, previousPath); // old name: no editor text served (E201); new: the globe
        host.logViewer().ensureControl(buffer); // a save-as to .log now gets the log control + level overlay
        boolean ok;
        if (then != null) {
            writeBufferThen(buffer, file, true, then);
            ok = true;
        } else {
            ok = synchronous ? writeBufferSynchronously(buffer, file, true) : writeBuffer(buffer, file, true);
        }
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
    void saveAsPrompt(@Nullable EditorBuffer buffer) {
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
            // whether the typed name is taken is looked at off the FX thread (PERF-01, Pauta 6.2.0)
            com.editora.perf.OffFx.then("save-as-look", () -> Files.exists(target), taken -> {
                if (buffer.isDisposed() || refuseTargetOpenElsewhere(buffer, target)) {
                    return;
                }
                Path current = buffer.getPath();
                boolean overwritingOther = taken.or(false)
                        && (current == null || !com.editora.config.PathKeys.sameNormalized(target, current));
                if (overwritingOther && !confirmOverwrite(target)) {
                    return;
                }
                applySaveAsTarget(buffer, target);
            });
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
        Path root = host.projectRoot();
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
        return Confirm.ask(host.stage(), overwriteQuestion(String.valueOf(target.getFileName())));
    }

    /**
     * The exact bytes to write for {@code buffer}: the EditorConfig save transforms (trim trailing
     * whitespace / final newline / end-of-line) applied to the text, then encoded in the effective charset
     * (BOM-aware). The live document is not mutated — only what we write. With EditorConfig off this is the
     * content in the file's own line ending and detected charset.
     */
    byte @Nullable [] saveBytes(EditorBuffer buffer) {
        return savePayload(buffer, buffer.getContent(), buffer.getPath())
                .encoding()
                .bytes();
    }

    private SavePayload savePayload(EditorBuffer buffer, String content, @Nullable Path target) {
        SaveInputs in = saveInputs(buffer, content, target, false);
        return payloadOf(
                in, in.rulesUsed(), buffer.getLineEnding(), buffer.isLineEndingForced(), buffer.getEffectiveCharset());
    }

    /** The buffer's facts a save's payload is worked out from, taken on the FX thread. */
    private SaveInputs saveInputs(EditorBuffer buffer, String content, @Nullable Path target, boolean resolveOnDisk) {
        return new SaveInputs(
                content,
                MixedLineEndings.of(buffer),
                target,
                String.valueOf(buffer.getLanguage()),
                host.lspCoordinator().projectScope(),
                host.editorSettings().editorConfigEnabled()
                        ? buffer.getEditorConfigProps()
                        : com.editora.editorconfig.EditorConfigProperties.EMPTY,
                buffer.getBaseLineEnding(),
                buffer.getUserCharset(),
                buffer.getDetectedCharset(),
                buffer.isCharsetAssumed(),
                bomlessUtf16.contains(buffer),
                buffer.getLineEnding(),
                buffer.isLineEndingForced(),
                buffer.getEffectiveCharset(),
                resolveOnDisk);
    }

    /**
     * What a save writes: the text transformed by {@code rules} and encoded. Pure (no file looked at): the FX thread
     * works it out with the rules the buffer holds, and the save worker again with the rules on disk when they
     * changed ({@link #reresolveRules}).
     */
    private static SavePayload payloadOf(
            SaveInputs in,
            com.editora.editorconfig.EditorConfigProperties rules,
            String lineEnding,
            boolean lineEndingForced,
            String charset) {
        String content = in.content();
        MixedLineEndings.Source mixedSource = in.mixedSource();
        MixedLineEndings.Decision mixed =
                MixedLineEndings.decide(mixedSource, content, lineEnding, lineEndingForced, in.target());
        if (mixedSource != null && mixed == MixedLineEndings.Decision.KEEP_BYTES) {
            // Unedited, and the document cannot reproduce the file's terminators: its own bytes go back.
            return new SavePayload(
                    content, new SaveEncoding.Plan(mixedSource.bytes(), null, null), SaveNotes.keptBytes(mixedSource));
        }
        com.editora.editorconfig.EditorConfigProperties p =
                in.scope().saveRules(rules); // the project's trim/final newline under .editorconfig
        String text = com.editora.editorconfig.EditorConfigTransform.transform(content, p);
        if (com.editora.editops.LineEndings.labelOf(p.endOfLine()) == null) {
            // No end_of_line rule: write the file's own line ending back. The document only ever holds '\n',
            // so without this every CRLF file was rewritten as LF by its first save.
            text = com.editora.editops.LineEndings.apply(text, lineEnding);
        }
        // A charset that can't represent what the user typed (an em dash / curly quote / emoji under
        // `charset = latin1`) would be written as '?' by String.getBytes — and the editor keeps showing the
        // real character until the file is reopened, so the corruption is invisible until it's permanent.
        // SaveEncoding decides what is written instead, and refuses when nothing safe can be.
        boolean bom = !(isUtf16(charset) && in.bomless());
        boolean chosen = in.userCharset() != null;
        SaveEncoding.Plan plan = SaveEncoding.plan(text, charset, in.charsetAssumed() || chosen, bom);
        SaveNotes notes = SaveNotes.of(
                mixed,
                mixedSource,
                lineEnding,
                content,
                text,
                p,
                in.detectedCharset(),
                charset,
                !in.charsetAssumed() && plan.fallbackFrom() == null,
                chosen);
        return new SavePayload(text, plan, notes);
    }

    /**
     * On the save worker, before the bytes are sealed and written: looks at the file's {@code .editorconfig} rules
     * as they are on disk now and, when they differ from the ones the FX thread captured the save with, works the
     * payload out again by them (PERF-01, Pauta 6.2.0). A charset that cannot hold the text refuses the save.
     */
    private static void reresolveRules(SaveRequest request) throws IOException {
        SaveInputs in = request.rules().inputs;
        if (in == null || request.rules().again != null) {
            return;
        }
        // The payload (the save transform and the encoding of the whole text) is worked out here, on the save
        // worker, never on the FX thread (PERF-01, Pauta 6.2.0).
        SavePayload again;
        com.editora.editorconfig.EditorConfigProperties found = in.rulesUsed();
        if (in.resolveOnDisk()) {
            com.editora.perf.BlockingSites.enter("FileWorkflowCoordinator.reresolveRules");
            found = com.editora.text.ShellScripts.honoured(
                    in.language(), com.editora.editorconfig.EditorConfig.resolveFor(request.target()));
            request.rules().found = found;
        }
        String charset;
        if (found.equals(in.rulesUsed())) {
            charset = in.effectiveCharset();
            again = payloadOf(in, found, in.lineEnding(), in.lineEndingForced(), charset);
        } else {
            String eol = com.editora.editops.LineEndings.labelOf(found.endOfLine());
            String lineEnding = eol != null ? eol : in.baseLineEnding();
            charset = in.userCharset() != null
                    ? in.userCharset()
                    : found.charset() != null && !in.charsetAssumed() ? found.charset() : in.detectedCharset();
            again = payloadOf(in, found, lineEnding, eol != null, charset);
        }
        if (again.encoding().bytes() == null) {
            throw new Unencodable(again.encoding().refused(), charset);
        }
        request.rules().again = again;
    }

    static String fingerprint(byte[] bytes) {
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
        if (unsavedOnlyBecauseMissing(buffer)) {
            return; // held open because its file was deleted: only an explicit save may bring the file back
        }
        Path file = buffer.getPath();
        SaveRequest request = file == null ? null : captureSave(buffer, file);
        if (request == null) {
            return;
        }
        try {
            saveExecutor(file).submit(() -> writeAsync(request, true));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            finishRequest(request);
        }
    }

    private @Nullable SaveRequest captureSave(EditorBuffer buffer, Path file) {
        return captureSave(buffer, file, false);
    }

    /** Why {@code buffer} cannot be saved in {@code charset}: the first character it cannot hold, and what to do. */
    private static String cannotEncodeStatus(
            EditorBuffer buffer, SaveEncoding.@Nullable Unencodable first, String charset) {
        return first != null && first.unpairedSurrogate()
                ? tr("status.save.cannotEncodeSurrogate", buffer.getTitle(), first.line(), first.display())
                : buffer.getUserCharset() != null && !buffer.isCharsetAssumed()
                        ? tr(
                                "status.save.cannotEncodeChosen",
                                buffer.getTitle(),
                                com.editora.editorconfig.EditorConfigCharset.displayName(charset),
                                first == null ? "?" : first.character(),
                                first == null ? "?" : String.valueOf(first.line()),
                                tr("command.buffer.saveWithEncoding"))
                        : tr(
                                "status.save.cannotEncodeAssumed",
                                buffer.getTitle(),
                                com.editora.editorconfig.EditorConfigCharset.displayName(charset),
                                first == null ? "?" : first.character(),
                                first == null ? "?" : String.valueOf(first.line()));
    }

    /**
     * Snapshots {@code buffer} for a write, or returns {@code null} (with a status message) when it must not be
     * written — see {@link #saveRefusal}. The single choke point for Save, Save As, autosave, the elevated
     * save and every programmatic caller.
     */
    private @Nullable SaveRequest captureSave(EditorBuffer buffer, Path file, boolean saveAs) {
        if (refuseUnsavable(buffer)) {
            return null;
        }
        String content = buffer.getContent();
        // The rules were resolved when the file was opened; an .editorconfig edited or pulled since then must govern
        // this write, not the next session's: the save worker looks at them again (reresolveRules). Save As has just
        // resolved its target's. The payload itself is worked out there too (a charset that cannot hold the text
        // refuses the save from there, with the same message).
        SaveInputs inputs = saveInputs(
                buffer,
                content,
                file,
                !saveAs && host.editorSettings().editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file));
        SaveRules rules = new SaveRules(inputs);
        SavePayload payload = new SavePayload(content, new SaveEncoding.Plan(null, null, null), SaveNotes.NONE);
        EncryptionCoordinator.Seal seal = encryption.sealFor(buffer, file);
        if (seal == EncryptionCoordinator.Seal.REFUSED) {
            return null; // an encrypted file that cannot be encrypted right now: never written in plain text
        }
        pendingSaves.merge(buffer, 1, Integer::sum);
        SaveRequest request = new SaveRequest(
                buffer,
                file,
                content,
                payload.text(),
                new byte[0], // worked out on the save worker (reresolveRules)
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
                payload.encoding().fallbackFrom(),
                payload.notes(),
                seal,
                rules);
        activeSaveRequests.add(request);
        return request;
    }

    private @Nullable DiskWrite writeToDisk(SaveRequest request, RemoteWritePlan plan) throws IOException {
        Runnable hook = beforeDocumentWriteForTest;
        if (hook != null) {
            hook.run();
        }
        BooleanSupplier commit = () -> request.ticket().isCurrent()
                && (plan.expectedAbsent()
                        ? Files.notExists(request.target())
                        : plan.expectedBytes() == null || diskBytesEqual(request.target(), plan.expectedBytes()));
        keepMixedOriginal(request);
        AtomicFileWrite.Outcome written = documentWriter.writeDocument(request.target(), request.diskBytes(), commit);
        if (written == AtomicFileWrite.Outcome.SKIPPED) {
            return null;
        }
        return new DiskWrite(
                lastModifiedMillis(request.target()),
                fileSize(request.target()),
                written == AtomicFileWrite.Outcome.IN_PLACE);
    }

    /**
     * Before a save rewrites a mixed file's line terminators, its loaded bytes are copied into the config
     * folder: local history records text, which is already normalised. No copy, no rewrite.
     */
    private void keepMixedOriginal(SaveRequest request) throws IOException {
        if (request.notes().normalisesMixed()) {
            MixedLineEndings.keepOriginal(
                    host.config().getConfigDir().resolve(MixedLineEndings.ORIGINALS_FOLDER),
                    request.notes().source());
        }
    }

    /**
     * Asks before an edit to a file that mixes line endings <em>and</em> holds binary data is saved:
     * writing one terminator throughout changes the binary bytes. Replaced by tests; FX thread.
     *
     * <p>Arguments: the file, and the line ending that would be written on every line.
     */
    volatile java.util.function.BiPredicate<Path, String> mixedBinaryConsent = this::askMixedBinaryConsent;

    /** Buffers the user agreed to normalise although they hold binary data. FX-thread only. */
    private final Set<EditorBuffer> mixedBinaryAgreed = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * The words of the question before a save rewrites every line break of {@code fileName}, a file that mixes line
     * endings and holds binary data, as {@code lineEnding}.
     */
    static Confirm.Question mixedBinaryQuestion(String fileName, String lineEnding) {
        return new Confirm.Question(
                tr("dialog.saveMixedBinary.title"),
                tr("dialog.saveMixedBinary.header", fileName),
                tr("dialog.saveMixedBinary.content", lineEnding),
                tr("dialog.saveMixedBinary.save"));
    }

    /** The words of the question before a save replaces {@code fileName}, a file that is read-only on disk. */
    static Confirm.Question replaceReadOnlyQuestion(String fileName) {
        return new Confirm.Question(
                tr("dialog.saveReadOnly.title"),
                tr("dialog.saveReadOnly.header", fileName),
                tr("dialog.saveReadOnly.content"),
                tr("dialog.saveReadOnly.overwrite"));
    }

    private boolean askMixedBinaryConsent(Path target, String lineEnding) {
        Confirm.Question words = mixedBinaryQuestion(String.valueOf(target.getFileName()), lineEnding);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.WARNING));
        alert.initOwner(host.stage());
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType rewrite = new ButtonType(words.action(), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(cancel, rewrite);
        return Dialogs.showAndWait(alert).orElse(cancel) == rewrite;
    }

    /**
     * Whether {@code request} may rewrite the terminators of a mixed file that holds binary data: an explicit
     * save asks once per buffer; a background save never asks and never writes, it says why.
     */
    private boolean mayNormaliseBinary(SaveRequest request, boolean mayAsk) throws IOException {
        if (!request.notes().needsConsent()) {
            return true;
        }
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Runnable decide = () -> {
            try {
                EditorBuffer buffer = request.buffer();
                if (buffer.isDisposed() || !request.ticket().isCurrent()) {
                    answer.complete(false);
                } else if (mixedBinaryAgreed.contains(buffer)) {
                    answer.complete(true);
                } else if (mayAsk && mixedBinaryConsent.test(request.target(), request.lineEnding())) {
                    mixedBinaryAgreed.add(buffer);
                    answer.complete(true);
                } else {
                    host.setStatus(tr(
                            "status.save.cannotSaveMixedBinary",
                            request.target().getFileName(),
                            request.lineEnding(),
                            tr("command.file.saveAs")));
                    answer.complete(false);
                }
            } catch (Throwable failure) {
                answer.completeExceptionally(failure);
            }
        };
        if (Platform.isFxApplicationThread()) {
            decide.run();
        } else {
            Platform.runLater(decide);
        }
        try {
            return answer.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IOException(String.valueOf(failed.getCause()), failed.getCause());
        }
    }

    private static boolean diskBytesEqual(@Nullable Path target, byte[] expected) {
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
            SaveRequest request,
            boolean autoSave,
            boolean raced,
            java.util.concurrent.atomic.@Nullable AtomicBoolean blocked)
            throws IOException {
        PreparedLoad current = prepareLoad(request.target(), false, false);
        byte[] currentBytes = current.sourceBytes();
        CommittedSave ownCommit = committedSaves.get(com.editora.config.PathKeys.key(request.target()));
        // A preceding request may be acknowledged and retired after this request captured its old
        // disk snapshot. Preserve that relationship across FX callbacks instead of consulting live requests.
        CommittedSave followed = ownCommit != null
                        && ownCommit.sequence() < request.sequence()
                        && request.precedingSaveSequences().contains(ownCommit.sequence())
                ? ownCommit
                : null;
        boolean changed = raced
                || (!request.saveAs()
                        && (followed != null
                                ? !followed.fingerprint().equals(current.fingerprint())
                                : request.diskSnapshot()
                                        .differsFrom(
                                                current.mtime(),
                                                current.size(),
                                                currentBytes == null ? null : fingerprint(currentBytes))));
        if (!changed) {
            return new RemoteWritePlan(true, currentBytes, false);
        }
        if (autoSave) {
            if (blocked != null) {
                blocked.set(true); // never prompt from a background save, but do not go quiet either
            }
            return new RemoteWritePlan(false, currentBytes, false);
        }
        // Its line endings resolved here, on the save worker: a Reload installs it without a scan on the FX thread.
        RemoteSaveChoice choice = promptRemoteSaveConflict(request, current.normalised());
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
                Confirm.Choice words = ExternalChangePrompt.saveConflictQuestion(
                        String.valueOf(request.target().getFileName()));
                Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
                alert.initOwner(host.stage());
                alert.setTitle(words.title());
                alert.setHeaderText(words.header());
                alert.setContentText(words.message());
                ButtonType reload = new ButtonType(words.answer(0), ButtonBar.ButtonData.LEFT);
                ButtonType overwrite = new ButtonType(words.answer(1), ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = Dialogs.cancel();
                alert.getButtonTypes().setAll(reload, overwrite, cancel);
                // Reload discards the unsaved edits: red, and never the default. Saving is an option here, so
                // Keep Mine stays the default button, as Save does in the unsaved-changes question.
                Dialogs.destructive(alert, reload);
                Dialogs.defaultTo(alert, overwrite);
                ButtonType selected = Dialogs.showAndWait(alert).orElse(cancel);
                if (selected == reload) {
                    invalidatePendingWrite(request.target());
                    Tab tab = host.editorArea().tabs().stream()
                            .filter(candidate -> host.bufferOf(candidate) == buffer)
                            .findFirst()
                            .orElse(null);
                    if (tab != null) {
                        applyPreparedReload(tab, buffer, current, null); // the whole document, prepared off FX
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
                    Confirm.Question words = replaceReadOnlyQuestion(String.valueOf(target.getFileName()));
                    Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
                    alert.initOwner(host.stage());
                    alert.setTitle(words.title());
                    alert.setHeaderText(words.header());
                    alert.setContentText(words.message());
                    ButtonType overwrite = new ButtonType(words.action(), ButtonBar.ButtonData.OK_DONE);
                    ButtonType cancel = Dialogs.cancel();
                    alert.getButtonTypes().setAll(overwrite, cancel);
                    boolean agreed = Dialogs.showAndWait(alert).orElse(cancel) == overwrite;
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
    void invalidatePendingWrite(@Nullable Path file) {
        if (file != null) {
            host.config().shared().documentWrites().supersede(file);
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

    private void writeAsync(SaveRequest request, boolean autoSave, @Nullable CompletableFuture<Boolean> completed) {
        java.util.concurrent.atomic.AtomicBoolean autoSaveBlocked = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var outcome = request.ticket().runIfCurrent(() -> {
                Path target = request.target();
                reresolveRules(request); // the .editorconfig as it is on disk now governs these bytes
                if (request.seal() != null) {
                    request.seal().seal(request.bytes()); // gpg, here on the save worker; fails closed
                }
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
                    if (attempt == 0 && !mayNormaliseBinary(request, !autoSave)) {
                        return null;
                    }
                    RemoteWritePlan plan = state == SaveTarget.PRESENT
                            ? prepareRemoteWrite(request, autoSave, attempt > 0, autoSaveBlocked)
                            : new RemoteWritePlan(true, null, true);
                    if (!plan.proceed()) {
                        return null;
                    }
                    DiskWrite disk = writeToDisk(request, plan);
                    if (disk != null) {
                        // Local History keeps what this save replaced when it was not our own earlier save.
                        host.historyCoordinator().saveReplaced(target, plan.expectedBytes(), request.diskBytes());
                    }
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
                        if (e instanceof EncryptionCoordinator.SealFailure failure) {
                            encryption.saveFailed(request.buffer(), failure); // the banner: why, and what to do
                        }
                        if (e instanceof Unencodable refused) {
                            host.setStatus(cannotEncodeStatus(request.buffer(), refused.first, refused.charset));
                        } else {
                            host.setStatus(tr(autoSave ? "status.autoSaveFailed" : "status.failedSave", why));
                        }
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
        encryption.saved(request.buffer(), request.target(), request.seal());
        host.historyCoordinator()
                .recordSaved(
                        request.target(),
                        request.content(),
                        autoSave ? HistoryRevision.REASON_AUTOSAVE : HistoryRevision.REASON_SAVE,
                        savedEncoding(request));
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
        com.editora.editorconfig.EditorConfigProperties found = request.rules().found;
        if (found != null && stillThisFile) {
            // The rules the save worker found on disk: the buffer (indent, line ending, charset shown) follows them.
            host.editorSettings().applyRefreshedEditorConfig(request.buffer(), found);
        }
        settleSaveNotes(request);
        if (".editorconfig".equals(String.valueOf(request.target().getFileName()))) {
            host.editorConfigSaved(); // its rules reach the files already open, in every window
        }
        host.editorSettings().spell().fileSaved(request.target()); // dictionary.txt edited by hand applies now
        ProjectPanel.noteLocalWrite(host.projectPanel(), request.target()); // ours: not an external change
        host.fileSaved(request.target());
        if (!request.buffer().isDisposed()) {
            host.htmlPreview().onBufferSaved(request.buffer()); // a saved file of a previewed site reloads it
        }
        if (showFeedback && !request.buffer().isDisposed()) {
            host.setStatus(savedStatus(request, disk, autoSave));
            if (!autoSave) {
                cleanUpPastedImages(request);
            }
            host.git().refreshSoon(); // coalesced: autosave on every typing pause need not run git status each time
            host.lspCoordinator().notifyDocumentSaved(request.buffer(), request.savedText(), autoSave);
            if (!autoSave) {
                host.refreshBuildTools();
                host.indexCoordinator().onBufferSaved(request.buffer());
                host.sourceSaved(request.buffer()); // after didSave: the build it may start reads the saved text
                host.lspCoordinator().afterUserSave(request.buffer()); // Java organize imports / format on save
            }
        }
    }

    /**
     * Brings the buffer's bookkeeping in line with what a completed save put on disk: a file whose mixed
     * line endings were rewritten is uniform now, one whose bytes were written back unchanged is still
     * mixed (at the saved path), and a re-encoded file is in its new charset — so that is said once.
     */
    private void settleSaveNotes(SaveRequest request) {
        EditorBuffer buffer = request.buffer();
        SaveNotes notes = request.notes();
        if (buffer.isDisposed()
                || buffer.getPath() == null
                || !com.editora.config.PathKeys.sameNormalized(buffer.getPath(), request.target())) {
            return;
        }
        if (notes.keepsBytes()) {
            MixedLineEndings.movedTo(buffer, request.target());
        } else if (notes.normalisesMixed() && MixedLineEndings.of(buffer) == notes.source()) {
            MixedLineEndings.forget(buffer);
            mixedBinaryAgreed.remove(buffer);
            host.editorSettings().refreshStatusBarFor(buffer);
        }
        String charsetTo = notes.charsetTo();
        if (charsetTo != null) { // notes.charsetChanged()
            buffer.charsetSaved(charsetTo);
        }
        if (buffer.getUserCharset() != null) {
            // The file is in the chosen encoding now; the next open must read it in that one too.
            rememberEncoding(request.target(), buffer.getUserCharset());
        }
    }

    /** The trash unused pasted images go to; a test puts its own in. Null: the system's. */
    com.editora.io.Trash.@Nullable Bin pastedImageBin;

    /** Test seam: runs on the background thread after the sibling scan, before the FX re-check. Null normally. */
    @Nullable
    Runnable beforePastedImageRecheck;

    /** Completes when the clean-up started by the latest save has settled (tests wait on it). */
    volatile CompletableFuture<Void> pastedImageCleanup = CompletableFuture.completedFuture(null);

    /** Another open Markdown or Typst document's text, taken on the FX thread for a check made off it. */
    private record OtherDoc(@Nullable Path path, String text) {}

    /** FX thread: the other open Markdown and Typst documents, as they are now. */
    private List<OtherDoc> otherDocs(EditorBuffer buffer) {
        List<OtherDoc> out = new java.util.ArrayList<>();
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer other = host.bufferOf(tab);
            if (other != null && other != buffer && (other.isMarkdown() || other.isTypst())) {
                out.add(new OtherDoc(other.getPath(), other.getContent()));
            }
        }
        return out;
    }

    /** Whether one of {@code others} mentions {@code file}: a search, made off the FX thread. */
    private static boolean mentionedIn(List<OtherDoc> others, Path file) {
        for (OtherDoc other : others) {
            if (PastedImageCleanup.mentions(other.path(), other.text(), file)) {
                return true;
            }
        }
        return false;
    }

    /** What the recheck takes on the FX thread: the document, its pasted images, the other documents. */
    private record Recheck(String text, List<Path> created, List<OtherDoc> others) {}

    /** {@link com.editora.perf.BlockingSites} name of the pasted-image check, made off the FX thread. */
    static final String PASTED_IMAGES_SITE = "FileWorkflowCoordinator.pastedImages";

    /**
     * After an explicit save: moves the images pasted into the document in this session that nothing
     * mentions any more to the trash ({@link PastedImageCleanup}). The save does not wait for it.
     *
     * <p>Nothing is searched on the FX thread (PERF-01, Pauta 6.2.0): it takes the texts there (the saved text,
     * the other open documents), and the search for the images they mention, the reading of the sibling documents
     * and the moving of files run on {@link #fileLoadExecutor}. Between the two the texts are taken again on the FX
     * thread — the user may have undone the deletion — and checked once more off it; the status note is posted from
     * the FX thread at the end. Closing the window stops it ({@link #shutdown}).
     */
    private void cleanUpPastedImages(SaveRequest request) {
        EditorBuffer buffer = request.buffer();
        java.util.Set<Path> created = buffer.markdown().sessionImages();
        if (shutdown || !host.config().getSettings().isMarkdownTrashUnusedPastedImages() || created.isEmpty()) {
            return;
        }
        Path document = request.target();
        List<Path> createdNow = List.copyOf(created);
        List<OtherDoc> others = otherDocs(buffer);
        com.editora.io.Trash.Bin chosen = pastedImageBin;
        java.util.function.BooleanSupplier cancelled = () -> shutdown || buffer.isDisposed();
        CompletableFuture<Void> done = new CompletableFuture<>();
        pastedImageCleanup = done;
        try {
            fileLoadExecutor.submit(() -> {
                try {
                    com.editora.perf.BlockingSites.enter(PASTED_IMAGES_SITE);
                    List<Path> candidates = PastedImageCleanup.candidates(
                            document, request.savedText(), createdNow, file -> mentionedIn(others, file));
                    if (candidates.isEmpty()) {
                        return;
                    }
                    List<Path> unused = PastedImageCleanup.unusedOnDisk(document, candidates, cancelled);
                    if (beforePastedImageRecheck != null) {
                        beforePastedImageRecheck.run();
                    }
                    // Is each one still ours, and still unmentioned right now? The texts are taken on the FX thread.
                    Recheck again = unused.isEmpty() || cancelled.getAsBoolean()
                            ? null
                            : onFx(() -> new Recheck(
                                            buffer.getContent(),
                                            created.stream()
                                                    .filter(unused::contains)
                                                    .toList(),
                                            otherDocs(buffer)))
                                    .get();
                    List<Path> confirmed = again == null
                            ? List.of()
                            : PastedImageCleanup.candidates(
                                    document, again.text(), again.created(), file -> mentionedIn(again.others(), file));
                    com.editora.io.Trash.Bin bin = chosen != null ? chosen : com.editora.io.Trash.system();
                    PastedImageCleanup.Outcome outcome = PastedImageCleanup.trash(confirmed, bin, cancelled);
                    onFx(() -> {
                                created.removeAll(outcome.trashed());
                                created.removeAll(outcome.gone());
                                String note = PastedImageCleanup.note(outcome);
                                if (!note.isEmpty() && !cancelled.getAsBoolean()) {
                                    host.setStatus(note);
                                }
                                return null;
                            })
                            .get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // the window is closing
                } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
                    // nothing was trashed that should not have been; the files stay for the next save
                } finally {
                    done.complete(null);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            done.complete(null);
        }
    }

    /** Runs {@code task} on the FX thread and hands its result back to a background caller. */
    private static <T> CompletableFuture<T> onFx(java.util.function.Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                result.complete(task.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** What a finished save says: the warnings a plain "Saved" used to overwrite a few milliseconds later. */
    private static String savedStatus(SaveRequest request, DiskWrite disk, boolean autoSave) {
        if (request.charsetFallback() != null) {
            return request.notes()
                    .appendTo(tr(
                            "status.charsetFallback",
                            com.editora.editorconfig.EditorConfigCharset.displayName(request.charsetFallback())));
        }
        if (disk.inPlace()) {
            return request.notes()
                    .appendTo(tr("status.savedInPlace", com.editora.config.PathDisplay.of(request.target())));
        }
        return request.notes()
                .appendTo(
                        autoSave
                                ? tr("status.autoSaved", request.target().getFileName())
                                : tr("status.saved", com.editora.config.PathDisplay.of(request.target())));
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
        host.htmlPreview().pathChanged(buffer, current);
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
                request.documentVersion(),
                request.content().length(),
                com.editora.editops.TextFingerprint.of(request.content()),
                fingerprint(request.diskBytes()),
                request.diskBytes().length,
                disk,
                request.lineEnding());
        committedSaves.compute(
                com.editora.config.PathKeys.key(request.target()),
                (ignored, previous) ->
                        previous == null || previous.sequence() < committed.sequence() ? committed : previous);
    }

    private boolean matchesOwnCommit(Path target, long modifiedMillis, long size) {
        CommittedSave committed = committedSaves.get(com.editora.config.PathKeys.key(target));
        if (committed == null || !hasOwnCommitMetadata(committed, target, modifiedMillis, size)) {
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

    private static boolean hasOwnCommitMetadata(
            @Nullable CommittedSave committed, Path target, long modifiedMillis, long size) {
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
        buffer.acknowledgeSavedContent(
                committed.documentVersion(),
                committed.textLength(),
                committed.textFingerprint(),
                committed.lineEnding());
        buffer.setDiskSnapshot(
                committed.disk().modifiedMillis(), committed.disk().size(), committed.fingerprint());
    }

    boolean hasPendingSave(EditorBuffer buffer) {
        return pendingSaves.getOrDefault(buffer, 0) > 0;
    }

    void shutdown() {
        shutdown = true;
        autoSaveIdleTimer.stop();
        // Not shutdownNow: that interrupts the write that is running, and an interrupt in the middle of an
        // in-place overwrite leaves the user's file truncated. The tickets closed below already stop every
        // write that has not reached its commit point; one that has is allowed to finish.
        autoSaveExecutor.shutdown();
        remoteSaveExecutors.values().forEach(ExecutorService::shutdown);
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

    private static void completeFuture(@Nullable CompletableFuture<Boolean> completed, boolean result) {
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
        String next = switch (autoSaveMode()) {
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
