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
 * <p>So the list is pinned to the things that make it meaningful: every extension resolves to a real
 * language, the types deliberately left to other applications stay out, and — because jpackage registers
 * each listed extension's <em>class</em> machine-wide rather than merely adding an "Open with" entry — no
 * type that Windows runs on double-click is claimed.
 */
class WindowsFileAssociationsTest {

    /** Left to the browser and the image viewer — the same call the Linux .deb's postinst makes. */
    private static final Set<String> DELIBERATELY_EXCLUDED = Set.of("html", "htm", "xhtml", "svg");

    /**
     * Types whose normal action on Windows is to <b>run</b> the file — through cmd.exe, Windows Script Host,
     * PowerShell, or an interpreter whose installer registers itself machine-wide (the Python launcher,
     * RubyInstaller, Perl, Git for Windows' bash) — plus the shell's own executable and shortcut types.
     *
     * <p>jpackage's MSI points {@code HKLM\Software\Classes\.<ext>} at Editora's ProgId for every listed
     * extension and deletes the key on uninstall. For a type with no per-user choice that replaces the
     * existing machine-wide class: double-clicking a {@code .bat} would open an editor instead of running
     * it, for every user of the machine, and after an uninstall the type would have no class at all. The
     * first nine were in the list until this guard existed; the rest must never be added.
     */
    private static final Set<String> RUN_ON_OPEN = Set.of(
            "bat", "cmd", "js", "ps1", "psm1", "py", "pyw", "rb", "sh", // removed from the list
            "jse", "vbs", "vbe", "wsf", "wsh", "hta", "psd1", "pyz", "pyc", "rbw", "pl", "reg", "msi", "exe", "com",
            "scr", "lnk", "url", "jar");

    /**
     * The one entry that is not a language: {@code txt}, which must come first. jpackage registers the first
     * extension it meets as the system default extension for the association's MIME type, so an
     * alphabetically sorted list told Windows that {@code text/plain} content is an {@code .automount} file.
     */
    private static final String PLAIN_TEXT_ANCHOR = "txt";

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
            if (PLAIN_TEXT_ANCHOR.equals(ext)) {
                continue; // plain text by definition; see theFirstExtensionIsTheCanonicalOneForTextPlain
            }
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
    void noTypeThatWindowsRunsOnDoubleClickIsClaimed() throws IOException {
        List<String> claimed = new ArrayList<>(extensions());
        claimed.retainAll(RUN_ON_OPEN);
        assertTrue(
                claimed.isEmpty(),
                "the MSI registers each listed extension's class machine-wide, so these would stop RUNNING on "
                        + "double-click and open in Editora instead (and lose their class on uninstall): "
                        + claimed);
    }

    @Test
    void theFirstExtensionIsTheCanonicalOneForTextPlain() throws IOException {
        assertEquals("text/plain", load().getProperty("mime-type"));
        assertEquals(
                PLAIN_TEXT_ANCHOR,
                extensions().getFirst(),
                "jpackage makes the FIRST extension the system default extension for the mime-type");
    }

    @Test
    void theHeaderNoLongerPromisesThatDefaultsCannotBeTakenOver() throws IOException {
        String text = Files.readString(FILE);
        assertFalse(
                text.contains("does NOT hijack"),
                "the MSI can take over a type that has no per-user choice; the file must not claim otherwise");
    }

    @Test
    void theListHasNoDuplicates() throws IOException {
        List<String> all = extensions();
        Set<String> unique = new LinkedHashSet<>(all);
        assertEquals(unique.size(), all.size(), "duplicate extensions would register the same type twice");
    }
}
