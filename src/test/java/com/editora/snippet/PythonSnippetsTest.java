package com.editora.snippet;

import java.nio.file.Path;

import com.editora.config.ConfigManager;
import com.editora.template.NewFileCatalog;
import com.editora.template.NewFileContent;
import com.editora.template.NewFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled Python snippets and the New ▸ Python file are current Python (review finding A10). */
class PythonSnippetsTest {

    @Test
    void theSnippetBodiesAreModernPython(@TempDir Path dir) {
        SnippetManager m = new SnippetManager(new ConfigManager(dir));
        assertEquals("#!/usr/bin/env python3$0", m.byPrefix("python", "#env").body());
        String classi = m.byPrefix("python", "classi").body();
        assertFalse(classi.contains("super("), classi);
        assertTrue(classi.startsWith("class ${1:ClassName}:"), "no empty () after the name: " + classi);
        for (String trigger : new String[] {"try", "trya", "tryf", "exceptas"}) {
            String body = m.byPrefix("python", trigger).body();
            assertFalse(body.contains("raise $"), trigger + " re-raises with a bare raise: " + body);
        }
    }

    @Test
    void newPythonFileStartsLikeThePythonScriptTemplate() {
        NewFileType python = NewFileCatalog.all().stream()
                .filter(t -> t.id().equals("scripts.python"))
                .findFirst()
                .orElseThrow();
        NewFileContent.Rendered r = NewFileContent.render(python, "tool", "");
        assertTrue(r.text().startsWith("#!/usr/bin/env python3\n\"\"\"Module summary.\"\"\"\n"), r.text());
        assertTrue(r.text().contains("\nif __name__ == \"__main__\":\n    main()\n"), r.text());
        assertEquals(r.text().indexOf("def main() -> None:\n    ") + "def main() -> None:\n    ".length(), r.caret());
    }
}
