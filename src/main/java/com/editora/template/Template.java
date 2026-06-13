package com.editora.template;

import java.util.List;

/**
 * A file template: an {@code id} (= its file stem; user templates override bundled ones with the same
 * id), a display {@code name}/{@code description}, an optional target {@code language} (used to seed the
 * grammar of the created buffer), and either a <b>single-file</b> body ({@code fileName} pattern +
 * {@code body}) or a <b>multi-file</b> set ({@link #files()}). Bodies/patterns may contain
 * {@code ${variable}} substitutions and a {@code ${cursor}} marker (see {@link TemplateEngine}).
 *
 * <p>Pure data produced by {@link TemplateRegistry}; rendering lives in {@link TemplateEngine}.
 */
public record Template(
        String id,
        String name,
        String description,
        String language,
        String fileName,
        String body,
        List<TemplateFile> files) {

    /** True when this template generates several files (uses {@link #files()} rather than body/fileName). */
    public boolean isMultiFile() {
        return files != null && !files.isEmpty();
    }
}
