package com.editora.lsp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * Pure: the HTML server's (vscode-html-language-server's) settings — its {@code html} configuration section — as
 * Editora answers {@code workspace/configuration} with it, pushes it in {@code workspace/didChangeConfiguration}, and
 * adds the per-document parts to a formatting request.
 *
 * <p>The server has no defaults of its own: VS Code's are in its extension's {@code package.json}, and VS Code always
 * sends them. Answering {@code {}} (as Editora did) left {@code html.format.contentUnformatted} unset, and the
 * server then protects {@code script} alone — Format Document collapsed the spaces in every {@code <pre>} and
 * {@code <textarea>} (HTML review B2). The section here is VS Code's defaults with the user's Settings → LSP → HTML
 * values, the project's {@code html} object (in {@code .editora/settings.json}, VS Code's key names) laid over them,
 * and two values from the document itself:
 *
 * <ul>
 *   <li>{@code format.wrapLineLength}: the file's EditorConfig {@code max_line_length} when it sets one ({@code off}
 *       = never wrap), else the setting (A3, B10);
 *   <li>{@code format.endWithNewline}: the file's {@code insert_final_newline} when it sets one, else whether the text
 *       ends with a line break now — the formatter's own default dropped the newline on every format (A3, D6).
 * </ul>
 *
 * <p>{@code autoClosingTags} is Editora's own auto-close setting: while Editora closes a tag on {@code >}, the server
 * must not offer the closer again in completion, which made {@code <div></div></div>} (D3).
 *
 * <p>The server caches each document's answer until it is closed or the configuration is pushed, so the two
 * per-document values are also sent as options of every formatting request ({@link #formattingOptions}), which the
 * server lays over its settings.
 *
 * @param wrapLineLength the column Format Document wraps at; 0 = never wraps
 * @param wrapAttributes {@code html.format.wrapAttributes}, one of {@link Settings#HTML_WRAP_ATTRIBUTES}
 * @param contentUnformatted the elements whose content Format Document leaves as written, comma-separated
 * @param attributeQuotes {@code html.completion.attributeDefaultValue}, one of {@link Settings#HTML_ATTRIBUTE_QUOTES}
 * @param validateScripts whether embedded scripts are validated
 * @param validateStyles whether embedded styles are validated
 * @param autoClosingTags whether Editora closes tags itself (the server then hides its close-tag proposal)
 * @param project the project's {@code html} object, laid over the rest (only known keys of the right type)
 */
public record HtmlServerSettings(
        int wrapLineLength,
        String wrapAttributes,
        String contentUnformatted,
        String attributeQuotes,
        boolean validateScripts,
        boolean validateStyles,
        boolean autoClosingTags,
        Map<String, Object> project) {

    /** The HTML server's id. */
    public static final String SERVER_ID = "html";

    /** The configuration section it reads. */
    public static final String SECTION = "html";

    private static final HtmlServerSettings DEFAULTS = new HtmlServerSettings(
            Settings.HTML_WRAP_LINE_LENGTH_DEFAULT,
            "auto",
            Settings.HTML_CONTENT_UNFORMATTED_DEFAULT,
            "doublequotes",
            true,
            true,
            true,
            Map.of());

    /**
     * Keys a project may set beyond the ones the section always carries, with a value of their type: the rest of
     * VS Code's {@code html.format.*} that the server reads.
     */
    private static final Map<String, Object> PROJECT_ONLY_FORMAT_KEYS = Map.of(
            "endWithNewline", false, "maxPreserveNewLines", 0, "wrapAttributesIndentSize", 0, "indentScripts", "");

    public HtmlServerSettings {
        wrapLineLength = Math.max(0, wrapLineLength);
        wrapAttributes = Settings.HTML_WRAP_ATTRIBUTES.contains(wrapAttributes) ? wrapAttributes : "auto";
        contentUnformatted = contentUnformatted == null ? "" : Settings.normalizeTagList(contentUnformatted);
        attributeQuotes = Settings.HTML_ATTRIBUTE_QUOTES.contains(attributeQuotes) ? attributeQuotes : "doublequotes";
        project = project == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(project));
    }

    /** VS Code's defaults, with Editora's auto-close on (its default). */
    public static HtmlServerSettings defaults() {
        return DEFAULTS;
    }

    /** The user's HTML settings (Settings → LSP → HTML, and the editor's auto-close-tags switch). */
    public static HtmlServerSettings of(Settings s) {
        return new HtmlServerSettings(
                s.getHtmlFormatWrapLineLength(),
                s.getHtmlFormatWrapAttributes(),
                s.getHtmlFormatContentUnformatted(),
                s.getHtmlAttributeQuotes(),
                s.isHtmlValidateScripts(),
                s.isHtmlValidateStyles(),
                s.isAutoCloseTags(),
                Map.of());
    }

    /** These settings with a project's {@code html} object (null or empty = none) laid over them. */
    public HtmlServerSettings withProject(@Nullable Map<String, Object> projectHtml) {
        return new HtmlServerSettings(
                wrapLineLength,
                wrapAttributes,
                contentUnformatted,
                attributeQuotes,
                validateScripts,
                validateStyles,
                autoClosingTags,
                projectHtml == null ? Map.of() : projectHtml);
    }

    /**
     * Root → settings: {@code project} for a server on {@code projectRoot} (or a folder inside it, or one holding
     * it), {@code user} for every other root — a loose file is not the project's.
     */
    public static Function<Path, HtmlServerSettings> perRoot(
            HtmlServerSettings project, HtmlServerSettings user, @Nullable Path projectRoot) {
        if (projectRoot == null || project.equals(user)) {
            return root -> user;
        }
        Path pr = projectRoot.toAbsolutePath().normalize();
        return root -> {
            Path r = root.toAbsolutePath().normalize();
            return r.getFileSystem() == pr.getFileSystem() && (r.startsWith(pr) || pr.startsWith(r)) ? project : user;
        };
    }

    /**
     * The {@code html} object for one document ({@code doc}: its formatting facts, null = unknown, as in a push):
     * see the class comment for what comes from where.
     */
    public Map<String, Object> section(@Nullable FormattingIndent doc) {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("enable", true);
        format.put("wrapLineLength", wrapLineLength);
        format.put("unformatted", "wbr");
        format.put("contentUnformatted", contentUnformatted);
        format.put("indentInnerHtml", false);
        format.put("preserveNewLines", true);
        format.put("indentHandlebars", false);
        format.put("extraLiners", "head, body, /html");
        format.put("wrapAttributes", wrapAttributes);
        format.put("templating", false);
        format.put("unformattedContentDelimiter", "");
        Map<String, Object> html = new LinkedHashMap<>();
        html.put("format", format);
        html.put("suggest", map("html5", true));
        html.put("validate", map("scripts", validateScripts, "styles", validateStyles));
        html.put("autoClosingTags", autoClosingTags);
        html.put("hover", map("documentation", true, "references", true));
        html.put("completion", map("attributeDefaultValue", attributeQuotes));
        overlay(html, project, true); // into the nested objects: format is still the object above
        if (doc != null) {
            Map<String, Object> f = format;
            Integer wrap = wrapColumn(doc);
            if (wrap != null) {
                f.put("wrapLineLength", wrap);
            }
            Boolean finalNewline = doc.finalNewline();
            if (finalNewline != null && (doc.insertFinalNewline() != null || !f.containsKey("endWithNewline"))) {
                f.put("endWithNewline", finalNewline);
            }
        }
        return html;
    }

    /** The column a document's EditorConfig says to wrap at (0 for {@code off}), or null when it says nothing. */
    private static @Nullable Integer wrapColumn(FormattingIndent doc) {
        Integer max = doc.maxLineLength();
        if (max == null) {
            return null;
        }
        return max < 0 ? 0 : max;
    }

    /**
     * What the HTML server is pushed after {@code initialized} and on every change: the section with no document
     * facts, and empty objects for its embedded CSS and JavaScript (a {@code null} there throws, see
     * {@link LspServerSettings}). A push makes the server forget every document's cached answer and ask again.
     */
    public Map<String, Object> push() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(SECTION, section(null));
        settings.put("css", new LinkedHashMap<String, Object>());
        settings.put("javascript", new LinkedHashMap<String, Object>());
        return settings;
    }

    /**
     * The answer to the HTML server asking {@code workspace/configuration} for {@code section} of the document whose
     * facts are {@code doc} (null = unknown): the {@code html} object (or a value inside it, by dotted path), an empty
     * object for {@code css} and {@code javascript}, and null for anything else — {@code js/ts} included, where an
     * empty object makes its JavaScript mode throw.
     */
    public @Nullable Object answer(@Nullable String section, @Nullable FormattingIndent doc) {
        String s = section == null ? "" : section;
        if (s.equals("css") || s.equals("javascript")) {
            return new LinkedHashMap<String, Object>();
        }
        if (s.equals(SECTION)) {
            return section(doc);
        }
        if (!s.startsWith(SECTION + ".")) {
            return null;
        }
        Object node = section(doc);
        for (String part : s.substring(SECTION.length() + 1).split("\\.")) {
            if (!(node instanceof Map<?, ?> m) || !m.containsKey(part)) {
                return null;
            }
            node = m.get(part);
        }
        return node;
    }

    /**
     * A formatting request's options for the HTML server: {@code indent}'s, plus the document's own wrap column and
     * final newline, which the server lays over the settings it cached for the document — the text may have gained
     * or lost its last line break since, or its {@code .editorconfig} changed.
     */
    public org.eclipse.lsp4j.FormattingOptions formattingOptions(FormattingIndent indent) {
        org.eclipse.lsp4j.FormattingOptions options = indent.options();
        if (section(indent).get("format") instanceof Map<?, ?> format) {
            if (format.get("wrapLineLength") instanceof Number n) {
                options.putNumber("wrapLineLength", n);
            }
            if (format.get("endWithNewline") instanceof Boolean b) {
                options.putBoolean("endWithNewline", b);
            }
        }
        return options;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /**
     * Lays {@code over} onto {@code into}: a key {@code into} has takes a value of the same kind (a nested object is
     * laid over in turn); other keys are dropped, except the extra {@code format} keys a project may add.
     */
    @SuppressWarnings("unchecked")
    private static void overlay(Map<String, Object> into, Map<?, ?> over, boolean top) {
        over.forEach((k, v) -> {
            String key = String.valueOf(k);
            Object have = into.get(key);
            if (have instanceof Map<?, ?> nested && v instanceof Map<?, ?> nestedOver) {
                overlay((Map<String, Object>) nested, nestedOver, false);
                if (top && key.equals("format")) {
                    nestedOver.forEach((fk, fv) -> {
                        Object kind = PROJECT_ONLY_FORMAT_KEYS.get(String.valueOf(fk));
                        if (kind != null && sameKind(kind, fv)) {
                            ((Map<String, Object>) nested).put(String.valueOf(fk), fv);
                        }
                    });
                }
            } else if (have != null && !(have instanceof Map<?, ?>) && sameKind(have, v)) {
                into.put(key, have instanceof Number && v instanceof Number n ? (Object) n.intValue() : v);
            }
        });
    }

    private static boolean sameKind(Object have, @Nullable Object value) {
        return (have instanceof Boolean && value instanceof Boolean)
                || (have instanceof Number && value instanceof Number)
                || (have instanceof String && value instanceof String);
    }
}
