#!/usr/bin/env python3
"""Promote a successful pre-merge release bundle; never rebuild or rewrite its bytes."""
import base64
from datetime import datetime
import json
import os
import re
import subprocess
import sys

from release import ROOT, ReleaseError, checkout, external_directory, git, require, verify


def github(*args):
    result = subprocess.run(['gh', *args], cwd=ROOT, capture_output=True)
    require(result.returncode == 0, 'GitHub metadata or artifact download failed')
    return result.stdout


def api(path):
    return json.loads(github('api', path))


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'GitHub timestamp requires a timezone')
    return result


def promote(directory):
    source = checkout(ROOT)
    repository = os.environ.get('GITHUB_REPOSITORY', '')
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository identity')
    require(source['sha'] == os.environ.get('GITHUB_SHA'), 'Checkout must be the triggering main commit')
    directory = external_directory(ROOT, directory, new=True)
    prefix = 'repos/' + repository
    pulls = api(prefix + '/commits/' + source['sha'] + '/pulls')
    merged = [pull for pull in pulls if pull.get('merged_at') and pull.get('merge_commit_sha') == source['sha']
              and pull['base']['ref'] == 'main' and pull['base']['repo']['full_name'] == repository
              and pull['head']['repo'] and pull['head']['repo']['full_name'] == repository]
    require(len(merged) == 1, 'Require one merged same-repository PR; main never rebuilds a missing candidate')
    pull = merged[0]
    head = pull['head']['sha']
    require(re.fullmatch(r'[0-9a-f]{40}', head), 'Invalid PR source identity')
    runs = api(prefix + '/actions/workflows/build.yml/runs?event=pull_request&status=success&head_sha='
               + head + '&per_page=100')['workflow_runs']
    eligible = [run for run in runs if run.get('event') == 'pull_request'
                and run.get('path') == '.github/workflows/build.yml' and run.get('head_sha') == head
                and run.get('status') == 'completed' and run.get('conclusion') == 'success'
                and run['repository']['full_name'] == repository and run['head_repository']['full_name'] == repository
                and timestamp(run['updated_at']) <= timestamp(pull['merged_at'])]
    require(eligible, 'No successful trusted build completed before merge; refusing to rebuild')
    run = max(eligible, key=lambda item: item['id'])
    require(type(run['id']) is int and run['id'] > 0 and type(run['run_attempt']) is int
            and run['run_attempt'] > 0, 'Invalid build run identity')
    artifacts = api(prefix + '/actions/runs/' + str(run['id']) + '/artifacts?per_page=100')['artifacts']
    pattern = r'kernel-release-([0-9a-f]{40})-' + str(run['run_attempt'])
    candidates = [artifact for artifact in artifacts if re.fullmatch(pattern, artifact['name'])]
    require(len(candidates) == 1 and candidates[0]['expired'] is False,
            'Require one unexpired immutable artifact from the successful build attempt')
    artifact = candidates[0]
    build_sha = re.fullmatch(pattern, artifact['name']).group(1)
    github('run', 'download', str(run['id']), '--repo', repository, '--name', artifact['name'], '--dir', str(directory))
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    require(token, 'GitHub credentials are required in the environment')
    credential = base64.b64encode(('x-access-token:' + token).encode()).decode()
    fetch_environment = {**os.environ, 'GIT_CONFIG_COUNT': '1',
                         'GIT_CONFIG_KEY_0': 'http.https://github.com/.extraheader',
                         'GIT_CONFIG_VALUE_0': 'AUTHORIZATION: basic ' + credential}
    fetched = subprocess.run(['git', 'fetch', '--no-tags', 'origin', build_sha], cwd=ROOT,
                             env=fetch_environment, capture_output=True)
    require(fetched.returncode == 0, 'Cannot fetch the exact tested source commit')
    require(git(ROOT, 'rev-parse', build_sha + '^{tree}').decode().strip() == source['tree'],
            'Tested source tree differs from merged main; refusing promotion')
    # Identical trees include the trusted scripts; retain the actual build SHA in receipts and tags.
    git(ROOT, 'switch', '--detach', build_sha)
    try:
        record = verify(ROOT, directory)
    except BaseException:
        git(ROOT, 'switch', '--detach', source['sha'])
        raise
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write('build_sha=' + build_sha + '\n')
    return {'main_sha': source['sha'], 'build_sha': build_sha, 'tree': source['tree'],
            'build_run_id': run['id'], 'artifact': artifact['name'], 'version': record['version']}


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: promote.py NEW_EXTERNAL_RELEASE_DIRECTORY')
    try:
        print(json.dumps(promote(sys.argv[1]), sort_keys=True))
    except (ReleaseError, ValueError, KeyError, TypeError, OSError) as error:
        raise SystemExit('Promotion refused: ' + str(error)) from None


if __name__ == '__main__':
    main()
