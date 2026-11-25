package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A test runner whose run Editora starts as Run File or Run Test at Caret starts it, read into Test Results: what it is
 * told to report, how its run is widened back to the whole file and narrowed to some tests, and how its results are
 * read. Six write a report file read when the run ends — Jest and Vitest JSON, RSpec JSON, PHPUnit's and Deno's JUnit
 * XML, Dart's JSON events — and two are read from the console as they run (Minitest's {@code -v}, ExUnit's
 * {@code --trace}), while the console shows the runner's own report. Pure.
 */
public enum ToolTestKind {
    JEST(".json"),
    VITEST(".json"),
    DENO(".xml"),
    RSPEC(".json"),
    MINITEST(""),
    PHPUNIT(".xml"),
    EXUNIT(""),
    DART(".json"),
    /** A GoogleTest program: {@code --gtest_output=xml}. */
    GTEST(".xml"),
    /** A Catch2 3 program: its XML reporter to the file, its console reporter kept. */
    CATCH2(".xml"),
    /** A Catch2 2 program ({@code catch.hpp}): one reporter only, so its console says little. */
    CATCH2_V2(".xml"),
    /** A doctest program: its XML reporter, to the file. */
    DOCTEST(".xml");

    /** The report file's suffix; empty for a runner read from its console. */
    private final String reportSuffix;

    ToolTestKind(String reportSuffix) {
        this.reportSuffix = reportSuffix;
    }

    /** Whether the run writes a report file ({@link #withReport}), read when it ends; else its console is read. */
    public boolean fileReport() {
        return !reportSuffix.isEmpty();
    }

    public String reportSuffix() {
        return reportSuffix;
    }

    /**
     * Whether its report lists the tests a filter left out, as skipped (Jest's {@code -t}, Vitest's, Deno's
     * {@code --filter}, doctest's {@code --test-case}): a narrowed run's skipped rows are dropped.
     */
    public boolean reportsFilteredAsSkipped() {
        return this == JEST || this == VITEST || this == DENO || this == DOCTEST;
    }

