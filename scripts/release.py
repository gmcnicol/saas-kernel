#!/usr/bin/env python3
"""Validate and stage immutable release bytes; never build, sign, or publish."""

import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from io import BytesIO

ROOT = Path(__file__).resolve().parents[1]
GROUP = "io.github.gmcnicol"
ARTIFACTS = {"saas-kernel": "kernel", "saas-kernel-taxi-generator": "taxi-generator"}
SUFFIXES = (".jar", "-sources.jar", "-javadoc.jar", ".pom")
CEDAR_BASE = "09e21d967f1da2cf2abbe1e0b0b2067b41867735"
TOOLCHAIN = "rust@sha256:3914072ca0c3b8aad871db9169a651ccfce30cf58303e5d6f2db16d1d8a7e58f"
NATIVE = "META-INF/saas-kernel/native/"
LOCK = "kernel/native/Cargo.lock"
PATCH = "kernel/native/patches/prepared-condition.patch"
MAX_FILE = 256 * 1024 * 1024
MAX_METADATA = 2 * 1024 * 1024
MAX_SIGNATURE = 64 * 1024
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SEMVER = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    require(result.returncode == 0, "Git validation failed")
    return result.stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def no_symlinks(path):
    require(not any(p.is_symlink() for p in (path, *path.parents)), "Symlinks are forbidden")


def read_file(path, limit=MAX_FILE):
    no_symlinks(path)
    require(path.is_file(), "Expected a regular file")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, "File exceeds size limit")
    return data


def pom(data):
    node = ET.fromstring(data)
    require(node.tag == "{" + NS["m"] + "}project", "Invalid Maven POM")
    return node


def field(node, name):
    return node.findtext("/".join("m:" + part for part in name.split("/")), "", NS).strip()


def semver(version):
    match = SEMVER.fullmatch(version)
    require(match is not None and "SNAPSHOT" not in version.upper(), "Expected canonical non-SNAPSHOT SemVer")
    major, minor, patch, pre, _ = match.groups()
    identifiers = pre.split(".") if pre else []
    require(all(not (p.isdigit() and len(p) > 1 and p[0] == "0") for p in identifiers),
            "Noncanonical SemVer prerelease")
    # Numeric prereleases sort before text; a final release sorts after either.
    prerelease = (1,) if not pre else (0, tuple((0, int(p)) if p.isdigit() else (1, p) for p in identifiers))
    return int(major), int(minor), int(patch), prerelease


def reactor_version(root):
    version = field(pom(read_file(root / "pom.xml", MAX_METADATA)), "version")
    semver(version)
    projects = []
    pending = [root / "pom.xml"]
    seen = set()
    while pending:
        path = pending.pop()
        require(path.is_relative_to(root) and path not in seen, "Invalid reactor module path")
        seen.add(path)
        node = pom(read_file(path, MAX_METADATA))
        projects.append(node)
        for module in node.findall("m:modules/m:module", NS):
            child = Path(os.path.abspath(path.parent / (module.text or "") / "pom.xml"))
            pending.append(child)
    own_ids = {field(node, "artifactId") for node in projects}
    for artifact, module in ARTIFACTS.items():
        require(root / module / "pom.xml" in seen, "Release artifact missing from reactor")
        node = pom(read_file(root / module / "pom.xml", MAX_METADATA))
        require(field(node, "artifactId") == artifact
                and (field(node, "groupId") or field(node, "parent/groupId")) == GROUP,
                "Original POM coordinates mismatch")
    for node in projects:
        explicit = field(node, "version")
        require(not explicit or explicit == version, "Reactor version mismatch")
        if field(node, "parent/groupId") == GROUP and field(node, "parent/artifactId") in own_ids:
            require(field(node, "parent/version") == version, "Own-parent version mismatch")
        else:
            require(bool(explicit), "Reactor project has no own version or parent")
    # Also check owned parents in tracked POMs outside the active reactor.
    for name in git(root, "ls-files", "-z", "--", "*pom.xml").decode().split("\0"):
        if name:
            node = pom(read_file(root / name, MAX_METADATA))
            if field(node, "parent/groupId") == GROUP and field(node, "parent/artifactId") in own_ids:
                require(field(node, "parent/version") == version
                        and field(node, "version") in {"", version}, "Own-parent version mismatch")
    return version


