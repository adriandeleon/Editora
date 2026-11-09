package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/**
 * A project's {@code .vscode/launch.json} (and {@code tasks.json}), read as VS Code reads them: JSON with comments and
 * trailing commas, each configuration's {@code linux} / {@code osx} / {@code windows} part laid over it, and VS Code's
 * variables ({@code ${workspaceFolder}}, {@code ${file}}, {@code ${env:NAME}}, {@code ${input:id}} …) put in.
 * Pure but for {@link #find}.
 */
public final class LaunchJson {

    private LaunchJson() {}

    /** A configuration: its name, {@code type}, {@code request}, and the whole object as written (Java values). */
    public record Configuration(String name, String type, String request, Map<String, Object> body) {}

    /** One of {@code inputs}: a {@code promptString} or {@code pickString} a {@code ${input:id}} asks. */
    public record Input(String id, String type, String description, String defaultValue, List<String> options) {}

    /** What a launch.json (or tasks.json) holds. */
    public record File(
            List<Configuration> configurations, Map<String, Input> inputs, List<Map<String, Object>> tasks) {}

    /** A launch.json (or tasks.json) that does not parse, or a variable that cannot be put in. */
    public static final class LaunchJsonException extends Exception {
        public LaunchJsonException(String message) {
            super(message);
        }
    }

    /** An {@code ${input:…}} prompt the user cancelled: nothing is started. */
    public static final class Cancelled extends Exception {
        public Cancelled() {
            super("cancelled");
        }
    }

