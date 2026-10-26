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
                new Range(new Position(start, 0), new Position(end, 1)),
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
        fx.server().rawHandler = raw -> com.google.gson.JsonParser.parseString("[]"); // findLinks: nothing
        fx.caret(b, 2, 10);
        fx.run(() -> fx.coordinator.gotoSuperMethod());
        fx.settle();

        assertEquals(List.of(new LspCoordinatorFixture.Jump(shape, 1, 11)), fx.ops.jumps);
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
}
