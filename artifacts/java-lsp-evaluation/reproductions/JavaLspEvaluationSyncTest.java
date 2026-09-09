package com.editora.lsp;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JavaLspEvaluationSyncTest {
    @Test void reopenDuringInitializeMustKeepShadowInWireOrder() {
        var s = new LanguageServerSession(new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of()), Path.of("/tmp"), d -> {}, (t,m) -> {});
        try {
            String uri = "file:///tmp/A.java";
            s.didOpen(uri, "java", "class A {}");
            s.didChange(uri, "class B {}");
            s.didClose(uri);
            s.didOpen(uri, "java", "class A {}");
            var fake = new FakeLanguageServer();
            var caps = new ServerCapabilities();
            caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
            s.attachForTest(fake, caps);
            s.didChange(uri, "class B {}");
            System.out.println("EVALUATION reopen-sync changes=" + fake.changed.size());
            assertEquals(2, fake.changed.size(), "final A-to-B edit after reopen must reach the server");
        } finally { s.dispose(); }
    }
}
