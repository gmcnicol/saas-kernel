"""Offline release-contract checks: temporary repositories, ZIPs, and HTTP doubles."""

import base64
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
import importlib.util
import http.client
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import zipfile

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


def make_pom(artifact, version=None, parent_version=None, modules=()):
    parent = (f"<parent><groupId>{release.GROUP}</groupId><artifactId>saas-kernel-reactor</artifactId>"
              f"<version>{parent_version}</version></parent>") if parent_version else ""
    own_version = f"<version>{version}</version>" if version else ""
    module_xml = "<modules>" + "".join(f"<module>{module}</module>" for module in modules) + "</modules>"
    return (f'<project xmlns="{release.NS["m"]}"><modelVersion>4.0.0</modelVersion>{parent}'
            f"<groupId>{release.GROUP}</groupId><artifactId>{artifact}</artifactId>{own_version}{module_xml}</project>").encode()


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "repo"
        self.root.mkdir()
        self.run_git("init", "-q")
        self.run_git("config", "user.email", "test@example.invalid")
        self.run_git("config", "user.name", "Release test")
        self.run_git("config", "commit.gpgsign", "false")
        self.run_git("config", "core.hooksPath", "/dev/null")
        output_environment = patch.dict(os.environ, {"GITHUB_OUTPUT": ""})
        output_environment.start()
        self.addCleanup(output_environment.stop)
        self.write(".gitignore", "target/\n.flattened-pom.xml\n")
        self.write(release.LOCK, "locked dependencies\n")
        self.write(release.PATCH, "reviewed patch\n")
        self.versions("0.2.0")
        self.commit()
        self.directory = self.base / "staged"

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.PIPE).decode().strip()

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data.encode() if isinstance(data, str) else data)

    def commit(self):
        self.run_git("add", ".")
        self.run_git("commit", "--allow-empty", "-qm", "fixture")
        return self.run_git("rev-parse", "HEAD")

    def versions(self, version, parent_version=None):
        self.version = version
        self.write("pom.xml", make_pom("saas-kernel-reactor", version, modules=release.ARTIFACTS.values()))
        for artifact, module in release.ARTIFACTS.items():
            self.write(module + "/pom.xml", make_pom(artifact, parent_version=parent_version or version))

    def native_entries(self):
        lock = (self.root / release.LOCK).read_bytes()
        patch_bytes = (self.root / release.PATCH).read_bytes()
        patch_hash = release.digest(patch_bytes)
        native = b"fake ELF bytes; tests never execute this"
        metadata = {"abi": "kernel-cedar/1", "cedar.source": release.CEDAR_BASE + "+patch.sha256:" + patch_hash,
                    "cedar.patch.sha256": patch_hash, "toolchain": release.TOOLCHAIN,
                    "cargo.lock.sha256": release.digest(lock)}
        return {"Cargo.lock": lock, "source/Cargo.lock": lock,
                "source/patches/prepared-condition.patch": patch_bytes,
                "linux-x86_64/libkernel_cedar.so": native,
                "linux-x86_64/libkernel_cedar.so.sha256": (release.digest(native) + "\n").encode(),
                "build.properties": "".join(f"{key}={value}\n" for key, value in metadata.items()).encode()}

    def build_files(self, changes=None):
        entries = self.native_entries()
        entries.update(changes or {})
        for artifact, module in release.ARTIFACTS.items():
            self.write(module + "/.flattened-pom.xml", make_pom(artifact, self.version))
            for suffix in release.SUFFIXES[:-1]:
                buffer = BytesIO()
                with zipfile.ZipFile(buffer, "w") as jar:
                    jar.writestr("example.txt", b"already built")
                    if artifact == "saas-kernel" and suffix == ".jar":
                        for name, data in entries.items():
                            jar.writestr(release.NATIVE + name, data)
                self.write(f"{module}/target/{artifact}-{self.version}{suffix}", buffer.getvalue())

    def stage(self):
        self.build_files()
        return release.stage(self.root, self.directory)

    def replace_record(self, update):
        path = self.directory / "release.json"
        record = json.loads(path.read_bytes())
        update(record)
        path.write_text(json.dumps(record))


