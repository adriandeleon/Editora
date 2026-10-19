package com.editora.editor;

import java.util.Locale;
import java.util.Map;

/**
 * Pure, unit-tested detector for an interpreter <strong>shebang</strong> on a file's first line
 * (e.g. {@code #!/usr/bin/env python3}), so a file with no telling extension still gets the right
 * language, syntax highlighting, folding, run gutter, and LSP.
 *
 * <p>It parses the three common shebang shapes — a direct interpreter path
 * ({@code #!/bin/bash}), an {@code env} lookup ({@code #!/usr/bin/env ruby}), and {@code env -S}
 * with arguments ({@code #!/usr/bin/env -S java --source 25}) — reduces the interpreter to its
 * basename, strips a trailing version ({@code python3.12} → {@code python}), and maps it to a
 * <em>representative file extension</em>. The caller resolves the language + grammar through the
 * normal {@code forFileName("_." + extension)} path, so a shebang file behaves exactly like a real
 * file of that type (no divergent mapping to keep in sync).
 *
 * <p>Java is special: a bare {@code java} interpreter only means a launchable
 * <em>compact source file</em> (JEP 512) when the shebang carries {@code --source N} (via
 * {@code env -S}); that version is captured for the run command, which must pass it to the source
 * launcher because the file has no {@code .java} extension.
 */
public final class Shebang {

    /**
     * A recognized shebang: the representative file {@code extension} to resolve language/grammar
     * with, and {@code javaSource} = the {@code --source N} version for a Java compact-source
     * shebang (else {@code null}).
     */
    public record Result(String extension, Integer javaSource) {}

    /** Interpreter basename (version stripped) → representative extension for the languages we support. */
    private static final Map<String, String> INTERPRETERS = Map.ofEntries(
            Map.entry("sh", "sh"),
            Map.entry("bash", "sh"),
            Map.entry("zsh", "sh"),
            Map.entry("dash", "sh"),
            Map.entry("ksh", "sh"),
            Map.entry("ash", "sh"),
            Map.entry("mksh", "sh"),
            Map.entry("bats", "sh"), // Bats test files are bash with @test blocks
            Map.entry("python", "py"),
            Map.entry("node", "js"),
            Map.entry("nodejs", "js"),
            Map.entry("deno", "ts"),
            Map.entry("bun", "ts"),
            Map.entry("ts-node", "ts"),
            Map.entry("tsx", "ts"),
            Map.entry("zx", "js"), // Google's zx: JavaScript with shell helpers
            Map.entry("ruby", "rb"),
            Map.entry("php", "php"),
            Map.entry("lua", "lua"),
            Map.entry("luajit", "lua"),
            Map.entry("groovy", "groovy"),
            Map.entry("kotlin", "kts"), // a *.main.kts script run by the kotlin launcher
            Map.entry("scala", "sc"),
            Map.entry("scala-cli", "sc"), // `#!/usr/bin/env -S scala-cli shebang`
            Map.entry("clojure", "clj"),
            Map.entry("clj", "clj"),
            Map.entry("bb", "clj"), // Babashka
            Map.entry("pwsh", "ps1"),
            Map.entry("powershell", "ps1"));

    /** {@code env} options whose argument is the next word (GNU and BSD env). */
    private static final java.util.Set<String> ENV_OPTIONS_WITH_ARGUMENT =
            java.util.Set.of("-u", "--unset", "-C", "--chdir", "-P", "-a", "--argv0");

    private Shebang() {}

