package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go to Super Method through the coordinator (seventh review BR1/BR2): the caret anywhere in a method asks jdtls about
 * that method from its name, a type goes to its supertypes, and nothing found is said.
 */
@Tag("fx")
class LspSuperMethodFxTest {

    private static final String SOURCE = "class A extends Base {\n    void go() {\n        int x = 1;\n    }\n}\n";

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    private static List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> outline() {
        DocumentSymbol go = new DocumentSymbol("go()", SymbolKind.Method, range(1, 4, 3), range(1, 9, 11));
        go.setRange(new Range(new Position(1, 4), new Position(3, 5)));
        DocumentSymbol a = new DocumentSymbol("A", SymbolKind.Class, range(0, 0, 1), range(0, 6, 7));
        a.setRange(new Range(new Position(0, 0), new Position(4, 1)));
        a.setChildren(List.of(go));
        return List.of(Either.forRight(a));
    }

    @Test
    void fromInsideAMethodItsSuperMethodIsAskedFromItsName() throws Exception {
        Path base = Files.writeString(root.resolve("Base.java"), "class Base {\n    void go() {}\n}\n");
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.server().documentSymbolResponse = outline();
        List<Object> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.server().rawHandler = raw -> {
            if (!raw.method().equals("java/findLinks")) {
                return null;
            }
            asked.add(raw.params());
            return com.google.gson.JsonParser.parseString("[{\"uri\":\"" + base.toUri()
                    + "\",\"range\":{\"start\":{\"line\":1,\"character\":9},\"end\":{\"line\":1,\"character\":11}}}]");
        };
        fx.caret(b, 2, 12); // in the body
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(1, asked.size());
        assertTrue(new com.google.gson.Gson().toJson(asked.get(0)).contains("\"line\":1"), "from the name line");
        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 1, 9)), fx.ops.jumps);
    }

    @Test
    void onATypeItGoesToTheSupertype() throws Exception {
        Path base = Files.writeString(root.resolve("Base.java"), "class Base {}\n");
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.server().documentSymbolResponse = outline();
        String uri = root.resolve("A.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("A", SymbolKind.Class, uri, range(0, 0, 1), range(0, 6, 7)));
        fx.server().supertypesResponse = List.of(new TypeHierarchyItem(
                "Base", SymbolKind.Class, base.toUri().toString(), range(0, 0, 1), range(0, 6, 10)));
        fx.caret(b, 0, 2); // on the class line
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 0, 6)), fx.ops.jumps);
    }

    private static DocumentSymbol member(String name, SymbolKind kind, int start, int end, int nameLine, int nameCol) {
        DocumentSymbol s = new DocumentSymbol(
                name,
                kind,
                new Range(
                        new Position(start, 0),
                        new Position(end, 200)), // to its line's end (BL3: an end column counts)
                range(nameLine, nameCol, nameCol + 1));
        s.setChildren(new java.util.ArrayList<>());
        return s;
    }

    /** On a method's @Override line the method is asked about, not its class (eighth review BQ1). */
    @Test
    void anOverrideLineIsTheMethods() throws Exception {
        Path base = Files.writeString(root.resolve("Base.java"), "class Base {\n    void go() {}\n}\n");
        String source = "class A extends Base {\n    @Override\n    void go() {\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("go()", SymbolKind.Method, 1, 3, 2, 9));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        List<Object> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.server().rawHandler = raw -> {
            asked.add(raw.params());
            return com.google.gson.JsonParser.parseString("[{\"uri\":\"" + base.toUri()
                    + "\",\"range\":{\"start\":{\"line\":1,\"character\":9},\"end\":{\"line\":1,\"character\":11}}}]");
        };
        fx.caret(b, 1, 6); // on @Override
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertTrue(new com.google.gson.Gson().toJson(asked).contains("\"line\":2"), "from the method's name: " + asked);
        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 1, 9)), fx.ops.jumps);
    }

    /** In a lambda, Go to Definition on its arrow: the functional interface's method (eighth review BQ2). */
    @Test
    void aLambdaGoesToItsFunctionalMethod() throws Exception {
        Path runnable = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        String source = "class A {\n    void go() {\n        Task t = () -> System.out.println();\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("go()", SymbolKind.Method, 1, 3, 1, 9));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        fx.server().definitionResponse =
                List.of(new org.eclipse.lsp4j.Location(runnable.toUri().toString(), range(1, 9, 12)));
        fx.caret(b, 2, 30); // in the lambda's body
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        Position asked =
                fx.server().definitions.get(fx.server().definitions.size() - 1).getPosition();
        assertEquals(new Position(2, source.split("\n")[2].indexOf("->")), asked, "at the arrow");
        assertEquals(List.of(new LspCoordinatorFixture.Jump(runnable, 1, 9)), fx.ops.jumps);
    }

    /** A constructor goes to the super constructor its super(...) calls (eighth review BR2). */
    @Test
    void aConstructorGoesToTheSuperConstructorItCalls() throws Exception {
        Path base = Files.writeString(root.resolve("Base.java"), "class Base {\n    Base(String s) {}\n}\n");
        String source = "class A extends Base {\n    A() {\n        super(\"a\");\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("A()", SymbolKind.Constructor, 1, 3, 1, 4));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        fx.server().definitionResponse =
                List.of(new org.eclipse.lsp4j.Location(base.toUri().toString(), range(1, 4, 8)));
        fx.caret(b, 1, 5);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        Position asked =
                fx.server().definitions.get(fx.server().definitions.size() - 1).getPosition();
        assertEquals(new Position(2, 8), asked, "at super");
        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 1, 4)), fx.ops.jumps);
    }

    /**
     * An interface's default method goes to the method it overrides in a superinterface, found up the type hierarchy:
     * jdtls's findLinks answers nothing there (eighth review BR2).
     */
    @Test
    void anInterfaceMethodIsFoundUpTheHierarchy() throws Exception {
        Path shape = Files.writeString(root.resolve("Shape.java"), "interface Shape {\n    String label();\n}\n");
        String source =
                "interface Named extends Shape {\n    default String label() {\n        return \"n\";\n    }\n}\n";
        EditorBuffer b = fx.open("Named.java", source);
        DocumentSymbol named = member("Named", SymbolKind.Interface, 0, 4, 0, 10);
        named.getChildren().add(member("label()", SymbolKind.Method, 1, 3, 1, 19));
        fx.server().documentSymbolResponse = List.of(Either.forRight(named));
        DocumentSymbol shapeType = member("Shape", SymbolKind.Interface, 0, 2, 0, 10);
        shapeType.getChildren().add(member("label()", SymbolKind.Method, 1, 1, 1, 11));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(shape), List.of(Either.forRight(shapeType)));
        String uri = root.resolve("Named.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("Named", SymbolKind.Interface, uri, range(0, 0, 1), range(0, 10, 15)));
        fx.server().supertypesResponse = List.of(new TypeHierarchyItem(
                "Shape", SymbolKind.Interface, shape.toUri().toString(), range(0, 0, 1), range(0, 10, 15)));
        fx.server().supertypesByName.put("Shape", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]"); // findLinks: nothing
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(shape, 1, 11)), fx.ops.jumps);
    }

    /**
     * A class's method marked {@code @Override} is looked for up the hierarchy when findLinks answers nothing, as for an
     * inner class of a generic class; a type variable named {@code Item} matches String (tenth review EO1).
     */
    @Test
    void anOverrideFindLinksMissesIsFoundUpTheHierarchy() throws Exception {
        Path store =
                Files.writeString(root.resolve("Store.java"), "interface Store<Item> {\n    void add(Item i);\n}\n");
        String source = "class StrStore implements Store<String> {\n    @Override\n    public void add(String i) {\n"
                + "        int x = 1;\n    }\n}\n";
        EditorBuffer b = fx.open("StrStore.java", source);
        DocumentSymbol strStore = member("StrStore", SymbolKind.Class, 0, 5, 0, 6);
        strStore.getChildren().add(member("add(String)", SymbolKind.Method, 1, 4, 2, 16));
        fx.server().documentSymbolResponse = List.of(Either.forRight(strStore));
        DocumentSymbol storeType = member("Store<Item>", SymbolKind.Interface, 0, 2, 0, 10);
        storeType.getChildren().add(member("add(Item)", SymbolKind.Method, 1, 1, 1, 9));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(store), List.of(Either.forRight(storeType)));
        String uri = root.resolve("StrStore.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("StrStore", SymbolKind.Class, uri, range(0, 0, 1), range(0, 6, 14)));
        fx.server().supertypesResponse = List.of(new TypeHierarchyItem(
                "Store", SymbolKind.Interface, store.toUri().toString(), range(0, 0, 1), range(0, 10, 15)));
        fx.server().supertypesByName.put("Store", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]"); // findLinks: nothing
        fx.caret(b, 3, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(store, 1, 9)), fx.ops.jumps);
    }

    /**
     * From a library tab a target in another library class opens: the request's check compared the project file the
     * tab is served through with the tab's own path, none, and dropped every such target (eighth review BQ3).
     */
    @Test
    void aLibraryTargetOpensFromALibraryTab() throws Exception {
        String list = "jdt://contents/java.base/java.util/ArrayList.class?=demo";
        String abstractList = "jdt://contents/java.base/java.util/AbstractList.class?=demo";
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.ops.readOnlyOpener = doc -> {
            EditorBuffer library = new EditorBuffer();
            library.setContent(doc.content());
            fx.show(library);
            fx.host.active = library;
            return library;
        };
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.server().rawHandler = raw -> {
            asked.add(raw.method());
            if (raw.method().equals("java/classFileContents")) {
                return new com.google.gson.JsonPrimitive(
                        "class X {\n    public boolean add(Object o) { return true; }\n}\n");
            }
            return com.google.gson.JsonParser.parseString("[{\"uri\":\"" + abstractList
                    + "\",\"range\":{\"start\":{\"line\":1,\"character\":4},\"end\":{\"line\":1,\"character\":7}}}]");
        };
        fx.server().definitionResponse = List.of(new org.eclipse.lsp4j.Location(list, range(1, 19, 22)));
        fx.caret(b, 2, 12);
        fx.run(() -> fx.coordinator.gotoDefinition());
        fx.settle();
        assertEquals(1, fx.ops.readOnlyDocs.size(), "ArrayList opened");

        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertTrue(asked.contains("java/findLinks"), "asked from the library tab: " + asked);
        assertEquals(2, fx.ops.readOnlyDocs.size(), "AbstractList opened from ArrayList's tab: " + asked);
    }

    /** A constructor with no super(...) call goes to the superclass's no-argument constructor (eighth review BR2). */
    @Test
    void anImplicitSuperCallGoesToTheNoArgumentConstructor() throws Exception {
        Path base =
                Files.writeString(root.resolve("Base.java"), "class Base {\n    Base(int x) {}\n    Base() {}\n}\n");
        String source = "class A extends Base {\n    A() {\n        int y = 1;\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("A()", SymbolKind.Constructor, 1, 3, 1, 4));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        DocumentSymbol baseType = member("Base", SymbolKind.Class, 0, 3, 0, 6);
        baseType.getChildren().add(member("Base(int)", SymbolKind.Constructor, 1, 1, 1, 4));
        baseType.getChildren().add(member("Base()", SymbolKind.Constructor, 2, 2, 2, 4));
        fx.server().documentSymbolByUri.put(com.editora.lsp.LspTestHooks.uri(base), List.of(Either.forRight(baseType)));
        String uri = root.resolve("A.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("A", SymbolKind.Class, uri, range(0, 0, 1), range(0, 6, 7)));
        fx.server().supertypesResponse = List.of(new TypeHierarchyItem(
                "Base", SymbolKind.Class, base.toUri().toString(), range(0, 0, 1), range(0, 6, 10)));
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 2, 4)), fx.ops.jumps, "Base(), not Base(int)");
    }

    /** On an anonymous class itself, the type it instantiates (eighth review BQ2). */
    @Test
    void onAnAnonymousClassItGoesToTheTypeItMakes() throws Exception {
        Path task = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        String source = "class A {\n    void go() {\n        Task t = new Task() {\n            int n;\n"
                + "            public void run() {}\n        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 7, 0, 6);
        DocumentSymbol go = member("go()", SymbolKind.Method, 1, 6, 1, 9);
        DocumentSymbol anon = member("new Task() {...}", SymbolKind.Class, 2, 5, 2, 21);
        anon.getChildren().add(member("run()", SymbolKind.Method, 4, 4, 4, 24));
        go.getChildren().add(anon);
        a.getChildren().add(go);
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        fx.server().typeHierarchyResponse = List.of(new TypeHierarchyItem(
                "Task", SymbolKind.Interface, task.toUri().toString(), range(0, 0, 1), range(0, 10, 14)));
        fx.caret(b, 3, 14); // the anonymous class's field
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(task, 0, 10)), fx.ops.jumps);
    }

    /** A static or private method overrides nothing: no jump to a same-named one (ninth review DP1). */
    @Test
    void aStaticMethodHasNoSuperMethod() throws Exception {
        String source = "class A extends Base {\n    static void util() {\n        int x = 1;\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("util()", SymbolKind.Method, 1, 3, 1, 16));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.server().rawHandler = raw -> {
            asked.add(raw.method());
            return com.google.gson.JsonParser.parseString("[]");
        };
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(tr("status.lsp.noSuperMethod"), fx.host.lastStatus());
        assertTrue(asked.isEmpty(), "not asked: " + asked);
    }

    /** A lambda held by a field goes to its functional interface's method (ninth review DP2). */
    @Test
    void aFieldsLambdaGoesToItsFunctionalMethod() throws Exception {
        Path task = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        String source = "class A {\n    final Task t = () -> {\n        System.out.println();\n    };\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("t", SymbolKind.Field, 1, 3, 1, 15));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        fx.server().definitionResponse =
                List.of(new org.eclipse.lsp4j.Location(task.toUri().toString(), range(1, 9, 12)));
        fx.caret(b, 2, 12);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        Position asked =
                fx.server().definitions.get(fx.server().definitions.size() - 1).getPosition();
        assertEquals(new Position(1, source.split("\n")[1].indexOf("->")), asked, "at the arrow");
        assertEquals(List.of(new LspCoordinatorFixture.Jump(task, 1, 9)), fx.ops.jumps);
    }

    /** A constructor chained with this(...) goes on to the super constructor the chain reaches (ninth review BP3). */
    @Test
    void aChainedConstructorFollowsThisToSuper() throws Exception {
        Path base = Files.writeString(root.resolve("Base.java"), "class Base {\n    Base(int x) {}\n}\n");
        String source = "class A extends Base {\n    A() {\n        this(1);\n    }\n    A(int x) {\n"
                + "        super(x);\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 7, 0, 6);
        a.getChildren().add(member("A()", SymbolKind.Constructor, 1, 3, 1, 4));
        a.getChildren().add(member("A(int)", SymbolKind.Constructor, 4, 6, 4, 4));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        String self = root.resolve("A.java").toUri().toString();
        fx.server().definitionHandler = params -> params.getPosition().getLine() == 2
                ? List.of(new org.eclipse.lsp4j.Location(self, range(4, 4, 5))) // this(1) → A(int)
                : List.of(new org.eclipse.lsp4j.Location(base.toUri().toString(), range(1, 4, 8))); // super(x)
        fx.caret(b, 2, 9);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 1, 4)), fx.ops.jumps);
    }

    /**
     * Of two super methods where one's type extends the other's, the more specific one alone: Sub.label goes to
     * Named.label, not Priced.label too (ninth review EP3).
     */
    @Test
    void aSuperMethodAnotherOverridesIsDropped() throws Exception {
        Path priced = Files.writeString(root.resolve("Priced.java"), "interface Priced {\n    String label();\n}\n");
        Path named = Files.writeString(
                root.resolve("Named.java"), "interface Named extends Priced {\n    String label();\n}\n");
        Path base = Files.writeString(root.resolve("Base.java"), "abstract class Base implements Priced {\n}\n");
        String source =
                "class Sub extends Base implements Named {\n    public String label() {\n        return \"s\";\n"
                        + "    }\n}\n";
        EditorBuffer b = fx.open("Sub.java", source);
        DocumentSymbol sub = member("Sub", SymbolKind.Class, 0, 4, 0, 6);
        sub.getChildren().add(member("label()", SymbolKind.Method, 1, 3, 1, 18));
        fx.server().documentSymbolResponse = List.of(Either.forRight(sub));
        for (Path type : List.of(priced, named)) {
            DocumentSymbol t =
                    member(type.getFileName().toString().replace(".java", ""), SymbolKind.Interface, 0, 2, 0, 10);
            t.getChildren().add(member("label()", SymbolKind.Method, 1, 1, 1, 11));
            fx.server().documentSymbolByUri.put(com.editora.lsp.LspTestHooks.uri(type), List.of(Either.forRight(t)));
        }
        fx.server()
                .documentSymbolByUri
                .put(
                        com.editora.lsp.LspTestHooks.uri(base),
                        List.of(Either.forRight(member("Base", SymbolKind.Class, 0, 1, 0, 15))));
        java.util.function.BiFunction<String, Path, TypeHierarchyItem> item = (name, file) -> new TypeHierarchyItem(
                name,
                name.equals("Base") || name.equals("Sub") ? SymbolKind.Class : SymbolKind.Interface,
                file.toUri().toString(),
                range(0, 0, 1),
                range(0, 10, 15));
        fx.server().typeHierarchyResponse = List.of(item.apply("Sub", root.resolve("Sub.java")));
        fx.server().supertypesByName.put("Sub", List.of(item.apply("Named", named), item.apply("Base", base)));
        fx.server().supertypesByName.put("Base", List.of(item.apply("Priced", priced)));
        fx.server().supertypesByName.put("Named", List.of(item.apply("Priced", priced)));
        fx.server().supertypesByName.put("Priced", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[{\"uri\":\"" + priced.toUri()
                + "\",\"range\":{\"start\":{\"line\":1,\"character\":11},\"end\":{\"line\":1,\"character\":16}}}]");
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(named, 1, 11)), fx.ops.jumps, "Named.label alone");
    }

    private static String link(Path file, int line, int col) {
        return "[{\"uri\":\"" + file.toUri() + "\",\"range\":{\"start\":{\"line\":" + line + ",\"character\":" + col
                + "},\"end\":{\"line\":" + line + ",\"character\":" + (col + 3) + "}}}]";
    }

    /** An anonymous class in an initializer, which jdtls leaves out of the outline, is found in the text (BQ2). */
    @Test
    void anInitializersAnonymousClassIsFoundInTheText() throws Exception {
        Path task = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        String source = "class A {\n    static {\n        Task t = new Task() {\n            public void run() {\n"
                + "                go();\n            }\n        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        fx.server().documentSymbolResponse = List.of(Either.forRight(member("A", SymbolKind.Class, 0, 8, 0, 6)));
        List<Object> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.server().rawHandler = raw -> {
            asked.add(raw.params());
            return com.google.gson.JsonParser.parseString(link(task, 1, 9));
        };
        fx.caret(b, 4, 16);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertTrue(new com.google.gson.Gson().toJson(asked).contains("\"line\":3"), "from run's name: " + asked);
        assertEquals(List.of(new LspCoordinatorFixture.Jump(task, 1, 9)), fx.ops.jumps);
    }

    /** A lambda in an initializer goes to its functional interface's method (BQ2). */
    @Test
    void anInitializersLambdaGoesToItsFunctionalMethod() throws Exception {
        Path task = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        String source = "class A {\n    static {\n        Task t = () -> {\n            go();\n        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        fx.server().documentSymbolResponse = List.of(Either.forRight(member("A", SymbolKind.Class, 0, 6, 0, 6)));
        fx.server().definitionResponse =
                List.of(new org.eclipse.lsp4j.Location(task.toUri().toString(), range(1, 9, 12)));
        fx.caret(b, 3, 12);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(task, 1, 9)), fx.ops.jumps);
    }

    /** On an enum constant's own body, outside its methods, the enum (ninth review DP2). */
    @Test
    void anEnumConstantsBodyGoesToItsEnum() throws Exception {
        String source = "enum Ops {\n    PLUS {\n        int n;\n        int apply() { return 1; }\n    };\n"
                + "    abstract int apply();\n}\n";
        EditorBuffer b = fx.open("Ops.java", source);
        DocumentSymbol ops = member("Ops", SymbolKind.Enum, 0, 6, 0, 5);
        DocumentSymbol plus = member("PLUS", SymbolKind.EnumMember, 1, 4, 1, 4);
        DocumentSymbol body = member("{...}", SymbolKind.Enum, 1, 4, 1, 4);
        body.getChildren().add(member("apply()", SymbolKind.Method, 3, 3, 3, 12));
        plus.getChildren().add(body);
        ops.getChildren().add(plus);
        fx.server().documentSymbolResponse = List.of(Either.forRight(ops));
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(root.resolve("Ops.java"), 0, 5)), fx.ops.jumps);
    }

    /** A walk that takes a moment says it is looking; Object is not read for a name it does not declare (BP5). */
    @Test
    void aSlowWalkSaysItIsLookingAndSkipsObject() throws Exception {
        Path named = Files.writeString(root.resolve("Named.java"), "interface Named {\n    String label();\n}\n");
        String source =
                "interface Sub extends Named {\n    default String label() {\n        return \"s\";\n    }\n}\n";
        EditorBuffer b = fx.open("Sub.java", source);
        DocumentSymbol sub = member("Sub", SymbolKind.Interface, 0, 4, 0, 10);
        sub.getChildren().add(member("label()", SymbolKind.Method, 1, 3, 1, 19));
        fx.server().documentSymbolResponse = List.of(Either.forRight(sub));
        DocumentSymbol n = member("Named", SymbolKind.Interface, 0, 2, 0, 10);
        n.getChildren().add(member("label()", SymbolKind.Method, 1, 1, 1, 11));
        fx.server().documentSymbolByUri.put(com.editora.lsp.LspTestHooks.uri(named), List.of(Either.forRight(n)));
        String uri = root.resolve("Sub.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("Sub", SymbolKind.Interface, uri, range(0, 0, 1), range(0, 10, 13)));
        fx.server()
                .supertypesByName
                .put(
                        "Sub",
                        List.of(
                                new TypeHierarchyItem(
                                        "Object",
                                        SymbolKind.Class,
                                        root.resolve("Object.java").toUri().toString(),
                                        range(0, 0, 1),
                                        range(0, 13, 19)),
                                new TypeHierarchyItem(
                                        "Named",
                                        SymbolKind.Interface,
                                        named.toUri().toString(),
                                        range(0, 0, 1),
                                        range(0, 10, 15))));
        fx.server().supertypesByName.put("Named", List.of());
        fx.server().rawHandler = raw -> {
            try {
                Thread.sleep(450); // findLinks takes a moment
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return com.google.gson.JsonParser.parseString("[]");
        };
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        for (int i = 0; i < 100 && !fx.host.statuses.contains(tr("status.lsp.superMethodSearching")); i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertTrue(fx.host.statuses.contains(tr("status.lsp.superMethodSearching")), fx.host.statuses.toString());
        for (int i = 0; i < 200 && fx.ops.jumps.isEmpty(); i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertEquals(List.of(new LspCoordinatorFixture.Jump(named, 1, 11)), fx.ops.jumps);
        assertTrue(
                fx.server().documentSymbols.stream()
                        .noneMatch(d -> d.getTextDocument().getUri().endsWith("Object.java")),
                "Object's outline is not read for label");
    }

    /** A request that fails says so (not "No super method found"). */
    @Test
    void aFailedRequestSaysSo() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.server().documentSymbolResponse = outline();
        fx.server().failEverything = true; // the outline and findLinks both fail
        fx.caret(b, 2, 12);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        for (int i = 0; i < 200 && fx.host.lastError() == null; i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertTrue(fx.host.lastError() != null, fx.host.statuses.toString());
        assertTrue(fx.ops.jumps.isEmpty());
    }

    /** A constructor of a class whose only superclass is Object has no super constructor to go to. */
    @Test
    void aConstructorOverObjectHasNone() throws Exception {
        String source = "class A {\n    A() {\n        int x = 1;\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("A()", SymbolKind.Constructor, 1, 3, 1, 4));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        String uri = root.resolve("A.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("A", SymbolKind.Class, uri, range(0, 0, 1), range(0, 6, 7)));
        fx.server().supertypesResponse = List.of(new TypeHierarchyItem(
                "Object",
                SymbolKind.Class,
                root.resolve("Object.java").toUri().toString(),
                range(0, 0, 1),
                range(0, 13, 19)));
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(tr("status.lsp.noSuperMethod"), fx.host.lastStatus());
        assertTrue(fx.ops.jumps.isEmpty());
    }

    /** A this(...) jdtls cannot resolve falls back to the superclass's no-argument constructor. */
    @Test
    void anUnresolvedThisCallFallsBackToTheImplicitSuper() throws Exception {
        Path base = Files.writeString(root.resolve("Base.java"), "class Base {\n    Base() {}\n}\n");
        String source = "class A extends Base {\n    A() {\n        this(1);\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("A()", SymbolKind.Constructor, 1, 3, 1, 4));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        DocumentSymbol baseType = member("Base", SymbolKind.Class, 0, 2, 0, 6);
        baseType.getChildren().add(member("Base()", SymbolKind.Constructor, 1, 1, 1, 4));
        fx.server().documentSymbolByUri.put(com.editora.lsp.LspTestHooks.uri(base), List.of(Either.forRight(baseType)));
        String uri = root.resolve("A.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("A", SymbolKind.Class, uri, range(0, 0, 1), range(0, 6, 7)));
        fx.server().supertypesResponse = List.of(new TypeHierarchyItem(
                "Base", SymbolKind.Class, base.toUri().toString(), range(0, 0, 1), range(0, 6, 10)));
        fx.server().definitionResponse = List.of(); // jdtls finds no A(int)
        fx.caret(b, 2, 9);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(base, 1, 4)), fx.ops.jumps);
    }

    @Test
    void nothingFoundIsSaid() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        fx.server().documentSymbolResponse = outline();
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]");
        fx.caret(b, 2, 12);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(tr("status.lsp.noSuperMethod"), fx.host.lastStatus());
        assertTrue(fx.ops.jumps.isEmpty());
    }

    /** An interface whose supertype is Store<String>: save(Integer) overrides nothing, save(String) Store.save (DO2). */
    @Test
    void aTypeArgumentDecidesWhichMethodIsOverridden() throws Exception {
        Path store = Files.writeString(root.resolve("Store.java"), "interface Store<T> {\n    void save(T item);\n}\n");
        String source = "interface StrStore extends Store<String> {\n    void save(Integer other);\n"
                + "    void save(String item);\n}\n";
        EditorBuffer b = fx.open("StrStore.java", source);
        DocumentSymbol strStore = member("StrStore", SymbolKind.Interface, 0, 3, 0, 10);
        strStore.getChildren().add(member("save(Integer)", SymbolKind.Method, 1, 1, 1, 9));
        strStore.getChildren().add(member("save(String)", SymbolKind.Method, 2, 2, 2, 9));
        fx.server().documentSymbolResponse = List.of(Either.forRight(strStore));
        DocumentSymbol storeType = member("Store<T>", SymbolKind.Interface, 0, 2, 0, 10);
        storeType.getChildren().add(member("save(T)", SymbolKind.Method, 1, 1, 1, 9));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(store), List.of(Either.forRight(storeType)));
        String uri = root.resolve("StrStore.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("StrStore", SymbolKind.Interface, uri, range(0, 0, 1), range(0, 10, 18)));
        fx.server()
                .supertypesByName
                .put(
                        "StrStore",
                        List.of(new TypeHierarchyItem(
                                "Store",
                                SymbolKind.Interface,
                                store.toUri().toString(),
                                range(0, 0, 1),
                                range(0, 10, 15))));
        fx.server().supertypesByName.put("Store", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]");

        fx.caret(b, 1, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();
        assertEquals(tr("status.lsp.noSuperMethod"), fx.host.lastStatus(), "T is String here");
        assertTrue(fx.ops.jumps.isEmpty());

        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();
        assertEquals(List.of(new LspCoordinatorFixture.Jump(store, 1, 9)), fx.ops.jumps);
    }

    /**
     * jdtls prepares the hierarchy of {@code new java.util.Comparator<>()} at the anonymous class itself: the walk
     * starts above it, and never lands on the method asked about (tenth review AO2).
     */
    @Test
    void anAnonymousClassNeverGoesToItself() throws Exception {
        Path comparator = Files.writeString(
                root.resolve("Comparator.java"), "interface Comparator<T> {\n    int compare(T a, T b);\n}\n");
        String source = "class A {\n    void f() {\n        Object c = new java.util.Comparator<>() {\n"
                + "            public int compare(String a, String b) {\n                return 3;\n            }\n"
                + "        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 8, 0, 6);
        DocumentSymbol f = member("f()", SymbolKind.Method, 1, 7, 1, 9);
        DocumentSymbol anonymous = member("new Comparator<>() {...}", SymbolKind.Class, 2, 6, 2, 34);
        anonymous.getChildren().add(member("compare(String, String)", SymbolKind.Method, 3, 5, 3, 23));
        f.getChildren().add(anonymous);
        a.getChildren().add(f);
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        DocumentSymbol comparatorType = member("Comparator<T>", SymbolKind.Interface, 0, 2, 0, 10);
        comparatorType.getChildren().add(member("compare(T, T)", SymbolKind.Method, 1, 1, 1, 8));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(comparator), List.of(Either.forRight(comparatorType)));
        String uri = root.resolve("A.java").toUri().toString();
        fx.server().typeHierarchyResponse = List.of(new TypeHierarchyItem(
                "new Comparator<>() {...}", SymbolKind.Class, uri, range(2, 0, 1), range(2, 34, 44)));
        fx.server()
                .supertypesByName
                .put(
                        "new Comparator<>() {...}",
                        List.of(new TypeHierarchyItem(
                                "Comparator",
                                SymbolKind.Interface,
                                comparator.toUri().toString(),
                                range(0, 0, 1),
                                range(0, 10, 20))));
        fx.server().supertypesByName.put("Comparator", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]");
        fx.caret(b, 4, 16);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(comparator, 1, 8)), fx.ops.jumps);
    }

    /**
     * An initializer's {@code new Comparator<>()}, which findLinks does not answer for, is walked from the type it
     * instantiates, bound by the field's type (tenth review BO2).
     */
    @Test
    void anInitializersDiamondAnonymousClassIsWalked() throws Exception {
        Path comparator = Files.writeString(
                root.resolve("Comparator.java"), "interface Comparator<T> {\n    int compare(T a, T b);\n}\n");
        String source = "class A {\n    static {\n        Comparator<String> by = new Comparator<>() {\n"
                + "            public int compare(String a, String b) {\n                return 0;\n            }\n"
                + "        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        fx.server().documentSymbolResponse = List.of(Either.forRight(member("A", SymbolKind.Class, 0, 8, 0, 6)));
        DocumentSymbol comparatorType = member("Comparator<T>", SymbolKind.Interface, 0, 2, 0, 10);
        comparatorType.getChildren().add(member("compare(T, T)", SymbolKind.Method, 1, 1, 1, 8));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(comparator), List.of(Either.forRight(comparatorType)));
        fx.server().typeHierarchyResponse = List.of(new TypeHierarchyItem(
                "Comparator", SymbolKind.Interface, comparator.toUri().toString(), range(0, 0, 1), range(0, 10, 20)));
        fx.server().supertypesByName.put("Comparator", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]"); // findLinks: nothing
        fx.caret(b, 4, 16);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(comparator, 1, 8)), fx.ops.jumps);
    }

    /** On a method reference, its functional interface's method, asked one character into the {@code ::} (BO3). */
    @Test
    void aMethodReferenceGoesToItsFunctionalMethod() throws Exception {
        Path function = Files.writeString(
                root.resolve("ToIntFunction.java"), "interface ToIntFunction<T> {\n    int applyAsInt(T t);\n}\n");
        String source = "class A {\n    int n(java.util.List<String> s) {\n"
                + "        return s.stream().mapToInt(String::length).sum();\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 4, 0, 6);
        a.getChildren().add(member("n(List<String>)", SymbolKind.Method, 1, 3, 1, 8));
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        fx.server().definitionResponse =
                List.of(new org.eclipse.lsp4j.Location(function.toUri().toString(), range(1, 8, 18)));
        int line = source.split("\n")[2].indexOf("length");
        fx.caret(b, 2, line + 2);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        Position asked =
                fx.server().definitions.get(fx.server().definitions.size() - 1).getPosition();
        assertEquals(new Position(2, source.split("\n")[2].indexOf("::") + 1), asked, "in the ::");
        assertEquals(List.of(new LspCoordinatorFixture.Jump(function, 1, 8)), fx.ops.jumps);
    }

    /**
     * A one-line field's anonymous class: the field's {@code private final} is not the method's, so jdtls is asked
     * (tenth review DO1).
     */
    @Test
    void aOneLineFieldsAnonymousMethodIsAsked() throws Exception {
        Path task = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        String source = "class A {\n    private final Task r = new Task() { public void run() { } };\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 2, 0, 6);
        DocumentSymbol field = member("r", SymbolKind.Field, 1, 1, 1, 23);
        DocumentSymbol anonymous = member("new Task() {...}", SymbolKind.Class, 1, 1, 1, 31);
        anonymous.getChildren().add(member("run()", SymbolKind.Method, 1, 1, 1, 52));
        field.getChildren().add(anonymous);
        a.getChildren().add(field);
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString(link(task, 1, 9));
        fx.caret(b, 1, 56);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(task, 1, 9)), fx.ops.jumps);
    }

    /**
     * An anonymous {@code new java.util.Comparator<>()} is prepared at its name's last segment, where jdtls answers
     * with Comparator, and a wildcard field type binds nothing (eleventh review BM1, EM1).
     */
    @Test
    void aQualifiedDiamondWithAWildcardFieldIsWalkedFromItsLastSegment() throws Exception {
        Path comparator = Files.writeString(
                root.resolve("Comparator.java"), "interface Comparator<T> {\n    int compare(T a, T b);\n}\n");
        String source =
                "class A {\n    void f() {\n        Comparator<? super String> c = new java.util.Comparator<>() {\n"
                        + "            public int compare(String a, String b) {\n                return 3;\n            }\n"
                        + "        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        int nameColumn = source.split("\n")[2].indexOf("java.util");
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 8, 0, 6);
        DocumentSymbol f = member("f()", SymbolKind.Method, 1, 7, 1, 9);
        DocumentSymbol anonymous = member("new Comparator<>() {...}", SymbolKind.Class, 2, 6, 2, nameColumn);
        anonymous.getChildren().add(member("compare(String, String)", SymbolKind.Method, 3, 5, 3, 23));
        f.getChildren().add(anonymous);
        a.getChildren().add(f);
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        DocumentSymbol comparatorType = member("Comparator<T>", SymbolKind.Interface, 0, 2, 0, 10);
        comparatorType.getChildren().add(member("compare(T, T)", SymbolKind.Method, 1, 1, 1, 8));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(comparator), List.of(Either.forRight(comparatorType)));
        fx.server().typeHierarchyResponse = List.of(new TypeHierarchyItem(
                "Comparator", SymbolKind.Interface, comparator.toUri().toString(), range(0, 0, 1), range(0, 10, 20)));
        fx.server().supertypesByName.put("Comparator", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]");
        fx.caret(b, 4, 16);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        Position prepared = fx.server().typeHierarchyPrepares.get(0).getPosition();
        assertEquals(new Position(2, source.split("\n")[2].indexOf("Comparator<>")), prepared, "at Comparator");
        assertEquals(List.of(new LspCoordinatorFixture.Jump(comparator, 1, 8)), fx.ops.jumps);
    }

    /**
     * An open supertype file is read as its buffer has it, which jdtls's positions follow: unsaved lines above its
     * header misread the disk text (eleventh review BM3).
     */
    @Test
    void anOpenSupertypeIsReadFromItsBuffer() throws Exception {
        Path store = Files.writeString(root.resolve("Store.java"), "interface Store<T> {\n    void save(T item);\n}\n");
        Path mid = Files.writeString(
                root.resolve("Mid.java"),
                "interface Mid<X> extends Store<X> {}\ninterface Wrong<Y> extends Store<java.util.List<Y>> {}\n");
        fx.open(
                "Mid.java",
                "\n\n\ninterface Mid<X> extends Store<X> {}\ninterface Wrong<Y> extends Store<java.util.List<Y>> {}\n");
        String source = "interface Leaf extends Mid<String> {\n    void save(String s);\n}\n";
        EditorBuffer b = fx.open("Leaf.java", source);
        DocumentSymbol leaf = member("Leaf", SymbolKind.Interface, 0, 2, 0, 10);
        leaf.getChildren().add(member("save(String)", SymbolKind.Method, 1, 1, 1, 9));
        fx.server().documentSymbolResponse = List.of(Either.forRight(leaf));
        DocumentSymbol midType = member("Mid<X>", SymbolKind.Interface, 3, 3, 3, 10); // the buffer's line
        fx.server().documentSymbolByUri.put(com.editora.lsp.LspTestHooks.uri(mid), List.of(Either.forRight(midType)));
        DocumentSymbol storeType = member("Store<T>", SymbolKind.Interface, 0, 2, 0, 10);
        storeType.getChildren().add(member("save(T)", SymbolKind.Method, 1, 1, 1, 9));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(store), List.of(Either.forRight(storeType)));
        String uri = root.resolve("Leaf.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("Leaf", SymbolKind.Interface, uri, range(0, 0, 1), range(0, 10, 14)));
        fx.server()
                .supertypesByName
                .put(
                        "Leaf",
                        List.of(new TypeHierarchyItem(
                                "Mid",
                                SymbolKind.Interface,
                                mid.toUri().toString(),
                                range(3, 0, 1),
                                range(3, 10, 13))));
        fx.server()
                .supertypesByName
                .put(
                        "Mid",
                        List.of(new TypeHierarchyItem(
                                "Store",
                                SymbolKind.Interface,
                                store.toUri().toString(),
                                range(0, 0, 1),
                                range(0, 10, 15))));
        fx.server().supertypesByName.put("Store", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]");
        fx.caret(b, 1, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(store, 1, 9)), fx.ops.jumps, "T is String through Mid");
    }

    /** A class's supertypes leave out java.lang.Object, as IntelliJ does: one left opens directly (twelfth review EL1). */
    @Test
    void aClassesSupertypesLeaveOutObject() throws Exception {
        Path task = Files.writeString(root.resolve("Task.java"), "interface Task {\n    void run();\n}\n");
        EditorBuffer b = fx.open("A.java", "class A implements Task {\n    public void run() {}\n}\n");
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 2, 0, 6);
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        String uri = root.resolve("A.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("A", SymbolKind.Class, uri, range(0, 0, 1), range(0, 6, 7)));
        TypeHierarchyItem object = new TypeHierarchyItem(
                "Object",
                SymbolKind.Class,
                "jdt://contents/java.base/java.lang/Object.class?=demo",
                range(37, 0, 1),
                range(37, 13, 19));
        object.setDetail("java.lang");
        TypeHierarchyItem taskType = new TypeHierarchyItem(
                "Task", SymbolKind.Interface, task.toUri().toString(), range(0, 0, 1), range(0, 10, 14));
        fx.server().supertypesResponse = List.of(object, taskType);
        fx.caret(b, 0, 7);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();
        assertEquals(List.of(new LspCoordinatorFixture.Jump(task, 0, 10)), fx.ops.jumps, "no picker with Object in it");

        fx.server().supertypesResponse = List.of(object);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();
        assertEquals(1, fx.ops.jumps.size(), "a plain class: nothing to open");
        assertEquals(tr("status.lsp.noSuperType"), fx.host.lastStatus());
    }

    /**
     * A raw anonymous class assigned to a parameterized variable is raw: save(Object) overrides Store.save, the
     * variable's Store<String> does not count (twelfth review BM4).
     */
    @Test
    void aRawAnonymousClassIsRawWhateverItsVariableSays() throws Exception {
        Path store = Files.writeString(root.resolve("Store.java"), "interface Store<T> {\n    void save(T item);\n}\n");
        String source = "class A {\n    void f() {\n        Store<String> raw = new Store() {\n"
                + "            public void save(Object o) {}\n        };\n    }\n}\n";
        EditorBuffer b = fx.open("A.java", source);
        DocumentSymbol a = member("A", SymbolKind.Class, 0, 6, 0, 6);
        DocumentSymbol f = member("f()", SymbolKind.Method, 1, 5, 1, 9);
        int name = source.split("\n")[2].indexOf("Store()");
        DocumentSymbol anonymous = member("new Store() {...}", SymbolKind.Class, 2, 4, 2, name);
        anonymous.getChildren().add(member("save(Object)", SymbolKind.Method, 3, 3, 3, 24));
        f.getChildren().add(anonymous);
        a.getChildren().add(f);
        fx.server().documentSymbolResponse = List.of(Either.forRight(a));
        DocumentSymbol storeType = member("Store<T>", SymbolKind.Interface, 0, 2, 0, 10);
        storeType.getChildren().add(member("save(T)", SymbolKind.Method, 1, 1, 1, 9));
        fx.server()
                .documentSymbolByUri
                .put(com.editora.lsp.LspTestHooks.uri(store), List.of(Either.forRight(storeType)));
        fx.server().typeHierarchyResponse = List.of(new TypeHierarchyItem(
                "Store", SymbolKind.Interface, store.toUri().toString(), range(0, 0, 1), range(0, 10, 15)));
        fx.server().supertypesByName.put("Store", List.of());
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]");
        fx.caret(b, 3, 26);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(store, 1, 9)), fx.ops.jumps);
    }

    /**
     * A jar class without sources names no type parameters in its outline: its decompiled header gives them, so a
     * descriptive type variable binds (LKeyed<Key>) and an overload with another type is none (twelfth review DL2,
     * DO2).
     */
    @Test
    void aJarClassesTypeParametersAreReadFromItsDecompiledHeader() throws Exception {
        String keyed = "jdt://contents/libbase.jar/lib/LKeyed.class?=demo";
        String source = "interface MyKeyed extends lib.LKeyed<String> {\n    void use(String k);\n"
                + "    void use(Integer k);\n}\n";
        EditorBuffer b = fx.open("MyKeyed.java", source);
        DocumentSymbol mine = member("MyKeyed", SymbolKind.Interface, 0, 3, 0, 10);
        mine.getChildren().add(member("use(String)", SymbolKind.Method, 1, 1, 1, 9));
        mine.getChildren().add(member("use(Integer)", SymbolKind.Method, 2, 2, 2, 9));
        fx.server().documentSymbolResponse = List.of(Either.forRight(mine));
        DocumentSymbol lib = member("LKeyed", SymbolKind.Interface, 0, 2, 0, 17);
        lib.getChildren().add(member("use(Key)", SymbolKind.Method, 1, 1, 1, 9));
        fx.server().documentSymbolByUri.put(keyed, List.of(Either.forRight(lib)));
        String uri = root.resolve("MyKeyed.java").toUri().toString();
        fx.server().typeHierarchyResponse =
                List.of(new TypeHierarchyItem("MyKeyed", SymbolKind.Interface, uri, range(0, 0, 1), range(0, 10, 17)));
        fx.server()
                .supertypesByName
                .put(
                        "MyKeyed",
                        List.of(new TypeHierarchyItem(
                                "LKeyed", SymbolKind.Interface, keyed, range(0, 0, 1), range(0, 17, 23))));
        fx.server().supertypesByName.put("LKeyed", List.of());
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        fx.server().rawHandler = raw -> {
            asked.add(raw.method());
            return raw.method().equals("java/classFileContents")
                    ? new com.google.gson.JsonPrimitive("public interface LKeyed<Key> {\n    void use(Key k);\n}\n")
                    : com.google.gson.JsonParser.parseString("[]");
        };
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();
        assertTrue(asked.contains("java/classFileContents"), "the header read: " + asked);
        assertEquals(tr("status.lsp.noSuperMethod"), fx.host.lastStatus(), "Key is String: use(Integer) is none");
        assertTrue(fx.ops.readOnlyDocs.isEmpty());

        fx.caret(b, 1, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();
        assertEquals(1, fx.ops.readOnlyDocs.size(), "use(String) opens LKeyed.use");
    }
}
