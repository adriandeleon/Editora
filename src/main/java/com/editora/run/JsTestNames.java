package com.editora.run;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The Jest or Vitest test at the caret, for Debug Test at Caret: the innermost {@code test(…)}, {@code it(…)} or
 * {@code describe(…)} (with {@code .only}, {@code .skip}, {@code .concurrent}…) whose call spans the caret's line,
 * named with the {@code describe}s around it, as both runners name a test. Pure.
 */
public final class JsTestNames {

    private JsTestNames() {}

    /**
     * A test or suite at the caret.
     *
     * @param names its {@code describe}s' names, outermost first, then its own
     * @param group whether it is a suite ({@code describe}, {@code suite}, {@code context}): every test in it runs
     */
    public record Target(List<String> names, boolean group) {
        public Target {
            names = List.copyOf(names);
        }
    }

    /** A test call: {@code describe(}, {@code it.only(}, {@code test.concurrent.skip(}… */
    private static final Pattern CALL = Pattern.compile(
            "(?<![\\w$.])(describe|context|suite|it|test)(?:\\.(?:only|skip|concurrent|sequential|todo|fails))*\\s*\\(");

    /** A call found in the text: its name (null when not a literal), its kind, and the lines it spans. */
    private record Call(@Nullable String name, boolean group, int start, int end) {}

    /**
     * The innermost test or suite whose call spans 0-based line {@code caret} of {@code text}, named with the suites
     * around it; null when the caret is in none, or a name on the way is not a plain literal (a {@code test.each}
     * table's {@code %s}, a template with a placeholder): every test of the file then runs.
     */
    public static @Nullable Target at(String text, int caret) {
        List<String> names = new ArrayList<>();
        Call innermost = null;
        for (Call c : calls(text)) {
            if (c.start() <= caret && caret <= c.end()) {
                if (c.name() == null) {
                    return null;
                }
                names.add(c.name()); // calls come in order of their start: an outer suite first
                innermost = c;
            }
        }
        return innermost == null ? null : new Target(names, innermost.group());
    }

    private static List<Call> calls(String text) {
        List<Call> out = new ArrayList<>();
        Matcher m = CALL.matcher(text);
        while (m.find()) {
            if (DenoTests.inComment(text, m.start())) {
                continue;
            }
            int open = m.end() - 1;
            int close = DenoTests.closing(text, open);
            int end = close < 0 ? text.length() : close;
            String args = text.substring(open + 1, end);
            String name = args.stripLeading().startsWith("{") ? null : DenoTests.nameOf(args); // no options form
            if (name != null && name.contains("%")) {
                name = null; // a .each placeholder: the runner names each row
            }
            boolean group = !m.group(1).equals("it") && !m.group(1).equals("test");
            out.add(new Call(name, group, DenoTests.lineOf(text, m.start()), DenoTests.lineOf(text, end)));
        }
        return out;
    }
}
