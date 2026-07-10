package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigFileTypeTest {

    @Test
    void dockerfileByNameAndTag() {
        assertEquals("dockerfile", ConfigFileType.resolve("Dockerfile"));
        assertEquals("dockerfile", ConfigFileType.resolve("Containerfile"));
        assertEquals("dockerfile", ConfigFileType.resolve("Dockerfile.dev"));
        assertEquals("dockerfile", ConfigFileType.resolve("/srv/app/Dockerfile"));
    }

    @Test
    void dotenvVariants() {
        assertEquals("dotenv", ConfigFileType.resolve(".env"));
        assertEquals("dotenv", ConfigFileType.resolve(".env.local"));
        assertEquals("dotenv", ConfigFileType.resolve(".env.production"));
        assertEquals("dotenv", ConfigFileType.resolve("config.env"));
        assertEquals("dotenv", ConfigFileType.resolve("/app/.env.test"));
    }

    @Test
    void sshConfigByNameAndLocation() {
        assertEquals("ssh-config", ConfigFileType.resolve("ssh_config"));
        assertEquals("ssh-config", ConfigFileType.resolve("sshd_config"));
        assertEquals("ssh-config", ConfigFileType.resolve("/home/u/.ssh/config"));
        assertEquals("ssh-config", ConfigFileType.resolve("/home/u/.ssh/config.d/work"));
        // Windows-style separators are normalized.
        assertEquals("ssh-config", ConfigFileType.resolve("C:\\Users\\u\\.ssh\\config"));
        // A bare "config" with no .ssh ancestor is NOT ssh-config.
        assertNull(ConfigFileType.resolve("config"));
        assertNull(ConfigFileType.resolve("/etc/myapp/config"));
    }

    @Test
    void gitConfigByNameAndLocation() {
        assertEquals("git-config", ConfigFileType.resolve(".gitconfig"));
        assertEquals("git-config", ConfigFileType.resolve("/etc/gitconfig"));
        assertEquals("git-config", ConfigFileType.resolve("/repo/.git/config"));
        // "config" under .git, not .ssh.
        assertEquals("git-config", ConfigFileType.resolve("/repo/.git/config"));
    }

    @Test
    void crontabAndCaddyfile() {
        assertEquals("crontab", ConfigFileType.resolve("crontab"));
        assertEquals("crontab", ConfigFileType.resolve("backup.cron"));
        assertEquals("crontab", ConfigFileType.resolve("/etc/cron.d/backup"));
        assertEquals("caddyfile", ConfigFileType.resolve("Caddyfile"));
        assertEquals("caddyfile", ConfigFileType.resolve("site.caddyfile"));
    }

    @Test
    void fstabAndHostsCollisionGuard() {
        assertEquals("fstab", ConfigFileType.resolve("fstab"));
        assertEquals("fstab", ConfigFileType.resolve("/etc/fstab"));
        assertEquals("fstab", ConfigFileType.resolve("sample.fstab"));
        assertEquals("hosts", ConfigFileType.resolve("/etc/hosts"));
        // A bare "hosts" (e.g. an Ansible inventory) must NOT be treated as /etc/hosts.
        assertNull(ConfigFileType.resolve("hosts"));
        assertNull(ConfigFileType.resolve("/project/inventory/hosts"));
    }

    @Test
    void debianFormats() {
        // deb822 — APT .sources, .dsc, debian/control, debian/copyright, apt preferences.
        assertEquals("deb822", ConfigFileType.resolve("/etc/apt/sources.list.d/debian.sources"));
        assertEquals("deb822", ConfigFileType.resolve("hello_2.10-3.dsc"));
        assertEquals("deb822", ConfigFileType.resolve("/work/pkg/debian/control"));
        assertEquals("deb822", ConfigFileType.resolve("/work/pkg/debian/copyright"));
        assertEquals("deb822", ConfigFileType.resolve("/etc/apt/preferences.d/pin"));
        // A bare "control"/"copyright" with no debian ancestor is not deb822.
        assertNull(ConfigFileType.resolve("control"));
        assertNull(ConfigFileType.resolve("/src/copyright"));

        // Classic one-line APT sources (a .list under sources.list.d, or the file itself).
        assertEquals("apt-sources", ConfigFileType.resolve("/etc/apt/sources.list"));
        assertEquals("apt-sources", ConfigFileType.resolve("/etc/apt/sources.list.d/ppa.list"));

        // ifupdown network config.
        assertEquals("interfaces", ConfigFileType.resolve("/etc/network/interfaces"));
        assertEquals("interfaces", ConfigFileType.resolve("/etc/network/interfaces.d/eth0"));
        // A bare "interfaces" elsewhere is not the network config.
        assertNull(ConfigFileType.resolve("interfaces"));

        // Debian packaging changelog — only debian/changelog, not a generic CHANGELOG.
        assertEquals("debian-changelog", ConfigFileType.resolve("/work/pkg/debian/changelog"));
        assertNull(ConfigFileType.resolve("CHANGELOG.md"));
        assertNull(ConfigFileType.resolve("/project/changelog"));
    }

    @Test
    void projectConfigFilesMapToReusableGrammars() {
        // .editorconfig is INI-style.
        assertEquals("ini", ConfigFileType.resolve(".editorconfig"));
        assertEquals("ini", ConfigFileType.resolve("/proj/.editorconfig"));
        // Ignore files share one syntax (any dotfile ending in "ignore").
        assertEquals("ignore", ConfigFileType.resolve(".gitignore"));
        assertEquals("ignore", ConfigFileType.resolve(".dockerignore"));
        assertEquals("ignore", ConfigFileType.resolve("/proj/sub/.npmignore"));
        // Maven/Gradle wrapper scripts are POSIX shell.
        assertEquals("shell", ConfigFileType.resolve("mvnw"));
        assertEquals("shell", ConfigFileType.resolve("/proj/gradlew"));
        // Shell startup / alias dotfiles (bash, zsh, ksh, POSIX sh) — extension-less, so name-matched.
        assertEquals("shell", ConfigFileType.resolve(".bashrc"));
        assertEquals("shell", ConfigFileType.resolve("/home/adl/.bash_aliases"));
        assertEquals("shell", ConfigFileType.resolve(".bash_profile"));
        assertEquals("shell", ConfigFileType.resolve(".zshrc"));
        assertEquals("shell", ConfigFileType.resolve("/home/adl/.zsh_aliases"));
        assertEquals("shell", ConfigFileType.resolve(".zshenv"));
        assertEquals("shell", ConfigFileType.resolve(".profile"));
        assertEquals("shell", ConfigFileType.resolve(".aliases"));
        assertEquals("shell", ConfigFileType.resolve(".zshrc.local"));
        // A non-shell dotfile must not be swept in.
        assertNull(ConfigFileType.resolve(".bash_history"));
        // X session startup scripts are shell too.
        assertEquals("shell", ConfigFileType.resolve("/home/adl/.xprofile"));
        assertEquals("shell", ConfigFileType.resolve(".xinitrc"));
        // Ruby DSL build files (no extension).
        assertEquals("ruby", ConfigFileType.resolve("Gemfile"));
        assertEquals("ruby", ConfigFileType.resolve("/proj/Rakefile"));
        assertEquals("ruby", ConfigFileType.resolve("Brewfile"));
        assertEquals("ruby", ConfigFileType.resolve("Podfile"));
        assertEquals("ruby", ConfigFileType.resolve("Vagrantfile"));
        // ...but a lockfile is not Ruby (left to its extension / plaintext).
        assertNull(ConfigFileType.resolve("Gemfile.lock"));
        // Jenkins pipeline is Groovy (literal + suffixed).
        assertEquals("groovy", ConfigFileType.resolve("Jenkinsfile"));
        assertEquals("groovy", ConfigFileType.resolve("Jenkinsfile.prod"));
        // .gitmodules shares .gitconfig syntax.
        assertEquals("git-config", ConfigFileType.resolve(".gitmodules"));
        // Lint/tool INI configs.
        assertEquals("ini", ConfigFileType.resolve(".npmrc"));
        assertEquals("ini", ConfigFileType.resolve("/proj/pylintrc"));
        assertEquals("ini", ConfigFileType.resolve(".flake8"));
        assertEquals("ini", ConfigFileType.resolve(".coveragerc"));
        // YAML tool configs.
        assertEquals("yaml", ConfigFileType.resolve(".clang-format"));
        assertEquals("yaml", ConfigFileType.resolve("/proj/.clang-tidy"));
        assertEquals("yaml", ConfigFileType.resolve(".condarc"));
        assertEquals("yaml", ConfigFileType.resolve(".gemrc"));
        // JS/web rc → JSON (bare forms).
        assertEquals("json", ConfigFileType.resolve(".babelrc"));
        assertEquals("json", ConfigFileType.resolve(".eslintrc"));
        assertEquals("json", ConfigFileType.resolve(".prettierrc"));
        // Eclipse project metadata is XML.
        assertEquals("xml", ConfigFileType.resolve(".classpath"));
        assertEquals("xml", ConfigFileType.resolve("/proj/.project"));
        // mvnw.cmd stays a batch file (matched by extension elsewhere, not special here).
        assertNull(ConfigFileType.resolve("mvnw.cmd"));
    }

    @Test
    void nullAndOrdinaryFilesAreNotSpecial() {
        assertNull(ConfigFileType.resolve(null));
        assertNull(ConfigFileType.resolve(""));
        assertNull(ConfigFileType.resolve("Main.java"));
        assertNull(ConfigFileType.resolve("/src/app.py"));
    }

    @Test
    void genericRcFilesDefaultToIni() {
        // Unmatched "rc" runtime-config files → INI (dotfiles and /etc forms).
        assertEquals("ini", ConfigFileType.resolve(".vimrc"));
        assertEquals("ini", ConfigFileType.resolve(".inputrc"));
        assertEquals("ini", ConfigFileType.resolve(".screenrc"));
        assertEquals("ini", ConfigFileType.resolve(".nanorc"));
        assertEquals("ini", ConfigFileType.resolve(".curlrc"));
        assertEquals("ini", ConfigFileType.resolve(".netrc"));
        assertEquals("ini", ConfigFileType.resolve("/home/me/.mailrc"));
        assertEquals("ini", ConfigFileType.resolve("/etc/inputrc"));
        // More specific rc rules above still win over the generic fallback.
        assertEquals("shell", ConfigFileType.resolve(".bashrc"));
        assertEquals("json", ConfigFileType.resolve(".babelrc"));
        assertEquals("yaml", ConfigFileType.resolve(".condarc"));
        // Ordinary files that merely contain/​end-ish with "rc" but have a real extension are untouched
        // (so the caller's extension map decides — e.g. text for .txt).
        assertNull(ConfigFileType.resolve("march.txt"));
        assertNull(ConfigFileType.resolve(".eslintrc.json")); // extension map → json
        assertNull(ConfigFileType.resolve("source.c")); // ends with "c", not "rc"
    }

    @Test
    void makefileJustfileAndGitattributesByName() {
        // Makefile — extension-less names (case-insensitive) + the "Makefile.<suffix>" form.
        assertEquals("makefile", ConfigFileType.resolve("Makefile"));
        assertEquals("makefile", ConfigFileType.resolve("makefile"));
        assertEquals("makefile", ConfigFileType.resolve("GNUmakefile"));
        assertEquals("makefile", ConfigFileType.resolve("Makefile.inc"));
        assertEquals("makefile", ConfigFileType.resolve("/proj/Makefile"));
        // justfile — bare and dotfile forms.
        assertEquals("just", ConfigFileType.resolve("justfile"));
        assertEquals("just", ConfigFileType.resolve("Justfile"));
        assertEquals("just", ConfigFileType.resolve(".justfile"));
        assertEquals("just", ConfigFileType.resolve("/proj/justfile"));
        // Git attributes — ".gitattributes" anywhere + the repo-local ".git/info/attributes".
        assertEquals("gitattributes", ConfigFileType.resolve(".gitattributes"));
        assertEquals("gitattributes", ConfigFileType.resolve("/proj/.gitattributes"));
        assertEquals("gitattributes", ConfigFileType.resolve("/repo/.git/info/attributes"));
        // A bare "attributes" outside .git must not match.
        assertNull(ConfigFileType.resolve("attributes"));
        assertNull(ConfigFileType.resolve("/etc/attributes"));
    }
}
