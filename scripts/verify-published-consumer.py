#!/usr/bin/env python3
"""Prove both published coordinates with an empty Maven repository, not staged JARs."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = """<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <servers><server><id>github</id><username>${env.GITHUB_ACTOR}</username>
    <password>${env.GITHUB_TOKEN}</password></server></servers>
  <profiles><profile><id>registry-proof</id>
    <repositories><repository><id>github</id>
      <url>https://maven.pkg.github.com/gmcnicol/saas-kernel</url>
      <snapshots><enabled>false</enabled></snapshots>
    </repository></repositories>
    <pluginRepositories><pluginRepository><id>github</id>
      <url>https://maven.pkg.github.com/gmcnicol/saas-kernel</url>
      <snapshots><enabled>false</enabled></snapshots>
    </pluginRepository></pluginRepositories>
  </profile></profiles><activeProfiles><activeProfile>registry-proof</activeProfile></activeProfiles>
</settings>
"""


def prepare_consumer(destination: Path, version: str) -> tuple[Path, Path]:
    destination.mkdir(mode=0o700)
    consumer = destination / 'application'
    shutil.copytree(ROOT / 'taxi-generator/src/it/adoption', consumer,
                    ignore=shutil.ignore_patterns('target', '.git'))
    pom = consumer / 'pom.xml'
    text = pom.read_text()
    if text.count('@project.version@') != 1:
        raise ValueError('Adoption fixture must have one explicit Kernel version token')
    pom.write_text(text.replace('@project.version@', version))
    settings = destination / 'settings.xml'
    settings.write_text(SETTINGS)
    settings.chmod(0o600)
    repository = destination / 'local-repository'
    if repository.exists():
        raise ValueError('Registry consumer requires a new empty local repository')
    return consumer, repository


def main():
    os.umask(0o077)
    if len(sys.argv) != 3:
        raise SystemExit('Usage: verify-published-consumer.py RELEASE_DIRECTORY NEW_CONSUMER_DIRECTORY')
    stage, destination = map(lambda value: Path(value).resolve(), sys.argv[1:])
    if not os.environ.get('GITHUB_ACTOR') or not os.environ.get('GITHUB_TOKEN'):
        raise SystemExit('Registry credentials are required in GITHUB_ACTOR and GITHUB_TOKEN')
    subprocess.run([sys.executable, str(ROOT / 'scripts/release.py'), 'verify', str(stage)], check=True)
    receipt = json.loads((stage / 'release.json').read_text())
    version = receipt['version']
    consumer, repository = prepare_consumer(destination, version)
    subprocess.run([str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress',
                    '--settings', str(destination / 'settings.xml'), '-f', str(consumer / 'pom.xml'),
                    f'-Dmaven.repo.local={repository}', '-Dkernel.authorisation.read-evaluator=prepared',
                    '-DargLine=--enable-native-access=ALL-UNNAMED', 'clean', 'verify'], cwd=ROOT, check=True)
    sums = {}
    for line in (stage / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        sums[name.lstrip('*')] = digest
    for artifact in ('saas-kernel', 'saas-kernel-taxi-generator'):
        name = f'{artifact}-{version}.jar'
        jar = repository / 'io/github/gmcnicol' / artifact / version / name
        actual = hashlib.sha256(jar.read_bytes()).hexdigest()
        if actual != sums.get(name):
            raise SystemExit(f'Registry consumer used the wrong {artifact} bytes')
    print('Fresh registry consumer verified: generated bindings, packaged application, prepared native startup, and exact two JAR hashes.')


if __name__ == '__main__':
    main()
