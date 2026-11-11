package com.editora.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;

/**
 * Pure: breakpoints written to a file to keep or share, and read back — Visual Studio's Export / Import Breakpoints.
 * A line breakpoint is kept with its file ({@code file}: relative to the project, with {@code /}, or absolute) and all
 * it is (condition, log message, hit count, lambdas, recording, group, the breakpoint it waits for…); method
 * breakpoints and field watchpoints as they are. The line's text goes too, so a breakpoint finds its line again in a
 * file edited since.
 */
public final class BreakpointExport {

    private BreakpointExport() {}

    /** What the file says it is. */
    public static final String FORMAT = "editora-breakpoints";

    /** The version of the file's shape written now. */
    public static final int VERSION = 1;

    /** A line breakpoint and its file, as written. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileBreakpoint(String file, Breakpoint breakpoint) {
        public FileBreakpoint {
            file = file == null ? "" : file;
        }
    }

    /** The file's content. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String format, int version, List<FileBreakpoint> breakpoints, List<MemberBreakpoint> memberBreakpoints) {
        public Document {
            format = format == null ? "" : format;
            breakpoints = breakpoints == null ? List.of() : List.copyOf(breakpoints);
            memberBreakpoints = memberBreakpoints == null ? List.of() : List.copyOf(memberBreakpoints);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** {@code breakpoints} and {@code members} as the file's text. */
    public static String write(List<FileBreakpoint> breakpoints, List<MemberBreakpoint> members) {
        try {
            return JSON.writeValueAsString(new Document(FORMAT, VERSION, breakpoints, members)) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e); // records of strings, numbers and lists always write
        }
    }

    /** A file's text read back; an {@link IOException} when it is not one Editora wrote (or a later version). */
    public static Document read(String text) throws IOException {
        Document d;
        try {
            d = JSON.readValue(text, Document.class);
        } catch (IOException | RuntimeException e) {
            throw new IOException("not a breakpoints file: " + e.getMessage(), e);
        }
        if (d == null || !FORMAT.equals(d.format())) {
            throw new IOException("not a breakpoints file");
        }
        if (d.version() > VERSION) {
            throw new IOException("written by a later Editora (version " + d.version() + ")");
        }
        List<FileBreakpoint> kept = new ArrayList<>();
        for (FileBreakpoint fb : d.breakpoints()) {
            if (fb != null
                    && fb.breakpoint() != null
                    && !fb.file().isBlank()
                    && fb.breakpoint().line() >= 0) {
                kept.add(fb);
            }
        }
        List<MemberBreakpoint> members = new ArrayList<>();
        for (MemberBreakpoint m : d.memberBreakpoints()) {
            if (m != null && !m.className().isEmpty() && !m.member().isEmpty()) {
                members.add(m);
            }
        }
        return new Document(FORMAT, d.version(), kept, members);
    }

    /**
     * {@code imported} with each id that a breakpoint already set elsewhere has ({@code taken}: id → its place, a
     * file key and line) changed to a new one, and the breakpoints that wait for it following: two breakpoints never
     * share an id. An id on a breakpoint set at the same place is kept (the same breakpoint, imported again).
     * {@code placeOf} says where an imported breakpoint goes.
     */
    public static List<FileBreakpoint> freshIds(
            List<FileBreakpoint> imported,
            Map<String, String> taken,
            java.util.function.Function<FileBreakpoint, @Nullable String> placeOf) {
        Map<String, String> renamed = new HashMap<>();
        Set<String> used = new java.util.HashSet<>(taken.keySet());
        for (FileBreakpoint fb : imported) {
            String id = fb.breakpoint().id();
            if (id.isEmpty() || renamed.containsKey(id)) {
                continue;
            }
            String at = taken.get(id);
            if (at != null && !at.equals(placeOf.apply(fb))) {
                String next;
                do {
                    next = Breakpoint.newId();
                } while (!used.add(next));
                renamed.put(id, next);
            }
        }
        if (renamed.isEmpty()) {
            return imported;
        }
        List<FileBreakpoint> out = new ArrayList<>(imported.size());
        for (FileBreakpoint fb : imported) {
            Breakpoint bp = fb.breakpoint();
            String id = renamed.getOrDefault(bp.id(), bp.id());
            String trigger = renamed.getOrDefault(bp.triggeredBy(), bp.triggeredBy());
            out.add(new FileBreakpoint(fb.file(), bp.withId(id).withTriggeredBy(trigger)));
        }
        return out;
    }
}
