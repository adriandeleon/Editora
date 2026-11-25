package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** The language server ids Editora knows and each one's configured command from {@link com.editora.config.Settings}. Pure. */
final class LspServerCommands {
    private LspServerCommands() {}

    /** Known LSP server ids (the configure/detect/gating loops iterate these). */
    static final String[] SERVER_IDS = {
        "java",
        "typescript",
        "python",
        "xml",
        "json",
        "bash",
        "yaml",
        "go",
        "rust",
        "php",
        "ruby",
        "clangd",
        "html",
        "css",
        "kotlin",
        "groovy",
        "scala",
        "clojure",
        "lua",
        "dockerfile",
        "sql",
        "terraform",
        "toml",
        "csharp",
        "typst",
        "astro",
        "dart",
        "elixir",
        "powershell",
        com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID
    };

    /**
     * Every known server's configured command, keyed by server id — what {@link LspManager#configure} needs.
     *
     * <p><b>Derived from {@link #SERVER_IDS}, never hand-listed.</b> This used to be a literal
     * {@code Map.ofEntries(...)} of 22 entries against a 23-entry {@code SERVER_IDS}, and the one it omitted
     * was {@code maven-pom} — whose registry default command is deliberately blank (it is only known after
     * install). So {@code commandFor} returned an empty argv, {@code available()} said false, and the
     * Maven-aware {@code pom.xml} server could <em>never</em> start, while the Settings row, the in-app
     * installer and Doctor (which all read {@code Settings} directly) reported it present and configured
     * (#723). Building the map from the same array the detect/gating loops walk makes that drift
     * unrepresentable; {@code LspCoordinatorServerIdsTest} pins it.
     *
     * <p>A {@code HashMap} rather than {@code Map.ofEntries} on purpose: it tolerates a null command from a
     * config where the field was explicitly nulled, which {@code Map.ofEntries} would turn into an NPE
     * escaping {@code applySupport} — i.e. LSP silently dead for the session.
     */
    static Map<String, String> commandsForAllServers(com.editora.config.Settings s) {
        Map<String, String> commands = new java.util.HashMap<>();
        for (String serverId : SERVER_IDS) {
            commands.put(serverId, serverCommand(s, serverId));
        }
        return commands;
    }

    /** Pure: {@code serverId}'s configured command from {@code s} — the id→Settings-field mapping, kept
     *  static so {@link #commandsForAllServers} (and its guard test) need no window/host (#723). */
    static String serverCommand(com.editora.config.Settings s, String serverId) {
        return switch (serverId) {
            case "typescript" -> s.getTypescriptLspCommand();
            case "python" -> s.getPythonLspCommand();
            // A Java lemminx (the pom.xml server's, where the native build cannot run) runs on the Maven JDK too.
            case "xml" -> com.editora.lsp.JvmServerCommand.withJdk(s.getXmlLspCommand(), s.getMavenJdkHome());
            case "json" -> s.getJsonLspCommand();
            case "bash" -> s.getBashLspCommand();
            case "yaml" -> s.getYamlLspCommand();
            case "go" -> s.getGoLspCommand();
            case "rust" -> s.getRustLspCommand();
            case "php" -> s.getPhpLspCommand();
            case "ruby" -> s.getRubyLspCommand();
            case "clangd" -> s.getClangdLspCommand();
            case "html" -> s.getHtmlLspCommand();
            case "css" -> s.getCssLspCommand();
            case "kotlin" -> s.getKotlinLspCommand();
            case "groovy" -> com.editora.lsp.JvmServerCommand.withJdk(s.getGroovyLspCommand(), s.getMavenJdkHome());
            case "scala" -> com.editora.lsp.JvmServerCommand.withJdk(s.getScalaLspCommand(), s.getMavenJdkHome());
            case "clojure" -> s.getClojureLspCommand();
            case "lua" -> s.getLuaLspCommand();
            case "dart" -> s.getDartLspCommand();
            case "elixir" -> s.getElixirLspCommand();
            case "powershell" -> s.getPowershellLspCommand();
            case "dockerfile" -> s.getDockerfileLspCommand();
            case "sql" -> s.getSqlLspCommand();
            case "terraform" -> s.getTerraformLspCommand();
            case "toml" -> s.getTomlLspCommand();
            case "csharp" -> s.getCsharpLspCommand();
            case "typst" -> s.getTypstLspCommand();
            case "astro" -> s.getAstroLspCommand();
            // The stored command starts with a bare `java`: run it on the Maven JDK like groovy/scala (review C4).
            case "maven-pom" ->
                com.editora.lsp.JvmServerCommand.withJdk(s.getMavenPomLspCommand(), s.getMavenJdkHome());
            default -> s.getJavaLspCommand();
        };
    }

