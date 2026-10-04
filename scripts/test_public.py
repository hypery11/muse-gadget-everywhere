"""Black-box tests for accidental publication, using an isolated synthetic repository."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


class PublicContentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'scripts').mkdir()
        (self.root / 'docs/img').mkdir(parents=True)
        shutil.copy2(pathlib.Path(__file__).with_name('check_public.py'), self.root / 'scripts/check_public.py')
        self.write('docs/img/assets.json', json.dumps({'assets': []}))

    def write(self, name, content):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def check(self):
        return subprocess.run([sys.executable, 'scripts/check_public.py'], cwd=self.root, capture_output=True, text=True)

    def test_source_and_neutral_documentation_are_allowed(self):
        self.write('README.md', 'Build instructions and public API documentation.')
        self.assertEqual(self.check().returncode, 0)

    def test_force_added_credentials_cannot_bypass_ignore(self):
        self.write('.gitignore', 'pairing.json\n')
        self.write('pairing.json', '{"token":"SYNTHETIC_NOT_A_REAL_SECRET"}')
        subprocess.run(['git', 'add', '-f', 'pairing.json'], cwd=self.root, check=True)
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('pairing.json', result.stderr)
        self.assertNotIn('SYNTHETIC_NOT_A_REAL_SECRET', result.stderr)

    def test_internal_material_and_private_addresses_are_rejected(self):
        self.write('docs/internal/launch.md', 'Unpublished launch notes')
        self.write('dist/debug-notes.txt', 'Local packaging notes')
        self.write('docs/device.json', '{"host":"10.1.2.3"}')
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('docs/internal/launch.md', result.stderr)
        self.assertIn('dist/debug-notes.txt', result.stderr)
        self.assertIn('docs/device.json', result.stderr)

    def test_changed_or_unregistered_media_requires_review(self):
        self.write('docs/img/screen.png', 'synthetic original bytes')
        digest = hashlib.sha256((self.root / 'docs/img/screen.png').read_bytes()).hexdigest()
        self.write('docs/img/assets.json', json.dumps({'assets': [{'path': 'docs/img/screen.png', 'sha256': digest}]}))
        self.assertEqual(self.check().returncode, 0)
        self.write('docs/img/screen.png', 'different synthetic bytes')
        self.assertNotEqual(self.check().returncode, 0)
        self.write('docs/img/extra.png', 'unreviewed synthetic bytes')
        self.assertIn('unreviewed public media', self.check().stderr)


if __name__ == '__main__':
    unittest.main()
