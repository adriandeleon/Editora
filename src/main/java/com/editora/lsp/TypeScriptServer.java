package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Editora tells typescript-language-server: which {@code tsserver} it runs, its
 * {@code initializationOptions}, and its {@code javascript}/{@code typescript} settings.
 *
 * <p>The server looks for a tsserver in this order: {@code initializationOptions.tsserver.path}, then the
 * workspace's {@code node_modules/typescript/lib} (walking up to the file-system root), then
 * {@code tsserver.fallbackPath}, then the {@code typescript} package next to itself. Left to itself it therefore
 * (a) ran a downloaded folder's own {@code node_modules/typescript} — code from a folder nobody trusted — and
 * (b) died with "Could not find a valid TypeScript installation" beside TypeScript 7, which ships no tsserver
 * (Node review B1, B2). Editora now always names the tsserver: the folder's own only when the folder is trusted,
 * else the one installed beside the server; and with neither it does not start the server and says why.
 *
 * <p>Pure apart from the file reads of the {@code find…} helpers, which take the file test as a parameter in
 * their testable forms.
 */
public final class TypeScriptServer {

    /** The server id of typescript-language-server in {@link LspServerRegistry}. */
    public static final String SERVER_ID = "typescript";

    private TypeScriptServer() {}

    /** Where the chosen tsserver came from, or why there is none. */
    public enum Outcome {
        /** The trusted folder's own {@code node_modules/typescript}. */
        WORKSPACE,
        /** The {@code typescript} package installed beside typescript-language-server. */
        INSTALLED,
        /** Only the folder's own TypeScript would do, and the folder is not trusted: not started. */
        WITHHELD_UNTRUSTED,
        /** No TypeScript with a tsserver anywhere it may come from: not started. */
        NO_SDK
    }

    /**
     * The decision for one server root. {@code tsserver} is the {@code tsserver.js} to name (null unless
     * {@link Outcome#WORKSPACE}/{@link Outcome#INSTALLED}); {@code installedVersion} is the version of the
     * {@code typescript} package found beside the server even when it has no tsserver ("7.0.2"), or "".
     */
    public record Choice(Outcome outcome, @Nullable Path tsserver, String installedVersion) {
        public boolean starts() {
            return tsserver != null;
        }
    }

    /**
     * Chooses the tsserver for a root.
     *
     * @param trustedWorkspace the folder's own tsserver found within its trusted part (null: none, or untrusted)
     * @param anyWorkspace the folder's own tsserver found anywhere up the tree, trusted or not (null: none)
     * @param installed the tsserver beside typescript-language-server (null: none)
     * @param installedVersion the version of the typescript package beside the server, or ""
     * @param loose whether this is the shared session for loose files, which never uses a folder's TypeScript
     */
    public static Choice choose(
            @Nullable Path trustedWorkspace,
            @Nullable Path anyWorkspace,
            @Nullable Path installed,
            String installedVersion,
            boolean loose) {
        String version = installedVersion == null ? "" : installedVersion;
        if (!loose && trustedWorkspace != null) {
            return new Choice(Outcome.WORKSPACE, trustedWorkspace, version);
        }
        if (installed != null) {
            return new Choice(Outcome.INSTALLED, installed, version);
        }
        if (!loose && anyWorkspace != null) {
            return new Choice(Outcome.WITHHELD_UNTRUSTED, null, version);
        }
        return new Choice(Outcome.NO_SDK, null, version);
    }

    /**
     * The first {@code node_modules/typescript/lib/tsserver.js} walking up from {@code start}; {@code ceiling},
     * when given, is the last folder looked at. Null when there is none. A TypeScript 7 package (no tsserver)
     * does not count.
     */
    public static @Nullable Path findTsserver(Path start, @Nullable Path ceiling) {
        return findTsserver(start, ceiling, Files::isRegularFile);
    }

