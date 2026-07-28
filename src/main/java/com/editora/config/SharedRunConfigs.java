package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Reading and writing run configurations as a file inside the project, so a team can commit them.
 *
 * <p>Configurations normally live in the window's session file, which is private to one machine. This is the
 * shareable half: an explicit <b>export</b> writes them to {@code <project>/.editora/run-configurations.json}
 * and an explicit <b>import</b> merges them back.
 *
 * <p>Deliberately export/import rather than a live second store that the editor reads and writes
 * continuously. A dual-source model has to decide, on every read, which copy wins when the two disagree —
 * and on every write, which store a configuration belongs to. Getting that wrong loses configurations
 * silently, and the failure only shows up once someone has already edited both. An explicit copy in each
 * direction has none of that: at any moment exactly one store is authoritative for a given configuration, and
 * the user chose when to move it. A live shared store is a reasonable follow-up, but it is a different and
 * much more delicate feature than it looks.
 */
public final class SharedRunConfigs {

    /** Folder Editora keeps project-local, committable files in. */
    public static final String DIR = ".editora";

    public static final String FILE = "run-configurations.json";

    private SharedRunConfigs() {}

    /** Where a project's shared configurations live. */
    public static Path fileFor(Path projectRoot) {
        return projectRoot.resolve(DIR).resolve(FILE);
    }

    /** Reads a project's shared configurations; an empty list when the file is absent or unreadable. */
    public static List<RunConfiguration> load(ObjectMapper mapper, Path projectRoot) {
        Path file = fileFor(projectRoot);
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try {
            Stored stored = mapper.readValue(file.toFile(), Stored.class);
            return stored == null || stored.configurations == null ? List.of() : stored.configurations;
        } catch (IOException | RuntimeException e) {
            return List.of(); // a hand-edited or half-written file must not break opening the project
        }
    }

    /** Writes {@code configs} into the project, creating {@code .editora/} if needed. */
    public static void save(ObjectMapper mapper, Path projectRoot, List<RunConfiguration> configs) throws IOException {
        Path file = fileFor(projectRoot);
        Files.createDirectories(file.getParent());
        Stored stored = new Stored();
        stored.configurations = new ArrayList<>(configs);
        // Temp file + atomic move, like every other config store: a crash mid-write must not leave a
        // truncated file, and this one lives in the user's repository where a corrupt file is worse still.
        // ConfigDurabilityTest enforces this for every store.
        ConfigWriter.writeAtomic(file, mapper.copy().enable(SerializationFeature.INDENT_OUTPUT), stored);
    }

    /**
     * Merges {@code incoming} into {@code existing}, matching by name.
     *
     * <p>An incoming configuration replaces one of the same name rather than being added beside it —
     * importing twice must not leave duplicates, and re-importing after a colleague changed a configuration
     * should update it, which is the whole point. Order is preserved: existing entries stay where they are,
     * genuinely new ones are appended.
     */
    public static List<RunConfiguration> merge(List<RunConfiguration> existing, List<RunConfiguration> incoming) {
        Map<String, RunConfiguration> byName = new LinkedHashMap<>();
        for (RunConfiguration c : existing) {
            byName.put(c.name(), c);
        }
        for (RunConfiguration c : incoming) {
            byName.put(c.name(), c);
        }
        return new ArrayList<>(byName.values());
    }

    /** On-disk shape: an object rather than a bare array, so a version or other keys can be added later. */
    static final class Stored {
        public List<RunConfiguration> configurations = new ArrayList<>();
    }
}
