package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.JavaLibrarySources;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A decompiled library class says it has no sources and offers them; when jdtls attaches them later (its own
 * download, or one asked for), the open tab shows them.
 */
@Tag("fx")
class LibrarySourcesFxTest {

    private static final String JAR = "/home/u/.m2/lib-1.0.jar";
    private static final String URI = "jdt://contents/lib-1.0.jar/p/C.class?=demo";

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

    @Test
    void aDecompiledClassOffersItsSourcesAndShowsThemOnceAttached() throws Exception {
        Path sources = Files.writeString(root.resolve("lib-1.0-sources.jar"), "");
        String[] attachedPath = {null};
        EditorBuffer a = fx.open("A.java", "class A {}\n");
        fx.ops.readOnlyOpener = doc -> {
            EditorBuffer library = new EditorBuffer();
            library.setContent(doc.content());
            fx.show(library);
            return library;
        };
        fx.server().rawResponse = "package p;\n\npublic class C {\n    // decompiled\n}\n";
        fx.server().definitionResponse =
                List.of(new Location(URI, new Range(new Position(2, 13), new Position(2, 14))));
        fx.server().executeCommandHandler = p -> p.getCommand().equals(JavaLibrarySources.RESOLVE)
                ? Map.of(
                        "attributes",
                        attachedPath[0] == null
                                ? Map.of("jarPath", JAR)
                                : Map.of("jarPath", JAR, "sourceAttachmentPath", attachedPath[0]))
                : null;

        fx.run(() -> fx.coordinator.gotoDefinition());
        fx.settle();
        EditorBuffer library = fx.ops.readOnlyDocs.get(0).buffer();
        assertEquals(tr("librarySources.none", "lib-1.0.jar"), FxTestSupport.callOnFx(library::fileNoticeText));

        attachedPath[0] = sources.toString(); // jdtls's download finished
        fx.server().rawResponse = "package p;\n\n/** The real thing. */\npublic class C {\n    int real;\n}\n";
        LspTestHooks.sourceInvalidated(fx.manager, a.getPath(), JAR);
        for (int i = 0; i < 100 && FxTestSupport.callOnFx(library::fileNoticeText) != null; i++) {
            Thread.sleep(10);
            fx.settle();
        }
        assertEquals(
                fx.server().rawResponse,
                FxTestSupport.callOnFx(() -> library.getArea().getText()));
        assertNull(FxTestSupport.callOnFx(library::fileNoticeText), "the bar is gone");
        assertEquals(tr("status.librarySources.attached", "lib-1.0.jar"), fx.host.lastStatus());
    }
}
