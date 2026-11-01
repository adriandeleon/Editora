package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *       = never wrap), else the setting (A3, B10).
 * </ul>
 *
 * <p>{@code format.endWithNewline} is always {@code false}, VS Code's value: the formatter hands it on to the CSS
 * beautifier of every {@code <style>}, which then put a blank line before each {@code </style>} (HTML review 2,
 * B104). Editora keeps the final newline itself instead ({@link #finalNewline}, applied to the edits by
 * {@link com.editora.text.LspTextEdits#withFinalNewline}): the file's {@code insert_final_newline} when it sets one,
 * else the project's {@code format.endWithNewline}, else what the text has now — the formatter's own default dropped
 * the newline on every format (A3, D6).
 *
 * <p>{@code autoClosingTags} is Editora's own auto-close setting, whatever the project says (B2 D102): while Editora
 * closes a tag on {@code >}, the server must not offer the closer again in completion, which made
 * {@code <div></div></div>} (D3).
 *
 * <p>The embedded {@code javascript} and {@code css} sections are VS Code's {@code format} defaults (B105): with an
 * empty object the server's TypeScript formatter turned off the spaces inside braces
 * ({@code function f() {return 1} }).
 *
 * <p>The project's object takes VS Code's dotted keys too ({@code "format.wrapAttributes": "force"}, and
 * {@code "html.format.wrapAttributes"} at the top of {@code .editora/settings.json}, as {@code .vscode/settings.json}
 * writes them); what it sets that the server does not take is listed by {@link #ignoredProjectKeys} (A107).
 *
 * <p>The server caches each document's answer until it is closed or the configuration is pushed, so the wrap column
 * is also sent as an option of every formatting request ({@link #formattingOptions}), which the server lays over its
 * settings.
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
    private static final Map<String, Object> PROJECT_ONLY_FORMAT_KEYS =
            Map.of("maxPreserveNewLines", 0, "wrapAttributesIndentSize", 0, "indentScripts", "");

    /** VS Code's {@code javascript.format} defaults (its TypeScript extension's {@code package.json}). */
    private static final Map<String, Object> JAVASCRIPT_FORMAT = orderedMap(
            "enable", true,
            "insertSpaceAfterCommaDelimiter", true,
            "insertSpaceAfterConstructor", false,
            "insertSpaceAfterSemicolonInForStatements", true,
            "insertSpaceBeforeAndAfterBinaryOperators", true,
            "insertSpaceAfterKeywordsInControlFlowStatements", true,
            "insertSpaceAfterFunctionKeywordForAnonymousFunctions", true,
            "insertSpaceBeforeFunctionParenthesis", false,
            "insertSpaceAfterOpeningAndBeforeClosingNonemptyParenthesis", false,
            "insertSpaceAfterOpeningAndBeforeClosingNonemptyBrackets", false,
            "insertSpaceAfterOpeningAndBeforeClosingNonemptyBraces", true,
            "insertSpaceAfterOpeningAndBeforeClosingEmptyBraces", true,
            "insertSpaceAfterOpeningAndBeforeClosingTemplateStringBraces", false,
            "insertSpaceAfterOpeningAndBeforeClosingJsxExpressionBraces", false,
            "insertSpaceAfterTypeAssertion", false,
            "placeOpenBraceOnNewLineForFunctions", false,
            "placeOpenBraceOnNewLineForControlBlocks", false,
            "semicolons", "ignore",
            "indentSwitchCase", true);

    /** VS Code's {@code css.format} defaults (its CSS extension's {@code package.json}), for parity. */
    private static final Map<String, Object> CSS_FORMAT = orderedMap(
            "enable", true,
            "newlineBetweenSelectors", true,
            "newlineBetweenRules", true,
            "spaceAroundSelectorSeparator", false,
            "braceStyle", "collapse",
            "preserveNewLines", true);

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
        overlay(html, projectTree(), "", null); // into the nested objects: format is still the object above
        html.put("autoClosingTags", autoClosingTags); // Editora's own, never the project's (D102)
        format.put("endWithNewline", false); // VS Code's: Editora keeps the final newline itself (B104)
        if (doc != null) {
            Integer wrap = wrapColumn(doc);
            if (wrap != null) {
                format.put("wrapLineLength", wrap);
            }
        }
        return html;
    }

    /**
     * Whether a formatted document should end with a line break (null: leave it as the server made it, for a request
     * that knows nothing of the text): the file's {@code insert_final_newline} when it sets one, else the project's
     * {@code format.endWithNewline}, else whether the text ends with one now.
     */
    public @Nullable Boolean finalNewline(FormattingIndent doc) {
        if (doc.endsWithNewline() == null) {
            return null; // nothing known of the text: Tab re-indent, on-type formatting
        }
        if (doc.insertFinalNewline() != null) {
            return doc.insertFinalNewline();
        }
        return projectTree().get("format") instanceof Map<?, ?> f && f.get("endWithNewline") instanceof Boolean b
                ? b
                : doc.endsWithNewline();
    }

    /**
     * What the project's object sets that the server is not given, as dotted names ({@code format.wrapAtributes}):
     * unknown keys, values of the wrong kind, and {@code autoClosingTags}, which follows Editora's own setting.
     */
    public List<String> ignoredProjectKeys() {
        List<String> ignored = new ArrayList<>();
        Map<String, Object> tree = projectTree();
        if (tree.isEmpty()) {
            return List.of();
        }
        Map<String, Object> scratch = new HtmlServerSettings(
                        wrapLineLength,
                        wrapAttributes,
                        contentUnformatted,
                        attributeQuotes,
                        validateScripts,
                        validateStyles,
                        autoClosingTags,
                        Map.of())
                .section(null);
        overlay(scratch, tree, "", ignored);
        return List.copyOf(ignored);
    }

    /** The project's object with VS Code's dotted keys ({@code "format.wrapAttributes"}) made nested. */
    private Map<String, Object> projectTree() {
        return project.isEmpty() ? Map.of() : unflatten(project);
    }

    /** {@code flat} with every dotted key turned into nested objects, merged with any written nested already. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unflatten(Map<?, ?> flat) {
        Map<String, Object> out = new LinkedHashMap<>();
        flat.forEach((k, v) -> {
            String[] parts = String.valueOf(k).split("\\.", -1);
            Map<String, Object> at = out;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = at.get(parts[i]);
                if (!(next instanceof Map<?, ?>)) {
                    next = new LinkedHashMap<String, Object>();
                    at.put(parts[i], next);
                }
                at = (Map<String, Object>) next;
            }
            String last = parts[parts.length - 1];
            Object value = v instanceof Map<?, ?> nested ? unflatten(nested) : v;
            if (at.get(last) instanceof Map<?, ?> have && value instanceof Map<?, ?> more) {
                ((Map<String, Object>) have).putAll((Map<String, Object>) more);
            } else {
                at.put(last, value);
            }
        });
        return out;
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
        settings.put("css", embedded("css"));
        settings.put("javascript", embedded("javascript"));
        return settings;
    }

    /**
     * The answer to the HTML server asking {@code workspace/configuration} for {@code section} of the document whose
     * facts are {@code doc} (null = unknown): the {@code html} object (or a value inside it, by dotted path), VS Code's
     * {@code format} defaults for {@code css} and {@code javascript}, and null for anything else — {@code js/ts}
     * included, where an empty object makes its JavaScript mode throw.
     */
    public @Nullable Object answer(@Nullable String section, @Nullable FormattingIndent doc) {
        String s = section == null ? "" : section;
        if (s.equals("css") || s.equals("javascript")) {
            return embedded(s);
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
     * A formatting request's options for the HTML server: {@code indent}'s, plus the document's own wrap column, which
     * the server lays over the settings it cached for the document — its {@code .editorconfig} may have changed
     * since — and {@code endWithNewline: false} (B104: Editora keeps the final newline itself).
     */
    public org.eclipse.lsp4j.FormattingOptions formattingOptions(FormattingIndent indent) {
        org.eclipse.lsp4j.FormattingOptions options = indent.options();
        if (section(indent).get("format") instanceof Map<?, ?> format
                && format.get("wrapLineLength") instanceof Number n) {
            options.putNumber("wrapLineLength", n);
        }
        options.putBoolean("endWithNewline", false);
        return options;
    }

    /** The {@code css} or {@code javascript} section: VS Code's {@code format} defaults, a fresh copy. */
    private static Map<String, Object> embedded(String section) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", new LinkedHashMap<>(section.equals("css") ? CSS_FORMAT : JAVASCRIPT_FORMAT));
        return out;
    }

    private static Map<String, Object> orderedMap(Object... keyValues) {
        return java.util.Collections.unmodifiableMap(map(keyValues));
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /**
     * Lays {@code over} onto {@code into} ({@code path}: where {@code into} is, {@code ""} at the top): a key
     * {@code into} has takes a value of the same kind (a nested object is laid over in turn), as do the extra
     * {@code format} keys a project may add; every other key is dropped, and named in {@code ignored} when given.
     * {@code format.endWithNewline} is Editora's to apply ({@link #finalNewline}), and {@code autoClosingTags} is
     * replaced by Editora's own value after this.
     */
    @SuppressWarnings("unchecked")
    private static void overlay(Map<String, Object> into, Map<?, ?> over, String path, @Nullable List<String> ignored) {
        over.forEach((k, v) -> {
            String key = String.valueOf(k);
            String name = path + key;
            Object have = into.get(key);
            if (name.equals("format.endWithNewline") && v instanceof Boolean) {
                return; // taken by finalNewline
            }
            if (have instanceof Map<?, ?> nested && v instanceof Map<?, ?> nestedOver) {
                Map<String, Object> extra = new LinkedHashMap<>();
                if (name.equals("format")) {
                    nestedOver.forEach((fk, fv) -> {
                        Object kind = PROJECT_ONLY_FORMAT_KEYS.get(String.valueOf(fk));
                        if (kind != null && sameKind(kind, fv)) {
                            extra.put(String.valueOf(fk), fv);
                        }
                    });
                }
                Map<Object, Object> rest = new LinkedHashMap<>(nestedOver);
                rest.keySet().removeAll(extra.keySet());
                overlay((Map<String, Object>) nested, rest, name + ".", ignored);
                ((Map<String, Object>) nested).putAll(extra);
            } else if (have != null
                    && !(have instanceof Map<?, ?>)
                    && sameKind(have, v)
                    && !name.equals("autoClosingTags")) {
                into.put(key, have instanceof Number && v instanceof Number n ? (Object) n.intValue() : v);
            } else if (ignored != null) {
                ignored.add(name);
            }
        });
    }

    private static boolean sameKind(Object have, @Nullable Object value) {
        return (have instanceof Boolean && value instanceof Boolean)
                || (have instanceof Number && value instanceof Number)
                || (have instanceof String && value instanceof String);
    }
}
