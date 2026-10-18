package com.editora.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.editora.process.TargetCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Editora's bash/zsh integration scripts: where they go, how a shell's cwd report is read. */
class ShellIntegrationTest {

    @Test
    void aCwdReportIsAFileUrlOrABarePath() {
        assertEquals("/home/ana/my app", ShellIntegration.pathOfCwdUrl("file://box/home/ana/my%20app"));
        assertEquals("/tmp", ShellIntegration.pathOfCwdUrl("file:///tmp"));
        assertEquals("/srv/x", ShellIntegration.pathOfCwdUrl("/srv/x"));
        assertEquals("/a b", ShellIntegration.pathOfCwdUrl("file://h/a b"), "an unencoded space from a lax shell");
        assertNull(ShellIntegration.pathOfCwdUrl("https://example.com/"));
        assertNull(ShellIntegration.pathOfCwdUrl(""));
        assertNull(ShellIntegration.pathOfCwdUrl(null));
    }

    @Test
    void theScriptsAreInstalledOnceInAFolderNamedByTheirContents(@TempDir Path config) throws IOException {
        Path folder = ShellIntegration.installLocal(config);
        assertEquals(ShellIntegration.version(), folder.getFileName().toString());
        for (String name : ShellIntegration.FILES) {
            assertEquals(ShellIntegration.scripts().get(name), Files.readString(folder.resolve(name)), name);
        }
        long modified =
                Files.getLastModifiedTime(folder.resolve("editora.bash")).toMillis();
        assertEquals(folder, ShellIntegration.installLocal(config), "the same folder again");
        assertEquals(
                modified,
                Files.getLastModifiedTime(folder.resolve("editora.bash")).toMillis(),
                "not rewritten");
    }

    @Test
    void onATargetTheyGoInItsCacheAndTheFolderIsNamedAsTheTargetNamesIt() throws IOException {
        Map<String, String> written = new HashMap<>();
        List<String> order = new ArrayList<>();
        TargetCache cache = new TargetCache() {
            @Override
            public String makeDirectories(String relative) {
                return "/home/ana/.cache/editora/" + relative;
            }

            @Override
            public String putFile(String relative, Path localFile) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String putText(String relative, String text) {
                written.put(relative, text);
                order.add(relative);
                return "/home/ana/.cache/editora/" + relative;
            }

            @Override
            public void delete(String relative) {}
        };
        String folder = ShellIntegration.installOn(cache);
        assertEquals("/home/ana/.cache/editora/shell-integration/" + ShellIntegration.version(), folder);
        assertEquals(ShellIntegration.FILES.size(), written.size());
        order.forEach(TargetCache::checked); // every name is a valid cache name (throws otherwise)
    }

    @Test
    void theBashScriptEmitsTheMarksItIsMeantTo() {
        String bash = ShellIntegration.scripts().get("editora.bash");
        assertTrue(bash.contains("133;A") && bash.contains("133;C") && bash.contains("133;D") && bash.contains("]7;"));
        assertTrue(bash.contains("/etc/profile") && bash.contains(".bash_profile"), "it reads the login files itself");
        String zsh = ShellIntegration.scripts().get("zsh/.zshrc");
        assertTrue(zsh.contains("add-zsh-hook precmd") && zsh.contains("133;C"));
    }
}
