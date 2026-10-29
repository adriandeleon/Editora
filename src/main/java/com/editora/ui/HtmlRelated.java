package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editops.HtmlLinks;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * Related File for the HTML round trip (HTML review C16): from a page, the stylesheets and scripts it loads with
 * {@code <link href>} and {@code <script src>}; from a stylesheet or a script, the project's pages that load it.
 * The name-based candidates of {@link com.editora.search.RelatedFiles} still follow — a {@code css/} and
 * {@code js/} folder layout is the usual one, and matching names never reach it.
 */
final class HtmlRelated {

    /** Pages read when looking for the ones that link a stylesheet or script. */
    static final int MAX_PAGES = 500;

    /** A page bigger than this is not read for its links. */
    private static final long MAX_PAGE_BYTES = 1_000_000;

    private HtmlRelated() {}

    /** Whether {@code file} is an HTML page ({@code .html}, {@code .htm}, {@code .xhtml}). */
    static boolean isPage(@Nullable Path file) {
        String ext = extension(file);
        return ext.equals("html") || ext.equals("htm") || ext.equals("xhtml");
    }

    /** Whether {@code file} is what a page loads: a stylesheet or a script. */
    static boolean isAsset(@Nullable Path file) {
        String ext = extension(file);
        return ext.equals("css") || ext.equals("js") || ext.equals("mjs");
    }

    /**
     * The local stylesheets and scripts {@code text}, the page {@code page}, loads, resolved and existing, in
     * document order; empty when it is not a page.
     */
    static List<Path> assetsOf(String text, Path page, @Nullable Path root) {
        if (!isPage(page) || !Vfs.isLocal(page)) {
            return List.of();
        }
        String base = HtmlLinks.baseHref(text);
        List<Path> out = new ArrayList<>();
        for (HtmlLinks.Link link : HtmlLinks.all(text)) {
            if (!link.asset()) {
                continue;
            }
            HtmlLinks.Target t = HtmlLinks.resolve(page, root, link.value(), base);
            Path file = t.file();
            if (t.kind() == HtmlLinks.Kind.FILE
                    && file != null
                    && isAsset(file)
                    && !out.contains(file)
                    && Files.isRegularFile(file)) {
                out.add(file);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The pages of {@code pages} whose links name {@code asset}, read off the FX thread; {@code done} gets them on
     * it.
     */
    static void pagesLinking(Path asset, List<Path> pages, @Nullable Path root, Consumer<List<Path>> done) {
        CompletableFuture.supplyAsync(() -> linking(asset, pages, root))
                .exceptionally(t -> List.of())
                .thenAccept(found -> Platform.runLater(() -> done.accept(found)));
    }

    /** Pure but for the reads: the pages that link {@code asset}. */
    static List<Path> linking(Path asset, List<Path> pages, @Nullable Path root) {
        Path target = asset.toAbsolutePath().normalize();
        List<Path> out = new ArrayList<>();
        for (Path page : pages) {
            if (!Vfs.isLocal(page)) {
                continue;
            }
            try {
                if (Files.size(page) > MAX_PAGE_BYTES) {
                    continue;
                }
                String text = Files.readString(page);
                String base = HtmlLinks.baseHref(text);
                for (HtmlLinks.Link link : HtmlLinks.all(text)) {
                    Path file =
                            HtmlLinks.resolve(page, root, link.value(), base).file();
                    if (file != null && file.toAbsolutePath().normalize().equals(target)) {
                        out.add(page);
                        break;
                    }
                }
            } catch (java.io.IOException | RuntimeException unreadable) {
                // a page that cannot be read links nothing
            }
        }
        return List.copyOf(out);
    }

    private static String extension(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        String n = name == null ? "" : name.toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
