package com.editora.http;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

/**
 * {@code editora http [options] FILE...}: runs the requests of {@code .http} files from the command line, without a
 * window, the way JetBrains' {@code ijhttp} does — for CI and scripts. Each file runs as the editor's Run All runs
 * it: in file order, one cookie jar, later requests reading earlier responses, its {@code # @assert} checks and its
 * JavaScript handlers (Node.js) run after each response. The same {@link HttpClientService} sends them; only the
 * window's questions are answered differently: whoever typed the command chose to run these files, so secrets
 * ({@code $processEnv}, {@code $dotenv}) are read and handlers run without asking.
 *
 * <p>Environment files are found as the editor finds them ({@link HttpEnvironments#forFile}), unless
 * {@code --env-file} / {@code --private-env-file} name them; {@code -V name=value} sets a variable over all of them.
 *
 * <p>Exit status: 0 when every request got a response and every test passed; 1 when a request failed or was not
 * sent, a test failed, or a handler stopped with an error; 2 for a usage error (an unknown option, a file that cannot
 * be read, an environment no env file defines). A status such as 404 alone is not a failure — an {@code @assert}
 * or {@code client.test} says what is expected.
 */
public final class HttpCli {

    /** The subcommand's name: {@code editora http …}. */
    public static final String COMMAND = "http";

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    /** How much of each exchange is printed. */
    enum LogLevel {
        /** The request line, the status, the tests. */
        BASIC,
        /** Also the request and response headers. */
        HEADERS,
        /** Also the request and response bodies. */
        VERBOSE
    }

    /** The parsed command line. */
    record Options(
            List<Path> files,
            String env,
            @Nullable Path envFile,
            @Nullable Path privateEnvFile,
            Map<String, String> variables,
            List<String> privateValues,
            LogLevel logLevel,
            @Nullable Path reportDir,
            boolean scripts,
            String node,
            boolean help) {}

    private HttpCli() {}

    /** Runs the command for {@code args} (the arguments after {@code http}) and returns the exit status. */
    public static int main(List<String> args) {
        System.setProperty(com.editora.process.ProcessRunner.HEADLESS_PROPERTY, "true");
        com.editora.i18n.Messages.init("en"); // the engine's messages (errors, warnings); this command speaks English
        return run(args, System.out, System.err);
    }

