package com.editora.config.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * The format version of a file the user owns and the editor also writes: a template
 * ({@code templates/<id>.json}) and a snippet file ({@code snippets/<lang>.json}). Each is a JSON object that
 * carries its version under {@link #KEY}, as the stores of {@link ConfigSchema} do, so that a later format can
 * be told from this one and migrated, and an older Editora can tell a file it does not understand.
 *
 * <p>These files differ from the stores in one way that decides how a newer one is treated: the editor never
 * rewrites them on its own, only when the user saves that template or that language's snippets. So a file from
 * a newer Editora is left where it is, for that newer Editora to keep using, and a copy is kept beside it
 * ({@code <name>.v<n>.bak}) in case the user does save over it from here. It is not loaded.
 */
public final class UserFileVersion {

    private static final Logger LOG = Logger.getLogger(UserFileVersion.class.getName());

    /** The member that carries the version, the same name the stores use. */
    public static final String KEY = "schemaVersion";

    /** A file with no marker is the format every file had before versions were written. */
    public static final int BEFORE_VERSIONS = 1;

    private static final int MAX_COPIES = 99;

    private UserFileVersion() {}

    /** The version {@code tree} carries, or {@link #BEFORE_VERSIONS} when it has no marker. Pure. */
    public static int of(@Nullable JsonNode tree) {
        JsonNode v = tree != null && tree.isObject() ? tree.get(KEY) : null;
        return v != null && v.isIntegralNumber() ? v.asInt() : BEFORE_VERSIONS;
    }

    /**
     * {@code tree} brought from version {@code stored} to the current one by {@code steps}: step {@code i} takes
     * version {@code i + 1} to {@code i + 2}, so the list holds one step for every version below the current
     * one. A version that only added a member uses {@link UnaryOperator#identity()}. Pure.
     */
    public static JsonNode migrate(JsonNode tree, int stored, List<UnaryOperator<JsonNode>> steps) {
        JsonNode at = tree;
        for (int version = Math.max(stored, BEFORE_VERSIONS); version <= steps.size(); version++) {
            at = steps.get(version - 1).apply(at);
        }
        return at;
    }

    /**
     * Keeps a copy of {@code file}, which a newer Editora wrote, beside it as {@code <name>.v<n>.bak}, and leaves
     * the file itself alone. A copy that is already there with the same bytes is the copy: a file that is read
     * again and again is copied once. Null when no copy could be made.
     */
    public static @Nullable Path keepCopyOfNewer(Path file, int storedVersion) {
        try {
            String base = file.getFileName() + ".v" + storedVersion + ".bak";
            for (int i = 1; i <= MAX_COPIES; i++) {
                Path candidate = file.resolveSibling(i == 1 ? base : base + "." + i);
                if (!Files.exists(candidate)) {
                    return Files.copy(file, candidate);
                }
                if (Files.mismatch(file, candidate) < 0) {
                    return candidate;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not keep a copy of the newer file {0}: {1}", new Object[] {file, e});
        }
        return null;
    }
}
