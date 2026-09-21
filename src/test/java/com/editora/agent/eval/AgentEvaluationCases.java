package com.editora.agent.eval;

import java.nio.file.*;
import java.util.*;

import com.editora.agent.runtime.AgentSessionStore;
import com.editora.process.ProcessRunner;

/** Expected outcomes and isolated project setup, independent of model text or runtime completion. */
public final class AgentEvaluationCases {
    private static final String MAIN = "src/main/java/demo/", TEST = "src/test/java/demo/LedgerTest.java";

    public record Task(
            String id,
            String category,
            String prompt,
            String active,
            Set<String> allowed,
            Set<String> required,
            boolean readOnly,
            boolean humanReview,
            boolean semanticExpected,
            int callBudget) {}

    public static List<Task> tasks() {
        return List.of(
                new Task(
                        "editora-save-understanding",
                        "understanding",
                        "Explain how Editora prevents an older asynchronous save from overwriting a newer save. Trace callers, the write sequencer and atomic commit boundary. Cite relevant files and distinguish queued writes from already-running writes. Do not edit files.",
                        "src/main/java/com/editora/io/DocumentWriteSequencer.java",
                        Set.of(),
                        Set.of(),
                        true,
                        true,
                        true,
                        24),
                new Task(
                        "editora-endpoint-bug",
                        "bug",
                        "Fix the regression where an LM Studio API-base URL with a trailing slash fails to normalize to the chat completions endpoint. Preserve other endpoint behavior, inspect tests and validate the fix.",
                        "src/main/java/com/editora/ai/AiEndpoints.java",
                        Set.of(
                                "src/main/java/com/editora/ai/AiEndpoints.java",
                                "src/test/java/com/editora/ai/AiEndpointsTest.java"),
                        Set.of("src/main/java/com/editora/ai/AiEndpoints.java"),
                        false,
                        false,
                        true,
                        28),
                new Task(
                        "ledger-bug",
                        "bug",
                        "Invoices undercharge when a line has a quantity greater than one. Fix the cause, inspect callers and regression coverage, and validate the change.",
                        MAIN + "Invoice.java",
                        Set.of(MAIN + "Ledger.java", MAIN + "Invoice.java", TEST),
                        Set.of(MAIN + "Ledger.java"),
                        false,
                        false,
                        true,
                        32),
                new Task(
                        "ledger-refactor",
                        "refactor",
                        "Rename Ledger.totalFor to invoiceTotal and migrate all callers and tests without changing billing behavior. Use semantic references and a rename preview where available. Validate the resulting project.",
                        MAIN + "Ledger.java",
                        Set.of(MAIN + "Ledger.java", MAIN + "Invoice.java", TEST),
                        Set.of(MAIN + "Ledger.java", MAIN + "Invoice.java", TEST),
                        false,
                        false,
                        true,
                        36),
                new Task(
                        "ledger-feature",
                        "feature",
                        "Add Invoice.itemCount(List<Line>) returning the total quantity across invoice lines as a long, with a Ledger.itemCount(List<Line>) implementation behind it. Preserve total pricing, cover empty and multi-line invoices with tests, and validate.",
                        MAIN + "Invoice.java",
                        Set.of(MAIN + "Ledger.java", MAIN + "Invoice.java", TEST),
                        Set.of(MAIN + "Ledger.java", MAIN + "Invoice.java"),
                        false,
                        false,
                        true,
                        36),
                new Task(
                        "ledger-tests",
                        "testing",
                        "Improve regression coverage for quantity multiplication and empty invoices. Existing tests only cover a quantity of one. Add meaningful tests without changing production behavior, then run them.",
                        TEST,
                        Set.of(TEST),
                        Set.of(),
                        false,
                        false,
                        false,
                        28),
                new Task(
                        "ledger-docs",
                        "maintenance",
                        "Correct README.md so its billing units and quantity behavior agree with the implementation. Inspect code and tests, update only the documentation, and verify consistency.",
                        "README.md",
                        Set.of("README.md"),
                        Set.of("README.md"),
                        false,
                        true,
                        false,
                        24));
    }

