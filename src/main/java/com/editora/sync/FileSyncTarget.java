package com.editora.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import com.editora.io.AtomicFileWrite;

/**
 * The synced files of a config directory, read and written as plain files. Before it overwrites or deletes
 * anything it copies those files to {@code sync/backups/<timestamp>/}, keeping the newest
 * {@value #BACKUPS_KEPT} sets: what a sync takes from the repository replaces local text that was never
 * pushed anywhere.
 */
public class FileSyncTarget implements SyncTarget {

    static final int BACKUPS_KEPT = 10;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path configDir;

    public FileSyncTarget(Path configDir) {
        this.configDir = configDir;
    }

    /** Where the copies of replaced files go. */
    public Path backupsDir() {
        return configDir.resolve("sync").resolve("backups");
    }

    @Override
    public Map<String, String> read(Set<SyncCategory> categories) throws IOException {
        return readTree(configDir, categories);
    }

    /** The files of {@code categories} under {@code root}. Text is decoded leniently: a bad byte is not a failure. */
    static Map<String, String> readTree(Path root, Set<SyncCategory> categories) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (SyncCategory category : SyncCategory.values()) {
            if (!categories.contains(category)) {
                continue;
            }
            if (!category.isDirectory()) {
                Path file = root.resolve(category.path());
                if (Files.isRegularFile(file)) {
                    out.put(category.path(), readText(file));
                }
                continue;
            }
            Path dir = root.resolve(category.path());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                stream.forEach(files::add);
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path file : files) {
                String relative = category.path() + file.getFileName();
                if (category.owns(relative) && Files.isRegularFile(file)) {
                    out.put(relative, readText(file));
                }
            }
        }
        return out;
    }

    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Override
    public boolean apply(Map<String, String> expected, Map<String, String> changes) throws IOException {
        for (String path : changes.keySet()) {
            Path file = configDir.resolve(path);
            String now = Files.isRegularFile(file) ? readText(file) : null;
            if (!Objects.equals(now, expected.get(path))) {
                return false;
            }
        }
        backup(changes.keySet());
        for (Map.Entry<String, String> change : changes.entrySet()) {
            writeOrDelete(configDir.resolve(change.getKey()), change.getValue());
        }
        return true;
    }

    /** Writes {@code text} to {@code file} through a temporary file, or deletes it when {@code text} is null. */
    static void writeOrDelete(Path file, String text) throws IOException {
        if (text == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        AtomicFileWrite.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private void backup(Set<String> paths) throws IOException {
        Path set = null;
        for (String path : paths) {
            Path file = configDir.resolve(path);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if (set == null) {
                set = backupsDir().resolve(LocalDateTime.now().format(STAMP));
            }
            Path copy = set.resolve(path);
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
        }
        if (set != null) {
            prune();
        }
    }

    private void prune() {
        try (Stream<Path> sets = Files.list(backupsDir())) {
            List<Path> newestFirst = sets.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                            .reversed())
                    .toList();
            for (Path old : newestFirst.subList(Math.min(BACKUPS_KEPT, newestFirst.size()), newestFirst.size())) {
                deleteTree(old);
            }
        } catch (IOException ignored) {
            // an old backup set that cannot be removed is only disk space
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true); // git marks its object files read-only; Windows refuses to delete those
                Files.deleteIfExists(p);
            }
        }
    }
}
