package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

/** The one-line previews of a reference list's rows: {@link LspCoordinator}'s, apart for its size. */
final class ReferencePreviews {

    private ReferencePreviews() {}

    private static final String[] NO_LINES = new String[0];

    /**
     * One-line previews for each reference target, splitting <b>each file's content at most once</b> —
     * previously the per-target {@code previewLine} re-split the whole document for <em>every</em> target, so
     * 500 references into one open file did 500 full {@code getText()} walks + splits in a single FX pulse
     * (#471). {@code contentOf} maps a file to its open-buffer content (cheap, FX-safe, reflects unsaved
     * edits), or {@code null} for a file with no open tab (closed files show just the line number — no disk I/O
     * on the FX thread). Pure; unit-tested.
     */
    static List<String> previewLines(
            List<LspManager.Target> targets, java.util.function.Function<Path, @Nullable String> contentOf) {
        java.util.Map<Path, String[]> byFile = new java.util.HashMap<>();
        List<String> out = new java.util.ArrayList<>(targets.size());
        for (LspManager.Target t : targets) {
            String[] lines = byFile.computeIfAbsent(t.file(), f -> {
                String content = contentOf.apply(f);
                return content == null ? NO_LINES : content.split("\n", -1);
            });
            out.add(t.line() >= 0 && t.line() < lines.length ? lines[t.line()].strip() : "");
        }
        return out;
    }
}
