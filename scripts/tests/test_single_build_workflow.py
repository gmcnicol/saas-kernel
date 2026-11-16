"""The producer builds once; images and publication consume immutable output."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SingleBuildWorkflowTests(unittest.TestCase):
    def test_only_pr_workflow_builds_the_producer_and_main_only_promotes(self):
        build = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertNotIn('  push:', build.split('jobs:', 1)[0])
        self.assertEqual(build.count('clean install'), 1)
        self.assertNotIn('-DskipTests', build)
        self.assertNotIn('\n  images:', build)
        self.assertEqual(build.count('spring-boot:build-image-no-fork'), 2)
        publish = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('python3 scripts/promote.py', publish)
        self.assertNotIn('clean verify', publish)
        self.assertNotIn('clean install', publish)
        self.assertNotIn('spring-boot:build-image', publish)
        self.assertIn('${{ steps.candidate.outputs.build_sha }}', publish)

    def test_image_conversion_never_rebuilds_or_silently_changes_tested_jars(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        marker = '      - name: Build fixture images from tested JARs without a lifecycle fork\n'
        self.assertIn(marker, workflow)
        step = workflow.split(marker, 1)[1]
        shell = textwrap.dedent(step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0])
        for mutate in (False, True):
            with self.subTest(mutate=mutate), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                for name in ('keep-in-touch-crm', 'ledgerling'):
                    jar = root / f'applications/{name}/target/{name}-0.3.3.jar'
                    jar.parent.mkdir(parents=True)
                    jar.write_bytes(b'already built and tested')
                wrapper = root / 'mvnw'
                wrapper.write_text('#!/usr/bin/env python3\nimport json,os,pathlib,sys\n'
                                   'with open("calls.jsonl","a") as out: out.write(json.dumps(sys.argv[1:])+"\\n")\n'
                                   'if os.environ["MUTATE"]=="1":\n'
                                   ' p=next(pathlib.Path("applications").glob("*/target/*.jar"));p.write_bytes(b"rebuilt")\n')
                wrapper.chmod(0o700)
                env = {**os.environ, 'VERSION': '0.3.3', 'RUNNER_TEMP': str(root), 'MUTATE': str(int(mutate))}
                result = subprocess.run(['bash', '-euo', 'pipefail', '-c', shell], cwd=root, env=env,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, not mutate, result.stdout + result.stderr)
                calls = [json.loads(line) for line in (root / 'calls.jsonl').read_text().splitlines()]
                self.assertEqual(len(calls), 2)
                for call in calls:
                    self.assertEqual(call[-1], 'spring-boot:build-image-no-fork')
                    self.assertTrue({'clean', 'compile', 'package', 'verify', 'install'}.isdisjoint(call))
                if mutate:
                    self.assertIn('FAILED', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
