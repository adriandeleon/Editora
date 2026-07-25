package com.editora.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.editora.config.Settings;
import com.editora.lsp.LspServerRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the agreement between {@code LspCoordinator.SERVER_IDS} — the array the detect/gating loops walk —
 * and the command map handed to {@code LspManager.configure}.
 *
 * <p>These used to be two independently maintained lists: a literal {@code Map.ofEntries(...)} of 22 entries
 * against 23 ids. The omitted one was {@code maven-pom}, whose registry default command is deliberately blank
 * (it is only known after install), so {@code commandFor} returned an empty argv, {@code available()} said
 * false, and the Maven-aware {@code pom.xml} server could never start — while Settings, the in-app installer
 * and Doctor all read the setting directly and reported it present (#723).
 */
class LspCoordinatorServerIdsTest {

    @Test
    void everyServerIdGetsItsCommandInTheConfigureMap() {
        var commands = LspCoordinator.commandsForAllServers(new Settings());
        for (String id : LspCoordinator.serverIds()) {
            assertTrue(commands.containsKey(id), "no command mapped for server id: " + id);
        }
        assertEquals(
                LspCoordinator.serverIds().size(),
                commands.size(),
                "the configure map must cover exactly SERVER_IDS — no more, no fewer");
    }

    /**
     * The id→Settings-field switch ends in {@code default -> java}, so a typo'd or newly-added id silently
     * returns the <em>Java</em> command instead of its own. Distinct per-server values catch that: each id
     * must read back the value written for it, not another server's.
     */
    @Test
    void eachServerIdReadsItsOwnSettingsFieldNotJavasByDefault() {
        Settings s = new Settings();
        List<String> ids = LspCoordinator.serverIds();
        for (String id : ids) {
            setCommand(s, id, "cmd-for-" + id);
        }
        var commands = LspCoordinator.commandsForAllServers(s);
        for (String id : ids) {
            assertEquals("cmd-for-" + id, commands.get(id), "server id " + id + " reads the wrong Settings field");
        }
        Set<String> distinct = new HashSet<>(commands.values());
        assertEquals(ids.size(), distinct.size(), "two server ids share one Settings field");
    }

    /** Every id the coordinator drives must be a server the registry actually knows. */
    @Test
    void everyServerIdIsKnownToTheRegistry() {
        for (String id : LspCoordinator.serverIds()) {
            assertNotNull(LspServerRegistry.defaultCommandFor(id), "registry does not know server id: " + id);
            assertTrue(!LspServerRegistry.rootMarkersForServer(id).isEmpty(), "no root markers for server id: " + id);
        }
    }

    /**
     * {@code maven-pom} specifically: its default command is blank by design, so it is the one id for which
     * an absent map entry is indistinguishable from "not installed" — the exact shape of #723.
     */
    @Test
    void mavenPomCarriesItsConfiguredCommandThrough() {
        Settings s = new Settings();
        assertEquals(
                "",
                LspServerRegistry.defaultCommandFor(LspServerRegistry.MAVEN_POM_SERVER_ID),
                "precondition: maven-pom has no built-in default command");
        s.setMavenPomLspCommand("java -cp /opt/lemminx/* org.eclipse.lemminx.XMLServerLauncher");
        var commands = LspCoordinator.commandsForAllServers(s);
        assertEquals(
                "java -cp /opt/lemminx/* org.eclipse.lemminx.XMLServerLauncher",
                commands.get(LspServerRegistry.MAVEN_POM_SERVER_ID));
        // …and that command must tokenize to a launchable argv (empty ⇒ available() is false ⇒ never starts).
        assertTrue(
                LspServerRegistry.commandFor(LspServerRegistry.MAVEN_POM_SERVER_ID, commands)
                                .size()
                        > 1,
                "maven-pom's configured command must reach the registry as a real argv");
    }

    private static void setCommand(Settings s, String id, String v) {
        switch (id) {
            case "typescript" -> s.setTypescriptLspCommand(v);
            case "python" -> s.setPythonLspCommand(v);
            case "xml" -> s.setXmlLspCommand(v);
            case "json" -> s.setJsonLspCommand(v);
            case "bash" -> s.setBashLspCommand(v);
            case "yaml" -> s.setYamlLspCommand(v);
            case "go" -> s.setGoLspCommand(v);
            case "rust" -> s.setRustLspCommand(v);
            case "php" -> s.setPhpLspCommand(v);
            case "ruby" -> s.setRubyLspCommand(v);
            case "clangd" -> s.setClangdLspCommand(v);
            case "html" -> s.setHtmlLspCommand(v);
            case "css" -> s.setCssLspCommand(v);
            case "kotlin" -> s.setKotlinLspCommand(v);
            case "lua" -> s.setLuaLspCommand(v);
            case "dockerfile" -> s.setDockerfileLspCommand(v);
            case "sql" -> s.setSqlLspCommand(v);
            case "terraform" -> s.setTerraformLspCommand(v);
            case "toml" -> s.setTomlLspCommand(v);
            case "csharp" -> s.setCsharpLspCommand(v);
            case "typst" -> s.setTypstLspCommand(v);
            case "maven-pom" -> s.setMavenPomLspCommand(v);
            case "java" -> s.setJavaLspCommand(v);
            default ->
                throw new AssertionError(
                        "test does not know server id: " + id + " — add its Settings setter here when adding a server");
        }
    }
}
