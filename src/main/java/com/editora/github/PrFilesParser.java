package com.editora.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.diff.PatchParser;
import com.editora.diff.PatchParser.FilePatch;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses GitHub's "list pull request files" API ({@code gh api repos/{owner}/{repo}/pulls/N/files --paginate})
 * into the same {@link FilePatch}es {@code gh pr diff} yields — the fallback for a pull request whose whole
 * diff GitHub refuses to produce (HTTP 406 above 300 files / 20,000 lines). Each file object carries its own
 * hunks in {@code patch}; GitHub leaves that out for a binary file and for a file whose own diff is too large,
 * so such a file is listed with no lines ({@link Result#withoutPatch} counts the ones that did change text).
 *
 * <p>{@code --paginate} prints one JSON array per page, back to back, so the input is read as a sequence of
 * root values (a single array, or {@code --slurp}'s array of arrays, reads the same). Pure — unit-tested.
 */
public final class PrFilesParser {

    private PrFilesParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The most files the API lists for one pull request, whatever its real size. */
    public static final int API_FILE_LIMIT = 3000;

    /**
     * The files of a pull request.
     *
     * @param withoutPatch how many changed files GitHub sent no hunks for (their own diff is too large)
     */
    public record Result(List<FilePatch> files, int withoutPatch) {
        /** Whether the list stops at the API's ceiling, so the pull request may touch more files than listed. */
        public boolean capped() {
            return files.size() >= API_FILE_LIMIT;
        }
    }

    /**
     * Whether {@code ghMessage} — what {@code gh pr diff} printed when it failed — is GitHub refusing the diff
     * for its size, the one failure the files API can stand in for.
     */
    public static boolean diffTooLarge(String ghMessage) {
        String m = ghMessage == null ? "" : ghMessage.toLowerCase(Locale.ROOT);
        return m.contains("http 406") || m.contains("too_large") || m.contains("diff exceeded the maximum");
    }

    /** Parses the API output. Never throws; an empty result on bad input. */
    public static Result parse(String json) {
        List<FilePatch> out = new ArrayList<>();
        int[] withoutPatch = {0};
        if (json == null || json.isBlank()) {
            return new Result(out, 0);
        }
        try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
            while (parser.nextToken() != null) { // one root value per page
                JsonNode page = MAPPER.readTree(parser);
                collect(page, out, withoutPatch);
            }
        } catch (Exception e) {
            // a page cut short: keep the files read so far
        }
        return new Result(List.copyOf(out), withoutPatch[0]);
    }

    private static void collect(JsonNode node, List<FilePatch> out, int[] withoutPatch) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, out, withoutPatch);
            }
            return;
        }
        String name = text(node, "filename");
        if (!node.isObject() || name.isEmpty()) {
            return;
        }
        String status = text(node, "status").toLowerCase(Locale.ROOT);
        String previous = text(node, "previous_filename");
        String oldPath = "added".equals(status) ? "/dev/null" : (previous.isEmpty() ? name : previous);
        String newPath = "removed".equals(status) ? "/dev/null" : name;
        int additions = node.path("additions").asInt(0);
        int deletions = node.path("deletions").asInt(0);
        String patch = text(node, "patch");
        List<FilePatch> parsed = patch.isEmpty() ? List.of() : PatchParser.parse("--- a/x\n+++ b/x\n" + patch);
        if (parsed.isEmpty()) {
            if (additions + deletions > 0) {
                withoutPatch[0]++;
            }
            out.add(new FilePatch(oldPath, newPath, List.of(), List.of(), additions, deletions, true, true));
            return;
        }
        FilePatch p = parsed.get(0);
        out.add(new FilePatch(
                oldPath,
                newPath,
                p.oldLines(),
                p.newLines(),
                p.additions(),
                p.deletions(),
                p.oldFinalNewline(),
                p.newFinalNewline(),
                p.oldLineNumbers(),
                p.newLineNumbers()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
