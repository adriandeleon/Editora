package com.editora.editor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.completion.CompletionProvider;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.language.LanguageRegistry;
import com.editora.markdown.MarkdownAnchors;
import com.editora.markdown.MarkdownBlocks;
import com.editora.markdown.MarkdownCompletionContext;
import com.editora.markdown.MarkdownCompletionContext.Context;
import com.editora.markdown.MarkdownCompletionContext.Kind;
import com.editora.markdown.MarkdownLinkAt;
import com.editora.markdown.WikiTargets;
import org.jspecify.annotations.Nullable;

/**
 * What a Markdown buffer offers where a dictionary word is the wrong suggestion: files and folders for a
 * link's target, headings for its {@code #anchor} (this document's, or the linked file's), the document's
 * reference labels, languages for a code fence, and Mermaid keywords inside a {@code mermaid} fence.
 *
 * <p>It wraps the window's {@link CompletionProvider} (so a buffer has one provider) and adds
 * {@link #offer}, which {@code BufferCompletion} asks first in a Markdown buffer. Runs on the typing pause,
 * never per keystroke; the only I/O is one bounded directory listing (path completion) or one bounded file
 * read (anchors of another file), and only for a local document.
 */
public final class MarkdownCompletions implements CompletionProvider {

    /** Most rows one offer holds. */
    static final int MAX_ITEMS = 60;
    /** Most directory entries looked at for a path; a folder beyond that offers what was seen. */
    static final int MAX_DIRECTORY_ENTRIES = 5_000;
    /** Largest linked file read for its headings. */
    static final long MAX_ANCHOR_FILE_BYTES = 4L * 1024 * 1024;

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "avif");

    /** Info strings worth offering that are not a bundled grammar's own name. */
    private static final List<String> FENCE_ALIASES = List.of(
            "bash",
            "sh",
            "zsh",
            "console",
            "javascript",
            "js",
            "ts",
            "jsx",
            "tsx",
            "py",
            "rb",
            "rs",
            "kt",
            "yml",
            "md",
            "text",
            "mermaid",
            "math",
            "diff",
            "c++",
            "cs",
            "golang",
            "ps1",
            "plaintext");

    /**
     * Rows to show at the caret. {@code start} is the column of the caret's line where the replaced text
     * begins (negative: the rows replace the identifier before the caret, the usual way).
     */
    public record Offer(int start, String partial, List<Completion> items) {}

    private final CompletionProvider base;
    private final Supplier<Path> documentPath;

    private MarkdownCompletions(CompletionProvider base, Supplier<Path> documentPath) {
        this.base = base;
        this.documentPath = documentPath;
    }

    /** {@code base}, able to answer {@link #offer} for the document saved at {@code documentPath} (null: unsaved). */
    public static @Nullable CompletionProvider provider(CompletionProvider base, Supplier<Path> documentPath) {
        return base == null ? null : new MarkdownCompletions(base, documentPath);
    }

    @Override
    public List<Completion> complete(
            String snippetLang, String dictLang, String prefix, String snippetPrefix, boolean prose) {
        return base.complete(snippetLang, dictLang, prefix, snippetPrefix, prose);
    }

    /**
     * The structural offer at {@code column} of line {@code line} of {@code document}, or null when the
     * caret is in ordinary prose (the caller then does what it always did).
     *
     * @param identifier the identifier run before the caret (for Mermaid keywords)
     */
    public @Nullable Offer offer(
            String document, int line, int column, String identifier, String dictLang, boolean manual) {
        MarkdownBlocks blocks = MarkdownBlocks.scan(document);
        if (line < 0 || line >= blocks.lineCount()) {
            return null;
        }
        MarkdownBlocks.Kind kind = blocks.kind(line);
        if (kind == MarkdownBlocks.Kind.FENCE_BODY) {
            MarkdownBlocks.Fence fence = blocks.fenceAt(line);
            boolean mermaid = fence != null && fence.info().split("\\s+", 2)[0].equalsIgnoreCase("mermaid");
            if (!mermaid || identifier.isEmpty()) {
                return null;
            }
            List<Completion> items = base.complete("mermaid", dictLang, identifier, identifier, false);
            return items.isEmpty() ? null : new Offer(-1, identifier, items);
        }
        if (kind != MarkdownBlocks.Kind.TEXT && kind != MarkdownBlocks.Kind.FENCE_OPEN) {
            return null; // code, front matter, raw HTML, a closing fence
        }
        Context ctx = MarkdownCompletionContext.at(
                blocks.line(line), Math.min(column, blocks.line(line).length()));
        if (ctx == null || (ctx.kind() == Kind.FENCE_LANGUAGE) != (kind == MarkdownBlocks.Kind.FENCE_OPEN)) {
            return null;
        }
        List<Completion> items = switch (ctx.kind()) {
            case FENCE_LANGUAGE -> fenceLanguages(ctx.partial());
            case PATH -> paths(ctx);
            case ANCHOR -> anchors(ctx, document);
            case REFERENCE -> references(ctx, document, manual);
            case EMOJI -> emoji(ctx);
            case WIKI -> wikiNotes(ctx);
            case WIKI_HEADING -> wikiHeadings(ctx, document);
        };
        return items.isEmpty() ? null : new Offer(ctx.start(), ctx.partial(), items);
    }

    private static Completion row(String label, String insert, @Nullable String detail, CompletionIconKind icon) {
        return Completion.lsp(label, insert, detail, null, icon, null, false, false, null);
    }

    // --- fence languages ----------------------------------------------------------------------------------

    static List<Completion> fenceLanguages(String partial) {
        Set<String> names = new TreeSet<>(GrammarRegistry.shared().availableLanguageNames());
        names.addAll(FENCE_ALIASES);
        String p = partial.toLowerCase(Locale.ROOT);
        List<Completion> out = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(p) && !name.equals(p) && out.size() < MAX_ITEMS) {
                out.add(row(name, name, "", CompletionIconKind.KEYWORD));
            }
        }
        return out;
    }

    // --- paths --------------------------------------------------------------------------------------------

    private List<Completion> paths(Context ctx) {
        Path dir = documentDir();
        if (dir == null || ctx.partial().startsWith("/") || ctx.partial().startsWith("~")) {
            return List.of();
        }
        int slash = ctx.partial().lastIndexOf('/');
        String dirPart = ctx.partial().substring(0, slash + 1);
        String namePart = decode(ctx.partial().substring(slash + 1)).toLowerCase(Locale.ROOT);
        Path folder;
        try {
            folder = dir.resolve(decode(dirPart)).normalize();
        } catch (RuntimeException e) {
            return List.of();
        }
        Path self = documentPath.get();
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            int seen = 0;
            for (Path entry : entries) {
                if (++seen > MAX_DIRECTORY_ENTRIES) {
                    break;
                }
                String name = entry.getFileName().toString();
                boolean hidden = name.startsWith(".") && !namePart.startsWith(".");
                if (!hidden && name.toLowerCase(Locale.ROOT).startsWith(namePart) && !entry.equals(self)) {
                    found.add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of(); // no such folder (yet), or not readable: nothing to offer
        }
        found.sort(Comparator.comparingInt(MarkdownCompletions::rank)
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        List<Completion> out = new ArrayList<>();
        for (Path entry : found) {
            if (out.size() >= MAX_ITEMS) {
                break;
            }
            String name = entry.getFileName().toString();
            boolean folderEntry = Files.isDirectory(entry);
            String shown = folderEntry ? name + "/" : name;
            String insert = dirPart + (ctx.angle() ? shown : shown.replace(" ", "%20"));
            out.add(row(shown, insert, "", folderEntry ? CompletionIconKind.FOLDER : CompletionIconKind.FILE));
        }
        return out;
    }

    /** Folders, then Markdown files, then images, then the rest. */
    private static int rank(Path entry) {
        if (Files.isDirectory(entry)) {
            return 0;
        }
        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        if ("markdown".equals(LanguageRegistry.forFileName(name))) {
            return 1;
        }
        return IMAGE_EXTENSIONS.contains(ext) ? 2 : 3;
    }

    /** The folder relative targets resolve against: the saved document's, on the local file system only. */
    private @Nullable Path documentDir() {
        Path doc = documentPath.get();
        if (doc == null || doc.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        return doc.toAbsolutePath().getParent();
    }

    private static String decode(String s) {
        return s.indexOf('%') < 0 ? s : s.replace("%20", " ");
    }

    // --- anchors ------------------------------------------------------------------------------------------

    private List<Completion> anchors(Context ctx, String document) {
        String text = document;
        if (!ctx.file().isEmpty()) {
            Path dir = documentDir();
            text = null;
            try {
                Path file = dir == null ? null : dir.resolve(decode(ctx.file())).normalize();
                if (file != null
                        && "markdown".equals(LanguageRegistry.forFileName(file.toString()))
                        && Files.isRegularFile(file)
                        && Files.size(file) <= MAX_ANCHOR_FILE_BYTES) {
                    text = Files.readString(file);
                }
            } catch (IOException | RuntimeException e) {
                text = null; // unreadable or not UTF-8: no headings to offer
            }
        }
        if (text == null) {
            return List.of();
        }
        String p = ctx.partial().toLowerCase(Locale.ROOT);
        List<Completion> out = new ArrayList<>();
        for (MarkdownAnchors.Anchor a : MarkdownAnchors.anchors(text)) {
            boolean matches =
                    a.slug().startsWith(p) || a.title().toLowerCase(Locale.ROOT).contains(p);
            if (matches && out.size() < MAX_ITEMS) {
                // Shown as the heading, with its level; filtered and inserted as its anchor.
                Completion c = row(
                        "#".repeat(a.level()) + " " + a.title(), a.slug(), "#" + a.slug(), CompletionIconKind.OTHER);
                out.add(c.withProtocol(new Completion.Protocol(a.slug(), null, null)));
            }
        }
        return out;
    }

    // --- reference labels ---------------------------------------------------------------------------------

    private static List<Completion> references(Context ctx, String document, boolean manual) {
        String p = ctx.partial().strip().toLowerCase(Locale.ROOT);
        List<Completion> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (MarkdownLinkAt.Definition d : MarkdownLinkAt.definitions(document)) {
            String label = d.label();
            String folded = label.toLowerCase(Locale.ROOT);
            if (folded.startsWith(p) && !folded.equals(p) && seen.add(folded) && out.size() < MAX_ITEMS) {
                out.add(row(label, label, d.destination(), CompletionIconKind.OTHER));
            }
        }
        return out;
    }

    /** Shortcodes from the table the renderer uses: the ones that start with what was typed, then the rest. */
    private static List<Completion> emoji(Context ctx) {
        String p = ctx.partial().toLowerCase(Locale.ROOT);
        List<Completion> starts = new ArrayList<>();
        List<Completion> contains = new ArrayList<>();
        for (var e : com.editora.markdown.MarkdownEmoji.table().entrySet()) {
            String code = e.getKey();
            boolean prefix = code.startsWith(p);
            if (!prefix && !code.contains(p)) {
                continue;
            }
            List<Completion> into = prefix ? starts : contains;
            if (into.size() < MAX_ITEMS) {
                String written = ":" + code + ":";
                into.add(row(written, written, e.getValue(), CompletionIconKind.OTHER));
            }
        }
        for (Completion c : contains) {
            if (starts.size() >= MAX_ITEMS) {
                break;
            }
            starts.add(c);
        }
        return starts;
    }

    // --- wiki links ----------------------------------------------------------------------------------------

    /**
     * The project's files as a wiki link may name them, for the document being edited: installed by the
     * window (it owns the link index), and {@link WikiTargets#EMPTY} until then or outside a project.
     */
    private static volatile java.util.function.Function<Path, WikiTargets> wikiSource = doc -> WikiTargets.EMPTY;

    public static void setWikiSource(java.util.function.Function<Path, WikiTargets> source) {
        wikiSource = source == null ? doc -> WikiTargets.EMPTY : source;
    }

    private WikiTargets wikiTargets() {
        try {
            WikiTargets t = wikiSource.apply(documentPath.get());
            return t == null ? WikiTargets.EMPTY : t;
        } catch (RuntimeException e) {
            return WikiTargets.EMPTY;
        }
    }

    /**
     * Notes first, then pictures; a name that starts with what was typed before one that only contains it. A
     * Markdown file is offered without its extension, and by its project path when its name is not unique.
     */
    private List<Completion> wikiNotes(Context ctx) {
        WikiTargets targets = wikiTargets();
        Path doc = documentPath.get();
        Path self = doc == null ? null : doc.toAbsolutePath().normalize();
        String p = ctx.partial().strip().toLowerCase(Locale.ROOT);
        List<String[]> rows = new ArrayList<>(); // {sort key, name to insert, detail}
        for (Path file : targets.files()) {
            if (file.equals(self)) {
                continue;
            }
            boolean markdown = "markdown".equals(LanguageRegistry.forFileName(file.toString()));
            String name = WikiTargets.name(file, false);
            String lower = name.toLowerCase(Locale.ROOT);
            int at = lower.indexOf(p);
            if (at < 0) {
                continue;
            }
            String rel = targets.root() == null
                    ? name
                    : targets.root().relativize(file).toString().replace('\\', '/');
            String insert = name;
            if (self != null) {
                WikiTargets.Resolved r = targets.resolve(self, name);
                if (r == null || !r.file().equals(file)) {
                    insert = markdown ? WikiTargets.stripMarkdownExtension(rel) : rel; // ambiguous by name
                }
            }
            rows.add(new String[] {(markdown ? "0" : "1") + (at == 0 ? "0" : "1") + lower, insert, rel});
        }
        rows.sort(java.util.Comparator.comparing(r -> r[0]));
        List<Completion> out = new ArrayList<>();
        for (String[] r : rows) {
            if (out.size() >= MAX_ITEMS) {
                break;
            }
            out.add(row(r[1], r[1], r[2], CompletionIconKind.FILE)
                    .withProtocol(new Completion.Protocol(r[1], null, null)));
        }
        return out;
    }

    /** The headings of the note named before the {@code #}, written as their titles (a wiki link's form). */
    private List<Completion> wikiHeadings(Context ctx, String document) {
        String text = document;
        if (!ctx.file().isEmpty()) {
            text = null;
            Path doc = documentPath.get();
            WikiTargets.Resolved r = doc == null ? null : wikiTargets().resolve(doc, ctx.file());
            try {
                if (r != null
                        && "markdown"
                                .equals(LanguageRegistry.forFileName(r.file().toString()))
                        && Files.isRegularFile(r.file())
                        && Files.size(r.file()) <= MAX_ANCHOR_FILE_BYTES) {
                    text = Files.readString(r.file());
                }
            } catch (IOException | RuntimeException e) {
                text = null;
            }
        }
        if (text == null) {
            return List.of();
        }
        String p = ctx.partial().strip().toLowerCase(Locale.ROOT);
        List<Completion> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (MarkdownAnchors.Anchor a : MarkdownAnchors.anchors(text)) {
            String title = a.title().strip();
            if (title.toLowerCase(Locale.ROOT).contains(p) && seen.add(title) && out.size() < MAX_ITEMS) {
                out.add(row("#".repeat(a.level()) + " " + title, title, null, CompletionIconKind.OTHER)
                        .withProtocol(new Completion.Protocol(title, null, null)));
            }
        }
        return out;
    }
}