    static int run(List<String> args, PrintStream out, PrintStream err) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("editora http: " + e.getMessage());
            err.println("Run 'editora http --help' for usage.");
            return USAGE;
        }
        if (o.help()) {
            out.println(helpText());
            return OK;
        }
        List<FileRun> runs = new ArrayList<>();
        for (Path file : o.files()) {
            FileRun run;
            try {
                run = prepare(file, o);
            } catch (IllegalArgumentException e) {
                err.println("editora http: " + e.getMessage());
                return USAGE;
            }
            runs.add(run);
        }
        HttpClientService service = new HttpClientService(Runnable::run); // results on the worker: no JavaFX
        Report report = new Report();
        try {
            for (FileRun run : runs) {
                List<HttpExchange> exchanges = send(service, run, o);
                Printer printer = new Printer(out, o.logLevel(), o.privateValues());
                printer.file(run, exchanges);
                report.add(run, exchanges);
            }
        } finally {
            service.shutdown();
        }
        out.println(report.summary());
        if (o.reportDir() != null) {
            Path xml = o.reportDir().resolve("report.xml");
            try {
                Files.createDirectories(o.reportDir());
                Files.writeString(xml, report.junitXml(o.privateValues()), StandardCharsets.UTF_8);
                out.println("Report: " + xml);
            } catch (IOException e) {
                err.println("editora http: could not write " + xml + ": " + e.getMessage());
                return FAILED;
            }
        }
        return report.failures() == 0 ? OK : FAILED;
    }

    // ---------------------------------------------------------------------------------------------------------- parse

    /** The options in {@code args}; throws {@link IllegalArgumentException} with what is wrong. Pure. */
    static Options parse(List<String> args) {
        List<Path> files = new ArrayList<>();
        String env = "";
        Path envFile = null;
        Path privateEnvFile = null;
        Map<String, String> variables = new LinkedHashMap<>();
        List<String> privateValues = new ArrayList<>();
        LogLevel level = LogLevel.BASIC;
        Path reportDir = null;
        boolean scripts = true;
        String node = "";
        boolean help = false;
        boolean filesOnly = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (filesOnly || !a.startsWith("-") || a.equals("-")) {
                files.add(Path.of(a));
                continue;
            }
            String name = a;
            String inline = null;
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 0) {
                name = a.substring(0, eq);
                inline = a.substring(eq + 1);
            }
            if (inline != null && FLAGS.contains(name)) {
                throw new IllegalArgumentException(name + " takes no value");
            }
            String v = "";
            if (VALUED.contains(name)) {
                if (inline != null) {
                    v = inline;
                } else if (i + 1 < args.size()) {
                    v = args.get(++i);
                } else {
                    throw new IllegalArgumentException(name + " needs a value");
                }
            }
            switch (name) {
                case "--" -> filesOnly = true;
                case "-h", "--help" -> help = true;
                case "-e", "--env" -> env = v;
                case "--env-file" -> envFile = Path.of(v);
                case "--private-env-file" -> privateEnvFile = Path.of(v);
                case "-V", "--env-variables" -> pair(v, variables, null);
                case "-P", "--private-env-variables" -> pair(v, variables, privateValues);
                case "-L", "--log-level" -> level = logLevel(v);
                case "-r", "--report" -> reportDir = Path.of(inline == null || inline.isBlank() ? "reports" : inline);
                case "--no-scripts" -> scripts = false;
                case "--node" -> node = v;
                default -> throw new IllegalArgumentException("unknown option: " + a);
            }
        }
        if (!help && files.isEmpty()) {
            throw new IllegalArgumentException("no .http file given");
        }
        return new Options(
                List.copyOf(files),
                env,
                envFile,
                privateEnvFile,
                variables,
                privateValues,
                level,
                reportDir,
                scripts,
                node,
                help);
    }

    /** Options that take a value, as the next argument or after {@code =}. */
    private static final java.util.Set<String> VALUED = java.util.Set.of(
            "-e",
            "--env",
            "--env-file",
            "--private-env-file",
            "-V",
            "--env-variables",
            "-P",
            "--private-env-variables",
            "-L",
            "--log-level",
            "--node");

    /** Options that take none. ({@code --report} takes one only as {@code --report=DIR}.) */
    private static final java.util.Set<String> FLAGS = java.util.Set.of("--", "-h", "--help", "-r", "--no-scripts");

    private static void pair(String nameValue, Map<String, String> into, @Nullable List<String> secrets) {
        int eq = nameValue.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("expected name=value, got: " + nameValue);
        }
        String value = nameValue.substring(eq + 1);
        into.put(nameValue.substring(0, eq).strip(), value);
        if (secrets != null && value.length() >= SecretMask.MIN_LENGTH) {
            secrets.add(value);
        }
    }

    private static LogLevel logLevel(String s) {
        try {
            return LogLevel.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("log level must be BASIC, HEADERS or VERBOSE, got: " + s);
        }
    }

    // -------------------------------------------------------------------------------------------------------- prepare

    /** One file to run: its requests, its folder, the variables it resolves against, their problems. */
    record FileRun(
            Path file,
            List<HttpFile.Request> requests,
            List<HttpFile.Parsed> parsed,
            Map<String, String> variables,
            List<String> problems) {}

    /** Reads and parses {@code file} and its environment; throws {@link IllegalArgumentException} when it cannot. */
    static FileRun prepare(Path file, Options o) {
        Path abs = file.toAbsolutePath().normalize();
        String text;
        try {
            text = Files.readString(abs, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + describe(e));
        }
        List<HttpFile.Request> requests = HttpFile.parse(text);
        List<HttpFile.Parsed> parsed =
                requests.stream().map(HttpFile::parseRequest).toList();
        HttpEnvironments.EnvFiles envFiles = envFiles(abs, o);
        if (!o.env().isEmpty() && !envFiles.names().contains(o.env())) {
            List<String> names = envFiles.names();
            throw new IllegalArgumentException("environment '" + o.env() + "' is not defined for " + file
                    + (names.isEmpty() ? " (no env file found)" : " (defined: " + String.join(", ", names) + ")"));
        }
        Map<String, String> vars = new LinkedHashMap<>(envFiles.variables(o.env()));
        vars.putAll(o.variables());
        return new FileRun(file, requests, parsed, vars, envFiles.problems());
    }

    /** The env files for {@code file}: those named on the command line, else the ones the editor would use. */
    private static HttpEnvironments.EnvFiles envFiles(Path file, Options o) {
        if (o.envFile() == null && o.privateEnvFile() == null) {
            return HttpEnvironments.read(HttpEnvironments.forFile(file, null));
        }
        Path dir = file.getParent();
        Path pub = o.envFile() != null ? o.envFile() : dir == null ? null : dir.resolve(HttpEnv.PUBLIC_FILE);
        Path priv = o.privateEnvFile() != null
                ? o.privateEnvFile()
                : dir == null ? null : dir.resolve(HttpEnv.PRIVATE_FILE);
        return new HttpEnvironments.EnvFiles(dir, readNamed(pub, o.envFile()), readNamed(priv, o.privateEnvFile()))
                .prepare();
    }

    /** {@code file}'s text; a file named on the command line ({@code named}) must exist, a default one may not. */
    private static @Nullable String readNamed(@Nullable Path file, @Nullable Path named) {
        if (file == null || (named == null && !Files.isRegularFile(file))) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + describe(e));
        }
    }

    private static String describe(Exception e) {
        return e instanceof java.nio.file.NoSuchFileException ? "no such file" : String.valueOf(e.getMessage());
    }

    // ----------------------------------------------------------------------------------------------------------- send

    /** Runs every request of {@code run} as Run All does, and waits for them. */
    private static List<HttpExchange> send(HttpClientService service, FileRun run, Options o) {
        Path abs = run.file().toAbsolutePath().normalize();
        Path folder = abs.getParent();
        CompletableFuture<List<HttpExchange>> done = new CompletableFuture<>();
        service.runAll(
                run.parsed(),
                () -> new RunEnvironment.Snapshot(run.variables(), run.problems()),
                folder,
                new CapturedResponses(),
                SecretGate.UNCHECKED, // the user ran this file from the command line: nobody to ask, nothing to ask
                o.scripts() ? scripts(abs, folder, o) : ResponseScripts.OFF,
                i -> {},
                done::complete);
        return done.join();
    }

    /** Runs the file's JavaScript handlers with Node.js; the command line trusts the files it was told to run. */
    private static ResponseScripts scripts(Path file, @Nullable Path folder, Options o) {
        NodeResponseScripts.Trust trusted = new NodeResponseScripts.Trust() {
            @Override
            public SecretGate.Verdict allowRun(BooleanSupplier cancelled) {
                return SecretGate.Verdict.ALLOWED;
            }

            @Override
            public boolean isTrusted(Path script) {
                return true;
            }
        };
        Path shims = com.editora.config.ConfigManager.configDirFor(null, false)
                .resolve("cache")
                .resolve("http-scripts");
        return new NodeResponseScripts(
                new NodeResponseScripts.Settings(
                        true, o.node(), NodeResponseScripts.DEFAULT_TIMEOUT, shims, null, "", folder),
                trusted,
                file,
                folder);
    }

    // ---------------------------------------------------------------------------------------------------------- print

    /** What to mask in what is shown of {@code ex}: the {@code -P} values and the secrets its run read. */
    static List<String> masks(List<String> secrets, HttpExchange ex) {
        List<String> masks = new ArrayList<>(secrets);
        HttpExchange.Details d = ex.details();
        if (d != null) {
            masks.addAll(d.secretValues());
        }
        return masks;
    }

    /** Prints the exchanges of one file, as much of each as the log level asks. */
    static final class Printer {
        private final PrintStream out;
        private final LogLevel level;
        private final List<String> secrets;

        Printer(PrintStream out, LogLevel level, List<String> secrets) {
            this.out = out;
            this.level = level;
            this.secrets = secrets;
        }

        void file(FileRun run, List<HttpExchange> exchanges) {
            out.println("=== " + run.file());
            for (int i = 0; i < exchanges.size(); i++) {
                HttpFile.Request req =
                        i < run.requests().size() ? run.requests().get(i) : null;
                exchange(req, exchanges.get(i));
            }
        }

        private void exchange(HttpFile.@Nullable Request req, HttpExchange ex) {
            List<String> masks = masks(secrets, ex);
            String title = req == null ? null : req.name();
            out.println();
            out.println("### " + (title == null || title.isBlank() ? ex.method() + " " + mask(ex.url(), masks) : title)
                    + (req == null ? "" : "  (line " + (req.startLine() + 1) + ")"));
            out.println(ex.method() + " " + mask(ex.url(), masks));
            if (level != LogLevel.BASIC) {
                for (String[] h : ex.headers()) {
                    out.println("> " + h[0] + ": " + mask(h[1], masks));
                }
            }
            if (level == LogLevel.VERBOSE && !ex.requestBody().isEmpty()) {
                out.println();
                out.println(indent(mask(ex.requestBody(), masks)));
            }
            HttpResult r = ex.result();
            if (r.failed()) {
                out.println("FAILED: " + mask(r.shownError(), masks));
            } else {
                out.println("<- " + r.status() + (r.protocol() == null ? "" : " " + r.protocol()) + "  ("
                        + r.elapsedMs() + " ms, " + HttpResponseFormat.humanSize(r.sizeBytes()) + ")");
                if (level != LogLevel.BASIC) {
                    for (String[] h : r.headers()) {
                        out.println("< " + h[0] + ": " + mask(h[1], masks));
                    }
                }
                if (level == LogLevel.VERBOSE) {
                    out.println();
                    out.println(indent(
                            r.binary()
                                    ? "[binary body, " + HttpResponseFormat.humanSize(r.sizeBytes()) + "]"
                                    : mask(HttpResponseFormat.prettyBody(r.body(), r.contentType()), masks)));
                }
                for (String w : r.written()) {
                    out.println("Saved to " + w);
                }
            }
            for (String w : r.warnings() == null ? List.<String>of() : r.warnings()) {
                out.println("warning: " + mask(w, masks));
            }
            HttpTests t = r.tests();
            for (String line : t.log()) {
                out.println("log: " + mask(line, masks));
            }
            for (HttpTests.Test test : t.tests()) {
                out.println((test.passed() ? "  PASS " : "  FAIL ")
                        + mask(test.name(), masks)
                        + (test.passed() || test.message().isEmpty() ? "" : ": " + mask(test.message(), masks)));
            }
            if (t.error() != null) {
                out.println("  ERROR in handler: " + mask(t.error(), masks));
            }
        }

        private static String mask(@Nullable String text, List<String> secrets) {
            String m = SecretMask.mask(text, secrets);
            return m == null ? "" : m;
        }

        private static String indent(String text) {
            return "    " + text.replace("\n", "\n    ");
        }
    }

    // --------------------------------------------------------------------------------------------------------- report

    /** The totals of the whole command, and its JUnit XML report. */
    static final class Report {
        private record Entry(FileRun run, List<HttpExchange> exchanges) {}

        private final List<Entry> entries = new ArrayList<>();
        private int requests;
        private int failedRequests;
        private int tests;
        private int failedTests;

        void add(FileRun run, List<HttpExchange> exchanges) {
            entries.add(new Entry(run, exchanges));
            for (HttpExchange ex : exchanges) {
                requests++;
                HttpResult r = ex.result();
                if (r.failed()) {
                    failedRequests++;
                }
                tests += r.tests().tests().size();
                failedTests += r.tests().failed() + (r.tests().error() != null ? 1 : 0);
            }
        }

        /** Failed requests plus failed tests and handler errors. */
        int failures() {
            return failedRequests + failedTests;
        }

        String summary() {
            return "\n" + requests + (requests == 1 ? " request" : " requests") + ", " + failedRequests + " failed; "
                    + tests + (tests == 1 ? " test" : " tests") + ", " + failedTests + " failed";
        }

        /**
         * JUnit XML: a test suite per file, a test case per request — or, for a request with tests, one per test
         * ("request: test"). A request that failed is an {@code <error>}, a failed test a {@code <failure>}.
         */
        String junitXml(List<String> secrets) {
            StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
            for (Entry e : entries) {
                StringBuilder cases = new StringBuilder();
                int count = 0;
                int failures = 0;
                int errors = 0;
                double seconds = 0;
                for (int i = 0; i < e.exchanges().size(); i++) {
                    HttpExchange ex = e.exchanges().get(i);
                    HttpResult r = ex.result();
                    List<String> masks = masks(secrets, ex);
                    String given = i < e.run().requests().size()
                            ? e.run().requests().get(i).name()
                            : null;
                    String name = given != null && !given.isBlank() ? given : ex.method() + " " + ex.url();
                    name = Printer.mask(name, masks);
                    double time = r.elapsedMs() / 1000.0;
                    seconds += time;
                    if (r.failed()) {
                        count++;
                        errors++;
                        cases.append(testCase(name, time, "error", Printer.mask(r.shownError(), masks)));
                        continue;
                    }
                    HttpTests t = r.tests();
                    if (t.tests().isEmpty() && t.error() == null) {
                        count++;
                        cases.append(testCase(name, time, null, null));
                        continue;
                    }
                    for (HttpTests.Test test : t.tests()) {
                        count++;
                        String caseName = name + ": " + Printer.mask(test.name(), masks);
                        if (test.passed()) {
                            cases.append(testCase(caseName, 0, null, null));
                        } else {
                            failures++;
                            cases.append(testCase(caseName, 0, "failure", Printer.mask(test.message(), masks)));
                        }
                    }
                    if (t.error() != null) {
                        count++;
                        errors++;
                        cases.append(testCase(name + ": handler", 0, "error", Printer.mask(t.error(), masks)));
                    }
                }
                b.append("  <testsuite name=\"")
                        .append(xml(e.run().file().toString()))
                        .append("\" tests=\"")
                        .append(count)
                        .append("\" failures=\"")
                        .append(failures)
                        .append("\" errors=\"")
                        .append(errors)
                        .append("\" time=\"")
                        .append(String.format(Locale.ROOT, "%.3f", seconds))
                        .append("\">\n")
                        .append(cases)
                        .append("  </testsuite>\n");
            }
            return b.append("</testsuites>\n").toString();
        }

        private static String testCase(String name, double time, @Nullable String kind, @Nullable String message) {
            String open = "    <testcase name=\"" + xml(name) + "\" time=\"" + String.format(Locale.ROOT, "%.3f", time)
                    + "\"";
            if (kind == null) {
                return open + "/>\n";
            }
            String m = message == null ? "" : xml(message);
            return open + ">\n      <" + kind + " message=\"" + m + "\">" + m + "</" + kind + ">\n    </testcase>\n";
        }

        static String xml(String s) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '&' -> b.append("&amp;");
                    case '<' -> b.append("&lt;");
                    case '>' -> b.append("&gt;");
                    case '"' -> b.append("&quot;");
                    case '\'' -> b.append("&apos;");
                    default -> {
                        if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') {
                            b.append('?'); // not allowed in XML 1.0
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            return b.toString();
        }
    }

    // ----------------------------------------------------------------------------------------------------------- help

    static String helpText() {
        return """
                Usage: editora http [options] FILE...

                Runs every request of each .http FILE, in order, as Run All does in the editor:
                one cookie jar per file, later requests can use earlier responses, and each
                request's # @assert checks and JavaScript handlers run after its response.

                Options:
                  -e, --env <name>                 Use environment <name> from the env files
                      --env-file <file>            Public env file (default: http-client.env.json,
                                                   found as the editor finds it)
                      --private-env-file <file>    Private env file (default:
                                                   http-client.private.env.json)
                  -V, --env-variables <name=value> Set a variable over the env files (repeatable)
                  -P, --private-env-variables <name=value>
                                                   The same, and masked in what is printed
                  -L, --log-level <level>          BASIC (default), HEADERS or VERBOSE (bodies too)
                  -r, --report[=<dir>]             Write a JUnit XML report to <dir>/report.xml
                                                   (default dir: reports)
                      --no-scripts                 Do not run JavaScript handlers or pre-request
                                                   scripts (they need Node.js)
                      --node <path>                The Node.js to run them with (default: node)
                  -h, --help                       Print this help and exit

                Secrets ($processEnv, $env, $dotenv) are read and scripts run without asking:
                running the command is the consent.

                Exit status: 0 all requests got a response and all tests passed; 1 a request
                failed or a test failed; 2 a usage error.

                On Windows, run it as editora-cli http ... (the console launcher; Editora.exe
                has no console).""";
    }
}
