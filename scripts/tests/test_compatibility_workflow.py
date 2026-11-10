"""Check ABI job plumbing without rebuilding or contacting a registry."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CompatibilityWorkflowTests(unittest.TestCase):
    def test_comparison_registers_exact_previous_and_tested_current_files_without_rebuilding(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        step = workflow.split('      - name: Check binary compatibility against the last completed release\n', 1)[1]
        shell = textwrap.dedent(step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary, home, runner = (root / name for name in ('bin', 'home', 'runner'))
            for path in (binary, home, runner, root / 'scripts', root / 'kernel/target'):
                path.mkdir(parents=True)
            shutil.copyfile(ROOT / 'scripts/release.py', root / 'scripts/release.py')
            gh = binary / 'gh'
            gh.write_text('#!/usr/bin/env python3\nimport json\n'
                          'print(json.dumps({"tag_name":"v0.2.0","draft":False,"prerelease":False}))\n')
            gh.chmod(0o700)
            wrapper = root / 'mvnw'
            wrapper.write_text('#!/usr/bin/env python3\nimport json,sys\n'
                               'with open("calls.jsonl","a") as out: out.write(json.dumps(sys.argv[1:])+"\\n")\n')
            wrapper.chmod(0o700)
            previous = home / '.m2/repository/io/github/gmcnicol/saas-kernel/0.2.0'
            previous.mkdir(parents=True)
            (previous / 'saas-kernel-0.2.0.jar').write_bytes(b'published previous jar')
            (previous / 'saas-kernel-0.2.0.pom').write_bytes(b'published previous pom')
            current = root / 'kernel/target/saas-kernel-0.3.0.jar'
            current.write_bytes(b'exact tested current jar')
            (root / 'kernel/.flattened-pom.xml').write_bytes(b'exact tested current pom')
            env = {**os.environ, 'HOME': str(home), 'RUNNER_TEMP': str(runner), 'VERSION': '0.3.0',
                   'GITHUB_REPOSITORY': 'example/kernel', 'PYTHONDONTWRITEBYTECODE': '1',
                   'PATH': str(binary) + os.pathsep + os.environ['PATH']}
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', shell], cwd=root, env=env,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            calls = [json.loads(line) for line in (root / 'calls.jsonl').read_text().splitlines()]
            self.assertEqual(len(calls), 5)
            for index, kind in enumerate(('jar', 'pom')):
                self.assertIn(f'-Dartifact=io.github.gmcnicol:saas-kernel:0.2.0:{kind}', calls[index])
                self.assertIn('-Dtransitive=false', calls[index])
                self.assertEqual((runner / f'abi-previous/previous.{kind}').read_bytes(),
                                 (previous / f'saas-kernel-0.2.0.{kind}').read_bytes())
            for call in calls[2:4]:
                self.assertIn('org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file', call)
            self.assertIn(f'-Dfile={runner}/abi-previous/previous.jar', calls[2])
            self.assertIn('-Dfile=kernel/target/saas-kernel-0.3.0.jar', calls[3])
            self.assertIn('com.github.siom79.japicmp:japicmp-maven-plugin:0.27.0:cmp', calls[4])
            for call in calls:
                self.assertTrue({'clean', 'compile', 'package', 'verify', 'install'}.isdisjoint(call))
            self.assertEqual(current.read_bytes(), b'exact tested current jar')


if __name__ == '__main__':
    unittest.main()
