import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('check_release', Path(__file__).with_name('check-release.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

REPO = Path(__file__).resolve().parents[2]


def pom(directory, version):
    path = Path(directory) / 'pom.xml'
    path.write_text(f'<project>\n  <artifactId>editora</artifactId>\n  <version>{version}</version>\n'
                    '  <dependencies><dependency><version>9.9.9</version></dependency></dependencies>\n'
                    '</project>\n', encoding='utf-8')
    return path


def populate(root, names):
    """Lays the files out as actions/download-artifact does: one directory per uploaded artifact."""
    for name in names:
        directory = Path(root) / name.rsplit('.', 1)[0]
        directory.mkdir(parents=True, exist_ok=True)
        (directory / name).write_bytes(b'payload')


def run(*argv):
    with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()):
        code = module.main([str(a) for a in argv])
    return code, out.getvalue()


class ExpectedAssetsTest(unittest.TestCase):
    def test_the_manifest_is_the_documented_release_set(self):
        self.assertEqual(sorted(module.expected_assets('1.2.3', '1.2.3')), sorted([
            'Editora-1.2.3-linux-x64.deb',
            'Editora-1.2.3-linux-x64.rpm',
            'Editora-1.2.3-linux-x64.AppImage',
            'Editora-1.2.3-linux-x64.tar.gz',
            'Editora-1.2.3-linux-x64.jar',
            'Editora-1.2.3-linux-arm64.tar.gz',
            'Editora-1.2.3-linux-arm64.jar',
            'Editora-1.2.3-macos-x64.dmg',
            'Editora-1.2.3-macos-arm64.dmg',
            'Editora-1.2.3-windows-x64.msi',
            'Editora-1.2.3-windows-x64.jar',
        ]))

    def test_every_target_of_the_build_matrix_has_a_manifest_entry(self):
        workflow = (REPO / '.github' / 'workflows' / 'release.yml').read_text(encoding='utf-8')
        build = workflow.split('\n  native-experimental:')[0]
        targets = set(module.re.findall(r'\{ target: ([a-z0-9-]+),', build))
        self.assertEqual(targets, set(module.EXPECTED), 'release.yml build matrix and EXPECTED have drifted')

    def test_every_expected_extension_is_attached_by_jreleaser(self):
        globs = (REPO / 'jreleaser.yml').read_text(encoding='utf-8')
        for name in module.expected_assets('1.2.3', '1.2.3'):
            extension = 'tar.gz' if name.endswith('.tar.gz') else name.rsplit('.', 1)[1]
            self.assertIn(f'.{extension}', globs, f'jreleaser.yml has no glob that would attach {name}')


class AssetsCommandTest(unittest.TestCase):
    def test_a_complete_matrix_passes(self):
        with tempfile.TemporaryDirectory() as scratch:
            populate(scratch, module.expected_assets('1.2.3', '1.2.3'))
            code, out = run('assets', scratch, '1.2.3', '--pom', pom(scratch, '1.2.3'))
            self.assertEqual(code, 0, out)
            self.assertNotIn('MISSING', out)

    def test_a_failed_deb_wrap_blocks_the_release_and_names_the_file(self):
        with tempfile.TemporaryDirectory() as scratch:
            names = module.expected_assets('1.2.3', '1.2.3')
            names.remove('Editora-1.2.3-linux-x64.deb')
            names.remove('Editora-1.2.3-linux-x64.AppImage')
            populate(scratch, names)
            code, out = run('assets', scratch, '1.2.3', '--pom', pom(scratch, '1.2.3'))
            self.assertEqual(code, 1)
            self.assertIn('::error', out)
            self.assertIn('Editora-1.2.3-linux-x64.deb', out)
            self.assertIn('Editora-1.2.3-linux-x64.AppImage', out)

    def test_an_empty_file_counts_as_missing(self):
        with tempfile.TemporaryDirectory() as scratch:
            populate(scratch, module.expected_assets('1.2.3', '1.2.3'))
            (Path(scratch) / 'Editora-1.2.3-windows-x64' / 'Editora-1.2.3-windows-x64.msi').write_bytes(b'')
            self.assertEqual(module.missing_assets(scratch, '1.2.3', '1.2.3'), ['Editora-1.2.3-windows-x64.msi'])

    def test_a_dry_run_reports_but_does_not_fail(self):
        with tempfile.TemporaryDirectory() as scratch:
            code, out = run('assets', scratch, '1.2.3-SNAPSHOT', '--pom', pom(scratch, '1.2.3-SNAPSHOT'),
                            '--mode', 'report')
            self.assertEqual(code, 0)
            self.assertIn('::warning', out)

    def test_the_experimental_archives_are_never_required(self):
        with tempfile.TemporaryDirectory() as scratch:
            populate(scratch, module.expected_assets('1.2.3', '1.2.3'))
            code, out = run('assets', scratch, '1.2.3', '--pom', pom(scratch, '1.2.3'))
            self.assertEqual(code, 0)
            self.assertIn('native-experimental', out)

    def test_an_rc_built_from_a_snapshot_pom_expects_jars_named_after_the_pom(self):
        # The Stage step names installers after the tag but copies the fat jar under its Maven name.
        with tempfile.TemporaryDirectory() as scratch:
            names = module.expected_assets('1.3.0-rc1', '1.3.0-SNAPSHOT')
            self.assertIn('Editora-1.3.0-SNAPSHOT-linux-x64.jar', names)
            self.assertIn('Editora-1.3.0-rc1-linux-x64.deb', names)
            populate(scratch, names)
            code, out = run('assets', scratch, '1.3.0-rc1', '--pom', pom(scratch, '1.3.0-SNAPSHOT'))
            self.assertEqual(code, 0, out)


class VersionCommandTest(unittest.TestCase):
    def check(self, ref_type, ref_name, version):
        with tempfile.TemporaryDirectory() as scratch:
            return module.check_version(ref_type, ref_name, pom(scratch, version))

    def test_a_matching_tag_passes(self):
        self.assertEqual(self.check('tag', 'v1.2.3', '1.2.3'), [])

    def test_a_tag_cut_from_a_snapshot_pom_is_refused(self):
        problems = self.check('tag', 'v1.2.3', '1.2.3-SNAPSHOT')
        self.assertEqual(len(problems), 1)
        self.assertIn('-SNAPSHOT', problems[0])

    def test_a_tag_for_a_different_version_is_refused(self):
        self.assertEqual(len(self.check('tag', 'v1.2.4', '1.2.3')), 1)

    def test_an_rc_tag_is_not_compared_with_the_pom(self):
        self.assertEqual(self.check('tag', 'v1.3.0-rc1', '1.2.4-SNAPSHOT'), [])

    def test_a_tag_that_is_not_a_version_is_refused(self):
        for name in ('vendor-import', 'v1.2', 'v1.2.3-beta', 'v1.2.3.4', '1.2.3'):
            with self.subTest(name=name):
                self.assertEqual(len(self.check('tag', name, '1.2.3')), 1)

    def test_a_branch_dry_run_is_not_checked(self):
        self.assertEqual(self.check('branch', 'master', '1.2.3-SNAPSHOT'), [])

    def test_the_project_version_is_the_first_one_in_the_pom(self):
        with tempfile.TemporaryDirectory() as scratch:
            self.assertEqual(module.pom_version(pom(scratch, '4.5.6')), '4.5.6')
        self.assertRegex(module.pom_version(REPO / 'pom.xml'), r'^\d+\.\d+\.\d+')

    def test_the_command_exits_nonzero_on_a_mismatch(self):
        with tempfile.TemporaryDirectory() as scratch:
            code, out = run('version', 'tag', 'v9.9.9', pom(scratch, '1.2.3'))
            self.assertEqual(code, 1)
            self.assertIn('::error', out)
            code, _ = run('version', 'tag', 'v1.2.3', pom(scratch, '1.2.3'))
            self.assertEqual(code, 0)


REQUIRED = ['Build & Test (JDK 25)', 'Format (JDK 25)']


def check(name, status='completed', conclusion='success', started='2026-10-14T01:00:00Z'):
    return {'name': name, 'status': status, 'conclusion': conclusion, 'started_at': started}


class CiVerdictTest(unittest.TestCase):
    def test_every_required_check_passed(self):
        verdict, lines = module.ci_verdict([check(n) for n in REQUIRED] + [check('FX tests', conclusion='failure')],
                                           REQUIRED)
        self.assertEqual(verdict, 'pass', lines)  # an advisory lane does not decide

    def test_a_required_check_that_failed_fails(self):
        verdict, lines = module.ci_verdict([check(REQUIRED[0], conclusion='failure'), check(REQUIRED[1])], REQUIRED)
        self.assertEqual(verdict, 'fail')
        self.assertIn('FAILED', lines[0])

    def test_running_or_missing_is_pending(self):
        self.assertEqual(module.ci_verdict([check(REQUIRED[0], 'in_progress', None)], REQUIRED)[0], 'pending')
        self.assertEqual(module.ci_verdict([], REQUIRED)[0], 'pending')

    def test_a_failure_beats_a_check_still_running(self):
        runs = [check(REQUIRED[0], conclusion='cancelled'), check(REQUIRED[1], 'queued', None)]
        self.assertEqual(module.ci_verdict(runs, REQUIRED)[0], 'fail')

    def test_the_newest_run_of_a_check_decides(self):
        old_fail = check(REQUIRED[0], conclusion='failure', started='2026-10-14T01:00:00Z')
        rerun_ok = check(REQUIRED[0], started='2026-10-14T02:00:00Z')
        self.assertEqual(module.ci_verdict([rerun_ok, old_fail, check(REQUIRED[1])], REQUIRED)[0], 'pass')
        rerun_fail = check(REQUIRED[0], conclusion='failure', started='2026-10-14T03:00:00Z')
        self.assertEqual(module.ci_verdict([rerun_ok, rerun_fail, check(REQUIRED[1])], REQUIRED)[0], 'fail')

    def test_a_skipped_required_check_is_not_a_pass(self):
        runs = [check(REQUIRED[0], conclusion='skipped'), check(REQUIRED[1])]
        self.assertEqual(module.ci_verdict(runs, REQUIRED)[0], 'fail')


class FakeGitHub:
    """Answers `gh api` for the rules and check-runs endpoints; each poll gets the next state."""

    def __init__(self, required, states):
        self.required = required
        self.states = list(states)
        self.polls = 0

    def __call__(self, args):
        if args[1].endswith('/rules/branches/master'):
            return ''.join(name + '\n' for name in self.required)
        state = self.states[min(self.polls, len(self.states) - 1)]
        self.polls += 1
        return ''.join(json.dumps(run) + '\n' for run in state)


class CheckCiTest(unittest.TestCase):
    def run_check(self, gh, mode='enforce', wait=30):
        now = [0.0]
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = module.check_ci('o/r', 'abc123', mode, wait, gh=gh,
                                   sleep=lambda s: now.__setitem__(0, now[0] + s), clock=lambda: now[0])
        return code, out.getvalue()

    def test_it_waits_for_ci_still_running_on_the_commit(self):
        gh = FakeGitHub(REQUIRED, [[], [check(REQUIRED[0], 'in_progress', None)], [check(n) for n in REQUIRED]])
        code, out = self.run_check(gh)
        self.assertEqual(code, 0, out)
        self.assertEqual(gh.polls, 3)

    def test_a_failed_check_stops_the_release_at_once(self):
        gh = FakeGitHub(REQUIRED, [[check(REQUIRED[0], conclusion='failure'), check(REQUIRED[1], 'queued', None)]])
        code, out = self.run_check(gh)
        self.assertEqual(code, 1)
        self.assertEqual(gh.polls, 1)
        self.assertIn('::error', out)

    def test_ci_that_never_finishes_fails_after_the_wait(self):
        gh = FakeGitHub(REQUIRED, [[]])
        code, out = self.run_check(gh, wait=5)
        self.assertEqual(code, 1)
        self.assertEqual(gh.polls, 11)  # at 0, 30 s, ... 300 s
        self.assertIn('has not finished', out)

    def test_a_dry_run_only_reports(self):
        gh = FakeGitHub(REQUIRED, [[check(REQUIRED[0], conclusion='failure')]])
        code, out = self.run_check(gh, mode='report', wait=0)
        self.assertEqual(code, 0)
        self.assertIn('::warning', out)

    def test_no_required_checks_is_refused_rather_than_passed(self):
        code, out = self.run_check(FakeGitHub([], [[]]))
        self.assertEqual(code, 1)
        self.assertIn('requires no checks', out)


if __name__ == '__main__':
    unittest.main()