    /**
     * Parses the file's first line as a shebang, or returns {@code null} when it is not a {@code #!}
     * line or names an interpreter we do not map to a supported language.
     */
    public static Result parse(String firstLine) {
        if (firstLine == null) {
            return null;
        }
        String line = firstLine.strip();
        if (!line.startsWith("#!")) {
            return null;
        }
        String[] tokens = line.substring(2).strip().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return null;
        }
        int idx = 0;
        String interpreter = basename(tokens[idx]);
        // `env` (with optional `-S`, flags, and VAR=value assignments) delegates to a real interpreter.
        if (interpreter.equals("env")) {
            idx++;
            while (idx < tokens.length) {
                String t = tokens[idx];
                if (ENV_OPTIONS_WITH_ARGUMENT.contains(t)) {
                    idx += 2; // `-u NAME`, `-C DIR`: the next word is the option's, not the interpreter
                } else if (t.startsWith("-S") && t.length() > 2) {
                    tokens[idx] = t.substring(2); // `-Sbash -e`: the split string starts right after the flag
                    break;
                } else if (t.startsWith("--split-string=")) {
                    tokens[idx] = t.substring("--split-string=".length());
                    break;
                } else if (t.startsWith("-") || isAssignment(t)) {
                    idx++;
                } else {
                    break;
                }
            }
            if (idx >= tokens.length || tokens[idx].isEmpty()) {
                return null;
            }
            interpreter = basename(tokens[idx]);
        }
        // `busybox sh`: the applet named after busybox is the interpreter.
        if (interpreter.equals("busybox") && idx + 1 < tokens.length) {
            interpreter = basename(tokens[++idx]);
        }
        // A package runner (`npx tsx`, `pnpm exec ts-node`, `yarn dlx zx`, `bunx tsx`): the tool it runs decides.
        int tool = packageRunnerTool(interpreter, tokens, idx + 1);
        if (tool > 0) {
            idx = tool;
            interpreter = basename(tokens[idx]);
        }
        String canonical = stripVersion(interpreter);
        // python3.14t (free-threaded), python3.12-dbg, pypy3.10, and `uv run --script` / `poetry run python`.
        if (isPython(interpreter) || runsPython(interpreter, tokens, idx + 1)) {
            canonical = "python";
        }
        // Java only counts as a launchable compact source file when a `--source N` follows.
        if (canonical.equals("java")) {
            Integer version = javaSourceVersion(tokens, idx + 1);
            return version == null ? null : new Result("java", version);
        }
        String ext = INTERPRETERS.get(canonical);
        return ext == null ? null : new Result(ext, null);
    }

    /** Convenience: the representative extension for {@code firstLine}, or {@code null}. */
    public static String extension(String firstLine) {
        Result r = parse(firstLine);
        return r == null ? null : r.extension();
    }

    /** A CPython or PyPy executable name, any version and build suffix ({@code python3.14t}, {@code pypy3}). */
    private static final java.util.regex.Pattern PYTHON_NAME =
            java.util.regex.Pattern.compile("(?:python|pypy)(?:\\d+(?:\\.\\d+)*)?(?:t|d|-dbg)?(?:\\.exe)?");

    private static boolean isPython(String name) {
        return PYTHON_NAME.matcher(name).matches();
    }

    /**
     * Whether a project/script runner runs Python here: {@code uv run} and {@code pipx run} run a Python script
     * (PEP 723 inline metadata); {@code poetry}/{@code pdm}/{@code hatch}/{@code rye}/{@code pipenv run} do when
     * the command they run is a Python interpreter.
     */
    private static boolean runsPython(String runner, String[] tokens, int from) {
        if (from >= tokens.length || !tokens[from].equals("run")) {
            return false;
        }
        if (runner.equals("uv") || runner.equals("pipx")) {
            return true;
        }
        if (!java.util.Set.of("poetry", "pdm", "hatch", "rye", "pipenv").contains(runner)) {
            return false;
        }
        for (int i = from + 1; i < tokens.length; i++) {
            if (!tokens[i].startsWith("-")) {
                return isPython(basename(tokens[i]));
            }
        }
        return false;
    }

    /**
     * Index of the tool a JavaScript package runner runs ({@code npx [-y] tsx}, {@code pnpm exec|dlx tsx},
     * {@code yarn [dlx|exec] tsx}, {@code bunx tsx}, {@code pnpx tsx}), skipping its options; -1 when
     * {@code runner} is not one or names no tool.
     */
    private static int packageRunnerTool(String runner, String[] tokens, int from) {
        int i = from;
        switch (runner) {
            case "npx", "bunx", "pnpx" -> {}
            case "pnpm" -> {
                if (i >= tokens.length || !(tokens[i].equals("exec") || tokens[i].equals("dlx"))) {
                    return -1;
                }
                i++;
            }
            case "yarn" -> {
                if (i < tokens.length && (tokens[i].equals("dlx") || tokens[i].equals("exec"))) {
                    i++;
                }
            }
            default -> {
                return -1;
            }
        }
        while (i < tokens.length && tokens[i].startsWith("-")) {
            i++; // `npx -y tsx`, `npx --yes ts-node`
        }
        return i < tokens.length && !tokens[i].isEmpty() ? i : -1;
    }

    /** The last path segment of {@code token} (an interpreter path), lower-cased. */
    private static String basename(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        String base = slash >= 0 ? token.substring(slash + 1) : token;
        return base.toLowerCase(Locale.ROOT);
    }

    /** A {@code NAME=value} env assignment (never an interpreter). */
    private static boolean isAssignment(String token) {
        int eq = token.indexOf('=');
        return eq > 0 && token.chars().limit(eq).allMatch(c -> c == '_' || Character.isLetterOrDigit(c));
    }

    /** Drops a trailing version suffix: {@code python3.12} → {@code python}, {@code php8} → {@code php}. */
    private static String stripVersion(String name) {
        int end = name.length();
        while (end > 0) {
            char c = name.charAt(end - 1);
            if (c == '.' || (c >= '0' && c <= '9')) {
                end--;
            } else {
                break;
            }
        }
        return end == 0 ? name : name.substring(0, end);
    }

    /** Scans the remaining tokens for {@code --source N} (or {@code --source=N}); the integer N, or {@code null}. */
    private static Integer javaSourceVersion(String[] tokens, int from) {
        for (int i = from; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("--source") && i + 1 < tokens.length) {
                return parsePositiveInt(tokens[i + 1]);
            }
            if (t.startsWith("--source=")) {
                return parsePositiveInt(t.substring("--source=".length()));
            }
        }
        return null;
    }

    private static Integer parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s.strip());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
