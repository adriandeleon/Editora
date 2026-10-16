package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Toggle Comment in a shell buffer leaves a here-doc's data alone (the shell review's here-doc low). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShellCommentFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void toggleCommentInsideAHereDocChangesNothing() throws Exception {
        String sh = "f() {\n    cat <<EOF\n    Usage: f NAME\nEOF\n}\n";
        Object[] out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("shell");
            b.setContent(sh);
            b.getNode();
            b.getArea().moveTo(sh.indexOf("Usage"));
            boolean toggled = b.toggleComment();
            return new Object[] {toggled, b.getArea().getText()};
        });
        assertFalse((Boolean) out[0]);
        assertEquals(sh, out[1], "a # in the body would be printed as part of the usage text");
    }
}
