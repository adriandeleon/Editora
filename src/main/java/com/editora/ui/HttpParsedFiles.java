package com.editora.ui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import com.editora.http.HttpFile;

/**
 * A {@code .http} buffer's requests, parsed off the FX thread (PERF-01, Pauta 6.0.1) and kept per document version:
 * the run commands (Run Request, Debug Request, Run All, Run again) ask for them here instead of parsing the file
 * where they are pressed. A version not parsed yet is parsed on a virtual thread and the command goes on when it
 * lands; a second command for the same buffer meanwhile is refused (it finds the buffer busy). FX thread only.
 */
final class HttpParsedFiles {

    /** A buffer's requests at one version: each section, each request's block parsed, and the text's lines. */
    record Parsed(long version, List<HttpFile.Request> requests, List<HttpFile.Parsed> parsed, List<String> lines) {

        /** The index of the request whose section holds 0-based {@code line}, or -1 (no text read). */
        int indexAt(int line) {
            return HttpEmptyStateFeed.indexAt(requests, line);
        }
    }

    private final Map<EditorBuffer, Parsed> kept = new java.util.WeakHashMap<>();
    private final Set<EditorBuffer> parsing = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Hands {@code then} {@code buffer}'s requests as of now, on the FX thread: at once when this version was
     * parsed, else once a parse off the FX thread lands. False (and nothing runs) while a parse for the buffer is
     * already out: the command that asked first goes on, a second press is refused.
     */
    boolean withParsed(EditorBuffer buffer, Consumer<Parsed> then) {
        long version = buffer.docVersion();
        Parsed known = kept.get(buffer);
        if (known != null && known.version() == version) {
            then.accept(known);
            return true;
        }
        if (!parsing.add(buffer)) {
            return false;
        }
        String text = buffer.getContent();
        com.editora.perf.OffFx.then("HttpParsedFiles.parse", () -> parse(version, text), answer -> {
            parsing.remove(buffer);
            Parsed got = answer.or(new Parsed(version, List.of(), List.of(), List.of()));
            kept.put(buffer, got);
            then.accept(got);
        });
        return true;
    }

    /** Whether a parse for {@code buffer} is out (a command waits for it). */
    boolean busy(EditorBuffer buffer) {
        return parsing.contains(buffer);
    }

    /** Off the FX thread: {@code text}'s requests and their blocks parsed. */
    static Parsed parse(long version, String text) {
        List<HttpFile.Request> requests = HttpFile.parse(text);
        return new Parsed(
                version,
                requests,
                requests.stream().map(HttpFile::parseRequest).toList(),
                List.of(text.split("\n", -1)));
    }
}
