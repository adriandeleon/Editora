package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An edited formatter profile reaches a running jdtls through the window's own {@link LspManager}: saved, the
 * project's style is pushed again and the next Format Document follows it, with no restart. Opt-in (run with
 * {@code -Dgroups=probe -Dlsp.probe=true -Dlsp.probe.jdtls=PATH}).
 */
@Tag("probe")
class JdtlsProfileReloadProbeTest {

    private static final String APP =
            "package demo;\n\npublic class App {\n    long go() {\n        return 1;\n    }\n}\n";

    private static String profile(String brace) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <profiles version="23">
                <profile kind="CodeFormatterProfile" name="Probe" version="23">
                <setting id="org.eclipse.jdt.core.formatter.brace_position_for_method_declaration" value="%s"/>
                </profile>
                </profiles>
                """.formatted(brace);
    }

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static String format(LspManager m, Path file) throws Exception {
        var edits = new CompletableFuture<List<com.editora.text.LspTextEdit>>();
        javafx.application.Platform.runLater(() -> m.formatDocument(file, 4, true, edits::complete));
        String text = APP;
        List<com.editora.text.LspTextEdit> list = new java.util.ArrayList<>(edits.get(60, TimeUnit.SECONDS));
        list.sort(
                (x, y) -> x.startLine() != y.startLine() ? y.startLine() - x.startLine() : y.startCol() - x.startCol());
        for (var e : list) {
            int from = offset(text, e.startLine(), e.startCol());
            int to = offset(text, e.endLine(), e.endCol());
            text = text.substring(0, from) + e.newText() + text.substring(to);
        }
        return text;
    }

    private static int offset(String text, int line, int col) {
        int at = 0;
        for (int i = 0; i < line; i++) {
            at = text.indexOf('\n', at) + 1;
        }
        return at + col;
    }

    @Test
    void anEditedProfileIsFollowedWithoutARestart() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String jdtls = System.getProperty("lsp.probe.jdtls", "");
        Assumptions.assumeFalse(jdtls.isBlank(), "needs -Dlsp.probe.jdtls");
        Path scratch = Files.createTempDirectory("editora-jdtls-profile-").toRealPath();
        Path project = Files.createDirectories(scratch.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Path app = src.resolve("App.java");
        Files.writeString(app, APP);
        Path style = project.resolve("config/style.xml");
        Files.createDirectories(style.getParent());
        Files.writeString(style, profile("next_line"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>profile-probe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <build><plugins><plugin><groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId>
                <configuration><java><eclipse><file>${project.basedir}/%s</file></eclipse></java></configuration>
                </plugin></plugins></build></project>
                """.formatted(project.relativize(style)));
        LspManager m = new LspManager((f, d) -> {}, (t, msg) -> {});
        m.setJdtlsWorkspaceBase(Files.createDirectories(scratch.resolve("workspaces")));
        m.configure(true, Map.of("java", jdtls));
        try {
            m.openDocument(app, project, "java", APP);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            String before = "";
            while (!before.contains("go()\n    {") && System.nanoTime() < deadline) {
                Thread.sleep(500);
                before = m.isRunning("java", project) ? format(m, app) : "";
            }
            System.out.println("PROBE before = " + before.replace("\n", "\\n"));
            assertTrue(before.contains("go()\n    {"), "the profile's next-line brace");

            // as a window does: the first format applied, then undone
            m.changeDocument(app, before);
            m.changeDocument(app, APP);
            Files.writeString(style, profile("end_of_line"));
            m.projectStyleChanged(style); // what saving it in Editora does; the folder watcher then says it changed
            m.notifyWatchedFiles(List.of(new LspManager.WatchedFile(style, LspManager.WatchedKind.CHANGED)));
            String after = "";
            for (int attempt = 0; attempt < 20 && !after.contains("go() {"); attempt++) {
                Thread.sleep(500);
                after = format(m, app);
            }
            System.out.println("PROBE after = " + after.replace("\n", "\\n"));
            assertTrue(after.contains("go() {"), "the edited profile, no restart");

            // back to next-line, then the profile removed: jdtls's defaults come back (fourth review DN4)
            Files.writeString(style, profile("next_line"));
            m.projectStyleChanged(style);
            String again = "";
            for (int attempt = 0; attempt < 20 && !again.contains("go()\n    {"); attempt++) {
                Thread.sleep(500);
                again = format(m, app);
            }
            assertTrue(again.contains("go()\n    {"), "next-line again: " + again);
            Files.delete(style);
            m.projectStyleChanged(style);
            m.notifyWatchedFiles(List.of(new LspManager.WatchedFile(style, LspManager.WatchedKind.DELETED)));
            String removed = "";
            for (int attempt = 0; attempt < 20 && !removed.contains("go() {"); attempt++) {
                Thread.sleep(500);
                removed = format(m, app);
            }
            System.out.println("PROBE removed = " + removed.replace("\n", "\\n"));
            assertTrue(removed.contains("go() {"), "the removed profile no longer formats, no restart");
        } finally {
            m.close();
        }
    }
}
