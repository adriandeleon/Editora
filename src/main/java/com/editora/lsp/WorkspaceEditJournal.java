package com.editora.lsp;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The on-disk record of a workspace edit's filesystem transaction, so one that was interrupted can be
 * found and put back.
 *
 * <p>Applying a workspace edit moves files aside under hidden {@code .editora-lsp-*} names before any text
 * changes: delete targets, overwritten destinations, rename sources. If the process dies between that
 * staging and the commit or rollback, those files exist only under names nobody would look for, and the
 * list of what was moved where lived in memory. This journal is that list: one line is appended (and forced
 * to disk) <em>before</em> each move, a {@code COMMIT} line once the edit is decided, and the file is
 * removed when the transaction has cleaned up after itself. A journal that is still there at the next
 * project open is an interrupted transaction.
 *
 * <p>Recovery never overwrites and never deletes user content: a staged file goes back only to a path
 * that is free, and everything else is left where it is and reported.
 *
 * <p>Pure {@code java.nio}; no JavaFX. Writing is best-effort — a journal that cannot be written must not
 * stop the refactoring it describes.
 */
public final class WorkspaceEditJournal {

    private static final String EXTENSION = ".journal";
    private static final String HEADER = "EDITORA-LSP-EDIT 1";

    /** Journals of transactions running in this process, and ones already shown to the user. */
    private static final Set<Path> CLAIMED = ConcurrentHashMap.newKeySet();

    private final Path file;
    private boolean started;
    private boolean broken;

    private WorkspaceEditJournal(Path file) {
        this.file = file;
    }

    /**
     * A journal for one transaction, kept in {@code directory}. Nothing is written until the first move is
     * recorded. With a null directory the journal records nothing.
     */
    public static WorkspaceEditJournal begin(Path directory) {
        if (directory == null) {
            return new WorkspaceEditJournal(null);
        }
        Path file = directory.resolve(UUID.randomUUID() + EXTENSION).toAbsolutePath();
        CLAIMED.add(file);
        return new WorkspaceEditJournal(file);
    }

    /** {@code original} is about to be moved to {@code stage} for a delete. */
    public void deleting(Path original, Path stage) {
        append("DELETE", original, stage);
    }

    /** {@code file} is about to be created; an existing file there is first moved to {@code backup} (or null). */
    public void creating(Path file, Path backup) {
        append("CREATE", file, backup);
    }

    /** {@code from} is about to be moved to {@code stage}, on its way to {@code to}. */
    public void renaming(Path from, Path stage, Path to) {
        append("RENAME", from, stage, to);
    }

    /** The existing file at a rename's destination {@code to} is about to be moved to {@code backup}. */
    public void replacing(Path to, Path backup) {
        append("REPLACE", to, backup);
    }

    /** The edit is decided: the staged files are now old copies awaiting removal, not something to restore. */
    public void committed() {
        if (started) {
            append("COMMIT");
        }
    }

