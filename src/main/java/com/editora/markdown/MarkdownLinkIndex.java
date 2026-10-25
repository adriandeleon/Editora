package com.editora.markdown;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.markdown.MarkdownLinkRewrite.Link;
import org.jspecify.annotations.Nullable;

/**
 * Which Markdown file links to which file: each document's outgoing links (inline, reference definitions,
 * raw HTML {@code src} / {@code href}, wiki links) and, derived from them, each file's incoming links.
 *
 * <p>Pure and single-threaded: it is given paths and texts and never reads the disk. Its owner fills it off
 * the FX thread and swaps it in, then keeps it current one document at a time ({@link #put},
 * {@link #remove}, {@link #renamed}). The incoming map is rebuilt lazily after a change — a whole-index pass
 * over links already in hand, not a re-read of any file.
 */
public final class MarkdownLinkIndex {

    /** A link in {@code source}, on 0-based {@code line} (whose trimmed text is {@code lineText}). */
    public record Backlink(Path source, int line, String lineText, Link link) {}

    private record Doc(List<Link> links, List<String> lineTexts) {}

    private static final int MAX_LINE_TEXT = 200;

    private final @Nullable Path root;
    private final Map<Path, Doc> docs = new LinkedHashMap<>();
    private final Set<Path> files = new LinkedHashSet<>();
    private boolean wikiLinks = true;

    private @Nullable WikiTargets targets;
    private @Nullable Map<Path, List<Backlink>> incoming;

    public MarkdownLinkIndex(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    public @Nullable Path root() {
        return root;
    }

    /** Whether {@code [[…]]} is a link (the {@code markdownWikiLinks} setting). Re-scan documents after a change. */
    public void setWikiLinks(boolean wikiLinks) {
        if (this.wikiLinks != wikiLinks) {
            this.wikiLinks = wikiLinks;
            incoming = null;
        }
    }

    public boolean wikiLinks() {
        return wikiLinks;
    }

    /** Every file a wiki link may name: the project's Markdown files and its pictures. */
    public void setFiles(Collection<Path> all) {
        files.clear();
        for (Path f : all) {
            files.add(norm(f));
        }
        invalidate();
    }

    public void addFile(Path file) {
        if (files.add(norm(file))) {
            invalidate();
        }
    }

    /** Records {@code doc}'s links as they are in {@code text}, replacing what was known of it. */
    public void put(Path doc, String text) {
        Path d = norm(doc);
        List<Link> links = MarkdownLinkRewrite.links(text == null ? "" : text, wikiLinks);
        List<String> lineTexts = new ArrayList<>(links.size());
        for (Link link : links) {
            lineTexts.add(lineText(text, link.start()));
        }
        docs.put(d, new Doc(List.copyOf(links), lineTexts));
        if (files.add(d)) {
            targets = null;
        }
        incoming = null;
    }

    private static String lineText(String text, int offset) {
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = text.indexOf('\n', offset);
        String line = text.substring(start, end < 0 ? text.length() : end).strip();
        return line.length() > MAX_LINE_TEXT ? line.substring(0, MAX_LINE_TEXT - 1) + "…" : line;
    }

    /** Forgets {@code path}: a file, or a folder and everything under it. */
    public void remove(Path path) {
        Path p = norm(path);
        boolean changed = docs.keySet().removeIf(d -> d.startsWith(p));
        changed |= files.removeIf(f -> f.startsWith(p));
        if (changed) {
            invalidate();
        }
    }

    /**
     * {@code from} (a file or a folder) is now {@code to}: what was known of the documents under it is kept
     * under their new paths. Their links are as they were written — the caller re-puts a document it rewrote.
     */
    public void renamed(Path from, Path to) {
        Path f = norm(from);
        Path t = norm(to);
        Map<Path, Doc> moved = new LinkedHashMap<>();
        docs.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(f)) {
                moved.put(t.resolve(f.relativize(e.getKey())).normalize(), e.getValue());
                return true;
            }
            return false;
        });
        docs.putAll(moved);
        List<Path> movedFiles = new ArrayList<>();
        files.removeIf(file -> {
            if (file.startsWith(f)) {
                movedFiles.add(t.resolve(f.relativize(file)).normalize());
                return true;
            }
            return false;
        });
        files.addAll(movedFiles);
        invalidate();
    }

    private void invalidate() {
        targets = null;
        incoming = null;
    }

    public boolean contains(Path doc) {
        return docs.containsKey(norm(doc));
    }

    public int documentCount() {
        return docs.size();
    }

    /** The indexed documents, in the order they were added. */
    public Set<Path> documents() {
        return java.util.Collections.unmodifiableSet(docs.keySet());
    }

    /** The files a wiki link may name. */
    public Set<Path> files() {
        return java.util.Collections.unmodifiableSet(files);
    }

    /** The wiki-name resolver over the files as they are now. */
    public WikiTargets wikiTargets() {
        if (targets == null) {
            targets = new WikiTargets(root, files);
        }
        return targets;
    }

    public List<Link> outgoing(Path doc) {
        Doc d = docs.get(norm(doc));
        return d == null ? List.of() : d.links();
    }

    /**
     * The file {@code link}, written in {@code doc}, points at — lexically for a path, by name for a wiki
     * link — or null: a web address, an anchor in the same document, an unresolved name.
     */
    public @Nullable Path target(Path doc, Link link) {
        Path d = norm(doc);
        if (link.isWiki()) {
            if (link.path().isEmpty()) {
                return null;
            }
            WikiTargets.Resolved r = wikiTargets().resolve(d, link.path());
            return r == null ? null : r.file();
        }
        String dest = link.destination();
        if (!MarkdownLinkRewrite.isRelative(dest) || d.getParent() == null) {
            return null;
        }
        String path = link.path();
        String decoded = link.angle() ? path : MarkdownLinkRewrite.decode(path);
        if (decoded.isBlank()) {
            return null;
        }
        try {
            return d.getParent().resolve(decoded).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every link that points at {@code target}, by source document then by line. */
    public List<Backlink> incoming(Path target) {
        return incomingMap().getOrDefault(norm(target), List.of());
    }

    /** The documents holding a link to {@code path} or, when it is a folder, to anything under it. */
    public Set<Path> sourcesOf(Path path) {
        Path p = norm(path);
        Set<Path> out = new LinkedHashSet<>();
        for (Map.Entry<Path, List<Backlink>> e : incomingMap().entrySet()) {
            if (e.getKey().startsWith(p)) {
                for (Backlink b : e.getValue()) {
                    out.add(b.source());
                }
            }
        }
        return out;
    }

    private Map<Path, List<Backlink>> incomingMap() {
        if (incoming == null) {
            Map<Path, List<Backlink>> map = new HashMap<>();
            for (Map.Entry<Path, Doc> e : docs.entrySet()) {
                List<Link> links = e.getValue().links();
                for (int i = 0; i < links.size(); i++) {
                    Link link = links.get(i);
                    Path to = target(e.getKey(), link);
                    if (to != null && !to.equals(e.getKey())) {
                        map.computeIfAbsent(to, k -> new ArrayList<>())
                                .add(new Backlink(
                                        e.getKey(),
                                        link.line(),
                                        e.getValue().lineTexts().get(i),
                                        link));
                    }
                }
            }
            incoming = map;
        }
        return incoming;
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
