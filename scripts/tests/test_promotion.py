"""Exercise the promotion CLI with real Git/receipts and a GitHub transport double."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from test_release import RepositoryTest, release

ROOT = Path(__file__).resolve().parents[2]


class PromotionTests(RepositoryTest):
    def candidate(self):
        self.assertTrue((ROOT / 'scripts/promote.py').is_file(), 'A promotion interface is required; main must not rebuild')
        for name in ('release.py', 'promote.py'):
            self.write('scripts/' + name, (ROOT / 'scripts' / name).read_bytes())
        base = self.commit()
        self.write('README.md', 'the reviewed candidate\n')
        head = self.commit()
        tree = self.run_git('rev-parse', 'HEAD^{tree}')
        self.build_sha = self.run_git('commit-tree', tree, '-p', base, '-p', head, '-m', 'tested PR merge')
        self.run_git('checkout', '--detach', self.build_sha)
        self.stage()
        self.main_sha = self.run_git('commit-tree', tree, '-p', base, '-p', head, '-m', 'actual main merge')
        self.run_git('checkout', '--detach', self.main_sha)
        self.run_git('remote', 'add', 'origin', str(self.root))
        self.destination = self.base / 'promoted'
        self.output = self.base / 'github-output'
        self.metadata = {
            'pulls': [{'merged_at': '2026-01-01T00:02:00Z', 'merge_commit_sha': self.main_sha,
                       'base': {'ref': 'main', 'repo': {'full_name': 'example/kernel'}},
                       'head': {'sha': head, 'repo': {'full_name': 'example/kernel'}}}],
            'runs': {'workflow_runs': [{'id': 123, 'run_attempt': 1, 'event': 'pull_request',
                     'path': '.github/workflows/build.yml', 'head_sha': head,
                     'status': 'completed', 'conclusion': 'success', 'updated_at': '2026-01-01T00:01:00Z',
                     'repository': {'full_name': 'example/kernel'}, 'head_repository': {'full_name': 'example/kernel'}}]},
            'artifacts': {'artifacts': [{'id': 456, 'name': f'kernel-release-{self.build_sha}-1', 'expired': False}]},
        }
        binary = self.base / 'bin'
        binary.mkdir()
        gh = binary / 'gh'
        gh.write_text('''#!/usr/bin/env python3
import json,os,pathlib,shutil,sys
args=sys.argv[1:]
with open(os.environ['CALLS'],'a') as out: out.write(json.dumps(args)+'\\n')
if args[0]=='api':
    path=args[1]
    key='pulls' if '/pulls' in path else 'artifacts' if '/artifacts' in path else 'runs'
    print(json.dumps(json.loads(pathlib.Path(os.environ['METADATA']).read_text())[key]))
elif args[:2]==['run','download']:
    shutil.copytree(os.environ['PAYLOAD'],args[args.index('--dir')+1])
else:
    raise SystemExit('Unexpected GitHub command')
''')
        gh.chmod(0o700)
        self.environment = {**os.environ, 'GITHUB_REPOSITORY': 'example/kernel', 'GITHUB_SHA': self.main_sha,
                            'GITHUB_TOKEN': 'not-a-real-token', 'GH_TOKEN': 'not-a-real-token',
                            'GITHUB_OUTPUT': str(self.output),
                            'METADATA': str(self.base / 'metadata.json'), 'PAYLOAD': str(self.directory),
                            'CALLS': str(self.base / 'calls.jsonl'), 'PYTHONDONTWRITEBYTECODE': '1',
                            'PATH': str(binary) + os.pathsep + os.environ['PATH']}

    def promote(self):
        (self.base / 'metadata.json').write_text(json.dumps(self.metadata))
        return subprocess.run([sys.executable, str(self.root / 'scripts/promote.py'), str(self.destination)],
                              cwd=self.root, env=self.environment, capture_output=True, text=True)

    def test_promotes_exact_premerge_bytes_when_main_has_the_same_tree_but_a_different_sha(self):
        self.candidate()
        original = {path.name: path.read_bytes() for path in self.directory.iterdir()}
        for module in release.ARTIFACTS.values():
            shutil.rmtree(self.root / module / 'target')
            (self.root / module / '.flattened-pom.xml').unlink()
        result = self.promote()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotEqual(self.main_sha, self.build_sha)
        self.assertEqual(self.run_git('rev-parse', 'HEAD'), self.build_sha)
        self.assertEqual({path.name: path.read_bytes() for path in self.destination.iterdir()}, original)
        self.assertEqual(release.verify(self.root, self.destination)['sha'], self.build_sha)
        self.assertIn('build_sha=' + self.build_sha, self.output.read_text())
        self.assertNotIn('not-a-real-token', result.stdout + result.stderr)
        self.assertFalse(any((self.root / module / 'target').exists() for module in release.ARTIFACTS.values()))

    def test_changed_merge_tree_is_not_a_license_to_rebuild_or_publish(self):
        self.candidate()
        self.write('README.md', 'different source after the successful build\n')
        self.main_sha = self.commit()
        self.metadata['pulls'][0]['merge_commit_sha'] = self.main_sha
        self.environment['GITHUB_SHA'] = self.main_sha
        result = self.promote()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('source tree differs', result.stderr)
        self.assertEqual(self.run_git('rev-parse', 'HEAD'), self.main_sha)
        self.assertFalse(self.output.exists())

    def test_substituted_payload_is_rejected_and_main_checkout_is_restored(self):
        self.candidate()
        (self.directory / 'saas-kernel-taxi-generator-0.2.0-sources.jar').write_bytes(b'substituted')
        result = self.promote()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('receipt does not match', result.stderr)
        self.assertEqual(self.run_git('rev-parse', 'HEAD'), self.main_sha)
        self.assertFalse(self.output.exists())

    def test_untrusted_failed_late_missing_expired_and_wrong_attempt_candidates_fail_before_download(self):
        self.candidate()
        original = json.dumps(self.metadata)
        for scenario in ('fork-pr', 'foreign-run', 'fork-run', 'failed', 'unfinished', 'wrong-workflow',
                         'after-merge', 'expired', 'missing-artifact', 'wrong-attempt', 'no-pr'):
            with self.subTest(scenario=scenario):
                self.metadata = json.loads(original)
                run = self.metadata['runs']['workflow_runs'][0]
                if scenario == 'fork-pr': self.metadata['pulls'][0]['head']['repo']['full_name'] = 'other/fork'
                elif scenario == 'foreign-run': run['repository']['full_name'] = 'other/repo'
                elif scenario == 'fork-run': run['head_repository']['full_name'] = 'other/fork'
                elif scenario == 'failed': run['conclusion'] = 'failure'
                elif scenario == 'unfinished': run['status'] = 'in_progress'
                elif scenario == 'wrong-workflow': run['path'] = '.github/workflows/unrelated.yml'
                elif scenario == 'after-merge': run['updated_at'] = '2026-01-01T00:03:00Z'
                elif scenario == 'expired': self.metadata['artifacts']['artifacts'][0]['expired'] = True
                elif scenario == 'missing-artifact': self.metadata['artifacts']['artifacts'] = []
                elif scenario == 'wrong-attempt': run['run_attempt'] = 2
                elif scenario == 'no-pr': self.metadata['pulls'] = []
                result = self.promote()
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(self.run_git('rev-parse', 'HEAD'), self.main_sha)
                self.assertFalse(self.destination.exists())
                self.assertFalse(self.output.exists())
        calls = [json.loads(line) for line in (self.base / 'calls.jsonl').read_text().splitlines()]
        self.assertTrue(all(call[0] == 'api' for call in calls))
