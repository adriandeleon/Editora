import importlib.util
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location('package_release', Path(__file__).with_name('package-release.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackageReleaseTest(unittest.TestCase):
    def test_platform_archives_include_launcher_and_adjacent_library(self):
        for target, suffix, library in (
                ('linux-x64', '', 'libawt.so'),
                ('macos-arm64', '', 'libawt.dylib'),
                ('windows-x64', '.exe', 'awt.dll')):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as scratch:
                root = Path(scratch)
                binary = root / ('editora-native' + suffix)
                binary.write_bytes(b'binary')
                (root / library).write_bytes(b'library')
                archive = module.package(binary, '0.18.6-SNAPSHOT', target, root / 'out')
                name = f'Editora-0.18.6-SNAPSHOT-{target}-native-experimental'
                if target.startswith('windows-'):
                    with zipfile.ZipFile(archive) as bundle:
                        members = set(bundle.namelist())
                        launcher = bundle.read(f'{name}/run-editora-native.cmd').decode()
                    self.assertIn('%APPDATA%', launcher)
                    self.assertIn(f'{name}/editora-native.exe', members)
                else:
                    with tarfile.open(archive) as bundle:
                        members = set(bundle.getnames())
                        launcher = bundle.extractfile(f'{name}/run-editora-native').read().decode()
                    self.assertIn('EDITORA_CONFIG_DIR', launcher)
                    self.assertIn(f'{name}/editora-native', members)
                self.assertIn(f'{name}/{library}', members)

    def test_linux_rejects_missing_shared_libraries(self):
        with tempfile.TemporaryDirectory() as scratch:
            binary = Path(scratch) / 'editora-native'
            binary.write_bytes(b'binary')
            with self.assertRaisesRegex(ValueError, 'no adjacent'):
                module.package(binary, '1.0.0', 'linux-x64', Path(scratch) / 'out')


if __name__ == '__main__':
    unittest.main()