    /**
     * The {@code .vscode/launch.json} for a debug session: the project's, else the nearest one in a folder above
     * {@code file}; null when there is none.
     */
    public static @Nullable Path find(@Nullable Path projectRoot, @Nullable Path file) {
        if (projectRoot != null) {
            Path p = projectRoot.resolve(".vscode").resolve("launch.json");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        if (file != null) {
            for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
                Path p = dir.resolve(".vscode").resolve("launch.json");
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Reads {@code text} (launch.json or tasks.json). */
    public static File parse(String text) throws LaunchJsonException {
        JsonElement root;
        try {
            root = JsonParser.parseString(withoutCommentsAndTrailingCommas(text));
        } catch (RuntimeException e) {
            throw new LaunchJsonException(String.valueOf(e.getMessage()));
        }
        if (!root.isJsonObject()) {
            throw new LaunchJsonException("not a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        List<Configuration> configurations = new ArrayList<>();
        if (o.get("configurations") instanceof JsonArray configs) {
            for (JsonElement c : configs) {
                if (c.isJsonObject() && toJava(c) instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) m;
                    configurations.add(new Configuration(
                            text(body.get("name")), text(body.get("type")), text(body.get("request")), body));
                }
            }
        }
        Map<String, Input> inputs = new LinkedHashMap<>();
        if (o.get("inputs") instanceof JsonArray ins) {
            for (JsonElement i : ins) {
                if (!i.isJsonObject()) {
                    continue;
                }
                JsonObject in = i.getAsJsonObject();
                List<String> options = new ArrayList<>();
                if (in.get("options") instanceof JsonArray opts) {
                    for (JsonElement opt : opts) {
                        if (opt.isJsonPrimitive()) {
                            options.add(opt.getAsString());
                        } else if (opt.isJsonObject() && opt.getAsJsonObject().has("value")) {
                            options.add(opt.getAsJsonObject().get("value").getAsString());
                        }
                    }
                }
                Input input =
                        new Input(str(in, "id"), str(in, "type"), str(in, "description"), str(in, "default"), options);
                inputs.put(input.id(), input);
            }
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (o.get("tasks") instanceof JsonArray ts) {
            for (JsonElement t : ts) {
                if (t.isJsonObject() && toJava(t) instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> task = (Map<String, Object>) m;
                    tasks.add(task);
                }
            }
        }
        return new File(configurations, inputs, tasks);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static String text(@Nullable Object o) {
        return o instanceof String s ? s : "";
    }

    /** {@code text} without {@code //} and {@code /* *}{@code /} comments and with no comma before a {@code ]} or {@code }}. */
    static String withoutCommentsAndTrailingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && text.charAt(j) != '"') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(text, i, Math.min(n, j + 1));
                i = j;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                out.append(' ');
            } else if (c == ',') {
                int j = i + 1;
                while (j < n && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                // a comma before a closer — or before a comment that leads to one — is dropped
                String rest = withoutCommentsAhead(text, j);
                if (!(rest.startsWith("]") || rest.startsWith("}"))) {
                    out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The first non-blank, non-comment text from {@code from} (a few characters of it). */
    private static String withoutCommentsAhead(String text, int from) {
        int i = from;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                return text.substring(i, Math.min(n, i + 1));
            }
        }
        return "";
    }

    /** A JSON value as plain Java: maps (in order), lists, strings, booleans, longs (whole numbers) and doubles. */
    static @Nullable Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var entry : e.getAsJsonObject().entrySet()) {
                m.put(entry.getKey(), toJava(entry.getValue()));
            }
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                l.add(toJava(x));
            }
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9e15 ? (Object) (long) d : (Object) d;
        }
        return p.getAsString();
    }

    /** VS Code's name of this operating system's part of a configuration: {@code linux}, {@code osx}, {@code windows}. */
    public static String osKey(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
    }

    /** {@code body} with its {@code osKey} part laid over it (and none of the three parts left in). */
    public static Map<String, Object> forPlatform(Map<String, Object> body, String osKey) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        Object own = out.get(osKey);
        out.remove("linux");
        out.remove("osx");
        out.remove("windows");
        if (own instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    /**
     * What VS Code's variables stand for in this launch.
     *
     * @param workspaceFolder the folder holding {@code .vscode}
     * @param file the active file, or null
     * @param line its caret's 1-based line
     * @param selectedText the selection
     * @param env the environment
     * @param home the user's home folder
     * @param input asks an {@code ${input:…}} (null: the user cancelled)
     */
    public record Context(
            Path workspaceFolder,
            @Nullable Path file,
            int line,
            String selectedText,
            Map<String, String> env,
            Path home,
            Function<Input, @Nullable String> input) {}

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * {@code value} (a string, list or map, recursively) with every {@code ${…}} put in. An {@code ${input:id}} is asked
     * once per launch ({@code answers}). Throws for a variable that cannot be: an unknown input, {@code ${file}} with
     * no file open, {@code ${command:…}} and {@code ${config:…}} (VS Code's own commands and settings).
     */
    public static @Nullable Object substitute(
            @Nullable Object value, Context ctx, Map<String, Input> inputs, Map<String, String> answers)
            throws LaunchJsonException, Cancelled {
        if (value instanceof String s) {
            return substituteString(s, ctx, inputs, answers);
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) {
                out.add(substitute(x, ctx, inputs, answers));
            }
            return out;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), substitute(e.getValue(), ctx, inputs, answers));
            }
            return out;
        }
        return value;
    }

    private static String substituteString(
            String s, Context ctx, Map<String, Input> inputs, Map<String, String> answers)
            throws LaunchJsonException, Cancelled {
        Matcher m = VARIABLE.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(variable(m.group(1), ctx, inputs, answers));
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    private static String variable(String name, Context ctx, Map<String, Input> inputs, Map<String, String> answers)
            throws LaunchJsonException, Cancelled {
        Path ws = ctx.workspaceFolder();
        if (name.startsWith("env:")) {
            return ctx.env().getOrDefault(name.substring(4), "");
        }
        if (name.startsWith("input:")) {
            String id = name.substring(6);
            String known = answers.get(id);
            if (known != null) {
                return known;
            }
            Input in = inputs.get(id);
            if (in == null) {
                throw new LaunchJsonException("${" + name + "}: no input \"" + id + "\" in launch.json");
            }
            String answer = ctx.input().apply(in);
            if (answer == null) {
                throw new Cancelled();
            }
            answers.put(id, answer);
            return answer;
        }
        switch (name) {
            case "workspaceFolder", "workspaceRoot" -> {
                return ws.toString();
            }
            case "workspaceFolderBasename" -> {
                return String.valueOf(ws.getFileName());
            }
            case "userHome" -> {
                return ctx.home().toString();
            }
            case "pathSeparator", "/" -> {
                return java.io.File.separator;
            }
            case "cwd" -> {
                return ws.toString();
            }
            case "lineNumber" -> {
                return String.valueOf(ctx.line());
            }
            case "selectedText" -> {
                return ctx.selectedText();
            }
            default -> {
                // the file variables, below
            }
        }
        Path file = ctx.file();
        if (name.startsWith("file") || name.equals("relativeFile") || name.equals("relativeFileDirname")) {
            if (file == null) {
                throw new LaunchJsonException("${" + name + "}: no file is open");
            }
            Path abs = file.toAbsolutePath();
            String base = String.valueOf(abs.getFileName());
            int dot = base.lastIndexOf('.');
            Path dir = abs.getParent();
            switch (name) {
                case "file" -> {
                    return abs.toString();
                }
                case "fileBasename" -> {
                    return base;
                }
                case "fileBasenameNoExtension" -> {
                    return dot > 0 ? base.substring(0, dot) : base;
                }
                case "fileExtname" -> {
                    return dot > 0 ? base.substring(dot) : "";
                }
                case "fileDirname" -> {
                    return dir == null ? "" : dir.toString();
                }
                case "fileDirnameBasename" -> {
                    return dir == null ? "" : String.valueOf(dir.getFileName());
                }
                case "fileWorkspaceFolder" -> {
                    return ws.toString();
                }
                case "relativeFile" -> {
                    return abs.startsWith(ws) ? ws.relativize(abs).toString() : abs.toString();
                }
                case "relativeFileDirname" -> {
                    return dir != null && dir.startsWith(ws)
                            ? ws.relativize(dir).toString()
                            : String.valueOf(dir);
                }
                default -> {
                    // unknown: below
                }
            }
        }
        throw new LaunchJsonException("${" + name + "} is a variable Editora does not know");
    }

    /** The {@code ${input:id}} ids {@code value} uses, in the order they first appear. */
    public static List<String> inputsUsed(@Nullable Object value) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        collectInputs(value, out);
        return List.copyOf(out);
    }

    private static void collectInputs(@Nullable Object value, java.util.Set<String> out) {
        if (value instanceof String s) {
            Matcher m = VARIABLE.matcher(s);
            while (m.find()) {
                if (m.group(1).startsWith("input:")) {
                    out.add(m.group(1).substring(6));
                }
            }
        } else if (value instanceof List<?> l) {
            l.forEach(x -> collectInputs(x, out));
        } else if (value instanceof Map<?, ?> m) {
            m.values().forEach(x -> collectInputs(x, out));
        }
    }

    /** The task of {@code tasks} labelled {@code label} ({@code tasks.json}), or null. */
    public static @Nullable Map<String, Object> task(File tasks, String label) {
        for (Map<String, Object> t : tasks.tasks()) {
            if (label.equals(t.get("label")) || label.equals(t.get("taskName"))) {
                return t;
            }
        }
        return null;
    }

    /**
     * A {@code tasks.json} task as the command line that runs it: a {@code process} task's command and arguments, a
     * {@code shell} task's through the shell ({@code sh -c}, or {@code cmd /c} on Windows). Null for any other task
     * (a {@code cargo}, {@code npm} or {@code dependsOn} one: VS Code's extensions run those).
     */
    public static @Nullable List<String> taskArgv(Map<String, Object> task, boolean windows) {
        String type = task.get("type") instanceof String t ? t : "process";
        if (!(task.get("command") instanceof String command) || command.isBlank() || task.containsKey("dependsOn")) {
            return null;
        }
        List<String> args = new ArrayList<>();
        if (task.get("args") instanceof List<?> l) {
            l.forEach(a -> args.add(
                    a instanceof Map<?, ?> q && q.get("value") != null
                            ? String.valueOf(q.get("value"))
                            : String.valueOf(a)));
        }
        switch (type) {
            case "process" -> {
                List<String> argv = new ArrayList<>(List.of(command));
                argv.addAll(args);
                return argv;
            }
            case "shell" -> {
                StringBuilder line = new StringBuilder(command);
                for (String a : args) {
                    line.append(' ').append(windows ? a : shellQuote(a));
                }
                return windows ? List.of("cmd", "/c", line.toString()) : List.of("sh", "-c", line.toString());
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code word} for a POSIX shell: as it is when plain, else in single quotes. Pure. */
    public static String shellQuote(String word) {
        if (!word.isEmpty() && word.matches("[A-Za-z0-9_./:=@%+,-]+")) {
            return word;
        }
        return "'" + word.replace("'", "'\"'\"'") + "'";
    }
}
