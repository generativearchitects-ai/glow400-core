#!/usr/bin/env python3
"""Checks that a tag can be released: it is v<project version>, the version is not a snapshot, and every module has the same one.

Usage: check_release_tag.py [--repo DIR] TAG        (TAG is the name of the tag, for example v0.1.0)

The project version is the one in the root pom.xml. Every module must name that version as its parent's, and pcml-bom, which has
no parent, must have it as its own. Needs Python 3 and nothing else. Exit code 0: the tag can be released; 1: problems, one per
line. Nothing is written or changed.
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = "{http://maven.apache.org/POM/4.0.0}"
BOM = "pcml-bom"


def _text(element, *names):
    for name in names:
        element = None if element is None else element.find(NS + name)
    return element.text.strip() if element is not None and element.text else None


def check(tag, repo):
    """The problems found, as a list of lines; empty when the tag can be released."""
    root = ET.parse(repo / "pom.xml").getroot()
    version = _text(root, "version")
    problems = []
    if version is None:
        return ["the root pom.xml has no version"]
    if version.endswith("-SNAPSHOT"):
        problems.append(f"the project version {version} is a snapshot: set the release version before tagging")
    if tag != "v" + version:
        problems.append(f"the tag {tag} is not v{version}, the version in pom.xml")
    for module in root.find(NS + "modules"):
        name = module.text.strip()
        pom = ET.parse(repo / name / "pom.xml").getroot()
        found = _text(pom, "version") if name == BOM else _text(pom, "parent", "version")
        if found != version:
            problems.append(f"{name} has the version {found}, the project has {version}")
    return problems


def main(argv):
    parser = argparse.ArgumentParser(description="Checks that a tag can be released.")
    parser.add_argument("tag", help="the name of the tag, for example v0.1.0")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent, help="the repository root")
    args = parser.parse_args(argv)
    problems = check(args.tag, args.repo)
    for problem in problems:
        print("FAIL: " + problem)
    if problems:
        return 1
    print(f"OK: the tag {args.tag} is the version of every module, and it is not a snapshot")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
