package com.editora.lsp;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in contract test for the client-driven jdtls flows: the accessor and delegate-method prompts
 * ({@link JdtlsGenerate}) and Move / Extract Interface / Change Signature ({@link JdtlsRefactor}). Every
 * request is assembled by the production helpers and sent to the real server through a production session.
 */
@Tag("probe")
class JdtlsRefactorProbeTest {
    private static String jdtls() {
        for (String candidate : List.of(
                System.getProperty("user.home") + "/.editora/plugins/lsp/java/bin/jdtls",
                System.getProperty("user.home") + "/.editora-dev/plugins/lsp/java/bin/jdtls",
                "/opt/homebrew/bin/jdtls",
                "/usr/local/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        return "";
    }

    static final String APP = """
            package demo;
            import java.util.ArrayList;
            import java.util.List;
            import demo.other.Helper;
            public class App {
                private int count;
                private String name;
                private final List<String> items = new ArrayList<>();
                public static int twice(int n) { return n * 2; }
                public String greet(Helper helper, int n) { return helper.tag() + name.repeat(n); }
                public void run() { System.out.println(greet(new Helper(), twice(2))); }
                static class Inner { int x; }
            }
            """;
    static final String HELPER = """
            package demo.other;
            public class Helper {
                public String tag() { return "#"; }
            }
            """;

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(60, TimeUnit.SECONDS);
    }

    private static Position pos(String text, String needle, int offset) {
        int at = text.indexOf(needle) + offset;
        String pre = text.substring(0, at);
        return new Position((int) pre.chars().filter(c -> c == '\n').count(), at - pre.lastIndexOf('\n') - 1);
    }

    private static void record(String name, Object value) {
        System.out.println("EVALUATION " + name + " = " + value);
    }

    /** The refactorings that ask the user something first; each has its own section below. */
    private static final Set<String> INTERACTIVE = Set.of(
            JdtlsRefactor.MOVE_FILE,
            JdtlsRefactor.MOVE_INSTANCE_METHOD,
            JdtlsRefactor.MOVE_STATIC_MEMBER,
            JdtlsRefactor.MOVE_TYPE,
            JdtlsRefactor.EXTRACT_INTERFACE,
            JdtlsRefactor.CHANGE_SIGNATURE);

    private LanguageServerSession s;
    private String uri;
    private final com.google.gson.Gson gson = LanguageServerSession.LSP_GSON;

    private com.google.gson.JsonElement ask(String method, Object params) throws Exception {
        return LspManager.asJson(get(s.rawRequest(method, params)));
    }

    /** The code action at {@code needle} whose command is {@code command} (and, if given, first argument). */
    private CodeAction action(String needle, String command, String firstArgument) throws Exception {
        Position p = pos(APP, needle, 1);
        for (var a : get(s.codeAction(uri, new Range(p, p), List.of()))) {
            if (!a.isRight() || a.getRight().getCommand() == null) continue;
            Command c = a.getRight().getCommand();
            if (!command.equals(c.getCommand())) continue;
            var args = LspManager.commandArguments(a.getRight());
            if (firstArgument == null
                    || (args.get(0).isJsonPrimitive()
                            && firstArgument.equals(args.get(0).getAsString()))) {
                return a.getRight();
            }
        }
        return fail("no " + command + " " + firstArgument + " action at " + needle);
    }

    private JdtlsRefactor.Request refactoring(String needle, String name) throws Exception {
        CodeAction a = action(needle, JdtlsRefactor.COMMAND, name);
        var request = JdtlsRefactor.parse(a.getCommand().getCommand(), LspManager.commandArguments(a));
        assertNotNull(request, name);
        return request;
    }

    /** The edit in an answer, decoded the way production decodes it. */
    private WorkspaceEdit edit(String what, com.google.gson.JsonElement answer) {
        assertNull(JdtlsRefactor.errorMessage(answer), what);
        var json = JdtlsRefactor.editOf(answer);
        assertNotNull(json, what + " must answer with an edit: " + answer);
        WorkspaceEdit edit = gson.fromJson(json, WorkspaceEdit.class);
        assertNotNull(WorkspaceEditMapper.map(edit), what + " edit must be mappable");
        record(what, json);
        return edit;
    }

    @Test
    void clientDrivenGenerateAndRefactorFlows() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        Path root = Files.createTempDirectory("editora-refactor-probe-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path file = project.resolve("src/main/java/demo/App.java");
        Path helper = project.resolve("src/main/java/demo/other/Helper.java");
        Files.createDirectories(file.getParent());
        Files.createDirectories(helper.getParent());
        Files.writeString(file, APP);
        Files.writeString(helper, HELPER);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>evaluation</artifactId><version>1</version>
                <properties><maven.compiler.release>25</maven.compiler.release></properties></project>
                """);
        uri = file.toUri().toString();
        s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls(), "-data", root.resolve("data").toString()), List.of("pom.xml")),
                project,
                d -> {},
                (type, message) -> {},
                LspManager.javaInitOptions(List.of()));
        try {
            assertTrue(s.start());
            s.didOpen(uri, "java", APP);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
            while (!s.isInitialized() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(s.isInitialized());
            get(s.documentSymbol(uri)); // serializes behind the project import

            // Code lenses: resolved to "N references" for the lines asked about, and no others.
            var lenses = LspManager.codeLensSpans(get(s.codeLens(uri, 0, 9)));
            record("codeLens", lenses);
            assertTrue(
                    lenses.stream().anyMatch(l -> l.line() == 9 && l.title().equals("1 reference")),
                    "greet is called once: " + lenses);
            assertTrue(lenses.stream().allMatch(l -> l.line() <= 9), "run() and Inner are outside the window");
            assertTrue(
                    lenses.stream().allMatch(l -> l.kind() == LspManager.CodeLensKind.REFERENCES),
                    "nothing here has an implementation, and a zero count is not shown");

            // Getters and setters: the prompt's argument carries the accessor kind and is sent back as is.
            var accessors = JdtlsGenerate.Kind.ACCESSORS;
            var accessorParams = LspManager.commandArguments(action("twice(int", accessors.command(), null))
                    .get(0);
            var accessorStatus = ask(accessors.checkRequest(), accessorParams);
            var fields = JdtlsGenerate.candidates(accessors, accessorStatus);
            assertEquals(3, fields.size(), "count, name, items: " + accessorStatus);
            edit(
                    "accessors",
                    ask(
                            accessors.generateRequest(),
                            JdtlsGenerate.generateParams(accessors, accessorParams, fields, accessorStatus)));

            // Delegate methods: a field, then some of its methods.
            var delegates = JdtlsGenerate.Kind.DELEGATE_METHODS;
            var delegateParams = LspManager.commandArguments(action("twice(int", delegates.command(), null))
                    .get(0);
            var delegateFields = JdtlsGenerate.delegateFields(ask(delegates.checkRequest(), delegateParams));
            assertFalse(delegateFields.isEmpty());
            var items = delegateFields.stream()
                    .filter(f -> f.label().startsWith("items"))
                    .findFirst()
                    .orElseThrow();
            edit(
                    "delegates",
                    ask(
                            delegates.generateRequest(),
                            JdtlsGenerate.delegateParams(
                                    delegateParams, items, items.methods().subList(0, 2))));

            // Move the file to another package.
            var moveFile = refactoring("App {", JdtlsRefactor.MOVE_FILE);
            var packages = JdtlsRefactor.packages(
                    ask(
                            "java/getMoveDestinations",
                            JdtlsRefactor.moveParams("moveResource", moveFile.info("uri"), null, null)),
                    false);
            var other = packages.stream()
                    .filter(d -> d.label().equals("demo.other"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    packages.stream().noneMatch(d -> d.label().equals("demo")), "the current package is not offered");
            edit(
                    "moveFile",
                    ask(
                            "java/move",
                            JdtlsRefactor.moveParams("moveResource", moveFile.info("uri"), null, other.raw())));

            // Move an instance method onto one of its parameters.
            var moveMethod = refactoring("greet(Helper", JdtlsRefactor.MOVE_INSTANCE_METHOD);
            var targets = JdtlsRefactor.instanceTargets(ask(
                    "java/getMoveDestinations",
                    JdtlsRefactor.moveParams(
                            "moveInstanceMethod", moveMethod.documentUri(), moveMethod.params(), null)));
            assertEquals("helper : Helper", targets.get(0).label());
            edit(
                    "moveInstanceMethod",
                    ask(
                            "java/move",
                            JdtlsRefactor.moveParams(
                                    "moveInstanceMethod",
                                    moveMethod.documentUri(),
                                    moveMethod.params(),
                                    targets.get(0).raw())));

            // Move a static member into another class.
            var moveStatic = refactoring("twice(int", JdtlsRefactor.MOVE_STATIC_MEMBER);
            var types = JdtlsRefactor.types(
                    ask("java/searchSymbols", JdtlsRefactor.searchTypesParams(moveStatic.info("projectName"))),
                    moveStatic.info("enclosingTypeName"));
            assertTrue(types.stream().noneMatch(d -> d.label().equals("App")), "not into its own class: " + types);
            var helperType = types.stream()
                    .filter(d -> d.label().equals("Helper"))
                    .findFirst()
                    .orElseThrow();
            edit(
                    "moveStaticMember",
                    ask(
                            "java/move",
                            JdtlsRefactor.moveParams(
                                    "moveStaticMember",
                                    moveStatic.documentUri(),
                                    moveStatic.params(),
                                    helperType.raw())));

            // Move a nested type into a file of its own.
            var moveType = refactoring("Inner {", JdtlsRefactor.MOVE_TYPE);
            assertTrue(moveType.supportsDestination("newFile") && moveType.supportsDestination("class"));
            edit(
                    "moveTypeToNewFile",
                    ask(
                            "java/move",
                            JdtlsRefactor.moveParams(
                                    "moveTypeToNewFile", moveType.documentUri(), moveType.params(), null)));

            // The extract refactorings arrive as the same command and need nothing from the user.
            Range expression = new Range(pos(APP, "n * 2", 0), pos(APP, "n * 2", 5));
            Range statement = new Range(pos(APP, "System.out", 0), pos(APP, "twice(2)));", 11));
            Set<String> extracted = new TreeSet<>();
            for (Range range : List.of(expression, statement)) {
                for (var a : get(s.codeAction(uri, range, List.of()))) {
                    if (!a.isRight() || a.getRight().getCommand() == null) continue;
                    var request = JdtlsRefactor.parse(
                            a.getRight().getCommand().getCommand(), LspManager.commandArguments(a.getRight()));
                    if (request == null || INTERACTIVE.contains(request.name())) continue;
                    record("extract info " + request.name(), request.info());
                    edit(
                            request.name(),
                            ask(
                                    "java/getRefactorEdit",
                                    JdtlsRefactor.refactorEditParams(request.name(), request.params(), 4, true, null)));
                    extracted.add(request.name());
                }
            }
            assertTrue(
                    extracted.containsAll(List.of("extractVariable", "extractField", "extractMethod")),
                    extracted.toString());

            // Extract an interface.
            JdtlsRefactor.Request extract = null;
            for (String needle : List.of("App {", "greet(Helper", "count;", "class App", "run()")) {
                try {
                    extract = refactoring(needle, JdtlsRefactor.EXTRACT_INTERFACE);
                    record("extractInterface offered at", needle);
                    break;
                } catch (AssertionError notHere) {
                    // try the next position
                }
            }
            assertNotNull(extract, "Extract Interface must be offered somewhere in the class");
            var status = ask("java/checkExtractInterfaceStatus", extract.params());
            var members = JdtlsRefactor.interfaceMembers(status);
            assertEquals(
                    List.of("greet(Helper, int) : String", "run() : void"),
                    members.stream().map(JdtlsGenerate.Candidate::label).toList());
            assertEquals("App", JdtlsRefactor.subTypeName(status));
            var interfacePackages = JdtlsRefactor.interfacePackages(status);
            assertEquals("demo", interfacePackages.get(0).label(), "the class's own package first");
            edit(
                    "extractInterface",
                    ask(
                            "java/getRefactorEdit",
                            JdtlsRefactor.refactorEditParams(
                                    JdtlsRefactor.EXTRACT_INTERFACE,
                                    extract.params(),
                                    4,
                                    true,
                                    JdtlsRefactor.extractInterfaceArguments(
                                            members, "Greeter", interfacePackages.get(0)))));

            // Change a signature: reorder, rename, add a parameter and an exception.
            var change = refactoring("greet(Helper", JdtlsRefactor.CHANGE_SIGNATURE);
            var info = ask("java/getChangeSignatureInfo", change.params());
            assertEquals("public String greet(Helper helper, int n)", JdtlsRefactor.signatureText(info));
            var arguments = JdtlsRefactor.changeSignatureArguments(
                    info,
                    "protected String hello(int times, Helper helper, String extra = \"x\") throws java.io.IOException");
            assertNotNull(arguments);
            var changed = edit(
                    "changeSignature",
                    ask(
                            "java/getRefactorEdit",
                            JdtlsRefactor.refactorEditParams(
                                    JdtlsRefactor.CHANGE_SIGNATURE, change.params(), 4, true, arguments)));
            String text = gson.toJson(changed);
            assertTrue(
                    text.contains("protected String hello(int times, Helper helper, String extra) throws IOException"),
                    text);
            assertTrue(text.contains("name.repeat(times)"), "a renamed parameter is renamed in the body: " + text);
            assertTrue(
                    text.contains("hello(twice(2), new Helper(), \\\"x\\\""),
                    "arguments follow their parameters, the new one gets its value: " + text);
        } finally {
            s.dispose();
        }
    }
}
