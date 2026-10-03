package com.editora.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds a handful of well-known, wide-coverage font files already installed on the machine, for the PDF
 * writers to fall back on when the bundled fonts lack a glyph ({@link PdfGlyphs}). Nothing is bundled and
 * nothing is read here beyond directory listings: the files are opened, one at a time, only by a document
 * that actually contains a character the bundled fonts cannot draw.
 *
 * <p>The list is short and ordered — CJK first (the biggest gap), then broad Unicode faces, then the
 * per-script Noto/OS fonts for Arabic, Hebrew, Thai and Devanagari, then symbols — so a document never has to
 * open more than a few files. Unknown fonts are ignored on purpose: a font directory can hold hundreds of
 * display faces, and probing each would turn one missing glyph into a long pause.
 */
final class SystemFontFiles {

    /** File names to look for (lower-case), best first. */
    private static final List<String> WANTED = List.of(
            // CJK
            "notosanscjk-regular.ttc",
            "notosanscjk-vf.ttc",
            "notosanscjksc-regular.otf",
            "notosanssc-regular.ttf",
            "notosansjp-regular.ttf",
            "notosanskr-regular.ttf",
            "droidsansfallbackfull.ttf",
            "droidsansfallback.ttf",
            "wqy-zenhei.ttc",
            "wqy-microhei.ttc",
            "pingfang.ttc",
            "hiragino sans gb.ttc",
            "stheiti light.ttc",
            "applegothic.ttf",
            "msyh.ttc",
            "msyh.ttf",
            "msgothic.ttc",
            "yugothr.ttc",
            "meiryo.ttc",
            "simsun.ttc",
            "malgun.ttf",
            // broad Unicode coverage
            "arial unicode.ttf",
            "arialuni.ttf",
            "notosans-regular.ttf",
            "dejavusans.ttf",
            "freesans.ttf",
            "arial.ttf",
            "segoeui.ttf",
            "tahoma.ttf",
            // one script each
            "notosansarabic-regular.ttf",
            "notonaskharabic-regular.ttf",
            "notosanshebrew-regular.ttf",
            "notosansthai-regular.ttf",
            "notosansdevanagari-regular.ttf",
            "nirmala.ttf",
            "leelawui.ttf",
            "thonburi.ttc",
            // symbols
            "notosanssymbols2-regular.ttf",
            "notosanssymbols-regular.ttf",
            "seguisym.ttf",
            "apple symbols.ttf");

    /** Font directories are shallow; this bounds a walk through an unexpectedly deep tree. */
    private static final int MAX_DEPTH = 4;

    private static volatile List<Path> cached;

    private SystemFontFiles() {}

    /** The fallback font files present on this machine, best first. Discovered once, then cached. */
    static List<Path> get() {
        List<Path> c = cached;
        if (c == null) {
            c = find(roots(System.getProperty("os.name", ""), System.getProperty("user.home", ""), System.getenv()));
            cached = c;
        }
        return c;
    }

    /** The font directories of the platform named by {@code osName}. Pure. */
    static List<Path> roots(String osName, String home, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> out = new ArrayList<>();
        if (os.contains("win")) {
            String windir = env.getOrDefault("WINDIR", env.getOrDefault("SystemRoot", "C:\\Windows"));
            out.add(Path.of(windir, "Fonts"));
            String local = env.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                out.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            out.add(Path.of("/System/Library/Fonts"));
            out.add(Path.of("/Library/Fonts"));
            out.add(Path.of(home, "Library", "Fonts"));
        } else {
            out.add(Path.of("/usr/share/fonts"));
            out.add(Path.of("/usr/local/share/fonts"));
            out.add(Path.of(home, ".local", "share", "fonts"));
            out.add(Path.of(home, ".fonts"));
        }
        return out;
    }

    /** The {@link #WANTED} files found under {@code roots}, in priority order (first root wins a tie). */
    static List<Path> find(List<Path> roots) {
        Map<String, Path> byName = new HashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
                walk.forEach(p -> {
                    Path fileName = p.getFileName();
                    if (fileName != null) {
                        String name = fileName.toString().toLowerCase(Locale.ROOT);
                        if (WANTED.contains(name) && Files.isRegularFile(p)) {
                            byName.putIfAbsent(name, p);
                        }
                    }
                });
            } catch (IOException | RuntimeException e) {
                // an unreadable font directory just contributes nothing
            }
        }
        List<Path> out = new ArrayList<>();
        for (String name : WANTED) {
            Path p = byName.get(name);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
