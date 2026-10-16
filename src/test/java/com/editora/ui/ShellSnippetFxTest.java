package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.snippet.SnippetManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** B9: the bundled shell snippets, expanded by Tab, follow the script's shebang. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShellSnippetFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Types {@code trigger} at the end of {@code content}, presses Tab, returns the text. */
    private static String expand(Path config, String content, String trigger) throws Exception {
        SnippetManager snippets = new SnippetManager(new ConfigManager(config));
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("shell");
            b.setSnippetProvider(snippets::byTabTrigger);
            b.setContent(content);
            b.getNode();
            b.getArea().moveTo(content.length());
            b.typeString(trigger);
            b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            return b.getArea().getText();
        });
    }

    @Test
    void anIfInAPosixShScriptUsesSingleBrackets(@TempDir Path config) throws Exception {
        // dash, the /bin/sh of Debian and Ubuntu, says `[[: not found` and runs the branch anyway.
        assertEquals("#!/bin/sh\nif [ condition ]; then\n    \nfi", expand(config, "#!/bin/sh\n", "if"));
        assertEquals(
                "#!/usr/bin/env bash\nif [[ condition ]]; then\n    \nfi",
                expand(config, "#!/usr/bin/env bash\n", "if"));
        assertEquals("#!/bin/sh\nwhile [ condition ]; do\n    \ndone", expand(config, "#!/bin/sh\n", "while"));
    }
}
