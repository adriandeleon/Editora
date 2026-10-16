package com.editora.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.editora.i18n.Messages.tr;

/**
 * What the {@code {{variables}}} of a {@code .http} document can resolve to while it is edited, without running
 * anything: the editor marks the references that cannot resolve and completes the names that can. Shared by
 * the squiggles and the {@code {{} completion so the two never disagree.
 *
 * <p>The resolution is static and follows the run's own rules ({@link HttpVars#scope}): the selected
 * environment (both env files, already merged into {@link Context#environment()}), the {@code @var} lines
 * above the reference, the dynamic built-ins (an environment variable must be set in this process, a
 * {@code $dotenv} key must be in the {@code .env} beside the file), and {@code {{name.response…}}} when the
 * file has a request called {@code name} — whether it has run yet is not known here, so it is not marked.
 * Comment lines and JavaScript handler/pre-request scripts are not looked at, as the run does not send them.
 * Pure apart from reading the {@code .env} file for {@code $dotenv}.
 */
public final class HttpVariableIndex {

    /**
     * The environment the document is analysed against: the selected environment's variables, the folder of the
     * {@code .env} file and its keys (read once, when the context is built off the FX thread, so the completion
     * popup never reads a file), and — for better reasons — which environments define each name when none is
     * selected ({@code definedIn}), and why an env file could not be read ({@code envProblems}).
     */
    public record Context(
            Map<String, String> environment,
            Path dotenvDir,
            Set<String> dotenvKeys,
            Map<String, List<String>> definedIn,
            List<String> envProblems) {
        public static final Context EMPTY = new Context(Map.of(), null, Set.of(), Map.of(), List.of());

        public Context {
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            dotenvKeys = dotenvKeys == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(dotenvKeys));
            definedIn = definedIn == null ? Map.of() : Map.copyOf(definedIn);
            envProblems = envProblems == null ? List.of() : List.copyOf(envProblems);
        }

        /** The selected environment and the {@code .env} beside the file in {@code dotenvDir} (read now). */
        public Context(Map<String, String> environment, Path dotenvDir) {
            this(environment, dotenvDir, readDotenvKeys(dotenvDir), Map.of(), List.of());
        }

