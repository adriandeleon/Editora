package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.i18n.Messages;
import com.editora.plugin.PluginDescriptor;
import com.editora.plugin.PluginDescriptor.LoadProblem;
import com.editora.plugin.PluginManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin that failed to load is explained in the user's language, not in the loader's English. */
class PluginLoadProblemsTest {

    @AfterEach
    void english() {
        Messages.init("en");
    }

    private static PluginDescriptor failed(LoadProblem problem) {
        PluginManifest m = new PluginManifest();
        m.id = "broken";
        return PluginDescriptor.withProblem(m, Path.of("plugins", "broken"), false, null, problem);
    }

    @Test
    void eachKindHasItsOwnSentenceInEveryLanguage() {
        PluginDescriptor at =
                failed(new LoadProblem(LoadProblem.Kind.MANIFEST_INVALID, List.of("4", "2"), "Unexpected '}'"));
        PluginDescriptor id = failed(new LoadProblem(LoadProblem.Kind.ID_NOT_ALLOWED, List.of("../evil"), ""));
        PluginDescriptor code = failed(new LoadProblem(LoadProblem.Kind.CODE_NOT_LOADED, List.of(), "zip END header"));
        PluginDescriptor io = failed(new LoadProblem(LoadProblem.Kind.MANIFEST_UNREADABLE, List.of(), "denied"));
        for (String lang : List.of("en", "de", "es", "fr", "it", "pt")) {
            Messages.init(lang);
            assertEquals(
                    Messages.tr("settings.plugins.error.manifestInvalidAt", "4", "2"), PluginLoadProblems.text(at));
            assertEquals(Messages.tr("settings.plugins.error.idNotAllowed", "../evil"), PluginLoadProblems.text(id));
            assertEquals(Messages.tr("settings.plugins.error.codeNotLoaded"), PluginLoadProblems.text(code));
            assertEquals(Messages.tr("settings.plugins.error.manifestUnreadable"), PluginLoadProblems.text(io));
        }
        Messages.init("de");
        assertTrue(PluginLoadProblems.text(at).contains("Zeile 4"), PluginLoadProblems.text(at));
        assertEquals("Unexpected '}'", PluginLoadProblems.detail(at), "the parser's own words go to the tooltip");
        assertNull(PluginLoadProblems.detail(id));
        assertTrue(at.loadError().startsWith("Invalid plugin.json"), "logs keep the English text");
    }
}
