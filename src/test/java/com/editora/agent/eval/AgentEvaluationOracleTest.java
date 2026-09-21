package com.editora.agent.eval;

import java.nio.file.*;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in checks that independent probes reject plausible broken tasks and accept complete repairs. */
class AgentEvaluationOracleTest {
    @TempDir
    Path scratch;

    @Test
    void fullProductSnapshotExcludesHiddenGradersAndStillCompiles() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.eval.oracles"));
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("editora-lsp-ui-understanding"))
                .findFirst()
                .orElseThrow();
        Path root = scratch.resolve("full-product");
        AgentEvaluationCases.prepare(task, Path.of("").toAbsolutePath(), root);
        assertTrue(Files.isRegularFile(root.resolve(task.active())));
        assertTrue(Files.isRegularFile(root.resolve("src/test/java/com/editora/io/DocumentWriteSequencerTest.java")));
        assertFalse(Files.exists(root.resolve("src/test/java/com/editora/agent/eval/AgentReliabilityCases.java")));
        assertFalse(Files.exists(root.resolve("src/test/java/com/editora/ui/AgentCodingEvaluationTest.java")));
        var result = com.editora.process.ProcessRunner.runRestricted(
                root, java.time.Duration.ofSeconds(120), List.of("mvn", "-q", "-o", "-DskipTests", "test"));
        assertEquals(0, result.exit(), result.out() + result.err());
    }

    @Test
    void saveCancellationOracleRejectsOlderTicketInvalidatingNewerSave() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.eval.oracles"));
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("editora-save-cancellation"))
                .findFirst()
                .orElseThrow();
        Path root = scratch.resolve("save"),
                classes = root.resolve("target/classes"),
                outside = scratch.resolve("save-oracle");
        AgentEvaluationCases.copyTree(Path.of("target/classes"), classes);
        // This isolated behavioral probe uses a classpath, not the application's JPMS launcher.
        Files.deleteIfExists(classes.resolve("module-info.class"));
        Files.createDirectories(outside);
        Path source = root.resolve("DocumentWriteSequencer.java");
        String original = Files.readString(Path.of(task.active()));
        String broken = original.replace(
                "state.generation.compareAndSet(generation, generation + 1);", "state.generation.incrementAndGet();");
        assertNotEquals(original, broken);
        for (boolean repaired : List.of(false, true)) {
            Files.writeString(source, repaired ? original : broken);
            var compile = com.editora.process.ProcessRunner.runRestricted(
                    root,
                    java.time.Duration.ofSeconds(30),
                    List.of(
                            Path.of(System.getProperty("java.home"), "bin", "javac")
                                    .toString(),
                            "-cp",
                            classes.toString(),
                            "-d",
                            classes.toString(),
                            source.toString()));
            assertEquals(0, compile.exit(), compile.err());
            assertEquals(repaired, AgentReliabilityCases.oracle(task, root, outside));
        }
    }

    @Test
    void newComponentAndEightFileOraclesRejectOriginalAndAcceptRepair() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.eval.oracles"));
        for (String id : List.of("editora-diff-newline", "editora-stash-overflow", "billing-contract-migration")) {
            var task = AgentEvaluationCases.tasks().stream()
                    .filter(t -> t.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            Path root = scratch.resolve(id), outside = scratch.resolve(id + "-oracle");
            AgentEvaluationCases.prepare(task, Path.of(".").toAbsolutePath(), root);
            assertFalse(
                    AgentEvaluationCases.oracle(task, root, outside), id + " original must fail independent grading");
            if (id.equals("editora-diff-newline")) {
                Files.writeString(root.resolve(task.active()), Files.readString(Path.of(task.active())));
            } else if (id.equals("editora-stash-overflow")) {
                Path file = root.resolve(task.active());
                String source = Files.readString(file);
                source = source.replace(
                                "int idx = Integer.parseInt(m.group(1));",
                                "int idx; try { idx = Integer.parseInt(m.group(1)); } catch(NumberFormatException invalid) { continue; }")
                        .replace(
                                "idx = Integer.parseInt(im.group(1));",
                                "try { idx = Integer.parseInt(im.group(1)); } catch(NumberFormatException invalid) { continue; }");
                Files.writeString(file, source);
            } else {
                for (String file : task.required()) {
                    Path p = root.resolve(file);
                    Files.writeString(p, Files.readString(p).replace("totalFor", "invoiceTotal"));
                }
                assertEquals(8, task.required().size());
            }
            assertTrue(
                    AgentEvaluationCases.oracle(task, root, outside),
                    id + " complete repair must pass independent grading");
            // Never fall back to the harness's production classes when a fixture class is missing.
            Path compiled = root.resolve("target/classes")
                    .resolve(task.active().substring("src/main/java/".length()).replace(".java", ".class"));
            Files.delete(compiled);
            assertFalse(AgentReliabilityCases.oracle(task, root, outside), id + " missing fixture class must fail");
        }
    }

    @Test
    void regressionQualityRejectsVacuousPassingTestAndProvesRealRegressionOnlyInCopy() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.eval.oracles"));
        var task = AgentAcceptanceCases.tasks().stream()
                .filter(t -> t.id().equals("editora-diff-test-quality"))
                .findFirst()
                .orElseThrow();
        Path root = scratch.resolve("quality");
        AgentEvaluationCases.prepare(task, Path.of(".").toAbsolutePath(), root);
        Path source = root.resolve(task.active());
        String old = Files.readString(source), fixed = Files.readString(Path.of(task.active()));
        Files.writeString(source, fixed);
        Path test = root.resolve("src/test/java/com/editora/diff/QualityTest.java");
        for (boolean effective : List.of(false, true)) {
            String assertion = effective
                    ? "assertEquals(\"x\", DiffText.parse(\"x\").compose(java.util.List.of(\"x\")));"
                    : "assertTrue(true);";
            Files.writeString(
                    test,
                    "package com.editora.diff; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class QualityTest { @Test void regression() { "
                            + assertion + " } }");
            var result = AgentAcceptanceCases.regressionQuality(
                    task,
                    root,
                    scratch.resolve("quality-" + effective),
                    old,
                    java.util.Set.of("com.editora.diff.QualityTest#regression"));
            assertEquals(
                    effective ? "TEST_PROVEN_TO_DETECT_OLD_FAILURE" : "NOT_PROVEN",
                    result.path("state").asText(),
                    result.toString());
            assertEquals(fixed, Files.readString(source), "proof must not restore the bug in the agent workspace");
        }
    }
}
