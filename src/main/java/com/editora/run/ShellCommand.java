package com.editora.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The interpreter a shell script runs under, decided the way running it as {@code ./script} would: by its
 * shebang line (pure, unit-tested).
 *
 * <ul>
 *   <li>A direct interpreter keeps its path and flags: {@code #!/bin/bash -eu} → {@code /bin/bash -eu}, and
 *       {@code #!/bin/sh} runs {@code /bin/sh}, not bash.</li>
 *   <li>An {@code env} lookup drops {@code env} and its options and keeps the interpreter with its flags:
 *       {@code #!/usr/bin/env -S bash -e} → {@code bash -e}; {@code #!/usr/bin/env zsh} → {@code zsh}.
 *       {@code NAME=value} assignments on that line become environment variables.</li>
 *   <li>A shebang naming another language runs that language: a {@code .sh} starting
 *       {@code #!/usr/bin/env python3} runs {@code python3}.</li>
 *   <li>No shebang: {@code bash}, or {@code zsh} for a {@code .zsh} file.</li>
 *   <li>A run configuration's <b>Interpreter</b> overrides all of that; its <b>Interpreter options</b> replace
 *       the shebang's flags.</li>
 * </ul>
 *
 * <p>Running the interpreter explicitly, rather than executing the file, keeps working for a script without the
 * executable bit and on Windows, where there is no kernel to read the shebang. For a run on this machine an
 * absolute interpreter that does not exist here (a {@code /bin/bash} on Windows, a {@code /usr/local/bin/bash}
 * that was never installed) falls back to its bare name, found on the PATH like any other command.
 */
public final class ShellCommand {

    private ShellCommand() {}

    /** The interpreter argv (before the script path) and any variables the shebang's {@code env} sets. */
    /**
     * The interpreter argv (before the script path), the variables the shebang's {@code env} sets — a
     * {@code null} value <em>unsets</em> one ({@code env -u NAME}) — and the folder {@code env -C DIR} runs it
     * in ({@code null} when there is none; a relative one is relative to the run's working directory).
     */
    public record Launch(List<String> interpreter, Map<String, String> env, String chdir) {
        public Launch(List<String> interpreter, Map<String, String> env) {
            this(interpreter, env, null);
        }
    }

    /** The default interpreter for a shell file with no shebang: {@code zsh} for {@code .zsh}, else bash. */
    public static String defaultInterpreter(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".zsh") ? "zsh" : "bash";
    }

    /**
     * Decides the launch.
     *
     * @param firstLine the script's first line (a leading byte-order mark is ignored), or null/blank
     * @param fileName the script's file name, for the no-shebang default
     * @param interpreterOverride a run configuration's Interpreter; blank keeps the shebang's
     * @param interpreterOptions a run configuration's Interpreter options; blank keeps the shebang's flags
     * @param existsHere for a run on this machine, whether an absolute interpreter path exists here;
     *     {@code null} for a remote host, whose paths are used as written
     * @throws ProgramArgs.UnclosedQuoteException when the options have an unclosed quote
     */
    public static Launch launch(
            String firstLine,
            String fileName,
            String interpreterOverride,
            String interpreterOptions,
            Predicate<String> existsHere) {
        Map<String, String> env = new LinkedHashMap<>();
        String[] chdir = new String[1];
        List<String> fromShebang = shebang(firstLine, env, chdir);
        String interpreter;
        List<String> flags;
        String override = interpreterOverride == null ? "" : interpreterOverride.strip();
        if (!override.isEmpty()) {
            interpreter = override;
            flags = List.of();
            env.clear(); // the configuration chose its own interpreter; the shebang's env line no longer applies
            chdir[0] = null;
        } else if (!fromShebang.isEmpty()) {
            interpreter = fromShebang.get(0);
            flags = fromShebang.subList(1, fromShebang.size());
        } else {
            interpreter = defaultInterpreter(fileName);
            flags = List.of();
        }
        if (interpreterOptions != null && !interpreterOptions.isBlank()) {
            flags = ProgramArgs.parse(interpreterOptions);
        }
        List<String> argv = new ArrayList<>();
        argv.add(localize(interpreter, existsHere));
        argv.addAll(flags);
        return new Launch(List.copyOf(argv), java.util.Collections.unmodifiableMap(env), chdir[0]);
    }

    /** The full argv: interpreter, flags, the script, then the program arguments. */
    public static List<String> argv(Launch launch, String script, List<String> args) {
        List<String> argv = new ArrayList<>(launch.interpreter());
        argv.add(script);
        if (args != null) {
            argv.addAll(args);
        }
        return argv;
    }

    /** Whether {@code interpreter} (a name or path) is bash. */
    public static boolean isBash(String interpreter) {
        return "bash".equals(baseName(interpreter));
    }

    /**
     * The trace prompt Run with Trace sets ({@code PS4}): {@code +/path/x.sh:12: } before each traced command
     * under bash, so every trace line is a link to its source line; plain {@code + } under a shell without
     * {@code BASH_SOURCE} (dash leaves {@code LINENO} empty in the prompt, so it could not say the line anyway).
     */
    public static final String TRACE_PROMPT = "+${BASH_SOURCE:+${BASH_SOURCE}:${LINENO}:} ";

    /** Shells whose {@code -x} trace honours {@link #TRACE_PROMPT}: bash, and the POSIX shells. */
    private static final java.util.Set<String> TRACEABLE = java.util.Set.of("bash", "sh", "dash", "ash", "ksh", "mksh");

    /** Whether Run with Trace can trace a script run by {@code launch} (not zsh, not another language). */
    public static boolean canTrace(Launch launch) {
        return TRACEABLE.contains(shellName(launch));
    }

    /**
     * The shell the launch runs: the interpreter's name, or for {@code busybox sh} the applet — busybox runs the
     * applet named after it ({@code #!/bin/busybox sh} keeps both words, as the kernel would).
     */
    private static String shellName(Launch launch) {
        List<String> argv = launch.interpreter();
        String name = baseName(argv.get(0));
        if (name.equals("busybox") && argv.size() > 1 && !argv.get(1).startsWith("-")) {
            return baseName(argv.get(1));
        }
        return name;
    }

    /** {@code launch} with {@code -x} added to the interpreter's flags, unless they already trace. */
    public static Launch withTrace(Launch launch) {
        if (traces(launch)) {
            return launch;
        }
        List<String> argv = new ArrayList<>(launch.interpreter());
        // After the applet for `busybox sh`, else right after the interpreter.
        boolean busybox = baseName(argv.get(0)).equals("busybox")
                && argv.size() > 1
                && !argv.get(1).startsWith("-");
        argv.add(busybox ? 2 : 1, "-x");
        return new Launch(List.copyOf(argv), launch.env(), launch.chdir());
    }

    /**
     * Whether the interpreter's flags turn tracing on — {@code -x}, a cluster such as {@code -eux}, or
     * {@code -o xtrace} — so the run gets {@link #TRACE_PROMPT} whether it came from Run with Trace, the
     * shebang ({@code #!/bin/bash -x}) or a configuration's Interpreter options.
     */
    public static boolean traces(Launch launch) {
        List<String> argv = launch.interpreter();
        for (int i = 1; i < argv.size(); i++) {
            String a = argv.get(i);
            if (a.startsWith("-") && !a.startsWith("--") && a.length() > 1 && !a.equals("-o") && a.indexOf('x') > 0) {
                return true;
            }
            if (a.equals("-o") && i + 1 < argv.size() && argv.get(i + 1).equals("xtrace")) {
                return true;
            }
        }
        return false;
    }

    /** The interpreter's name without its folder or {@code .exe}, for messages. */
    public static String interpreterName(Launch launch) {
        String name = baseName(launch.interpreter().get(0));
        String shell = shellName(launch);
        return shell.equals(name) ? name : name + " " + shell;
    }

    /**
     * The shebang's interpreter and its flags, with an {@code env} lookup resolved; empty when the line is no
     * shebang. Variables set on an {@code env} line are added to {@code env}.
     */
    static List<String> shebang(String firstLine, Map<String, String> env) {
        return shebang(firstLine, env, new String[1]);
    }

    /** As above; {@code chdir[0]} receives an {@code env -C DIR} folder. */
    static List<String> shebang(String firstLine, Map<String, String> env, String[] chdir) {
        if (firstLine == null) {
            return List.of();
        }
        String line = firstLine.startsWith("﻿") ? firstLine.substring(1) : firstLine;
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl);
        }
        line = line.strip();
        if (!line.startsWith("#!")) {
            return List.of();
        }
        String[] tokens = line.substring(2).strip().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (!"env".equals(baseName(tokens[0]))) {
            for (String t : tokens) {
                out.add(t);
            }
            return out;
        }
        // `env [options] [NAME=value]... interpreter [args]` — Linux hands everything after env to it as ONE
        // argument unless -S splits it; macOS splits it either way. Splitting always is what both mean.
        // The option forms are GNU's and BSD's, matching editor/Shebang's detection, so the interpreter Run starts
        // is the one the file was recognized by: `-u NAME` used to make NAME the "interpreter".
        boolean options = true;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            String next = i + 1 < tokens.length ? tokens[i + 1] : null;
            if (!out.isEmpty()) {
                out.add(t);
            } else if (options && t.equals("--")) {
                options = false;
            } else if (options && (t.equals("-u") || t.equals("--unset")) && next != null) {
                env.put(next, null); // the child runs without NAME
                i++;
            } else if (options && t.startsWith("--unset=")) {
                env.put(t.substring("--unset=".length()), null);
            } else if (options && t.startsWith("-u") && t.length() > 2) {
                env.put(t.substring(2), null);
            } else if (options && (t.equals("-C") || t.equals("--chdir")) && next != null) {
                chdir[0] = next;
                i++;
            } else if (options && t.startsWith("--chdir=")) {
                chdir[0] = t.substring("--chdir=".length());
            } else if (options && (t.equals("-P") || t.equals("-a") || t.equals("--argv0"))) {
                i++; // BSD's search path, GNU's argv[0]: their value is not the interpreter
            } else if (options && t.equals("--split-string") && next != null) {
                out.add(next);
                i++;
            } else if (options && t.startsWith("-S") && t.length() > 2) {
                out.add(t.substring(2)); // -Sbash: the split string starts right after the flag
            } else if (options && t.startsWith("--split-string=")) {
                String rest = t.substring("--split-string=".length());
                if (!rest.isEmpty()) {
                    out.add(rest);
                }
            } else if (options && t.startsWith("-")) {
                // -S, -i, --ignore-environment, -v, … : nothing to keep
            } else if (t.indexOf('=') > 0) {
                env.put(t.substring(0, t.indexOf('=')), t.substring(t.indexOf('=') + 1));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    private static String localize(String interpreter, Predicate<String> existsHere) {
        if (existsHere == null || !interpreter.startsWith("/") || existsHere.test(interpreter)) {
            return interpreter;
        }
        return baseName(interpreter);
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        base = base.toLowerCase(Locale.ROOT);
        return base.endsWith(".exe") ? base.substring(0, base.length() - 4) : base;
    }
}