        /** The keys of the {@code .env} file in {@code dir} (for {@code $dotenv} completion), or none. */
        public static Set<String> readDotenvKeys(Path dir) {
            if (dir == null) {
                return Set.of();
            }
            try {
                Path env = dir.resolve(".env");
                return Files.isRegularFile(env) && Files.size(env) <= 1_000_000
                        ? new TreeSet<>(DotEnv.parse(Files.readString(env)).keySet())
                        : Set.of();
            } catch (Exception e) {
                return Set.of();
            }
        }
    }

    /**
     * A reference that cannot resolve: its 0-based {@code line} and {@code column}, its {@code length} (the whole
     * {@code {{…}}}), the {@code reference} as written, and a short {@code reason} in the UI language.
     */
    public record Problem(int line, int column, int length, String reference, String reason) {}

    /** At most this many problems are reported for one document (a file full of them needs no more). */
    public static final int MAX_PROBLEMS = 500;

    /** The same {@code {{…}}} scanner a run uses, so a mark sits exactly where a reference is resolved. */
    private static final Pattern VAR = HttpVars.referencePattern();

    /** The dynamic built-ins offered after {@code {{}, in the form they are usually written. */
    private static final List<String> DYNAMIC = List.of(
            "$uuid",
            "$random.uuid",
            "$timestamp",
            "$isoTimestamp",
            "$randomInt",
            "$random.integer(0, 100)",
            "$random.float(0, 1)",
            "$random.alphabetic(10)",
            "$random.alphanumeric(10)",
            "$random.hexadecimal(10)",
            "$random.email",
            "$datetime iso8601",
            "$datetime rfc1123",
            "$datetime \"yyyy-MM-dd\"",
            "$localDatetime iso8601",
            "$processEnv NAME",
            "$env.NAME");

    private HttpVariableIndex() {}

    /** The dynamic built-ins, in a useful order. */
    public static List<String> dynamicVariables() {
        return DYNAMIC;
    }

    /** The names of the document's requests ({@code # @name} or {@code ###} title), in order, without repeats. */
    public static List<String> requestNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        for (HttpFile.Request r : HttpFile.parse(text)) {
            if (r.name() != null && !r.name().isBlank()) {
                names.add(r.name());
            }
        }
        return List.copyOf(names);
    }

    /**
     * The variable names a reference on {@code line} can use: the environment's, then the {@code @var} lines
     * declared above it (outside request bodies), sorted, without repeats.
     */
    public static List<String> variablesAt(String text, int line, Context ctx) {
        Set<String> names =
                new TreeSet<>(ctx == null ? Set.of() : ctx.environment().keySet());
        Walk walk = new Walk(text);
        for (int k = 0; k < walk.lines.length && k < line; k++) {
            String t = walk.lines[k].strip();
            if (walk.request(k) == null && HttpFile.isVarDecl(t)) {
                names.add(HttpFile.varPair(t)[0]);
            }
        }
        return List.copyOf(names);
    }

    /** The references in {@code text} that cannot resolve against {@code ctx}, in document order. */
    public static List<Problem> unresolved(String text, Context ctx, ZonedDateTime now) {
        List<Problem> out = new ArrayList<>();
        if (text == null || text.indexOf("{{") < 0) {
            return out;
        }
        Context c = ctx == null ? Context.EMPTY : ctx;
        ZonedDateTime clock = now == null ? ZonedDateTime.now() : now;
        Walk walk = new Walk(text);
        Set<String> requests = new LinkedHashSet<>(requestNames(text));
        List<String[]> declared = new ArrayList<>();
        HttpVars.Scope scope = null;
        int scopeSize = -1;
        boolean inScript = false;
        for (int k = 0; k < walk.lines.length && out.size() < MAX_PROBLEMS; k++) {
            String line = walk.lines[k];
            String t = line.strip();
            if (inScript) {
                inScript = !t.contains("%}");
                continue;
            }
            if (isScriptStart(t)) {
                inScript = !t.substring(t.indexOf("{%") + 2).contains("%}");
                continue;
            }
            if (t.isEmpty() || HttpFile.isSeparator(t) || HttpFile.isComment(t) || isScriptFile(t)) {
                continue;
            }
            if (line.indexOf("{{") < 0 && !(walk.request(k) == null && HttpFile.isVarDecl(t))) {
                continue;
            }
            if (scope == null || scopeSize != declared.size()) {
                scope = HttpVars.scope(c.environment(), declared, clock, null, c.dotenvDir());
                scopeSize = declared.size();
            }
            boolean declaration = walk.request(k) == null && HttpFile.isVarDecl(t);
            check(k, line, declaration ? line.indexOf('=') + 1 : 0, scope, requests, c, clock, out);
            if (declaration) {
                declared.add(HttpFile.varPair(t));
            }
        }
        return out;
    }

    private static void check(
            int lineNo,
            String line,
            int from,
            HttpVars.Scope scope,
            Set<String> requests,
            Context ctx,
            ZonedDateTime now,
            List<Problem> out) {
        Matcher m = VAR.matcher(line);
        m.region(Math.max(0, from), line.length());
        while (m.find() && out.size() < MAX_PROBLEMS) {
            String name = m.group(1).strip();
            String ref = "{{" + name + "}}";
            String reason = reasonFor(name, scope, requests, ctx, now);
            if (reason != null) {
                out.add(new Problem(lineNo, m.start(), m.end() - m.start(), ref, reason));
            }
        }
    }

    /** Why {@code name} cannot resolve, or {@code null} when it can. */
    private static String reasonFor(
            String name, HttpVars.Scope scope, Set<String> requests, Context ctx, ZonedDateTime now) {
        String[] why = new String[1];
        if (name.isEmpty()) {
            return tr("http.unresolved.reason.undefined");
        }
        if (DynamicVars.isDynamic(name)) {
            return DynamicVars.resolve(name, now, ctx.dotenvDir(), why) == null ? why[0] : null;
        }
        if (scope.has(name)) {
            HttpVars.Unresolved inner = new HttpVars.Unresolved();
            scope.value(name, inner);
            String undefined = tr("http.unresolved.reason.undefined");
            for (Map.Entry<String, String> e : inner.entries().entrySet()) {
                String innerName = e.getKey().substring(2, e.getKey().length() - 2);
                if (CapturedResponses.isResponseRef(innerName) && requests.contains(requestOf(innerName))) {
                    continue; // a response of a request in this file: resolved when that request has run
                }
                String innerWhy = undefined.equals(e.getValue()) ? undefined(innerName.strip(), ctx) : e.getValue();
                return tr("http.lint.uses", e.getKey(), innerWhy);
            }
            return null;
        }
        if (CapturedResponses.isResponseRef(name)) {
            String request = requestOf(name);
            return requests.contains(request) ? null : tr("http.unresolved.reason.noRequest", request);
        }
        return undefined(name, ctx);
    }

    /**
     * Why {@code name} is not defined, as helpfully as the context allows: the environments that would define it
     * when none is selected, else the env files that could not be read, else plainly "not defined".
     */
    private static String undefined(String name, Context ctx) {
        List<String> envs = ctx.definedIn().get(name);
        if (envs != null && !envs.isEmpty()) {
            return tr("http.unresolved.reason.noEnvironment", String.join(", ", envs));
        }
        if (!ctx.envProblems().isEmpty()) {
            return tr("http.unresolved.reason.envUnreadable", String.join("; ", ctx.envProblems()));
        }
        return tr("http.unresolved.reason.undefined");
    }

    private static String requestOf(String responseRef) {
        return responseRef.substring(0, responseRef.indexOf(".response."));
    }

    /** {@code > {%} or {@code < {%}: the start of an inline JavaScript block. */
    private static boolean isScriptStart(String t) {
        return (t.startsWith(">") || t.startsWith("<"))
                && !t.startsWith(">>")
                && t.substring(1).strip().startsWith("{%");
    }

    /** {@code > ./handler.js} or {@code < ./script.js}: a script file reference. */
    private static boolean isScriptFile(String t) {
        return (t.startsWith("> ") || t.startsWith("< "))
                && t.toLowerCase(java.util.Locale.ROOT).endsWith(".js");
    }

    /** The document's lines and which request (if any) each line belongs to. */
    private static final class Walk {
        final String[] lines;
        private final HttpFile.Request[] owner;

        Walk(String text) {
            lines = (text == null ? "" : text).split("\n", -1);
            owner = new HttpFile.Request[lines.length];
            for (HttpFile.Request r : HttpFile.parse(text)) {
                for (int k = r.startLine(); k <= r.sectionEnd() && k < lines.length; k++) {
                    owner[k] = r;
                }
            }
        }

        /** The request whose lines (request line to the end of its section) include {@code k}, else null. */
        HttpFile.Request request(int k) {
            return k >= 0 && k < owner.length ? owner[k] : null;
        }
    }
}
