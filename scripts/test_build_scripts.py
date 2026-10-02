"""Tests for the shell scripts in this directory that build release artifacts or manage worktrees.

They run the real scripts against throwaway directories. Nothing is downloaded: `curl` is replaced by
a stand-in first on PATH, so the AppImage tests exercise the verify-before-run logic without the
network and without executing anything fetched from it.
"""
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent

# What the stand-in curl "downloads": a fake appimagetool that records how it was called and writes
# the output file, and a fake runtime.
FAKE_TOOL = '''#!/bin/sh
printf '%s\\n' "$@" > "$FAKE_TOOL_LOG"
for last; do :; done
ls "$4/usr/share/licenses/editora" > "$FAKE_TOOL_LOG.licenses" 2>/dev/null
echo appimage > "$last"
'''
FAKE_RUNTIME = 'runtime-bytes\n'
FAKE_CURL = '''#!/bin/sh
# curl -fsSL --retry 3 -o <dest> <url>
while [ $# -gt 0 ]; do
  case "$1" in
    -o) dest="$2"; shift ;;
    http*) url="$1" ;;
  esac
  shift
done
printf '%s\\n' "$url" >> "$FAKE_CURL_LOG"
case "$url" in
  *appimagetool*) cp "$FAKE_PAYLOADS/tool" "$dest" ;;
  *) cp "$FAKE_PAYLOADS/runtime" "$dest" ;;
esac
'''


def sha256(text):
    return hashlib.sha256(text.encode()).hexdigest()


def fake_app_image(root):
    image = root / 'aot-image' / 'Editora'
    (image / 'bin').mkdir(parents=True)
    (image / 'lib' / 'app').mkdir(parents=True)
    launcher = image / 'bin' / 'Editora'
    launcher.write_text('#!/bin/sh\n')
    launcher.chmod(0o755)
    (image / 'lib' / 'app' / 'editora.aot').write_bytes(b'cache')
    return image


@unittest.skipUnless(platform.system() == 'Linux' and platform.machine() in ('x86_64', 'aarch64')
                     and shutil.which('sha256sum'), 'build-appimage.sh only runs on the Linux release legs')
class BuildAppImageTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.image = fake_app_image(self.root)
        self.icon = self.root / 'icon.png'
        self.icon.write_bytes(b'png')
        self.shims = self.root / 'shims'
        self.payloads = self.root / 'payloads'
        self.shims.mkdir()
        self.payloads.mkdir()
        (self.shims / 'curl').write_text(FAKE_CURL)
        (self.shims / 'curl').chmod(0o755)
        (self.payloads / 'tool').write_text(FAKE_TOOL)
        (self.payloads / 'runtime').write_text(FAKE_RUNTIME)
        self.tool_log = self.root / 'tool.log'
        self.curl_log = self.root / 'curl.log'
        self.out = self.root / 'dist'

    def run_script(self, script):
        env = dict(os.environ, PATH=f'{self.shims}{os.pathsep}{os.environ["PATH"]}',
                   FAKE_PAYLOADS=str(self.payloads), FAKE_TOOL_LOG=str(self.tool_log),
                   FAKE_CURL_LOG=str(self.curl_log), TMPDIR=str(self.root))
        return subprocess.run(['bash', str(script), str(self.image), str(self.icon), str(self.out)],
                              env=env, capture_output=True, text=True)

    def test_a_download_that_does_not_match_its_pinned_hash_is_never_run(self):
        # The real script, with its real pinned hashes: the stand-in payloads cannot match them.
        result = self.run_script(SCRIPTS / 'build-appimage.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('CHECKSUM MISMATCH', result.stderr)
        self.assertFalse(self.tool_log.exists(), 'the unverified appimagetool was executed')
        self.assertFalse(list(self.out.glob('*.AppImage')) if self.out.exists() else [])

    def test_the_downloads_are_versioned_releases_not_the_rolling_one(self):
        self.run_script(SCRIPTS / 'build-appimage.sh')
        urls = self.curl_log.read_text().split()
        self.assertEqual(len(urls), 1, 'the script must stop at the first mismatch')
        self.assertRegex(urls[0], r'/AppImage/appimagetool/releases/download/\d[\d.]*/appimagetool-')
        self.assertNotIn('continuous', urls[0])

    def test_verified_downloads_build_the_image_with_the_pinned_runtime_and_the_licences(self):
        # A copy of the script whose pinned hashes are those of the stand-in payloads. It keeps the
        # scripts/ + repository-root layout, because the script finds LICENSE and NOTICE relative to itself.
        text = (SCRIPTS / 'build-appimage.sh').read_text()
        arch = platform.machine()
        block = text.split(f'  {arch})')[1].split(';;')[0]
        tool_hash = block.split('APPIMAGETOOL_SHA256="')[1].split('"')[0]
        runtime_hash = block.split('TYPE2_RUNTIME_SHA256="')[1].split('"')[0]
        text = text.replace(tool_hash, sha256(FAKE_TOOL)).replace(runtime_hash, sha256(FAKE_RUNTIME))
        copy = self.root / 'repo' / 'scripts'
        copy.mkdir(parents=True)
        (copy / 'build-appimage.sh').write_text(text)
        for document in ('LICENSE', 'NOTICE'):
            shutil.copy(REPO / document, copy.parent / document)

        result = self.run_script(copy / 'build-appimage.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = self.tool_log.read_text().splitlines()
        self.assertEqual(arguments[0], '--no-appstream')
        self.assertEqual(arguments[1], '--runtime-file')
        self.assertTrue(arguments[2].endswith(f'/runtime-{arch}'), arguments)
        self.assertTrue(arguments[3].endswith('/Editora.AppDir'), arguments)
        self.assertEqual(arguments[4], str(self.out / f'Editora-{arch}.AppImage'))
        self.assertEqual(Path(str(self.tool_log) + '.licenses').read_text().split(), ['LICENSE', 'NOTICE'])
        urls = self.curl_log.read_text().split()
        self.assertEqual(len(urls), 2)
        self.assertRegex(urls[1], r'/AppImage/type2-runtime/releases/download/\d+/runtime-')
        self.assertTrue((self.out / f'Editora-{arch}.AppImage').is_file())

    def test_an_architecture_without_pinned_hashes_is_refused(self):
        (self.shims / 'uname').write_text('#!/bin/sh\necho riscv64\n')
        (self.shims / 'uname').chmod(0o755)
        result = self.run_script(SCRIPTS / 'build-appimage.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('refusing to build', result.stderr)
        self.assertFalse(self.curl_log.exists(), 'nothing may be downloaded for an unpinned architecture')


@unittest.skipUnless(platform.system() == 'Linux', 'build-tarball.sh needs GNU tar (the Linux release legs)')
class BuildTarballTest(unittest.TestCase):
    def test_the_tarball_carries_the_image_the_installer_and_the_licences_in_a_stable_order(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            image = fake_app_image(root)
            result = subprocess.run(['bash', str(SCRIPTS / 'build-tarball.sh'), str(image), str(root / 'dist')],
                                    capture_output=True, text=True, env=dict(os.environ, TMPDIR=scratch))
            self.assertEqual(result.returncode, 0, result.stderr)
            arch = platform.machine()
            with tarfile.open(root / 'dist' / f'Editora-{arch}.tar.gz') as bundle:
                members = bundle.getmembers()
            names = [m.name for m in members]
            top = f'editora-{arch}'
            for expected in ('Editora/bin/Editora', 'Editora/lib/app/editora.aot', 'install.sh', 'README.txt',
                             'LICENSE', 'NOTICE'):
                self.assertIn(f'{top}/{expected}', names)
            self.assertEqual(names, sorted(names), 'members are written in name order, not readdir order')
            self.assertTrue(all(m.uid == 0 and m.gid == 0 for m in members), 'archive members are root-owned')
            install = next(m for m in members if m.name == f'{top}/install.sh')
            self.assertTrue(install.mode & 0o111, 'install.sh must be executable')


@unittest.skipUnless(shutil.which('git') and os.name == 'posix', 'worktree.sh needs git and bash')
class WorktreeScriptTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name).resolve()
        self.main = self.root / 'Project'
        self.main.mkdir()
        self.env = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@example.invalid',
                        GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@example.invalid',
                        GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull)
        self.git('init', '-q', '-b', 'master')
        (self.main / 'file.txt').write_text('one\n')
        self.git('add', 'file.txt')
        self.git('commit', '-q', '-m', 'first')

    def git(self, *args, cwd=None):
        return subprocess.run(['git', *args], cwd=cwd or self.main, env=self.env, check=True,
                              capture_output=True, text=True).stdout

    def worktree(self, *args, cwd=None):
        return subprocess.run(['bash', str(SCRIPTS / 'worktree.sh'), *args], cwd=cwd or self.main, env=self.env,
                              capture_output=True, text=True)

    def test_worktrees_always_go_beside_the_main_checkout(self):
        first = self.worktree('new', 'feat/one', 'HEAD')
        self.assertEqual(first.returncode, 0, first.stderr)
        one = self.root / 'Project-worktrees' / 'feat-one'
        self.assertTrue((one / 'file.txt').is_file())

        # Run from INSIDE that worktree: it used to create feat-one-worktrees/feat-two beside it.
        second = self.worktree('new', 'feat/two', 'HEAD', cwd=one)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertTrue((self.root / 'Project-worktrees' / 'feat-two' / 'file.txt').is_file())
        self.assertFalse((self.root / 'Project-worktrees' / 'feat-one-worktrees').exists())
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ['Project', 'Project-worktrees'])

    def test_rm_keeps_a_branch_with_unmerged_commits_unless_forced(self):
        self.worktree('new', 'feat/work', 'HEAD')
        tree = self.root / 'Project-worktrees' / 'feat-work'
        (tree / 'new.txt').write_text('unmerged work\n')
        self.git('add', 'new.txt', cwd=tree)
        self.git('commit', '-q', '-m', 'work', cwd=tree)

        kept = self.worktree('rm', 'feat/work')
        self.assertNotEqual(kept.returncode, 0)
        self.assertIn('NOT fully merged', kept.stderr)
        self.assertFalse(tree.exists(), 'the worktree itself is removed')
        self.assertIn('feat/work', self.git('branch', '--list', 'feat/work'), 'the unmerged branch was deleted')

        # A fresh worktree on that surviving branch, removed with --force this time.
        self.git('worktree', 'add', '-q', str(tree), 'feat/work')
        forced = self.worktree('rm', 'feat/work', '--force')
        self.assertEqual(forced.returncode, 0, forced.stderr)
        self.assertEqual(self.git('branch', '--list', 'feat/work').strip(), '')

    def test_rm_deletes_a_merged_branch(self):
        self.worktree('new', 'feat/done', 'HEAD')
        result = self.worktree('rm', 'feat/done')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git('branch', '--list', 'feat/done').strip(), '')


if __name__ == '__main__':
    unittest.main()