def documentation(path):
    suffix = Path(path).suffix.lower()
    return (suffix in {".md", ".rst", ".adoc"}
            or (path.startswith("docs/") and suffix in {".txt", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp"})
            or ("/" not in path and suffix == ".txt")
            or Path(path).name in {"LICENSE", "NOTICE", "README"})


def check_version(root, base):
    require(re.fullmatch(r"[0-9a-fA-F]{40}", base) is not None, "BASE_SHA must be exactly 40 hex characters")
    version = reactor_version(root)
    previous = field(pom(git(root, "show", base + ":pom.xml")), "version")
    if previous == "0.2.0-SNAPSHOT":
        require(version == "0.2.0", "Initial release must be 0.2.0")
        release = True
    else:
        current_key, previous_key = semver(version), semver(previous)
        require(current_key >= previous_key, "Release version decreased")
        changed = git(root, "diff", "--name-only", "--no-renames", "-z", base, "--")
        untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z")
        paths = (changed + untracked).decode().split("\0")
        code_changed = any(path and not documentation(path) for path in paths)
        if current_key == previous_key:
            require(version == previous and not code_changed, "Version reused for non-documentation changes")
            release = False
        else:
            release = True
    result = {"version": version, "previous": previous, "release": release}
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"version={version}\nprevious={previous}\nrelease={str(release).lower()}\n")
    return result


def checkout(root):
    require(not git(root, "status", "--porcelain=v1", "--untracked-files=all"), "Source checkout must be clean")
    return {"sha": git(root, "rev-parse", "HEAD").decode().strip(),
            "tree": git(root, "rev-parse", "HEAD^{tree}").decode().strip()}


def filenames(version):
    return {artifact + "-" + version + suffix: artifact
            for artifact in ARTIFACTS for suffix in SUFFIXES}


def flat_pom(data, artifact, version):
    node = pom(data)
    require(node.find("m:parent", NS) is None, "Consumer POM must have no parent")
    require((field(node, "groupId"), field(node, "artifactId"), field(node, "version"))
            == (GROUP, artifact, version), "Consumer POM coordinates mismatch")


def properties(data):
    result = {}
    for line in data.decode("ascii").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(separator and key not in result, "Invalid native build properties")
        result[key] = value
    return result


def native_provenance(root, runtime):
    lock_hash = digest(git(root, "show", "HEAD:" + LOCK))
    patch_hash = digest(git(root, "show", "HEAD:" + PATCH))
    require(digest(read_file(root / LOCK)) == lock_hash and digest(read_file(root / PATCH)) == patch_hash,
            "Native source differs from committed source")
    expected = {"abi": "kernel-cedar/1", "cedar.source": CEDAR_BASE + "+patch.sha256:" + patch_hash,
                "cedar.patch.sha256": patch_hash, "toolchain": TOOLCHAIN, "cargo.lock.sha256": lock_hash}
    with zipfile.ZipFile(BytesIO(runtime)) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), "Duplicate runtime JAR entries")

        def entry(name, limit=MAX_METADATA):
            info = jar.getinfo(NATIVE + name)
            mode = info.external_attr >> 16
            require(not info.is_dir() and stat.S_IFMT(mode) in {0, stat.S_IFREG} and info.file_size <= limit,
                    "Invalid native JAR entry")
            with jar.open(info) as stream:
                data = stream.read(limit + 1)
            require(len(data) <= limit, "Native entry exceeds size limit")
            return data

        require(properties(entry("build.properties")) == expected, "Native build provenance mismatch")
        for name in ("Cargo.lock", "source/Cargo.lock"):
            require(digest(entry(name)) == lock_hash, "Packaged Cargo.lock mismatch")
        require(digest(entry("source/patches/prepared-condition.patch")) == patch_hash, "Packaged Cedar patch mismatch")
        native_hash = digest(entry("linux-x86_64/libkernel_cedar.so", MAX_FILE))
        require(entry("linux-x86_64/libkernel_cedar.so.sha256").decode("ascii").strip() == native_hash,
                "Packaged native digest mismatch")
    return {**expected, "native.sha256": native_hash}


