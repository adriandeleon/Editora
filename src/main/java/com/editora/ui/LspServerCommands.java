package com.editora.ui;

import java.util.Map;

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

    /** Known server ids in Settings-page order (read by the Doctor screen's check catalog). */
    static java.util.List<String> serverIds() {
        return java.util.List.of(SERVER_IDS);
    }
}
