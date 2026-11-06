package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javafx.application.Platform;

import com.editora.lsp.LspManager.WatchedFile;
import com.editora.lsp.LspManager.WatchedKind;

/**
 * File changes made outside the editor, on their way to the servers, for {@link LspManager}: the watchers of local
 * server roots and the scanners of remote ones, {@code workspace/didChangeWatchedFiles} to every session whose root
 * holds a change, lemminx told of an open grammar rewritten on disk, and jdtls given the project style again when
 * a file it is read from changes (#677).
 */
final class LspFileWatching {

    private final LspManager m;

    LspFileWatching(LspManager m) {
        this.m = m;
    }

    /** Root → its watcher; at most {@link #MAX_ROOT_WATCHERS}, closed with the manager. */
    private final Map<Path, RootWatcher> rootWatchers = new ConcurrentHashMap<>();

    static final int MAX_ROOT_WATCHERS = 8;

    /**
     * The grammar files lemminx registers to be told of ({@code **}{@code /*.xsd}, {@code *.dtd}, {@code *.rng},
     * {@code *.rnc}), plus the DTD fragments it reads ({@code .ent}, {@code .mod}).
     */
    private static final java.util.Set<String> XML_GRAMMAR_EXTENSIONS =
            java.util.Set.of("xsd", "dtd", "rng", "rnc", "ent", "mod");

