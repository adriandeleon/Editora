package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * What a Python server must hear about its environment that the project's watchers never report (Python review 2).
 *
 * <ul>
 *   <li><b>Packages installed or removed</b> (B102): Pyright re-resolves imports when it is told a file under a
 *       library folder changed, and only then — it watches nothing itself when the client does not register its
 *       watchers. The project's watchers skip every virtual environment, so {@code pip install requests} in the
 *       terminal left {@code import requests} red until a restart. Each local Python session gets a shallow watcher
 *       on the {@code site-packages} folders of the environment it was told about; a package folder created or
 *       removed there is reported to that session alone.</li>
 *   <li><b>An environment created or deleted</b> (B103): {@link #touchesEnvironment} tells the manager to resolve
 *       the interpreter again.</li>
 * </ul>
 */
final class PythonLibraryWatchers implements AutoCloseable {

    private record Entry(String pythonPath, List<RootWatcher> watchers) {}

    private final Map<LanguageServerSession, Entry> entries = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /**
     * Watches the library folders of the interpreter {@code session} was last told ({@link
     * LanguageServerSession#pythonPath()}), replacing what it watched before. A remote session, or one told no
     * interpreter, watches nothing. Any thread; touches the file system.
     */
    void update(LanguageServerSession session) {
        entries.keySet().removeIf(s -> {
            boolean gone = s.isDisposed();
            if (gone) {
                closeEntry(entries.get(s));
            }
            return gone;
        });
        String pythonPath = session.pythonPath();
        Entry old = entries.get(session);
        if (old != null && old.pythonPath().equals(pythonPath)) {
            return;
        }
        closeEntry(entries.remove(session));
        if (closed
                || pythonPath == null
                || session.isDisposed()
                || !com.editora.process.ExecTargets.runsHere(session.root())) {
            return;
        }
        List<RootWatcher> watchers = new ArrayList<>();
        for (Path dir : sitePackages(pythonPath)) {
            RootWatcher w = RootWatcher.startShallow(dir, batch -> report(session, dir, batch));
            if (w != null) {
                watchers.add(w);
            }
        }
        entries.put(session, new Entry(pythonPath, watchers));
        if (closed) {
            close();
        }
    }

    private void report(LanguageServerSession session, Path dir, List<LspManager.WatchedFile> batch) {
        if (session.isDisposed()) {
            closeEntry(entries.remove(session));
            return;
        }
        var events = LspManager.eventsUnderRoot(batch, dir, session.uris());
        if (!events.isEmpty()) {
            session.didChangeWatchedFiles(events);
        }
    }

    /** How many folders are watched for {@code session} (tests). */
    int watchedFor(LanguageServerSession session) {
        Entry e = entries.get(session);
        return e == null ? 0 : e.watchers().size();
    }

    private static void closeEntry(@Nullable Entry e) {
        if (e != null) {
            e.watchers().forEach(RootWatcher::close);
        }
    }

    /** Stops every watcher; a later {@link #update} watches again (language servers turned off). */
    void closeAll() {
        entries.values().forEach(PythonLibraryWatchers::closeEntry);
        entries.clear();
    }

    /** Stops every watcher for good (the window's manager closed). */
    @Override
    public void close() {
        closed = true;
        closeAll();
    }

    /**
     * The {@code site-packages} folders of the virtual environment {@code pythonPath} runs in: {@code
     * lib/python3.X/site-packages} (and {@code lib64}'s when it is another folder) on POSIX, {@code
     * Lib/site-packages} on Windows. Empty for an interpreter outside a virtual environment — a system Python's
     * packages change with the system's package manager, not while a project is edited. File reads.
     */
    static List<Path> sitePackages(String pythonPath) {
        Path venv;
        try {
            venv = venvOf(Path.of(pythonPath));
        } catch (RuntimeException e) {
            return List.of();
        }
        if (venv == null) {
            return List.of();
        }
        Set<Path> found = new java.util.LinkedHashSet<>();
        Path windows = venv.resolve("Lib").resolve("site-packages");
        if (Files.isDirectory(windows)) {
            found.add(windows);
        }
        for (String lib : List.of("lib", "lib64")) {
            try (var children = Files.list(venv.resolve(lib))) {
                children.filter(d -> d.getFileName().toString().startsWith("python"))
                        .map(d -> d.resolve("site-packages"))
                        .filter(Files::isDirectory)
                        .sorted()
                        .forEach(d -> {
                            if (found.stream().noneMatch(f -> sameFile(f, d))) {
                                found.add(d);
                            }
                        });
            } catch (java.io.IOException | RuntimeException e) {
                // no such folder
            }
        }
        return List.copyOf(found);
    }

    /** The environment folder an interpreter runs in ({@code …/.venv/bin/python} → {@code …/.venv}), or null. */
    static @Nullable Path venvOf(Path executable) {
        Path bin = executable.getParent();
        Path root = bin == null ? null : bin.getParent();
        if (root != null && Files.isRegularFile(root.resolve("pyvenv.cfg"))) {
            return root;
        }
        return bin != null && Files.isRegularFile(bin.resolve("pyvenv.cfg")) ? bin : null; // conda on Windows
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (java.io.IOException | RuntimeException e) {
            return a.equals(b);
        }
    }

    /**
     * Whether {@code changes} create or remove a Python environment: a {@code pyvenv.cfg}, or a folder named as
     * one ({@code .venv}, {@code venv}, {@code env}, {@code .env}) — the watchers report the folder itself but
     * nothing inside it (Python review 2, B103). Pure.
     */
    static boolean touchesEnvironment(List<LspManager.WatchedFile> changes) {
        for (LspManager.WatchedFile c : changes) {
            if (c == null || c.file() == null || c.file().getFileName() == null) {
                continue;
            }
            String name = c.file().getFileName().toString();
            if (name.equals("pyvenv.cfg")
                    || (c.kind() != LspManager.WatchedKind.CHANGED
                            && com.editora.run.PythonInterpreters.VENV_DIRS.contains(name))) {
                return true;
            }
        }
        return false;
    }
}
