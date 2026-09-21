package com.editora.agent.eval;

import java.nio.file.*;
import java.util.*;

import com.editora.process.ProcessRunner;

import static com.editora.agent.eval.AgentEvaluationCases.*;

/** Phase 4 corpus: real components, full repository investigations, and a bounded eight-file migration. */
final class AgentReliabilityCases {
    private static final String MAIN = "src/main/java/com/editora/", TEST = "src/test/java/com/editora/";

    static List<Task> tasks() {
        var migration = new TreeSet<String>();
        for (String file : List.of("Ledger", "Invoice", "Quote", "Receipt", "Statement", "BillingJob", "Preview"))
            migration.add("src/main/java/demo/" + file + ".java");
        migration.add("src/test/java/demo/BillingContractTest.java");
        return List.of(
                component(
                        "editora-diff-newline",
                        "diff/DiffText",
                        "A diff recomposition now always terminates the last line, corrupting files without a final newline. Fix the regression without losing CRLF, CR, blank-line or empty-file behavior. Inspect and update regression tests and validate."),
                component(
                        "editora-stash-overflow",
                        "git/StashParser",
                        "Stash parsing throws when a stash index exceeds the integer range, losing subsequent valid entries. Ignore an overflowing indexed entry in both normal and fallback formats, preserve valid and unknown-format entries, and add regression tests. Existing tests pass but do not cover this behavior. Validate your fix."),
                new Task(
                        "editora-save-cancellation",
                        "bug",
                        "Closing an older buffer can cancel a newer pending save in another window. Investigate asynchronous save ordering rather than assuming the atomic file replacement is at fault. Fix the cause with a concurrency regression test and validate. Keep the change in the write sequencer and its tests.",
                        MAIN + "io/DocumentWriteSequencer.java",
                        Set.of(MAIN + "io/DocumentWriteSequencer.java", TEST + "io/DocumentWriteSequencerTest.java"),
                        Set.of(MAIN + "io/DocumentWriteSequencer.java"),
                        false,
                        false,
                        false,
                        56),
                new Task(
                        "editora-lsp-ui-understanding",
                        "understanding",
                        "Trace how a native agent opens an invisible Java document, synchronizes unsaved edits, checks semantic response freshness, and survives a server restart. Explain the JavaFX/background boundaries and what an unversioned diagnostic can actually prove. Cite relevant callers and tests. Do not edit.",
                        MAIN + "ui/WindowAgentSemantics.java",
                        Set.of(),
                        Set.of(),
                        true,
                        true,
                        true,
                        40),
                new Task(
                        "editora-settings-persistence-understanding",
                        "understanding",
                        "Trace how per-model native agent settings persist and migrate without replacing existing user choices. Inspect defaults, schema migration, dialog synchronization and tests. Identify any unsupported sampling controls without inventing provider guarantees. Do not edit.",
                        MAIN + "config/Settings.java",
                        Set.of(),
                        Set.of(),
                        true,
                        true,
                        false,
                        40),
                new Task(
                        "billing-contract-migration",
                        "refactor",
                        "Rename Ledger.totalFor to invoiceTotal across this billing application, updating every caller and existing test. Remove the old API, preserve prices and overflow behavior, and validate the project. This affects several independent presentation and background consumers; inspect all references before editing.",
                        "src/main/java/demo/Ledger.java",
                        migration,
                        migration,
                        false,
                        false,
                        true,
                        60));
    }

    private static Task component(String id, String type, String prompt) {
        return new Task(
                id,
                "bug",
                prompt,
                MAIN + type + ".java",
                Set.of(MAIN + type + ".java", TEST + type + "Test.java"),
                Set.of(MAIN + type + ".java"),
                false,
                false,
                false,
                36);
    }

    static boolean fullSnapshot(Task task, Path source, Path root) throws Exception {
        if (!Set.of(
                        "editora-save-cancellation",
                        "editora-lsp-ui-understanding",
                        "editora-settings-persistence-understanding")
                .contains(task.id())) return false;
        for (String tree : List.of("src", "docs")) copyTree(source.resolve(tree), root.resolve(tree));
        hideOracles(root);
        for (String file : List.of("pom.xml", "README.md", "AGENTS.md", "TODO.md", ".gitignore"))
            Files.copy(source.resolve(file), root.resolve(file));
        if (task.id().equals("editora-save-cancellation")) {
            Path p = root.resolve(task.active());
            String original = Files.readString(p),
                    seeded =
                            original.replace(
                                    "state.generation.compareAndSet(generation, generation + 1);",
                                    "state.generation.incrementAndGet();");
            if (original.equals(seeded)) throw new IllegalStateException("Sequencer seed needs explicit revision");
            Files.writeString(p, seeded);
        }
        return true;
    }

