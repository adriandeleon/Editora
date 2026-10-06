package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One read of a directory for a picker: its entries, folders first then files, each group by name without
 * case bias — and which of them are folders, so that rendering a row never has to ask the disk again.
 * {@link #read} blocks (a list plus a {@code stat} per entry): call it off the JavaFX thread.
 *
 * @param readable false when the path is not a directory or could not be listed
 */
record DirectoryListing(List<Path> entries, Set<Path> directories, boolean readable) {

    static final DirectoryListing UNREADABLE = new DirectoryListing(List.of(), Set.of(), false);

    boolean isDirectory(Path entry) {
        return directories.contains(entry);
    }

    /** Lists {@code dir}; with {@code directoriesOnly}, files are left out (a folder picker). */
    static DirectoryListing read(Path dir, boolean directoriesOnly) {
        if (dir == null || !Files.isDirectory(dir)) {
            return UNREADABLE;
        }
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                } else if (!directoriesOnly) {
                    files.add(p);
                }
            });
        } catch (IOException | RuntimeException ex) {
            return UNREADABLE;
        }
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
        dirs.sort(byName);
        files.sort(byName);
        List<Path> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        return new DirectoryListing(List.copyOf(all), Set.copyOf(new HashSet<>(dirs)), true);
    }
}
