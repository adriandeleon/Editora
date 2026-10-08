package com.editora.template;

import java.util.List;
import java.util.Map;

/**
 * A file template: an {@code id} (= its file stem; a user template overrides a plugin or bundled one with
 * the same id), a display {@code name}/{@code description}, an optional target {@code language} (the
 * grammar of the file a <b>single-file</b> template creates, when it differs from what the file name
 * implies), and either a single-file body ({@code fileName} pattern + {@code body}) or a <b>multi-file</b>
 * set ({@link #files()}). Bodies/patterns may contain {@code ${variable}} substitutions and a
 * {@code ${cursor}} marker (see {@link TemplateEngine} for the exact syntax).
 *
 * <p>{@link #labels()} carries the template's own wizard labels ({@code variable -> label});
 * {@link #origin()} says where it was loaded from. Pure data produced by {@link TemplateRegistry};
 * rendering lives in {@link TemplateEngine}.
 */
public record Template(
        String id,
        String name,
        String description,
        String language,
        String fileName,
        String body,
        List<TemplateFile> files,
        Map<String, String> labels,
        Origin origin,
        String source) {

    /** Where a template was loaded from. Later origins override earlier ones with the same id. */
    public enum Origin {
        BUNDLED,
        PLUGIN,
        USER
    }

    public Template {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        origin = origin == null ? Origin.USER : origin;
        source = source == null ? "" : source;
    }

    /** A user template with no wizard labels — what the Settings form builds. */
    public Template(
            String id,
            String name,
            String description,
            String language,
            String fileName,
            String body,
            List<TemplateFile> files) {
        this(id, name, description, language, fileName, body, files, Map.of(), Origin.USER, "");
    }

    /** True when this template generates several files (uses {@link #files()} rather than body/fileName). */
    public boolean isMultiFile() {
        return files != null && !files.isEmpty();
    }

    /** This template with {@code labels} as its wizard labels (an edit in Settings must not drop them). */
    public Template withLabels(Map<String, String> labels) {
        return new Template(id, name, description, language, fileName, body, files, labels, origin, source);
    }

    /** This template as the user's own copy (same id, so it overrides the original). */
    public Template asUserCopy() {
        return new Template(id, name, description, language, fileName, body, files, labels, Origin.USER, "");
    }
}
