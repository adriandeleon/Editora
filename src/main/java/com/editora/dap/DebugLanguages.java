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
            // are where its breakpoints go (source maps). One adapter, one "javascript" session.
            Set.of("javascript", "typescript", "javascriptreact", "typescriptreact"),
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

    private static final List<DebugLanguage> ALL = List.of(JAVA, PYTHON, JAVASCRIPT, GROOVY, KOTLIN, CLOJURE, SCALA);

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