    static @Nullable Path findTsserver(Path start, @Nullable Path ceiling, Predicate<Path> isFile) {
        if (start == null) {
            return null;
        }
        Path stop = ceiling == null ? null : ceiling.toAbsolutePath().normalize();
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (stop != null && !dir.startsWith(stop)) {
                break;
            }
            Path candidate = dir.resolve("node_modules/typescript/lib/tsserver.js");
            if (isFile.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The tsserver installed beside typescript-language-server, whose {@code executable} (symbolic links already
     * followed) is e.g. {@code …/lib/node_modules/typescript-language-server/lib/cli.mjs}: the walk up from its
     * folder meets its own {@code node_modules}, then the global {@code lib/node_modules} the {@code typescript}
     * package sits in — how Node itself would resolve {@code typescript} from there.
     */
    public static @Nullable Path installedTsserver(@Nullable Path executable) {
        return executable == null || executable.getParent() == null ? null : findTsserver(executable.getParent(), null);
    }

    /**
     * The TypeScript the server runs outside a trusted folder of its own, as Settings and Doctor show it:
     * {@code tsserver} (null: none it can run), its {@code version}, whether it is Editora's own copy ({@code own}),
     * and the version of the {@code typescript} package beside the server ({@code besideVersion}, "" when none),
     * which may have no tsserver (TypeScript 7).
     */
    public record Sdk(@Nullable Path tsserver, String version, boolean own, String besideVersion) {
        /** Whether the server can start on it. */
        public boolean found() {
            return tsserver != null;
        }

        /** Whether it is one that leaves the server as it should be: a tsserver and TypeScript 5's types (B101). */
        public boolean suitable() {
            return com.editora.install.TypeScriptServerInstall.suitable(version, tsserver != null);
        }
    }

    /**
     * {@link Sdk} for the server whose executable (links followed) is {@code executable}, with Editora's own
     * TypeScript ({@code ownTsserver}, null: not installed) first, as {@link LspManager} chooses it. File reads.
     */
    public static Sdk sdk(@Nullable Path executable, @Nullable Path ownTsserver) {
        String beside = installedPackageVersion(executable);
        if (ownTsserver != null) {
            return new Sdk(ownTsserver, sdkVersion(ownTsserver), true, beside);
        }
        Path tsserver = installedTsserver(executable);
        return new Sdk(tsserver, tsserver == null ? "" : sdkVersion(tsserver), false, beside);
    }

    /** The version of the {@code typescript} package beside the server (any version, tsserver or not), or "". */
    public static String installedPackageVersion(@Nullable Path executable) {
        if (executable == null || executable.getParent() == null) {
            return "";
        }
        for (Path dir = executable.toAbsolutePath().normalize().getParent(); dir != null; dir = dir.getParent()) {
            Path pkg = dir.resolve("node_modules/typescript/package.json");
            if (Files.isRegularFile(pkg)) {
                try {
                    return packageVersion(Files.readString(pkg));
                } catch (java.io.IOException | RuntimeException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** The version of the TypeScript whose {@code tsserver.js} is {@code tsserver}, or "". */
    public static String sdkVersion(Path tsserver) {
        if (tsserver == null
                || tsserver.getParent() == null
                || tsserver.getParent().getParent() == null) {
            return "";
        }
        try {
            return packageVersion(
                    Files.readString(tsserver.getParent().getParent().resolve("package.json")));
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    /** The {@code "version"} of a package.json's text, or "". Pure. */
    static String packageVersion(String packageJson) {
        if (packageJson == null) {
            return "";
        }
        Matcher m = VERSION.matcher(packageJson);
        return m.find() ? m.group(1) : "";
    }

    /**
     * The {@code initializationOptions}: the tsserver to run, automatic type acquisition (off unless the user
     * turned it on — it runs {@code npm install} against the registry, Node review B4), and for the shared
     * loose-files session no separate syntax server (one tsserver instead of two, review B7).
     */
    public static Map<String, Object> initializationOptions(
            @Nullable Path tsserver, boolean typeAcquisition, boolean loose) {
        Map<String, Object> options = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        if (tsserver != null) {
            server.put("path", tsserver.toString());
        }
        if (loose) {
            server.put("useSyntaxServer", "never");
        }
        if (!server.isEmpty()) {
            options.put("tsserver", server);
        }
        options.put("disableAutomaticTypingAcquisition", !typeAcquisition);
        return options;
    }

    /**
     * The {@code workspace/didChangeConfiguration} settings: inlay hints and code lenses, which the server keeps
     * off until asked (so Editora's switches did nothing for JS/TS, Node review B6). Parameter-name hints are
     * asked for on every argument; Editora's own literals/all filter then drops what the mode hides, as it does
     * for every server. Reference lenses go on every function, as Editora's Java lenses go on every method: a
     * plain Node script's functions are seldom exported, and the server's default puts lenses only on exports.
     */
    public static Map<String, Object> settings(boolean inlayHints, boolean codeLens) {
        Map<String, Object> language = new HashMap<>();
        Map<String, Object> hints = new HashMap<>();
        hints.put("includeInlayParameterNameHints", inlayHints ? "all" : "none");
        hints.put("includeInlayParameterNameHintsWhenArgumentMatchesName", false);
        hints.put("includeInlayFunctionLikeReturnTypeHints", inlayHints);
        hints.put("includeInlayEnumMemberValueHints", inlayHints);
        hints.put("includeInlayFunctionParameterTypeHints", false);
        hints.put("includeInlayVariableTypeHints", false);
        hints.put("includeInlayPropertyDeclarationTypeHints", false);
        language.put("inlayHints", hints);
        language.put("referencesCodeLens", Map.of("enabled", codeLens, "showOnAllFunctions", codeLens));
        language.put("implementationsCodeLens", Map.of("enabled", codeLens));
        Map<String, Object> out = new HashMap<>();
        out.put("javascript", language);
        out.put("typescript", language);
        return out;
    }

    /**
     * The answer to the server's {@code workspace/configuration} for {@code section}: the document's own
     * indentation for {@code formattingOptions} (else tsserver formats quick fixes and refactorings with its
     * 4-space default, Node review B8); null for anything else.
     */
    public static @Nullable Object answer(String section, @Nullable FormattingIndent indent) {
        if ("formattingOptions".equals(section) && indent != null) {
            Map<String, Object> options = new HashMap<>();
            options.put("tabSize", indent.tabSize());
            options.put("insertSpaces", indent.insertSpaces());
            return options;
        }
        return null;
    }

    private static final Pattern TSSERVER_EXITED =
            Pattern.compile("\\[tsserver\\] Exited\\. Code: (\\S+?)\\.? Signal: (\\S+)");

    /**
     * Whether {@code logMessage} is typescript-language-server saying its tsserver died — {@code [tsserver] Exited.
     * Code: null. Signal: SIGKILL}, or a non-zero code (out of memory) — rather than ended normally. Pure.
     */
    public static boolean tsserverDied(@Nullable String logMessage) {
        if (logMessage == null) {
            return false;
        }
        Matcher m = TSSERVER_EXITED.matcher(logMessage);
        if (!m.find()) {
            return false;
        }
        String code = m.group(1);
        String signal = m.group(2).replaceAll("[.,;]+$", "");
        return !"null".equals(signal) || !"0".equals(code);
    }

    /**
     * The version of a folder's own TypeScript the server does not run because the folder is not trusted, when it is
     * of another major than the one it runs (a project pinned to TypeScript 5.4 checked by 5.9 can disagree with its
     * own {@code tsc}, JS review B109); "" otherwise. Reads the folder's package.json.
     */
    static String ignoredFolderVersion(Choice choice, Path root, boolean loose) {
        Path running = choice.tsserver();
        if (loose || choice.outcome() != Outcome.INSTALLED || running == null) {
            return "";
        }
        Path folders = findTsserver(root, null);
        if (folders == null || folders.equals(running)) {
            return "";
        }
        return differentMajor(sdkVersion(folders), sdkVersion(running));
    }

    /** {@code folder} when its major differs from {@code running}'s, else "". Pure. */
    static String differentMajor(String folder, String running) {
        int f = com.editora.install.TypeScriptServerInstall.major(folder);
        int r = com.editora.install.TypeScriptServerInstall.major(running);
        return f > 0 && r > 0 && f != r ? folder : "";
    }

    /**
     * The status bar's LSP segment for a file on this server: the TypeScript it runs ("TypeScript 5.9.3") once the
     * server said so — which TypeScript a file ran on was nowhere to be seen (JS review B109) — else {@code program}.
     * Pure.
     */
    public static String segmentName(String program, @Nullable String typeScriptVersion) {
        return typeScriptVersion == null || typeScriptVersion.isBlank() ? program : "TypeScript " + typeScriptVersion;
    }

    private static final java.util.Set<String> SOURCE_EXTENSIONS =
            java.util.Set.of(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs");

    /**
     * Whether renaming or moving {@code path} in the Project tree may need imports updated by this server: a JS/TS
     * source file, or a folder (which may hold some), outside {@code node_modules}. File test only.
     */
    public static boolean renamesImports(@Nullable Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        for (Path part : path) {
            if ("node_modules".equals(part.toString())) {
                return false;
            }
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".d.ts")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(name.substring(dot)) || Files.isDirectory(path);
    }

    /** The code-action kinds asked for explicitly: the server returns its source actions only when asked. */
    public static final List<String> CODE_ACTION_KINDS = List.of("quickfix", "refactor", "source");
}
