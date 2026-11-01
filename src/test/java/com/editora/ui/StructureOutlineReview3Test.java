package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 3, the outlines group: the heuristic outline the Structure window and the Map's Structure
 * columns show without a language server, on excerpts of real files (Kubernetes, Spring Framework; Apache 2.0) in
 * {@code src/test/resources/com/editora/ui/outline-review3}.
 */
class StructureOutlineReview3Test {

    @BeforeAll
    static void messages() {
        com.editora.i18n.Messages.init("en");
    }

    private static String resource(String name) {
        try (InputStream in = StructureOutlineReview3Test.class.getResourceAsStream("outline-review3/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<StructureNode> outline(String fileName, String text) {
        return StructureOutline.build(
                StructureOutline.Source.file(fileName, text).analyzed());
    }

    /** {@code kind label @line[start-end]} per node, children indented, lines 1-based. */
    private static List<String> dump(List<StructureNode> nodes) {
        List<String> out = new ArrayList<>();
        dump(nodes, "", out);
        return out;
    }

    private static void dump(List<StructureNode> nodes, String indent, List<String> out) {
        for (StructureNode n : nodes) {
            String region = n.region() == null
                    ? ""
                    : "[" + (n.region().startLine() + 1) + "-" + (n.region().endLine() + 1) + "]";
            out.add(indent + n.kind() + " " + n.label() + n.detail() + " @" + (n.line() + 1) + region);
            dump(n.children(), indent + "  ", out);
        }
    }

    private static StructureNode find(List<StructureNode> nodes, String label) {
        for (StructureNode n : nodes) {
            if (n.label().equals(label)) {
                return n;
            }
        }
        throw new AssertionError("no " + label + " in " + dump(nodes));
    }

    // ---- C3 / C15: Go ----

    @Test
    void aGoOutlineListsDeclarationsNotThePackageOrEmbeddedFieldsAsTypes() {
        List<StructureNode> roots = outline("kubelet.go", resource("kubelet-excerpt.go"));
        List<String> all = dump(roots);
        assertFalse(all.stream().anyMatch(s -> s.contains(" kubelet @")), "the package clause is no type: " + all);
        assertFalse(all.stream().anyMatch(s -> s.contains(" lifecycle @") || s.contains(" clock @")), all.toString());
        assertEquals(
                List.of(
                        "constant maxWaitForContainerRuntime @26",
                        "constant nodeStatusUpdateRetry @29",
                        "constant nodeReadyGracePeriod @33",
                        "variable ContainerLogsDir @38",
                        "variable etcHostsPath @39",
                        "interface SyncHandler @43[43-50]",
                        "class Option @53[53-53]",
                        "function NewMainKubelet(…) @56[56-67]",
                        "struct Kubelet @71[71-89]"),
                roots.stream().map(n -> dump(List.of(n)).getFirst()).toList(),
                "constants and variables by name, types, functions; methods under their type");
    }

    @Test
    void goMethodsAreLabelledByTheirReceiverAndNestUnderTheirType() {
        StructureNode kubelet = find(outline("kubelet.go", resource("kubelet-excerpt.go")), "Kubelet");
        List<String> members = kubelet.children().stream()
                .map(n -> n.kind() + " " + n.label() + n.detail())
                .toList();
        assertEquals(
                List.of(
                        "field kubeletConfiguration",
                        "field hostname",
                        "field nodeName",
                        "field cachedNode",
                        "field runtimeCache",
                        "field kubeClient",
                        "field heartbeatClient",
                        "field lastNodeUnschedulable",
                        "field lifecycle.PodSyncLoopHandlers",
                        "field lifecycle.PodSyncHandlers",
                        "method (*Kubelet).ListPodStats(…)",
                        "method (*Kubelet).RootFsStats()",
                        "method (*Kubelet).Run(…)"),
                members,
                "fields (embedded ones by their type), then the methods, receiver and all");
        StructureNode run = find(kubelet.children(), "(*Kubelet).Run");
        assertEquals(101, run.line(), "the func line");
        assertEquals(104, run.region().endLine(), "its body");
    }

    @Test
    void anInterfaceListsItsMethodsAndTypedConstantsSitUnderTheirType() {
        StructureNode sync = find(outline("kubelet.go", resource("kubelet-excerpt.go")), "SyncHandler");
        assertEquals(6, sync.children().size(), dump(sync.children()).toString());
        assertEquals(
                "HandlePodAdditions(…)",
                sync.children().getFirst().label() + sync.children().getFirst().detail());

        List<StructureNode> types = outline("types.go", resource("types-excerpt.go"));
        assertEquals(
                List.of("PodPhase", "Secret"),
                types.stream().map(StructureNode::label).toList(),
                dump(types).toString());
        assertEquals(
                List.of("PodPending", "PodRunning", "PodSucceeded", "PodFailed", "PodUnknown"),
                find(types, "PodPhase").children().stream()
                        .map(StructureNode::label)
                        .toList(),
                "an enumeration reads as one");
        assertEquals(
                List.of("metav1.TypeMeta", "metav1.ObjectMeta", "Immutable", "Data", "StringData", "Type"),
                find(types, "Secret").children().stream()
                        .map(StructureNode::label)
                        .toList());
    }

    @Test
    void goGroupsGenericsAndValueReceiversAreRead() {
        String go = "package p\n\ntype (\n\tA struct {\n\t\tX, Y int\n\t\tInner struct {\n\t\t\tZ string\n\t\t}\n\t}\n"
                + "\tB = A\n)\n\nfunc (s Set[T]) Has(v T) bool { return true }\n\nfunc Map[T, U any](xs []T) []U {\n"
                + "\treturn nil\n}\n\nconst (\n\tKindA Kind = iota\n\tKindB\n\t_\n)\n\ntype Kind int\n";
        List<StructureNode> roots = outline("p.go", go);
        assertEquals(
                List.of(
                        "struct A @4[4-9]",
                        "  field X @5",
                        "  field Y @5",
                        "  field Inner @6[6-8]",
                        "    field Z @7",
                        "class B @10[10-10]",
                        "method (Set).Has(…) @13[13-13]",
                        "function Map(…) @15[15-17]",
                        "class Kind @25[25-25]",
                        "  constant KindA @20",
                        "  constant KindB @21"),
                dump(roots));
    }

    @Test
    void theGoGrammarsPackageClauseQualifiersAndEmbeddedFieldsAreNoTypeSymbols() {
        String go = "package kubelet\n\ntype (\n\tGrouped struct {\n\t}\n)\n\ntype Kubelet struct {\n"
                + "\tlifecycle.PodSyncHandlers\n\tEmbedded\n}\n\nfunc New() {\n\tx := []any{\n\t\tclock.RealClock{},\n\t}\n}\n";
        List<String> symbols =
                TextMateHighlighter.analyze(go, GrammarRegistry.shared().forFileName("x.go")).symbols().stream()
                        .map(s -> s.line() + ":" + s.name() + ":" + s.kind())
                        .toList();
        assertFalse(symbols.contains("0:kubelet:type"), symbols.toString());
        assertTrue(symbols.contains("3:Grouped:type"), "a grouped spec still is one: " + symbols);
        assertTrue(symbols.contains("7:Kubelet:type"), symbols.toString());
        assertFalse(symbols.stream().anyMatch(s -> s.startsWith("8:") || s.startsWith("9:")), symbols.toString());
        assertFalse(symbols.stream().anyMatch(s -> s.startsWith("14:")), "a composite literal: " + symbols);
    }

    // ---- C5 / C6 / C8: Java and Kotlin ----

    @Test
    void aJavaClassWithAWrappedHeaderHoldsItsFieldsAndMembers() {
        List<StructureNode> roots =
                outline("AbstractApplicationContext.java", resource("AbstractApplicationContext-excerpt.java"));
        assertEquals(1, roots.size(), dump(roots).toString());
        StructureNode type = roots.getFirst();
        assertEquals("AbstractApplicationContext", type.label());
        assertEquals(23, type.line(), "the line that names it, above `implements … {`");
        assertEquals(24, type.region().startLine());
        assertEquals(
                List.of(
                        "field MESSAGE_SOURCE_BEAN_NAME",
                        "field logger",
                        "field id",
                        "field earlyApplicationEvents",
                        "function AbstractApplicationContext",
                        "function AbstractApplicationContext"),
                type.children().stream().map(n -> n.kind() + " " + n.label()).toList());
    }

    @Test
    void javaFieldsAreReadAtMemberDepthOnly() {
        String java = "class A {\n" // 0
                + "    @Deprecated\n" // 1
                + "    private static final int MAX = 3;\n" // 2
                + "    private final Map<String, List<Integer>> byName = new HashMap<>(\n" // 3
                + "            Map.of(\"a\", List.of(1)));\n" // 4
                + "    String text = \"x; y = 2\";\n" // 5
                + "    int a, b;\n" // 6
                + "    private int wrapped =\n" // 7
                + "            other + value;\n" // 8
                + "    void run() {\n" // 9
                + "        int local = 1;\n" // 10
                + "    }\n" // 11
                + "    enum E { ONE, TWO }\n" // 12
                + "    static class Inner {\n" // 13
                + "        long count;\n" // 14
                + "    }\n" // 15
                + "}\n";
        List<String> all = dump(outline("A.java", java));
        assertTrue(all.contains("  field MAX @3"), all.toString());
        assertTrue(all.contains("  field byName @4"), all.toString());
        assertTrue(all.contains("  field text @6"), all.toString());
        assertTrue(all.contains("  field a @7"), all.toString());
        assertTrue(all.contains("  field wrapped @8"), all.toString());
        assertTrue(all.contains("    field count @15"), "a nested class's own: " + all);
        assertFalse(all.stream().anyMatch(s -> s.contains("local") || s.contains("other")), all.toString());
    }

    @Test
    void kotlinAnnotationsAreNoTypesAndAWrappedConstructorHeaderHoldsTheClass() {
        List<StructureNode> roots = outline("BeanDefinitionDsl.kt", resource("BeanDefinitionDsl-excerpt.kt"));
        List<String> all = dump(roots);
        assertFalse(all.stream().anyMatch(s -> s.contains(" @") && s.contains("type @")), all.toString());
        assertFalse(all.stream().anyMatch(s -> s.contains("Deprecated") || s.contains("PublishedApi")), all.toString());
        StructureNode dsl = find(roots, "BeanDefinitionDsl");
        assertEquals(34, dsl.line());
        assertNotNull(dsl.region(), "the class is its body, not one line");
        assertEquals(46, dsl.region().endLine());
    }

    @Test
    void aGradleKotlinScriptIsOutlinedByItsBlocks() {
        String kts = "plugins {\n    `java-library`\n}\n\nrepositories {\n    mavenCentral()\n}\n\n"
                + "tasks.register(\"hello\") {\n    doLast {\n        println(\"hello\")\n    }\n}\n\nfun helper(x: Int) = x * 2\n";
        assertEquals(
                List.of(
                        "null plugins { @1[1-3]",
                        "null repositories { @5[5-7]",
                        "null tasks.register(\"hello\") { @9[9-13]",
                        "  null doLast { @10[10-12]",
                        "function helper @15"),
                dump(outline("build.gradle.kts", kts)));
    }

    @Test
    void aWrappedHeaderNeverBorrowsADeclarationAcrossAStatement() {
        List<String> lines = List.of(
                "class A {", // 0
                "    void run() {", // 1
                "        String s = foo", // 2
                "            .bar(x -> {", // 3
                "            });", // 4
                "    }", // 5
                "}");
        java.util.Map<Integer, TextMateHighlighter.Symbol> symbols = java.util.Map.of(
                0,
                new TextMateHighlighter.Symbol(0, "A", "type"),
                1,
                new TextMateHighlighter.Symbol(1, "run", "function"));
        assertEquals(-1, StructureOutline.wrappedHeaderLine(lines::get, 3, symbols));
        List<String> header = List.of("public class A extends B", "        implements C {", "}");
        assertEquals(
                0,
                StructureOutline.wrappedHeaderLine(
                        header::get, 1, java.util.Map.of(0, new TextMateHighlighter.Symbol(0, "A", "type"))));
    }

    // ---- C7: YAML ----

    @Test
    void aYamlKeySpansItsBlockScalarAndItsList() {
        List<StructureNode> roots = outline("fluentd-gcp-configmap.yaml", resource("fluentd-configmap-excerpt.yaml"));
        StructureNode data = find(roots, "data");
        assertEquals(
                "key containers.input.conf @10[10-19]",
                dump(List.of(data.children().getFirst())).getFirst());
        assertEquals(
                "key system.input.conf @20[20-24]",
                dump(List.of(data.children().get(1))).getFirst());
        assertNull(find(roots, "kind").region(), "a one-line value");

        String manifest =
                "spec:\n  containers:\n  - name: app\n    image: nginx\n    args:\n    - --port\n    - \"80\"\n"
                        + "  - name: side\n  volumes: []\n# trailing\n";
        List<String> all = dump(outline("deploy.yaml", manifest));
        assertEquals(
                List.of(
                        "key spec @1[1-9]",
                        "  key containers @2[2-8]",
                        "    key name @3",
                        "    key image @4",
                        "    key args @5[5-7]",
                        "    key name @8",
                        "  key volumes @9"),
                all,
                "a list at its key's own indent is the key's");
    }

    // ---- C13 / C14: shell, Makefile, Dockerfile ----

    @Test
    void aShellOutlineHasNoDollarRows() {
        List<String> all = dump(outline("common.sh", resource("common-excerpt.sh")));
        assertEquals(
                List.of("function kube::build::verify_prereqs @27"),
                all.stream().map(s -> s.replaceAll("\\[.*", "")).toList());
    }

    @Test
    void repeatedMakefileTargetsAreToldApartByLineAndHoldTheirRecipe() {
        List<String> all = dump(outline("Makefile", resource("Makefile-excerpt")));
        assertTrue(all.contains("function all (line 18) @18[18-19]"), all.toString());
        assertTrue(all.contains("function all (line 21) @21[21-22]"), all.toString());
    }

    @Test
    void aDockerfileIsOutlinedByItsBuildStages() {
        assertTrue(StructureOutline.supports("Dockerfile"));
        List<String> all = dump(outline("Dockerfile", resource("Dockerfile-excerpt")));
        assertEquals(2, all.size(), all.toString());
        assertTrue(all.getFirst().startsWith("module prep — $BASEIMAGE @"), all.toString());
        assertTrue(all.get(1).startsWith("module scratch @"), all.toString());
        assertTrue(all.get(1).endsWith("-22]"), "the last stage runs to the end: " + all);
    }
}
