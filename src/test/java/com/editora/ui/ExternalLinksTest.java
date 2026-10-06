package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.ui.ExternalLinks.Browser;
import com.editora.ui.ExternalLinks.InEditor;
import com.editora.ui.ExternalLinks.Refused;
import com.editora.ui.ExternalLinks.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The link choke point: only {@code http}/{@code https}/{@code mailto} reach the OS opener; a relative or
 * {@code file:} link opens inside the editor when it is a regular file under the project or the document's
 * folder; everything else is refused with a message. Nothing scheme-less is ever handed to the OS.
 */
class ExternalLinksTest {

    @BeforeAll
    static void messages() {
        com.editora.i18n.Messages.init("en"); // open() localizes its refusal message
    }

    private static String refusal(Target t) {
        return assertInstanceOf(Refused.class, t, String.valueOf(t)).messageKey();
    }

    @Test
    void webAndMailLinksGoToTheBrowser() {
        assertEquals(
                new Browser("https://example.com/a?b=c#d"),
                ExternalLinks.classify("https://example.com/a?b=c#d", null, null));
        assertEquals(
                new Browser("http://127.0.0.1:8123/tok/index.html"),
                ExternalLinks.classify("http://127.0.0.1:8123/tok/index.html", null, null));
        assertEquals(new Browser("HTTPS://Example.com"), ExternalLinks.classify("  HTTPS://Example.com  ", null, null));
        assertEquals(
                new Browser("mailto:someone@example.com"),
                ExternalLinks.classify("mailto:someone@example.com", null, null));
        assertEquals(
                new Browser("https://example.com/a%20b|c"),
                ExternalLinks.classify("https://example.com/a b|c", null, null),
                "a space is encoded; other characters the OS opener accepts are left alone");
        assertEquals(
                new Browser("https://example.com/%22%20x"),
                ExternalLinks.classify("https://example.com/\" x", null, null),
                "a quote cannot split the opener's argument");
    }

    @Test
    void everyOtherSchemeIsRefused() {
        for (String link : List.of(
                "smb://attacker/share",
                "ssh://host",
                "javascript:alert(1)",
                "vbscript:x",
                "data:text/html,<script>1</script>",
                "ms-msdt:/id PCWDiagnostic",
                "vscode://file/etc/passwd",
                "x-apple.systempreferences:com.apple.preference",
                "ftp://example.com/a",
                "jar:file:///a.jar!/x",
                "tel:+15550100")) {
            assertEquals(ExternalLinks.KEY_SCHEME, refusal(ExternalLinks.classify(link, null, null)), link);
        }
    }

