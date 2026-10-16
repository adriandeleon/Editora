package com.editora.http;

import java.util.BitSet;
import java.util.List;

/**
 * Which lines of a {@code .http} document are JavaScript — the lines of its inline response handlers
 * ({@code > {% … %}}) and pre-request scripts ({@code < {% … %}}), from the line that opens one to the line that
 * closes it — so a breakpoint goes only where a debugged script can stop (adl, 2026-10-16). Pure.
 */
public final class HttpScriptLines {

    private HttpScriptLines() {}

    /** The 0-based script lines of {@code text}. */
    public static BitSet of(String text) {
        BitSet lines = new BitSet();
        if (text == null || text.indexOf("{%") < 0) {
            return lines;
        }
        for (HttpFile.Request r : HttpFile.parse(text)) {
            HttpFile.Parsed p = HttpFile.parseRequest(r);
            for (HttpFile.Handler h : p.preScripts()) {
                mark(lines, h);
            }
            for (HttpFile.Handler h : p.handlers()) {
                mark(lines, h);
            }
        }
        return lines;
    }

    /** Whether 0-based {@code line} of {@code text} is a script line. */
    public static boolean isScriptLine(String text, int line) {
        return line >= 0 && of(text).get(line);
    }

    /** Whether the request whose section holds 0-based {@code line} has a pre-request script or a handler. */
    public static boolean requestHasScripts(String text, int line) {
        if (text == null || (text.indexOf("{%") < 0 && text.indexOf(".js") < 0)) {
            return false;
        }
        List<HttpFile.Request> requests = HttpFile.parse(text);
        int index = HttpFile.requestIndexAt(text, line);
        if (index < 0 || index >= requests.size()) {
            return false;
        }
        HttpFile.Parsed p = HttpFile.parseRequest(requests.get(index));
        return !p.preScripts().isEmpty() || !p.handlers().isEmpty();
    }

    private static void mark(BitSet lines, HttpFile.Handler h) {
        if (h.source() == null) {
            return; // a script file: its breakpoints go in that file
        }
        int count = (int) h.source().chars().filter(c -> c == '\n').count();
        lines.set(h.line(), h.line() + count + 1);
    }
}
