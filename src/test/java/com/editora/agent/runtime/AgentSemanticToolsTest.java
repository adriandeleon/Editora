package com.editora.agent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentSemanticToolsTest {
    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();

    private final class Documents implements AgentDocuments {
        Snapshot current = new Snapshot(root.resolve("A.java"), "v1", "class A {}", false);

        public Snapshot read(Path p, AgentCancellation c) {
            c.check();
            return current;
        }

        public List<Snapshot> open(AgentCancellation c) {
            return List.of(current);
        }

        public List<Snapshot> apply(List<Edit> edits, AgentCancellation c) {
            for (var edit : edits)
                if (!edit.revision().equals(current.revision())) throw new IllegalStateException("stale");
            current = new Snapshot(current.path(), "v2", edits.getFirst().newText(), true);
            return List.of(current);
        }

        public Snapshot create(Path p, String text, AgentCancellation c) {
            throw new UnsupportedOperationException();
        }

        public void save(List<Snapshot> s, AgentCancellation c) {}

        public Diagnostics diagnostics(Path p, AgentCancellation c) {
            return new Diagnostics(false, 0, "");
        }

        public boolean saved(Snapshot s, AgentCancellation c) {
            return false;
        }

        public void showDiff(Path p, String a, String b, AgentCancellation c) {}
    }

    private com.fasterxml.jackson.databind.JsonNode edits(String uri) throws Exception {
        var edit = json.readTree(
                "{\"edits\":[{\"edits\":[{\"range\":{\"start\":{\"line\":0,\"character\":6},\"end\":{\"line\":0,\"character\":7}},\"newText\":\"B\"}]}]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) edit.path("edits").get(0)).put("uri", uri);
        return edit;
    }

    @Test
    void previewIsReadOnlyAndCommitRejectsNewerUserRevision() throws Exception {
        Documents docs = new Documents();
        AgentTools registry = new AgentTools();
        AtomicInteger committed = new AtomicInteger();
        AgentSemantics semantic = new AgentSemantics() {
            public com.fasterxml.jackson.databind.JsonNode capabilities(
                    AgentDocuments.Snapshot s, AgentCancellation c) {
                return json.createObjectNode();
            }

            public com.fasterxml.jackson.databind.JsonNode request(
                    String op,
                    com.fasterxml.jackson.databind.JsonNode args,
                    AgentDocuments.Snapshot s,
                    List<AgentDocuments.Snapshot> pre,
                    AgentCancellation c)
                    throws Exception {
                return edits(s.path().toUri().toString());
            }
        };
        new AgentSemanticTools(new AgentWorkspace(root), docs, semantic, (e, c) -> {
                    docs.apply(e, c);
                    committed.incrementAndGet();
                    return new AgentTool.Result("done", false, true);
                })
                .register(registry);
        var args =
                json.readTree("{\"path\":\"A.java\",\"revision\":\"v1\",\"operation\":\"rename\",\"new_name\":\"B\"}");
        var result = registry.get("semantic_prepare").handler().execute(args, new AgentCancellation());
        String token = json.readTree(result.text()).get("proposal").asText();
        assertEquals(0, committed.get());
        docs.current = new AgentDocuments.Snapshot(docs.current.path(), "user-v2", "class A { int user; }", true);
        var apply = json.createObjectNode().put("proposal", token);
        assertThrows(
                IllegalStateException.class,
                () -> registry.get("semantic_apply").handler().execute(apply, new AgentCancellation()));
        assertEquals(0, committed.get());
        assertTrue(docs.current.text().contains("user"));
        assertTrue(new AgentPolicy()
                .requiresApproval(registry.get("semantic_apply").spec()));
    }

    @Test
    void workspaceEditRequiresRequestTimePreimagesAndRejectsOverlap() throws Exception {
        Documents docs = new Documents();
        var workspace = new AgentWorkspace(root);
        var changes = edits(docs.current.path().toUri().toString()).path("edits");
        var applied = AgentSemanticTools.translate(changes, Map.of(docs.current.path(), docs.current), workspace);
        assertEquals("class B {}", applied.getFirst().newText());
        assertThrows(IllegalStateException.class, () -> AgentSemanticTools.translate(changes, Map.of(), workspace));
        var list =
                (com.fasterxml.jackson.databind.node.ArrayNode) changes.get(0).path("edits");
        list.add(list.get(0).deepCopy());
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentSemanticTools.translate(changes, Map.of(docs.current.path(), docs.current), workspace));
    }

    @Test
    void rangeCoordinatesRejectClampingAndSurrogateSplits() {
        assertEquals(4, AgentSemanticTools.offset("a\r\nb", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> AgentSemanticTools.offset("abc", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> AgentSemanticTools.offset("abc", 0, 4));
        assertThrows(IllegalArgumentException.class, () -> AgentSemanticTools.offset("😀", 0, 1));
    }
}