    static void hideOracles(Path root) throws Exception {
        // A full product snapshot must not give the model its hidden grading implementation.
        // Exclude the evaluation package and tests importing it, keeping the ordinary suite compilable.
        try (var files = Files.walk(root.resolve("src/test/java"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList())
                if (Files.readString(file).contains("com.editora.agent.eval")) Files.delete(file);
        }
    }

    static boolean prepare(Task task, Path source, Path root) throws Exception {
        String type =
                switch (task.id()) {
                    case "editora-diff-newline" -> "diff/DiffText";
                    case "editora-stash-overflow" -> "git/StashParser";
                    default -> null;
                };
        if (type != null) {
            for (String file : List.of(MAIN + type + ".java", TEST + type + "Test.java"))
                write(root, file, Files.readString(source.resolve(file)));
            if (task.id().equals("editora-diff-newline")) {
                Path p = root.resolve(task.active());
                String old = Files.readString(p),
                        seed =
                                old.replace(
                                        "return finalNewline ? body + lineSeparator : body;",
                                        "return body + lineSeparator;");
                if (old.equals(seed)) throw new IllegalStateException("Diff seed needs explicit revision");
                Files.writeString(p, seed);
            }
            return true;
        }
        if (!task.id().equals("billing-contract-migration")) return false;
        write(
                root,
                "src/main/java/demo/Line.java",
                "package demo; public record Line(long unitCents,int quantity) {}\n");
        write(
                root,
                "src/main/java/demo/Ledger.java",
                "package demo; import java.util.List; public class Ledger { public long totalFor(List<Line> lines) { long total=0; for(var line:lines)total=Math.addExact(total,Math.multiplyExact(line.unitCents(),line.quantity()));return total; } }\n");
        for (String name : List.of("Invoice", "Quote", "Receipt", "Statement", "BillingJob", "Preview"))
            write(
                    root,
                    "src/main/java/demo/" + name + ".java",
                    "package demo; import java.util.List; public class " + name
                            + " { public long total(List<Line> lines) { return new Ledger().totalFor(lines); } }\n");
        write(
                root,
                "src/test/java/demo/BillingContractTest.java",
                "package demo; import java.util.List; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class BillingContractTest { @Test void price() { assertEquals(6,new Ledger().totalFor(List.of(new Line(2,3)))); } }\n");
        return true;
    }

    static Boolean oracle(Task task, Path root, Path outside) throws Exception {
        String body =
                switch (task.id()) {
                    case "editora-diff-newline" ->
                        "for(String text:new String[]{\"\",\"x\",\"a\\r\\nb\",\"a\\rb\",\"a\\n\\n\",\"a\\r\\n\"}){var d=com.editora.diff.DiffText.parse(text);if(!text.equals(d.compose(d.lines())))throw new AssertionError();}";
                    case "editora-stash-overflow" ->
                        "for(String prefix:new String[]{\"On main: \" ,\"unusual \"}){var entries=com.editora.git.StashParser.parse(\"stash@{999999999999999999999}: \"+prefix+\"bad\\nstash@{2}: On main: good\");if(entries.size()!=1||entries.getFirst().index()!=2)throw new AssertionError();}";
                    case "editora-save-cancellation" ->
                        "var seq=new com.editora.io.DocumentWriteSequencer();try(var old=seq.begin(java.nio.file.Path.of(\"document\"));var newer=seq.begin(java.nio.file.Path.of(\"document\"))){old.invalidate();if(!newer.runIfCurrent(()->true).executed()||old.runIfCurrent(()->true).executed())throw new AssertionError();}";
                    case "billing-contract-migration" ->
                        "var input=java.util.List.of(new demo.Line(7,3)); if((long)demo.Ledger.class.getMethod(\"invoiceTotal\",java.util.List.class).invoke(new demo.Ledger(),input)!=21)throw new AssertionError();try{demo.Ledger.class.getMethod(\"totalFor\",java.util.List.class);throw new AssertionError();}catch(NoSuchMethodException expected){} for(String name:new String[]{\"Invoice\",\"Quote\",\"Receipt\",\"Statement\",\"BillingJob\",\"Preview\"}){var type=Class.forName(\"demo.\"+name);if((long)type.getMethod(\"total\",java.util.List.class).invoke(type.getConstructor().newInstance(),input)!=21)throw new AssertionError();}try{demo.Ledger.class.getMethod(\"invoiceTotal\",java.util.List.class).invoke(new demo.Ledger(),java.util.List.of(new demo.Line(Long.MAX_VALUE,2)));throw new AssertionError();}catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof ArithmeticException))throw expected;}";
                    default -> null;
                };
        if (body == null) return null;
        Path probe = outside.resolve("Oracle.java");
        Files.writeString(
                probe, "class Oracle {public static void main(String[] args) throws Exception {" + body + "}}");
        return ProcessRunner.runRestricted(
                                outside,
                                java.time.Duration.ofSeconds(30),
                                List.of(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "--class-path",
                                        root.resolve("target/classes").toString(),
                                        probe.toString()))
                        .exit()
                == 0;
    }
}
