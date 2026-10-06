package com.editora.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Persistent list of recently-opened files in {@code <configDir>/recent-files.json}. Most-recent
 * first; capped at {@link #MAX_ENTRIES}. The backing {@link ObservableList} lets UI controls react
 * to changes automatically.
 *
 * <p>The app holds <b>one</b> instance, in {@link SharedConfig#recentFiles()}, shared by every window: a
 * per-window copy rewrote the whole file from its own list, so the last window to write won.
 *
 * <p>Stored as a versioned object {@code { "schemaVersion": 1, "files": [ … ] }}. The legacy v0 format
 * was a bare JSON array; it is migrated to the wrapped form on read (see {@link ConfigSchema#RECENT}).
 */
public class RecentFiles {

    public static final int MAX_ENTRIES = 20;
    /** Current on-disk schema version of {@code recent-files.json} (v0 = the legacy bare JSON array). */
    public static final int SCHEMA_VERSION = 1;

    static final String FILE_NAME = "recent-files.json";

    /** Serialized form of {@code recent-files.json}: a version stamp plus the file paths. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stored {
        public int schemaVersion = SCHEMA_VERSION;
        public List<String> files = new ArrayList<>();
    }

    private final Path file;
    private final ConfigWriter.Sink sink;
    /**
     * Every remembered entry in its stored form, most recent first. This — not {@link #recents} — is what is
     * written back, so a remote ({@code sftp://}) entry whose connection is not open right now stays in the
     * file instead of being dropped by the next save.
     */
    private final List<String> stored = new ArrayList<>();
    /** The entries of {@link #stored} that resolve to a path at the moment, in the same order. */
    private final ObservableList<Path> recents = FXCollections.observableArrayList();

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** A standalone list that writes on the calling thread (tests, tools). The app uses {@link SharedConfig}. */
    public RecentFiles(Path configDir) {
        this(configDir, ConfigWriter.DIRECT, problem -> {});
    }

    RecentFiles(Path configDir, ConfigWriter.Sink sink, Consumer<ConfigLoadProblem> problems) {
        this.file = configDir.resolve(FILE_NAME);
        this.sink = sink;
        load(problems);
    }

    /**
     * The entries worth showing: local files that are still on disk, plus every non-local one unchecked.
     *
     * <p>A recent list outlives the files in it — a build output, a scratch file, a checkout that has been
     * deleted — and offering an entry that cannot open is worse than offering nothing.
     *
     * <p><b>Remote paths are deliberately never checked.</b> They are kept whatever {@code exists} would
     * say, because asking is a network round trip on an SFTP filesystem — per entry, on the FX thread,
     * every time the dropdown is built — which would freeze the UI for as long as the host takes to answer,
     * and answer "gone" for a host that is merely asleep. {@code isLocal} decides which are which.
     *
     * <p>Filtering only: the stored list is left alone, so a file on an unplugged drive or an unmounted
     * share comes back by itself rather than being pruned the one time it was unreachable.
     *
     * @param isLocal whether an entry lives on the default filesystem ({@code Vfs::isLocal})
     * @param exists whether it is still there ({@code Files::exists}) — consulted only for local entries
     */
    public static List<Path> showable(List<Path> entries, Predicate<Path> isLocal, Predicate<Path> exists) {
        List<Path> out = new ArrayList<>();
        for (Path p : entries) {
            if (p != null && (!isLocal.test(p) || exists.test(p))) {
                out.add(p);
            }
        }
        return out;
    }

    public ObservableList<Path> getList() {
        return recents;
    }

    /** Move {@code path} to the top (deduplicated), trim to MAX_ENTRIES, persist. */
    public void add(Path path) {
        if (path == null) {
            return;
        }
        // De-dupe by the storable string, not Path.equals — a remote (SFTP) Path.equals against a local
        // Path throws ProviderMismatchException, so a mixed local/remote list can't be compared directly.
        String key = com.editora.vfs.Vfs.toStorableString(path);
        stored.remove(key);
        stored.add(0, key);
        while (stored.size() > MAX_ENTRIES) {
            stored.remove(stored.size() - 1);
        }
        publish(key, path);
        save();
    }

    public void remove(Path path) {
        if (path == null) {
            return;
        }
        if (stored.remove(com.editora.vfs.Vfs.toStorableString(path))) {
            publish(null, null);
            save();
        }
    }

    public void clear() {
        if (!stored.isEmpty()) {
            stored.clear();
            publish(null, null);
            save();
        }
    }

    private void load(Consumer<ConfigLoadProblem> problems) {
        Stored read = ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.RECENT, problems);
        if (read.files != null) {
            read.files.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .limit(MAX_ENTRIES)
                    .forEach(stored::add);
        }
        publish(null, null);
    }

    /**
     * Rebuilds the visible list from {@link #stored} as one change. Local paths round-trip as plain strings;
     * a remote entry resolves only while its connection is open, and is simply not listed until then.
     * {@code known} is the live path for {@code knownKey} (the entry just added), used as given.
     */
    private void publish(String knownKey, Path known) {
        List<Path> resolved = new ArrayList<>(stored.size());
        for (String entry : stored) {
            Path path = entry.equals(knownKey) ? known : resolve(entry);
            if (path != null) {
                resolved.add(path);
            }
        }
        recents.setAll(resolved);
    }

    private static Path resolve(String entry) {
        try {
            return com.editora.vfs.Vfs.parseStorable(entry);
        } catch (java.nio.file.InvalidPathException notAPathHere) {
            return null; // e.g. written on another OS — keep it stored, just don't list it
        }
    }

    private void save() {
        Stored snapshot = new Stored();
        snapshot.files = List.copyOf(stored);
        sink.write(file, () -> mapper.writeValueAsBytes(snapshot));
    }
}
