package com.editora.editor;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.editora.markdown.MarkdownToc;
import com.editora.markdown.MarkdownWikiLinks.WikiLink;

/**
 * Where a wiki link leads: the one place the renderers ask, and the seam the project's link index is plugged
 * into ({@link #setResolver}).
 *
 * <p>Until an index is installed, a target is looked for beside the document only: the file of that name, or
 * that name with a Markdown extension. That is one {@code stat} or two per link of a block being built, on the
 * local disk only — a document on a remote file system is not probed from here (its links read as
 * unresolved until a resolver that knows better is installed).
 */
public final class WikiLinkTargets {

    /** Finds the file a wiki link names. */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param folder the folder of the document the link is in; null for a document with no file
         * @param target the link's target as written ({@code note}, {@code folder/note.md}); never empty
         * @return the file, or null when nothing (or more than one thing) answers to the name
         */
        Path resolve(Path folder, String target);
    }

    /** The scheme of the address a click on an <em>unresolved</em> wiki link is reported with:
     *  {@code wikilink:} followed by the link's {@code target#heading}, as written. */
    public static final String SCHEME = "wikilink:";

    private static final List<String> MARKDOWN_EXTENSIONS = List.of(".md", ".markdown", ".mdown", ".mkd");
    private static final List<String> IMAGE_EXTENSIONS =
            List.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".bmp", ".avif", ".ico");

    private static final Resolver BESIDE = WikiLinkTargets::beside;
    private static volatile Resolver resolver = BESIDE;

    private WikiLinkTargets() {}

    private static final java.util.concurrent.atomic.AtomicLong EPOCH = new java.util.concurrent.atomic.AtomicLong();

    /** Changes when a link drawn before may lead somewhere else now: a preview built before is out of date. */
    static long epoch() {
        return EPOCH.get();
    }

    /**
     * Says that what targets resolve to has changed (a note was created, renamed or removed): previews redraw
     * their links on their next render. Cheap; safe from any thread.
     */
    public static void changed() {
        EPOCH.incrementAndGet();
    }

    /** Installs the resolver every preview asks (the project's link index); null restores the built-in one. */
    public static void setResolver(Resolver r) {
        resolver = r == null ? BESIDE : r;
        changed();
    }

    /** The file {@code target} names for a document in {@code folder}, or null. Never throws. */
    public static Path resolve(Path folder, String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            return resolver.resolve(folder, target.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The built-in resolver: the file of that name beside the document, a Markdown extension optional. */
    static Path beside(Path folder, String target) {
        if (folder == null || folder.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        Path exact = child(folder, target);
        if (exact == null) {
            return null;
        }
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        for (String ext : MARKDOWN_EXTENSIONS) {
            Path named = child(folder, target + ext);
            if (named != null && Files.isRegularFile(named)) {
                return named;
            }
        }
        return null;
    }

    private static Path child(Path folder, String name) {
        try {
            Path p = folder.resolve(name).normalize();
            return p.startsWith(folder) || p.isAbsolute() ? p : null;
        } catch (RuntimeException e) {
            return null; // not a path on this file system
        }
    }

    /** Whether an embed of {@code target} ({@code ![[target]]}) is a picture, by its extension. Pure. */
    public static boolean isImage(String target) {
        String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * The address a click on {@code link} is reported with to the link opener every other preview link goes
     * through: {@code #heading-slug} for a link into this document, else {@code wikilink:target#heading} as
     * written — the opener resolves it when it is followed ({@link #resolve}), so a note created since the
     * preview was drawn is found.
     */
    public static String address(WikiLink link) {
        if (link.getTarget().isEmpty()) {
            return "#" + MarkdownToc.slug(link.getHeading() == null ? "" : link.getHeading());
        }
        return SCHEME + link.getReference();
    }

    /** Whether {@code address} is a wiki link's ({@link #address}). Pure. */
    public static boolean isWikiAddress(String address) {
        return address != null && address.startsWith(SCHEME);
    }

    /** The target of a wiki address ({@code wikilink:note#Heading} gives {@code note}). Pure. */
    public static String targetOf(String address) {
        String reference = address.substring(SCHEME.length());
        int hash = reference.indexOf('#');
        return hash < 0 ? reference : reference.substring(0, hash);
    }

    /** The heading of a wiki address as an anchor slug ({@code wikilink:note#Some Heading} gives
     *  {@code some-heading}), or null when it names none. Pure. */
    public static String fragmentOf(String address) {
        int hash = address.indexOf('#', SCHEME.length());
        return hash < 0 || hash == address.length() - 1 ? null : MarkdownToc.slug(address.substring(hash + 1));
    }
}