    public static boolean testSource(Task task, String path) {
        String prefix = task.id().startsWith("editora-") ? "src/test/java/com/editora/ai/" : "src/test/java/demo/";
        return Set.of("bug", "feature", "refactor", "testing").contains(task.category())
                && path.startsWith(prefix)
                && path.endsWith(".java")
                && !path.contains("/../");
    }

    public static Set<String> allowedChanges(Task task, Set<String> changed) {
        var allowed = new TreeSet<>(task.allowed());
        changed.stream().filter(path -> testSource(task, path)).forEach(allowed::add);
        return allowed;
    }

    public static boolean requiredTestsChanged(Task task, Set<String> changed) {
        return !Set.of("feature", "testing").contains(task.category())
                || changed.stream().anyMatch(path -> testSource(task, path));
    }

    public static void prepare(Task task, Path source, Path root) throws Exception {
        Files.createDirectories(root);
        if (task.id().equals("editora-save-understanding")) {
            // A real repository snapshot, including current uncommitted source, without user state or build output.
            for (String tree : List.of("src", "docs")) copyTree(source.resolve(tree), root.resolve(tree));
            for (String file : List.of("pom.xml", "README.md", "AGENTS.md", "TODO.md", ".gitignore"))
                Files.copy(source.resolve(file), root.resolve(file));
            return;
        }
        write(root, "pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><groupId>evaluation</groupId><artifactId>coding-task</artifactId><version>1</version>
                <properties><maven.compiler.release>25</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.1.3</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.6.0</version></plugin></plugins></build></project>
                """);
        write(
                root,
                "AGENTS.md",
                "Use the existing Java conventions. Keep edits focused. Run mvn test for validation. Do not change build configuration to hide test failures.\n");
        if (task.id().startsWith("editora-")) {
            for (String name : List.of("AiEndpoints.java", "AiProvider.java"))
                write(
                        root,
                        "src/main/java/com/editora/ai/" + name,
                        Files.readString(source.resolve("src/main/java/com/editora/ai/" + name)));
            write(
                    root,
                    "src/test/java/com/editora/ai/AiEndpointsTest.java",
                    Files.readString(source.resolve("src/test/java/com/editora/ai/AiEndpointsTest.java")));
            Path file = root.resolve("src/main/java/com/editora/ai/AiEndpoints.java");
            String original = Files.readString(file),
                    seeded =
                            original.replace(
                                    "path.endsWith(\"/v1\") || path.endsWith(\"/v1/\")", "path.endsWith(\"/v1\")");
            if (original.equals(seeded))
                throw new IllegalStateException("Endpoint seed no longer matches; update the benchmark explicitly");
            Files.writeString(file, seeded);
            return;
        }
        write(
                root,
                MAIN + "Line.java",
                "package demo; public record Line(long unitCents, int quantity) { public Line { if(unitCents < 0 || quantity < 0) throw new IllegalArgumentException(); } }\n");
        write(root, MAIN + "Ledger.java", """
                package demo;
                import java.util.List;
                public class Ledger {
                    public long totalFor(List<Line> lines) {
                        long total = 0;
                        for (Line line : lines) total = Math.addExact(total, Math.multiplyExact(line.unitCents(), line.quantity()));
                        return total;
                    }
                }
                """);
        write(root, MAIN + "Invoice.java", """
                package demo;
                import java.util.List;
                public class Invoice {
                    private final Ledger ledger = new Ledger();
                    public long total(List<Line> lines) { return ledger.totalFor(lines); }
                }
                """);
        write(root, TEST, """
                package demo;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class LedgerTest {
                    @Test void oneLine() { assertEquals(125, new Ledger().totalFor(List.of(new Line(125, 1)))); }
                }
                """);
        write(
                root,
                "README.md",
                "# Billing\nThe invoice service returns whole dollars. Each line contributes its unit price regardless of quantity.\n");
        if (task.id().equals("ledger-bug")) {
            Path file = root.resolve(MAIN + "Ledger.java");
            Files.writeString(
                    file,
                    Files.readString(file)
                            .replace("Math.multiplyExact(line.unitCents(), line.quantity())", "line.unitCents()"));
            Path test = root.resolve(TEST);
            Files.writeString(
                    test,
                    Files.readString(test)
                            .replace(
                                    "class LedgerTest {",
                                    "class LedgerTest {\n @Test void multipleItems() { assertEquals(250, new Invoice().total(List.of(new Line(125, 2)))); }"));
        }
    }

    public static Map<String, String> snapshot(Path root) throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(
                            p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p))
                    .toList()) {
                String relative = root.relativize(p).toString().replace('\\', '/');
                if (relative.startsWith("target/") || relative.startsWith(".git/")) continue;
                hashes.put(
                        relative,
                        Files.isSymbolicLink(p)
                                ? "SYMLINK:" + Files.readSymbolicLink(p)
                                : AgentSessionStore.hash(Files.readAllBytes(p)));
            }
        }
        return hashes;
    }

    public static Set<String> changed(Map<String, String> before, Map<String, String> after) {
        var changed = new TreeSet<String>();
        changed.addAll(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(p -> Objects.equals(before.get(p), after.get(p)));
        return changed;
    }

    public static boolean oracle(Task task, Path root, Path outside) throws Exception {
        if (task.readOnly()) return true; // Explanation correctness explicitly requires independent human review.
        if (!test(root)) return false;
        if (task.id().equals("ledger-docs")) return true; // Documentation semantics also require human review.
        if (task.id().equals("ledger-tests")) {
            Path file = root.resolve(MAIN + "Ledger.java");
            String original = Files.readString(file);
            try {
                Files.writeString(
                        file,
                        original.replace("Math.multiplyExact(line.unitCents(), line.quantity())", "line.unitCents()"));
                return !test(root);
            } finally {
                Files.writeString(file, original);
            }
        }
        Files.createDirectories(outside);
        String body = task.id().equals("editora-endpoint-bug")
                ? """
                import com.editora.ai.*;
                class Oracle { public static void main(String[] args) {
                    for(String url : new String[]{"http://localhost:1234", "http://localhost:1234/", "http://localhost:1234/v1", " http://localhost:1234/v1/ "})
                        if(!AiEndpoints.resolve(AiProvider.LMSTUDIO,url).equals("http://localhost:1234/v1/chat/completions"))throw new AssertionError();
                    if(!AiEndpoints.resolve(AiProvider.OPENAI,"http://localhost/v1").equals("http://localhost/v1"))throw new AssertionError();
                    if(!AiEndpoints.isCleartextRemote("http://example.com"))throw new AssertionError();
                }}
                """
                : """
                import demo.*; import java.util.*;
                class Oracle { public static void main(String[] args) throws Exception {
                    Invoice invoice=new Invoice();
                    if(invoice.total(List.of(new Line(125,3),new Line(250,2))) != 875 || invoice.total(List.of())!=0)throw new AssertionError();
                EXTRA
                }}
                """.replace(
                                "EXTRA",
                                task.id().equals("ledger-refactor")
                                        ? "if((long)Ledger.class.getMethod(\"invoiceTotal\",List.class).invoke(new Ledger(),List.of(new Line(4,3)))!=12)throw new AssertionError(); try{Ledger.class.getMethod(\"totalFor\",List.class);throw new AssertionError();}catch(NoSuchMethodException expected){}"
                                        : task.id().equals("ledger-feature")
                                                ? "if((long)Invoice.class.getMethod(\"itemCount\",List.class).invoke(invoice,List.of(new Line(125,3),new Line(250,2)))!=5 || (long)Ledger.class.getMethod(\"itemCount\",List.class).invoke(new Ledger(),List.of())!=0)throw new AssertionError();"
                                                : "");
        Path oracle = outside.resolve("Oracle.java");
        Files.writeString(oracle, body);
        var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path",
                root.resolve("target/classes").toString(),
                oracle.toString());
        return ProcessRunner.runRestricted(outside, java.time.Duration.ofSeconds(30), command)
                        .exit()
                == 0;
    }

    public static boolean test(Path root) {
        return ProcessRunner.runRestricted(root, java.time.Duration.ofSeconds(120), List.of("mvn", "-q", "-o", "test"))
                        .exit()
                == 0;
    }

    private static void write(Path root, String name, String content) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path dest = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) Files.copy(p, dest);
            }
        }
    }
}
