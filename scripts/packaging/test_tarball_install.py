"""Runs packaging/linux/tarball-install.sh for real, against throwaway directories.

Everything a user-mode install touches hangs off $HOME, and --destdir stages a system-mode install, so
neither needs root. Root-only behaviour (the ownership fix) is observed through `id` and `chown`
stand-ins placed first on PATH.
"""
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
INSTALLER = REPO / 'packaging' / 'linux' / 'tarball-install.sh'


@unittest.skipUnless(os.name == 'posix', 'the tarball installer is a POSIX shell script')
class TarballInstallTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        # The extracted tarball: install.sh beside the Editora/ app image and the licence texts.
        self.bundle = self.root / 'editora-x86_64'
        (self.bundle / 'Editora' / 'bin').mkdir(parents=True)
        (self.bundle / 'Editora' / 'lib').mkdir()
        launcher = self.bundle / 'Editora' / 'bin' / 'Editora'
        launcher.write_text('#!/bin/sh\necho launched\n')
        launcher.chmod(0o775)
        (self.bundle / 'Editora' / 'lib' / 'Editora.png').write_bytes(b'png')
        (self.bundle / 'LICENSE').write_text('MIT\n')
        (self.bundle / 'NOTICE').write_text('notice\n')
        shutil.copy(INSTALLER, self.bundle / 'install.sh')
        self.home = self.root / 'home'
        self.home.mkdir()

    def install(self, *args, path_prefix=None):
        env = {'HOME': str(self.home), 'PATH': os.environ['PATH']}
        if path_prefix:
            env['PATH'] = f'{path_prefix}{os.pathsep}{env["PATH"]}'
        return subprocess.run(['sh', str(self.bundle / 'install.sh'), *args],
                              env=env, capture_output=True, text=True)

    def desktop_entry(self, base=None):
        base = base or self.home / '.local' / 'share'
        return (base / 'applications' / 'editora.desktop').read_text()

    def test_a_user_install_wires_the_command_the_menu_entry_and_the_licences(self):
        result = self.install('--user')
        self.assertEqual(result.returncode, 0, result.stderr)
        app = self.home / '.local' / 'editora'
        self.assertTrue((app / 'bin' / 'Editora').is_file())
        self.assertEqual((app / 'LICENSE').read_text(), 'MIT\n')
        self.assertEqual((app / 'NOTICE').read_text(), 'notice\n')
        link = self.home / '.local' / 'bin' / 'editora'
        self.assertEqual(os.readlink(link), str(app / 'bin' / 'Editora'))
        entry = self.desktop_entry()
        # No reserved character in the path, so the Exec line is exactly what it always was.
        self.assertIn(f'Exec={app}/bin/Editora %F\n', entry)
        self.assertIn(f'Icon={app}/lib/Editora.png\n', entry)

    def test_reinstalling_over_a_previous_install_and_uninstalling_work(self):
        self.assertEqual(self.install('--user').returncode, 0)
        stale = self.home / '.local' / 'editora' / 'lib' / 'stale.aot'
        stale.write_text('old')
        self.assertEqual(self.install('--user').returncode, 0)
        self.assertFalse(stale.exists(), 'a reinstall replaces the previous image')
        result = self.install('--user', '--uninstall')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.home / '.local' / 'editora').exists())
        self.assertFalse(os.path.lexists(self.home / '.local' / 'bin' / 'editora'))
        self.assertFalse((self.home / '.local' / 'share' / 'applications' / 'editora.desktop').exists())

    def test_a_prefix_holding_someone_elses_directory_is_refused(self):
        prefix = self.root / 'srv'
        foreign = prefix / 'editora'
        foreign.mkdir(parents=True)
        (foreign / 'thesis.tex').write_text('irreplaceable')
        for args in (('--user', '--prefix', str(prefix)), ('--user', '--prefix', str(prefix), '--uninstall')):
            with self.subTest(args=args):
                result = self.install(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('refusing', result.stderr)
                self.assertEqual((foreign / 'thesis.tex').read_text(), 'irreplaceable')

    def test_an_empty_directory_at_the_prefix_is_accepted(self):
        prefix = self.root / 'apps'
        (prefix / 'editora').mkdir(parents=True)
        result = self.install('--user', '--prefix', str(prefix))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((prefix / 'editora' / 'bin' / 'Editora').is_file())

    def test_a_prefix_with_reserved_characters_is_quoted_in_the_exec_line(self):
        prefix = self.root / 'My Apps $x'
        result = self.install('--user', f'--prefix={prefix}')
        self.assertEqual(result.returncode, 0, result.stderr)
        entry = self.desktop_entry()
        # Desktop Entry spec: double-quote the argument and write a literal $ as \\$ (the value's own
        # escaping layer doubles the backslash). The field code stays outside the quotes.
        expected = str(prefix / 'editora' / 'bin' / 'Editora').replace('$', '\\\\$')
        self.assertIn(f'Exec="{expected}" %F\n', entry)
        validator = shutil.which('desktop-file-validate')
        if validator:
            check = subprocess.run([validator, str(self.home / '.local/share/applications/editora.desktop')],
                                   capture_output=True, text=True)
            self.assertEqual(check.returncode, 0, check.stdout + check.stderr)
        # And GLib — what parses Exec= on a GNOME desktop — splits it back into exactly one program path.
        # (get_commandline() is the value after the string-escaping layer; shell_parse_argv is the quoting
        # layer GLib applies when it launches.)
        try:
            import gi
            gi.require_version('Gio', '2.0')
            from gi.repository import Gio, GLib
        except (ImportError, ValueError):
            return
        info = Gio.DesktopAppInfo.new_from_filename(str(self.home / '.local/share/applications/editora.desktop'))
        self.assertIsNotNone(info, 'GLib rejected the generated entry')
        _, argv = GLib.shell_parse_argv(info.get_commandline())
        self.assertEqual(argv, [str(prefix / 'editora' / 'bin' / 'Editora'), '%F'])

    def test_a_foreign_editora_command_is_not_overwritten(self):
        binary = self.home / '.local' / 'bin' / 'editora'
        binary.parent.mkdir(parents=True)
        binary.write_text('#!/bin/sh\necho some other editora\n')
        result = self.install('--user')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(binary.is_symlink())
        self.assertIn('some other editora', binary.read_text())
        self.assertIn('leaving it alone', result.stderr)

    def test_a_system_install_is_handed_to_root_and_is_not_group_or_world_writable(self):
        # Stand-ins: `id -u` says root, and `chown` records what it was asked to do.
        shims = self.root / 'shims'
        shims.mkdir()
        log = self.root / 'chown.log'
        (shims / 'id').write_text('#!/bin/sh\nif [ "$1" = "-u" ]; then echo 0; else exec /usr/bin/id "$@"; fi\n')
        (shims / 'chown').write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" >> "{log}"\n')
        for shim in shims.iterdir():
            shim.chmod(0o755)
        # The image as a user with umask 002 unpacks it: group-writable throughout.
        for path in (self.bundle / 'Editora').rglob('*'):
            path.chmod(path.stat().st_mode | stat.S_IWGRP | stat.S_IWOTH)
        stage = self.root / 'stage'
        result = self.install('--destdir', str(stage), path_prefix=shims)
        self.assertEqual(result.returncode, 0, result.stderr)
        app = stage / 'opt' / 'editora'
        self.assertEqual(log.read_text().splitlines(), [f'-R -h 0:0 {app}'])
        writable = [str(p) for p in [app, *app.rglob('*')]
                    if not p.is_symlink() and p.stat().st_mode & (stat.S_IWGRP | stat.S_IWOTH)]
        self.assertEqual(writable, [], 'group/other-writable files left in a system install')
        # Staged under --destdir, but the command and the menu entry name the real, un-prefixed paths.
        self.assertEqual(os.readlink(stage / 'usr' / 'local' / 'bin' / 'editora'), '/opt/editora/bin/Editora')
        self.assertIn('Exec=/opt/editora/bin/Editora %F\n', self.desktop_entry(stage / 'usr' / 'share'))

    def test_a_user_install_is_not_chowned(self):
        shims = self.root / 'shims'
        shims.mkdir()
        log = self.root / 'chown.log'
        (shims / 'chown').write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" >> "{log}"\n')
        (shims / 'chown').chmod(0o755)
        self.assertEqual(self.install('--user', path_prefix=shims).returncode, 0)
        self.assertFalse(log.exists())

    def test_help_prints_only_the_usage_header(self):
        result = self.install('--help')
        self.assertEqual(result.returncode, 0)
        self.assertIn('--prefix DIR', result.stdout)
        self.assertNotIn('set -eu', result.stdout)
        self.assertNotIn('PROG=', result.stdout)


if __name__ == '__main__':
    unittest.main()