    /** Sets {@code serverId}'s per-server enable toggle in {@code s} — the mirror of the window's enable read. */
    static void setServerEnabled(com.editora.config.Settings s, String serverId, boolean on) {
        switch (serverId) {
            case "typescript" -> s.setTypescriptLspEnabled(on);
            case "python" -> s.setPythonLspEnabled(on);
            case "xml" -> s.setXmlLspEnabled(on);
            case "json" -> s.setJsonLspEnabled(on);
            case "bash" -> s.setBashLspEnabled(on);
            case "yaml" -> s.setYamlLspEnabled(on);
            case "go" -> s.setGoLspEnabled(on);
            case "rust" -> s.setRustLspEnabled(on);
            case "php" -> s.setPhpLspEnabled(on);
            case "ruby" -> s.setRubyLspEnabled(on);
            case "clangd" -> s.setClangdLspEnabled(on);
            case "html" -> s.setHtmlLspEnabled(on);
            case "css" -> s.setCssLspEnabled(on);
            case "kotlin" -> s.setKotlinLspEnabled(on);
            case "groovy" -> s.setGroovyLspEnabled(on);
            case "scala" -> s.setScalaLspEnabled(on);
            case "clojure" -> s.setClojureLspEnabled(on);
            case "lua" -> s.setLuaLspEnabled(on);
            case "dart" -> s.setDartLspEnabled(on);
            case "elixir" -> s.setElixirLspEnabled(on);
            case "powershell" -> s.setPowershellLspEnabled(on);
            case "dockerfile" -> s.setDockerfileLspEnabled(on);
            case "sql" -> s.setSqlLspEnabled(on);
            case "terraform" -> s.setTerraformLspEnabled(on);
            case "toml" -> s.setTomlLspEnabled(on);
            case "csharp" -> s.setCsharpLspEnabled(on);
            case "typst" -> s.setTypstLspEnabled(on);
            case "astro" -> s.setAstroLspEnabled(on);
            case "maven-pom" -> s.setMavenPomLspEnabled(on);
            default -> s.setJavaLspEnabled(on);
        }
    }

    /** Known server ids in Settings-page order (read by the Doctor screen's check catalog). */
    static java.util.List<String> serverIds() {
        return java.util.List.of(SERVER_IDS);
    }

    /** {@code command} for {@code serverId}: Editora's own copy, kept outside PATH, for a blank XML, pom, HTML, CSS,
     *  JSON or Elixir command (review A9, review 2 A3, HTML A2), on the Maven JDK ({@code jdkHome}). Pure but for file lookups. */
    static String withOwnCopy(
            String serverId, @Nullable String command, @Nullable Path configDir, @Nullable String jdkHome) {
        return OWN_COPIES.get(
                new OwnCopyKey(serverId, command == null ? "" : command, configDir, jdkHome == null ? "" : jdkHome));
    }

    /** Whether {@link #withOwnCopy} for these has an answer kept (it then answers from memory). */
    static boolean ownCopyKnown(
            String serverId, @Nullable String command, @Nullable Path configDir, @Nullable String jdkHome) {
        return OWN_COPIES.knows(key(serverId, command, configDir, jdkHome));
    }

    /** Looks {@link #withOwnCopy}'s answer up now, on this thread (off the FX thread), and keeps it. */
    static void prepareOwnCopy(
            String serverId, @Nullable String command, @Nullable Path configDir, @Nullable String jdkHome) {
        OWN_COPIES.prepare(key(serverId, command, configDir, jdkHome));
    }

