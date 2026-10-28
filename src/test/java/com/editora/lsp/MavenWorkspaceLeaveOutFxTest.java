package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A malformed pom is taken out of lemminx-maven's workspace by its own file URI (review 2, E3): one
 * {@code didChangeWorkspaceFolders} that removes it and adds nothing, sent once per pom for the session.
 */
@Tag("fx")
class MavenWorkspaceLeaveOutFxTest {

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private Path pom;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.attachForTest(fake, new ServerCapabilities());
        });
        manager.setFolderTrust(folder -> true);
        manager.configure(true, Map.of(LspServerRegistry.MAVEN_POM_SERVER_ID, "lemminx-maven"));
        pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project/>\n");
        manager.openDocument(pom, root, LspServerRegistry.MAVEN_POM_LANGUAGE_ID, "<project/>\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    @Test
    void aMalformedPomLeavesTheWorkspaceByItsOwnUriOnce() throws Exception {
        Path broken = root.resolve("broken/pom.xml");
        Path other = root.resolve("wip/pom.xml");

        assertEquals(List.of(broken, other), manager.leaveOutOfMavenWorkspace(pom, List.of(broken, other)));
        assertEquals(List.of(), manager.leaveOutOfMavenWorkspace(pom, List.of(broken)), "already out");

        FakeLanguageServer fake = fakes.get(0);
        assertEquals(1, fake.workspaceFolderChanges.size());
        var event = fake.workspaceFolderChanges.get(0).getEvent();
        assertTrue(event.getAdded().isEmpty(), "nothing is added: a re-added pom brings its failed build back");
        assertEquals(
                List.of(LspTestHooks.uri(broken), LspTestHooks.uri(other)),
                event.getRemoved().stream().map(WorkspaceFolder::getUri).toList());
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "<project>");
        manager.openDocument(broken, root, LspServerRegistry.MAVEN_POM_LANGUAGE_ID, "<project>"); // saved from a tab
        assertTrue(manager.leftOutOfMavenWorkspace(broken));
        assertFalse(manager.leftOutOfMavenWorkspace(pom));
    }

    @Test
    void theLeftOutPomsAndTheRootAreKnownByFile() {
        Path broken = root.resolve("broken/pom.xml");
        manager.leaveOutOfMavenWorkspace(pom, List.of(broken));

        assertEquals(List.of(broken), manager.mavenLeftOut(pom));
        assertEquals(root, manager.mavenRoot(pom));
        // a pom not open (left out, or changed on disk) belongs to the Maven server whose root holds it (E24)
        assertEquals(root, manager.mavenRoot(broken));
        assertTrue(manager.leftOutOfMavenWorkspace(broken));
        assertEquals(List.of(broken), manager.mavenLeftOut(broken));
        assertEquals(null, manager.mavenRoot(root.resolveSibling("elsewhere/pom.xml")), "no server's root holds it");
    }

    @Test
    void aServerNotReadyLeavesNothingOutAndRecordsNothing() throws Exception {
        manager.shutdownAll();
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {}); // never attached: never ready
        manager.setFolderTrust(folder -> true);
        manager.configure(true, Map.of(LspServerRegistry.MAVEN_POM_SERVER_ID, "lemminx-maven"));
        manager.openDocument(pom, root, LspServerRegistry.MAVEN_POM_LANGUAGE_ID, "<project/>\n");
        Path broken = root.resolve("broken/pom.xml");

        assertEquals(List.of(), manager.leaveOutOfMavenWorkspace(pom, List.of(broken)));
        assertFalse(manager.leftOutOfMavenWorkspace(broken), "nothing was sent, so nothing is claimed (E Gaps)");
    }

    @Test
    void theServersLoggedWorkspaceFailureIsCounted() {
        assertEquals(0, manager.mavenCompletionFailures(pom));
        var session = manager.sessionForTest(pom);
        session.logMessage(new org.eclipse.lsp4j.MessageParams(
                org.eclipse.lsp4j.MessageType.Error,
                "Message: While performing ICompletionParticipant#onXMLContent for participant "
                        + "'org.eclipse.lemminx.extensions.maven.participants.completion.MavenCompletionParticipant'.\n"
                        + "java.lang.NullPointerException: Cannot invoke \"MavenProject.getGroupId()\"\n"
                        + "\tat org.eclipse.lemminx.extensions.maven.participants.completion.MavenCompletionParticipant"
                        + ".internalCollectWorkspaceArtifacts(MavenCompletionParticipant.java:1400)"));
        session.logMessage(
                new org.eclipse.lsp4j.MessageParams(org.eclipse.lsp4j.MessageType.Info, "Local repo updater started"));
        assertEquals(1, manager.mavenCompletionFailures(pom));
    }

    @Test
    void thePomServerAsksForTheServersLog() {
        Object logs = manager.xmlSectionFor(LspServerRegistry.MAVEN_POM_SERVER_ID, root)
                .get("logs");
        assertEquals(Map.of("client", true), logs, "lemminx-maven's failure is only in its log (E21)");
        assertEquals(null, manager.xmlSectionFor("xml", root).get("logs"), "the plain XML server: unchanged");
    }

    @Test
    void aFileOnAnotherServerLeavesNothingOut() throws Exception {
        manager.configure(true, Map.of(LspServerRegistry.MAVEN_POM_SERVER_ID, "lemminx-maven", "java", "jdtls"));
        Path java = root.resolve("A.java");
        Files.writeString(java, "class A {}\n");
        manager.openDocument(java, root, "java", "class A {}\n");

        assertEquals(List.of(), manager.leaveOutOfMavenWorkspace(java, List.of(root.resolve("broken/pom.xml"))));
        assertTrue(fakes.stream().allMatch(f -> f.workspaceFolderChanges.isEmpty()));
    }
}
