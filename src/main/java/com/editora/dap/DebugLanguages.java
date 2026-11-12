package com.editora.dap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.dap.DapServerRegistry.Kind;
import com.editora.dap.DebugLanguage.Availability;
import org.jspecify.annotations.Nullable;

/** Every language Editora debugs, as its {@link DebugLanguage} profile. Pure. */
public final class DebugLanguages {

    private DebugLanguages() {}

    private static DapModels.ExceptionFilterOption filter(String id, String label, boolean on) {
        return new DapModels.ExceptionFilterOption(id, label, on);
    }

    /** java-debug's caught / uncaught pair, neither on by default. */
    private static final List<DapModels.ExceptionFilterOption> JAVA_FILTERS =
            List.of(filter("caught", "Caught Exceptions", false), filter("uncaught", "Uncaught Exceptions", false));

    /**
     * Editora's JVM adapter ({@code com.editora.dap.jvm}): a script's uncaught exception stops, as debugpy's does.
     * Metals' adapter (scala-debug-adapter, on java-debug) offers the same two ids.
     */
    private static final List<DapModels.ExceptionFilterOption> JVM_FILTERS =
            List.of(filter("caught", "Caught Exceptions", false), filter("uncaught", "Uncaught Exceptions", true));

    /** The JVM languages Editora's own adapter debugs (a Groovy, Kotlin or Clojure program or script). */
    private static DebugLanguage jvm(String id, String displayName) {
        return new DebugLanguage(
                id,
                displayName,
                Set.of(id),
                Kind.JVM,
                DapServerRegistry.JVM_LAUNCH_TYPE,
                DapServerRegistry.JVM_LAUNCH_TYPE,
                "",
                List.of(),
                null,
                Availability.BUILT_IN,
                JVM_FILTERS,
                "caught",
                null,
                false,
                "status.debug.unavailable",
                null);
    }

    public static final DebugLanguage JAVA = new DebugLanguage(
            "java",
            "Java",
            Set.of("java"),
            // java-debug runs inside jdtls (workspace/executeCommand answers a port): DapManager drives that flow.
            Kind.JDTLS,
            "java",
            "java",
            "",
            List.of(),
            null,
            Availability.JAVA_DEBUG_PLUGIN,
            JAVA_FILTERS,
            "caught",
            null,
            true, // through the jdtls running on the host, handed the java-debug jar there
            "status.debug.unavailable",
            null);

    public static final DebugLanguage PYTHON = new DebugLanguage(
            "python",
            "Python",
            Set.of("python"),
            Kind.STDIO,
            "python",
            "python",
            DapServerRegistry.DEFAULT_PYTHON_INTERPRETER,
            List.of("-m", "debugpy.adapter"),
            null,
            Availability.LOCATED,
            List.of(
                    filter("raised", "Raised Exceptions", false),
                    filter("uncaught", "Uncaught Exceptions", true),
                    filter("userUnhandled", "User Uncaught Exceptions", false)),
            "raised",
            null,
            true,
            "debug.error.pythonUnavailable",
            null);

    public static final DebugLanguage JAVASCRIPT = new DebugLanguage(
            "javascript",
            "JavaScript",
            // TypeScript too: Node 22.18+/23.6+ runs a .ts file itself, and a compiled project's .ts/.tsx sources
            // are where its breakpoints go (source maps). One adapter, one "javascript" session. HTML too: a page's
            // inline scripts, debugged in a browser (BrowserLaunch).
            Set.of("javascript", "typescript", "javascriptreact", "typescriptreact", "html"),
            Kind.SOCKET,
            "pwa-node",
            "pwa-node",
            DapServerRegistry.DEFAULT_NODE_INTERPRETER,
            List.of(),
            null,
            Availability.LOCATED,
            List.of(filter("all", "Caught Exceptions", false), filter("uncaught", "Uncaught Exceptions", false)),
            "all",
            null,
            true,
            "debug.error.jsUnavailable",
            null);

    /**
     * A Groovy script runs under {@code groovy} with the JDWP agent in {@code JAVA_OPTS}; Editora's own adapter maps
     * its breakpoints by source name (java-debug parses every file as Java).
     */
    public static final DebugLanguage GROOVY = jvm("groovy", "Groovy");

    /**
     * A Kotlin script runs under {@code kotlinc -script} (agent in {@code JAVA_OPTS}), a program as {@code java -jar}
     * (agent on its command line). kotlin-lsp's own debugger is undocumented and alpha: not used.
     */
    public static final DebugLanguage KOTLIN = jvm("kotlin", "Kotlin");

    /**
     * A Clojure file runs under the Clojure CLI (the agent as a {@code -J} argument) or Leiningen (in
     * {@code JVM_OPTS}); its functions' classes ({@code my_app.core$greet}) record {@code core.clj}, so breakpoints
     * bind by source name as Groovy's. Babashka is native: nothing to attach to.
     */
    public static final DebugLanguage CLOJURE = jvm("clojure", "Clojure");

