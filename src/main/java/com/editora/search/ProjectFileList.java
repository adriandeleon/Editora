package com.editora.search;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * The names of a project's files and folders, listed once and then kept current, for searches that filter them
 * in memory on every keystroke instead of walking the disk again (Project Map review 3 A3).
 *
 * <p>A local root is walked once through {@link ProjectWalk}: names only, nothing read, {@code .gitignore}d and
 * dependency folders pruned before they are entered when the setting asks for it, the cap counting files only.
 * A remote root is listed by one command on its host ({@link RemoteFiles}), or walked over SFTP when its host runs
 * none. A complete names-only walk of a 31,000-file repository takes about 0.2 s; scanning the list then costs a
 * few milliseconds per query, so a search sees the whole project instead of the first levels a bounded walk
 * reached.
 *
 * <p>The list follows the disk by {@link #changed}: the owner reports each path an in-app operation or a watch
 * event touched, and the list looks at that path again (a created folder is listed below it). What no event
 * reports (a change in a folder nobody watches) is caught by {@link #markStale}, after which the owner lists the
 * project again in the background and keeps searching the old list meanwhile.
 *
 * <p>Thread-safe. Building and {@link #changed} touch the disk: call them off the JavaFX thread. {@link
 * #snapshot} does not.
 */
public final class ProjectFileList {

    /** At most this many files are listed; a project with more is listed in part and says so. */
    public static final int MAX_FILES = 200_000;
    /** Folders deeper than this are not entered (a cycle through a junction, a generated maze). */
    public static final int MAX_DEPTH = 64;
    /** A created folder with more entries than this is not listed by {@link #changed}: the list goes stale. */
    static final int MAX_CHANGED_ENTRIES = 5_000;

    /** What is listed: with or without {@code .gitignore}d files, with or without hidden ones. */
    public record Settings(boolean respectGitignore, boolean includeHidden) {}

    /**
     * The list at one moment, for a scan: root-relative, {@code /}-separated paths of the files and of the
     * folders (the root itself not among them), and whether the listing was cut short by the cap or the depth.
     */
    public record Snapshot(List<String> files, List<String> folders, boolean truncated) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), false);

        public Snapshot {
            files = List.copyOf(files);
            folders = List.copyOf(folders);
        }
    }

    private final Path root;
    private final Settings settings;
    private final GitignoreFilter gitignore;
    private final Set<String> files;
    private final Set<String> folders;
    private final boolean truncated;
    private volatile boolean stale;
    private @Nullable Snapshot snapshot;
    /**
     * When the disk was last known to match the list (wall clock, ms): the start of the walk, then of each
     * {@link #revalidate}. A folder modified since may have gained or lost an entry no event reported.
     */
    private volatile long validatedAt;

    private ProjectFileList(
            Path root,
            Settings settings,
            GitignoreFilter gitignore,
            Set<String> files,
            Set<String> folders,
            boolean truncated) {
        this.root = root;
        this.settings = settings;
        this.gitignore = gitignore;
        this.files = files;
        this.folders = folders;
        this.truncated = truncated;
    }

    private ProjectFileList at(long when) {
        validatedAt = when;
        return this;
    }

    /**
     * Lists {@code root}: its files and folders by name, as {@code settings} says. Blocking. {@code null} when
     * {@code cancelled} said so before the listing finished (a newer search or another root replaced it).
     */
    public static @Nullable ProjectFileList build(Path root, Settings settings, BooleanSupplier cancelled) {
        return build(root, settings, null, cancelled);
    }

    /**
     * As above, a local root leaving out what {@code gitignore} ignores instead of the rules the settings would
     * load ({@code null}: those). A remote root is listed as above.
     */
    public static @Nullable ProjectFileList build(
            Path root, Settings settings, @Nullable GitignoreFilter gitignore, BooleanSupplier cancelled) {
        Path normalized = root.toAbsolutePath().normalize();
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        long started = System.currentTimeMillis();
        if (!Vfs.isLocal(normalized)) {
            ProjectFileList listed = listRemote(normalized, settings);
            if (listed != null) {
                return stop.getAsBoolean() ? null : listed;
            }
        }
        GitignoreFilter ignore = gitignore != null
                ? gitignore
                : settings.respectGitignore() ? GitignoreFilter.load(normalized) : GitignoreFilter.NONE;
        Set<String> files = new LinkedHashSet<>();
        Set<String> folders = new LinkedHashSet<>();
        ProjectWalk.Outcome outcome = ProjectWalk.walk(
                normalized,
                new ProjectWalk.Options(MAX_DEPTH, MAX_FILES, ignore, stop, settings.includeHidden()),
                new ProjectWalk.Visitor() {
                    @Override
                    public ProjectWalk.Verdict file(Path file, String rel, BasicFileAttributes attrs) {
                        files.add(rel); // a link to a folder is a row of its own on the map: listed as a name
                        return ProjectWalk.Verdict.ACCEPT;
                    }

                    @Override
                    public boolean enter(Path dir, String rel) {
                        folders.add(rel);
                        return true;
                    }
                });
        if (stop.getAsBoolean()) {
            return null;
        }
        return new ProjectFileList(normalized, settings, ignore, files, folders, outcome.truncated()).at(started);
    }

    /** One command on the host; {@code null} when the host runs none (the caller walks over SFTP then). */
    private static @Nullable ProjectFileList listRemote(Path root, Settings settings) {
        RemoteFiles.Listing listing;
        try {
            listing = RemoteFiles.list(root, settings.respectGitignore(), MAX_FILES);
        } catch (RuntimeException failed) {
            listing = null; // a dropped connection: the SFTP walk reports what it can
        }
        if (listing == null) {
            return null;
        }
        Set<String> files = new LinkedHashSet<>(listing.paths());
        Set<String> folders = new LinkedHashSet<>();
        for (String rel : files) {
            addParents(rel, folders);
        }
        // The host's own lister applied the ignore rules; a later change is checked against the root's file.
        GitignoreFilter ignore = settings.respectGitignore() ? GitignoreFilter.load(root) : GitignoreFilter.NONE;
        return new ProjectFileList(root, settings, ignore, files, folders, listing.truncated());
    }

    private static void addParents(String rel, Set<String> folders) {
        for (int slash = rel.lastIndexOf('/'); slash > 0; slash = rel.lastIndexOf('/', slash - 1)) {
            if (!folders.add(rel.substring(0, slash))) {
                return; // its parents are there already
            }
        }
    }

    public Path root() {
        return root;
    }

    public Settings settings() {
        return settings;
    }

    /** Whether this list was built for {@code root} with {@code settings}. */
    public boolean isFor(@Nullable Path root, Settings settings) {
        return root != null && this.root.equals(root.toAbsolutePath().normalize()) && this.settings.equals(settings);
    }

    /** Whether the cap or the depth limit cut the listing short. */
    public boolean truncated() {
        return truncated;
    }

    /** What {@link #revalidate} found. */
    public enum Revalidation {
        /** Every listed folder is as it was listed. */
        UNCHANGED,
        /** Some folders gained or lost entries; the list has been brought up to date. */
        CHANGED,
        /** A cheap look could not tell (a remote root, ignore rules changed, too many folders moved): list again. */
        RELIST
    }

    /** Beyond this many folders modified since the last look, listing again is cheaper than looking at each. */
    static final int MAX_REVALIDATED_FOLDERS = 64;

    /**
     * Allowance for file systems whose modification times are coarse (a second or two): a folder modified this
     * close before the last look is looked at again.
     */
    static final long MTIME_SLACK_MS = 2_000;

    /**
     * Checks the list against the disk by the folders' modification times — one stat per listed folder instead of
     * a walk with a stat per file — and looks again at the direct entries of each folder modified since the list
     * was last known to be current: what something outside the editor added or removed in a folder nobody watches
     * (a focus regain, F4 of perf review 2026-11-23). A file rewritten in place changes no folder, and no name.
     * Blocking: off the FX thread.
     */
    public Revalidation revalidate(BooleanSupplier cancelled) {
        if (!Vfs.isLocal(root)) {
            return Revalidation.RELIST;
        }
        long started = System.currentTimeMillis();
        long since = validatedAt - MTIME_SLACK_MS;
        if (settings.respectGitignore() && modifiedSince(root.resolve(".gitignore"), since)) {
            return Revalidation.RELIST; // the rules the list was made by changed
        }
        List<String> known;
        synchronized (this) {
            known = new ArrayList<>(folders.size() + 1);
            known.add("");
            known.addAll(folders);
        }
        List<String> moved = new ArrayList<>();
        for (String rel : known) {
            if (cancelled.getAsBoolean()) {
                return Revalidation.UNCHANGED; // nothing was changed; the next look starts from the same time
            }
            if (modifiedSince(rel.isEmpty() ? root : root.resolve(rel), since)) {
                moved.add(rel);
                if (moved.size() > MAX_REVALIDATED_FOLDERS) {
                    return Revalidation.RELIST;
                }
            }
        }
        boolean changed = false;
        for (String rel : moved) {
            changed |= relookFolder(rel);
            if (stale) {
                return Revalidation.RELIST; // a created folder held too much: listChanged asked for a listing
            }
        }
        validatedAt = started;
        return changed ? Revalidation.CHANGED : Revalidation.UNCHANGED;
    }

    private static boolean modifiedSince(Path path, long since) {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.lastModifiedTime().toMillis() >= since;
        } catch (IOException | RuntimeException gone) {
            return false; // a folder that went away: its parent's time moved too, and that look removes it
        }
    }

    /** Brings the direct entries of folder {@code rel} ("" for the root) up to date; whether anything changed. */
    private boolean relookFolder(String rel) {
        Path dir = rel.isEmpty() ? root : root.resolve(rel);
        String prefix = rel.isEmpty() ? "" : rel + "/";
        Set<String> onDisk = new java.util.HashSet<>();
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> onDisk.add(prefix + p.getFileName()));
        } catch (IOException | RuntimeException unreadable) {
            return false; // gone or unreadable: its parent's look, or the next listing, says so
        }
        List<Path> toLook = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        synchronized (this) {
            for (String name : onDisk) {
                if (!files.contains(name) && !folders.contains(name)) {
                    toLook.add(root.resolve(name)); // new here, or left out by the rules: changedOne decides
                }
            }
            for (String file : files) {
                if (directlyIn(file, prefix) && !onDisk.contains(file)) {
                    gone.add(file);
                }
            }
            for (String folder : folders) {
                if (directlyIn(folder, prefix) && !onDisk.contains(folder)) {
                    gone.add(folder);
                }
            }
        }
        Snapshot before = snapshot();
        gone.forEach(this::remove);
        changed(toLook);
        return snapshot() != before;
    }

    /** Whether {@code rel} is a direct entry of the folder whose names start with {@code prefix}. */
    private static boolean directlyIn(String rel, String prefix) {
        return rel.startsWith(prefix) && rel.indexOf('/', prefix.length()) < 0;
    }

    /** Something may have changed that no event reported (a focus regain, a lost event): list again soon. */
    public void markStale() {
        stale = true;
    }

    public boolean stale() {
        return stale;
    }

    public synchronized int fileCount() {
        return files.size();
    }

    /** The list as it is now; the same object until the list changes. */
    public synchronized Snapshot snapshot() {
        Snapshot known = snapshot;
        if (known == null) {
            known = new Snapshot(new ArrayList<>(files), new ArrayList<>(folders), truncated);
            snapshot = known;
        }
        return known;
    }

    /**
     * Looks at each of {@code paths} again: one that is gone leaves the list with everything below it; a file
     * that is there joins it unless the listing's rules leave it out; a folder that is there joins it with what
     * it holds. Blocking. Paths outside the root are ignored.
     */
    public void changed(Collection<Path> paths) {
        for (Path path : paths) {
            if (path != null) {
                changedOne(path.toAbsolutePath().normalize());
            }
        }
    }

    private void changedOne(Path path) {
        if (!path.startsWith(root) || path.equals(root) || !path.getFileSystem().equals(root.getFileSystem())) {
            return;
        }
        String rel = ProjectWalk.relativize(root, path);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException gone) {
            remove(rel);
            return;
        }
        if (!offered(path, attrs.isDirectory())) {
            remove(rel); // now ignored, or under an ignored folder
            return;
        }
        if (!attrs.isDirectory()) {
            add(rel);
            return;
        }
        List<String> below = new ArrayList<>();
        List<String> belowFolders = new ArrayList<>();
        boolean tooMany = !listBelow(path, below, belowFolders);
        if (tooMany) {
            stale = true; // a checkout or an unpacked archive: listing all of it again is cheaper and right
            return;
        }
        synchronized (this) {
            folders.add(rel);
            addParents(rel, folders);
            folders.addAll(belowFolders);
            files.addAll(below);
            snapshot = null;
        }
    }

    /** Whether the listing's rules keep {@code path}: no hidden or ignored step on the way down. */
    private boolean offered(Path path, boolean directory) {
        if (settings.includeHidden()) {
            Path rel = root.relativize(path);
            for (Path name : rel) {
                if (name.toString().equals(".git")) {
                    return false;
                }
            }
            if (gitignore.isEmpty()) {
                return true;
            }
            // Hidden steps are allowed here; only the ignore rules can still leave it out.
            String relText = ProjectWalk.relativize(root, path);
            return !gitignore.ignored(relText, directory);
        }
        return ProjectWalk.offers(root, path, gitignore);
    }

    /**
     * The files and folders below the created folder {@code dir}, as the listing's rules keep them; false when it
     * holds more than {@link #MAX_CHANGED_ENTRIES}.
     */
    private boolean listBelow(Path dir, List<String> filesOut, List<String> foldersOut) {
        int[] seen = {0};
        boolean[] tooMany = {false};
        try {
            Files.walkFileTree(dir, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (d.equals(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++seen[0] > MAX_CHANGED_ENTRIES) {
                        tooMany[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!offered(d, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    foldersOut.add(ProjectWalk.relativize(root, d));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++seen[0] > MAX_CHANGED_ENTRIES) {
                        tooMany[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isDirectory() && offered(file, false)) {
                        filesOut.add(ProjectWalk.relativize(root, file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException unreadable) {
            // what was collected is still right; the rest comes with the next full listing
        }
        return !tooMany[0];
    }

    private synchronized void add(String rel) {
        int before = folders.size();
        boolean changed = files.add(rel);
        addParents(rel, folders);
        if (changed || folders.size() != before) {
            snapshot = null;
        }
    }

    /** Drops {@code rel}; a folder goes with everything listed below it (one scan, folders only). */
    private synchronized void remove(String rel) {
        boolean changed = files.remove(rel);
        if (folders.remove(rel)) {
            String prefix = rel + "/";
            files.removeIf(path -> path.startsWith(prefix));
            folders.removeIf(path -> path.startsWith(prefix));
            changed = true;
        }
        if (changed) {
            snapshot = null;
        }
    }
}
