package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class GrammarRegistryTest {

    @Test
    void forLanguageNameResolvesBundledLanguages() {
        assertNotNull(GrammarRegistry.shared().forLanguageName("java"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("python"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("csharp"));
    }

    @Test
    void forLanguageNameIsCaseInsensitive() {
        assertNotNull(GrammarRegistry.shared().forLanguageName("Java"));
    }

    @Test
    void forLanguageNameUnknownOrNullIsNull() {
        assertNull(GrammarRegistry.shared().forLanguageName("klingon"));
        assertNull(GrammarRegistry.shared().forLanguageName(null));
        assertNull(GrammarRegistry.shared().forLanguageName("plaintext"));
    }

    @Test
    void systemdUnitFilesLoadTheSystemdGrammar() {
        // Loads the bundled systemd.tmLanguage.json end-to-end (JSON parse + Oniguruma compile).
        assertNotNull(GrammarRegistry.shared().forLanguageName("systemd"));
        assertNotNull(GrammarRegistry.shared().forFileName("foo.service"));
        assertNotNull(GrammarRegistry.shared().forFileName("/etc/systemd/system/web.socket"));
        assertNotNull(GrammarRegistry.shared().forFileName("backup.timer"));
        assertNotNull(GrammarRegistry.shared().forFileName("eth0.network"));
    }

    @Test
    void linuxConfigGrammarsLoad() {
        // Each loads end-to-end (JSON parse + Oniguruma compile) and resolves by name/extension.
        assertNotNull(GrammarRegistry.shared().forLanguageName("desktop"));
        assertNotNull(GrammarRegistry.shared().forFileName("app.desktop"));
        assertNotNull(GrammarRegistry.shared().forFileName("mime.directory"));

        assertNotNull(GrammarRegistry.shared().forLanguageName("dotenv"));
        assertNotNull(GrammarRegistry.shared().forFileName(".env"));
        assertNotNull(GrammarRegistry.shared().forFileName(".env.production"));
        assertNotNull(GrammarRegistry.shared().forFileName("config.env"));

        assertNotNull(GrammarRegistry.shared().forLanguageName("ssh-config"));
        assertNotNull(GrammarRegistry.shared().forFileName("ssh_config"));
        assertNotNull(GrammarRegistry.shared().forFileName("sshd_config"));
        // A bare "config" must NOT be treated as ssh-config (too generic on basename alone).
        assertNull(GrammarRegistry.shared().forFileName("config"));
    }

    @Test
    void moreLinuxConfigGrammarsLoad() {
        // caddyfile (MIT), crontab + git-config (TextMate, plist->JSON), in-house fstab/hosts.
        assertNotNull(GrammarRegistry.shared().forLanguageName("caddyfile"));
        assertNotNull(GrammarRegistry.shared().forFileName("Caddyfile"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("crontab"));
        assertNotNull(GrammarRegistry.shared().forFileName("backup.cron"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("git-config"));
        assertNotNull(GrammarRegistry.shared().forFileName(".gitconfig"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("fstab"));
        assertNotNull(GrammarRegistry.shared().forFileName("/etc/fstab"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("hosts"));
        // ".properties" reuses the INI grammar.
        assertNotNull(GrammarRegistry.shared().forFileName("app.properties"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("properties"));
    }

    @Test
    void debianGrammarsLoad() {
        // In-house deb822 / apt-sources / interfaces / debian-changelog grammars load end-to-end.
        assertNotNull(GrammarRegistry.shared().forLanguageName("deb822"));
        assertNotNull(GrammarRegistry.shared().forFileName("/etc/apt/sources.list.d/debian.sources"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("apt-sources"));
        assertNotNull(GrammarRegistry.shared().forFileName("/etc/apt/sources.list"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("interfaces"));
        assertNotNull(GrammarRegistry.shared().forFileName("/etc/network/interfaces"));
        assertNotNull(GrammarRegistry.shared().forLanguageName("debian-changelog"));
        assertNotNull(GrammarRegistry.shared().forFileName("/pkg/debian/changelog"));
    }

    @Test
    void fullPathRulesResolveByLocation() {
        // The marquee fix: ~/.ssh/config now highlights (matched via its .ssh ancestor).
        assertNotNull(GrammarRegistry.shared().forFileName("/home/u/.ssh/config"));
        assertNotNull(GrammarRegistry.shared().forFileName("/repo/.git/config"));
        assertNotNull(GrammarRegistry.shared().forFileName("/etc/hosts"));
        // A bare "config" / "hosts" must stay unmatched (no false-positives).
        assertNull(GrammarRegistry.shared().forFileName("config"));
        assertNull(GrammarRegistry.shared().forFileName("hosts"));
    }

    @Test
    void availableLanguageNamesListsBundledOnly() {
        Set<String> names = GrammarRegistry.shared().availableLanguageNames();
        assertTrue(names.contains("java"));
        assertTrue(names.contains("markdown"));
        assertTrue(names.contains("csharp"));
        assertFalse(names.contains("plaintext"));
    }
}