    /** A file of a build debugs on Metals' adapter (it knows the classpath); a script on Editora's own. */
    public static final DebugLanguage SCALA = new DebugLanguage(
            "scala",
            "Scala",
            Set.of("scala"),
            Kind.LANGUAGE_SERVER,
            "scala",
            "scala",
            "",
            List.of(),
            Kind.JVM,
            Availability.BUILT_IN,
            JVM_FILTERS,
            "caught",
            "debug-adapter-start",
            false,
            "status.debug.unavailable",
            null);

    /**
     * What Delve prints once {@code dlv dap} listens: {@code DAP server listening at: 127.0.0.1:38697} (an IPv6
     * address in brackets on a machine that resolves there).
     */
    public static final java.util.regex.Pattern DELVE_ANNOUNCEMENT =
            java.util.regex.Pattern.compile("DAP server listening at: \\S*:(\\d+)");

    /**
     * Go, on Delve's DAP server ({@code dlv dap}): started on a port it picks ({@code 127.0.0.1:0}) and announces.
     * Delve builds the program itself (with optimizations off), runs it, and stops on a panic by itself: it offers no
     * exception choices. Delve is found as configured, on the PATH, or where {@code go install} put it.
     */
    public static final DebugLanguage GO = new DebugLanguage(
            "go",
            "Go",
            Set.of("go"),
            Kind.EXTERNAL,
            "go",
            "go",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(),
            null,
            null,
            false,
            "debug.error.goUnavailable",
            new ExternalAdapter(
                    List.of("dlv", "dap", "--listen=127.0.0.1:0"),
                    ExternalAdapter.Transport.ANNOUNCED_PORT,
                    DELVE_ANNOUNCEMENT));

    /**
     * CodeLLDB ({@code codelldb --port N}), the adapter of Rust and of C and C++: one program, found once
     * ({@link DapManager#locateExternal}).
     */
    public static final ExternalAdapter CODELLDB = new ExternalAdapter(
            List.of("codelldb", "--port", ExternalAdapter.PORT), ExternalAdapter.Transport.PORT_ARGUMENT, null);

    /** CodeLLDB's "stop when Rust panics" exception filter. */
    public static final String RUST_PANIC_FILTER = "rust_panic";

    /**
     * Rust, on CodeLLDB (LLDB with Rust's formatters): started on a port Editora picks ({@code codelldb --port N}).
     * Editora builds the program with Cargo first and launches the executable Cargo reports. A panic stops it, as in
     * VS Code.
     */
    public static final DebugLanguage RUST = new DebugLanguage(
            "rust",
            "Rust",
            Set.of("rust"),
            Kind.EXTERNAL,
            "lldb",
            "lldb",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(filter(RUST_PANIC_FILTER, "Rust: on panic", true)),
            null,
            null,
            false,
            "debug.error.rustUnavailable",
            CODELLDB);

    /** CodeLLDB's "stop when a C++ exception is thrown" filter. */
    public static final String CPP_THROW_FILTER = "cpp_throw";

    /**
     * C and C++, on CodeLLDB. Editora builds the program first — with CMake, Make, or the C or C++ compiler for a lone
     * file — and launches the executable it made. A thrown C++ exception stops it ({@link #CPP_THROW_FILTER}), as
     * CodeLLDB itself has it on.
     */
    public static final DebugLanguage CPP = new DebugLanguage(
            "cpp",
            "C/C++",
            Set.of("c", "cpp"),
            Kind.EXTERNAL,
            "lldb",
            "lldb",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(filter(CPP_THROW_FILTER, "C++: on throw", true), filter("cpp_catch", "C++: on catch", false)),
            null,
            null,
            false,
            "debug.error.cppUnavailable",
            CODELLDB);

    /** netcoredbg's "stop on an exception user code does not handle" filter. */
    public static final String CSHARP_USER_UNHANDLED_FILTER = "user-unhandled";

    /**
     * C#, on Samsung's netcoredbg ({@code netcoredbg --interpreter=vscode}, MIT) over its standard streams. Editora
     * builds the project — or a lone file, a .NET 10 file-based app — with {@code dotnet build} first and launches the
     * assembly it made; the program's output arrives as output events. An exception user code does not handle stops it,
     * as in Visual Studio.
     */
    public static final DebugLanguage CSHARP = new DebugLanguage(
            "csharp",
            "C#",
            Set.of("csharp"),
            Kind.EXTERNAL,
            "coreclr",
            "coreclr",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(
                    filter(CSHARP_USER_UNHANDLED_FILTER, "C#: user-unhandled exceptions", true),
                    filter("all", "C#: all exceptions", false)),
            null,
            null,
            false,
            "debug.error.csharpUnavailable",
            new ExternalAdapter(List.of("netcoredbg", "--interpreter=vscode"), ExternalAdapter.Transport.STDIO, null));

    /**
     * What rdbg prints once it listens: {@code DEBUGGER: Debugger can attach via TCP/IP (127.0.0.1:41234)} (an IPv6
     * address in brackets).
     */
    public static final java.util.regex.Pattern RDBG_ANNOUNCEMENT =
            java.util.regex.Pattern.compile("Debugger can attach via TCP/IP \\(\\S*:(\\d+)\\)");

