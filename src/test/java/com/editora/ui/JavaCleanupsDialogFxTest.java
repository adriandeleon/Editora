package com.editora.ui;

import java.util.List;

import javafx.scene.control.CheckBox;

import com.editora.config.Settings;
import com.editora.lsp.JavaCleanups;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class JavaCleanupsDialogFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void everyCleanUpIsOneLabelledBoxAndTheTickedOnesComeBackInOrder() throws Exception {
        List<CheckBox> boxes = FxTestSupport.callOnFx(() -> JavaCleanupsDialog.boxes(Settings.DEFAULT_JAVA_CLEANUPS));
        assertEquals(JavaCleanups.ALL.size(), boxes.size());
        for (CheckBox box : boxes) {
            String id = (String) box.getUserData();
            assertEquals(tr("settings.javaCleanup." + id), box.getText());
            assertFalse(box.getText().startsWith("settings."), "every clean-up has a label: " + id);
            assertEquals(Settings.DEFAULT_JAVA_CLEANUPS.contains(id), box.isSelected(), id);
        }
        FxTestSupport.runOnFx(() -> {
            boxes.get(0).setSelected(false); // addOverride
            boxes.get(boxes.size() - 1).setSelected(true); // renameUnusedLocalVariables
        });
        assertEquals(
                List.of("addDeprecated", "redundantModifiers", "redundantSuperCall", "renameUnusedLocalVariables"),
                JavaCleanupsDialog.ticked(boxes));
    }

    @Test
    void onlyKnownCleanUpsAreSentAndInTheCatalogsOrder() {
        assertEquals(
                List.of("addOverride", "tryWithResource"),
                JavaCleanups.known(List.of("tryWithResource", "nope", "addOverride")));
        assertTrue(JavaCleanups.known(null).isEmpty());
        assertFalse(JavaCleanups.ALL.contains("organizeImports"), "it has a setting of its own");
    }
}
