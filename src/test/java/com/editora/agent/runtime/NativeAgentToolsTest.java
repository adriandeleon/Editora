package com.editora.agent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class NativeAgentToolsTest {
    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();

    private class Documents implements AgentDocuments {
        final Map<Path, Snapshot> snapshots = new LinkedHashMap<>();
        int errors;
        int applied;
        String freshness = "UNKNOWN";

        public Snapshot read(Path p, AgentCancellation c) {
            return snapshots.get(p);
        }

        public List<Snapshot> open(AgentCancellation c) {
            return List.copyOf(snapshots.values());
        }

        public List<Snapshot> apply(List<Edit> edits, AgentCancellation c) {
            for (var edit : edits) {
                if (!snapshots.get(edit.path()).revision().equals(edit.revision())) {
                    throw new IllegalArgumentException("stale");
                }
            }
            applied++;
            return edits.stream()
                    .map(e -> {
                        var snapshot = new Snapshot(
                                e.path(),
                                "new-" + applied,
                                AgentDocuments.replacement(
                                        snapshots.get(e.path()).text(), e),
                                true);
                        snapshots.put(e.path(), snapshot);
                        return snapshot;
                    })
                    .toList();
        }

        public Snapshot create(Path p, String t, AgentCancellation c) {
            throw new UnsupportedOperationException();
        }

        public void save(List<Snapshot> saved, AgentCancellation c) throws Exception {
            for (var s : saved) {
                Files.writeString(s.path(), s.text());
                snapshots.put(s.path(), new Snapshot(s.path(), s.revision(), s.text(), false));
            }
        }

        public Diagnostics diagnostics(Path p, AgentCancellation c) {
            return new Diagnostics(true, errors, "diagnostic evidence", freshness, 1, null);
        }

        public boolean saved(Snapshot s, AgentCancellation c) throws Exception {
            return Files.readString(s.path()).equals(s.text());
        }

        public void showDiff(Path p, String before, String after, AgentCancellation c) {}
    }

    private AgentTool.Result invoke(AgentTools tools, String name, String arguments) throws Exception {
        var tool = tools.get(name);
        var args = json.readTree(arguments);
        AgentTools.validate(args, tool.spec().inputSchema());
        return tool.handler().execute(args, new AgentCancellation());
    }

    @Test
    void explicitTargetedOnlyRequestRejectsBroadValidationBeforeExecution() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var executions = new AtomicInteger();
        var natives = new NativeAgentTools(new AgentWorkspace(root), new Documents(), p -> {}, (cwd, argv) -> {
            executions.incrementAndGet();
            try {
                var reports = Files.createDirectories(root.resolve("target/surefire-reports"));
                Files.writeString(
                        reports.resolve("TEST-demo.FooTest.xml"),
                        "<testsuite><testcase classname='demo.FooTest' name='passes'/></testsuite>");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return new com.editora.process.ProcessRunner.Result(0, "success", "");
        });
        natives.acceptance().user("Don't run the full suite; just run FooTest.");
        var tools = natives.registry();
        assertTrue(invoke(tools, "run_validation", "{\"type\":\"TEST\",\"isolation\":\"HOST_REDUCED\"}")
                .error());
        assertTrue(invoke(tools, "run_command", "{\"argv\":[\"mvn\",\"verify\"],\"purpose\":\"validate\"}")
                .error());
        assertEquals(0, executions.get());
        assertFalse(invoke(
                        tools,
                        "run_validation",
                        "{\"type\":\"TARGETED_TEST\",\"test\":\"FooTest\",\"isolation\":\"HOST_REDUCED\"}")
                .error());
        assertEquals(1, executions.get());
        assertTrue(natives.acceptance().finish("done", new AgentCancellation()).accepted());
    }

    @Test
    void structuredValidationNeedsFreshTestsAndCurrentSavedRevisions() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var file = Files.writeString(root.resolve("Source.java"), "before");
        var docs = new Documents();
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "before", false));
        var run = new AtomicInteger();
        var natives = new NativeAgentTools(new AgentWorkspace(root), docs, p -> {}, (cwd, argv) -> {
            int n = run.incrementAndGet();
            try {
                if (n > 1) {
                    var reports = Files.createDirectories(root.resolve("target/surefire-reports"));
                    Files.writeString(
                            reports.resolve("TEST-Check" + n + ".xml"),
                            "<testsuite><testcase name='valid'/>"
                                    + (n == 2 ? "<testcase name='bad'><failure message='fix this'/></testcase>" : "")
                                    + "</testsuite>");
                }
                if (n == 4)
                    docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "user-edit", "newer user text", true));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new com.editora.process.ProcessRunner.Result(0, "success", "");
        });
        var tools = natives.registry();
        invoke(
                tools,
                "apply_edits",
                "{\"edits\":[{\"path\":\"Source.java\",\"revision\":\"v1\",\"old_text\":\"before\",\"new_text\":\"after\"}]}");
        String args = "{\"type\":\"TEST\",\"isolation\":\"HOST_REDUCED\"}";
        assertTrue(invoke(tools, "run_validation", args).error());
        assertEquals(0, run.get(), "dirty buffers must prevent execution");
        invoke(tools, "save_files", "{}");
        assertTrue(
                invoke(tools, "run_validation", args).error(), "exit zero without fresh reports is not test evidence");
        assertTrue(invoke(tools, "run_validation", args).error(), "fresh report failure defeats an exit-zero process");
        assertFalse(natives.verify(new AgentCancellation()).passed());
        var repaired = invoke(tools, "run_validation", args);
        assertFalse(repaired.error());
        var evidence = json.readTree(repaired.text());
        assertEquals(
                "new-1", evidence.path("savedRevisions").get(0).path("revision").asText());
        assertTrue(natives.verify(new AgentCancellation()).passed());
        assertTrue(invoke(tools, "run_validation", args).error(), "concurrent user edit invalidates validation");
        assertFalse(natives.verify(new AgentCancellation()).passed());
    }

    @Test
    void searchOutputRetainsValidJsonWithinTheRuntimeObservationBudget() throws Exception {
        Files.writeString(root.resolve("Many.java"), ("needle " + "x".repeat(300) + "\n").repeat(120));
        var tools = new NativeAgentTools(new AgentWorkspace(root), new Documents(), p -> {}).registry();
        String output = invoke(tools, "search_text", "{\"query\":\"needle\"}").text();
        assertTrue(output.length() < 6500);
        var parsed = json.readTree(AgentContext.bounded(output, 8000));
        assertTrue(parsed.path("truncated").asBoolean());
        assertTrue(parsed.path("matches").size() > 0);
    }

    @Test
    void newlyCreatedTestsRankAboveMerelyInspectedContext() throws Exception {
        var docs = new Documents() {
            @Override
            public Snapshot create(Path p, String text, AgentCancellation c) {
                var snapshot = new Snapshot(p, "created", text, true);
                snapshots.put(p, snapshot);
                return snapshot;
            }
        };
        var natives = new NativeAgentTools(new AgentWorkspace(root), docs, p -> {});
        natives.contextIndex().note("Other.java", AgentContextRanker.Signal.AGENT_INSPECTED);
        invoke(
                natives.registry(),
                "create_file",
                "{\"path\":\"RegressionTest.java\",\"text\":\"class RegressionTest {}\"}");
        assertEquals(
                "RegressionTest.java",
                AgentContextRanker.rank(natives.contextIndex().candidates(), 1)
                        .getFirst()
                        .path());
    }

    @Test
    void changeReviewIdentifiesRelativeTargetsAndSubsequentEdits() throws Exception {
        var docs = new Documents();
        var file = Files.writeString(root.resolve("Code.java"), "before");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "before", false));
        var tools = new NativeAgentTools(new AgentWorkspace(root), docs, p -> {}).registry();
        invoke(
                tools,
                "apply_edits",
                "{\"edits\":[{\"path\":\"Code.java\",\"revision\":\"v1\",\"old_text\":\"before\",\"new_text\":\"agent\"}]}");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "user-v3", "user", true));
        String review = invoke(tools, "review_changes", "{}").text();
        assertTrue(review.contains("Code.java [unsaved] [changed since agent edit; includes subsequent edits]"));
        assertTrue(review.contains("+user"));
        assertEquals("before", Files.readString(file));
    }

    @Test
    void filenameDiscoveryFindsNestedTestsWithoutReadingContentAndRespectsBoundaries() throws Exception {
        Files.createDirectories(root.resolve("src/test/java/pkg"));
        Files.writeString(root.resolve("src/test/java/pkg/FooTest.java"), "do not open me");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/FooTest.java"), "ignored");
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        Files.writeString(root.resolve("FooTest.key"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("FooTestLink.java"), root.resolve("src/test/java/pkg/FooTest.java"));
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            // Some Windows test users cannot create links; still exercise filename and credential filtering.
        }
        var tools = new NativeAgentTools(new AgentWorkspace(root), new Documents(), p -> {}).registry();
        var result = json.readTree(
                invoke(tools, "find_files", "{\"query\":\"footest\"}").text());
        assertEquals(1, result.path("paths").size());
        assertEquals(
                "src/test/java/pkg/FooTest.java", result.path("paths").get(0).asText());
        assertFalse(result.path("truncated").asBoolean());
        assertFalse(result.toString().contains("do not open"));
        assertThrows(
                java.io.IOException.class, () -> invoke(tools, "find_files", "{\"query\":\"foo\",\"path\":\"..\"}"));
        var cancelled = new AgentCancellation();
        cancelled.cancel();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> tools.get("find_files").handler().execute(json.readTree("{\"query\":\"foo\"}"), cancelled));
    }

    @Test
    void completeRuntimeLifecycleReadsEditsSavesValidatesAndRechecksBeforeCompletion() throws Exception {
        var docs = new Documents();
        var file = Files.writeString(root.resolve("Source.java"), "class Source {}\n");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "class Source {}\n", false));
        var nativeTools = new NativeAgentTools(
                new AgentWorkspace(root),
                docs,
                plan -> {},
                (cwd, argv) -> new com.editora.process.ProcessRunner.Result(0, "compiled", ""));
        List<AgentModel.Response> script = List.of(
                response("read-1", "read_file", "{\"path\":\"Source.java\"}"),
                response(
                        "edit-1",
                        "apply_edits",
                        "{\"edits\":[{\"path\":\"Source.java\",\"revision\":\"v1\",\"old_text\":\"class Source {}\",\"new_text\":\"class Source { int value; }\"}]}"),
                response("save-1", "save_files", "{}"),
                response(
                        "validate-1", "run_command", "{\"argv\":[\"javac\",\"Source.java\"],\"purpose\":\"validate\"}"),
                new AgentModel.Response("candidate", List.of(), "stop", 10, 10),
                new AgentModel.Response("complete", List.of(), "stop", 10, 10));
        AtomicInteger request = new AtomicInteger();
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 32_768, 4096);
            }

            public Response respond(Request ignored, AgentCancellation cancellation, Consumer<String> text) {
                return script.get(request.getAndIncrement());
            }
        };
        AgentPolicy policy = new AgentPolicy();
        policy.setTrust(AgentPolicy.Trust.WORKSPACE);
        try (var runtime = new AgentRuntime(
                model,
                nativeTools.registry(),
                policy,
                (spec, arguments, cancellation) -> spec.effect() == AgentTool.Effect.EXTERNAL,
                nativeTools::verify,
                NativeAgentTools.SYSTEM,
                AgentRuntime.Limits.DEFAULT,
                event -> {},
                text -> {})) {
            AgentRuntime.Outcome outcome = runtime.submit("add a field").get(3, TimeUnit.SECONDS);
            assertEquals(AgentRuntime.State.COMPLETED, outcome.state());
            assertEquals("complete", outcome.detail());
            assertEquals("class Source { int value; }\n", Files.readString(file));
            assertEquals(6, request.get());
        }
    }

    private static AgentModel.Response response(String id, String name, String arguments) {
        return new AgentModel.Response("", List.of(new AgentModel.Call(id, name, arguments)), "tool_calls", 10, 10);
    }

    @Test
    void semanticRefactorDiscoversCallersPreviewsCommitsRepairsTestsAndVerifies() throws Exception {
        var workspace = new AgentWorkspace(root);
        var docs = new Documents();
        Files.writeString(root.resolve("AGENTS.md"), "Follow references and validate every rename.");
        for (var entry : Map.of(
                        "Manager.java",
                        "void renameDocument() {}",
                        "Caller.java",
                        "manager.renameDocument();",
                        "Test.java",
                        "expected = oldName;")
                .entrySet()) {
            Path path = Files.writeString(root.resolve(entry.getKey()), entry.getValue());
            docs.snapshots.put(path, new AgentDocuments.Snapshot(path, "v1", entry.getValue(), false));
        }
        AtomicInteger checks = new AtomicInteger();
        var nativeTools = new NativeAgentTools(workspace, docs, p -> {}, (cwd, argv) -> {
            checks.incrementAndGet();
            boolean repaired =
                    docs.snapshots.get(root.resolve("Test.java")).text().contains("newName");
            return new com.editora.process.ProcessRunner.Result(
                    repaired ? 0 : 1, repaired ? "3 tests passed" : "1 test failed: old expectation", "");
        });
        var registry = nativeTools.registry();
        new AgentGuidance(workspace, null).register(registry);
        AgentSemantics semantics = new AgentSemantics() {
            public com.fasterxml.jackson.databind.JsonNode capabilities(
                    AgentDocuments.Snapshot source, AgentCancellation c) {
                return json.createObjectNode()
                        .put("available", true)
                        .set("operations", json.valueToTree(List.of("references", "rename")));
            }

            public com.fasterxml.jackson.databind.JsonNode request(
                    String operation,
                    com.fasterxml.jackson.databind.JsonNode args,
                    AgentDocuments.Snapshot source,
                    List<AgentDocuments.Snapshot> preimages,
                    AgentCancellation c) {
                var out = json.createObjectNode();
                if (operation.equals("references")) {
                    out.putArray("items")
                            .addObject()
                            .put("uri", root.resolve("Caller.java").toUri().toString());
                } else {
                    var changes = out.putArray("edits");
                    for (var snapshot : preimages) {
                        int offset = snapshot.text().indexOf("renameDocument");
                        if (offset < 0) continue;
                        var change = changes.addObject()
                                .put("uri", snapshot.path().toUri().toString());
                        var edit = change.putArray("edits").addObject().put("newText", "renameAndRefresh");
                        var range = edit.putObject("range");
                        range.putObject("start").put("line", 0).put("character", offset);
                        range.putObject("end").put("line", 0).put("character", offset + "renameDocument".length());
                    }
                }
                return out;
            }
        };
        new AgentSemanticTools(workspace, docs, semantics, nativeTools::applySemanticEdits, nativeTools.contextIndex())
                .register(registry);
        new AgentAwareness(c -> json.createObjectNode(), nativeTools.contextIndex()).register(registry);
        AtomicInteger step = new AtomicInteger();
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 65536, 4096);
            }

            public Response respond(Request request, AgentCancellation c, Consumer<String> text) throws Exception {
                int i = step.getAndIncrement();
                return switch (i) {
                    case 0 -> response("s0", "project_instructions", "{\"path\":\"Manager.java\"}");
                    case 1 -> response("s1", "semantic_capabilities", "{\"path\":\"Manager.java\"}");
                    case 2 -> response("s2", "read_file", "{\"path\":\"Manager.java\"}");
                    case 3 ->
                        response(
                                "s3",
                                "semantic_query",
                                "{\"path\":\"Manager.java\",\"revision\":\"v1\",\"operation\":\"references\"}");
                    case 4 -> response("s4", "read_file", "{\"path\":\"Caller.java\"}");
                    case 5 ->
                        response(
                                "s5",
                                "semantic_prepare",
                                "{\"path\":\"Manager.java\",\"revision\":\"v1\",\"operation\":\"rename\",\"new_name\":\"renameAndRefresh\"}");
                    case 6 -> {
                        var result = request.messages().stream()
                                .filter(m -> "s5".equals(m.callId()))
                                .findFirst()
                                .orElseThrow();
                        String proposal =
                                json.readTree(result.text()).path("proposal").asText();
                        assertFalse(proposal.isBlank());
                        assertEquals(0, docs.applied, "preview never mutates");
                        yield response(
                                "s6",
                                "semantic_apply",
                                json.createObjectNode()
                                        .put("proposal", proposal)
                                        .toString());
                    }
                    case 7, 12 -> response("s" + i, "save_files", "{}");
                    case 8, 13 ->
                        response("s" + i, "run_command", "{\"argv\":[\"mvn\",\"test\"],\"purpose\":\"validate\"}");
                    case 9 -> {
                        assertTrue(request.messages().stream()
                                .anyMatch(m -> m.error() && m.text().contains("1 test failed")));
                        yield response("s9", "read_file", "{\"path\":\"Test.java\"}");
                    }
                    case 10 ->
                        response(
                                "s10",
                                "apply_edits",
                                "{\"edits\":[{\"path\":\"Test.java\",\"revision\":\"v1\",\"old_text\":\"oldName\",\"new_text\":\"newName\"}]}");
                    case 11 -> response("s11", "diagnostics", "{\"path\":\"Manager.java\"}");
                    case 14 -> response("s14", "review_changes", "{}");
                    default ->
                        new Response("Validated rename and callers; three tests passed.", List.of(), "stop", 10, 10);
                };
            }
        };
        AgentModelTools.register(registry, model);
        new com.editora.mcp.AgentMcpManager(List.of(), root).register(registry, new AgentCancellation());
        assertTrue(AgentContext.fixedCost(NativeAgentTools.SYSTEM, registry.specs()) + 4096 + 2048
                < com.editora.config.Settings.AGENT_CONTEXT_MIN);
        var policy = new AgentPolicy();
        policy.setTrust(AgentPolicy.Trust.WORKSPACE);
        try (var runtime = new AgentRuntime(
                model,
                registry,
                policy,
                (spec, a, c) -> spec.effect() == AgentTool.Effect.EXTERNAL,
                nativeTools::verify,
                NativeAgentTools.SYSTEM,
                AgentRuntime.Limits.DEFAULT,
                event -> {},
                text -> {})) {
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("Rename the method and callers, update coverage, and validate.")
                            .get(5, TimeUnit.SECONDS)
                            .state());
        }
        assertEquals(2, checks.get());
        assertTrue(Files.readString(root.resolve("Caller.java")).contains("renameAndRefresh"));
        assertTrue(nativeTools.contextIndex().candidates().stream()
                .anyMatch(candidate -> candidate.path().equals("Caller.java")
                        && candidate.signals().contains(AgentContextRanker.Signal.REFERENCE)));
    }

    @Test
    void editSaveBuildAndVerificationTrackExactRevisionsAndDiagnostics() throws Exception {
        var docs = new Documents();
        var file = Files.writeString(root.resolve("Source.java"), "class Source {}\n");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "class Source {}\n", false));
        var nativeTools = new NativeAgentTools(new AgentWorkspace(root), docs, plan -> {});
        var tools = nativeTools.registry();
        assertTrue(
                AgentContext.fixedCost(NativeAgentTools.SYSTEM, tools.specs()) + 4096 + 2048
                        < com.editora.config.Settings.AGENT_CONTEXT_MIN,
                "minimum context must leave room for the model response and at least one user turn");
        assertTrue(
                invoke(tools, "read_file", "{\"path\":\"Source.java\"}").text().contains("v1"));
        assertTrue(invoke(tools, "apply_edits", """
                {"edits":[{"path":"Source.java","revision":"v1","old_text":"class Source {}","new_text":"class Source { int value; }"}]}
                """).changed());
        assertFalse(nativeTools.verify(new AgentCancellation()).passed());
        String javac = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac")
                .toString();
        var command = json.createObjectNode();
        command.putArray("argv").add(javac).add("Source.java");
        command.put("purpose", "validate");
        assertTrue(
                invoke(tools, "run_command", command.toString()).error(), "unsaved text cannot be validated on disk");
        invoke(tools, "save_files", "{}");
        assertFalse(invoke(tools, "run_command", command.toString()).error());
        assertTrue(nativeTools.verify(new AgentCancellation()).passed());
        docs.errors = 1;
        assertFalse(nativeTools.verify(new AgentCancellation()).passed());
        docs.errors = 0;
        assertTrue(nativeTools.verify(new AgentCancellation()).passed());
        docs.freshness = "STALE";
        assertFalse(
                nativeTools.verify(new AgentCancellation()).passed(),
                "even a successful command cannot promote stale diagnostics");
        docs.freshness = "UNKNOWN";
        var inspect = json.createObjectNode();
        inspect.putArray("argv")
                .add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .add("-version");
        inspect.put("purpose", "inspect");
        assertFalse(invoke(tools, "run_command", inspect.toString()).error());
        assertFalse(nativeTools.verify(new AgentCancellation()).passed(), "later execution invalidates prior evidence");
        Files.writeString(file, "external mutation");
        assertFalse(nativeTools.verify(new AgentCancellation()).passed());
    }

    @Test
    void acceptanceSurvivesNativeEditCheckpointWithoutTreatingAPlanAsEvidence() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file = Files.writeString(root.resolve("Source.java"), "before");
        var docs = new Documents();
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "before", false));
        NativeAgentTools.CommandRunner runner =
                (cwd, argv) -> new com.editora.process.ProcessRunner.Result(0, "compiled", "");
        var first = new NativeAgentTools(new AgentWorkspace(root), docs, p -> {}, runner);
        first.acceptance().user("Fix Source.java and validate.");
        var tools = first.registry();
        String id = first.acceptance().contract().requirements().getFirst().id();
        invoke(
                tools,
                "update_plan",
                "{\"steps\":[{\"text\":\"Done\",\"status\":\"completed\",\"requirements\":[\"" + id + "\"]}]}");
        assertFalse(first.acceptance().finish("done", new AgentCancellation()).accepted());
        invoke(
                tools,
                "apply_edits",
                "{\"edits\":[{\"path\":\"Source.java\",\"revision\":\"v1\",\"old_text\":\"before\",\"new_text\":\"after\"}]}");
        invoke(tools, "save_files", "{}");
        String validation = "{\"type\":\"COMPILE\",\"isolation\":\"HOST_REDUCED\"}";
        assertFalse(invoke(tools, "run_validation", validation).error());
        assertTrue(
                first.acceptance().contract().complete(),
                "Structured validation must update the next request's acceptance reminder");
        assertTrue(first.acceptance().finish("done", new AgentCancellation()).accepted());
        var second = new NativeAgentTools(new AgentWorkspace(root), docs, p -> {}, runner);
        second.restoreMemory(first.sessionMemory(), true);
        assertFalse(second.acceptance().finish("done", new AgentCancellation()).accepted());
        tools = second.registry();
        invoke(tools, "read_file", "{\"path\":\"Source.java\"}");
        assertEquals(
                1,
                second.acceptance()
                        .ledger()
                        .current(AgentEvidence.Kind.FILE_CHANGED)
                        .size());
        assertFalse(second.acceptance().finish("done", new AgentCancellation()).accepted());
        assertFalse(invoke(tools, "run_validation", validation).error());
        assertTrue(second.acceptance().finish("done", new AgentCancellation()).accepted());
    }

    @Test
    void resumedEditsNeedFreshReadsAndNewValidationWithoutReusingLeases() throws Exception {
        Path file = Files.writeString(root.resolve("Source.java"), "saved agent edit");
        var docs = new Documents();
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "new-process-identity", "saved agent edit", false));
        var memory = json.createObjectNode();
        var remembered = memory.putArray("files");
        for (int i = 0; i < 128; i++)
            remembered.addObject().put("path", "Inspected" + i + ".java").put("hash", "0".repeat(64));
        remembered
                .addObject()
                .put("path", "Source.java")
                .put("modified", true)
                .put(
                        "hash",
                        AgentSessionStore.hash("saved agent edit".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var nativeTools = new NativeAgentTools(
                new AgentWorkspace(root),
                docs,
                p -> {},
                (cwd, argv) -> new com.editora.process.ProcessRunner.Result(0, "passed", ""));
        nativeTools.restoreMemory(memory, true);
        var registry = nativeTools.registry();
        assertFalse(nativeTools.verify(new AgentCancellation()).passed());
        String command = "{\"argv\":[\"mvn\",\"test\"],\"purpose\":\"validate\"}";
        assertTrue(invoke(registry, "run_command", command).error());
        invoke(registry, "read_file", "{\"path\":\"Source.java\"}");
        assertFalse(nativeTools.verify(new AgentCancellation()).passed(), "a reread is not validation");
        assertFalse(invoke(registry, "run_command", command).error());
        assertTrue(nativeTools.verify(new AgentCancellation()).passed());
        assertTrue(invoke(registry, "review_changes", "{}").text().contains("original diff unavailable"));

        var conflicted = new NativeAgentTools(new AgentWorkspace(root), docs, p -> {});
        conflicted.restoreMemory(memory, true);
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "user-version", "new user content", true));
        invoke(conflicted.registry(), "read_file", "{\"path\":\"Source.java\"}");
        assertFalse(
                conflicted.verify(new AgentCancellation()).passed(),
                "changed checkpoint content must be reconciled explicitly");
        invoke(conflicted.registry(), "save_files", "{}");
        assertEquals(
                "saved agent edit",
                Files.readString(file),
                "resuming must not adopt newer user edits into automatic saves");
    }

    @Test
    void modelCannotPromoteAnIrrelevantSuccessfulCommandToValidation() throws Exception {
        var docs = new Documents();
        var file = Files.writeString(root.resolve("source.txt"), "before");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "before", false));
        var nativeTools = new NativeAgentTools(
                new AgentWorkspace(root),
                docs,
                plan -> {},
                (cwd, argv) -> fail("rejected validation must not execute"));
        var tools = nativeTools.registry();
        invoke(tools, "apply_edits", """
                {"edits":[{"path":"source.txt","revision":"v1","old_text":"before","new_text":"after"}]}
                """);
        invoke(tools, "save_files", "{}");
        var command = json.createObjectNode();
        command.putArray("argv")
                .add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .add("-version");
        command.put("purpose", "validate");
        AgentTool.Result result = invoke(tools, "run_command", command.toString());
        assertTrue(result.error());
        assertTrue(result.text().contains("no recognized build"));
        assertFalse(nativeTools.verify(new AgentCancellation()).passed());
        assertNull(NativeAgentTools.validationKind(List.of("mvn", "--version")));
        assertNull(NativeAgentTools.validationKind(List.of("npm", "run", "deploy", "test")));
        assertNull(NativeAgentTools.validationKind(List.of("make", "clean")));
        assertNull(NativeAgentTools.validationKind(List.of("cmake", "--build", ".", "--target", "clean")));
        assertEquals("Maven lifecycle/check", NativeAgentTools.validationKind(List.of("./mvnw", "verify")));
    }

    @Test
    void userEditDuringValidationInvalidatesSuccessfulCommand() throws Exception {
        var docs = new Documents();
        var file = Files.writeString(root.resolve("Source.java"), "class Source {}\n");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "class Source {}\n", false));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var nativeTools = new NativeAgentTools(new AgentWorkspace(root), docs, plan -> {}, (cwd, argv) -> {
            running.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("validation not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return new com.editora.process.ProcessRunner.Result(0, "passed", "");
        });
        var tools = nativeTools.registry();
        invoke(tools, "apply_edits", """
                {"edits":[{"path":"Source.java","revision":"v1","old_text":"class Source {}","new_text":"class Source { int value; }"}]}
                """);
        invoke(tools, "save_files", "{}");
        var command = json.createObjectNode();
        command.putArray("argv").add("javac").add("Source.java");
        command.put("purpose", "validate");
        try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = worker.submit(() -> invoke(tools, "run_command", command.toString()));
            assertTrue(running.await(2, TimeUnit.SECONDS));
            docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "user-v3", "user text", true));
            release.countDown();
            AgentTool.Result result = future.get(2, TimeUnit.SECONDS);
            assertTrue(result.error());
            assertTrue(result.text().contains("changed during command"));
            assertFalse(nativeTools.verify(new AgentCancellation()).passed());
        } finally {
            release.countDown();
        }
    }

    @Test
    void partialEditorFailureIsRecoveredIntoReviewableSessionState() throws Exception {
        var file = Files.writeString(root.resolve("one.txt"), "before");
        var docs = new Documents() {
            @Override
            public List<Snapshot> apply(List<Edit> edits, AgentCancellation cancellation) {
                Edit edit = edits.getFirst();
                snapshots.put(edit.path(), new Snapshot(edit.path(), "partial-v2", "partial", true));
                throw new IllegalStateException("editor listener failed");
            }
        };
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "before", false));
        var tools = new NativeAgentTools(new AgentWorkspace(root), docs, plan -> {}).registry();
        assertThrows(IllegalStateException.class, () -> invoke(tools, "apply_edits", """
                        {"edits":[{"path":"one.txt","revision":"v1","old_text":"before","new_text":"after"}]}
                        """));
        String review = invoke(tools, "review_changes", "{}").text();
        assertTrue(review.contains("one.txt [unsaved] +1 -1"));
        assertTrue(review.contains("+partial"));
    }

    @Test
    void processTimeoutIsReportedAsUncertainMutation() throws Exception {
        var docs = new Documents();
        var tools = new NativeAgentTools(
                        new AgentWorkspace(root),
                        docs,
                        plan -> {},
                        (cwd, argv) -> new com.editora.process.ProcessRunner.Result(-1, "", "command timed out"))
                .registry();
        assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> invoke(tools, "run_command", "{\"argv\":[\"javac\",\"Source.java\"],\"purpose\":\"validate\"}"));
    }

    @Test
    void searchUsesUnsavedTextAndExcludesSecretAndSymlinkTargets() throws Exception {
        var docs = new Documents();
        var file = Files.writeString(root.resolve("source.txt"), "saved text");
        docs.snapshots.put(file, new AgentDocuments.Snapshot(file, "v1", "needle draft", true));
        Files.writeString(root.resolve(".env"), "needle secret");
        var tools = new NativeAgentTools(new AgentWorkspace(root), docs, plan -> {}).registry();
        String found = invoke(tools, "search_text", "{\"query\":\"needle\"}").text();
        assertTrue(found.contains("needle draft"));
        assertFalse(found.contains("secret"));
        assertEquals("[]", invoke(tools, "get_plan", "{}").text());
        invoke(tools, "update_plan", "{\"steps\":[{\"text\":\"Review code\",\"status\":\"in_progress\"}]}");
        assertTrue(invoke(tools, "get_plan", "{}").text().contains("Review code"));
    }

    @Test
    void policyCannotBeRaisedByModelArgumentsAndSpecsAreDefensive() throws Exception {
        var tools = new NativeAgentTools(new AgentWorkspace(root), new Documents(), plan -> {}).registry();
        var policy = new AgentPolicy();
        assertFalse(policy.requiresApproval(tools.get("read_file").spec()));
        assertTrue(policy.requiresApproval(tools.get("apply_edits").spec()));
        for (var trust : AgentPolicy.Trust.values()) {
            policy.setTrust(trust);
            assertTrue(policy.requiresApproval(tools.get("run_command").spec()));
        }
        assertFalse(policy.requiresApproval(tools.get("apply_edits").spec()));
        assertThrows(
                IllegalArgumentException.class,
                () -> invoke(tools, "read_file", "{\"path\":\"x\",\"permission\":\"allow\"}"));
        var schema = tools.get("read_file").spec().inputSchema();
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema).removeAll();
        assertTrue(tools.get("read_file").spec().inputSchema().has("type"));
    }
}