    /**
     * Ruby, on the debug gem's {@code rdbg} (Ruby 3.1 and later ship it): the program runs under rdbg, which opens a
     * port it picks and announces, stops at load until the breakpoints are set, then goes on ({@code configurationDone}).
     * The program's words follow the command ({@code -- ruby script.rb …}): see {@link DapManager#startExternal(
     * java.nio.file.Path, DebugLanguage, java.util.Map, List)}.
     */
    public static final DebugLanguage RUBY = new DebugLanguage(
            "ruby",
            "Ruby",
            Set.of("ruby"),
            Kind.EXTERNAL,
            "rdbg",
            "rdbg",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(
                    filter("any", "Ruby: any raised exception", false),
                    filter("RuntimeError", "Ruby: RuntimeError", false)),
            null,
            null,
            false,
            "debug.error.rubyUnavailable",
            new ExternalAdapter(
                    List.of("rdbg", "--command", "--open", "--stop-at-load", "--host=127.0.0.1", "--port=0"),
                    ExternalAdapter.Transport.ANNOUNCED_PORT,
                    RDBG_ANNOUNCEMENT));

    /**
     * PHP, on Xdebug through vscode-php-debug ({@code phpDebug.js}, MIT), which runs on Node.js over its standard
     * streams: it listens for Xdebug on a port it picks, starts {@code php} with Xdebug told to connect there, and
     * passes the program's output on as output events. Xdebug itself is PHP's extension ({@code php-xdebug}).
     */
    public static final DebugLanguage PHP = new DebugLanguage(
            "php",
            "PHP",
            Set.of("php"),
            Kind.EXTERNAL,
            "php",
            "php",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(
                    filter("Exception", "PHP: exceptions", false),
                    filter("Error", "PHP: errors", false),
                    filter("Warning", "PHP: warnings", false),
                    filter("Notice", "PHP: notices", false),
                    filter("Deprecated", "PHP: deprecations", false),
                    filter("*", "PHP: everything", false)),
            null,
            null,
            false,
            "debug.error.phpUnavailable",
            new ExternalAdapter(List.of("phpDebug.js"), ExternalAdapter.Transport.STDIO, null));

    private static final List<DebugLanguage> ALL =
            List.of(JAVA, PYTHON, JAVASCRIPT, GROOVY, KOTLIN, CLOJURE, SCALA, GO, RUST, CPP, CSHARP, RUBY, PHP);

    /**
     * GDB's own DAP mode ({@code gdb -i dap}, GDB 14 and later), over its standard streams: C and C++'s other debugger
     * (Settings → Debugging's choice, and launch.json's {@code gdb} and {@code cppdbg}). Not a language of its own, so
     * not among {@link #all}.
     */
    public static final DebugLanguage GDB = new DebugLanguage(
            "gdb",
            "GDB",
            Set.of("c", "cpp"),
            Kind.EXTERNAL,
            "gdb",
            "gdb",
            "",
            List.of(),
            null,
            Availability.LOCATED,
            List.of(),
            null,
            null,
            false,
            "debug.error.gdbUnavailable",
            new ExternalAdapter(List.of("gdb", "-q", "-i", "dap"), ExternalAdapter.Transport.STDIO, null));

    /** The user's own adapters ({@link CustomAdapters}): found by id and by language after the built-in ones. */
    private static volatile List<DebugLanguage> custom = List.of();

    /** Sets the user's own adapters (a launch reads them again from {@code debug-adapters.json}). */
    public static void setCustom(List<DebugLanguage> adapters) {
        custom = List.copyOf(adapters);
    }

    /** The user's own adapters. */
    public static List<DebugLanguage> custom() {
        return custom;
    }

    /** Every profile, in the order the Settings page lists them. */
    public static List<DebugLanguage> all() {
        return ALL;
    }

    /** The profile of adapter language {@code id} ({@code javascript}, not {@code typescript}), or null. */
    public static @Nullable DebugLanguage byId(@Nullable String id) {
        for (DebugLanguage l : ALL) {
            if (l.id().equals(id)) {
                return l;
            }
        }
        if (GDB.id().equals(id)) {
            return GDB;
        }
        for (DebugLanguage l : custom) {
            if (l.id().equals(id)) {
                return l;
            }
        }
        return null;
    }

    /** The profile serving editor language {@code languageId} ({@code typescript} → JavaScript's), or null. */
    public static @Nullable DebugLanguage forLanguage(@Nullable String languageId) {
        if (languageId == null) {
            return null;
        }
        for (DebugLanguage l : ALL) {
            if (l.editorLanguages().contains(languageId)) {
                return l;
            }
        }
        for (DebugLanguage l : custom) {
            if (l.editorLanguages().contains(languageId)) {
                return l;
            }
        }
        return null;
    }

    /** The adapter languages ({@code java}, {@code python}, {@code javascript}, …). */
    public static Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        for (DebugLanguage l : ALL) {
            out.add(l.id());
        }
        return out;
    }
}