    /** Whether a file is one an XML server revalidates its documents against when it changes (XML review B9). */
    static boolean xmlGrammarFile(Path file) {
        Path name = file.getFileName();
        String n = name == null ? "" : name.toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && XML_GRAMMAR_EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /** The build files jdtls updates a project's configuration from when told they changed. */
    private static final java.util.Set<String> JAVA_BUILD_FILES =
            java.util.Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * Whether a file is one a server rebuilds its model from when it changes: an XML grammar (XML review B9) or a
     * Java build file (twelfth review CL1).
     */
    static boolean modelFile(Path file) {
        Path name = file.getFileName();
        return xmlGrammarFile(file) || (name != null && JAVA_BUILD_FILES.contains(name.toString()));
    }

    /**
     * Starts watching a local server's {@code root}, the whole tree, when nothing watches it yet (any thread). The
     * Project panel watches only the folders it shows expanded, so a file changed by git, a terminal or another
     * editor in a collapsed one never reached the servers: a schema regenerated there left lemminx's errors stale
     * (XML review B9), a module's pom.xml left jdtls's classpath stale (twelfth review CL1), a sourced library left
     * the Bash server's lines and functions stale (Bash review B16, B7). The panel's own events are forwarded only for
     * the files this watcher does not report ({@link #reportsChangesTo}). One watcher per root, none for a root
     * inside one already watched, at most {@link #MAX_ROOT_WATCHERS}; each bounded by {@link RootWatcher#MAX_DIRS}
     * folders, skipping hidden, dependency and {@code .gitignore}d ones.
     */
    void ensureRootWatcher(Path root, String serverId) {
        if (m.isClosed()
                || root == null
                || !com.editora.process.ExecTargets.runsHere(root)
                || RootResolver.isBroadRoot(root)) {
            return;
        }
        Path key = root.toAbsolutePath().normalize();
        synchronized (rootWatchers) {
            if (rootWatchers.containsKey(key)
                    || rootWatchers.size() >= MAX_ROOT_WATCHERS
                    || rootWatchers.values().stream().anyMatch(w -> key.startsWith(w.root()) && w.watches(key))) {
                return;
            }
            java.util.function.Consumer<List<WatchedFile>> sink = batch -> Platform.runLater(() -> {
                notifyWatchedFiles(batch);
                m.onWatchedFiles.accept(batch);
            });
            RootWatcher w = RootWatcher.start(key, sink);
            if (w == null) {
                return;
            }
            rootWatchers
                    .entrySet()
                    .removeIf(
                            e -> { // a root inside this one: its events would go out twice
                                boolean inside = e.getKey().startsWith(key);
                                if (inside) {
                                    e.getValue().close();
                                }
                                return inside;
                            });
            rootWatchers.put(key, w);
        }
        if (m.isClosed()) {
            closeRootWatchers();
        }
    }

    void closeRootWatchers() {
        synchronized (rootWatchers) {
            rootWatchers.values().forEach(RootWatcher::close);
            rootWatchers.clear();
            remoteScanners.values().forEach(RemoteRootScanner::close);
            remoteScanners.clear();
        }
    }

    /** Remote root → its scanner ({@link RemoteRootScanner}); at most {@link #MAX_ROOT_WATCHERS}. */
    private final Map<Path, RemoteRootScanner> remoteScanners = new ConcurrentHashMap<>();

    /**
     * Starts listing a server's folder on another machine — an SSH host, WSL, a container — when the window comes
     * to the front, so a file {@code git pull} created there reaches the server (Python review 2, B108). Any thread.
     */
    void ensureRemoteScanner(Path root) {
        if (m.isClosed() || root == null) {
            return;
        }
        synchronized (rootWatchers) {
            remoteScanners
                    .entrySet()
                    .removeIf(
                            e -> { // a dropped connection's: its tabs moved to the new one
                                boolean gone = !e.getKey().getFileSystem().isOpen();
                                if (gone) {
                                    e.getValue().close();
                                }
                                return gone;
                            });
            if (remoteScanners.containsKey(root)
                    || remoteScanners.size() >= MAX_ROOT_WATCHERS
                    || remoteScanners.keySet().stream() // paths of two connections cannot be compared
                            .anyMatch(k -> k.getFileSystem() == root.getFileSystem() && root.startsWith(k))) {
                return;
            }
            remoteScanners.put(
                    root,
                    new RemoteRootScanner(
                                    root,
                                    batch -> Platform.runLater(() -> {
                                        if (!m.isClosed()) {
                                            notifyWatchedFiles(batch);
                                            m.onWatchedFiles.accept(batch);
                                        }
                                    }),
                                    () -> m.windowFocused.getAsBoolean())
                            .start());
        }
    }

    /** The remote roots being listed (tests). */
    java.util.Set<Path> scannedRemoteRoots() {
        return java.util.Set.copyOf(remoteScanners.keySet());
    }

    /**
     * Whether a watcher of this manager's own reports changes to {@code file}, a build file or a grammar: one that
     * watches the file's own folder, not merely its root, which a large tree is watched only in part of (thirteenth
     * review CK3).
     */
    public boolean watchesModelFile(Path file) {
        return modelFile(file.toAbsolutePath().normalize()) && reportsChangesTo(file);
    }

    private static boolean watches(RootWatcher w, Path file) {
        return file.startsWith(w.root()) && file.getParent() != null && w.watches(file.getParent());
    }

    /**
     * Whether a watcher of this manager's own reports changes to {@code file} (its folder is watched): the Project
     * panel's own event for it would go out twice (twelfth review CL1, Bash review B16).
     */
    public boolean reportsChangesTo(Path file) {
        Path at = file.toAbsolutePath().normalize();
        return rootWatchers.values().stream().anyMatch(w -> watches(w, at));
    }

    /** The roots watched by this manager itself (tests). */
    java.util.Set<Path> watchedRoots() {
        return java.util.Set.copyOf(rootWatchers.keySet());
    }

    /**
     * Forwards external file changes to every live session whose root contains them
     * ({@code workspace/didChangeWatchedFiles}) — how a git checkout / CLI build / external editor reaches
     * the servers' project models instead of leaving them stale until restart (#677). Callers coalesce
     * bursts (a branch switch touches hundreds of files); one notification per session per batch.
     */
    public void notifyWatchedFiles(List<WatchedFile> changes) {
        if (!m.isEnabled() || changes == null || changes.isEmpty()) {
            return;
        }
        for (LanguageServerSession s : m.sessionsByRoot.values()) {
            List<org.eclipse.lsp4j.FileEvent> events = eventsUnderRoot(changes, s.root(), s.uris());
            if (!events.isEmpty()) {
                s.didChangeWatchedFiles(events);
                m.bashReanalysis.changed(s, changes); // the Bash server ignores the notification (Bash review B7)
                openGrammarsChanged(s, changes);
            }
        }
        changes.stream().map(WatchedFile::file).filter(f -> f != null).forEach(this::projectStyleChanged);
        if (PythonLibraryWatchers.touchesEnvironment(changes)) {
            m.pythonEnvironmentChanged(); // python -m venv .venv after the server started (review 2, B103)
        }
    }

    /**
     * lemminx revalidates the documents that use a grammar when that grammar is saved, and treats a watched-file
     * change as a save only for a file the client does not hold open (XML/pom review 4, B31): a schema open in a tab
     * and rewritten on disk by a pull or a build left every document using it with its old errors. Such a change is
     * told as the save it is on disk; the tab's own text is the editor's to reload.
     */
    private static void openGrammarsChanged(LanguageServerSession s, List<WatchedFile> changes) {
        if (!XmlServerSettings.isLemminx(s.serverId())) {
            return;
        }
        for (WatchedFile c : openGrammarChanges(changes, s::isOpen)) {
            s.didSave(LspManager.uri(c.file()), null);
        }
    }

    /** Pure: the changes to a grammar file ({@link #xmlGrammarFile}) still on disk whose document {@code open} holds. */
    static List<WatchedFile> openGrammarChanges(List<WatchedFile> changes, java.util.function.Predicate<String> open) {
        return changes.stream()
                .filter(c -> c != null && c.file() != null && c.kind() != WatchedKind.DELETED)
                .filter(c -> xmlGrammarFile(c.file()) && open.test(LspManager.uri(c.file())))
                .toList();
    }

    /**
     * {@code file} changed (saved here, or seen by a watcher): when it is one the project's Java style is read
     * from, the Java servers whose project holds it get the style again (re-review AN5).
     */
    public void projectStyleChanged(Path file) {
        if (file == null || !JavaProjectSettings.isStyleSource(file)) {
            return;
        }
        Path at = file.toAbsolutePath().normalize();
        refreshStyleUnder(at);
        String name = at.getFileName() == null ? "" : at.getFileName().toString();
        if (name.equals(".settings") || name.equals("config")) {
            // the folder came, or went, with its profile: read again once the files in it have landed too (DN4)
            CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> Platform.runLater(() -> {
                        if (!m.isClosed()) {
                            refreshStyleUnder(at);
                        }
                    }));
        }
    }