    private static OwnCopyKey key(
            String serverId, @Nullable String command, @Nullable Path configDir, @Nullable String jdkHome) {
        return new OwnCopyKey(serverId, command == null ? "" : command, configDir, jdkHome == null ? "" : jdkHome);
    }

    /** What {@link #withOwnCopy} is asked: a server, its configured command, the config folder and the JDK. */
    record OwnCopyKey(String serverId, String raw, @Nullable Path configDir, String jdk) {}

    /**
     * The probes behind {@link #withOwnCopy}, kept: a window's set-up and every settings apply ask for all 26
     * servers, twice, and each probe stats files under the config folder. The launch fills it off the FX thread
     * ({@link #prepareOwnCopies}); an install {@linkplain #forgetOwnCopies forgets} it.
     */
    private static final com.editora.perf.ProbeCache<OwnCopyKey, String> OWN_COPIES =
            new com.editora.perf.ProbeCache<>("lsp-own-copies", LspServerCommands::probeOwnCopySafely);

    /** {@link #probeOwnCopy}, never throwing: a copy that cannot be looked at is not used (the command as set). */
    private static String probeOwnCopySafely(OwnCopyKey key) {
        try {
            return probeOwnCopy(key);
        } catch (RuntimeException e) {
            return key.raw();
        }
    }

    /** Looks up, on the calling thread, every server's launch command for {@code s}: the launch's warm-up. */
    static void prepareOwnCopies(com.editora.config.Settings s, @Nullable Path configDir) {
        String jdk = s.getMavenJdkHome() == null ? "" : s.getMavenJdkHome();
        commandsForAllServers(s)
                .forEach((id, command) ->
                        OWN_COPIES.prepare(new OwnCopyKey(id, command == null ? "" : command, configDir, jdk)));
    }

    /** Looks at every kept launch command again, off the FX thread; {@code onChange} when one moved. */
    static void refreshOwnCopies(Runnable onChange) {
        OWN_COPIES.refresh(onChange);
    }

    /** Looks every kept launch command up again now, on this thread (off the FX thread): after an install. */
    static void reprobeOwnCopies() {
        OWN_COPIES.reprobeKept();
    }

    /** Drops the kept launch commands: an install or removal changed what is on disk. */
    static void forgetOwnCopies() {
        OWN_COPIES.forget();
    }

    private static String probeOwnCopy(OwnCopyKey key) {
        String serverId = key.serverId();
        String raw = key.raw();
        String jdk = key.jdk();
        Path configDir = key.configDir();
        if (com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId) && raw.isBlank()) {
            String own = com.editora.install.MavenServerInstall.effectiveCommand(raw, configDir);
            return own.isBlank() ? raw : com.editora.lsp.JvmServerCommand.withJdk(own, jdk);
        }
        if (com.editora.install.PowerShellServerInstall.SERVER_ID.equals(serverId)) {
            return com.editora.install.PowerShellServerInstall.effectiveCommand(raw, configDir); // PSES's start script
        }
        if (com.editora.install.ElixirServerInstall.SERVER_ID.equals(serverId)) {
            return com.editora.install.ElixirServerInstall.effectiveCommand(raw, configDir); // ElixirLS's own install
        }
        if (com.editora.install.TypeScriptServerInstall.SERVER_ID.equals(serverId)) {
            return com.editora.install.TypeScriptServerInstall.effectiveCommand(raw, configDir); // JS review B104
        }
        return "xml".equals(serverId)
                ? com.editora.install.XmlServerInstall.effectiveCommand(raw, configDir, jdk)
                : com.editora.install.WebServerInstall.effectiveCommand(serverId, raw, configDir); // HTML A2
    }

    /** {@link #commandsForAllServers} as they are launched: {@link #withOwnCopy} applied to each. */
    static Map<String, String> launchCommandsForAllServers(com.editora.config.Settings s, @Nullable Path configDir) {
        Map<String, String> commands = commandsForAllServers(s);
        commands.replaceAll((id, command) -> withOwnCopy(id, command, configDir, s.getMavenJdkHome()));
        return commands;
    }
}
