package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class EditorBufferSavedContentFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void acknowledgingAnOlderSavedSnapshotDoesNotClearLaterEdits() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("saved before request");
            b.replaceWholeDocument("edited while save ran");
            b.acknowledgeSavedContent("saved before request");
            return b;
        });

        assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("saved before request"));
        assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
    }
}
