#!/usr/bin/env python3
"""Checks the files that a release would publish, in the folder where a build with the release profile staged them.

Usage: check_staged_artifacts.py [--repo DIR] [--signed] STAGING

STAGING is the staging folder of the Central publishing plugin (target/central-deferred for a snapshot, target/central-staging
for a release): a Maven repository with ai/generativearchitects/slay400/<artifactId>/<version>/<files>. --repo is the
repository root (default: the parent of this script's folder). --signed also requires a .asc file for every file.

What is checked (kickoff 12), for the root pom and every module of the root pom:
  * exactly these artifacts are staged, with the project version, and nothing else (no test jar, no unknown file; the checksum
    files that the plugin adds for a release are ignored);
  * the POM, and for a module that is not a pom also the jar, the sources jar and the Javadoc jar;
  * the POM has what Maven Central requires: name, description, url, a licence, a developer, the source-control entries
    (as written in the module's own POM or, if it has none, in its parent's, which is published in the same deployment);
  * every jar has META-INF/LICENSE and META-INF/NOTICE;
  * the jar of pcml-cli is the runnable one: a Main-Class, and its dependencies inside;
  * pcml-bom lists exactly the published modules, with the project version.

Needs Python 3 and nothing else. Exit code 0: all good; 1: problems, one per line. No file is written or changed.
"""
import argparse
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

GROUP_PATH = "ai/generativearchitects/slay400"
NS = "{http://maven.apache.org/POM/4.0.0}"
ROOT = "slay400-core"
BOM = "pcml-bom"
CLI = "pcml-cli"
# Checksum files that the publishing plugin adds when it stages a release; they are its business, not a published artifact.
CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
# The classes that show that the dependencies of the command-line tool are inside its jar.
CLI_CONTENT = [
    "ai/generativearchitects/slay400/cli/Main.class",
    "com/fasterxml/jackson/core/JsonFactory.class",
    "org/snakeyaml/engine/v2/api/Load.class",
]


def pom(path):
    return ET.parse(path).getroot()


def child(element, *names):
    """The first element at the path of local names below this one, or None."""
    for name in names:
        element = None if element is None else element.find(NS + name)
    return element


def text(element, *names):
    found = child(element, *names)
    return found.text.strip() if found is not None and found.text and found.text.strip() else None


def modules_of(repo):
    """The artifactIds of the modules of the root pom, in order."""
    return [m.text.strip() for m in pom(repo / "pom.xml").find(NS + "modules")]


def project_version(repo):
    return text(pom(repo / "pom.xml"), "version")


def published_artifacts(repo):
    """The root pom and every module: what a release publishes."""
    return [ROOT] + modules_of(repo)


def metadata_problems(own, parent):
    """What Central requires in a POM; an element missing in the module's own POM is looked up in its parent's."""
    problems = []
    for label, path in [
        ("name", ("name",)),
        ("description", ("description",)),
        ("url", ("url",)),
        ("license name", ("licenses", "license", "name")),
        ("license url", ("licenses", "license", "url")),
        ("developer name", ("developers", "developer", "name")),
        ("scm connection", ("scm", "connection")),
        ("scm developerConnection", ("scm", "developerConnection")),
        ("scm url", ("scm", "url")),
    ]:
        if text(own, *path) is None and (parent is None or text(parent, *path) is None):
            problems.append(f"the POM has no {label}")
    return problems


def jar_problems(path, name, is_cli=False):
    problems = []
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            for needed in ("META-INF/LICENSE", "META-INF/NOTICE"):
                if needed not in names:
                    problems.append(f"{name} has no {needed}")
            if is_cli:
                manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace") if "META-INF/MANIFEST.MF" in names else ""
                if "Main-Class: ai.generativearchitects.slay400.cli.Main" not in manifest:
                    problems.append(f"{name} has no Main-Class in its manifest")
                for entry in CLI_CONTENT:
                    if entry not in names:
                        problems.append(f"{name} does not contain {entry}: it is not the jar with its dependencies")
    except (zipfile.BadZipFile, OSError) as e:
        problems.append(f"{name} cannot be read as a jar: {e}")
    return problems


