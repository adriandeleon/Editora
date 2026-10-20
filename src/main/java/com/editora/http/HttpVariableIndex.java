package com.editora.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

import org.jspecify.annotations.Nullable;

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
 * file has a request called {@code name} — whether it has run yet is not known here, so it is not marked. Nor is
 * a name the file sets as a session variable ({@link #sessionNames}: a {@code # @capture} line, or a script's
 * {@code client.global.set("name", …)} or {@code request.variables.set("name", …)}, inline or in a script file the
 * file names, {@link Context#scriptNames()}), which resolves once that request has run.
 * Comment lines and JavaScript handler/pre-request scripts are not looked at, as the run does not send them.
 * Pure apart from reading the {@code .env} file for {@code $dotenv} and the script files for their names, both when
 * a {@link Context} is built.
 */
public final class HttpVariableIndex {

    /**
     * The environment the document is analysed against: the selected environment's variables, the folder of the
     * {@code .env} file and its keys (read once, when the context is built off the FX thread, so the completion
     * popup never reads a file), and — for better reasons — which environments define each name when none is
     * selected ({@code definedIn}), why an env file could not be read ({@code envProblems}), and the names the
     * document's script files set ({@code scriptNames}, {@link #scriptFileNames}, read with the rest).
     */
    public record Context(
            Map<String, String> environment,
            @Nullable Path dotenvDir,
            Set<String> dotenvKeys,
            Map<String, List<String>> definedIn,
            List<String> envProblems,
            Set<String> scriptNames) {
        public static final Context EMPTY = new Context(Map.of(), null, Set.of(), Map.of(), List.of());

        public Context {
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            dotenvKeys = dotenvKeys == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(dotenvKeys));
            definedIn = definedIn == null ? Map.of() : Map.copyOf(definedIn);
            envProblems = envProblems == null ? List.of() : List.copyOf(envProblems);
            scriptNames = scriptNames == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(scriptNames));
        }

        /** A context whose document names no script file. */
        public Context(
                Map<String, String> environment,
                @Nullable Path dotenvDir,
                Set<String> dotenvKeys,
                Map<String, List<String>> definedIn,
                List<String> envProblems) {
            this(environment, dotenvDir, dotenvKeys, definedIn, envProblems, Set.of());
        }

        /** The selected environment and the {@code .env} beside the file in {@code dotenvDir} (read now). */
        public Context(Map<String, String> environment, Path dotenvDir) {
            this(environment, dotenvDir, readDotenvKeys(dotenvDir), Map.of(), List.of());
        }

        /**
         * The keys of the {@code .env} file in {@code dir} (for {@code $dotenv} completion), or none — read through
         * {@link DynamicVars#dotenvValues}, the run's own reader, so a {@code .env} the run refuses (a link out of the
         * folder, not a regular file, too large) lists no keys either.
         */
        public static Set<String> readDotenvKeys(@Nullable Path dir) {
            Map<String, String> values = dir == null ? null : DynamicVars.dotenvValues(dir);
            return values == null ? Set.of() : new TreeSet<>(values.keySet());
        }
    }

    /**
     * A reference that cannot resolve: its 0-based {@code line} and {@code column}, its {@code length} (the whole
     * {@code {{…}}}), the {@code reference} as written, and a short {@code reason} in the UI language.
     */
    public record Problem(int line, int column, int length, String reference, String reason) {}

    /** At most this many problems are reported for one document (a file full of them needs no more). */
    public static final int MAX_PROBLEMS = 500;

    /** {@code client.global.set("name"} or {@code request.variables.set("name"} with a literal name, in an inline script. */
    private static final Pattern GLOBAL_SET = Pattern.compile(
            "(?:client\\.global|request\\.variables)\\.set\\(\\s*([\"'`])([A-Za-z_][A-Za-z0-9_.-]*)\\1");

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
     * The variables the document's scripts set when its requests run: the names of its {@code # @capture} lines and
     * the literal names its inline scripts pass to {@code client.global.set} or {@code request.variables.set}. A script
     * file is not read here: its names come from {@link #scriptFileNames}, through {@link Context#scriptNames()}.
     */
    public static Set<String> sessionNames(String text) {
        Set<String> names = new TreeSet<>();
        if (text == null || text.isEmpty()) {
            return names;
        }
        for (HttpFile.Request r : HttpFile.parse(text)) {
            for (HttpChecks.Check c : r.directives().checks()) {
                if (c.isCapture()) {
                    names.add(c.captureName());
                }
            }
        }
        names.addAll(setNames(text));
        return names;
    }

    /**
     * The literal names the script files {@code text} runs ({@code < ./pre.js} above a request line, {@code > ./check.js}
     * after it) pass to {@code client.global.set} or {@code request.variables.set}, each file resolved against
     * {@code dir} as a run resolves it. A file that is missing, not a regular file or larger than a run reads
     * ({@link NodeResponseScripts#MAX_SCRIPT_BYTES}) adds nothing. Reading a file only to find these names runs
     * nothing, so it does not ask for the folder's trust. Reads files: call it off the FX thread.
     */
    public static Set<String> scriptFileNames(String text, @Nullable Path dir) {
        Set<String> names = new TreeSet<>();
        if (dir == null || text == null || text.isEmpty()) {
            return names;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (HttpFile.Request r : HttpFile.parse(text)) {
            List<HttpFile.Handler> scripts = new ArrayList<>(r.preScripts());
            scripts.addAll(HttpFile.parseRequest(r).handlers());
            for (HttpFile.Handler h : scripts) {
                String path = h.path(); // null for an inline handler
                if (path != null && !path.isEmpty() && seen.add(path)) {
                    names.addAll(setNames(readScript(dir, path)));
                }
            }
        }
        return names;
    }

    /** The script file {@code written} in {@code dir}, as a run would read it, or "" when a run would not. */
    private static String readScript(Path dir, String written) {
        try {
            Path real = dir.resolve(written).normalize().toRealPath();
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(real) > NodeResponseScripts.MAX_SCRIPT_BYTES) {
                return "";
            }
            return Files.readString(real, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) { // missing, unreadable, not UTF-8, a bad path
            return "";
        }
    }

    /** The literal names {@code script} passes to {@code client.global.set} or {@code request.variables.set}. */
    private static Set<String> setNames(String script) {
        Set<String> names = new TreeSet<>();
        if (script.contains("client.global.set") || script.contains("request.variables.set")) {
            Matcher m = GLOBAL_SET.matcher(script);
            while (m.find()) {
                names.add(m.group(2));
            }
        }
        return names;
    }

    /**
     * The variable names a reference on {@code line} can use: the environment's, then the {@code @var} lines
     * declared above it (outside request bodies), and the session variables the document sets, sorted, without
     * repeats.
     */
    public static List<String> variablesAt(String text, int line, Context ctx) {
        Set<String> names =
                new TreeSet<>(ctx == null ? Set.of() : ctx.environment().keySet());
        names.addAll(sessionNames(text));
        if (ctx != null) {
            names.addAll(ctx.scriptNames());
        }
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
        Set<String> session = sessionNames(text);
        session.addAll(c.scriptNames());
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
            check(k, line, declaration ? line.indexOf('=') + 1 : 0, scope, requests, session, c, clock, out);
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
            Set<String> session,
            Context ctx,
            ZonedDateTime now,
            List<Problem> out) {
        Matcher m = VAR.matcher(line);
        m.region(Math.max(0, from), line.length());
        while (m.find() && out.size() < MAX_PROBLEMS) {
            String name = m.group(1).strip();
            String ref = "{{" + name + "}}";
            String reason = reasonFor(name, scope, requests, session, ctx, now);
            if (reason != null) {
                out.add(new Problem(lineNo, m.start(), m.end() - m.start(), ref, reason));
            }
        }
    }

    /** Why {@code name} cannot resolve, or {@code null} when it can. */
    private static @Nullable String reasonFor(
            String name,
            HttpVars.Scope scope,
            Set<String> requests,
            Set<String> session,
            Context ctx,
            ZonedDateTime now) {
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
                if (session.contains(innerName.strip())) {
                    continue; // set by a request of this file when it runs
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
        if (session.contains(name)) {
            return null; // a @capture or client.global.set of this file sets it when its request runs
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
        HttpFile.@Nullable Request request(int k) {
            return k >= 0 && k < owner.length ? owner[k] : null;
        }
    }
}