    @Test
    void malformedWebLinksAndControlCharactersAreRefused() {
        assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify(null, null, null)));
        assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("   ", null, null)));
        assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("https://", null, null)));
        assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("http:/example.com", null, null)));
        assertEquals(
                ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("https://exa\u0000mple.com", null, null)));
        assertEquals(
                ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("https://example.com/\r\nX: y", null, null)));
        assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("mailto:", null, null)));
    }

    /** Before the choke point these went to {@code showDocument}, i.e. {@code open}/{@code xdg-open} with a
     *  bare string: resolved against the process cwd, or read as an option ({@code -a Calculator}). */
    @Test
    void aSchemelessStringWithNoDocumentIsNeverOpened() {
        for (String link : List.of("setup.command", "./run.sh", "-a Calculator", "--help", "www.example.com", "#top")) {
            assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify(link, null, null)), link);
        }
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify("/etc/passwd", null, null)));
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify("file:///etc/passwd", null, null)));
    }

    @Test
    void aRelativeLinkOpensInTheEditorWhenItIsAFileNextToTheDocument(@TempDir Path tmp) throws IOException {
        Path docs = Files.createDirectories(tmp.resolve("notes"));
        Path readme = Files.writeString(docs.resolve("README.md"), "# hi");
        Path other = Files.writeString(docs.resolve("other file.md"), "x");
        Path nested =
                Files.writeString(Files.createDirectories(docs.resolve("sub")).resolve("deep.md"), "x");
        assertEquals(new InEditor(other), ExternalLinks.classify("other%20file.md", readme, null));
        assertEquals(new InEditor(other), ExternalLinks.classify("./other%20file.md#section", readme, null));
        assertEquals(new InEditor(nested), ExternalLinks.classify("sub/deep.md?plain=1", readme, null));
        assertEquals(new InEditor(other), ExternalLinks.classify("sub/../other file.md", readme, null));
        assertEquals(new InEditor(other), ExternalLinks.classify(other.toUri().toString(), readme, null), "file: URL");
        assertEquals(ExternalLinks.KEY_MISSING, refusal(ExternalLinks.classify("nope.md", readme, null)));
        assertEquals(ExternalLinks.KEY_MISSING, refusal(ExternalLinks.classify("sub", readme, null)), "a folder");
        assertEquals(ExternalLinks.KEY_INVALID, refusal(ExternalLinks.classify("#heading", readme, null)));
    }

    @Test
    void aLinkOutOfTheDocumentFolderNeedsTheProjectToContainIt(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path docs = Files.createDirectories(project.resolve("docs"));
        Path guide = Files.writeString(docs.resolve("guide.md"), "x");
        Path license = Files.writeString(project.resolve("LICENSE"), "MIT");
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "s");
        // inside the project: fine from anywhere in it
        assertEquals(new InEditor(license), ExternalLinks.classify("../LICENSE", guide, project));
        // "/docs/guide.md" means "from the repository root" on every forge
        assertEquals(
                new InEditor(guide), ExternalLinks.classify("/docs/guide.md", project.resolve("README.md"), project));
        // out of the document's folder with no project: refused, existing or not (no probing)
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify("../LICENSE", guide, null)));
        // out of the project: refused
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify("../../secret.txt", guide, project)));
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify(secret.toString(), guide, project)));
        assertEquals(
                ExternalLinks.KEY_OUTSIDE,
                refusal(ExternalLinks.classify(secret.toUri().toString(), guide, project)));
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify("../../not-there.txt", guide, project)));
    }

    @Test
    void aSymlinkInTheProjectDoesNotReachOutOfIt(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path readme = Files.writeString(project.resolve("README.md"), "x");
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "s");
        try {
            Files.createSymbolicLink(project.resolve("setup.md"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }
        assertEquals(ExternalLinks.KEY_OUTSIDE, refusal(ExternalLinks.classify("setup.md", readme, project)));
    }

    @Test
    void uncAndRemoteFileLinksAreRefusedWithoutTouchingThem(@TempDir Path tmp) throws IOException {
        Path readme = Files.writeString(tmp.resolve("README.md"), "x");
        for (String link : List.of(
                "file://attacker/share/setup.exe",
                "file:////attacker/share/x",
                "//attacker/share/x",
                "\\\\attacker\\share\\x",
                "file:///%5C%5Cattacker/share")) {
            Target t = ExternalLinks.classify(link, readme, tmp);
            assertInstanceOf(Refused.class, t, link);
        }
    }

    @Test
    void openDispatchesEachDecisionToExactlyOneSink(@TempDir Path tmp) throws IOException {
        Path readme = Files.writeString(tmp.resolve("README.md"), "x");
        Path other = Files.writeString(tmp.resolve("other.md"), "x");
        List<String> browser = new ArrayList<>();
        List<Path> editor = new ArrayList<>();
        List<String> status = new ArrayList<>();
        Consumer<String> os = browser::add;

        ExternalLinks.open("https://example.com", readme, tmp, os, editor::add, status::add);
        ExternalLinks.open("other.md", readme, tmp, os, editor::add, status::add);
        ExternalLinks.open("file:///etc/passwd", readme, tmp, os, editor::add, status::add);
        ExternalLinks.open("smb://attacker/share", readme, tmp, os, editor::add, status::add);
        ExternalLinks.open("setup.command", null, null, os, editor::add, status::add);

        assertEquals(List.of("https://example.com"), browser, "only the web link reached the OS opener");
        assertEquals(List.of(other), editor);
        assertEquals(3, status.size(), status.toString());
        assertTrue(status.get(1).contains("smb"), status.get(1));
        ExternalLinks.open(null, readme, tmp, os, editor::add, status::add); // an item with no URL: silent
        assertEquals(3, status.size());
        // no OS opener available (HostServices not set yet): the link is dropped, not misrouted
        ExternalLinks.open("https://example.com", readme, tmp, (Consumer<String>) null, editor::add, status::add);
        assertEquals(1, editor.size());
        assertEquals(3, status.size());
    }

    @Test
    void percentDecodingIsUtf8AndLeavesMalformedEscapesAlone() {
        assertEquals("a b", ExternalLinks.percentDecode("a%20b"));
        assertEquals("café.md", ExternalLinks.percentDecode("caf%C3%A9.md"));
        assertEquals("a+b", ExternalLinks.percentDecode("a+b"));
        assertEquals("100%.md", ExternalLinks.percentDecode("100%.md"));
        assertEquals("%zz", ExternalLinks.percentDecode("%zz"));
    }
}
