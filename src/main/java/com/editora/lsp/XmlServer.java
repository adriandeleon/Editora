package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

/**
 * What Editora does differently for the plain XML server, lemminx (XML review A7, A8, A10, B5, B10, D6, B15): the
 * language id a DTD or stylesheet opens under, the one root every loose XML file shares, the protocol extensions
 * Editora declares ({@code extendedClientCapabilities}), the client commands lemminx may ask for, and Go to
 * Definition's XML fallbacks — lemminx's {@code definition} answers an element with its own other tag, while its
 * {@code typeDefinition} goes to the XSD/DTD declaration and its {@code documentLink} names the schema a
 * {@code schemaLocation}, {@code DOCTYPE} system id or {@code xml-model} href refers to; the stylesheet an
 * {@code xsl:include} or {@code xsl:import} names, which lemminx does not link, Editora resolves itself.
 *
 * <p>Pure apart from {@link #definition}, which composes a session's requests.
 */
public final class XmlServer {

    /** The plain XML server's id in {@link LspServerRegistry}. */
    public static final String SERVER_ID = "xml";

    /**
     * Lines up to which an XML file in the large-source tier keeps lemminx: it validated and outlined a 60,000-line
     * document in under a second with no measurable typing lag (XML review A10). The 5 MB large-file cut still
     * applies first, so in practice the limit is "up to 100,000 lines and 5 MB" (XML review 2 B5).
     */
    public static final int HEAVY_FILE_LINE_LIMIT = 100_000;

    /** Server command: revalidate one document ({@code [{uri}]}). */
    public static final String VALIDATE_CURRENT = "xml.validation.current.file";

    /** Server command: revalidate every open document and forget cached grammars. */
    public static final String VALIDATE_ALL = "xml.validation.all.files";

    /** Server command: the edit binding a document to a grammar ({@code [{uri}, grammarUri, bindingType]}). */
    public static final String ASSOCIATE_GRAMMAR = "xml.associate.grammar.insert";

    /** Client command (code lens, client command request): references at {@code [uri, position]}. */
    public static final String SHOW_REFERENCES = "xml.show.references";

    /** Client command: open {@code [uri]}, a file or a web page. */
    public static final String OPEN_URI = "xml.open.uri";

    /** Client command (quick fix "Bind to existing grammar/schema", code lens): bind {@code [documentUri]}. */
    public static final String BINDING_WIZARD = "xml.open.binding.wizard";

    /** The client commands Editora carries out for lemminx. */
    public static final Set<String> CLIENT_COMMANDS = Set.of(SHOW_REFERENCES, OPEN_URI, BINDING_WIZARD);

    /** Binding by the document's own syntax: {@code xsi:schemaLocation}/{@code noNamespaceSchemaLocation}, DOCTYPE. */
    public static final String BINDING_STANDARD = "standard";

    /** Binding with an {@code <?xml-model href="…"?>} processing instruction (any grammar, RELAX NG included). */
    public static final String BINDING_XML_MODEL = "xml-model";

    private XmlServer() {}