class CompletedReleaseTests(unittest.TestCase):
    def test_compatibility_uses_completed_release_not_retired_source_version(self):
        self.assertEqual(release.completed_release_version(
            {"tag_name": "v0.2.0", "draft": False, "prerelease": False}), "0.2.0")

    def test_invalid_or_unpublished_release_cannot_be_a_compatibility_baseline(self):
        for record in (None, {}, {"tag_name": "v0.2.0", "draft": True, "prerelease": False},
                       {"tag_name": "v0.2.0", "draft": False, "prerelease": True},
                       {"tag_name": "v0.2.1-rc.1", "draft": False, "prerelease": False},
                       {"tag_name": "0.2.0", "draft": False, "prerelease": False},
                       {"tag_name": "v0.2.0-SNAPSHOT", "draft": False, "prerelease": False}):
            with self.subTest(record=record):
                with self.assertRaises(release.ReleaseError):
                    release.completed_release_version(record)


class VersionTests(RepositoryTest):
    def test_initial_snapshot_to_release_and_github_outputs(self):
        self.versions("0.2.0-SNAPSHOT")
        base = self.commit()
        self.versions("0.2.0")
        self.commit()
        output = self.base / "github-output"
        output.write_text("existing=value\n")
        with patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}):
            result = release.check_version(self.root, base)
        self.assertEqual(result, {"version": "0.2.0", "previous": "0.2.0-SNAPSHOT", "release": True})
        self.assertEqual(output.read_text(), "existing=value\nversion=0.2.0\nprevious=0.2.0-SNAPSHOT\nrelease=true\n")

    def test_remote_snapshot_baseline_to_first_release(self):
        self.versions("0.1.0-SNAPSHOT")
        base = self.commit()
        self.versions("0.2.0")
        self.commit()
        self.assertTrue(release.check_version(self.root, base)["release"])

    def test_unchanged_docs_only_is_explicitly_non_releasing(self):
        base = self.run_git("rev-parse", "HEAD")
        self.write("docs/releasing.md", "Documentation only")
        self.write("taxi-generator/src/it/adoption/README.md", "Consumer documentation only")
        self.commit()
        self.assertFalse(release.check_version(self.root, base)["release"])
        self.assertFalse(release.check_version(self.root, self.run_git("rev-parse", "HEAD"))["release"])

    def test_code_workflow_and_scripts_require_new_version(self):
        for name in ("kernel/src/Foo.java", ".github/workflows/release.yml", "scripts/release.py", "docs/run.sh",
                     "kernel/src/main/resources/help.md", "taxi-generator/src/main/resources/template.md"):
            with self.subTest(name=name):
                base = self.run_git("rev-parse", "HEAD")
                self.write(name, "changed")
                self.commit()
                with self.assertRaisesRegex(release.ReleaseError, "reused"):
                    release.check_version(self.root, base)

    def test_deletion_and_rename_of_code_are_not_docs_only(self):
        self.write("kernel/Foo.java", "code")
        base = self.commit()
        (self.root / "docs").mkdir()
        self.run_git("mv", "kernel/Foo.java", "docs/Foo.md")
        self.commit()
        with self.assertRaises(release.ReleaseError):
            release.check_version(self.root, base)

    def test_untracked_code_also_requires_a_version_increment(self):
        base = self.run_git("rev-parse", "HEAD")
        self.write("scripts/new.py", "new code")
        with self.assertRaisesRegex(release.ReleaseError, "reused"):
            release.check_version(self.root, base)

    def test_version_transitions(self):
        cases = [("0.2.0", "0.2.1", True), ("0.2.4", "0.3.0", True),
                 ("0.9.0", "1.0.0", True), ("1.2.0", "1.2.1", True),
                 ("1.2.0", "1.3.0", True), ("1.2.0", "2.0.0", True),
                 ("0.2.1", "0.2.0", False), ("1.0.0", "0.9.9", False),
                 ("0.2.0", "0.2.0+rebuilt", False),
                 ("0.2.0-rc.2", "0.2.0-rc.10", True),
                 ("0.2.0-rc.10", "0.2.0", True),
                 ("0.2.0", "0.2.0-rc.1", False),
                 ("0.2.0-SNAPSHOT", "0.3.0", False)]
        for previous, current, allowed in cases:
            with self.subTest(previous=previous, current=current):
                self.versions(previous)
                base = self.commit()
                self.versions(current)
                self.commit()
                if allowed:
                    self.assertTrue(release.check_version(self.root, base)["release"])
                else:
                    with self.assertRaises(release.ReleaseError):
                        release.check_version(self.root, base)

    def test_canonical_semver(self):
        for invalid in ("0.2.0-SNAPSHOT", "0.02.0", "v0.2.0", "0.2", "0.2.0-01", "0.2.0-", "0.2.0+", "0.2.0\n"):
            with self.subTest(version=invalid), self.assertRaises(release.ReleaseError):
                release.semver(invalid)
        for valid in ("0.2.0", "1.2.3-rc.1+build.001", "1.0.0-0", "0.0.1"):
            release.semver(valid)

    def test_exact_base_sha_only(self):
        for base in ("HEAD", "0" * 39, "a" * 41, "g" * 40, "--help", "a" * 40 + "\n"):
            with self.subTest(base=base), self.assertRaises(release.ReleaseError):
                release.check_version(self.root, base)

    def test_own_parent_and_explicit_module_version_mismatches(self):
        self.versions("0.2.0", "0.1.0")
        with self.assertRaisesRegex(release.ReleaseError, "Own-parent"):
            release.reactor_version(self.root)
        self.versions("0.2.0")
        self.write("kernel/pom.xml", make_pom("saas-kernel", "0.1.0", "0.2.0"))
        with self.assertRaisesRegex(release.ReleaseError, "Reactor version"):
            release.reactor_version(self.root)

    def test_owned_parent_outside_reactor_is_checked(self):
        self.write("fixture/pom.xml", make_pom("fixture", parent_version="0.1.0"))
        self.commit()
        with self.assertRaisesRegex(release.ReleaseError, "Own-parent"):
            release.reactor_version(self.root)

    def test_original_artifact_id_is_checked(self):
        self.write("kernel/pom.xml", make_pom("wrong-artifact", parent_version="0.2.0"))
        with self.assertRaisesRegex(release.ReleaseError, "Original POM"):
            release.reactor_version(self.root)

    def test_both_release_artifacts_must_be_in_the_reactor(self):
        self.write("pom.xml", make_pom("saas-kernel-reactor", "0.2.0", modules=["kernel"]))
        with self.assertRaisesRegex(release.ReleaseError, "missing from reactor"):
            release.reactor_version(self.root)

    def test_nested_reactor_is_checked(self):
        self.write("kernel/pom.xml", make_pom("saas-kernel", parent_version="0.2.0", modules=["nested"]))
        self.write("kernel/nested/pom.xml", make_pom("nested", parent_version="0.1.0"))
        with self.assertRaises(release.ReleaseError):
            release.reactor_version(self.root)


