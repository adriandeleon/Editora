package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * Pure: the XML language server's (lemminx's) settings — its {@code xml} configuration object — as Editora sends
 * them in {@code initializationOptions.settings}, in a {@code workspace/didChangeConfiguration} push, and in
 * answer to {@code workspace/configuration}.
 *
 * <p>Until these were sent lemminx ran entirely on its own defaults (XML review D3): it downloaded every schema,
 * DTD and RelaxNG grammar a file named over the network, http included (B2); it kept them in the passwd home's
 * {@code ~/.lemminx}, out of Editora's reach (D4); catalogs and file associations could not be used (D2); every
 * grammar-less file got a "No grammar constraints" hint (B6); and Format Document wrapped text at 100 columns and
 * wrote {@code <a />} with no way to change either (B7, C9).
 *
 * <p>One object serves every server on one root: {@link #section} decides what depends on that root — whether
 * schema downloads are on ({@code trusted} follows the folder's trust, as VS Code's workspace trust does) and
 * where a relative catalog or grammar path points.
 *
 * @param schemaDownloads {@code never}, {@code trusted} or {@code always}
 * @param noGrammar lemminx's {@code xml.validation.noGrammar}: {@code ignore}, {@code hint} or {@code warning}
 * @param catalogs OASIS catalog paths; a relative one is resolved under the server's root and sent only when it
 *     exists there
 * @param fileAssociations glob → grammar (a path, relative to the root when not absolute, or a URL)
 * @param maxLineWidth the column Format Document wraps text at; 0 = never wraps
 * @param symbolLimit {@code xml.symbols.maxItemsComputed}
 * @param workDir lemminx's {@code xml.server.workDir}, under which it keeps its {@code cache}; null = its own
 *     default ({@code ~/.lemminx})
 */
public record XmlServerSettings(
        String schemaDownloads,
        String noGrammar,
        List<String> catalogs,
        Map<String, String> fileAssociations,
        int maxLineWidth,
        String splitAttributes,
        String emptyElements,
        boolean spaceBeforeEmptyCloseTag,
        int symbolLimit,
        @Nullable Path workDir) {

    /** The configuration section lemminx reads. */
    public static final String SECTION = "xml";

    /** The plain XML server's id. */
    public static final String SERVER_ID = "xml";

    /** Whether {@code serverId} is a lemminx (the native XML server, or the JVM one with lemminx-maven for poms). */
    public static boolean isLemminx(@Nullable String serverId) {
        return SERVER_ID.equals(serverId) || LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId);
    }

    public XmlServerSettings {
        schemaDownloads = Settings.XML_SCHEMA_DOWNLOADS.contains(schemaDownloads) ? schemaDownloads : "trusted";
        noGrammar = Settings.XML_NO_GRAMMAR.contains(noGrammar) ? noGrammar : "ignore";
        catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
        fileAssociations = fileAssociations == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fileAssociations));
        maxLineWidth = Math.max(0, maxLineWidth);
        splitAttributes = Settings.XML_SPLIT_ATTRIBUTES.contains(splitAttributes) ? splitAttributes : "preserve";
        emptyElements = Settings.XML_EMPTY_ELEMENTS.contains(emptyElements) ? emptyElements : "ignore";
        symbolLimit = symbolLimit < 1 ? Settings.XML_SYMBOL_LIMIT_DEFAULT : symbolLimit;
    }

    /** The defaults of a fresh {@link Settings}, with no work dir. */
    public static XmlServerSettings defaults() {
        return of(new Settings(), null);
    }

    /**
     * The user's XML settings; {@code configDir} (null = none) places lemminx's cache under
     * {@link #cacheDir}. Wrapping uses the editor's fill column, the column Fill Paragraph wraps at.
     */
    public static XmlServerSettings of(Settings s, @Nullable Path configDir) {
        return new XmlServerSettings(
                s.getXmlSchemaDownloads(),
                s.getXmlNoGrammar(),
                s.getXmlCatalogs(),
                s.getXmlFileAssociations(),
                s.isXmlFormatWrap() ? s.getFillColumn() : 0,
                s.getXmlFormatSplitAttributes(),
                s.getXmlFormatEmptyElements(),
                s.isXmlFormatSpaceBeforeEmptyCloseTag(),
                s.getXmlSymbolLimit(),
                configDir == null ? null : cacheDir(configDir));
    }

    /**
     * Where lemminx works and keeps its downloaded grammars, under Editora's config folder: settings export
     * skips {@code cache/}, and Doctor shows its size with a Clear action. lemminx puts them in a {@code cache}
     * folder of its own inside.
     */
    public static Path cacheDir(Path configDir) {
        return configDir.resolve("cache").resolve("lemminx");
    }

    /** Whether a file whose root is trusted ({@code trusted}) may have its grammars downloaded. */
    public boolean downloads(boolean trusted) {
        return switch (schemaDownloads) {
            case "always" -> true;
            case "never" -> false;
            default -> trusted;
        };
    }

    /**
     * The {@code xml} object for a server on {@code root} (null = unknown): {@code trusted} is that folder's trust,
     * {@code local} whether the server runs on this machine (a remote one keeps its own work dir, and relative
     * paths are sent as typed: this machine cannot look at the host's disk).
     */
    public Map<String, Object> section(@Nullable Path root, boolean trusted, boolean local) {
        Map<String, Object> xml = new LinkedHashMap<>();
        xml.put("downloadExternalResources", Map.of("enabled", downloads(trusted)));
        // Explicit, though it is lemminx's default: an external entity would be another fetch (or a local read).
        xml.put("validation", Map.of("noGrammar", noGrammar, "resolveExternalEntities", false));
        List<String> resolvedCatalogs = new ArrayList<>();
        // Editora's own grammars first (the POM schemas, D12), so a pom validates and completes with downloads off.
        Path bundled = local ? XmlBundledSchemas.catalogBeside(workDir) : null;
        if (bundled != null) {
            resolvedCatalogs.add(bundled.toString());
        }
        for (String catalog : catalogs) {
            String path = resolve(catalog, root, local, true);
            if (path != null) {
                resolvedCatalogs.add(path);
            }
        }
        xml.put("catalogs", resolvedCatalogs);
        List<Map<String, String>> associations = new ArrayList<>();
        fileAssociations.forEach((pattern, grammar) -> {
            String systemId = resolve(grammar, root, local, false);
            if (systemId != null) {
                associations.add(Map.of("pattern", globPattern(pattern), "systemId", systemId));
            }
        });
        xml.put("fileAssociations", associations);
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("maxLineWidth", maxLineWidth);
        format.put("splitAttributes", splitAttributes);
        format.put("emptyElements", emptyElements);
        format.put("spaceBeforeEmptyCloseTag", spaceBeforeEmptyCloseTag);
        xml.put("format", format);
        // Lenses are asked for only while Editora's own Code Lens setting is on, so the server side stays on: off
        // (lemminx's default) meant the XSD/DTD reference lenses never came at all (D3).
        xml.put("codeLens", Map.of("enabled", true));
        // showReferencedGrammars off: no "Grammars" pseudo-rows at the top of Structure (XML review B13).
        xml.put("symbols", Map.of("maxItemsComputed", symbolLimit, "showReferencedGrammars", false));
        if (local && workDir != null) {
            xml.put("server", Map.of("workDir", workDir.toString()));
        }
        return xml;
    }

    /**
     * A file pattern as lemminx matches it. lemminx compares the glob with the whole path, so {@code *.myconf.xml}
     * matched nothing (probed against lemminx 0.31), and a relative folder pattern would be read against the server's
     * root, which for a file outside the open project is Editora's shared loose-files folder. So a pattern that is
     * not already absolute or {@code **}-led matches at any depth: {@code *.myconf.xml} → {@code **}{@code /*.myconf.xml},
     * {@code conf/*.xml} → {@code **}{@code /conf/*.xml}.
     */
    static String globPattern(String pattern) {
        String p = pattern.strip().replace('\\', '/');
        boolean absolute = p.startsWith("/") || p.matches("^[A-Za-z]:/.*");
        return absolute || p.startsWith("**") ? p : "**/" + p;
    }

    /**
     * {@code value} as sent: a URL or an absolute path unchanged; a relative path under {@code root} (a catalog
     * only when it exists there, so one global "catalog.xml" entry serves every project that has one), or as
     * typed when it cannot be looked at. Null when it is to be left out.
     */
    private static @Nullable String resolve(String value, @Nullable Path root, boolean local, boolean mustExist) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return null;
        }
        if (isUrl(v) || !local) {
            return v;
        }
        try {
            Path path = Path.of(expandHome(v));
            if (path.isAbsolute()) {
                return path.toString();
            }
            if (root == null) {
                return mustExist ? null : v;
            }
            Path under = root.resolve(path).normalize();
            return !mustExist || Files.isRegularFile(under) ? under.toString() : null;
        } catch (InvalidPathException e) {
            return mustExist ? null : v;
        }
    }

    private static String expandHome(String v) {
        if (v.equals("~") || v.startsWith("~/")) {
            return System.getProperty("user.home", "") + v.substring(1);
        }
        return v;
    }

    private static boolean isUrl(String v) {
        String lower = v.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:");
    }

    /**
     * The answer to lemminx asking {@code workspace/configuration} for {@code section} of the {@code xml} object
     * {@code xml}: {@code xml.format.tabSize} / {@code insertSpaces} from the document's own indentation
     * ({@code indent}, null = unknown) — lemminx asks for them before completion and code actions, and a null
     * answer gave generated text two-space indents in a four-space file (B12) — else the value at that path,
     * else null (the server keeps its default).
     */
    public static @Nullable Object answer(
            @Nullable Map<String, Object> xml, @Nullable String section, @Nullable FormattingIndent indent) {
        String s = section == null ? "" : section;
        if (indent != null) {
            if (s.equals("xml.format.tabSize")) {
                return indent.tabSize();
            }
            if (s.equals("xml.format.insertSpaces")) {
                return indent.insertSpaces();
            }
        }
        if (xml == null) {
            return null;
        }
        if (s.equals(SECTION)) {
            return xml;
        }
        if (!s.startsWith(SECTION + ".")) {
            return null;
        }
        Object node = xml;
        for (String part : s.substring(SECTION.length() + 1).split("\\.")) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }
            node = map.get(part);
        }
        return node;
    }

    /**
     * {@code options} (a server's {@code initializationOptions}, null = none) with {@code xml} laid over its
     * {@code settings.xml}: lemminx-maven's own {@code xml.maven} object is kept.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withSection(@Nullable Object options, Map<String, Object> xml) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (options instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        if (out.get("settings") instanceof Map<?, ?> s) {
            s.forEach((k, v) -> settings.put(String.valueOf(k), v));
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (settings.get(SECTION) instanceof Map<?, ?> existing) {
            merged.putAll((Map<String, Object>) existing);
        }
        merged.putAll(xml);
        settings.put(SECTION, merged);
        out.put("settings", settings);
        return out;
    }

    /** Parses Settings' association text, {@code pattern=grammar; pattern=grammar}; malformed pairs are dropped. */
    public static Map<String, String> parseAssociations(@Nullable String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String pair : text.split("[;\\n]")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String pattern = pair.substring(0, eq).strip();
            String grammar = pair.substring(eq + 1).strip();
            if (!pattern.isEmpty() && !grammar.isEmpty()) {
                out.put(pattern, grammar);
            }
        }
        return out;
    }

    /** The text {@link #parseAssociations} reads back. */
    public static String formatAssociations(Map<String, String> associations) {
        List<String> pairs = new ArrayList<>();
        associations.forEach((pattern, grammar) -> pairs.add(pattern + "=" + grammar));
        return String.join("; ", pairs);
    }

    /** Parses Settings' catalog text: paths separated by {@code ;} or new lines. */
    public static List<String> parseCatalogs(@Nullable String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String part : text.split("[;\\n]")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }
}