def receipt(root, version, source, payload):
    for artifact in ARTIFACTS:
        flat_pom(payload[artifact + "-" + version + ".pom"], artifact, version)
    return {"schema": 1, "version": version, **source,
            "files": {name: digest(data) for name, data in sorted(payload.items())},
            "native": native_provenance(root, payload["saas-kernel-" + version + ".jar"])}


def adoption(record):
    version, hashes, native = record["version"], record["files"], record["native"]
    values = {"runtime.version": version, "kernel.source.tip": record["sha"],
              "cedar.source": native["cedar.source"], "cedar.patch.sha256": native["cedar.patch.sha256"],
              "runtime.sha256": hashes["saas-kernel-" + version + ".jar"],
              "generator.sha256": hashes["saas-kernel-taxi-generator-" + version + ".jar"],
              "native.sha256": native["native.sha256"], "cargo.lock.sha256": native["cargo.lock.sha256"]}
    return "".join(f"{key}={value}\n" for key, value in values.items()).encode("ascii")


def checksums(payload):
    return "".join(f"{digest(data)}  {name}\n" for name, data in sorted(payload.items())).encode("ascii")


def external_directory(root, directory, new=False):
    path = Path(os.path.abspath(directory))
    no_symlinks(path)
    require(not path.is_relative_to(root.resolve()) and not path.is_relative_to((Path.home() / ".m2").resolve())
            and not {"target", "targets"}.intersection(path.parts), "Directory must be external to source, targets and ~/.m2")
    if new:
        require(not path.exists() and path.parent.is_dir(), "Destination must be new with an existing parent")
    else:
        require(path.is_dir(), "Expected payload directory")
    return path


def write_payload(directory, payload):
    directory.mkdir(mode=0o700)
    for name, data in payload.items():
        with (directory / name).open("xb") as stream:
            stream.write(data)


def stage(root, directory):
    source = checkout(root)
    version = reactor_version(root)
    directory = external_directory(root, directory, new=True)
    payload = {}
    for name, artifact in filenames(version).items():
        module = root / ARTIFACTS[artifact]
        source_path = module / ".flattened-pom.xml" if name.endswith(".pom") else module / "target" / name
        payload[name] = read_file(source_path, MAX_METADATA if name.endswith(".pom") else MAX_FILE)
    record = receipt(root, version, source, payload)
    payload["release.json"] = (json.dumps(record, indent=2, sort_keys=True) + "\n").encode()
    payload["kernel-adoption.properties"] = adoption(record)
    payload["SHA256SUMS"] = checksums(payload)
    require(checkout(root) == source, "Checkout changed during staging")
    write_payload(directory, payload)
    return record


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate receipt key")
        result[key] = value
    return result


def verify(root, directory):
    source = checkout(root)
    version = reactor_version(root)
    directory = external_directory(root, directory)
    names = filenames(version)
    require({p.name for p in directory.iterdir()} == set(names) | {"release.json", "kernel-adoption.properties", "SHA256SUMS"},
            "Unexpected or missing payload files")
    payload = {name: read_file(directory / name, MAX_METADATA if name.endswith(".pom") else MAX_FILE) for name in names}
    record_bytes = read_file(directory / "release.json", MAX_METADATA)
    record = json.loads(record_bytes, object_pairs_hook=unique_object)
    expected = receipt(root, version, source, payload)
    require(isinstance(record, dict) and type(record.get("schema")) is int and record == expected,
            "Release receipt does not match payload and checkout")
    adoption_bytes = read_file(directory / "kernel-adoption.properties", MAX_METADATA)
    require(adoption_bytes == adoption(expected), "Adoption properties mismatch")
    require(read_file(directory / "SHA256SUMS", MAX_METADATA)
            == checksums({**payload, "release.json": record_bytes, "kernel-adoption.properties": adoption_bytes}),
            "SHA256SUMS mismatch")
    require(checkout(root) == source, "Checkout changed during verification")
    return record


class HTTPSRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        target = urllib.parse.urlsplit(newurl)
        require(target.scheme == "https" and target.hostname and not target.username and not target.password,
                "Unsafe registry redirect")
        # Authorization is an unredirected header, including on same-host redirects.
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def registry_opener():
    return urllib.request.build_opener(HTTPSRedirect())


def fetch(opener, url, limit):
    actor, token = os.environ.get("GITHUB_ACTOR"), os.environ.get("GITHUB_TOKEN")
    require(actor and token, "GITHUB_ACTOR and GITHUB_TOKEN are required")
    request = urllib.request.Request(url, method="GET")
    auth = base64.b64encode((actor + ":" + token).encode()).decode("ascii")
    request.add_unredirected_header("Authorization", "Basic " + auth)
    try:
        with opener.open(request, timeout=30) as response:
            require(response.status == 200, "Unexpected registry response")
            length = response.headers.get("Content-Length")
            require(length is None or (length.isdigit() and int(length) <= limit), "Registry response exceeds size limit")
            data = response.read(limit + 1)
            require(len(data) <= limit, "Registry response exceeds size limit")
            require(length is None or len(data) == int(length), "Truncated registry response")
            return data
    except urllib.error.HTTPError as error:
        error.close()
        if error.code == 404:
            return None
        raise ReleaseError("Registry HTTP error (not absence)") from None
    except (OSError, urllib.error.URLError, ValueError, http.client.HTTPException):
        raise ReleaseError("Registry connection failed") from None


def registry_files(root, directory):
    record = verify(root, directory)
    opener = registry_opener()
    for name, artifact in filenames(record["version"]).items():
        url = ("https://maven.pkg.github.com/gmcnicol/saas-kernel/io/github/gmcnicol/"
               + artifact + "/" + record["version"] + "/" + name)
        original = fetch(opener, url, (Path(directory) / name).stat().st_size)
        require(original is None or digest(original) == record["files"][name], "Registry collision: original hash mismatch")
        yield name, original
        signature = fetch(opener, url + ".asc", MAX_SIGNATURE)
        require(signature is None or bool(signature), "Empty registry signature")
        yield name + ".asc", signature


def probe(root, directory):
    present = [data is not None for _, data in registry_files(root, directory)]
    require(all(present) or not any(present), "Registry is partial; refusing overwrite")
    return {"state": "complete" if all(present) else "empty"}


def verify_published(root, directory, download_directory):
    destination = external_directory(root, download_directory, new=True)
    require(not destination.is_relative_to(Path(directory).resolve()), "Downloads must be outside the staged payload")
    payload = dict(registry_files(root, directory))
    require(all(data is not None for data in payload.values()), "Published release is incomplete")
    write_payload(destination, payload)
    return {"state": "complete"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check-version").add_argument("base_sha")
    for name in ("stage", "verify", "probe", "verify-published"):
        command = commands.add_parser(name)
        command.add_argument("directory")
        if name == "verify-published":
            command.add_argument("download_directory")
    args = parser.parse_args(argv)
    try:
        if args.command == "check-version":
            result = check_version(ROOT, args.base_sha)
        elif args.command == "verify-published":
            result = verify_published(ROOT, args.directory, args.download_directory)
        else:
            result = {"stage": stage, "verify": verify, "probe": probe}[args.command](ROOT, args.directory)
        print(json.dumps(result, sort_keys=True))
        return 0
    except ReleaseError as error:
        print("release: " + str(error), file=sys.stderr)
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, RuntimeError):
        # Never echo exception text: inputs and network errors may contain credentials.
        print("release: invalid or unreadable release input", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