    /**
     * The diagnostic lemminx reports on a document without a grammar, at the root element's name {@code range}: sent
     * as a code-action context, it brings lemminx's generate-a-grammar fixes even while "Files without a grammar" is
     * Nothing and the real one is never reported (XML review 2 C8).
     */
    public static org.eclipse.lsp4j.Diagnostic noGrammarDiagnostic(Range range) {
        var d = new org.eclipse.lsp4j.Diagnostic(range, "No grammar constraints (DTD or XML Schema).");
        d.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Hint);
        d.setSource("xml");
        d.setCode(XmlGrammarBinding.NO_GRAMMAR_CODE);
        return d;
    }

    /**
     * Whether a code action from lemminx generates a grammar for a document without one: a fix for its
     * {@link XmlGrammarBinding#NO_GRAMMAR_CODE} diagnostic that is not "Bind to existing grammar/schema" (a client
     * command, which XML: Bind to Grammar… already is).
     */
    public static boolean generatesGrammar(@Nullable Object raw) {
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action) || action.getDiagnostics() == null) {
            return false;
        }
        if (action.getCommand() != null
                && BINDING_WIZARD.equals(action.getCommand().getCommand())) {
            return false;
        }
        for (org.eclipse.lsp4j.Diagnostic d : action.getDiagnostics()) {
            if (d != null
                    && d.getCode() != null
                    && d.getCode().isLeft()
                    && XmlGrammarBinding.NO_GRAMMAR_CODE.equals(d.getCode().getLeft())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a generate fix binds its grammar with a DOCTYPE (lemminx's {@code GenerateDocTypeCodeActionResolver},
     * or its title when resolved in place): not for a document that has a DOCTYPE already (XML/pom review 3, C24).
     */
    public static boolean bindsWithDoctype(@Nullable Object raw) {
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action)) {
            return false;
        }
        String title = action.getTitle();
        return String.valueOf(action.getData()).contains("GenerateDocTypeCodeActionResolver")
                || (title != null && title.endsWith(" and bind with DOCTYPE"));
    }

    /** Whether {@code serverId} is the plain XML server. */
    public static boolean isXml(@Nullable String serverId) {
        return SERVER_ID.equals(serverId);
    }

    /**
     * The language id vscode-xml opens {@code fileName} under: {@code dtd} for a DTD and its entity/module files,
     * {@code xsl} for a stylesheet, else {@code xml}.
     */
    public static String languageId(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".dtd") || name.endsWith(".ent") || name.endsWith(".mod")) {
            return "dtd";
        }
        return name.endsWith(".xsl") || name.endsWith(".xslt") ? "xsl" : "xml";
    }

    /**
     * The workspace root of {@code file} on the XML server: the open project when the file is in it, else the one
     * shared loose root — lemminx keeps no project model, so one process per window serves every XML file outside
     * the project instead of one per folder or Maven module (XML review A7). Null when there is no loose root yet.
     */
    public static @Nullable Path sharedRoot(@Nullable Path projectRoot, Path file, @Nullable Path looseRoot) {
        if (projectRoot != null) {
            Path project = projectRoot.toAbsolutePath().normalize();
            Path resolved = RootResolver.resolve(projectRoot, file, List.of());
            if (project.equals(resolved)) {
                return project;
            }
        }
        return looseRoot;
    }

    /**
     * lemminx's {@code initializationOptions.extendedClientCapabilities}: reference-count code lenses on XSD/DTD
     * declarations, and the "Bind to existing grammar/schema" quick fix (whose client command Editora carries out).
     * The association lenses are left out: their commands are display-only. No {@code xml.open.settings}: Editora
     * cannot open a setting by vscode-xml's name, so lemminx leaves that button off its notifications.
     */
    public static Map<String, Object> extendedClientCapabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("codeLens", Map.of("codeLensKind", Map.of("valueSet", List.of("references"))));
        caps.put("actionableNotificationSupport", true);
        caps.put("openSettingsCommandSupport", false);
        caps.put("bindingWizardSupport", true);
        caps.put("shouldLanguageServerExitOnShutdown", true);
        return caps;
    }

    /**
     * How a document can be bound to the grammar {@code grammarFileName}: an XSD or DTD by the document's own
     * syntax or by {@code xml-model}; a RELAX NG (or anything else) only by {@code xml-model} — lemminx's
     * "standard" binding writes a DOCTYPE for any grammar it does not know as a schema.
     */
    public static List<String> bindingTypes(String grammarFileName) {
        String name = grammarFileName == null ? "" : grammarFileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".xsd") || name.endsWith(".dtd")
                ? List.of(BINDING_STANDARD, BINDING_XML_MODEL)
                : List.of(BINDING_XML_MODEL);
    }

    /**
     * Reads {@link #ASSOCIATE_GRAMMAR}'s answer, a {@code TextDocumentEdit}, as a workspace edit; null when it is
     * not one.
     */
    static org.eclipse.lsp4j.@Nullable WorkspaceEdit associateEdit(@Nullable Object result) {
        JsonElement json = LspManager.asJson(result);
        if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("edits")) {
            return null;
        }
        org.eclipse.lsp4j.TextDocumentEdit edit;
        try {
            edit = LanguageServerSession.LSP_GSON.fromJson(json, org.eclipse.lsp4j.TextDocumentEdit.class);
        } catch (RuntimeException e) {
            return null;
        }
        if (edit == null || edit.getTextDocument() == null || edit.getEdits() == null) {
            return null;
        }
        var we = new org.eclipse.lsp4j.WorkspaceEdit();
        we.setDocumentChanges(List.of(Either.forLeft(edit)));
        return we;
    }

    /**
     * Whether every target of a definition is the other tag of the element at {@code pos} in the same document —
     * lemminx's answer on a tag name, which moves the caret a few columns and looks like nothing happened (XML
     * review B10). {@code text} is the document; {@code targets} are ranges in it ({@code null} for a target in
     * another document).
     */
    static boolean onlyPairedTag(@Nullable String text, Position pos, List<@Nullable Range> targets) {
        if (text == null || targets.isEmpty()) {
            return false;
        }
        String name = tagNameAt(text, pos);
        if (name == null) {
            return false;
        }
        for (Range r : targets) {
            if (r == null || !name.equals(tagNameAt(text, r.getStart()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The name of the start or end tag whose name {@code pos} is in ({@code <a:b} / {@code </a:b}), or null when the
     * position is not on a tag name.
     */
    static @Nullable String tagNameAt(String text, Position pos) {
        int at = offset(text, pos);
        if (at < 0) {
            return null;
        }
        int start = at;
        while (start > 0 && isNameChar(text.charAt(start - 1))) {
            start--;
        }
        int end = at;
        while (end < text.length() && isNameChar(text.charAt(end))) {
            end++;
        }
        if (start == end) {
            return null;
        }
        int lt = start - 1;
        if (lt >= 0 && text.charAt(lt) == '/') {
            lt--;
        }
        return lt >= 0 && text.charAt(lt) == '<' ? text.substring(start, end) : null;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
    }

    /** The offset of {@code pos} in {@code text} ({@code \n} lines), or -1 when it is outside. */
    static int offset(String text, Position pos) {
        int line = pos.getLine();
        int at = 0;
        for (int i = 0; i < line; i++) {
            int nl = text.indexOf('\n', at);
            if (nl < 0) {
                return -1;
            }
            at = nl + 1;
        }
        int lineEnd = text.indexOf('\n', at);
        int end = lineEnd < 0 ? text.length() : lineEnd;
        int off = at + pos.getCharacter();
        return pos.getCharacter() < 0 || off > end ? -1 : off;
    }

    /** The link whose range holds {@code pos} (resolved or not), or null. */
    static @Nullable DocumentLink linkAt(@Nullable List<DocumentLink> links, Position pos) {
        if (links == null) {
            return null;
        }
        for (DocumentLink link : links) {
            if (link != null && link.getRange() != null && contains(link.getRange(), pos)) {
                return link;
            }
        }
        return null;
    }

    private static boolean contains(Range r, Position p) {
        return compare(r.getStart(), p) <= 0 && compare(p, r.getEnd()) <= 0;
    }

    private static int compare(Position a, Position b) {
        int byLine = Integer.compare(a.getLine(), b.getLine());
        return byLine != 0 ? byLine : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    /** The ranges of a definition answer's targets in {@code key}'s document (null for a target elsewhere). */
    private static List<@Nullable Range> sameDocumentRanges(
            Either<List<? extends Location>, List<? extends LocationLink>> found, WireUris uris, String key) {
        List<@Nullable Range> out = new ArrayList<>();
        if (found == null) {
            return out;
        }
        if (found.isLeft() && found.getLeft() != null) {
            for (Location l : found.getLeft()) {
                if (l != null && l.getRange() != null) {
                    out.add(l.getUri() != null && key.equals(uris.toKey(l.getUri())) ? l.getRange() : null);
                }
            }
        } else if (found.isRight() && found.getRight() != null) {
            for (LocationLink l : found.getRight()) {
                Range r = l == null
                        ? null
                        : l.getTargetSelectionRange() != null ? l.getTargetSelectionRange() : l.getTargetRange();
                if (r != null) {
                    out.add(l.getTargetUri() != null && key.equals(uris.toKey(l.getTargetUri())) ? r : null);
                }
            }
        }
        return out;
    }

    private static boolean isEmpty(@Nullable Either<List<? extends Location>, List<? extends LocationLink>> found) {
        if (found == null) {
            return true;
        }
        List<?> list = found.isLeft() ? found.getLeft() : found.getRight();
        return list == null || list.isEmpty();
    }

    /**
     * Go to Definition on the XML server (XML review B5, B10): lemminx's {@code definition}; when that finds nothing
     * or only the element's own other tag, the grammar declaration ({@code typeDefinition}) instead; and when that
     * finds nothing either, the file a document link at the position names — the schema of a {@code schemaLocation},
     * the DTD of a DOCTYPE, an {@code xml-model} href. Last, on the {@code href} of an {@code xsl:include} or
     * {@code xsl:import}, the stylesheet it names: lemminx has no link for those (XML review 2 B3). A link to a web
     * page is not followed (there is no file to open); the definition's own answer then stands.
     */
    static CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            LanguageServerSession s, String key, Position pos) {
        return s.definition(key, pos).thenCompose(found -> {
            String text = s.documentSnapshots().get(key);
            if (!isEmpty(found) && !onlyPairedTag(text, pos, sameDocumentRanges(found, s.uris(), key))) {
                return CompletableFuture.completedFuture(found);
            }
            return s.typeDefinition(key, pos)
                    .handle((types, error) -> error == null ? types : null)
                    .thenCompose(types -> {
                        if (!isEmpty(types)) {
                            return CompletableFuture.completedFuture(types);
                        }
                        return linkLocation(s, key, pos).thenApply(link -> {
                            Location to = link != null ? link : includeLocation(s, key, pos);
                            return to == null
                                    ? found
                                    : Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                                            List.of(to));
                        });
                    });
        });
    }

    /** The document link at {@code pos} as a location at the start of its file (resolved when it must be), or null. */
    private static CompletableFuture<@Nullable Location> linkLocation(
            LanguageServerSession s, String key, Position pos) {
        return s.documentLinks(key)
                .thenCompose(links -> {
                    DocumentLink link = linkAt(links, pos);
                    if (link == null) {
                        return CompletableFuture.<@Nullable DocumentLink>completedFuture(null);
                    }
                    return link.getTarget() != null
                            ? CompletableFuture.<@Nullable DocumentLink>completedFuture(link)
                            : s.resolveDocumentLink(link);
                })
                .<@Nullable Location>thenApply(link -> {
                    String target = link == null ? null : link.getTarget();
                    if (target == null || s.uris().toPath(target) == null) {
                        return null; // a web page or another scheme: nothing to open as a file
                    }
                    return new Location(target, new Range(new Position(0, 0), new Position(0, 0)));
                })
                .exceptionally(t -> null);
    }

    /** An {@code href} attribute's value, quoted either way, with its value group. */
    private static final java.util.regex.Pattern HREF =
            java.util.regex.Pattern.compile("\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /**
     * The {@code href} value {@code offset} is in, when it is the {@code href} of an {@code include} or
     * {@code import} element with a prefix — {@code xsl:include}, {@code xsl:import}, XInclude's {@code xi:include}
     * — or without one in the XInclude or XSLT namespace ({@code <include xmlns="http://www.w3.org/2001/XInclude">},
     * XML/pom review 3, C26); else null. An XSD's {@code xs:include} names its file by {@code schemaLocation}, which
     * lemminx links itself.
     */
    static @Nullable String includeHref(String text, int offset) {
        if (offset < 0 || offset > text.length()) {
            return null;
        }
        int lt = text.lastIndexOf('<', Math.max(0, offset - 1));
        int gt = lt < 0 ? -1 : text.indexOf('>', lt);
        if (lt < 0 || (gt >= 0 && gt < offset)) {
            return null;
        }
        int tagEnd = gt < 0 ? text.length() : gt;
        int nameEnd = lt + 1;
        while (nameEnd < tagEnd && isNameChar(text.charAt(nameEnd))) {
            nameEnd++;
        }
        String name = text.substring(lt + 1, nameEnd);
        int colon = name.indexOf(':');
        String local = name.substring(colon + 1);
        if (colon == 0 || !(local.equals("include") || local.equals("import"))) {
            return null;
        }
        if (colon < 0 && !INCLUDE_NAMESPACES.contains(defaultNamespace(text, lt, tagEnd))) {
            return null; // an unprefixed include of some other vocabulary
        }
        java.util.regex.Matcher m = HREF.matcher(text).region(nameEnd, tagEnd);
        while (m.find()) {
            int group = m.group(1) != null ? 1 : 2;
            if (m.start(group) <= offset && offset <= m.end(group)) {
                String href = m.group(group).trim();
                return href.isEmpty() ? null : href;
            }
        }
        return null;
    }

    /** The namespaces whose unprefixed {@code include}/{@code import} names a file by {@code href}. */
    private static final java.util.Set<String> INCLUDE_NAMESPACES = java.util.Set.of(
            "http://www.w3.org/2001/XInclude",
            "http://www.w3.org/2003/XInclude",
            "http://www.w3.org/1999/XSL/Transform");

    /** A default namespace declaration ({@code xmlns="…"}), quoted either way. */
    private static final java.util.regex.Pattern DEFAULT_NS =
            java.util.regex.Pattern.compile("\\sxmlns\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /**
     * The default namespace in scope at the tag starting at {@code lt}: its own {@code xmlns}, else that of the
     * nearest open ancestor that declares one, else "". A light scan of the tags before it (comments, CDATA,
     * processing instructions and declarations skipped); only asked on an unprefixed include.
     */
    static String defaultNamespace(String text, int lt, int tagEnd) {
        String own = declaredNamespace(text, lt, tagEnd);
        if (own != null) {
            return own;
        }
        java.util.ArrayDeque<String> scopes = new java.util.ArrayDeque<>();
        int i = text.indexOf('<');
        while (i >= 0 && i < lt) {
            int next;
            if (text.startsWith("<!--", i)) {
                next = endOf(text, i + 4, "-->");
            } else if (text.startsWith("<![CDATA[", i)) {
                next = endOf(text, i + 9, "]]>");
            } else if (text.startsWith("<?", i)) {
                next = endOf(text, i + 2, "?>");
            } else if (text.startsWith("<!", i)) {
                next = endOf(text, i + 2, ">");
            } else {
                int end = tagEnd(text, i);
                if (text.startsWith("</", i)) {
                    scopes.pollFirst();
                } else if (end > 0 && text.charAt(end - 1) != '/') {
                    String ns = declaredNamespace(text, i, end);
                    String outer = scopes.peekFirst();
                    scopes.push(ns != null ? ns : outer != null ? outer : "");
                }
                next = end < 0 ? text.length() : end + 1;
            }
            i = text.indexOf('<', next);
        }
        String inScope = scopes.peekFirst();
        return inScope == null ? "" : inScope;
    }

    /** The {@code xmlns="…"} the tag from {@code lt} to {@code end} declares, or null. */
    private static @Nullable String declaredNamespace(String text, int lt, int end) {
        java.util.regex.Matcher m = DEFAULT_NS.matcher(text).region(lt, Math.max(lt, end));
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    /** Just after {@code close} found from {@code from}, or the end of {@code text}. */
    private static int endOf(String text, int from, String close) {
        int at = text.indexOf(close, from);
        return at < 0 ? text.length() : at + close.length();
    }

    /** The {@code >} that ends the tag starting at {@code lt}, outside quoted values; -1 when it does not end. */
    private static int tagEnd(String text, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The URI an include {@code href} names, resolved against the wire URI {@code documentUri}, without its fragment:
     * a URI reference as such ({@code sub%20dir/lib.xsl} is {@code sub dir/lib.xsl}, XML/pom review 3, B22), else the
     * text as a path ({@code sub dir/lib.xsl}); a {@code file:} URI as it is. Null for another scheme (a web
     * address, a drive letter) or a reference that cannot be parsed.
     */
    static @Nullable String includeUri(String documentUri, String href) {
        String h = href.replace('\\', '/');
        java.net.URI ref;
        try {
            ref = new java.net.URI(h);
        } catch (java.net.URISyntaxException notAUri) {
            try {
                ref = new java.net.URI(null, null, h, null);
            } catch (java.net.URISyntaxException e) {
                return null;
            }
        }
        String scheme = ref.getScheme();
        if (scheme != null && !(scheme.equalsIgnoreCase("file") && !ref.isOpaque())) {
            return null; // http:, a drive letter, an opaque file:name
        }
        try {
            String target =
                    (scheme != null ? ref : java.net.URI.create(documentUri).resolve(ref)).toString();
            int hash = target.indexOf('#');
            return hash < 0 ? target : target.substring(0, hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The file an include {@code href} names (see {@link #includeUri}): its wire URI, or null when there is none to
     * open — another scheme, or (on this machine) a file that is not there.
     */
    static @Nullable String resolveInclude(String documentUri, String href, WireUris uris) {
        String target = includeUri(documentUri, href);
        Path file = target == null ? null : toPath(uris, target);
        if (file == null || (isLocal(file) && !java.nio.file.Files.isRegularFile(file))) {
            return null;
        }
        return target;
    }

    /**
     * When Go to Definition found nothing at {@code pos} because the include {@code href} there names a local file
     * that does not exist: that file's name, for "… does not exist" (XML/pom review 3, C26); else null.
     */
    static @Nullable String missingInclude(LanguageServerSession s, String key, Position pos) {
        String text = s.documentSnapshots().get(key);
        String href = text == null ? null : includeHref(text, offset(text, pos));
        String target = href == null ? null : includeUri(s.uris().toWire(key), href);
        Path file = target == null ? null : toPath(s.uris(), target);
        if (file == null || !isLocal(file) || java.nio.file.Files.exists(file) || file.getFileName() == null) {
            return null;
        }
        return file.getFileName().toString();
    }

    private static @Nullable Path toPath(WireUris uris, String target) {
        try {
            return uris.toPath(target);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isLocal(Path file) {
        return file.getFileSystem() == java.nio.file.FileSystems.getDefault();
    }

    /** The stylesheet the {@code xsl:include}/{@code xsl:import} href at {@code pos} names, at its start; or null. */
    private static @Nullable Location includeLocation(LanguageServerSession s, String key, Position pos) {
        String text = s.documentSnapshots().get(key);
        String href = text == null ? null : includeHref(text, offset(text, pos));
        String target = href == null ? null : resolveInclude(s.uris().toWire(key), href, s.uris());
        return target == null ? null : new Location(target, new Range(new Position(0, 0), new Position(0, 0)));
    }
}
