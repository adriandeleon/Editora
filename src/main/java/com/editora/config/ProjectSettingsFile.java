package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes one value of a project's {@code .editora/settings.json} — what Settings' Project scope does when a row
 * changes — without rewriting the rest of the file. The file is committed and edited by hand by anyone on the
 * team, so it is changed as a JSON tree: keys this Editora does not know, the template's {@code _description}
 * and {@code _examples}, and the order of what is there all stay as they were. Only the one key changes, a
 * section left empty is removed, and {@code version} is raised to {@link ProjectSettings#VERSION}.
 *
 * <p>A legacy {@code settings.toml} is converted first ({@link ProjectSettings#migrateLegacyForEditing}). A file
 * that cannot be read as JSON is not overwritten (that would lose the team's settings): the write fails with
 * {@link IOException} and the caller says so. A file from a newer Editora ({@code version} above ours) is not
 * written either: {@link NewerFileException}.
 */
public final class ProjectSettingsFile {

    /** The file was written by a newer Editora; writing it here could drop what that version keeps in it. */
    public static final class NewerFileException extends IOException {
        public NewerFileException(int version) {
            super("version " + version);
        }
    }

    private ProjectSettingsFile() {}

    /**
     * Sets {@code section.key} (or the top-level {@code key} when {@code section} is null) to {@code value} —
     * a String, Boolean, Integer, List of Integers or Map of language → List of Integers — or removes it when
     * {@code value} is null, then writes the file atomically. Creates the file (and {@code .editora/}) when absent.
     */
    public static void set(Path projectRoot, String section, String key, Object value) throws IOException {
        Path file = ProjectSettings.migrateLegacyForEditing(projectRoot);
        ObjectNode root = read(file);
        JsonNode v = root.get("version");
        if (v != null && v.canConvertToInt() && v.asInt() > ProjectSettings.VERSION) {
            throw new NewerFileException(v.asInt());
        }
        ObjectNode target = root;
        if (section != null) {
            JsonNode existing = root.get(section);
            target = existing instanceof ObjectNode o ? o : root.putObject(section);
        }
        if (value == null) {
            target.remove(key);
        } else {
            target.set(key, ProjectSettings.JSON.valueToTree(value));
        }
        if (section != null && target.isEmpty()) {
            root.remove(section);
        }
        root.put("version", ProjectSettings.VERSION);
        Files.createDirectories(file.getParent());
        ConfigWriter.writeAtomic(file, ProjectSettings.JSON, root);
    }

    private static ObjectNode read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return ProjectSettings.JSON.createObjectNode();
        }
        JsonNode node = ProjectSettings.JSON.readTree(file.toFile()); // throws on malformed JSON: keep the file
        if (node == null || node.isMissingNode() || node.isNull()) {
            return ProjectSettings.JSON.createObjectNode();
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IOException("not a JSON object");
        }
        return object;
    }
}