    /**
     * The kind of a Node launch's {@code argv} that runs tests under a runner Test Results reads — Jest's
     * {@code jest.js}, Vitest's {@code vitest.mjs}, {@code deno test} — else null (a script, or {@code node --test},
     * which {@code NodeTestRunner} runs).
     */
    public static @Nullable ToolTestKind ofNodeArgv(List<String> argv) {
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i).replace('\\', '/');
            String name = a.substring(a.lastIndexOf('/') + 1);
            if (i == 0 && (name.equals("deno") || name.equals("deno.exe"))) {
                return argv.size() > 1 && argv.get(1).equals("test") ? DENO : null;
            }
            if (a.endsWith("/jest/bin/jest.js") || a.endsWith("/jest-cli/bin/jest.js")) {
                return JEST;
            }
            if (a.endsWith("/vitest/vitest.mjs")) {
                return argv.contains("run") ? VITEST : null; // watching: no end, no report
            }
        }
        return null;
    }

    /**
     * {@code argv} told to report as this kind is read: into {@code report} for a file kind, more verbosely on the
     * console for the others. {@code rspecFormats} are the console formats a project's {@code .rspec} names (RSpec's
     * console otherwise goes quiet once a JSON format is asked for); empty for {@code progress}. Options go before a
     * {@code --}, which hands the rest to the tests.
     */
    public List<String> withReport(List<String> argv, Path report, List<String> rspecFormats) {
        List<String> add = switch (this) {
            case JEST -> List.of("--json", "--outputFile=" + report, "--testLocationInResults");
            case VITEST -> List.of("--reporter=default", "--reporter=json", "--outputFile.json=" + report);
            case DENO -> List.of("--junit-path=" + report);
            case RSPEC -> {
                List<String> out = new ArrayList<>();
                for (String f : rspecFormats.isEmpty() ? List.of("progress") : rspecFormats) {
                    out.add("--format");
                    out.add(f);
                }
                out.addAll(List.of("--format", "json", "--out", report.toString()));
                yield out;
            }
            case MINITEST -> List.of("-v");
            case PHPUNIT -> List.of("--log-junit", report.toString());
            case EXUNIT -> List.of("--trace");
            case DART -> List.of("--file-reporter", "json:" + report);
            case GTEST -> List.of("--gtest_output=xml:" + report);
            case CATCH2 -> List.of("--reporter", "console::out=-", "--reporter", "xml::out=" + report);
            case CATCH2_V2 -> List.of("--reporter", "xml", "--out", report.toString());
            case DOCTEST -> List.of("--reporters=xml", "--out=" + report);
        };
        return insert(argv, add);
    }

    /**
     * {@code argv} as Run File runs the whole file: the test at the caret's narrowing taken out — {@code -t},
     * {@code --filter}, {@code -n}, {@code --name} / {@code --plain-name} and their value, a {@code file:line}'s line.
     */
    public List<String> wholeFile(List<String> argv) {
        List<String> out = new ArrayList<>();
        boolean tests = false; // past "--": the tests' own arguments, left as they are
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            if (a.equals("--")) {
                tests = true;
            }
            if (!tests && i > 0 && narrowingWhole(a)) {
                continue; // a filter written as one argument, or a Catch2 test spec
            }
            if (!tests && narrowing(a) && i + 1 < argv.size()) {
                i++; // and its value
                continue;
            }
            out.add(tests ? a : withoutLine(a));
        }
        return List.copyOf(out);
    }

    /** A native test program's narrowing, all in one argument (the program itself is never one). */
    private boolean narrowingWhole(String arg) {
        return switch (this) {
            case GTEST -> arg.startsWith("--gtest_filter=");
            case DOCTEST -> arg.startsWith("--test-case=") || arg.startsWith("-tc=");
            case CATCH2, CATCH2_V2 -> !arg.startsWith("-"); // its test specs: what is not an option
            default -> false;
        };
    }

    private boolean narrowing(String arg) {
        return switch (this) {
            case JEST, VITEST -> arg.equals("-t") || arg.equals("--testNamePattern");
            case DENO, PHPUNIT -> arg.equals("--filter");
            case MINITEST -> arg.equals("-n") || arg.equals("--name");
            case DART -> arg.equals("--plain-name") || arg.equals("--name") || arg.equals("-N") || arg.equals("-n");
            case RSPEC -> arg.equals("-e") || arg.equals("--example");
            case EXUNIT -> arg.equals("--only");
            case GTEST, CATCH2, CATCH2_V2, DOCTEST -> false;
        };
    }

    private static final Pattern FILE_LINE = Pattern.compile("(.+\\.(?:rb|exs))(?::\\d+)+");

    private String withoutLine(String arg) {
        if (this == RSPEC || this == MINITEST || this == EXUNIT) {
            var m = FILE_LINE.matcher(arg);
            if (m.matches()) {
                return m.group(1);
            }
        }
        return arg;
    }

    /**
     * {@code wholeFile} narrowed to the tests called {@code names} (Test Results rows' {@link TestNode#methodName}):
     * Rerun Failed, Run This Test. Each runner's own filter, every name literal and whole.
     */
    public List<String> filtered(List<String> wholeFile, List<String> names) {
        if (names.isEmpty()) {
            return wholeFile;
        }
        List<String> add = new ArrayList<>();
        switch (this) {
            case JEST, VITEST -> {
                String join = this == JEST ? " " : "( | > )"; // how each runner joins a test's full name
                List<String> each = new ArrayList<>();
                for (String n : names) {
                    List<String> parts = new ArrayList<>();
                    for (String part : n.split(Pattern.quote(JestJsonReport.SEPARATOR), -1)) {
                        parts.add(regex(part));
                    }
                    each.add(String.join(join, parts));
                }
                add.addAll(List.of("-t", "^(" + String.join("|", each) + ")$"));
            }
            case DENO -> {
                List<String> each = new ArrayList<>();
                for (String n : names) {
                    int step = n.indexOf(" > ");
                    each.add(regex(step >= 0 ? n.substring(0, step) : n).replace("/", "\\/")); // a step: its test
                }
                add.addAll(List.of("--filter", "/^(" + String.join("|", each) + ")$/"));
            }
            case RSPEC -> {
                for (String n : names) {
                    add.addAll(List.of("--example", n)); // matches the full description, with any after it
                }
            }
            case MINITEST -> add.addAll(List.of("-n", "/^(" + alternatives(names) + ")$/"));
            case PHPUNIT -> {
                List<String> each = new ArrayList<>();
                for (String n : names) {
                    int data = n.indexOf(" with data set ");
                    each.add(regex(data >= 0 ? n.substring(0, data) : n));
                }
                add.addAll(List.of("--filter", "/::(" + String.join("|", each) + ")(?: .*)?$/"));
            }
            case EXUNIT -> {
                for (String n : names) {
                    add.addAll(List.of("--only", "test:" + n));
                }
            }
            case DART -> add.addAll(List.of("--name", "^(" + alternatives(names) + ")$"));
            case GTEST -> add.addAll(CppTests.filterArgs(this, names)); // Suite.Test, as the report names it
            case CATCH2, CATCH2_V2 -> {
                List<String> each = new ArrayList<>();
                names.forEach(n -> each.add(CppTests.catchEscape(n)));
                add.addAll(CppTests.filterArgs(this, each));
            }
            case DOCTEST -> {
                List<String> each = new ArrayList<>();
                names.forEach(n -> each.add(n.replace(",", "\\,")));
                add.addAll(CppTests.filterArgs(this, each));
            }
        }
        return insert(wholeFile, add);
    }

    private static String alternatives(List<String> names) {
        List<String> each = new ArrayList<>();
        for (String n : names) {
            each.add(regex(n));
        }
        return String.join("|", each);
    }

    /** {@code s} as a regular expression that matches it literally. */
    static String regex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** {@code add} put into {@code argv} before a {@code --}, else at its end. */
    private static List<String> insert(List<String> argv, List<String> add) {
        List<String> out = new ArrayList<>(argv);
        int dashes = out.indexOf("--");
        out.addAll(dashes >= 0 ? dashes : out.size(), add);
        return List.copyOf(out);
    }

    /**
     * The tests of a report file's {@code text}, run in {@code dir} — the console kinds have none. Empty when it is not
     * a report (the run failed before it wrote one).
     */
    public List<ParsedSuite> readReport(String text, Path dir) {
        return switch (this) {
            case JEST, VITEST -> JestJsonReport.parse(text, dir);
            case RSPEC -> RspecJsonReport.parse(text, dir);
            case DART -> DartJsonReport.parse(text, dir);
            case PHPUNIT, DENO -> JUnitXmlParser.parseAll(text, dir);
            case MINITEST, EXUNIT -> List.of();
            case GTEST -> NativeTestReports.googleTest(text, dir);
            case CATCH2, CATCH2_V2, DOCTEST -> NativeTestReports.catchOrDoctest(text, dir);
        };
    }

    /** The parser of a console kind's output; null for a file kind. */
    public @Nullable TestResultParser consoleParser(Path dir) {
        return switch (this) {
            case MINITEST -> new MinitestOutputParser(dir);
            case EXUNIT -> new ExUnitTraceParser(dir);
            default -> null;
        };
    }

    /**
     * The console formats {@code dotRspec} (a project's {@code .rspec}) names — {@code --format X}, {@code -f X},
     * {@code --format=X}, {@code -fX} — whose console a JSON report would silence.
     */
    public static List<String> rspecFormats(@Nullable String dotRspec) {
        List<String> out = new ArrayList<>();
        if (dotRspec == null) {
            return out;
        }
        String[] words = dotRspec.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String f = null;
            if ((w.equals("--format") || w.equals("-f")) && i + 1 < words.length) {
                f = words[++i];
            } else if (w.startsWith("--format=")) {
                f = w.substring("--format=".length());
            } else if (w.startsWith("-f") && w.length() > 2 && !w.startsWith("-f-")) {
                f = w.substring(2);
            }
            if (f != null && !f.isEmpty() && !f.startsWith("j") && !out.contains(f)) {
                out.add(f); // a json format of its own would write over the console
            }
        }
        return out;
    }
    /**
     * The 1-based line of {@code text} that declares the test called {@code name}, 0 when none does: its title quoted
     * ({@code it("adds"}, {@code test "adds" do}) — a describe path's last part, Minitest's {@code test_} name as Rails
     * and specs write it — or a {@code def name}.
     */
    public static int lineOf(String text, String name) {
        List<String> titles = new ArrayList<>();
        String last = name.contains(JestJsonReport.SEPARATOR)
                ? name.substring(name.lastIndexOf(JestJsonReport.SEPARATOR) + JestJsonReport.SEPARATOR.length())
                : name;
        titles.add(last);
        if (last.startsWith("test_")) {
            String rest = last.substring("test_".length()).replaceFirst("^\\d{4}_", ""); // a spec's it
            titles.add(rest);
            titles.add(rest.replace('_', ' ')); // Rails' test "adds two"
        } else if (last.startsWith("test ")) {
            titles.add(last.substring("test ".length())); // ExUnit's
        }
        String[] lines = text.split("\\R", -1);
        for (String title : titles) {
            if (title.isBlank()) {
                continue;
            }
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                for (char q : new char[] {'"', '\'', '`'}) {
                    if (l.contains(q + title + q)) {
                        return i + 1;
                    }
                }
            }
        }
        Pattern def =
                Pattern.compile("^\\s*(?:public\\s+|static\\s+)*(?:def|function)\\s+" + Pattern.quote(last) + "\\b");
        for (int i = 0; i < lines.length; i++) {
            if (def.matcher(lines[i]).find()) {
                return i + 1;
            }
        }
        return 0;
    }
}