class StagingTests(RepositoryTest):
    def test_stage_exact_files_hashes_and_independent_adoption_receipt(self):
        record = self.stage()
        self.assertEqual(len(record["files"]), 8)
        self.assertEqual(record["schema"], 1)
        self.assertEqual(record["sha"], self.run_git("rev-parse", "HEAD"))
        self.assertEqual(record["tree"], self.run_git("rev-parse", "HEAD^{tree}"))
        self.assertEqual(release.verify(self.root, self.directory), record)
        self.assertEqual(len(list(self.directory.iterdir())), 11)
        values = release.properties((self.directory / "kernel-adoption.properties").read_bytes())
        self.assertEqual(set(values), {"runtime.version", "kernel.source.tip", "cedar.source", "cedar.patch.sha256",
                                      "runtime.sha256", "generator.sha256", "native.sha256", "cargo.lock.sha256"})
        self.assertEqual(values["kernel.source.tip"], record["sha"])
        self.assertEqual(values["cedar.patch.sha256"], release.digest((self.root / release.PATCH).read_bytes()))
        self.assertEqual(values["runtime.sha256"], record["files"]["saas-kernel-0.2.0.jar"])
        for name, hash_value in record["files"].items():
            self.assertEqual(release.digest((self.directory / name).read_bytes()), hash_value)

    def test_staging_does_not_build_or_copy_unrelated_files(self):
        self.build_files()
        self.write("kernel/target/secret.asc", "not a release file")
        self.write("kernel/target/saas-kernel-0.1.0.jar", "stale")
        with patch.object(release.subprocess, "run", wraps=subprocess.run) as run:
            release.stage(self.root, self.directory)
        self.assertTrue(all(call.args[0][0] == "git" for call in run.call_args_list))
        self.assertFalse((self.directory / "secret.asc").exists())
        self.assertFalse((self.directory / "saas-kernel-0.1.0.jar").exists())

    def test_destination_must_be_new_and_external(self):
        self.build_files()
        self.directory.mkdir()
        forbidden = [self.directory, self.root / "stage", self.root / "kernel/target/stage",
                     self.base / "target" / "stage", Path.home() / ".m2" / "stage"]
        for path in forbidden:
            with self.subTest(path=path), self.assertRaises(release.ReleaseError):
                release.stage(self.root, path)
        link = self.base / "linked"
        link.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(release.ReleaseError):
            release.stage(self.root, link / "payload")

    def test_clean_source_required_for_stage_and_verify(self):
        self.stage()
        for name in ("untracked", release.LOCK, release.PATCH):
            with self.subTest(name=name):
                original = (self.root / name).read_bytes() if (self.root / name).exists() else None
                self.write(name, "dirty")
                with self.assertRaisesRegex(release.ReleaseError, "clean"):
                    release.verify(self.root, self.directory)
                with self.assertRaisesRegex(release.ReleaseError, "clean"):
                    release.stage(self.root, self.base / "other")
                if original is None:
                    (self.root / name).unlink()
                else:
                    self.write(name, original)

    def test_missing_or_symlinked_build_input_is_rejected(self):
        self.build_files()
        jar = self.root / "kernel/target/saas-kernel-0.2.0.jar"
        data = jar.read_bytes()
        jar.unlink()
        with self.assertRaises(release.ReleaseError):
            release.stage(self.root, self.directory)
        other = self.base / "elsewhere.jar"
        other.write_bytes(data)
        jar.symlink_to(other)
        with self.assertRaises(release.ReleaseError):
            release.stage(self.root, self.directory)
        jar.unlink()
        jar.write_bytes(data)
        target = self.root / "kernel/target"
        target.rename(self.base / "build-target")
        target.symlink_to(self.base / "build-target", target_is_directory=True)
        with self.assertRaises(release.ReleaseError):
            release.stage(self.root, self.directory)

    def test_parented_or_wrong_flattened_coordinates_rejected(self):
        self.build_files()
        cases = [make_pom("saas-kernel", "0.1.0"), make_pom("wrong", "0.2.0"),
                 make_pom("saas-kernel", "0.2.0", "0.2.0"),
                 make_pom("saas-kernel", "0.2.0").replace(release.GROUP.encode(), b"wrong.group")]
        for data in cases:
            with self.subTest(data=data):
                self.write("kernel/.flattened-pom.xml", data)
                with self.assertRaises(release.ReleaseError):
                    release.stage(self.root, self.directory)
                self.assertFalse(self.directory.exists())

    def test_native_integrity_invariants(self):
        good = self.native_entries()
        metadata = good["build.properties"]
        changes = [{"Cargo.lock": b"other"}, {"source/Cargo.lock": b"other"},
                   {"source/patches/prepared-condition.patch": b"other"},
                   {"linux-x86_64/libkernel_cedar.so": b"changed binary"},
                   {"linux-x86_64/libkernel_cedar.so.sha256": b"0" * 64},
                   {"build.properties": metadata.replace(b"kernel-cedar/1", b"kernel-cedar/2")},
                   {"build.properties": metadata.replace(release.CEDAR_BASE.encode(), b"0" * 40)},
                   {"build.properties": metadata.replace(release.TOOLCHAIN.encode(), b"moving-tag")},
                   {"build.properties": metadata + b"token=must-not-leak\n"}]
        for change in changes:
            with self.subTest(change=list(change)):
                self.build_files(change)
                with self.assertRaises(release.ReleaseError):
                    release.stage(self.root, self.directory)
                self.assertFalse(self.directory.exists())

    def test_bad_zip_duplicate_entry_and_zip_symlink_rejected(self):
        self.build_files()
        runtime = self.root / "kernel/target/saas-kernel-0.2.0.jar"
        runtime.write_bytes(b"not zip")
        with self.assertRaises(zipfile.BadZipFile):
            release.stage(self.root, self.directory)
        self.build_files()
        with zipfile.ZipFile(runtime, "a") as jar:
            with self.assertWarns(UserWarning):
                jar.writestr(release.NATIVE + "Cargo.lock", b"duplicate")
        with self.assertRaisesRegex(release.ReleaseError, "Duplicate"):
            release.stage(self.root, self.directory)
        self.build_files()
        entries = self.native_entries()
        with zipfile.ZipFile(runtime, "w") as jar:
            for name, data in entries.items():
                info = zipfile.ZipInfo(release.NATIVE + name)
                if name == "Cargo.lock":
                    info.create_system = 3
                    info.external_attr = 0o120777 << 16
                jar.writestr(info, data)
        with self.assertRaises(release.ReleaseError):
            release.stage(self.root, self.directory)

    def test_payload_tampering_extra_files_and_symlinks(self):
        self.stage()
        path = self.directory / "saas-kernel-0.2.0.jar"
        original = path.read_bytes()
        path.write_bytes(original + b"changed")
        with self.assertRaises(release.ReleaseError):
            release.verify(self.root, self.directory)
        path.write_bytes(original)
        extra = self.directory / "saas-kernel-0.2.0.jar.asc"
        extra.write_text("initial payload must be unsigned")
        with self.assertRaisesRegex(release.ReleaseError, "Unexpected"):
            release.verify(self.root, self.directory)
        extra.unlink()
        path.unlink()
        outside = self.base / "runtime.jar"
        outside.write_bytes(original)
        path.symlink_to(outside)
        with self.assertRaisesRegex(release.ReleaseError, "Symlink"):
            release.verify(self.root, self.directory)

    def test_receipt_schema_source_tree_version_and_hashes_are_checked(self):
        self.stage()
        path = self.directory / "release.json"
        original = path.read_bytes()
        updates = [lambda r: r.update(schema=2), lambda r: r.update(schema=True),
                   lambda r: r.update(sha="0" * 40), lambda r: r.update(tree="0" * 40),
                   lambda r: r.update(version="0.1.0"), lambda r: r.update(extra="untrusted"),
                   lambda r: r["files"].update({"saas-kernel-0.2.0.jar": "0" * 64}),
                   lambda r: r["native"].update(abi="kernel-cedar/2")]
        for update in updates:
            with self.subTest(update=update):
                self.replace_record(update)
                with self.assertRaises(release.ReleaseError):
                    release.verify(self.root, self.directory)
                path.write_bytes(original)
        path.write_bytes(original.replace(b'"schema": 1', b'"schema": 1, "schema": 1'))
        with self.assertRaisesRegex(release.ReleaseError, "Duplicate"):
            release.verify(self.root, self.directory)

    def test_independent_adoption_and_checksum_tampering_rejected(self):
        self.stage()
        for name in ("kernel-adoption.properties", "SHA256SUMS"):
            path = self.directory / name
            original = path.read_bytes()
            path.write_bytes(original + b"tampered")
            with self.assertRaises(release.ReleaseError):
                release.verify(self.root, self.directory)
            path.write_bytes(original)

    def test_other_clean_head_or_root_version_is_not_accepted(self):
        self.stage()
        self.write("docs/new.md", "new source commit")
        self.commit()
        with self.assertRaises(release.ReleaseError):
            release.verify(self.root, self.directory)
        self.versions("0.2.1")
        self.commit()
        with self.assertRaises(release.ReleaseError):
            release.verify(self.root, self.directory)

    def test_verify_uses_staged_bytes_not_build_outputs(self):
        self.stage()
        for module in release.ARTIFACTS.values():
            for path in (self.root / module / "target").iterdir():
                path.unlink()
            (self.root / module / ".flattened-pom.xml").unlink()
        release.verify(self.root, self.directory)


