package com.editora.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.editora.editor.LanguageRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@code packaging/windows/file-associations.properties} — the extensions the Windows MSI registers
 * under Explorer's "Open with".
 *
 * <p>Nothing at build time checks that file: jpackage takes whatever it is handed, so a stale entry ships an
 * installer claiming a type Editora no longer understands, and a language added later is simply never
 * offered. Neither failure is visible without a Windows machine and a manual right-click — exactly the kind
 * of packaging detail that has gone unnoticed here before.
 *
 * <p>So the list is pinned to the two things that make it meaningful: every extension resolves to a real
 * language, and the types deliberately left to other applications stay out.
 */
class WindowsFileAssociationsTest {

    /** Left to the browser and the image viewer — the same call the Linux .deb's postinst makes. */
    private static final Set<String> DELIBERATELY_EXCLUDED = Set.of("html", "htm", "xhtml", "svg");

    private static final Path FILE = Path.of("packaging", "windows", "file-associations.properties");

    private static Properties load() throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(FILE)) {
            props.load(in);
        }
        return props;
    }

    private static List<String> extensions() throws IOException {
        String value = load().getProperty("extension", "").trim();
        List<String> out = new ArrayList<>();
        for (String e : value.split("\\s+")) {
            if (!e.isBlank()) {
                out.add(e);
            }
        }
        return out;
    }

    @Test
    void theAssociationsFileIsPresentAndComplete() throws IOException {
        assertTrue(Files.isRegularFile(FILE), FILE + " is missing — the MSI would register no associations");
        Properties props = load();
        // jpackage's parser needs all three; a missing one is accepted silently and yields a useless entry.
        assertFalse(props.getProperty("mime-type", "").isBlank(), "mime-type is required by jpackage");
        assertFalse(props.getProperty("description", "").isBlank(), "description shows in Explorer");
        assertFalse(extensions().isEmpty(), "no extensions listed");
    }

    @Test
    void everyAssociatedExtensionResolvesToALanguageEditoraSupports() throws IOException {
        List<String> unknown = new ArrayList<>();
        for (String ext : extensions()) {
            // forFileName is the resolver the editor itself uses, so this asks the real question: would
            // opening such a file actually give a recognised language rather than plain text?
            if (LanguageRegistry.PLAINTEXT.equals(LanguageRegistry.forFileName("sample." + ext))) {
                unknown.add(ext);
            }
        }
        assertTrue(
                unknown.isEmpty(),
                "these extensions are advertised to Windows but resolve to plaintext — either drop them or "
                        + "teach LanguageRegistry about them: " + unknown);
    }

    @Test
    void theTypesLeftToOtherApplicationsAreNotClaimed() throws IOException {
        List<String> claimed = new ArrayList<>(extensions());
        claimed.retainAll(DELIBERATELY_EXCLUDED);
        assertTrue(
                claimed.isEmpty(),
                "these belong to the browser / image viewer and are excluded on Linux too, so Windows must "
                        + "not claim them either: " + claimed);
    }

    @Test
    void theListHasNoDuplicates() throws IOException {
        List<String> all = extensions();
        Set<String> unique = new LinkedHashSet<>(all);
        assertEquals(unique.size(), all.size(), "duplicate extensions would register the same type twice");
    }
}