def bom_problems(bom_pom, modules, version):
    """pcml-bom lists exactly the published modules (not itself, not the root pom), each with the project version."""
    problems = []
    managed = {}
    for dep in bom_pom.findall(f"{NS}dependencyManagement/{NS}dependencies/{NS}dependency"):
        artifact = text(dep, "artifactId")
        if text(dep, "groupId") != GROUP_PATH.replace("/", "."):
            problems.append(f"{BOM} manages {artifact} with another groupId")
        if artifact in managed:
            problems.append(f"{BOM} lists {artifact} twice")
        managed[artifact] = text(dep, "version")
        expected_type = "maven-plugin" if artifact == "pcml-maven-plugin" else None
        if text(dep, "type") != expected_type:
            problems.append(f"{BOM} lists {artifact} with type {text(dep, 'type')}, expected {expected_type}")
    wanted = [m for m in modules if m != BOM]
    for missing in sorted(set(wanted) - set(managed)):
        problems.append(f"{BOM} does not list the module {missing}")
    for extra in sorted(set(managed) - set(wanted)):
        problems.append(f"{BOM} lists {extra}, which is not a published module")
    for artifact, v in managed.items():
        if v != "${project.version}":
            problems.append(f"{BOM} gives {artifact} the version {v}, expected ${{project.version}}")
    if text(bom_pom, "version") != version:
        problems.append(f"{BOM} has the version {text(bom_pom, 'version')}, the project has {version}")
    return problems


def check(staging, repo, signed=False):
    """All the problems found, as a list of lines; empty when everything is as it should be."""
    problems = []
    version = project_version(repo)
    root_pom = pom(repo / "pom.xml")
    artifacts = published_artifacts(repo)
    group = staging / GROUP_PATH

    staged = sorted(p.name for p in group.iterdir() if p.is_dir()) if group.is_dir() else []
    for artifact in artifacts:
        if artifact not in staged:
            problems.append(f"{artifact}: not staged")
    for extra in staged:
        if extra not in artifacts:
            problems.append(f"{extra}: staged, but it is not the root pom or a module")

    for artifact in artifacts:
        folder = group / artifact / version
        if not folder.is_dir():
            if artifact in staged:
                problems.append(f"{artifact}: no folder for the version {version}")
            continue
        source_pom = repo / "pom.xml" if artifact == ROOT else repo / artifact / "pom.xml"
        module_pom = pom(source_pom)
        is_pom_packaging = artifact in (ROOT, BOM)

        expected = {f"{artifact}-{version}.pom"}
        if not is_pom_packaging:
            expected |= {f"{artifact}-{version}.jar", f"{artifact}-{version}-sources.jar", f"{artifact}-{version}-javadoc.jar"}
        present = {p.name for p in folder.iterdir()
                   if p.is_file() and not p.name.startswith("maven-metadata") and not p.name.endswith(CHECKSUM_SUFFIXES)}
        if signed:
            present_signed = {n for n in present if n.endswith(".asc")}
            present -= present_signed
            for name in sorted(expected):
                if name + ".asc" not in present_signed:
                    problems.append(f"{artifact}: {name} is not signed (no {name}.asc)")
            for name in sorted(present_signed):
                if name[: -len(".asc")] not in expected:
                    problems.append(f"{artifact}: {name} signs a file that is not published")
        for name in sorted(expected - present):
            problems.append(f"{artifact}: {name} is missing")
        for name in sorted(present - expected):
            problems.append(f"{artifact}: {name} is not expected (a test jar, or a file nobody asked for)")

        if f"{artifact}-{version}.pom" in present:
            staged_pom = pom(folder / f"{artifact}-{version}.pom")
            parent = root_pom if artifact != ROOT and child(staged_pom, "parent") is not None else None
            for problem in metadata_problems(staged_pom, parent):
                problems.append(f"{artifact}: {problem}")
            if text(staged_pom, "version") not in (version, None) or (
                    child(staged_pom, "parent") is not None and text(staged_pom, "parent", "version") != version):
                problems.append(f"{artifact}: the POM has a version other than {version}")
            if artifact == BOM:
                problems.extend(bom_problems(staged_pom, modules_of(repo), version))

        for name in sorted(expected):
            if name.endswith(".jar") and name in present:
                for problem in jar_problems(folder / name, f"{artifact}: {name}", is_cli=(artifact == CLI and name == f"{CLI}-{version}.jar")):
                    problems.append(problem)
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description="Checks the files that a release would publish.")
    parser.add_argument("staging", type=Path, help="the staging folder of the Central publishing plugin")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent, help="the repository root")
    parser.add_argument("--signed", action="store_true", help="also require a .asc file for every file")
    args = parser.parse_args(argv)
    problems = check(args.staging, args.repo, signed=args.signed)
    for problem in problems:
        print("FAIL: " + problem)
    if problems:
        print(f"{len(problems)} problem(s) in {args.staging}")
        return 1
    count = len(published_artifacts(args.repo))
    print(f"OK: {count} artifacts in {args.staging} have the files, the POM elements and the licence files that a release needs")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
