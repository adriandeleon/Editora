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
