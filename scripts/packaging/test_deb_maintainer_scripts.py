"""Runs the .deb maintainer scripts (packaging/linux/postinst, postrm) against a scratch root.

Both scripts address every path under $DPKG_ROOT, which dpkg leaves empty for a real install, so pointing it
at a temporary directory exercises the real code without root and without touching the machine.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
LINUX = REPO / 'packaging' / 'linux'
EXPERT = 'editora-Editora-expert.desktop'

PRIMARY_DESKTOP = '''[Desktop Entry]
Type=Application
Name=Editora
Comment=A keyboard-driven, cross-platform programmer's text editor
Exec=/opt/editora/bin/Editora %F
Icon=/opt/editora/lib/Editora.png
StartupWMClass=com.editora.App
MimeType=text/plain;
'''

# A system file as another package and the administrator left it: other editors' defaults for types
# Editora claims, a default for a type it does not claim, and a second section it must not touch.
SHARED = '''[Default Applications]
text/plain=org.gnome.TextEditor.desktop
text/html=firefox-esr.desktop
application/json=code.desktop
image/png=org.gnome.Loupe.desktop

[Added Associations]
text/plain=vim.desktop;
'''


@unittest.skipUnless(os.name == 'posix', 'the maintainer scripts are POSIX shell')
class MaintainerScriptsTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.apps = self.root / 'usr' / 'share' / 'applications'
        self.apps.mkdir(parents=True)
        self.mimeapps = self.apps / 'mimeapps.list'
        self.state = self.root / 'var' / 'lib' / 'editora' / 'mimeapps.replaced'
        self.payload(self.root / 'opt' / 'editora')

    @staticmethod
    def payload(app):
        (app / 'bin').mkdir(parents=True)
        (app / 'lib' / 'icons').mkdir(parents=True)
        (app / 'bin' / 'Editora').write_text('#!/bin/sh\n')
        (app / 'bin' / 'Editora').chmod(0o755)
        (app / 'lib' / 'editora-Editora.desktop').write_text(PRIMARY_DESKTOP)
        (app / 'lib' / 'com.editora.Editora.metainfo.xml').write_text('<component/>\n')
        (app / 'lib' / 'icons' / 'editora-48.png').write_bytes(b'png')

    def run_script(self, name, action):
        result = subprocess.run(['sh', str(LINUX / name), action],
                                env={'DPKG_ROOT': str(self.root), 'PATH': os.environ['PATH']},
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result

    def defaults(self):
        """The [Default Applications] section as a dict."""
        found, inside = {}, False
        for line in self.mimeapps.read_text().splitlines():
            if line.startswith('['):
                inside = line == '[Default Applications]'
            elif inside and '=' in line:
                key, value = line.split('=', 1)
                self.assertNotIn(key, found, f'duplicate default for {key}')
                found[key] = value
        return found

    def leftovers(self):
        return [p.name for p in self.apps.iterdir() if p.name.startswith('.mimeapps')]

    def test_install_registers_the_command_the_entries_and_the_expert_defaults(self):
        self.run_script('postinst', 'configure')
        self.assertEqual(os.readlink(self.root / 'usr' / 'bin' / 'editora'), '/opt/editora/bin/Editora')
        self.assertTrue((self.apps / 'editora-Editora.desktop').is_file())
        expert = (self.apps / EXPERT).read_text()
        self.assertIn('Exec=/opt/editora/bin/Editora --expert --single-window --no-session %F', expert)
        self.assertNotIn('StartupWMClass', expert)
        self.assertTrue((self.root / 'usr/share/metainfo/com.editora.Editora.metainfo.xml').is_file())
        self.assertTrue((self.root / 'usr/share/icons/hicolor/48x48/apps/editora.png').is_file())
        defaults = self.defaults()
        self.assertEqual(defaults['text/plain'], EXPERT)
        self.assertEqual(defaults['application/json'], EXPERT)
        self.assertEqual(len([k for k, v in defaults.items() if v == EXPERT]), 28)
        self.assertFalse(self.state.exists(), 'nothing was displaced, so nothing to remember')
        self.assertEqual(self.leftovers(), [])

    def test_other_applications_defaults_survive_an_install_and_a_removal(self):
        self.mimeapps.write_text(SHARED)
        self.run_script('postinst', 'configure')
        defaults = self.defaults()
        self.assertEqual(defaults['text/plain'], EXPERT)        # the deliberate system default
        self.assertEqual(defaults['text/html'], 'firefox-esr.desktop')
        self.assertEqual(defaults['image/png'], 'org.gnome.Loupe.desktop')
        self.assertIn('[Added Associations]\ntext/plain=vim.desktop;', self.mimeapps.read_text())
        self.assertEqual(sorted(self.state.read_text().splitlines()),
                         ['application/json=code.desktop', 'text/plain=org.gnome.TextEditor.desktop'])
        self.assertEqual(self.mimeapps.stat().st_mode & 0o777, 0o644)

        self.run_script('postrm', 'remove')
        self.assertEqual(self.defaults(), {
            'text/plain': 'org.gnome.TextEditor.desktop',
            'text/html': 'firefox-esr.desktop',
            'application/json': 'code.desktop',
            'image/png': 'org.gnome.Loupe.desktop',
        })
        self.assertIn('[Added Associations]\ntext/plain=vim.desktop;', self.mimeapps.read_text())
        self.assertNotIn(EXPERT, self.mimeapps.read_text())
        self.assertFalse(self.state.exists())
        self.assertFalse(self.state.parent.exists())
        self.assertEqual(self.mimeapps.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.leftovers(), [])

    def test_an_upgrade_keeps_the_remembered_defaults(self):
        self.mimeapps.write_text(SHARED)
        self.run_script('postinst', 'configure')
        before = self.mimeapps.read_text()
        self.run_script('postinst', 'configure')       # the upgrade: our lines are already there
        self.assertEqual(self.mimeapps.read_text(), before, 'configure must be idempotent')
        self.assertEqual(sorted(self.state.read_text().splitlines()),
                         ['application/json=code.desktop', 'text/plain=org.gnome.TextEditor.desktop'])
        self.run_script('postrm', 'remove')
        self.assertEqual(self.defaults()['text/plain'], 'org.gnome.TextEditor.desktop')

    def test_a_default_chosen_after_install_is_not_overridden_by_the_restore(self):
        self.mimeapps.write_text(SHARED)
        self.run_script('postinst', 'configure')
        # The administrator later points text/plain somewhere else, in place of our line.
        self.mimeapps.write_text(self.mimeapps.read_text().replace(f'text/plain={EXPERT}', 'text/plain=kate.desktop'))
        self.run_script('postrm', 'remove')
        defaults = self.defaults()
        self.assertEqual(defaults['text/plain'], 'kate.desktop')
        self.assertEqual(defaults['application/json'], 'code.desktop')

    def test_a_file_the_install_created_is_removed_again(self):
        self.run_script('postinst', 'configure')
        self.assertTrue(self.mimeapps.is_file())
        self.run_script('postrm', 'purge')
        self.assertFalse(self.mimeapps.exists())
        self.assertFalse(os.path.lexists(self.root / 'usr' / 'bin' / 'editora'))
        self.assertFalse((self.apps / 'editora-Editora.desktop').exists())
        self.assertFalse((self.apps / EXPERT).exists())
        self.assertFalse((self.root / 'usr/share/icons/hicolor/48x48/apps/editora.png').exists())

    def test_only_the_packages_own_install_directory_is_used(self):
        # Sorts before /opt/editora, so the old /opt/*/bin/Editora glob picked it.
        self.payload(self.root / 'opt' / 'aaa-other')
        (self.root / 'opt/aaa-other/lib/editora-Editora.desktop').write_text(
            PRIMARY_DESKTOP.replace('/opt/editora', '/opt/aaa-other'))
        self.run_script('postinst', 'configure')
        self.assertEqual(os.readlink(self.root / 'usr' / 'bin' / 'editora'), '/opt/editora/bin/Editora')
        self.assertIn('Exec=/opt/editora/bin/Editora %F', (self.apps / 'editora-Editora.desktop').read_text())

    def test_a_foreign_usr_bin_editora_is_left_alone(self):
        usr_bin = self.root / 'usr' / 'bin'
        usr_bin.mkdir(parents=True)
        for kind in ('file', 'link'):
            with self.subTest(kind=kind):
                target = usr_bin / 'editora'
                if kind == 'file':
                    target.write_text('#!/bin/sh\necho another editora\n')
                else:
                    target.symlink_to('/usr/local/libexec/another-editora')
                result = self.run_script('postinst', 'configure')
                self.assertIn('not replacing it', result.stderr)
                if kind == 'file':
                    self.assertFalse(target.is_symlink())
                    self.assertIn('another editora', target.read_text())
                else:
                    self.assertEqual(os.readlink(target), '/usr/local/libexec/another-editora')
                self.run_script('postrm', 'remove')
                self.assertTrue(os.path.lexists(target), 'postrm removed a command that is not ours')
                target.unlink()

    def test_our_own_stale_link_is_refreshed(self):
        usr_bin = self.root / 'usr' / 'bin'
        usr_bin.mkdir(parents=True)
        (usr_bin / 'editora').symlink_to('/opt/editora/bin/OldLauncher')
        self.run_script('postinst', 'configure')
        self.assertEqual(os.readlink(usr_bin / 'editora'), '/opt/editora/bin/Editora')

    def test_the_scripts_parse_under_a_posix_shell(self):
        for name in ('postinst', 'postrm', 'tarball-install.sh'):
            with self.subTest(script=name):
                check = subprocess.run(['sh', '-n', str(LINUX / name)], capture_output=True, text=True)
                self.assertEqual(check.returncode, 0, check.stderr)


if __name__ == '__main__':
    unittest.main()