    /** The transaction cleaned up after itself (committed, or rolled back completely): forget it. */
    public void close() {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A journal left behind points at stages that no longer exist; pending() discards it.
        }
        CLAIMED.remove(file);
    }

    /**
     * The transaction ended with files it could not put back. The journal stays so the next project open
     * can offer them, and is no longer treated as running.
     */
    public void abandon() {
        if (file != null) {
            CLAIMED.remove(file);
        }
    }

    private synchronized void append(String kind, Path... paths) {
        if (file == null || broken) {
            return;
        }
        StringBuilder line = new StringBuilder();
        if (!started) {
            line.append(HEADER).append('\n');
        }
        line.append(kind);
        for (Path path : paths) {
            line.append('\t')
                    .append(
                            path == null
                                    ? "-"
                                    : URLEncoder.encode(
                                            path.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8));
        }
        line.append('\n');
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap(line.toString().getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            started = true;
        } catch (IOException | RuntimeException e) {
            broken = true;
        }
    }

    // --- recovery -------------------------------------------------------------------------------------

    /** One recorded move. Unused paths are null. */
    private record Entry(String kind, Path first, Path second, Path third) {}

    /** What a restore did. */
    public record Restored(List<Path> restored, List<Path> leftInPlace) {}

    /** A transaction that never finished. */
    public static final class Interrupted {
        private final Path journal;
        private final boolean committed;
        private final List<Entry> entries;

        private Interrupted(Path journal, boolean committed, List<Entry> entries) {
            this.journal = journal;
            this.committed = committed;
            this.entries = entries;
        }

        /**
         * Whether the edit had been decided before the interruption. The files left over are then the
         * old copies it replaced or deleted; there is nothing to put back.
         */
        public boolean committed() {
            return committed;
        }

        /** The hidden staging files that still exist. */
        public List<Path> leftovers() {
            Set<Path> out = new LinkedHashSet<>();
            for (Entry entry : entries) {
                Path stage =
                        switch (entry.kind()) {
                            case "DELETE", "REPLACE", "CREATE", "RENAME" -> entry.second();
                            default -> null;
                        };
                if (stage != null && Files.exists(stage)) {
                    out.add(stage);
                }
            }
            return List.copyOf(out);
        }

        /** The paths the transaction was working on — where the user knows the files from. */
        public List<Path> originals() {
            Set<Path> out = new LinkedHashSet<>();
            for (Entry entry : entries) {
                if (entry.second() != null && Files.exists(entry.second())) {
                    out.add(entry.first());
                }
            }
            return List.copyOf(out);
        }

        /** Whether any path of the transaction lies at or under {@code root}. */
        public boolean touches(Path root) {
            if (root == null) {
                return false;
            }
            Path base = root.toAbsolutePath().normalize();
            for (Entry entry : entries) {
                for (Path path : new Path[] {entry.first(), entry.second(), entry.third()}) {
                    if (path != null && path.startsWith(base)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Puts every staged file back where it came from, in the reverse of the order the transaction
         * staged them (deletes, then renames, then creates). A file goes back only to a path that is free;
         * otherwise both copies stay and the staged one is reported in {@link Restored#leftInPlace}. The
         * journal is removed when nothing is left over.
         */
        public Restored restore() {
            List<Path> restored = new ArrayList<>();
            if (committed) {
                return new Restored(List.of(), leftovers()); // a decided edit is not undone from here
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (entry.kind().equals("DELETE")) {
                    moveBack(entry.second(), entry.first(), restored);
                }
            }
            // Renames: first take every completed destination back to its source, then return what was
            // moved out of a destination's way — the order rollback uses, so A→B, B→C chains unwind.
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (!entry.kind().equals("RENAME")) {
                    continue;
                }
                if (Files.exists(entry.second())) {
                    moveBack(entry.second(), entry.first(), restored);
                } else if (entry.third() != null && Files.exists(entry.third())) {
                    // The stage is gone and the source is missing: the file is at its destination.
                    moveBack(entry.third(), entry.first(), restored);
                }
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (entry.kind().equals("REPLACE")) {
                    moveBack(entry.second(), entry.first(), restored);
                }
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (!entry.kind().equals("CREATE")) {
                    continue;
                }
                try {
                    // The empty file the transaction created; one that has gained content is not ours to remove.
                    if (Files.isRegularFile(entry.first()) && Files.size(entry.first()) == 0) {
                        Files.delete(entry.first());
                    }
                } catch (IOException ignored) {
                    // Left in place; the backup below then stays where it is too.
                }
                if (entry.second() != null) {
                    moveBack(entry.second(), entry.first(), restored);
                }
            }
            List<Path> left = leftovers();
            if (left.isEmpty()) {
                dismiss();
            }
            return new Restored(List.copyOf(restored), left);
        }

        private static void moveBack(Path from, Path to, List<Path> restored) {
            if (from == null || to == null || !Files.exists(from) || Files.exists(to)) {
                return;
            }
            try {
                Path parent = to.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.move(from, to);
                restored.add(to);
            } catch (IOException ignored) {
                // Reported through leftovers().
            }
        }

        /**
         * Removes the old copies a committed transaction did not get to remove — only ever on the user's
         * explicit choice. Returns the ones that could not be removed.
         */
        public List<Path> discardLeftovers() {
            if (!committed) {
                return leftovers();
            }
            for (Path stage : leftovers()) {
                try (var tree = Files.walk(stage)) {
                    for (Path path :
                            tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // Reported through the returned leftovers.
                }
            }
            List<Path> left = leftovers();
            if (left.isEmpty()) {
                dismiss();
            }
            return left;
        }

        /** Forgets the transaction, leaving every file where it is. */
        public void dismiss() {
            try {
                Files.deleteIfExists(journal);
            } catch (IOException ignored) {
                // Offered again at the next project open.
            }
            CLAIMED.remove(journal);
        }
    }

    /**
     * The interrupted transactions recorded in {@code directory} that still have files to offer, each
     * returned once per process. Journals of transactions running right now are skipped; one whose staged
     * files are all gone describes nothing recoverable and is removed.
     */
    public static List<Interrupted> pending(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> journals;
        try (var files = Files.list(directory)) {
            journals = files.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .map(Path::toAbsolutePath)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        List<Interrupted> out = new ArrayList<>();
        for (Path journal : journals) {
            if (!CLAIMED.add(journal)) {
                continue;
            }
            Interrupted interrupted = read(journal);
            if (interrupted == null) {
                CLAIMED.remove(journal);
                continue; // unreadable: keep the file, it may be readable by a later version
            }
            if (interrupted.leftovers().isEmpty()) {
                interrupted.dismiss();
                continue;
            }
            out.add(interrupted);
        }
        return out;
    }

    /** Lets a journal returned by {@link #pending} be returned again (it was not shown after all). */
    public static void release(Interrupted interrupted) {
        if (interrupted != null) {
            CLAIMED.remove(interrupted.journal);
        }
    }

    private static Interrupted read(Path journal) {
        List<String> lines;
        try {
            lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            return null;
        }
        boolean committed = false;
        List<Entry> entries = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            try {
                switch (fields[0]) {
                    case "COMMIT" -> committed = true;
                    case "DELETE", "REPLACE", "CREATE" ->
                        entries.add(new Entry(fields[0], path(fields[1]), path(fields[2]), null));
                    case "RENAME" ->
                        entries.add(new Entry(fields[0], path(fields[1]), path(fields[2]), path(fields[3])));
                    default -> {
                        // A torn last line, or a kind a later version writes: ignore it.
                    }
                }
            } catch (RuntimeException e) {
                // A torn line (the process died while writing it): the move it announced never started.
            }
        }
        return new Interrupted(journal, committed, entries);
    }

    private static Path path(String field) {
        return field.equals("-") ? null : Path.of(URLDecoder.decode(field, StandardCharsets.UTF_8));
    }
}
