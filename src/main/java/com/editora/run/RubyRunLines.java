package com.editora.run;

import java.util.regex.Pattern;

/**
 * Where a Ruby file's Run ▶ goes, if it has one: an RSpec file at its first {@code describe}, a Minitest file at its test
 * class, a Rails app's {@code config.ru} and {@code bin/rails}, a script with code of its own — from the top. A file of
 * classes, modules and methods alone has none: it does nothing when run (in a Rails app Debug File serves the app from
 * it, {@link RubyApps}). Pure.
 */
public final class RubyRunLines {

    private RubyRunLines() {}

    /** A top-level line that declares rather than does. */
    private static final Pattern DECLARING = Pattern.compile(
            "(?:class|module|def|end|require|require_relative|autoload|include|extend|private|public|protected|"
                    + "attr_\\w+|module_function|using|__END__)\\b.*|[A-Z][\\w:]*\\s*(?:=[^=~].*|\\|\\|=.*)?|#.*|=begin.*|=end.*");

    private static final Pattern DESCRIBE = Pattern.compile("^(?:RSpec\\.)?describe\\b|^\\s*RSpec\\.describe\\b");

    private static final Pattern TEST_CLASS = Pattern.compile("^\\s*class\\s+\\w+\\s*<");

    /** The 0-based line of file {@code name}'s Run ▶ (text {@code text}), or -1 for none. */
    public static int runLine(String name, String text) {
        String[] lines = text.split("\n", -1);
        if (RubyApps.isSpec(name)) {
            return first(lines, DESCRIBE);
        }
        if (RubyApps.isMinitest(name, text)) {
            return first(lines, TEST_CLASS);
        }
        if (name.equals("config.ru") || name.equals("rails") || name.equals("Rakefile")) {
            return 0;
        }
        return hasTopLevelCode(lines) ? 0 : -1;
    }

    /**
     * Whether {@code lines} run something of their own: a line at the left margin, outside a {@code =begin} comment and
     * after {@code __END__} nothing, that is not a declaration ({@code class}, {@code module}, {@code def}, {@code
     * require}, a constant, a comment…). Ruby's code is indented within its classes and methods, so the margin is the
     * top level.
     */
    static boolean hasTopLevelCode(String[] lines) {
        boolean comment = false;
        for (String line : lines) {
            if (line.startsWith("__END__")) {
                return false;
            }
            if (line.startsWith("=begin")) {
                comment = true;
            }
            if (comment) {
                comment = !line.startsWith("=end");
                continue;
            }
            if (line.isBlank() || Character.isWhitespace(line.charAt(0)) || line.startsWith("#!")) {
                continue;
            }
            if (!DECLARING.matcher(line.stripTrailing()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static int first(String[] lines, Pattern p) {
        for (int i = 0; i < lines.length; i++) {
            if (p.matcher(lines[i]).find()) {
                return i;
            }
        }
        return 0;
    }
}