class Response(BytesIO):
    def __init__(self, data, headers=None, status=200):
        super().__init__(data)
        self.headers = headers or {}
        self.status = status
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        return super().read(size)


class FakeHTTP:
    def __init__(self, payload=None):
        self.payload = payload or {}
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        self.timeout = timeout
        value = self.payload.get(request.full_url, 404)
        if isinstance(value, Exception):
            raise value
        if isinstance(value, int):
            raise urllib.error.HTTPError(request.full_url, value, "private error text", {}, BytesIO(b"private body"))
        if isinstance(value, Response):
            return value
        return Response(value)


class RegistryTests(RepositoryTest):
    def setUp(self):
        super().setUp()
        self.stage()
        self.credentials = patch.dict(os.environ, {"GITHUB_ACTOR": "test-actor", "GITHUB_TOKEN": "very-secret-token"})
        self.credentials.start()
        self.addCleanup(self.credentials.stop)

    def remote_payload(self):
        result = {}
        for name, artifact in release.filenames(self.version).items():
            url = (f"https://maven.pkg.github.com/gmcnicol/saas-kernel/io/github/gmcnicol/"
                   f"{artifact}/{self.version}/{name}")
            result[url] = (self.directory / name).read_bytes()
            result[url + ".asc"] = b"detached signature verified by workflow GPG"
        return result

    def test_empty_and_complete_require_all_sixteen_exact_gets(self):
        for payload, state in (({}, "empty"), (self.remote_payload(), "complete")):
            with self.subTest(state=state):
                http = FakeHTTP(payload)
                with patch.object(release, "registry_opener", return_value=http):
                    self.assertEqual(release.probe(self.root, self.directory), {"state": state})
                self.assertEqual(len(http.requests), 16)
                self.assertEqual({request.full_url for request in http.requests}, set(self.remote_payload()))
                self.assertTrue(all(request.get_method() == "GET" for request in http.requests))
                self.assertEqual(http.timeout, 30)
                auth = "Basic " + base64.b64encode(b"test-actor:very-secret-token").decode()
                for request in http.requests:
                    self.assertNotIn("Authorization", request.headers)
                    self.assertEqual(request.unredirected_hdrs["Authorization"], auth)

    def test_partial_original_signature_only_and_collision_fail(self):
        complete = self.remote_payload()
        url = next(iter(complete))
        cases = [{url: complete[url]}, {url + ".asc": b"signature"},
                 {**complete, url: 404}, {**complete, url + ".asc": 404},
                 {**complete, url: b"collision"}, {**complete, url + ".asc": b""}]
        for payload in cases:
            with self.subTest(keys=len(payload)), patch.object(release, "registry_opener", return_value=FakeHTTP(payload)):
                with self.assertRaises(release.ReleaseError):
                    release.probe(self.root, self.directory)

    def test_authentication_server_and_network_errors_are_never_absence(self):
        url = next(iter(self.remote_payload()))
        for value in (401, 403, 429, 500, 503, urllib.error.URLError("secret token in URL"),
                      TimeoutError("private"), http.client.BadStatusLine("very-secret-token")):
            with self.subTest(value=type(value)), patch.object(release, "registry_opener", return_value=FakeHTTP({url: value})):
                with self.assertRaises(release.ReleaseError) as caught:
                    release.probe(self.root, self.directory)
                self.assertNotIn("secret", str(caught.exception))
                self.assertNotIn("private", str(caught.exception))

    def test_credentials_required_and_never_written(self):
        http = FakeHTTP()
        with patch.dict(os.environ, {}, clear=True), patch.object(release, "registry_opener", return_value=http):
            with self.assertRaisesRegex(release.ReleaseError, "required"):
                release.probe(self.root, self.directory)
        self.assertFalse(http.requests)
        for path in self.directory.iterdir():
            self.assertNotIn(b"very-secret-token", path.read_bytes())

    def test_verify_precedes_any_network_request(self):
        (self.directory / "extra").touch()
        with patch.object(release, "registry_opener") as opener:
            with self.assertRaises(release.ReleaseError):
                release.probe(self.root, self.directory)
            with self.assertRaises(release.ReleaseError):
                release.verify_published(self.root, self.directory, self.base / "downloads")
        opener.assert_not_called()

    def test_fresh_download_has_exact_originals_and_signatures_only(self):
        payload = self.remote_payload()
        http = FakeHTTP(payload)
        downloads = self.base / "downloads"
        with patch.object(release, "registry_opener", return_value=http):
            self.assertEqual(release.verify_published(self.root, self.directory, downloads), {"state": "complete"})
        self.assertEqual(len(list(downloads.iterdir())), 16)
        for url, data in payload.items():
            self.assertEqual((downloads / url.rsplit("/", 1)[1]).read_bytes(), data)
        with patch.object(release, "registry_opener") as opener:
            with self.assertRaises(release.ReleaseError):
                release.verify_published(self.root, self.directory, downloads)
            opener.assert_not_called()

    def test_download_cannot_mutate_the_staged_payload(self):
        with patch.object(release, "registry_opener") as opener:
            with self.assertRaisesRegex(release.ReleaseError, "outside the staged"):
                release.verify_published(self.root, self.directory, self.directory / "downloads")
            opener.assert_not_called()
        release.verify(self.root, self.directory)

    def test_download_missing_or_wrong_bytes_never_produces_success_directory(self):
        complete = self.remote_payload()
        url = next(iter(complete))
        for value in (404, b"wrong bytes"):
            downloads = self.base / "downloads"
            http = FakeHTTP({**complete, url: value})
            with patch.object(release, "registry_opener", return_value=http), self.assertRaises(release.ReleaseError):
                release.verify_published(self.root, self.directory, downloads)
            self.assertFalse(downloads.exists())

    def test_bounded_responses_and_no_truncation(self):
        url = next(iter(self.remote_payload()))
        cases = [Response(b"12345"), Response(b"", {"Content-Length": "5"}),
                 Response(b"12", {"Content-Length": "3"}), Response(b"", {"Content-Length": "-1"}),
                 Response(b"", {"Content-Length": "invalid"}), Response(b"", status=206)]
        for response in cases:
            with self.subTest(headers=response.headers), self.assertRaises(release.ReleaseError):
                release.fetch(FakeHTTP({url: response}), url, 4)
            self.assertTrue(all(size == 5 for size in response.read_sizes))
        self.assertEqual(release.fetch(FakeHTTP({url: b"1234"}), url, 4), b"1234")
        complete = self.remote_payload()
        with patch.object(release, "registry_opener", return_value=FakeHTTP({**complete, url + ".asc": b"s" * (release.MAX_SIGNATURE + 1)})):
            with self.assertRaises(release.ReleaseError):
                release.probe(self.root, self.directory)

    def test_redirects_strip_auth_for_same_and_other_https_hosts(self):
        original = urllib.request.Request("https://maven.pkg.github.com/original")
        original.add_unredirected_header("Authorization", "Basic private")
        handler = release.HTTPSRedirect()
        for url in ("https://maven.pkg.github.com/elsewhere", "https://objects.example.invalid/file"):
            with self.subTest(url=url):
                redirected = handler.redirect_request(original, None, 302, "Found", {}, url)
                self.assertEqual(redirected.full_url, url)
                self.assertIsNone(redirected.get_header("Authorization"))
        for url in ("http://example.invalid/file", "file:///tmp/local", "https://user:pass@example.invalid/file"):
            with self.subTest(url=url), self.assertRaises(release.ReleaseError):
                handler.redirect_request(original, None, 302, "Found", {}, url)
        self.assertTrue(any(isinstance(handler, release.HTTPSRedirect) for handler in release.registry_opener().handlers))

    def test_cli_sanitizes_errors_and_emits_machine_readable_json(self):
        stdout, stderr = StringIO(), StringIO()
        with patch.object(release, "ROOT", self.root), redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(release.main(["verify", str(self.directory)]), 0)
        self.assertEqual(json.loads(stdout.getvalue())["version"], self.version)
        self.assertEqual(stderr.getvalue(), "")
        stdout, stderr = StringIO(), StringIO()
        with patch.object(release, "ROOT", self.root), patch.object(release, "probe", side_effect=ValueError("very-secret-token")):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                self.assertEqual(release.main(["probe", str(self.directory)]), 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("very-secret-token", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