    private void refreshStyleUnder(Path at) {
        for (Map.Entry<String, LanguageServerSession> e : m.sessionsByRoot.entrySet()) {
            LanguageServerSession s = e.getValue();
            if (e.getKey().startsWith(LspManager.sessionKeyPrefix(LspServerRegistry.JAVA_SERVER_ID))
                    && at.startsWith(s.root().toAbsolutePath().normalize())) {
                s.refreshProjectSettings();
            }
        }
    }

    /** Pure: the {@code FileEvent}s for the changes under {@code root} (path-component containment). */
    static List<org.eclipse.lsp4j.FileEvent> eventsUnderRoot(List<WatchedFile> changes, Path root) {
        return eventsUnderRoot(changes, root, WireUris.LOCAL);
    }

    /** As above, with each file named the way {@code uris}'s server names it. */
    static List<org.eclipse.lsp4j.FileEvent> eventsUnderRoot(List<WatchedFile> changes, Path root, WireUris uris) {
        Path rootNorm = root.toAbsolutePath().normalize();
        List<org.eclipse.lsp4j.FileEvent> out = new ArrayList<>();
        for (WatchedFile c : changes) {
            if (c == null || c.file() == null) {
                continue;
            }
            Path p = c.file().toAbsolutePath().normalize();
            if (!p.startsWith(rootNorm)) {
                continue;
            }
            var type = switch (c.kind()) {
                case CREATED -> org.eclipse.lsp4j.FileChangeType.Created;
                case CHANGED -> org.eclipse.lsp4j.FileChangeType.Changed;
                case DELETED -> org.eclipse.lsp4j.FileChangeType.Deleted;
            };
            out.add(new org.eclipse.lsp4j.FileEvent(uris.toWire(DocumentUris.of(p)), type));
        }
        return out;
    }
}
